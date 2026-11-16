package com.netadvisor.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.netadvisor.app.domain.ActiveTransport
import com.netadvisor.app.domain.AdvisorConfig
import com.netadvisor.app.domain.DualProbeSnapshot
import com.netadvisor.app.domain.NetworkProbeMetrics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "net_advisor_prefs")

class AdvisorRepository(private val context: Context) {

    private object Keys {
        val IS_ENABLED = booleanPreferencesKey("is_enabled")
        val CHECK_INTERVAL_MIN = intPreferencesKey("check_interval_minutes")
        val SPEED_RATIO_THRESHOLD = floatPreferencesKey("speed_ratio_threshold")
        val MIN_MBPS_DIFF = floatPreferencesKey("min_mbps_diff")
        val COOLDOWN_MIN = intPreferencesKey("cooldown_minutes")
        val LAST_ALERT_TS = longPreferencesKey("last_alert_timestamp_ms")

        val SNAPSHOT_TS = longPreferencesKey("snapshot_timestamp_ms")
        val ACTIVE_TRANSPORT = stringPreferencesKey("snapshot_active_transport")
        val RECOMMENDATION = stringPreferencesKey("snapshot_recommendation")
        val RECOMMENDED_TARGET = stringPreferencesKey("snapshot_recommended_target")

        val WIFI_AVAILABLE = booleanPreferencesKey("wifi_available")
        val WIFI_MBPS = doublePreferencesKey("wifi_mbps")
        val WIFI_LATENCY = longPreferencesKey("wifi_latency")
        val WIFI_LABEL = stringPreferencesKey("wifi_label")
        val WIFI_ERROR = stringPreferencesKey("wifi_error")

        val CELL_AVAILABLE = booleanPreferencesKey("cell_available")
        val CELL_MBPS = doublePreferencesKey("cell_mbps")
        val CELL_LATENCY = longPreferencesKey("cell_latency")
        val CELL_LABEL = stringPreferencesKey("cell_label")
        val CELL_ERROR = stringPreferencesKey("cell_error")
    }

    private val _isProbing = MutableStateFlow(false)
    val isProbing: StateFlow<Boolean> = _isProbing.asStateFlow()

    private val _monitorStatus = MutableStateFlow("Monitoring paused")
    val monitorStatus: StateFlow<String> = _monitorStatus.asStateFlow()

    val configFlow: Flow<AdvisorConfig> = context.dataStore.data.map { prefs ->
        prefsToConfig(prefs)
    }

    val snapshotFlow: Flow<DualProbeSnapshot> = context.dataStore.data.map { prefs ->
        prefsToSnapshot(prefs)
    }

