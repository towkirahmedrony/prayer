package io.github.towkirahmedrony.salah.core.domain.qibla

/**
 * The geographic reference the Qibla bearing is measured towards.
 *
 * **The coordinate.** `21.4225° N, 39.8262° E` — the reference this project specifies, and the
 * value its tests and documentation are written against.
 *
 * **Where it comes from, and what the last digit costs.** The English Wikipedia article *Kaaba*
 * publishes the structure as `21°25′21″N 39°49′34″E`, with the GeoHack decimal parameter
 * `21.4225_N_39.82617_E` (region `SA`). That decimal longitude sits `0.00003°` east of this
 * constant — about **3.1 m** at the Kaaba's latitude — because this constant is that published
 * value rounded to the four decimal places the specification states.
 *
 * The offset moves the bearing by at most `atan(3.1 m / d)`: `0.00004°` from Dhaka, `0.0002°` from
 * Cairo, under `0.0002°` from anywhere beyond 1 000 km — all far below the compass's own
 * uncertainty, so the two reference points are indistinguishable at every distance where a
 * bearing is meaningful. `QiblaCalculatorTest` pins the resulting bearings.
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
    const val LONGITUDE_DEGREES: Double = 39.8262

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
    const val SOURCE: String = "Wikipedia, \"Kaaba\" coordinate block (21.4225°N 39.8262°E)"
}
