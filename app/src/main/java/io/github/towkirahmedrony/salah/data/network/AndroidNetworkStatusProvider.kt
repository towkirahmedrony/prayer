package io.github.towkirahmedrony.salah.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.github.towkirahmedrony.salah.core.domain.network.NetworkStatusProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The platform implementation of [NetworkStatusProvider], over `ConnectivityManager`.
 *
 * **What it answers, and what it does not.** It answers the cheap question — "is there *obviously* no
 * network?" — and nothing else. It reports `true` whenever the answer is anything but a confident "no":
 * if the service is missing, if there is no active network, or if the active network lacks the internet
 * capability it returns `false`; every other case returns `true`. It deliberately does **not** require the
 * `VALIDATED` capability, because a freshly connected network is momentarily unvalidated and treating that
 * as offline would suppress a request that would have succeeded. A connected-but-captive network therefore
 * passes this gate and is caught by the transport's own failure handling, which is where a captive portal
 * belongs.
 *
 * Reading `activeNetwork` and its capabilities needs `ACCESS_NETWORK_STATE`, a normal (install-time)
 * permission declared in the manifest. No location or runtime permission is involved, and the answer is
 * never logged with anything that identifies a network.
 *
 * The query is run off the main thread because it is a binder call; it is a single synchronous lookup, not
 * a registered callback, so Salah holds no continuous connectivity observer and is woken for nothing.
 */
class AndroidNetworkStatusProvider(
    private val context: Context,
) : NetworkStatusProvider {

    override suspend fun isOnline(): Boolean = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            // No service (a stripped-down or test environment): fail open, so the request is attempted and
            // the transport reports the real outcome rather than a fabricated "offline".
            ?: return@withContext true

        val network = manager.activeNetwork ?: return@withContext false
        val capabilities = manager.getNetworkCapabilities(network) ?: return@withContext false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
