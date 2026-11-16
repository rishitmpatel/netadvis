package com.netadvisor.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.netadvisor.app.NetAdvisorApp
import com.netadvisor.app.domain.AdvisorConfig
import com.netadvisor.app.domain.AlertEvaluator
import com.netadvisor.app.domain.DualProbeSnapshot
import com.netadvisor.app.service.NetMonitorService
import com.netadvisor.app.tile.NetAdvisorTileService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MainUiState(
    val config: AdvisorConfig = AdvisorConfig(),
    val snapshot: DualProbeSnapshot = DualProbeSnapshot(),
    val isProbing: Boolean = false,
    val monitorStatus: String = "Monitoring off"
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NetAdvisorApp
    private val repository = app.repository
    private val prober = app.prober

    val uiState: StateFlow<MainUiState> = combine(
        repository.configFlow,
        repository.snapshotFlow,
        repository.isProbing,
        repository.monitorStatus
    ) { config, snapshot, isProbing, monitorStatus ->
        MainUiState(
            config = config,
            snapshot = snapshot,
            isProbing = isProbing,
            monitorStatus = if (config.isEnabled) monitorStatus else "Monitoring off"
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = MainUiState()
    )

    fun syncServiceIfEnabled() {
        viewModelScope.launch {
            if (repository.getConfig().isEnabled) {
                NetMonitorService.start(app, NetMonitorService.ACTION_START_OR_SYNC)
            }
            NetAdvisorTileService.requestTileRefresh(app)
        }
    }

    fun setMonitoringEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setEnabled(enabled)
            if (enabled) {
                NetMonitorService.start(app, NetMonitorService.ACTION_START_OR_SYNC)
            } else {
                NetMonitorService.stop(app)
            }
            NetAdvisorTileService.requestTileRefresh(app)
        }
    }

    fun updateCheckInterval(minutes: Int) {
        viewModelScope.launch {
            repository.setCheckIntervalMinutes(minutes)
        }
    }

    fun updateSpeedRatioThreshold(ratio: Float) {
        viewModelScope.launch {
            repository.setSpeedRatioThreshold(ratio)
        }
    }

    fun triggerManualProbe() {
        if (repository.isProbing.value) return

        viewModelScope.launch {
            val config = repository.getConfig()
            if (config.isEnabled) {
                NetMonitorService.start(app, NetMonitorService.ACTION_TEST_NOW)
                return@launch
            }

            repository.setProbing(true)
            try {
                val rawSnapshot = prober.probeBothNetworks()
                val decision = AlertEvaluator.evaluate(
                    snapshot = rawSnapshot,
                    config = config,
                    lastAlertTimestampMs = repository.getLastAlertTimestamp(),
                    nowMs = rawSnapshot.timestampMs
                )
                val finalSnapshot = rawSnapshot.copy(
                    recommendation = decision.recommendationSummary,
                    recommendedSwitchTarget = decision.targetTransport
                )
                repository.saveSnapshot(finalSnapshot)
            } finally {
                repository.setProbing(false)
            }
        }
    }
}
