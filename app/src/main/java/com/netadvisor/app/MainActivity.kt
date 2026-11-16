package com.netadvisor.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.netadvisor.app.ui.MainScreen
import com.netadvisor.app.ui.MainViewModel
import com.netadvisor.app.ui.theme.NetAdvisorTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as NetAdvisorApp

        setContent {
            NetAdvisorTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val lifecycleOwner = LocalLifecycleOwner.current

                var hasNotificationPermission by remember {
                    mutableStateOf(checkNotificationPermission())
                }
                var hasLocationPermission by remember {
                    mutableStateOf(checkLocationPermission())
                }
                var isIgnoringBatteryOptimizations by remember {
                    mutableStateOf(checkIgnoringBatteryOptimizations())
                }

                val notificationLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    hasNotificationPermission = granted || checkNotificationPermission()
                    viewModel.syncServiceIfEnabled()
                }

                val locationLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    hasLocationPermission = granted || checkLocationPermission()
                }

                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            hasNotificationPermission = checkNotificationPermission()
                            hasLocationPermission = checkLocationPermission()
                            isIgnoringBatteryOptimizations = checkIgnoringBatteryOptimizations()
                            viewModel.syncServiceIfEnabled()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                MainScreen(
                    uiState = uiState,
                    hasNotificationPermission = hasNotificationPermission,
                    hasLocationPermission = hasLocationPermission,
                    isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations,
                    onToggleMonitoring = { enabled ->
                        if (enabled && !hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        viewModel.setMonitoringEnabled(enabled)
                    },
                    onIntervalSelected = viewModel::updateCheckInterval,
                    onThresholdSelected = viewModel::updateSpeedRatioThreshold,
                    onTestNow = viewModel::triggerManualProbe,
                    onOpenInternetPanel = {
                        val intent = app.notificationHelper.buildConnectivityPanelIntent()
                        runCatching { startActivity(intent) }
                    },
                    onRequestNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onRequestLocationPermission = {
                        locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    },
                    onOpenBatterySettings = {
                        openAppBatterySettings()
                    }
                )
            }
        }
    }

    private fun checkNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun checkLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkIgnoringBatteryOptimizations(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun openAppBatterySettings() {
        val detailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        if (packageManager.resolveActivity(detailsIntent, 0) != null) {
            startActivity(detailsIntent)
        } else if (packageManager.resolveActivity(fallbackIntent, 0) != null) {
            startActivity(fallbackIntent)
        }
    }
}
