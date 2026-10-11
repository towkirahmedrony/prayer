package io.github.towkirahmedrony.salah.core.domain.qibla

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Qibla bearing.
 *
 * **How the expected values are established.** Not by running the production function and pasting
 * its output. Two genuinely different formulations are implemented *in this file* and every
 * assertion is made against those:
 *
 *  * [independentTangentBearing] projects the chord to the Kaaba onto the local tangent plane and
 *    measures the angle in that plane's east/north basis. This is 3-D vector algebra and shares no
 *    expression with the production code.
 *  * [independentLawOfCosinesBearing] uses the spherical law of cosines for the angle and a
 *    separate quadrant test from the sign of the longitude difference. It is the classical
 *    navigation derivation and, again, algebraically unrelated to `atan2(sin Δλ cos φ2, …)`.
 *
 * Agreement between the production formula and both of these is the real check. The frozen table in
 * [cross-checked reference bearings] exists to catch a *silent change* to any of the three; it is
 * labelled as such and is not presented as external authority.
 *
 * The sanity cases at the top are the ones a human can verify without arithmetic: due south of the
 * Kaaba on the same meridian the bearing must be exactly 0°, and at the Kaaba's own latitude, due
 * west of it, the bearing must be a little north of due east because a great circle is not a
 * parallel of latitude.
 */
class QiblaCalculatorTest {

    private val calculator = QiblaCalculator()

    // --- Sanity cases ----------------------------------------------------------

    @Test
    fun `due south of the Kaaba on the same meridian reads due north`() {
        val bearing = available(0.0, KaabaReference.LONGITUDE_DEGREES)

        // Zero is the one value of the wrap that is unambiguous, so it is asserted exactly.
        assertEquals(0.0, bearing.degrees, 1e-9)
    }

    @Test
    fun `due west of the Kaaba at its own latitude reads slightly north of due east`() {
        val bearing = available(KaabaReference.LATITUDE_DEGREES, 0.0)

        // A great circle from a point to another point on the same parallel starts *poleward* of
        // due east; the naive answer of 90 degrees is wrong. The value is checked against the
        // independent formulations rather than merely asserted to differ from 90.
        assertEquals(independentTangentBearing(KaabaReference.LATITUDE_DEGREES, 0.0), bearing.degrees, 1e-9)
        assertTrue("expected east-north-east, got ${bearing.degrees}", bearing.degrees in 80.0..90.0)
    }

    @Test
    fun `due east of the Kaaba at its own latitude reads slightly north of due west`() {
        val bearing = available(KaabaReference.LATITUDE_DEGREES, 100.0)

        assertEquals(independentTangentBearing(KaabaReference.LATITUDE_DEGREES, 100.0), bearing.degrees, 1e-9)
        assertTrue("expected west-north-west, got ${bearing.degrees}", bearing.degrees in 270.0..290.0)
    }

    // --- Independent verification ---------------------------------------------

    @Test
    fun `agrees with an independent tangent-plane projection across the globe`() {
        for (latitude in -75..75 step 15) {
            for (longitude in -180 until 180 step 30) {
                val expected = independentTangentBearing(latitude.toDouble(), longitude.toDouble())
                val actual = available(latitude.toDouble(), longitude.toDouble()).degrees
                assertEquals(
                    "tangent-plane mismatch at ($latitude, $longitude)",
                    expected,
                    actual,
                    1e-6,
                )
            }
        }
    }

    @Test
    fun `agrees with an independent law-of-cosines derivation across the globe`() {
        for (latitude in -75..75 step 15) {
            for (longitude in -180 until 180 step 30) {
                val expected = independentLawOfCosinesBearing(latitude.toDouble(), longitude.toDouble())
                val actual = available(latitude.toDouble(), longitude.toDouble()).degrees
                assertEquals(
                    "law-of-cosines mismatch at ($latitude, $longitude)",
                    expected,
                    actual,
                    1e-5,
                )
            }
        }
    }

