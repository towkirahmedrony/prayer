package io.github.towkirahmedrony.salah.core.domain.qibla

import kotlin.time.Duration

/**
 * How much precision the user has allowed Salah.
 *
 * Both are modelled because the two are materially different for this feature, and Android itself
 * makes the distinction on API 31+ when the user picks "Approximate" or "Precise" in the runtime
 * dialog.
 *
 * **Why approximate is enough for the Qibla.** Bearing error from a position error is roughly
 * `positionError / distance`, so a coarse fix accurate to 2 km shifts the bearing from Dhaka
 * (5 172 km away) by about 0.02° — three orders of magnitude below the compass's own uncertainty.
 * Even a 50 km error changes it by only 0.5°. Coarse location is therefore not a compromise for
 * direction; it matters for *prayer times*, where 2 km can move a time by tens of seconds.
 */
enum class LocationPrecision { APPROXIMATE, PRECISE }

/**
 * The result of asking the platform for a position.
 *
 * A sealed result so every failure is a value the UI can explain, rather than an exception that
 * surfaces as a spinner that never stops.
 */
sealed interface LocationAcquisitionResult {

    /** A position was obtained. */
    data class Acquired(
        val latitude: Double,
        val longitude: Double,
        /** Horizontal accuracy in metres, or `null` when the provider did not report one. */
        val accuracyMetres: Double?,
        val acquiredAtEpochMillis: Long,
    ) : LocationAcquisitionResult

    /**
     * A fix could not be obtained inside the timeout.
     *
     * Distinct from [Failed]: the device may be perfectly capable and simply unable to see the sky
     * indoors, so the message is "could not get a fix", not "broken".
     */
    data object TimedOut : LocationAcquisitionResult

    /** The user has not granted location permission. */
    data object PermissionDenied : LocationAcquisitionResult

    /** Location is switched off for the whole device. */
    data object ServicesDisabled : LocationAcquisitionResult

    /** The provider reported an error. */
    data class Failed(val detail: String) : LocationAcquisitionResult
}

/**
 * The device's own position, as a port.
 *
 * Deliberately *one-shot*, not a stream: the Qibla feature needs a position once per request, and a
 * continuous location subscription would drain the battery to answer a question that does not
 * change while the user stands still. The saved location is what the screen reads thereafter — which
 * is also what keeps the feature working offline.
 */
interface LocationProvider {

    /** Whether location is enabled for the device as a whole. */
    fun servicesEnabled(): Boolean

    /**
     * Requests a single position, giving up after [timeout].
     *
     * Implementations must not leave a location listener registered after returning, and must
     * return rather than throw on every failure path.
     */
    suspend fun currentLocation(timeout: Duration): LocationAcquisitionResult
}

/**
 * How the user has answered the location permission prompts.
 *
 * Read as state rather than requested: Salah asks only when the user taps a button on the Qibla or
 * Settings screen, and re-reads this afterwards to see what happened.
 */
enum class LocationPermissionState {

    /**
     * No location permission. Manual coordinates remain usable — the Qibla bearing needs a
     * position, not a permission.
     */
    DENIED,

    /** Only `ACCESS_COARSE_LOCATION`: roughly suburb-level, and plenty for a bearing. */
    COARSE,

    /** `ACCESS_FINE_LOCATION`: the user chose precise. */
    PRECISE,
    ;

    /** True when automatic location is possible at all. */
    val isGranted: Boolean get() = this != DENIED

    /** The precision actually available, or `null` when nothing is granted. */
    val precision: LocationPrecision?
        get() = when (this) {
            DENIED -> null
            COARSE -> LocationPrecision.APPROXIMATE
            PRECISE -> LocationPrecision.PRECISE
        }
}

/** Reads the current location permission state. */
interface LocationPermissionChecker {

    /** Never prompts; only reports what has already been decided. */
    fun state(): LocationPermissionState
}
