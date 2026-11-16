package com.netadvisor.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.netadvisor.app.domain.ActiveTransport
import com.netadvisor.app.domain.NetworkProbeMetrics
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val INTERVAL_OPTIONS = listOf(1, 3, 5, 10, 15)
private val THRESHOLD_OPTIONS = listOf(1.5f, 2.0f, 2.5f, 3.0f)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MainScreen(
    uiState: MainUiState,
    hasNotificationPermission: Boolean,
    hasLocationPermission: Boolean,
    isIgnoringBatteryOptimizations: Boolean,
    onToggleMonitoring: (Boolean) -> Unit,
    onIntervalSelected: (Int) -> Unit,
    onThresholdSelected: (Float) -> Unit,
    onTestNow: () -> Unit,
    onOpenInternetPanel: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onRequestLocationPermission: () -> Unit,
    onOpenBatterySettings: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "NetAdvisor",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Wi-Fi vs Mobile Data Speed Advisor",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!hasNotificationPermission) {
                PermissionCard(
                    onGrantClick = onRequestNotificationPermission
                )
            }

            MasterControlCard(
                isEnabled = uiState.config.isEnabled,
                statusText = uiState.monitorStatus,
                onToggle = onToggleMonitoring
            )

            LiveComparisonCard(
                uiState = uiState,
                onTestNow = onTestNow,
                onOpenInternetPanel = onOpenInternetPanel
            )

            SettingsCard(
                checkIntervalMinutes = uiState.config.checkIntervalMinutes,
                speedRatioThreshold = uiState.config.speedRatioThreshold,
                minMbpsDiff = uiState.config.minMbpsDiff,
                cooldownMinutes = uiState.config.cooldownMinutes,
                hasLocationPermission = hasLocationPermission,
                onIntervalSelected = onIntervalSelected,
                onThresholdSelected = onThresholdSelected,
                onRequestLocationPermission = onRequestLocationPermission
            )

            BatteryOptimizationCard(
                isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations,
                onOpenBatterySettings = onOpenBatterySettings
            )

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun PermissionCard(onGrantClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.NotificationsActive,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Notification permission needed",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = "Allow notifications so NetAdvisor can alert you when switching networks is faster.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = onGrantClick) {
                Text("Allow")
            }
        }
    }
}

