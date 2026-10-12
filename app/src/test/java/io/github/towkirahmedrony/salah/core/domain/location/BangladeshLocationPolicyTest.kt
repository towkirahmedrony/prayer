package io.github.towkirahmedrony.salah.core.domain.location

import io.github.towkirahmedrony.salah.testing.BundledDataset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos

/**
 * The Bangladesh-only boundary policy, and the distinction the audit found missing.
 *
 * The policy accepts a point either because it is *inside* the polygon or because it is *within the
 * tolerance* of it. Only the first is containment; the second is a GPS/simplification allowance that
 * also covers territory just over the border. These tests pin that the two are not conflated, using
 * both a synthetic square (exact, obvious geometry) and the real bundled boundary.
 */
class BangladeshLocationPolicyTest {

    private val bundled = BundledDataset.load(version = "test-bundled")

    @Test
    fun `a point inside the polygon is Inside`() {
        assertEquals(
            BangladeshCheck.Inside,
            BangladeshLocationPolicy.check(GeoPoint(23.5, 89.0), listOf(square())),
        )
    }

    @Test
    fun `a point well outside the polygon is Outside with a measured distance`() {
        val check = BangladeshLocationPolicy.check(GeoPoint(23.5, 95.0), listOf(square()))

        assertTrue("expected Outside, got $check", check is BangladeshCheck.Outside)
        assertTrue("distance must exceed the tolerance", (check as BangladeshCheck.Outside).distanceMetres > 5_000.0)
    }

    @Test
    fun `a point just outside the polygon but within the tolerance is WithinBoundaryTolerance`() {
        // ~2 km east of the lon = 90.0 edge.
        val check = BangladeshLocationPolicy.check(GeoPoint(23.5, 90.02), listOf(square()))

        assertTrue("expected WithinBoundaryTolerance, got $check", check is BangladeshCheck.WithinBoundaryTolerance)
        val distance = (check as BangladeshCheck.WithinBoundaryTolerance).distanceMetres
        assertTrue("distance $distance should be small but positive", distance in 1.0..BangladeshLocationPolicy.BOUNDARY_TOLERANCE_METRES)
    }

    @Test
    fun `a point beyond the tolerance is Outside, not tolerance-accepted`() {
        // ~20 km east of the same edge.
        val check = BangladeshLocationPolicy.check(GeoPoint(23.5, 90.20), listOf(square()))

        assertTrue("expected Outside, got $check", check is BangladeshCheck.Outside)
    }

    @Test
    fun `tolerance-acceptance is never reported as containment`() {
        val inside = BangladeshLocationPolicy.check(GeoPoint(23.5, 89.0), listOf(square()))
        val tolerated = BangladeshLocationPolicy.check(GeoPoint(23.5, 90.02), listOf(square()))

        assertEquals(BangladeshCheck.Inside, inside)
        assertTrue(tolerated !is BangladeshCheck.Inside)
        assertTrue(tolerated !is BangladeshCheck.Outside)
    }

    @Test
    fun `an unreadable or empty boundary cannot establish anything`() {
        assertEquals(
            BangladeshCheck.BoundaryUnavailable,
            BangladeshLocationPolicy.check(GeoPoint(23.5, 89.0), emptyList()),
        )
        assertEquals(
            BangladeshCheck.BoundaryUnavailable,
            BangladeshLocationPolicy.check(GeoPoint(Double.NaN, 89.0), listOf(square())),
        )
    }

    @Test
    fun `the coarse bounds reject far-away points and accept the whole country`() {
        assertTrue(BangladeshLocationPolicy.isWithinCoarseBounds(GeoPoint(23.8103, 90.4125)))
        assertTrue(BangladeshLocationPolicy.isWithinCoarseBounds(GeoPoint(26.9, 92.9)))
        assertFalse(BangladeshLocationPolicy.isWithinCoarseBounds(GeoPoint(19.9, 89.0)))
        assertFalse(BangladeshLocationPolicy.isWithinCoarseBounds(GeoPoint(27.1, 89.0)))
        assertFalse(BangladeshLocationPolicy.isWithinCoarseBounds(GeoPoint(51.5074, -0.1278)))
        assertFalse(BangladeshLocationPolicy.isWithinCoarseBounds(GeoPoint(Double.NaN, 89.0)))
    }

