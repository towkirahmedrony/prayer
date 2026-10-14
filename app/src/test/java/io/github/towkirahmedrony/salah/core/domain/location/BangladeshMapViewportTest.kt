package io.github.towkirahmedrony.salah.core.domain.location

import io.github.towkirahmedrony.salah.testing.BundledDataset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The camera constraint, and its relationship to the real country check.
 *
 * These two must not be confused: the viewport keeps the *camera* over Bangladesh, and the polygon test
 * decides whether a *point* is in the country. The last test here is the important one — it confirms the
 * camera rectangle is genuinely wider than the country, so a user who pans to a corner is still shown a
 * point that the polygon check can correctly reject.
 */
class BangladeshMapViewportTest {

    @Test
    fun `the stored centre is inside the camera bounds`() {
        assertTrue(BangladeshMapViewport.containsCameraPoint(BangladeshMapViewport.CENTER))
    }

    @Test
    fun `a point outside the rectangle is clamped into it`() {
        val clamped = BangladeshMapViewport.clampToCamera(GeoPoint(latitude = 10.0, longitude = 10.0))
        assertTrue(BangladeshMapViewport.containsCameraPoint(clamped))
        assertEquals(BangladeshMapViewport.SOUTH, clamped.latitude, 1e-9)
        assertEquals(BangladeshMapViewport.WEST, clamped.longitude, 1e-9)
    }

    @Test
    fun `a point inside the rectangle is unchanged by clamping`() {
        val point = GeoPoint(latitude = 23.7, longitude = 90.4)
        assertEquals(point, BangladeshMapViewport.clampToCamera(point))
    }

    @Test
    fun `a non-finite point is not inside the rectangle`() {
        assertFalse(BangladeshMapViewport.containsCameraPoint(GeoPoint(Double.NaN, Double.NaN)))
    }

    @Test
    fun `the camera rectangle contains the whole real country`() {
        // Every boundary vertex must be inside the camera rectangle, or a pan to the edge of Bangladesh
        // would be blocked by the map before it reached the coast.
        val dataset = BundledDataset.load()
        val cornersOutside = dataset.boundaryRings
            .flatten()
            .filterNot { BangladeshMapViewport.containsCameraPoint(it) }
        assertTrue("boundary vertices outside the camera rectangle: $cornersOutside", cornersOutside.isEmpty())
    }

    @Test
    fun `the camera rectangle is wider than the country, so the polygon check still decides`() {
        // The rectangle deliberately covers a little of the neighbours and the Bay of Bengal. A point in
        // that margin is inside the camera bounds but outside the country, which is exactly why the
        // camera is not used as the validation.
        val dataset = BundledDataset.load()
        val marginPoint = GeoPoint(latitude = BangladeshMapViewport.NORTH, longitude = BangladeshMapViewport.EAST)
        assertTrue(BangladeshMapViewport.containsCameraPoint(marginPoint))
        val check = BangladeshLocationPolicy.check(marginPoint, dataset.boundaryRings)
        assertTrue("the rectangle corner must not be classified as inside", check !is BangladeshCheck.Inside)
    }
}
