package com.netadvisor.app

import android.app.Application
import com.netadvisor.app.data.AdvisorRepository
import com.netadvisor.app.network.DualNetworkProber
import com.netadvisor.app.notification.NotificationHelper

class NetAdvisorApp : Application() {

    lateinit var repository: AdvisorRepository
        private set

    lateinit var prober: DualNetworkProber
        private set

    lateinit var notificationHelper: NotificationHelper
        private set

    override fun onCreate() {
        super.onCreate()
        repository = AdvisorRepository(this)
        prober = DualNetworkProber(this)
        notificationHelper = NotificationHelper(this)
        notificationHelper.ensureChannels()
    }
}
