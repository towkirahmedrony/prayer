package io.github.towkirahmedrony.salah.core.domain.network

/**
 * Whether the device currently has a usable network, as a port.
 *
 * The remote synchronisers ask this *before* attempting a fetch so a device that is plainly offline is
 * not made to pay for a doomed request on every foreground trip. The answer is a hint, never a
 * guarantee: [isOnline] returning `true` only means "a network claims to be connected", and every
 * remote call is still written to survive a DNS failure, a captive portal, a reset or a timeout. The
 * concern this port addresses is the *cheap* decision — "is there obviously no network?" — and nothing
 * more.
 *
 * **Why a port and not a direct `ConnectivityManager` call.** The synchronisers live in `core.domain`
 * and must stay Android-free so their rules can be tested with a fake rather than a mocked framework.
 * The Android implementation lives in `data.network`; the domain default, [AlwaysOnlineNetworkStatusProvider],
 * is deliberately permissive so a test (or a build with no connectivity observer) never accidentally
 * suppresses a check.
 */
interface NetworkStatusProvider {

    /**
     * True unless the platform is *certain* there is no network.
     *
     * Implementations must fail open: anything unknown — a missing service, an inconclusive capability
     * query — returns `true`, because the app's job is to keep working, and a false "offline" would
     * silently stop remote data from ever being refreshed.
     */
    suspend fun isOnline(): Boolean
}

/**
 * The default, permissive implementation: always reports a network.
 *
 * Used as the constructor default of both synchronisers so an existing caller (and every unit test that
 * does not care about connectivity) keeps the pre-existing behaviour of attempting the request and
 * letting the transport decide. It introduces no dependency and no `android.*` import, so it is safe in
 * the domain layer.
 */
object AlwaysOnlineNetworkStatusProvider : NetworkStatusProvider {
    override suspend fun isOnline(): Boolean = true
}