    @Test
    fun `the policy constants are the documented ones`() {
        assertEquals("BD", BangladeshLocationPolicy.COUNTRY_CODE)
        assertEquals("Asia/Dhaka", BangladeshLocationPolicy.TIME_ZONE_ID)
        assertEquals(5_000.0, BangladeshLocationPolicy.BOUNDARY_TOLERANCE_METRES, 0.0)
    }

    @Test
    fun `real Bangladeshi cities are inside the bundled boundary`() {
        listOf(
            "Dhaka" to GeoPoint(23.8103, 90.4125),
            "Chattogram" to GeoPoint(22.3569, 91.7832),
            "Sylhet" to GeoPoint(24.8949, 91.8687),
            "Cox's Bazar" to GeoPoint(21.4397, 92.0058),
            "Khulna" to GeoPoint(22.8409, 89.5621),
        ).forEach { (name, point) ->
            assertEquals(name, BangladeshCheck.Inside, BangladeshLocationPolicy.check(point, bundled.boundaryRings))
        }
    }

    @Test
    fun `a city far outside the border is Outside with a real distance`() {
        val check = BangladeshLocationPolicy.check(GeoPoint(22.5726, 88.3639), bundled.boundaryRings)

        assertTrue("Kolkata should be Outside, got $check", check is BangladeshCheck.Outside)
        assertTrue((check as BangladeshCheck.Outside).distanceMetres > 5_000.0)

        val newDelhi = BangladeshLocationPolicy.check(GeoPoint(28.6139, 77.2090), bundled.boundaryRings)
        assertTrue("New Delhi should be Outside, got $newDelhi", newDelhi is BangladeshCheck.Outside)
        assertTrue((newDelhi as BangladeshCheck.Outside).distanceMetres > 500_000.0)
    }

    @Test
    fun `an Indian city within the tolerance is not reported as inside Bangladesh`() {
        // Agartala, the capital of Tripura, India, sits ~3.4 km from the simplified border line. Under
        // the old single-outcome policy it was reported as verified containment; it must not be.
        val agartala = GeoPoint(23.8315, 91.2868)

        val check = BangladeshLocationPolicy.check(agartala, bundled.boundaryRings)

        assertFalse("Agartala must not be Inside, got $check", check is BangladeshCheck.Inside)
        assertTrue("Agartala should be the tolerance case, got $check", check is BangladeshCheck.WithinBoundaryTolerance)
    }

    @Test
    fun `stepping outward from the real boundary moves from tolerance to outside`() {
        val westernmost = requireNotNull(bundled.boundaryRings.flatten().minByOrNull { it.longitude })

        val withinTolerance = BangladeshLocationPolicy.check(
            GeoPoint(westernmost.latitude, westernmost.longitude - longitudeDelta(westernmost.latitude, 2_000.0)),
            bundled.boundaryRings,
        )
        val beyondTolerance = BangladeshLocationPolicy.check(
            GeoPoint(westernmost.latitude, westernmost.longitude - longitudeDelta(westernmost.latitude, 20_000.0)),
            bundled.boundaryRings,
        )

        assertTrue("2 km west should be WithinBoundaryTolerance, got $withinTolerance", withinTolerance is BangladeshCheck.WithinBoundaryTolerance)
        assertTrue("20 km west should be Outside, got $beyondTolerance", beyondTolerance is BangladeshCheck.Outside)
    }

    private fun square(): List<GeoPoint> = listOf(
        GeoPoint(22.0, 88.5),
        GeoPoint(22.0, 90.0),
        GeoPoint(25.0, 90.0),
        GeoPoint(25.0, 88.5),
    )

    /** A longitude offset, in degrees, covering [metres] at [latitudeDegrees]. */
    private fun longitudeDelta(latitudeDegrees: Double, metres: Double): Double =
        metres / (111_320.0 * cos(latitudeDegrees * PI / 180.0))
}
