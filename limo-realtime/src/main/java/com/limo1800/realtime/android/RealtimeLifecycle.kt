package com.limo1800.realtime.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.limo1800.realtime.client.RealtimeClient

/**
 * Ties [RealtimeClient] to the device:
 *
 * - **Network:** when the *default* network changes (Wi-Fi ↔ LTE, back from airplane mode),
 *   reconnect at once instead of waiting for the old socket to time out — it is bound to the
 *   network that just went away. The callback Android fires for the current network at
 *   registration is ignored.
 * - **App state:** foreground/background go to the server as `app_state` (it pushes instead of
 *   relying on the socket while backgrounded), and a foreground with a dead socket reconnects
 *   at once.
 *
 * [onNetworkAvailable] lets the app drain its outbox on the same signal.
 * Call [start] once from `Application.onCreate` (main thread).
 */
class RealtimeLifecycle(
    context: Context,
    private val client: RealtimeClient,
    private val onNetworkAvailable: () -> Unit = {},
    private val onForeground: () -> Unit = {},
) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    @Volatile private var currentNetwork: Network? = null
    private var started = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val previous = currentNetwork
            currentNetwork = network
            // Same network as at registration: nothing changed. Anything else (a new default
            // network, or the first one after being offline) means the old socket is useless.
            if (previous != network) client.reconnectNow("default network changed")
            onNetworkAvailable()
        }

        override fun onLost(network: Network) {
            if (network == currentNetwork) currentNetwork = null
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = Unit
    }

    private val appObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            client.onForeground()
            onForeground()
        }

        override fun onStop(owner: LifecycleOwner) {
            client.onBackground()
        }
    }

    fun start() {
        if (started) return
        started = true
        currentNetwork = connectivity?.activeNetwork
        runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback) }
        ProcessLifecycleOwner.get().lifecycle.addObserver(appObserver)
    }
}
