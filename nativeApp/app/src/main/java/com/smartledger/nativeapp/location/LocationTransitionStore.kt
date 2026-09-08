package com.smartledger.nativeapp.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.LocationManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class RecentLocation(
    val countryCode: String,
    val countryName: String,
    val city: String,
    val recordedAtEpochMillis: Long,
) {
    val displayName: String get() = listOf(city, countryName).filter { it.isNotBlank() }.distinct().joinToString("，")
    val placeKey: String get() = "${countryCode.uppercase(Locale.ROOT)}|${city.trim().lowercase(Locale.ROOT)}"
}

data class LocationTransition(val previous: RecentLocation, val current: RecentLocation)

@Singleton
@Suppress("DEPRECATION")
class LocationTransitionStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs = context.getSharedPreferences("recent_location_history", Context.MODE_PRIVATE)
    private val scenePrefs = context.getSharedPreferences("scene_context", Context.MODE_PRIVATE)
    private val _transition = MutableStateFlow<LocationTransition?>(null)
    val transition: StateFlow<LocationTransition?> = _transition.asStateFlow()
    private val _consent = MutableStateFlow(prefs.getBoolean("consent", false))
    val consent: StateFlow<Boolean> = _consent.asStateFlow()
    private val _consentPromptNeeded = MutableStateFlow(!prefs.contains("consentDecision"))
    val consentPromptNeeded: StateFlow<Boolean> = _consentPromptNeeded.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        if (!_consent.value || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            _transition.value = null
            return@withContext
        }
        val manager = context.getSystemService(LocationManager::class.java)
        val location = runCatching { manager.getProviders(true).mapNotNull(manager::getLastKnownLocation).maxByOrNull { it.time } }.getOrNull() ?: return@withContext
        val address = runCatching { Geocoder(context, Locale.CHINA).getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull() }.getOrNull() ?: return@withContext
        val sample = address.toRecentLocation() ?: return@withContext
        val history = readHistory()
        val baseline = readBaseline() ?: history.firstOrNull()
        writeHistory(sample, history)
        if (baseline == null) {
            writeBaseline(sample)
            return@withContext
        }
        if (baseline.placeKey != sample.placeKey) _transition.value = LocationTransition(baseline, sample)
    }

    fun keepCurrentLedger() {
        _transition.value?.current?.let(::writeBaseline)
        _transition.value = null
    }

    fun activateLedger(ledgerId: String) {
        scenePrefs.edit().putString("locationLedgerId", ledgerId).apply()
        _transition.value?.current?.let(::writeBaseline)
        _transition.value = null
    }

    fun activeLedgerId(): String = scenePrefs.getString("locationLedgerId", "daily") ?: "daily"

    fun setConsent(enabled: Boolean) {
        _consent.value = enabled
        _consentPromptNeeded.value = false
        if (enabled) prefs.edit().putBoolean("consent", true).putBoolean("consentDecision", true).apply()
        else {
            prefs.edit().clear().putBoolean("consent", false).putBoolean("consentDecision", true).apply()
            _transition.value = null
        }
    }

    fun clearHistory() {
        val allowed = _consent.value
        prefs.edit().clear().putBoolean("consent", allowed).putBoolean("consentDecision", true).apply()
        _transition.value = null
    }

    private fun Address.toRecentLocation(): RecentLocation? {
        val code = countryCode?.trim().orEmpty()
        val resolvedCity = listOf(locality, subAdminArea, adminArea).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
        if (code.isBlank() || resolvedCity.isBlank()) return null
        return RecentLocation(code, countryName?.trim().orEmpty(), resolvedCity, System.currentTimeMillis())
    }

    private fun writeHistory(sample: RecentLocation, old: List<RecentLocation>) {
        val cutoff = sample.recordedAtEpochMillis - RETENTION_MILLIS
        val values = listOf(sample) + old.filter { it.recordedAtEpochMillis >= cutoff && (it.placeKey != sample.placeKey || kotlin.math.abs(it.recordedAtEpochMillis - sample.recordedAtEpochMillis) > 24 * 60 * 60_000L) }
        val json = JSONArray()
        values.take(MAX_HISTORY).forEach { json.put(it.json()) }
        prefs.edit().putString("history", json.toString()).apply()
    }

    private fun readHistory(): List<RecentLocation> = runCatching {
        val json = JSONArray(prefs.getString("history", "[]"))
        (0 until json.length()).mapNotNull { json.optJSONObject(it)?.location() }
    }.getOrDefault(emptyList())

    private fun readBaseline(): RecentLocation? = prefs.getString("baseline", null)?.let { runCatching { JSONObject(it).location() }.getOrNull() }
    private fun writeBaseline(value: RecentLocation) { prefs.edit().putString("baseline", value.json().toString()).apply() }

    private fun RecentLocation.json() = JSONObject()
        .put("countryCode", countryCode).put("countryName", countryName).put("city", city)
        .put("recordedAt", recordedAtEpochMillis)

    private fun JSONObject.location(): RecentLocation? {
        val code = optString("countryCode")
        val city = optString("city")
        if (code.isBlank() || city.isBlank()) return null
        return RecentLocation(code, optString("countryName"), city, optLong("recordedAt"))
    }

    companion object {
        private const val MAX_HISTORY = 100
        private const val RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1_000
    }
}
