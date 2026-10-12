package io.github.towkirahmedrony.salah.core.domain.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The geometry the location system is built on, tested as pure functions.
 *
 * Two of the distance checks compare against a second, independently written formula (the
 * spherical law of cosines) so a mistake in the haversine implementation alone would be caught rather
 * than agreeing with itself.
 */
class GeoMathTest {

    @Test
    fun `the distance from a point to itself is zero`() {
        val dhaka = GeoPoint(23.8103, 90.4125)

        assertEquals(0.0, GeoMath.distanceMetres(dhaka, dhaka), 0.0)
    }

    @Test
    fun `distance is symmetric and matches an independent haversine`() {
        val dhaka = GeoPoint(23.8103, 90.4125)
        val chattogram = GeoPoint(22.3569, 91.7832)

        val distance = GeoMath.distanceMetres(dhaka, chattogram)

        assertTrue("distance must be positive, got $distance", distance > 0.0)
        assertEquals(distance, GeoMath.distanceMetres(chattogram, dhaka), 1e-6)
        assertEquals(referenceHaversine(dhaka, chattogram), distance, 0.001)
        assertEquals(referenceLawOfCosines(dhaka, chattogram), distance, 5.0)
    }

    @Test
    fun `a short hop between two nearby points is a few kilometres`() {
        // Gulshan to Banani, both inside Dhaka.
        val metres = GeoMath.distanceMetres(GeoPoint(23.8103, 90.4125), GeoPoint(23.8053, 90.4042))

        assertTrue("expected a sub-kilometre hop, got $metres m", metres in 900.0..2_000.0)
    }

    @Test
    fun `one degree of latitude is about 111 km`() {
        val metres = GeoMath.distanceMetres(GeoPoint(0.0, 0.0), GeoPoint(1.0, 0.0))

        // A meridian degree is R * pi / 180 = 111 194.9 m on the sphere GeoMath uses.
        assertTrue("one degree of latitude was $metres m", abs(metres - 111_194.9) < 200.0)
    }

    @Test
    fun `isInside accepts an interior point and rejects points outside the ring`() {
        val square = square()

        assertTrue(GeoMath.isInside(GeoPoint(23.5, 89.0), square))
        assertFalse(GeoMath.isInside(GeoPoint(19.0, 89.0), square))
        assertFalse(GeoMath.isInside(GeoPoint(23.5, 95.0), square))
    }

    @Test
    fun `isInside refuses a ring that cannot enclose an area`() {
        val degenerate = listOf(GeoPoint(0.0, 0.0), GeoPoint(1.0, 1.0))

        assertFalse(GeoMath.isInside(GeoPoint(0.5, 0.5), degenerate))
        assertFalse(GeoMath.isInside(GeoPoint(23.5, 89.0), emptyList()))
    }

    @Test
    fun `isInsideAny is true when any ring contains the point`() {
        val rings = listOf(square(), farAwaySquare())

        assertTrue(GeoMath.isInsideAny(GeoPoint(23.5, 89.0), rings))
        assertTrue(GeoMath.isInsideAny(GeoPoint(10.5, 10.5), rings))
        assertFalse(GeoMath.isInsideAny(GeoPoint(0.0, 0.0), rings))
    }

    @Test
    fun `distanceToBoundaryMetres is zero on an edge and grows with the offset`() {
        val square = square()

        assertEquals(0.0, GeoMath.distanceToBoundaryMetres(GeoPoint(22.0, 89.0), listOf(square)), 1.0)

        // About 2 km east of the lon = 90.0 edge, at latitude 23.5.
        val near = GeoMath.distanceToBoundaryMetres(GeoPoint(23.5, 90.02), listOf(square))
        assertTrue("expected roughly 2 km, got $near m", near in 1_000.0..3_000.0)

        // About 20 km east of the same edge.
        val far = GeoMath.distanceToBoundaryMetres(GeoPoint(23.5, 90.20), listOf(square))
        assertTrue("expected more than 15 km, got $far m", far > 15_000.0)
    }

    @Test
    fun `distanceToBoundaryMetres is effectively unbounded when there are no rings`() {
        assertEquals(
            Double.MAX_VALUE,
            GeoMath.distanceToBoundaryMetres(GeoPoint(0.0, 0.0), emptyList()),
            0.0,
        )
    }

    @Test
    fun `boundsOf covers every vertex and contains interior points`() {
        val bounds = requireNotNull(GeoMath.boundsOf(listOf(square())))

        assertEquals(22.0, bounds.minLatitude, 1e-9)
        assertEquals(25.0, bounds.maxLatitude, 1e-9)
        assertEquals(88.5, bounds.minLongitude, 1e-9)
        assertEquals(90.0, bounds.maxLongitude, 1e-9)

        assertTrue(bounds.contains(GeoPoint(23.5, 89.0)))
        assertFalse(bounds.contains(GeoPoint(19.0, 89.0)))

        // A margin widens the rectangle; without one, a point beyond the edge is excluded.
        assertTrue(bounds.contains(GeoPoint(21.8, 89.0), marginDegrees = 0.3))
        assertFalse(bounds.contains(GeoPoint(21.8, 89.0)))
    }

    @Test
    fun `boundsOf is null without any points`() {
        assertNull(GeoMath.boundsOf(emptyList()))
    }

    @Test
    fun `a point's finiteness requires both coordinates to be in range`() {
        assertTrue(GeoPoint(23.5, 89.0).isFinite)
        assertFalse(GeoPoint(91.0, 89.0).isFinite)
        assertFalse(GeoPoint(23.5, 181.0).isFinite)
        assertFalse(GeoPoint(Double.NaN, 89.0).isFinite)
        assertFalse(GeoPoint(23.5, Double.POSITIVE_INFINITY).isFinite)
    }

    private fun square(): List<GeoPoint> = listOf(
        GeoPoint(22.0, 88.5),
        GeoPoint(22.0, 90.0),
        GeoPoint(25.0, 90.0),
        GeoPoint(25.0, 88.5),
    )

    private fun farAwaySquare(): List<GeoPoint> = listOf(
        GeoPoint(10.0, 10.0),
        GeoPoint(10.0, 11.0),
        GeoPoint(11.0, 11.0),
        GeoPoint(11.0, 10.0),
    )

    private fun referenceHaversine(from: GeoPoint, to: GeoPoint): Double {
        val lat1 = from.latitude.toRadians()
        val lat2 = to.latitude.toRadians()
        val deltaLat = (to.latitude - from.latitude).toRadians()
        val deltaLon = (to.longitude - from.longitude).toRadians()
        val a = sin(deltaLat / 2).let { it * it } +
            cos(lat1) * cos(lat2) * sin(deltaLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_METRES * asin(min(1.0, sqrt(a)))
    }

    private fun referenceLawOfCosines(from: GeoPoint, to: GeoPoint): Double {
        val lat1 = from.latitude.toRadians()
        val lat2 = to.latitude.toRadians()
        val deltaLon = (to.longitude - from.longitude).toRadians()
        val centralAngle = acos(
            max(
                -1.0,
                min(
                    1.0,
                    sin(lat1) * sin(lat2) + cos(lat1) * cos(lat2) * cos(deltaLon),
                ),
            ),
        )
        return EARTH_RADIUS_METRES * centralAngle
    }

    private fun Double.toRadians(): Double = this * PI / 180.0

    private companion object {
        /** IUGG mean earth radius in metres, matching `GeoMath.EARTH_RADIUS_METRES`. */
        const val EARTH_RADIUS_METRES: Double = 6_371_008.8
    }
}
