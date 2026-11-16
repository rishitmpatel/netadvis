package com.netadvisor.app.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.netadvisor.app.MainActivity
import com.netadvisor.app.R
import com.netadvisor.app.domain.AlertDecision
import com.netadvisor.app.service.NetMonitorService

class NotificationHelper(private val context: Context) {

    private val appContext = context.applicationContext
    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val CHANNEL_SERVICE = "netadvisor_monitor"
        const val CHANNEL_ALERTS = "netadvisor_alerts"

        const val FOREGROUND_NOTIFICATION_ID = 1001
        const val ALERT_NOTIFICATION_ID = 1002

        private const val REQ_OPEN_APP = 2001
        private const val REQ_TEST_NOW = 2002
        private const val REQ_STOP_SERVICE = 2003
        private const val REQ_SWITCH_NETWORK = 2004
    }

    fun ensureChannels() {
        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE,
            appContext.getString(R.string.channel_service_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = appContext.getString(R.string.channel_service_description)
            setShowBadge(false)
        }

        val alertsChannel = NotificationChannel(
            CHANNEL_ALERTS,
            appContext.getString(R.string.channel_alerts_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = appContext.getString(R.string.channel_alerts_description)
        }

        notificationManager.createNotificationChannel(serviceChannel)
        notificationManager.createNotificationChannel(alertsChannel)
    }

    fun buildConnectivityPanelIntent(): Intent {
        val panelIntent = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
        val canResolvePanel = appContext.packageManager.resolveActivity(panelIntent, 0) != null
        return if (canResolvePanel) {
            panelIntent
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }
    }

    fun buildForegroundNotification(
        statusText: String,
        summaryText: String
    ): Notification {
        ensureChannels()

        val openAppIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            appContext,
            REQ_OPEN_APP,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val testNowIntent = Intent(appContext, NetMonitorService::class.java).apply {
            action = NetMonitorService.ACTION_TEST_NOW
        }
        val testNowPendingIntent = PendingIntent.getForegroundService(
            appContext,
            REQ_TEST_NOW,
            testNowIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(appContext, NetMonitorService::class.java).apply {
            action = NetMonitorService.ACTION_STOP_MONITORING
        }
        val stopPendingIntent = PendingIntent.getForegroundService(
            appContext,
            REQ_STOP_SERVICE,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(appContext, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("NetAdvisor • $statusText")
            .setContentText(summaryText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summaryText))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppPendingIntent)
            .addAction(0, "Test Now", testNowPendingIntent)
            .addAction(0, "Turn Off", stopPendingIntent)
            .build()
    }

    fun updateForegroundNotification(statusText: String, summaryText: String) {
        if (!hasNotificationPermission()) return
        notificationManager.notify(
            FOREGROUND_NOTIFICATION_ID,
            buildForegroundNotification(statusText, summaryText)
        )
    }

    fun sendSwitchAlertNotification(decision: AlertDecision) {
        if (!hasNotificationPermission()) return
        ensureChannels()

        val switchIntent = buildConnectivityPanelIntent().apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val switchPendingIntent = PendingIntent.getActivity(
            appContext,
            REQ_SWITCH_NETWORK,
            switchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(decision.title)
            .setContentText(decision.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(decision.message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(switchPendingIntent)
            .addAction(0, "Switch", switchPendingIntent)
            .build()

        notificationManager.notify(ALERT_NOTIFICATION_ID, notification)
    }

    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