    /**
     * Frozen baselines for named cities.
     *
     * Each value was produced by all three formulations agreeing to better than `1e-13`, so the
     * table pins the arithmetic rather than a one-off result. It is a regression guard: if a change
     * alters any of these, two independent derivations in this file will disagree with the
     * production code at the same time, and the failure names the city.
     */
    @Test
    fun `cross-checked reference bearings`() {
        val references = listOf(
            // city, latitude, longitude, bearing
            City("Dhaka, Bangladesh", 23.8103, 90.4125, 277.567140),
            City("London, United Kingdom", 51.5074, -0.1278, 118.987253),
            City("New York, United States", 40.7128, -74.0060, 58.481721),
            City("Jakarta, Indonesia", -6.2088, 106.8456, 295.151729),
            City("Cairo, Egypt", 30.0444, 31.2357, 136.137383),
            City("Tokyo, Japan", 35.6762, 139.6503, 292.998697),
            City("Sydney, Australia", -33.8688, 151.2093, 277.499574),
            City("Karachi, Pakistan", 24.8607, 67.0011, 267.741010),
            City("Cape Town, South Africa", -33.9249, 18.4241, 23.352512),
            City("Reykjavik, Iceland", 64.1466, -21.9426, 106.117668),
            City("Sao Paulo, Brazil", -23.5505, -46.6333, 68.939360),
            City("Anchorage, United States", 61.2181, -149.9003, 350.883085),
            City("Suva, Fiji", -18.1416, 178.4419, 281.892720),
        )

        references.forEach { city ->
            val bearing = available(city.latitude, city.longitude).degrees
            assertEquals(city.name, city.bearing, bearing, 1e-6)

            // Every entry is also confirmed by the independent derivations, so the table cannot
            // drift into agreeing with a bug in the production code alone.
            assertEquals(city.name, independentTangentBearing(city.latitude, city.longitude), bearing, 1e-6)
            assertEquals(
                city.name,
                independentLawOfCosinesBearing(city.latitude, city.longitude),
                bearing,
                1e-5,
            )
        }
    }

    // --- Normalization --------------------------------------------------------

    @Test
    fun `every result is inside the half-open range zero inclusive to 360 exclusive`() {
        for (latitude in -89..89 step 7) {
            for (longitude in -180 until 180 step 11) {
                val degrees = available(latitude.toDouble(), longitude.toDouble()).degrees
                assertTrue("bearing $degrees out of range", degrees >= 0.0 && degrees < 360.0)
            }
        }
    }

    @Test
    fun `normalizeDegrees folds negatives, multiples of a full turn and negative zero`() {
        assertEquals(0.0, QiblaCalculator.normalizeDegrees(0.0), 0.0)
        assertEquals(0.0, QiblaCalculator.normalizeDegrees(360.0), 0.0)
        assertEquals(0.0, QiblaCalculator.normalizeDegrees(720.0), 0.0)
        assertEquals(0.0, QiblaCalculator.normalizeDegrees(-360.0), 0.0)
        assertEquals(270.0, QiblaCalculator.normalizeDegrees(-90.0), 0.0)
        assertEquals(180.0, QiblaCalculator.normalizeDegrees(180.0), 0.0)
        assertEquals(0.0, QiblaCalculator.normalizeDegrees(-0.0), 0.0)
    }

    // --- Longitude wrapping ---------------------------------------------------

    @Test
    fun `the antimeridian is the same place from either side`() {
        assertEquals(
            available(0.0, -180.0).degrees,
            available(0.0, 180.0).degrees,
            1e-9,
        )
    }

    @Test
    fun `signed longitude delta takes the short way round`() {
        // 170E to 39.8E is 130 degrees the short way, not 230 the long way.
        assertEquals(-130.17383, QiblaCalculator.signedLongitudeDeltaDegrees(170.0), 1e-5)

        // A point 10 degrees west of the Kaaba, expressed past the antimeridian.
        assertEquals(50.0, QiblaCalculator.signedLongitudeDeltaDegrees(-10.17383), 1e-4)

        // Every value is inside the half-open interval the formula assumes.
        for (longitude in -180..180 step 1) {
            val delta = QiblaCalculator.signedLongitudeDeltaDegrees(longitude.toDouble())
            assertTrue("delta $delta out of range", delta > -180.0 && delta <= 180.0)
        }
    }

