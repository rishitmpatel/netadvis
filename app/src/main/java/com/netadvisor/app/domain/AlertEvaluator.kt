package com.netadvisor.app.domain

import java.util.Locale

object AlertEvaluator {

    private const val RESCUE_ACTIVE_MAX_MBPS = 2.0
    private const val RESCUE_ALTERNATE_MIN_MBPS = 10.0

    fun evaluate(
        snapshot: DualProbeSnapshot,
        config: AdvisorConfig,
        lastAlertTimestampMs: Long,
        nowMs: Long = snapshot.timestampMs
    ): AlertDecision {
        val activeTransport = snapshot.activeTransport
        if (activeTransport != ActiveTransport.WIFI && activeTransport != ActiveTransport.CELLULAR) {
            return AlertDecision(
                shouldNotify = false,
                targetTransport = null,
                title = "",
                message = "",
                recommendationSummary = "Waiting for an active Wi-Fi or Mobile Data connection"
            )
        }

        val (activeMetrics, alternateMetrics, alternateTransport) = when (activeTransport) {
            ActiveTransport.WIFI -> Triple(snapshot.wifi, snapshot.cellular, ActiveTransport.CELLULAR)
            ActiveTransport.CELLULAR -> Triple(snapshot.cellular, snapshot.wifi, ActiveTransport.WIFI)
            else -> return AlertDecision(
                shouldNotify = false,
                targetTransport = null,
                title = "",
                message = "",
                recommendationSummary = "No comparable dual-network path available"
            )
        }

        if (!alternateMetrics.available || alternateMetrics.downloadMbps <= 0.0) {
            val summary = if (activeMetrics.available) {
                "${activeTransport.displayName} active (${formatMbps(activeMetrics.downloadMbps)}); ${alternateTransport.displayName} unavailable"
            } else {
                "Both Wi-Fi and Mobile Data probes were unavailable"
            }
            return AlertDecision(
                shouldNotify = false,
                targetTransport = null,
                title = "",
                message = "",
                recommendationSummary = summary
            )
        }

        val activeMbps = if (activeMetrics.available) activeMetrics.downloadMbps.coerceAtLeast(0.0) else 0.0
        val alternateMbps = alternateMetrics.downloadMbps

        val ratioAdvantage = activeMetrics.available &&
            activeMbps > 0.0 &&
            alternateMbps >= activeMbps * config.speedRatioThreshold &&
            (alternateMbps - activeMbps) > config.minMbpsDiff

        val rescueTriggered = (!activeMetrics.available || activeMbps < RESCUE_ACTIVE_MAX_MBPS) &&
            alternateMbps > RESCUE_ALTERNATE_MIN_MBPS

        if (!ratioAdvantage && !rescueTriggered) {
            val summary = "${activeTransport.displayName} is performing well (${formatMbps(activeMbps)} vs ${alternateTransport.displayName} ${formatMbps(alternateMbps)})"
            return AlertDecision(
                shouldNotify = false,
                targetTransport = null,
                title = "",
                message = "",
                recommendationSummary = summary
            )
        }

        val diffMbps = alternateMbps - activeMbps
        val comparisonDetail = if (activeMetrics.available && activeMbps >= 0.2) {
            val ratio = alternateMbps / activeMbps
            String.format(
                Locale.US,
                "%.1fx faster (+%.1f Mbps)",
                ratio,
                diffMbps
            )
        } else {
            String.format(
                Locale.US,
                "%.1f Mbps while %s is stalled",
                alternateMbps,
                activeTransport.displayName
            )
        }

        val cooldownMs = config.cooldownMinutes * 60_000L
        val cooldownElapsed = lastAlertTimestampMs <= 0L || (nowMs - lastAlertTimestampMs) >= cooldownMs

        val title = "Switch to ${alternateTransport.displayName}"
        val activeDisplay = if (activeMetrics.available) formatMbps(activeMbps) else "stalled"
        val message = "${alternateTransport.displayName} (${formatMbps(alternateMbps)}) is $comparisonDetail vs ${activeTransport.displayName} ($activeDisplay)."
        val summary = "${alternateTransport.displayName} is faster: $comparisonDetail"

        return AlertDecision(
            shouldNotify = cooldownElapsed,
            targetTransport = alternateTransport,
            title = title,
            message = message,
            recommendationSummary = summary
        )
    }

    fun formatMbps(mbps: Double): String {
        return String.format(Locale.US, "%.1f Mbps", mbps)
    }
}
