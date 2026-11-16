package com.netadvisor.app.tile

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.netadvisor.app.NetAdvisorApp
import com.netadvisor.app.service.NetMonitorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class NetAdvisorTileService : TileService() {

    private val tileScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    companion object {
        fun requestTileRefresh(context: Context) {
            runCatching {
                requestListeningState(
                    context,
                    ComponentName(context, NetAdvisorTileService::class.java)
                )
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        tileScope.launch {
            val repository = (application as NetAdvisorApp).repository
            val config = repository.getConfig()
            val snapshot = repository.getSnapshot()
            applyTileUi(isEnabled = config.isEnabled, recommendation = snapshot.recommendation)
        }
    }

    override fun onClick() {
        super.onClick()
        tileScope.launch {
            val repository = (application as NetAdvisorApp).repository
            val nextEnabled = !repository.getConfig().isEnabled
            repository.setEnabled(nextEnabled)

            if (nextEnabled) {
                NetMonitorService.start(this@NetMonitorService)
            } else {
                NetMonitorService.stop(this@NetMonitorService)
            }

            val snapshot = repository.getSnapshot()
            applyTileUi(isEnabled = nextEnabled, recommendation = snapshot.recommendation)
        }
    }

    override fun onDestroy() {
        tileScope.cancel()
        super.onDestroy()
    }

    private fun applyTileUi(isEnabled: Boolean, recommendation: String) {
        val tile = qsTile ?: return
        tile.state = if (isEnabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (isEnabled) {
                recommendation.take(28).ifBlank { "Active" }
            } else {
                "Off"
            }
        }
        tile.updateTile()
    }
}
