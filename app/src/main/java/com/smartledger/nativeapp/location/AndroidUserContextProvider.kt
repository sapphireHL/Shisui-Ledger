package com.smartledger.nativeapp.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.smartledger.domain.model.*
import com.smartledger.domain.repository.UserContextProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

@Suppress("DEPRECATION")
class AndroidUserContextProvider @Inject constructor(@ApplicationContext private val context: Context) : UserContextProvider {
    override suspend fun current(): UserContext = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance()
        val day = calendar.get(Calendar.DAY_OF_WEEK)
        val prefs = context.getSharedPreferences("scene_context", Context.MODE_PRIVATE)
        val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val location = if (allowed) runCatching {
            val manager = context.getSystemService(LocationManager::class.java)
            manager.getProviders(true).mapNotNull(manager::getLastKnownLocation).maxByOrNull { it.time }
        }.getOrNull() else null
        val address = location?.let { runCatching { Geocoder(context, Locale.CHINA).getFromLocation(it.latitude, it.longitude, 1)?.firstOrNull() }.getOrNull() }
        val activeLedgerId = prefs.getString("activeLedgerId", null)
        val locationLedgerId = prefs.getString("locationLedgerId", null)
        UserContext(now, day, calendar.get(Calendar.HOUR_OF_DAY), countryCode = address?.countryCode,
            city = listOf(address?.locality, address?.subAdminArea, address?.adminArea).firstOrNull { !it.isNullOrBlank() },
            latitude = location?.latitude, longitude = location?.longitude,
            isWeekend = day == Calendar.SATURDAY || day == Calendar.SUNDAY,
            isTraveling = false,
            activeTravelLedgerId = null, activeLedgerId = activeLedgerId, locationLedgerId = locationLedgerId)
    }
}
