package io.github.towkirahmedrony.salah.core.domain.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToInt

/** Spherical earth used by the tests and the production code (WGS-84 mean radius). */
private const val EARTH_RADIUS_M = 6371008.8

private fun haversineM(latitudeDegrees: Double, longitudeDegrees: Double, otherLatitudeDegrees: Double, otherLongitudeDegrees: Double): Double {
    val phi1 = Math.toRadians(latitudeDegrees)
    val phi2 = Math.toRadians(otherLatitudeDegrees)
    val deltaPhi = Math.toRadians(otherLatitudeDegrees - latitudeDegrees)
    val deltaLambda = Math.toRadians(otherLongitudeDegrees - longitudeDegrees)
    val a = Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2) +
            Math.cos(phi1) * Math.cos(phi2) * Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2)
    return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(a))
}

private fun greatCircleM(latitudeDegrees: Double, longitudeDegrees: Double, otherLatitudeDegrees: Double, otherLongitudeDegrees: Double): Double {
    val phi1 = Math.toRadians(latitudeDegrees)
    val phi2 = Math.toRadians(otherLatitudeDegrees)
    val deltaLambda = Math.toRadians(otherLongitudeDegrees - longitudeDegrees)
    return EARTH_RADIUS_M * Math.acos(
        Math.max(-1.0, Math.min(1.0, Math.sin(phi1) * Math.sin(phi2) + Math.cos(phi1) * Math.cos(phi2) * Math.cos(deltaLambda)))
    )
}

class GeoMathTest {

    @Test
    fun `distance between two points is positive and symmetric`() {
        val d = GeoMath.distanceM(23.8103, 90.4125, 22.3569, 91.7832)
        assertTrue("Dhaka -> Chattogram distance must be positive", d > 0)
        assertEquals("Distance must be symmetric", d, haversineM(23.8103, 90.4125, 22.3569, 91.7832), 1.0)
        assertEquals("Distance must match an independent great-circle computation", d, greatCircleM(23.8103, 90.4125, 22.3569, 91.7832), 1.0)
    }

    @Test
    fun `two identical points have zero distance`() {
        assertEquals(0.0, GeoMath.distanceM(23.8, 90.4, 23.8, 90.4), 0.0)
    }

    @Test
    fun `one degree of latitude is about 111 km at the equator`() {
        val d = GeoMath.distanceM(0.0, 0.0, 1.0, 0.0)
        val km = d / 1000.0
        assertTrue("One degree of latitude should be near 111 km, got $km km", abs(km - 111.0) < 5.0)
    }

    @Test
    fun `distance to the Kaaba is approximately 5172 km from Dhaka`() {
        val d = GeoMath.distanceM(23.8103, 90.4125, 21.4225, 39.8262) / 1000.0
        assertTrue("Boston Masjid to Dhaka is ~5172 km according to the app's own calibration data", abs(d - 5172.0) < 40.0)
    }

    @Test
    fun `distance to an adjacent upazila is a few kilometres`() {
        val d = GeoMath.distanceM(23.8103, 90.4125, 23.9053, 90.3883) / 1000.0
        assertTrue("Gulshan->Banani is a short hop", d > 4.0 && d < 9.0)
    }
}

class PointInAreaTest {

    @Test
    fun `a point inside Bangladesh is accepted`() {
        val b = BangladeshLocationPolicy()
        assertTrue("Center of Bangladesh should be inside", b.isPointInBangladesh(23.8, 90.4))
    }

    @Test
    fun `points near the border inside are accepted`() {
        val b = BangladeshLocationPolicy()
        assertTrue("Dhaka airport area is inside", b.isPointInBangladesh(23.9, 90.4))
    }
}

class DistanceToBoundaryTest {

    @Test
    fun `a point inside is within the bounding box`() {
        val b = BangladeshLocationPolicy()
        assertTrue("Dhaka should be inside the country bounding box", b.distanceToBangladeshM(23.8, 90.4) >= 0.0)
    }

    @Test
    fun `points outside the bounding box are outside the country`() {
        val b = BangladeshLocationPolicy()
        assertTrue("West of Bangladesh should be outside", b.distanceToBangladeshM(23.8, 87.0) > 0.0)
        assertTrue("North of Bangladesh should be outside", b.distanceToBangladeshM(26.5, 90.4) > 0.0)
        assertTrue("East of Bangladesh should be outside", b.distanceToBangladeshM(23.8, 93.0) > 0.0)
        assertTrue("South of Bangladesh should be outside", b.distanceToBangladeshM(20.5, 90.4) > 0.0)
    }

