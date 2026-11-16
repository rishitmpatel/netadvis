package com.netadvisor.app.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.netadvisor.app.NetAdvisorApp
import com.netadvisor.app.domain.AlertEvaluator
import com.netadvisor.app.tile.NetAdvisorTileService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class NetMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val probeMutex = Mutex()

    private val app by lazy { application as NetAdvisorApp }
    private val repository by lazy { app.repository }
    private val prober by lazy { app.prober }
    private val notificationHelper by lazy { app.notificationHelper }

    private val connectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    private val powerManager by lazy {
        getSystemService(Context.POWER_SERVICE) as PowerManager
    }
    private val wifiManager by lazy {
        applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }

    private var periodicJob: Job? = null
    private var bssidDebounceJob: Job? = null
    private var activeProbeJob: Job? = null
    private var configObserverJob: Job? = null

    @Volatile
    private var isScreenInteractive: Boolean = true

    @Volatile
    private var lastProbeTimestampMs: Long = 0L

    @Volatile
    private var lastWifiSignature: String? = null

    private var trackedWifiNetworkHandle: Long? = null
    private var wifiMonitorCallback: ConnectivityManager.NetworkCallback? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> handleScreenOff()
                Intent.ACTION_SCREEN_ON -> handleScreenOn()
            }
        }
    }

    companion object {
        const val ACTION_START_OR_SYNC = "com.netadvisor.app.action.START_OR_SYNC"
        const val ACTION_TEST_NOW = "com.netadvisor.app.action.TEST_NOW"
        const val ACTION_STOP_MONITORING = "com.netadvisor.app.action.STOP_MONITORING"

        private const val BSSID_DEBOUNCE_MS = 3_000L
        private const val PLACEHOLDER_BSSID = "02:00:00:00:00:00"
        private const val ZERO_BSSID = "00:00:00:00:00:00"

        fun start(context: Context, action: String = ACTION_START_OR_SYNC) {
            val intent = Intent(context, NetMonitorService::class.java).apply {
                this.action = action
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NetMonitorService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        isScreenInteractive = powerManager.isInteractive
        registerScreenReceiver()
        registerWifiRoamCallback()
        observeConfiguration()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForegroundStarted()

        when (intent?.action) {
            ACTION_STOP_MONITORING -> {
                serviceScope.launch {
                    repository.setEnabled(false)
                    NetAdvisorTileService.requestTileRefresh(this@NetMonitorService)
                    stopMonitoringAndSelf()
                }
                return START_NOT_STICKY
            }

            ACTION_TEST_NOW -> {
                serviceScope.launch {
                    val config = repository.getConfig()
                    if (!config.isEnabled) {
                        stopMonitoringAndSelf()
                        return@launch
                    }
                    lastProbeTimestampMs = repository.getSnapshot().timestampMs
                    triggerImmediateProbe(config.checkIntervalMinutes)
                }
            }

            else -> {
                serviceScope.launch {
                    val config = repository.getConfig()
                    if (!config.isEnabled) {
                        stopMonitoringAndSelf()
                        return@launch
                    }
                    lastProbeTimestampMs = repository.getSnapshot().timestampMs
                    if (isScreenInteractive) {
                        startOrRestartPeriodicLoop(config.checkIntervalMinutes)
                    } else {
                        handleScreenOff()
                    }
                }
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        periodicJob?.cancel()
        bssidDebounceJob?.cancel()
        activeProbeJob?.cancel()
        configObserverJob?.cancel()
        unregisterScreenReceiver()
        unregisterWifiRoamCallback()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun ensureForegroundStarted() {
        val initialStatus = if (isScreenInteractive) "Monitoring active" else "Paused (Screen Off)"
        val notification = notificationHelper.buildForegroundNotification(
            statusText = initialStatus,
            summaryText = "Comparing Wi-Fi and Mobile Data when both are available."
        )
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            com.netadvisor.app.notification.NotificationHelper.FOREGROUND_NOTIFICATION_ID,
            notification,
            serviceType
        )
    }

    private fun observeConfiguration() {
        var previousIntervalMinutes: Int? = null
        configObserverJob = serviceScope.launch {
            repository.configFlow
                .distinctUntilChanged()
                .collect { config ->
                    if (!config.isEnabled) {
                        stopMonitoringAndSelf()
                        return@collect
                    }
                    if (previousIntervalMinutes != null &&
                        previousIntervalMinutes != config.checkIntervalMinutes &&
                        isScreenInteractive
                    ) {
                        startOrRestartPeriodicLoop(config.checkIntervalMinutes)
                    }
                    previousIntervalMinutes = config.checkIntervalMinutes
                }
        }
    }

    private fun handleScreenOff() {
        isScreenInteractive = false
        periodicJob?.cancel()
        periodicJob = null
        bssidDebounceJob?.cancel()
        bssidDebounceJob = null
        activeProbeJob?.cancel()
        activeProbeJob = null

        repository.setMonitorStatus("Paused (Screen Off)")
        notificationHelper.updateForegroundNotification(
            statusText = "Paused (Screen Off)",
            summaryText = "Probing paused to save battery while screen is off."
        )
    }

    private fun handleScreenOn() {
        isScreenInteractive = true
        serviceScope.launch {
            val config = repository.getConfig()
            if (!config.isEnabled) {
                stopMonitoringAndSelf()
                return@launch
            }
            startOrRestartPeriodicLoop(config.checkIntervalMinutes)
        }
    }

    private fun startOrRestartPeriodicLoop(intervalMinutes: Int) {
        periodicJob?.cancel()
        val intervalMs = intervalMinutes.coerceAtLeast(1) * 60_000L

        repository.setMonitorStatus("Monitoring (every ${intervalMinutes}m)")
        periodicJob = serviceScope.launch {
            val snapshot = repository.getSnapshot()
            notificationHelper.updateForegroundNotification(
                statusText = "Monitoring (every ${intervalMinutes}m)",
                summaryText = snapshot.recommendation
            )

            while (isActive && isScreenInteractive) {
                val now = System.currentTimeMillis()
                val elapsed = if (lastProbeTimestampMs > 0L) now - lastProbeTimestampMs else intervalMs
                val remaining = (intervalMs - elapsed).coerceAtLeast(0L)

                if (remaining > 0L) {
                    delay(remaining)
                }
                if (!isActive || !isScreenInteractive) break

                runProbeCycle()
                delay(intervalMs)
            }
        }
    }

    private fun triggerImmediateProbe(intervalMinutes: Int) {
        activeProbeJob?.cancel()
        activeProbeJob = serviceScope.launch {
            runProbeCycle()
            if (isScreenInteractive && periodicJob?.isActive != true && repository.getConfig().isEnabled) {
                startOrRestartPeriodicLoop(intervalMinutes)
            }
        }
    }

    private fun scheduleDebouncedWifiProbe() {
        if (!isScreenInteractive) return
        bssidDebounceJob?.cancel()
        bssidDebounceJob = serviceScope.launch {
            delay(BSSID_DEBOUNCE_MS)
            if (isScreenInteractive && repository.getConfig().isEnabled) {
                runProbeCycle()
            }
        }
    }

    private suspend fun runProbeCycle() {
        probeMutex.withLock {
            val config = repository.getConfig()
            repository.setProbing(true)
            repository.setMonitorStatus("Probing networks...")
            notificationHelper.updateForegroundNotification(
                statusText = "Probing...",
                summaryText = "Measuring Wi-Fi and Mobile Data speed and latency"
            )

            try {
                val rawSnapshot = prober.probeBothNetworks()
                val lastAlertTs = repository.getLastAlertTimestamp()
                val decision = AlertEvaluator.evaluate(
                    snapshot = rawSnapshot,
                    config = config,
                    lastAlertTimestampMs = lastAlertTs,
                    nowMs = rawSnapshot.timestampMs
                )

                val finalSnapshot = rawSnapshot.copy(
                    recommendation = decision.recommendationSummary,
                    recommendedSwitchTarget = decision.targetTransport
                )

                lastProbeTimestampMs = finalSnapshot.timestampMs
                repository.saveSnapshot(finalSnapshot)

                if (decision.shouldNotify) {
                    notificationHelper.sendSwitchAlertNotification(decision)
                    repository.setLastAlertTimestamp(finalSnapshot.timestampMs)
                }

                val statusLabel = if (isScreenInteractive) {
                    "Monitoring (every ${config.checkIntervalMinutes}m)"
                } else {
                    "Paused (Screen Off)"
                }
                repository.setMonitorStatus(statusLabel)
                notificationHelper.updateForegroundNotification(
                    statusText = statusLabel,
                    summaryText = finalSnapshot.recommendation
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                repository.setMonitorStatus("Monitoring active")
            } finally {
                repository.setProbing(false)
            }
        }
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun unregisterScreenReceiver() {
        runCatching { unregisterReceiver(screenReceiver) }
    }

    private fun registerWifiRoamCallback() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onAvailable(network: Network) = onWifiUpdated(network, null)
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                    onWifiUpdated(network, caps)
                override fun onLost(network: Network) = onWifiLost(network)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = onWifiUpdated(network, null)
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                    onWifiUpdated(network, caps)
                override fun onLost(network: Network) = onWifiLost(network)
            }
        }

        wifiMonitorCallback = callback
        runCatching {
            connectivityManager.registerNetworkCallback(request, callback)
        }
    }

    private fun onWifiUpdated(network: Network, capabilities: NetworkCapabilities?) {
        val resolvedCaps = capabilities ?: runCatching {
            connectivityManager.getNetworkCapabilities(network)
        }.getOrNull()
        serviceScope.launch {
            evaluateWifiChange(network, resolvedCaps)
        }
    }

    private fun onWifiLost(network: Network) {
        serviceScope.launch {
            if (trackedWifiNetworkHandle == network.networkHandle) {
                trackedWifiNetworkHandle = null
                lastWifiSignature = null
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun evaluateWifiChange(network: Network, capabilities: NetworkCapabilities?) {
        val wifiInfo = (capabilities?.transportInfo as? WifiInfo)
            ?: runCatching { wifiManager?.connectionInfo }.getOrNull()

        val bssid = wifiInfo?.bssid?.takeIf {
            it.isNotBlank() && it != PLACEHOLDER_BSSID && it != ZERO_BSSID
        }
        val signature = "${network.networkHandle}:${bssid ?: "unknown"}"
        trackedWifiNetworkHandle = network.networkHandle

        val previous = lastWifiSignature
        lastWifiSignature = signature
        if (previous != null && previous != signature) {
            scheduleDebouncedWifiProbe()
        }
    }

    private fun unregisterWifiRoamCallback() {
        wifiMonitorCallback?.let { cb ->
            runCatching { connectivityManager.unregisterNetworkCallback(cb) }
        }
        wifiMonitorCallback = null
    }

    private fun stopMonitoringAndSelf() {
        periodicJob?.cancel()
        bssidDebounceJob?.cancel()
        activeProbeJob?.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
