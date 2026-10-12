package io.github.towkirahmedrony.salah.core.domain.location

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A latitude/longitude pair in degrees. Order is latitude first, matching every other model here. */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
) {
    val isFinite: Boolean
        get() = latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0
}

/**
 * The geometry Salah's location system needs, as pure functions.
 *
 * **Why this is hand-written rather than a library.** Three things are needed: a distance between two
 * points, whether a point is inside a polygon, and how far a point is from a polygon's edge. All
 * three are a few lines of well-understood trigonometry, and the alternative — a geometry dependency
 * — would add a library (and a JVM-target constraint) to answer questions this app asks about one
 * country. The tests in `GeoMathTest` check the results against independently computed reference
 * values, including cases where an approximate formula would visibly fail.
 *
 * **The approximation is stated, not hidden.** [distanceMetres] uses the haversine formula on a
 * spherical earth of radius [EARTH_RADIUS_METRES]; that is accurate to about 0.3% against the
 * ellipsoid, i.e. a few hundred metres over Bangladesh's ~800 km extent. For deciding "is this fix
 * inside Bangladesh" and "which upazila centre is nearest", that error is far below the decisions'
 * own resolution, and the boundary check carries an explicit tolerance rather than pretending to be
 * exact.
 */
object GeoMath {

    /** Mean earth radius, in metres (IUGG mean radius). */
    const val EARTH_RADIUS_METRES: Double = 6_371_008.8

    /** Great-circle distance between two points, in metres. */
    fun distanceMetres(from: GeoPoint, to: GeoPoint): Double {
        val lat1 = from.latitude.toRadians()
        val lat2 = to.latitude.toRadians()
        val deltaLat = (to.latitude - from.latitude).toRadians()
        val deltaLon = (to.longitude - from.longitude).toRadians()

        val a = sin(deltaLat / 2).let { it * it } +
            cos(lat1) * cos(lat2) * sin(deltaLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_METRES * asin(min(1.0, sqrt(a)))
    }

    /**
     * Whether [point] is inside [ring], by ray casting.
     *
     * The ring is treated as closed whether or not its last vertex repeats the first. A point exactly
     * on an edge may be reported either way; callers that care use [distanceToBoundaryMetres] with an
     * explicit tolerance instead of relying on this answer at the boundary, which is what
     * [BangladeshLocationPolicy] does.
     */
    fun isInside(point: GeoPoint, ring: List<GeoPoint>): Boolean {
        if (ring.size < 3) return false
        var inside = false
        var index = 0
        var previous = ring.last()
        while (index < ring.size) {
            val current = ring[index]
            if ((current.latitude > point.latitude) != (previous.latitude > point.latitude)) {
                val intersectionLongitude = current.longitude +
                    (point.latitude - current.latitude) *
                    (previous.longitude - current.longitude) /
                    (previous.latitude - current.latitude)
                if (point.longitude < intersectionLongitude) inside = !inside
            }
            previous = current
            index++
        }
        return inside
    }

    /** Whether [point] is inside any of [rings] — the test for a polygon with holes or islands. */
    fun isInsideAny(point: GeoPoint, rings: List<List<GeoPoint>>): Boolean =
        rings.any { ring -> isInside(point, ring) }

    /**
     * Shortest distance from [point] to any edge of [rings], in metres.
     *
     * Used only to widen the boundary test by a stated tolerance, so a fix a few hundred metres
     * outside a simplified border is not reported as being in another country. Segments are measured
     * in a local equirectangular projection centred on the point: over a few kilometres the error is
     * far below the tolerance being applied.
     */
    fun distanceToBoundaryMetres(point: GeoPoint, rings: List<List<GeoPoint>>): Double {
        if (rings.isEmpty()) return Double.MAX_VALUE
        var best = Double.MAX_VALUE
        for (ring in rings) {
            if (ring.size < 2) continue
            var previous = ring.last()
            for (current in ring) {
                best = min(best, distanceToSegmentMetres(point, previous, current))
                previous = current
            }
        }
        return best
    }

    private fun distanceToSegmentMetres(point: GeoPoint, start: GeoPoint, end: GeoPoint): Double {
        val metresPerDegreeLat = EARTH_RADIUS_METRES * PI / 180.0
        val metresPerDegreeLon = metresPerDegreeLat * cos(point.latitude.toRadians())

        val ax = (start.longitude - point.longitude) * metresPerDegreeLon
        val ay = (start.latitude - point.latitude) * metresPerDegreeLat
        val bx = (end.longitude - point.longitude) * metresPerDegreeLon
        val by = (end.latitude - point.latitude) * metresPerDegreeLat

        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0.0) return sqrt(ax * ax + ay * ay)

        val t = max(0.0, min(1.0, -(ax * dx + ay * dy) / lengthSquared))
        val closestX = ax + t * dx
        val closestY = ay + t * dy
        return sqrt(closestX * closestX + closestY * closestY)
    }

    /** Bounding box of [rings], or `null` when there are none. */
    fun boundsOf(rings: List<List<GeoPoint>>): GeoBounds? {
        val points = rings.flatten()
        if (points.isEmpty()) return null
        return GeoBounds(
            minLatitude = points.minOf { it.latitude },
            maxLatitude = points.maxOf { it.latitude },
            minLongitude = points.minOf { it.longitude },
            maxLongitude = points.maxOf { it.longitude },
        )
    }

    private fun Double.toRadians(): Double = this * PI / 180.0
}

/** An axis-aligned latitude/longitude rectangle. */
data class GeoBounds(
    val minLatitude: Double,
    val maxLatitude: Double,
    val minLongitude: Double,
    val maxLongitude: Double,
) {
    /** True when [point] is inside the rectangle, with [marginDegrees] of slack on every side. */
    fun contains(point: GeoPoint, marginDegrees: Double = 0.0): Boolean =
        abs(point.latitude - centerLatitude) <= latitudeSpan / 2 + marginDegrees &&
            abs(point.longitude - centerLongitude) <= longitudeSpan / 2 + marginDegrees

    val centerLatitude: Double get() = (minLatitude + maxLatitude) / 2
    val centerLongitude: Double get() = (minLongitude + maxLongitude) / 2
    val latitudeSpan: Double get() = maxLatitude - minLatitude
    val longitudeSpan: Double get() = maxLongitude - minLongitude
}
