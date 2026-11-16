package com.netadvisor.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.telephony.TelephonyManager
import com.netadvisor.app.domain.ActiveTransport
import com.netadvisor.app.domain.DualProbeSnapshot
import com.netadvisor.app.domain.NetworkProbeMetrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

class DualNetworkProber(context: Context) {

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager =
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val telephonyManager =
        appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    companion object {
        private const val LATENCY_PROBE_URL = "https://connectivitycheck.gstatic.com/generate_204"
        private const val PRIMARY_DOWNLOAD_URL = "https://speed.cloudflare.com/__down?bytes=204800"
        private const val FALLBACK_DOWNLOAD_URL = "https://proof.ovh.net/files/1Mb.dat"

        private const val REQUEST_TIMEOUT_MS = 3_500
        private const val ACQUIRE_COROUTINE_TIMEOUT_MS = 4_000L
        private const val LATENCY_TIMEOUT_MS = 3_000
        private const val DOWNLOAD_TIMEOUT_MS = 4_000
        private const val TARGET_DOWNLOAD_BYTES = 204_800
        private const val BUFFER_SIZE_BYTES = 8_192
    }

    fun detectActiveTransport(): ActiveTransport {
        val activeNetwork = connectivityManager.activeNetwork ?: return ActiveTransport.NONE
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            ?: return ActiveTransport.NONE
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ActiveTransport.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ActiveTransport.CELLULAR
            else -> ActiveTransport.OTHER
        }
    }

    suspend fun probeBothNetworks(): DualProbeSnapshot = coroutineScope {
        val activeTransport = detectActiveTransport()

        val wifiDeferred = async(Dispatchers.IO) {
            probeTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }
        val cellularDeferred = async(Dispatchers.IO) {
            probeTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        }

        val wifiMetrics = wifiDeferred.await()
        val cellularMetrics = cellularDeferred.await()

        DualProbeSnapshot(
            timestampMs = System.currentTimeMillis(),
            activeTransport = activeTransport,
            wifi = wifiMetrics,
            cellular = cellularMetrics
        )
    }

    private suspend fun probeTransport(transportType: Int): NetworkProbeMetrics {
        if (transportType == NetworkCapabilities.TRANSPORT_WIFI && wifiManager?.isWifiEnabled == false) {
            return NetworkProbeMetrics(available = false, error = "Wi-Fi is turned off")
        }

        val request = NetworkRequest.Builder()
            .addTransportType(transportType)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        var registeredCallback: ConnectivityManager.NetworkCallback? = null
        try {
            val acquired = acquireNetwork(request) { callback ->
                registeredCallback = callback
            } ?: return NetworkProbeMetrics(
                available = false,
                error = "Unavailable"
            )

            val network = acquired.network
            val label = resolveNetworkLabel(transportType, acquired.capabilities)

            val latencyMs = measureLatency(network)
            val downloadMbps = measureDownloadMbps(network)

            return NetworkProbeMetrics(
                available = true,
                downloadMbps = downloadMbps,
                latencyMs = latencyMs,
                networkLabel = label,
                error = null
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: SecurityException) {
            return NetworkProbeMetrics(
                available = false,
                error = "Network permission denied"
            )
        } catch (e: Exception) {
            return NetworkProbeMetrics(
                available = false,
                error = e.message?.take(60) ?: "Probe failed"
            )
        } finally {
            registeredCallback?.let { cb ->
                runCatching { connectivityManager.unregisterNetworkCallback(cb) }
            }
        }
    }

    private data class AcquiredNetwork(
        val network: Network,
        val capabilities: NetworkCapabilities?
    )

    private suspend fun acquireNetwork(
        request: NetworkRequest,
        onCallbackCreated: (ConnectivityManager.NetworkCallback) -> Unit
    ): AcquiredNetwork? {
        return withTimeoutOrNull(ACQUIRE_COROUTINE_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val resumed = AtomicBoolean(false)
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (resumed.compareAndSet(false, true) && continuation.isActive) {
                            val caps = runCatching {
                                connectivityManager.getNetworkCapabilities(network)
                            }.getOrNull()
                            continuation.resume(AcquiredNetwork(network, caps))
                        }
                    }

                    override fun onUnavailable() {
                        if (resumed.compareAndSet(false, true) && continuation.isActive) {
                            continuation.resume(null)
                        }
                    }
                }
                onCallbackCreated(callback)
                try {
                    connectivityManager.requestNetwork(request, callback, REQUEST_TIMEOUT_MS)
                } catch (e: Exception) {
                    if (resumed.compareAndSet(false, true) && continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            }
        }
    }

    private fun measureLatency(network: Network): Long {
        var connection: HttpURLConnection? = null
        return try {
            val startMs = SystemClock.elapsedRealtime()
            connection = (network.openConnection(URL(LATENCY_PROBE_URL)) as HttpURLConnection).apply {
                connectTimeout = LATENCY_TIMEOUT_MS
                readTimeout = LATENCY_TIMEOUT_MS
                useCaches = false
                instanceFollowRedirects = false
                setRequestProperty("Connection", "close")
            }
            val code = connection.responseCode
            val elapsed = SystemClock.elapsedRealtime() - startMs
            if (code in 200..399) elapsed.coerceAtLeast(1L) else -1L
        } catch (_: Exception) {
            -1L
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private suspend fun measureDownloadMbps(network: Network): Double {
        return try {
            downloadSizedPayload(network, PRIMARY_DOWNLOAD_URL)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            downloadSizedPayload(network, FALLBACK_DOWNLOAD_URL)
        }
    }

    private suspend fun downloadSizedPayload(network: Network, urlString: String): Double {
        var connection: HttpURLConnection? = null
        try {
            connection = (network.openConnection(URL(urlString)) as HttpURLConnection).apply {
                connectTimeout = DOWNLOAD_TIMEOUT_MS
                readTimeout = DOWNLOAD_TIMEOUT_MS
                useCaches = false
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Connection", "close")
            }

            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                throw IOException("HTTP $statusCode")
            }

            val inputStream = connection.inputStream
            val buffer = ByteArray(BUFFER_SIZE_BYTES)
            var totalBytesRead = 0
            val deadlineMs = SystemClock.elapsedRealtime() + DOWNLOAD_TIMEOUT_MS
            val startNanos = SystemClock.elapsedRealtimeNanos()

            inputStream.use { stream ->
                while (totalBytesRead < TARGET_DOWNLOAD_BYTES && SystemClock.elapsedRealtime() < deadlineMs) {
                    coroutineContext.ensureActive()
                    val remaining = TARGET_DOWNLOAD_BYTES - totalBytesRead
                    val bytesToRead = minOf(buffer.size, remaining)
                    val read = stream.read(buffer, 0, bytesToRead)
                    if (read == -1) break
                    totalBytesRead += read
                }
            }

            if (totalBytesRead <= 0) {
                throw IOException("Zero bytes received")
            }

            val elapsedNanos = (SystemClock.elapsedRealtimeNanos() - startNanos).coerceAtLeast(1_000_000L)
            val elapsedSeconds = elapsedNanos / 1_000_000_000.0
            val bits = totalBytesRead * 8.0
            return (bits / elapsedSeconds) / 1_000_000.0
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveNetworkLabel(
        transportType: Int,
        capabilities: NetworkCapabilities?
    ): String? {
        return when (transportType) {
            NetworkCapabilities.TRANSPORT_WIFI -> {
                val wifiInfo = (capabilities?.transportInfo as? WifiInfo)
                    ?: runCatching { wifiManager?.connectionInfo }.getOrNull()
                val rawSsid = wifiInfo?.ssid?.trim('"')
                if (!rawSsid.isNullOrBlank() && rawSsid != "<unknown ssid>" && rawSsid != WifiManager.UNKNOWN_SSID) {
                    rawSsid
                } else {
                    "Wi-Fi"
                }
            }

            NetworkCapabilities.TRANSPORT_CELLULAR -> {
                val operator = telephonyManager?.networkOperatorName?.takeIf { it.isNotBlank() }
                operator ?: "Mobile Data"
            }

            else -> null
        }
    }
}
