package com.netadvisor.app.domain

enum class ActiveTransport(val displayName: String) {
    WIFI("Wi-Fi"),
    CELLULAR("Mobile Data"),
    OTHER("Other"),
    NONE("None")
}

data class NetworkProbeMetrics(
    val available: Boolean,
    val downloadMbps: Double = 0.0,
    val latencyMs: Long = -1L,
    val networkLabel: String? = null,
    val error: String? = null
)

data class DualProbeSnapshot(
    val timestampMs: Long = 0L,
    val activeTransport: ActiveTransport = ActiveTransport.NONE,
    val wifi: NetworkProbeMetrics = NetworkProbeMetrics(available = false),
    val cellular: NetworkProbeMetrics = NetworkProbeMetrics(available = false),
    val recommendation: String = "No check run yet",
    val recommendedSwitchTarget: ActiveTransport? = null
)

data class AdvisorConfig(
    val isEnabled: Boolean = false,
    val checkIntervalMinutes: Int = 3,
    val speedRatioThreshold: Float = 2.0f,
    val minMbpsDiff: Float = 5.0f,
    val cooldownMinutes: Int = 10
)

data class AlertDecision(
    val shouldNotify: Boolean,
    val targetTransport: ActiveTransport?,
    val title: String,
    val message: String,
    val recommendationSummary: String
)
