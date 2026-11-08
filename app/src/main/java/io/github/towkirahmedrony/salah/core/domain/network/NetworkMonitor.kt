package io.github.towkirahmedrony.salah.core.domain.network

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Connectivity as an observable flow, for screens that must react when the network returns.
 *
 * [NetworkStatusProvider] answers one cheap question at a moment in time, which is all a synchroniser
 * needs before a request. It cannot tell a screen "the connection is back", so the mosque finder would
 * otherwise sit on cached results until the user pressed refresh. This port is that missing signal.
 *
 * **The observer is scoped, not global.** An implementation registers a platform callback for as long
 * as the returned flow is collected and unregisters it when collection stops, so the app holds no
 * connectivity observer between screens. The first emission is the current state, so a collector never
 * has to wait for a change to learn whether it starts offline.
 *
 * Like [NetworkStatusProvider], the value is a hint. `true` means "a network claims to be connected",
 * never "the service is reachable"; every request still has to survive a captive portal or a timeout.
 */
interface NetworkMonitor {

    /** Connectivity changes, starting with the current state. Emits distinct values only. */
    fun connectivity(): Flow<Boolean>
}

/**
 * The permissive default: one emission, always online.
 *
 * Used where no platform observer is available (and by every test that does not care about
 * connectivity), so nothing is ever suppressed on the strength of a fabricated "offline".
 */
object AlwaysOnlineNetworkMonitor : NetworkMonitor {
    override fun connectivity(): Flow<Boolean> = flowOf(true)
}
