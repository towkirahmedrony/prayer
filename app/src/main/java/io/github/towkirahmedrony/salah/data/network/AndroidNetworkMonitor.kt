package io.github.towkirahmedrony.salah.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.github.towkirahmedrony.salah.core.domain.network.NetworkMonitor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Connectivity changes from `ConnectivityManager`, as a scoped flow.
 *
 * **Registered only while collected.** The callback is registered when collection starts and
 * unregistered in `awaitClose`, so the observer lives exactly as long as the screen that needs it.
 * That is a deliberate change from the app's other network use: the dataset synchronisers make a
 * one-shot lookup before a request, while the mosque finder has to notice the connection returning
 * and refresh by itself.
 *
 * **The same permissive reading as [AndroidNetworkStatusProvider].** A network without the internet
 * capability is "offline"; anything inconclusive is "online", so a captive portal or a momentarily
 * unvalidated network is left to the transport to fail rather than being reported as offline. The
 * first emission is the current state, so a collector that starts with no connection is not left
 * waiting for a change.
 *
 * `ACCESS_NETWORK_STATE` is the only permission involved, and it is already declared.
 */
class AndroidNetworkMonitor(
    private val context: Context,
) : NetworkMonitor {

    override fun connectivity(): Flow<Boolean> = callbackFlow {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (manager == null) {
            // No connectivity service (a stripped-down environment): report online and never claim a
            // network change. Fail-open matches the rest of the app's connectivity handling.
            trySend(true)
            awaitClose { }
            return@callbackFlow
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(manager.isOnline())
            }

            override fun onLost(network: Network) {
                trySend(manager.isOnline())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                trySend(networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            }
        }

        trySend(manager.isOnline())
        manager.registerDefaultNetworkCallback(callback)

        awaitClose { manager.unregisterNetworkCallback(callback) }
    }
        .distinctUntilChanged()
        .conflate()

    private fun ConnectivityManager.isOnline(): Boolean {
        val network = activeNetwork ?: return false
        val capabilities = getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
