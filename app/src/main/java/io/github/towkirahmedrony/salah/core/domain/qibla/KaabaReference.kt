package io.github.towkirahmedrony.salah.core.domain.qibla

/**
 * The geographic reference the Qibla bearing is measured towards.
 *
 * **Source of the coordinates.** `21°25′21″N 39°49′34″E`, published as `21.4225°N 39.82617°E` in
 * the coordinate block of the English Wikipedia article *Kaaba* (GeoHack parameters
 * `21.4225_N_39.82617_E`, region `SA`). That article's coordinates are the widely mirrored value
 * for the structure, and they agree with the commonly cited `21.4225, 39.8262` to within a
 * ten-thousandth of a degree.
 *
 * **Why one point is enough.** The Kaaba's footprint is about 12.86 m by 11.03 m, so any single
 * representative point lies within roughly 7 m of the structure's centre. Salah uses the
 * north-east corner region of the published coordinate rather than attempting to model the
 * building.
 *
 * **What that error costs.** A lateral displacement of 7 m changes the bearing by at most
 * `atan(7 m / d)`. At 100 km that is 0.004°; at 1 km it is 0.4°; at 10 m it is 35°. So the
 * coordinate choice is irrelevant at any realistic distance and only becomes visible immediately
 * adjacent to the building — which is why this file also defines the separation below which Salah
 * refuses to report a direction at all rather than printing a number that would be noise.
 */
object KaabaReference {

    /** Kaaba latitude in degrees, positive north. */
    const val LATITUDE_DEGREES: Double = 21.4225

    /** Kaaba longitude in degrees, positive east. */
    const val LONGITUDE_DEGREES: Double = 39.82617

    /**
     * The shortest separation, in metres, at which a bearing is still meaningful.
     *
     * Below this the direction from the observer to the Kaaba is dominated by the choice of
     * reference point and by the observer's own position error, so any figure printed would be
     * false precision. One metre is deliberately small: it is the point at which the answer stops
     * being *about* the building at all, not a claim about compass accuracy.
     */
    const val MINIMUM_SEPARATION_METRES: Double = 1.0

    /** Human-readable provenance, shown in the UI and repeated in `docs/QIBLA_COMPASS.md`. */
    const val SOURCE: String = "Wikipedia, \"Kaaba\" coordinate block (21.4225°N 39.82617°E)"
}
