package com.m3.pocketmusic

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.work.Constraints
import androidx.work.NetworkType
import java.io.Closeable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object DownloadPolicy {
    fun allows(wifiOnly: Boolean, connected: Boolean, wifi: Boolean, cellular: Boolean) =
        connected && (!wifiOnly || (wifi && !cellular))
    fun constraints(wifiOnly: Boolean): Constraints = Constraints.Builder().apply {
        if (wifiOnly) setRequiredNetworkRequest(NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), NetworkType.CONNECTED)
        else setRequiredNetworkType(NetworkType.CONNECTED)
    }.build()
}

class DownloadDeferred : IOException("Waiting for an allowed download connection")

/** Use the app's default network (including its VPN), never bypass a VPN to find Wi-Fi. */
class DownloadNetwork(context: Context, private val state: () -> LibraryState, private val stopped: () -> Boolean) : Closeable {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    @Volatile private var connection: HttpURLConnection? = null
    private val monitor = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) { interruptIfBlocked() }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { interruptIfBlocked() }
        override fun onAvailable(network: Network) { interruptIfBlocked() }
    }
    init {
        manager.registerDefaultNetworkCallback(callback)
        // Also observe pause/policy changes and cancellation while a socket read is blocked.
        monitor.launch { while (isActive) { delay(200); interruptIfBlocked() } }
    }
    fun allowed(): Boolean {
        val s = state()
        val caps = manager.getNetworkCapabilities(manager.activeNetwork)
        return !s.offline && !s.downloadsPaused && DownloadPolicy.allows(s.wifiOnlyDownloads, caps != null,
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true)
    }
    fun check() {
        if (stopped()) throw CancellationException()
        if (!allowed()) throw DownloadDeferred()
    }
    private fun interruptIfBlocked() { if (stopped() || !allowed()) connection?.disconnect() }
    fun open(url: URL): HttpURLConnection {
        check()
        val network = manager.activeNetwork ?: throw DownloadDeferred()
        val caps = manager.getNetworkCapabilities(network)
        if (!DownloadPolicy.allows(state().wifiOnlyDownloads, caps != null,
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true)) throw DownloadDeferred()
        // Pin each request to the checked network so reconnects cannot fall back to cellular.
        val result = network.openConnection(url) as HttpURLConnection
        connection = result
        try { check() } catch (e: Exception) { result.disconnect(); throw e }
        return result
    }
    fun interrupt() { connection?.disconnect() }
    override fun close() { monitor.cancel(); manager.unregisterNetworkCallback(callback); interrupt() }
}