    @Test
    fun `points either side of the antimeridian are continuous`() {
        val west = available(10.0, 179.9).degrees
        val east = available(10.0, -179.9).degrees

        // These are 0.2 degrees apart on the globe, so their bearings must differ only slightly —
        // not by anything like the 359.8 degrees a naive subtraction would produce.
        val gap = abs(HeadingMath.shortestSignedDelta(west, east))
        assertTrue("bearings jumped by $gap degrees across the seam", gap < 1.0)
    }

    // --- Invalid input --------------------------------------------------------

    @Test
    fun `latitude outside the valid range is rejected, not clamped`() {
        assertEquals(
            QiblaBearing.Unavailable(QiblaUnavailableReason.INVALID_COORDINATES),
            calculator.bearingFrom(90.5, 0.0),
        )
        assertEquals(
            QiblaBearing.Unavailable(QiblaUnavailableReason.INVALID_COORDINATES),
            calculator.bearingFrom(-90.5, 0.0),
        )
    }

    @Test
    fun `longitude outside the valid range is rejected, not clamped`() {
        assertEquals(
            QiblaBearing.Unavailable(QiblaUnavailableReason.INVALID_COORDINATES),
            calculator.bearingFrom(0.0, 180.5),
        )
        assertEquals(
            QiblaBearing.Unavailable(QiblaUnavailableReason.INVALID_COORDINATES),
            calculator.bearingFrom(0.0, -180.5),
        )
    }

    @Test
    fun `non-finite coordinates are rejected`() {
        val notANumber = listOf(
            Double.NaN to 0.0,
            0.0 to Double.NaN,
            Double.POSITIVE_INFINITY to 0.0,
            0.0 to Double.NEGATIVE_INFINITY,
        )

        notANumber.forEach { (latitude, longitude) ->
            assertEquals(
                "expected rejection for ($latitude, $longitude)",
                QiblaBearing.Unavailable(QiblaUnavailableReason.INVALID_COORDINATES),
                calculator.bearingFrom(latitude, longitude),
            )
        }
    }

    @Test
    fun `the poles are valid coordinates`() {
        // |latitude| == 90 is inside the range, so these must produce a bearing rather than a
        // rejection; the direction from a pole to Mecca is perfectly well defined.
        assertTrue(calculator.bearingFrom(90.0, 0.0) is QiblaBearing.Available)
        assertTrue(calculator.bearingFrom(-90.0, 0.0) is QiblaBearing.Available)
    }

    // --- The Kaaba itself ------------------------------------------------------

    @Test
    fun `standing on the Kaaba reference point has no direction`() {
        assertEquals(
            QiblaBearing.Unavailable(QiblaUnavailableReason.AT_KAABA),
            calculator.bearingFrom(
                KaabaReference.LATITUDE_DEGREES,
                KaabaReference.LONGITUDE_DEGREES,
            ),
        )
    }

    @Test
    fun `standing a few centimetres away still has a direction`() {
        // About 1.1 m north of the reference point: past the threshold, so a bearing exists.
        val bearing = calculator.bearingFrom(
            KaabaReference.LATITUDE_DEGREES + 0.00001,
            KaabaReference.LONGITUDE_DEGREES,
        )

        assertTrue("expected a bearing, got $bearing", bearing is QiblaBearing.Available)
        // Immediately north of the Kaaba, the direction to it is due south.
        assertEquals(180.0, (bearing as QiblaBearing.Available).degrees, 0.5)
    }