@Composable
private fun MasterControlCard(
    isEnabled: Boolean,
    statusText: String,
    onToggle: (Boolean) -> Unit
) {
    ElevatedCard(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (isEnabled) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isEnabled) "Monitoring Enabled" else "Monitoring Disabled",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isEnabled) {
                            "$statusText • Pauses automatically when screen is off"
                        } else {
                            "Turn on to monitor Wi-Fi vs Mobile Data in the background"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = isEnabled,
                    onCheckedChange = onToggle
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Tip: Add the NetAdvisor Quick Settings tile to toggle monitoring from your notification shade.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LiveComparisonCard(
    uiState: MainUiState,
    onTestNow: () -> Unit,
    onOpenInternetPanel: () -> Unit
) {
    val snapshot = uiState.snapshot
    val hasSwitchRecommendation = snapshot.recommendedSwitchTarget != null

    ElevatedCard(shape = RoundedCornerShape(24.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Network Comparison",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = formatTimestamp(snapshot.timestampMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = "Active: ${snapshot.activeTransport.displayName}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TransportMetricTile(
                    modifier = Modifier.weight(1f),
                    title = snapshot.wifi.networkLabel ?: "Wi-Fi",
                    icon = Icons.Rounded.Wifi,
                    metrics = snapshot.wifi,
                    isActive = snapshot.activeTransport == ActiveTransport.WIFI,
                    isRecommended = snapshot.recommendedSwitchTarget == ActiveTransport.WIFI
                )
                TransportMetricTile(
                    modifier = Modifier.weight(1f),
                    title = snapshot.cellular.networkLabel ?: "Mobile Data",
                    icon = Icons.Rounded.CellTower,
                    metrics = snapshot.cellular,
                    isActive = snapshot.activeTransport == ActiveTransport.CELLULAR,
                    isRecommended = snapshot.recommendedSwitchTarget == ActiveTransport.CELLULAR
                )
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (hasSwitchRecommendation) {
                    MaterialTheme.colorScheme.tertiaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            ) {
                Text(
                    text = snapshot.recommendation,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (hasSwitchRecommendation) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (hasSwitchRecommendation) {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onTestNow,
                    enabled = !uiState.isProbing,
                    modifier = Modifier.weight(1f)
                ) {
                    if (uiState.isProbing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Testing...")
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Test Now")
                    }
                }

                FilledTonalButton(
                    onClick = onOpenInternetPanel,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SwapHoriz,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Switch Network")
                }
            }
        }
    }
}

@Composable
private fun TransportMetricTile(
    modifier: Modifier = Modifier,
    title: String,
    icon: ImageVector,
    metrics: NetworkProbeMetrics,
    isActive: Boolean,
    isRecommended: Boolean
) {
    val containerColor = when {
        isRecommended -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
        isActive -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = containerColor
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (metrics.available) {
                Text(
                    text = String.format(Locale.US, "%.1f Mbps", metrics.downloadMbps),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (metrics.latencyMs >= 0L) "Latency: ${metrics.latencyMs} ms" else "Latency: --",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = "-- Mbps",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = metrics.error ?: "Not tested yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            val badgeText = when {
                isActive -> "ACTIVE"
                isRecommended -> "FASTER"
                else -> null
            }
            if (badgeText != null) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (isRecommended) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.secondary
                    }
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isRecommended) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSecondary
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsCard(
    checkIntervalMinutes: Int,
    speedRatioThreshold: Float,
    minMbpsDiff: Float,
    cooldownMinutes: Int,
    hasLocationPermission: Boolean,
    onIntervalSelected: (Int) -> Unit,
    onThresholdSelected: (Float) -> Unit,
    onRequestLocationPermission: () -> Unit
) {
    ElevatedCard(shape = RoundedCornerShape(24.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Probe & Alert Thresholds",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Check interval (when screen is on)",
                style = MaterialTheme.typography.labelLarge
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                INTERVAL_OPTIONS.forEach { minutes ->
                    FilterChip(
                        selected = checkIntervalMinutes == minutes,
                        onClick = { onIntervalSelected(minutes) },
                        label = { Text("${minutes} min") }
                    )
                }
            }

            Text(
                text = "Speed advantage threshold",
                style = MaterialTheme.typography.labelLarge
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                THRESHOLD_OPTIONS.forEach { ratio ->
                    FilterChip(
                        selected = speedRatioThreshold == ratio,
                        onClick = { onThresholdSelected(ratio) },
                        label = { Text("${ratio}x faster") }
                    )
                }
            }

            Text(
                text = "Smart hysteresis: alerts trigger when the non-active network is at least ${speedRatioThreshold}x faster and > ${minMbpsDiff.toInt()} Mbps ahead (or if active < 2 Mbps while alternate > 10 Mbps), with a $cooldownMinutes-minute cooldown.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!hasLocationPermission) {
                OutlinedButton(
                    onClick = onRequestLocationPermission,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Allow Location for Mesh BSSID Roam Detection (Optional)")
                }
            }
        }
    }
}

@Composable
private fun BatteryOptimizationCard(
    isIgnoringBatteryOptimizations: Boolean,
    onOpenBatterySettings: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (isIgnoringBatteryOptimizations) {
                        Icons.Rounded.BatteryChargingFull
                    } else {
                        Icons.Rounded.BatteryAlert
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = if (isIgnoringBatteryOptimizations) {
                        "Battery Optimization: Unrestricted"
                    } else {
                        "Samsung One UI Battery Tip"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = "On Samsung Galaxy S25 Ultra (One UI), Android may pause background checks if battery optimization is enabled. For reliable monitoring, set Battery usage to \"Unrestricted\" in App Info -> Battery.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedButton(
                onClick = onOpenBatterySettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open App Battery Settings")
            }
        }
    }
}

private fun formatTimestamp(timestampMs: Long): String {
    if (timestampMs <= 0L) return "Not tested yet"
    val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    return "Last checked at ${formatter.format(Date(timestampMs))}"
}
