package io.github.towkirahmedrony.salah.core.domain.qibla

/**
 * The result of a Qibla bearing calculation.
 *
 * A sealed result rather than a `Double?`, because "there is no direction" has three genuinely
 * different causes and the UI must not conflate them with "not calculated yet".
 */
sealed interface QiblaBearing {

    /**
     * A bearing is defined.
     *
     * @param degrees the **initial great-circle bearing** from the observer to the Kaaba, in
     *   degrees clockwise from **true north**, in `[0, 360)`.
     * @param distanceMetres the great-circle distance to the Kaaba, on the same sphere.
     */
    data class Available(
        val degrees: Double,
        val distanceMetres: Double,
    ) : QiblaBearing

    /** No bearing exists, for the stated reason. */
    data class Unavailable(val reason: QiblaUnavailableReason) : QiblaBearing
}

/** Why a Qibla bearing could not be produced. */
enum class QiblaUnavailableReason {

    /**
     * The coordinates are outside the valid ranges, or are not finite.
     *
     * Rejected rather than clamped: a latitude of 91° is a bug in the caller, not a position near
     * the pole, and silently clamping it would answer a question nobody asked.
     */
    INVALID_COORDINATES,

    /**
     * The observer is standing on the Kaaba reference point (within
     * [KaabaReference.MINIMUM_SEPARATION_METRES]).
     *
     * The direction from a point to itself is undefined, and it is numerically unstable just next
     * to it. Salah says so instead of printing a number.
     */
    AT_KAABA,

    /**
     * The observer is (numerically) at the antipode of the Kaaba, near `-21.4225, -140.17383` in
     * the South Pacific.
     *
     * There the true initial bearing is genuinely ill-conditioned: every direction at that point is
     * an equal-length great-circle path to the Kaaba, and the formula's horizontal component
     * vanishes. For the exact antipode `atan2(0, 0)` returns `0.0` rather than failing, which would
     * have been a silently wrong answer of "due north" — so the degeneracy is detected explicitly.
     */
    ANTIPODAL,
}
