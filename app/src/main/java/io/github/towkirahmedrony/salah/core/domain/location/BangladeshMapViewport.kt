package io.github.towkirahmedrony.salah.core.domain.location

import kotlin.math.max
import kotlin.math.min

/**
 * The initial camera and the camera constraint for the map picker.
 *
 * **Two different things, kept apart on purpose.**
 *
 *  - These bounds keep the *camera* over the country, so the picker opens on Bangladesh and panning
 *    cannot wander off to the Bay of Bengal and back. They are a rectangle, and a generous one — the map
 *    should show a little of the border and the coast around the country.
 *  - They are **not** the validation boundary. Whether a selected point is in Bangladesh is decided by
 *    [BangladeshLocationPolicy] against the bundled national polygon (`bd_boundary.tsv`, 13 rings), which
 *    is the only thing in Salah that establishes containment and which handles the country's complex
 *    coastal geography. A rectangle cannot, which is why the two are not the same value and why nothing
 *    here is ever reported to the user as "inside Bangladesh".
 *
 * The numbers are the country's own extent with a small margin, and the camera constraint is applied as
 * a *limit* by the map itself, so a drag simply stops rather than being corrected after the fact.
 */
object BangladeshMapViewport {

    /** Latitude/longitude centre used when no location is stored yet. */
    val CENTER: GeoPoint = GeoPoint(latitude = 23.6850, longitude = 90.3563)

    /** The zoom the picker opens at: the whole country, with its divisions readable. */
    const val INITIAL_ZOOM: Double = 6.4

    /** Zoom used when centring on a specific point (a stored location, or the device's own). */
    const val FOCUS_ZOOM: Double = 13.0

    /** Lower bound of the camera's zoom, so the country always fills a useful part of the screen. */
    const val MIN_ZOOM: Double = 4.5

    /** Upper bound of the camera's zoom; beyond ~19 the raster/vector tiles carry no more detail. */
    const val MAX_ZOOM: Double = 18.0

    /** Southern edge of the camera constraint, west of the Bay of Bengal margin. */
    const val SOUTH: Double = 20.40

    /** Northern edge of the camera constraint. */
    const val NORTH: Double = 26.80

    /** Western edge of the camera constraint. */
    const val WEST: Double = 87.80

    /** Eastern edge of the camera constraint. */
    const val EAST: Double = 92.90

    /** True when [point] is inside the camera rectangle (a viewport concern, never a country check). */
    fun containsCameraPoint(point: GeoPoint): Boolean =
        point.isFinite &&
            point.latitude in SOUTH..NORTH &&
            point.longitude in WEST..EAST

    /** [point] clamped into the camera rectangle, so a camera move always lands over the country. */
    fun clampToCamera(point: GeoPoint): GeoPoint = GeoPoint(
        latitude = min(max(point.latitude, SOUTH), NORTH),
        longitude = min(max(point.longitude, WEST), EAST),
    )
}