    val lastAlertTimestampFlow: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[Keys.LAST_ALERT_TS] ?: 0L
    }

    suspend fun getConfig(): AdvisorConfig = configFlow.first()

    suspend fun getSnapshot(): DualProbeSnapshot = snapshotFlow.first()

    suspend fun getLastAlertTimestamp(): Long = lastAlertTimestampFlow.first()

    suspend fun setEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.IS_ENABLED] = enabled
        }
        if (!enabled) {
            _monitorStatus.value = "Monitoring off"
        }
    }

    suspend fun setCheckIntervalMinutes(minutes: Int) {
        val clamped = minutes.coerceIn(1, 60)
        context.dataStore.edit { prefs ->
            prefs[Keys.CHECK_INTERVAL_MIN] = clamped
        }
    }

    suspend fun setSpeedRatioThreshold(ratio: Float) {
        val clamped = ratio.coerceIn(1.2f, 5.0f)
        context.dataStore.edit { prefs ->
            prefs[Keys.SPEED_RATIO_THRESHOLD] = clamped
        }
    }

    suspend fun setLastAlertTimestamp(timestampMs: Long) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LAST_ALERT_TS] = timestampMs
        }
    }

    suspend fun saveSnapshot(snapshot: DualProbeSnapshot) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SNAPSHOT_TS] = snapshot.timestampMs
            prefs[Keys.ACTIVE_TRANSPORT] = snapshot.activeTransport.name
            prefs[Keys.RECOMMENDATION] = snapshot.recommendation
            if (snapshot.recommendedSwitchTarget != null) {
                prefs[Keys.RECOMMENDED_TARGET] = snapshot.recommendedSwitchTarget.name
            } else {
                prefs.remove(Keys.RECOMMENDED_TARGET)
            }

            prefs[Keys.WIFI_AVAILABLE] = snapshot.wifi.available
            prefs[Keys.WIFI_MBPS] = snapshot.wifi.downloadMbps
            prefs[Keys.WIFI_LATENCY] = snapshot.wifi.latencyMs
            setOrRemove(prefs, Keys.WIFI_LABEL, snapshot.wifi.networkLabel)
            setOrRemove(prefs, Keys.WIFI_ERROR, snapshot.wifi.error)

            prefs[Keys.CELL_AVAILABLE] = snapshot.cellular.available
            prefs[Keys.CELL_MBPS] = snapshot.cellular.downloadMbps
            prefs[Keys.CELL_LATENCY] = snapshot.cellular.latencyMs
            setOrRemove(prefs, Keys.CELL_LABEL, snapshot.cellular.networkLabel)
            setOrRemove(prefs, Keys.CELL_ERROR, snapshot.cellular.error)
        }
    }

    fun setProbing(probing: Boolean) {
        _isProbing.value = probing
    }

    fun setMonitorStatus(status: String) {
        _monitorStatus.value = status
    }

    private fun setOrRemove(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        key: Preferences.Key<String>,
        value: String?
    ) {
        if (value != null) {
            prefs[key] = value
        } else {
            prefs.remove(key)
        }
    }

    private fun prefsToConfig(prefs: Preferences): AdvisorConfig {
        return AdvisorConfig(
            isEnabled = prefs[Keys.IS_ENABLED] ?: false,
            checkIntervalMinutes = prefs[Keys.CHECK_INTERVAL_MIN] ?: 3,
            speedRatioThreshold = prefs[Keys.SPEED_RATIO_THRESHOLD] ?: 2.0f,
            minMbpsDiff = prefs[Keys.MIN_MBPS_DIFF] ?: 5.0f,
            cooldownMinutes = prefs[Keys.COOLDOWN_MIN] ?: 10
        )
    }

    private fun prefsToSnapshot(prefs: Preferences): DualProbeSnapshot {
        val activeName = prefs[Keys.ACTIVE_TRANSPORT]
        val activeTransport = activeName?.let { runCatching { ActiveTransport.valueOf(it) }.getOrNull() }
            ?: ActiveTransport.NONE
        val targetName = prefs[Keys.RECOMMENDED_TARGET]
        val targetTransport = targetName?.let { runCatching { ActiveTransport.valueOf(it) }.getOrNull() }

        return DualProbeSnapshot(
            timestampMs = prefs[Keys.SNAPSHOT_TS] ?: 0L,
            activeTransport = activeTransport,
            wifi = NetworkProbeMetrics(
                available = prefs[Keys.WIFI_AVAILABLE] ?: false,
                downloadMbps = prefs[Keys.WIFI_MBPS] ?: 0.0,
                latencyMs = prefs[Keys.WIFI_LATENCY] ?: -1L,
                networkLabel = prefs[Keys.WIFI_LABEL],
                error = prefs[Keys.WIFI_ERROR]
            ),
            cellular = NetworkProbeMetrics(
                available = prefs[Keys.CELL_AVAILABLE] ?: false,
                downloadMbps = prefs[Keys.CELL_MBPS] ?: 0.0,
                latencyMs = prefs[Keys.CELL_LATENCY] ?: -1L,
                networkLabel = prefs[Keys.CELL_LABEL],
                error = prefs[Keys.CELL_ERROR]
            ),
            recommendation = prefs[Keys.RECOMMENDATION] ?: "No check run yet",
            recommendedSwitchTarget = targetTransport
        )
    }
}
