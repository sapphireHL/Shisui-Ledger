package com.smartledger.nativeapp

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.smartledger.nativeapp.ui.*
import com.smartledger.nativeapp.notification.NotificationProcessor
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint class MainActivity : ComponentActivity() {
    private val viewModel: LedgerViewModel by viewModels()
    @Inject lateinit var notificationProcessor: NotificationProcessor
    private var openPending by mutableStateOf(false)
    private var openConfirmed by mutableStateOf(false)
    private var notificationRouteVersion by mutableIntStateOf(0)
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) viewModel.refreshLocationTransition() }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) openNotificationAccess() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyNotificationRoute(intent)
        setContent {
            SmartLedgerRoot(
                viewModel = viewModel,
                openPendingInitially = openPending,
                openConfirmedInitially = openConfirmed,
                notificationRouteVersion = notificationRouteVersion,
                openLedgersInitially = intent.getBooleanExtra("openLedgers", false),
                requestNotificationAccess = {
                    if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else openNotificationAccess()
                },
                requestLocation = { locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
                notificationAccessEnabled = { Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty().contains(packageName) },
            )
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyNotificationRoute(intent)
    }
    override fun onStart() {
        super.onStart()
        lifecycleScope.launch { notificationProcessor.flushDueResultNotifications() }
        viewModel.refreshExchangeRates()
    }
    private fun openNotificationAccess() = startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    private fun applyNotificationRoute(intent: Intent) {
        val pending = intent.getBooleanExtra("openPending", false)
        val confirmed = intent.getBooleanExtra("openConfirmed", false)
        if (!pending && !confirmed) return
        openPending = pending
        openConfirmed = confirmed
        notificationRouteVersion++
    }
}
