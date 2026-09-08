package com.smartledger.nativeapp
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Application
import android.os.Build
import dagger.hilt.android.HiltAndroidApp
import androidx.work.WorkManager
import com.smartledger.nativeapp.notification.NotificationProcessor
@HiltAndroidApp class SmartLedgerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(NotificationProcessor.CHANNEL, "自动记账确认", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "跨来源合并窗口结束后提醒最终记账结果"
            },
        )
        // City/country detection runs only when app opens. Remove legacy periodic location work.
        WorkManager.getInstance(this).cancelUniqueWork("travel-check")
    }
}