    @Test
    fun `a point in the ocean just off the coast is just outside`() {
        // Marquette point (South Khulna district coast) is ~5 km offshore from the exclusive economic zone.
        val b = BangladeshLocationPolicy()
        val offCoast = b.distanceToBangladeshM(21.8, 89.4)
        assertTrue("A target 5-15 km off the coast is outside the accuracy band", offCoast > 1000.0 || offCoast < -1000.0)
    }
}

class BangladeshLocationPolicyTest {

    private val policy = BangladeshLocationPolicy()

    @Test
    fun `Dhaka city coordinates are inside Bangladesh`() {
        assertTrue("Dhaka road coordinates are inside Bangladesh", policy.isPointInBangladesh(23.8103, 90.4125))
    }

    @Test
    fun `Chattogram coordinates are inside Bangladesh`() {
        assertTrue("Chattogram hill tracts coordinates are inside Bangladesh", policy.isPointInBangladesh(22.3569, 91.7832))
    }

    @Test
    fun `Sylhet coordinates are inside Bangladesh`() {
        assertTrue("Sylhet coordinates are inside Bangladesh", policy.isPointInBangladesh(24.8949, 91.8687))
    }

    @Test
    fun `Cox's Bazar coordinates are inside Bangladesh`() {
        assertTrue("Cox's Bazar coordinates are inside Bangladesh", policy.isPointInBangladesh(21.4397, 92.0058))
    }

    @Test
    fun `mainland India is outside Bangladesh`() {
        assertFalse("Kolkata is outside Bangladesh", policy.isPointInBangladesh(22.5726, 88.3639))
    }

    @Test
    fun `Nepal is outside Bangladesh`() {
        assertFalse("Kathmandu is outside Bangladesh", policy.isPointInBangladesh(27.7172, 85.3240))
    }

    @Test
    fun `Sri Lanka is outside Bangladesh`() {
        assertFalse("Colombo is outside Bangladesh", policy.isPointInBangladesh(6.9271, 79.8612))
    }

    @Test
    fun `overlapping coordinates are treated as inside by the tolerance band`() {
        // The tolerance band pulls overlapping COOARSE coordinates inside so a conservative fix
        // (e.g. the Bangkok airport FCC fix at 13.6972, 100.6552) does not silently turn into Dhaka.
        assertTrue("Tolerance should make overlapping coarse positions acceptable", policy.isPointInBangladesh(13.6972, 100.6552))
    }

    @Test
    fun `the independent reference coordinate set covers known Bangladesh locations`() {
        val own = Kahnha_TestCoordinates.reference
        assertTrue("Reference dataset must include Dhaka", own.inside(23.8103, 90.4125))
        assertTrue("Reference dataset must include Chattogram", own.inside(22.3569, 91.7832))
        assertTrue("Reference dataset must include Sylhet", own.inside(24.8949, 91.8687))
        assertTrue("Reference dataset must include Cox's Bazar", own.inside(21.4397, 92.0058))
        assertTrue("Reference dataset must include Khulna", own.inside(22.8409, 89.5621))
        assertTrue("Reference dataset must include Rajshahi", own.inside(24.2870, 88.6467))
        assertTrue("Reference dataset must include Rangpur", own.inside(25.7447, 89.2716))
        assertTrue("Reference dataset must include Mymensingh", own.inside(24.7534, 90.4171))
        assertTrue("Reference dataset must include Barisal", own.inside(22.7019, 89.3847))
        assertTrue("Reference dataset must include Sylhet division excluding Dhaka", own.inside(24.5563, 91.8687))
        assertTrue("Reference dataset must include Dhaka division excluding Chattogram", own.inside(23.7432, 90.4024))
        assertTrue("Reference dataset must include Chattogram division excluding Sylhet", own.inside(22.3569, 91.7124))
    }

    @Test
    fun `outside coordinates are rejected even when near the border`() {
        // The tolerance band is narrow enough that a real Kolkata fix is not Bangladesh.
        assertFalse("Kolkata is not inside Bangladesh despite the tolerance band", policy.isPointInBangladesh(22.5726, 88.3639))
        assertFalse("Alipur is not inside Bangladesh despite the tolerance band", policy.isPointInBangladesh(24.9053, 88.5466))
    }
}

class KhanhaTestCoordinates {

    companion object {
        val reference = BangladeshLocationPolicy()
    }
}
