package com.smartledger.nativeapp.location

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.smartledger.domain.model.*
import com.smartledger.domain.scene.TravelDetector
import com.smartledger.nativeapp.MainActivity
import com.smartledger.nativeapp.R
import java.util.Locale
import java.util.concurrent.TimeUnit

@Suppress("DEPRECATION")
class TravelCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return Result.success()
        val location = runCatching { val manager = applicationContext.getSystemService(LocationManager::class.java); manager.getProviders(true).mapNotNull(manager::getLastKnownLocation).maxByOrNull { it.time } }.getOrNull() ?: return Result.retry()
        val country = runCatching { Geocoder(applicationContext, Locale.CHINA).getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()?.countryCode }.getOrNull() ?: return Result.retry()
        val prefs = applicationContext.getSharedPreferences("travel_detector", Context.MODE_PRIVATE)
        val previous = TravelSnapshot(
            state = runCatching { TravelState.valueOf(prefs.getString("state", TravelState.NORMAL.name)!!) }.getOrDefault(TravelState.NORMAL),
            homeCountry = prefs.getString("homeCountry", "CN") ?: "CN", observedCountry = prefs.getString("country", null), changedAtEpochMillis = prefs.getLong("changedAt", 0),
        )
        val next = TravelDetector().update(previous, country, System.currentTimeMillis())
        prefs.edit().putString("state", next.state.name).putString("country", next.observedCountry).putLong("changedAt", next.changedAtEpochMillis).apply()
        if (previous.state != TravelState.TRAVELING && next.state == TravelState.TRAVELING) notifyTravel(country)
        if (previous.state == TravelState.POSSIBLE_RETURN && next.state == TravelState.NORMAL) applicationContext.getSharedPreferences("scene_context", Context.MODE_PRIVATE).edit().putBoolean("traveling", false).remove("travelLedgerId").apply()
        return Result.success()
    }
    private fun notifyTravel(country: String) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "旅行场景", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(applicationContext, 21, Intent(applicationContext, MainActivity::class.java).putExtra("openLedgers", true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(2101, NotificationCompat.Builder(applicationContext, CHANNEL).setSmallIcon(R.drawable.ic_ledger).setContentTitle("发现新的位置场景").setContentText("当前国家/地区：$country。可按需新建账本记录。").setContentIntent(open).setAutoCancel(true).build())
    }
    companion object {
        private const val CHANNEL = "travel_scene"
        fun schedule(context: Context) { WorkManager.getInstance(context).enqueueUniquePeriodicWork("travel-check", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<TravelCheckWorker>(30, TimeUnit.MINUTES).build()) }
    }
}