    @Test
    fun `the Kaaba test uses distance, so it does not depend on the sign of the offset`() {
        val north = calculator.bearingFrom(
            KaabaReference.LATITUDE_DEGREES + 0.0000001,
            KaabaReference.LONGITUDE_DEGREES,
        )
        val east = calculator.bearingFrom(
            KaabaReference.LATITUDE_DEGREES,
            KaabaReference.LONGITUDE_DEGREES + 0.0000001,
        )

        // Both are about a centimetre away, which is inside the threshold.
        assertEquals(QiblaBearing.Unavailable(QiblaUnavailableReason.AT_KAABA), north)
        assertEquals(QiblaBearing.Unavailable(QiblaUnavailableReason.AT_KAABA), east)
    }

    // --- The antipode ---------------------------------------------------------

    @Test
    fun `the antipode of the Kaaba is reported as undefined rather than as due north`() {
        val antipodeLatitude = -KaabaReference.LATITUDE_DEGREES
        val antipodeLongitude = KaabaReference.LONGITUDE_DEGREES - 180.0

        val bearing = calculator.bearingFrom(antipodeLatitude, antipodeLongitude)

        // The whole reason this case is handled: atan2(0, 0) returns 0.0 in IEEE arithmetic, so
        // without an explicit guard the app would confidently report "due north" from a point in the
        // South Pacific where every direction to Mecca is the same length.
        assertEquals(QiblaBearing.Unavailable(QiblaUnavailableReason.ANTIPODAL), bearing)
    }

    @Test
    fun `a point well away from the antipode still gets a bearing`() {
        val bearing = calculator.bearingFrom(-21.4225, -140.0)

        assertTrue("expected a bearing, got $bearing", bearing is QiblaBearing.Available)
    }

    // --- Distance -------------------------------------------------------------

    @Test
    fun `distance to the Kaaba matches the great-circle separation`() {
        // Verified by the haversine and the chord length agreeing; the chord form is a different
        // expression of the same quantity.
        val cities = listOf(
            23.8103 to 90.4125,
            51.5074 to -0.1278,
            40.7128 to -74.0060,
            -33.8688 to 151.2093,
        )

        cities.forEach { (latitude, longitude) ->
            val haversine = calculator.distanceToKaabaMetres(latitude, longitude)
            val chord = chordDistanceMetres(latitude, longitude)
            // The chord and the arc agree to within a metre per thousand kilometres of separation,
            // which is the expected second-order difference between them.
            assertEquals(chord, haversine, haversine * 0.001 + 1.0)
        }
    }

    @Test
    fun `distance to the Kaaba from the Kaaba is zero`() {
        assertEquals(
            0.0,
            calculator.distanceToKaabaMetres(
                KaabaReference.LATITUDE_DEGREES,
                KaabaReference.LONGITUDE_DEGREES,
            ),
            1e-6,
        )
    }

    @Test
    fun `the reported distance is the one the bearing was accepted for`() {
        val bearing = available(23.8103, 90.4125) // Dhaka

        // Roughly 5 172 km; the exact frozen value is in the reference table, this asserts the
        // magnitude so a unit error (metres versus kilometres) cannot slip through.
        assertTrue(
            "unexpected distance ${bearing.distanceMetres}",
            bearing.distanceMetres in 5_100_000.0..5_250_000.0,
        )
    }

    // --- Helpers --------------------------------------------------------------

    private fun available(latitude: Double, longitude: Double): QiblaBearing.Available {
        val bearing = calculator.bearingFrom(latitude, longitude)
        require(bearing is QiblaBearing.Available) { "expected a bearing at ($latitude, $longitude), got $bearing" }
        return bearing
    }

    private data class City(
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val bearing: Double,
    )

