package com.smartledger.nativeapp.data.settings

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore by preferencesDataStore("app_settings")
data class SourceSettings(
    val wechat: Boolean = true,
    val alipay: Boolean = true,
    val cmb: Boolean = true,
    val correlationWindowSeconds: Int = 10,
)

data class PortableAppSettings(
    val sources: SourceSettings,
    val defaultLedgerId: String,
    val displayCurrency: String,
)

@Singleton class AppSettingsStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val coverPrefix = "ledger_cover_"
    private object Keys { val wechat = booleanPreferencesKey("source_wechat"); val alipay = booleanPreferencesKey("source_alipay"); val cmb = booleanPreferencesKey("source_cmb"); val correlationWindowSeconds = intPreferencesKey("correlation_window_seconds"); val defaultLedgerId = stringPreferencesKey("default_ledger_id"); val displayCurrency = stringPreferencesKey("display_currency") }
    val sources = context.settingsDataStore.data.map { SourceSettings(it[Keys.wechat] ?: true, it[Keys.alipay] ?: true, it[Keys.cmb] ?: true, (it[Keys.correlationWindowSeconds] ?: 10).coerceIn(1, 120)) }
    val defaultLedgerId = context.settingsDataStore.data.map { it[Keys.defaultLedgerId] ?: "daily" }
    val displayCurrency = context.settingsDataStore.data.map { it[Keys.displayCurrency] ?: "CNY" }
    val ledgerCoverUris = context.settingsDataStore.data.map { preferences ->
        preferences.asMap().mapNotNull { (key, value) ->
            val name = key.name
            if (name.startsWith(coverPrefix) && value is String && value.isNotBlank()) name.removePrefix(coverPrefix) to value else null
        }.toMap()
    }
    suspend fun setSource(name: String, enabled: Boolean) = context.settingsDataStore.edit { values -> when (name) { "wechat" -> values[Keys.wechat] = enabled; "alipay" -> values[Keys.alipay] = enabled; "cmb" -> values[Keys.cmb] = enabled } }
    suspend fun setCorrelationWindowSeconds(seconds: Int) = context.settingsDataStore.edit { it[Keys.correlationWindowSeconds] = seconds.coerceIn(1, 120) }
    suspend fun setDefaultLedgerId(id: String) = context.settingsDataStore.edit { it[Keys.defaultLedgerId] = id }
    suspend fun setDisplayCurrency(currency: String) = context.settingsDataStore.edit { it[Keys.displayCurrency] = currency.uppercase() }
    suspend fun setLedgerCoverUri(id: String, uri: String) = context.settingsDataStore.edit { it[stringPreferencesKey("$coverPrefix$id")] = uri }
    suspend fun clearLedgerCoverUri(id: String) = context.settingsDataStore.edit { it.remove(stringPreferencesKey("$coverPrefix$id")) }
    suspend fun correlationWindowMillis(): Long = sources.first().correlationWindowSeconds * 1_000L
    suspend fun portableSnapshot() = PortableAppSettings(sources.first(), defaultLedgerId.first(), displayCurrency.first())
    suspend fun restorePortable(value: PortableAppSettings) = context.settingsDataStore.edit { preferences ->
        preferences[Keys.wechat] = value.sources.wechat
        preferences[Keys.alipay] = value.sources.alipay
        preferences[Keys.cmb] = value.sources.cmb
        preferences[Keys.correlationWindowSeconds] = value.sources.correlationWindowSeconds.coerceIn(1, 120)
        preferences[Keys.defaultLedgerId] = value.defaultLedgerId
        preferences[Keys.displayCurrency] = value.displayCurrency.uppercase()
    }
    suspend fun isPackageEnabled(packageName: String): Boolean = sources.first().let { values -> when (packageName) { "com.tencent.mm" -> values.wechat; "com.eg.android.AlipayGphone" -> values.alipay; "cmb.pb", "com.cmbchina.ccd.pluto.cmbActivity" -> values.cmb; else -> false } }
    suspend fun clear() = context.settingsDataStore.edit { it.clear() }
}
