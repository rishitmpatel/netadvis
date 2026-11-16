package com.netadvisor.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertEvaluatorTest {

    private val defaultConfig = AdvisorConfig(
        isEnabled = true,
        checkIntervalMinutes = 3,
        speedRatioThreshold = 2.0f,
        minMbpsDiff = 5.0f,
        cooldownMinutes = 10
    )

    @Test
    fun alertsWhenCellularIsAtLeast2xAndOver5MbpsFasterThanActiveWifi() {
        val now = 1_000_000L
        val snapshot = DualProbeSnapshot(
            timestampMs = now,
            activeTransport = ActiveTransport.WIFI,
            wifi = NetworkProbeMetrics(available = true, downloadMbps = 12.0, latencyMs = 35),
            cellular = NetworkProbeMetrics(available = true, downloadMbps = 48.0, latencyMs = 28)
        )

        val decision = AlertEvaluator.evaluate(
            snapshot = snapshot,
            config = defaultConfig,
            lastAlertTimestampMs = 0L,
            nowMs = now
        )

        assertTrue(decision.shouldNotify)
        assertEquals(ActiveTransport.CELLULAR, decision.targetTransport)
        assertTrue(decision.title.contains("Mobile Data"))
    }

    @Test
    fun suppressesAlertWhenRatioIsMetButAbsoluteDifferenceIsUnder5Mbps() {
        val now = 1_000_000L
        val snapshot = DualProbeSnapshot(
            timestampMs = now,
            activeTransport = ActiveTransport.WIFI,
            wifi = NetworkProbeMetrics(available = true, downloadMbps = 1.0, latencyMs = 40),
            cellular = NetworkProbeMetrics(available = true, downloadMbps = 2.5, latencyMs = 45)
        )

        val decision = AlertEvaluator.evaluate(
            snapshot = snapshot,
            config = defaultConfig,
            lastAlertTimestampMs = 0L,
            nowMs = now
        )

        assertFalse(decision.shouldNotify)
        assertNull(decision.targetTransport)
    }

    @Test
    fun alertsOnRescueConditionWhenActiveIsUnder2MbpsAndAlternateIsOver10Mbps() {
        val now = 1_000_000L
        val snapshot = DualProbeSnapshot(
            timestampMs = now,
            activeTransport = ActiveTransport.WIFI,
            wifi = NetworkProbeMetrics(available = true, downloadMbps = 1.2, latencyMs = 220),
            cellular = NetworkProbeMetrics(available = true, downloadMbps = 15.0, latencyMs = 32)
        )

        val decision = AlertEvaluator.evaluate(
            snapshot = snapshot,
            config = defaultConfig,
            lastAlertTimestampMs = 0L,
            nowMs = now
        )

        assertTrue(decision.shouldNotify)
        assertEquals(ActiveTransport.CELLULAR, decision.targetTransport)
    }

    @Test
    fun suppressesNotificationDuringCooldownPeriodAndResumesAfter() {
        val lastAlert = 1_000_000L
        val withinCooldown = lastAlert + 5 * 60_000L
        val afterCooldown = lastAlert + 10 * 60_000L

        val snapshot = DualProbeSnapshot(
            timestampMs = withinCooldown,
            activeTransport = ActiveTransport.WIFI,
            wifi = NetworkProbeMetrics(available = true, downloadMbps = 5.0, latencyMs = 50),
            cellular = NetworkProbeMetrics(available = true, downloadMbps = 40.0, latencyMs = 25)
        )

        val suppressed = AlertEvaluator.evaluate(
            snapshot = snapshot,
            config = defaultConfig,
            lastAlertTimestampMs = lastAlert,
            nowMs = withinCooldown
        )
        assertFalse(suppressed.shouldNotify)
        assertEquals(ActiveTransport.CELLULAR, suppressed.targetTransport)

        val allowed = AlertEvaluator.evaluate(
            snapshot = snapshot.copy(timestampMs = afterCooldown),
            config = defaultConfig,
            lastAlertTimestampMs = lastAlert,
            nowMs = afterCooldown
        )
        assertTrue(allowed.shouldNotify)
        assertEquals(ActiveTransport.CELLULAR, allowed.targetTransport)
    }

    @Test
    fun alertsInReverseDirectionWhenCellularIsActiveAndWifiIsMuchFaster() {
        val now = 2_000_000L
        val snapshot = DualProbeSnapshot(
            timestampMs = now,
            activeTransport = ActiveTransport.CELLULAR,
            wifi = NetworkProbeMetrics(available = true, downloadMbps = 180.0, latencyMs = 12),
            cellular = NetworkProbeMetrics(available = true, downloadMbps = 22.0, latencyMs = 38)
        )

        val decision = AlertEvaluator.evaluate(
            snapshot = snapshot,
            config = defaultConfig,
            lastAlertTimestampMs = 0L,
            nowMs = now
        )

        assertTrue(decision.shouldNotify)
        assertEquals(ActiveTransport.WIFI, decision.targetTransport)
        assertTrue(decision.title.contains("Wi-Fi"))
    }
}