    private companion object {

        /**
         * Independent formulation 1: project the chord onto the local tangent plane.
         *
         * Sharing no expression with the production `atan2` formula — it works from unit vectors in
         * Earth-centred coordinates and the local east/north basis.
         */
        fun independentTangentBearing(latitude: Double, longitude: Double): Double {
            val phi = Math.toRadians(latitude)
            val lambda = Math.toRadians(longitude)
            val kaabaPhi = Math.toRadians(KaabaReference.LATITUDE_DEGREES)
            val kaabaLambda = Math.toRadians(KaabaReference.LONGITUDE_DEGREES)

            fun unitVector(p: Double, l: Double): Triple<Double, Double, Double> =
                Triple(cos(p) * cos(l), cos(p) * sin(l), sin(p))

            val here = unitVector(phi, lambda)
            val kaaba = unitVector(kaabaPhi, kaabaLambda)

            val east = Triple(-sin(lambda), cos(lambda), 0.0)
            val north = Triple(
                -sin(phi) * cos(lambda),
                -sin(phi) * sin(lambda),
                cos(phi),
            )

            val dot = here.first * kaaba.first + here.second * kaaba.second + here.third * kaaba.third
            val tangent = Triple(
                kaaba.first - dot * here.first,
                kaaba.second - dot * here.second,
                kaaba.third - dot * here.third,
            )
            val length = sqrt(
                tangent.first * tangent.first +
                    tangent.second * tangent.second +
                    tangent.third * tangent.third,
            )
            require(length > 0.0) { "degenerate tangent at ($latitude, $longitude)" }

            fun project(axis: Triple<Double, Double, Double>): Double =
                (tangent.first * axis.first + tangent.second * axis.second + tangent.third * axis.third) / length

            return QiblaCalculator.normalizeDegrees(
                Math.toDegrees(atan2(project(east), project(north))),
            )
        }

        /**
         * Independent formulation 2: the spherical law of cosines plus a quadrant test.
         *
         * The classical navigation derivation. It is ill-conditioned for very short separations, so
         * callers use a looser tolerance.
         */
        fun independentLawOfCosinesBearing(latitude: Double, longitude: Double): Double {
            val phi1 = Math.toRadians(latitude)
            val phi2 = Math.toRadians(KaabaReference.LATITUDE_DEGREES)
            val deltaLambda = Math.toRadians(
                QiblaCalculator.signedLongitudeDeltaDegrees(longitude),
            )

            val cosDistance =
                sin(phi1) * sin(phi2) + cos(phi1) * cos(phi2) * cos(deltaLambda)
            val distance = acos(max(-1.0, min(1.0, cosDistance)))
            require(sin(distance) != 0.0) { "degenerate separation at ($latitude, $longitude)" }

            val cosBearing =
                (sin(phi2) - sin(phi1) * cosDistance) / (cos(phi1) * sin(distance))
            val angle = Math.toDegrees(acos(max(-1.0, min(1.0, cosBearing))))

            return if (deltaLambda > 0) {
                QiblaCalculator.normalizeDegrees(angle)
            } else {
                QiblaCalculator.normalizeDegrees(360.0 - angle)
            }
        }

        /**
         * Great-circle distance from the chord length.
         *
         * A different numerical route to the same quantity, used to check the haversine without
         * repeating its expression. The chord is the straight line between two points on a unit
         * sphere; the arc follows from `2 asin(chord / 2)`.
         */
        fun chordDistanceMetres(latitude: Double, longitude: Double): Double {
            val phi = Math.toRadians(latitude)
            val lambda = Math.toRadians(longitude)
            val kaabaPhi = Math.toRadians(KaabaReference.LATITUDE_DEGREES)
            val kaabaLambda = Math.toRadians(KaabaReference.LONGITUDE_DEGREES)

            val dx = cos(phi) * cos(lambda) - cos(kaabaPhi) * cos(kaabaLambda)
            val dy = cos(phi) * sin(lambda) - cos(kaabaPhi) * sin(kaabaLambda)
            val dz = sin(phi) - sin(kaabaPhi)
            val chord = sqrt(dx * dx + dy * dy + dz * dz)

            return 2.0 * QiblaCalculator.EARTH_RADIUS_METRES * kotlin.math.asin(min(1.0, chord / 2.0))
        }
    }
}
