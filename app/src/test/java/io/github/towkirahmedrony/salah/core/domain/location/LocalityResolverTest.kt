package io.github.towkirahmedrony.salah.core.domain.location

import io.github.towkirahmedrony.salah.core.model.BangladeshLocalityKind
import io.github.towkirahmedrony.salah.testing.BundledDataset
import io.github.towkirahmedrony.salah.testing.localityRow
import io.github.towkirahmedrony.salah.testing.syntheticDataset
import io.github.towkirahmedrony.salah.testing.syntheticLocalities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a device position becomes — or does not become — a named locality.
 *
 * The synthetic fixture is used for the threshold decisions, because its geometry is written in the test
 * and a failure reads as "the wrong rule fired" rather than as a change in the shipped file. The real
 * bundled layer is used at the end, so the shipped data is checked against the names it exists to
 * produce.
 *
 * Every case is deterministic: fixed coordinates, fixed accuracies, no device and no network.
 */
class LocalityResolverTest {

    private val dataset = syntheticDataset(
        localities = syntheticLocalities(
            // Two named places in Kushtia district: one at the upazila centre used by the other tests, and
            // one about 0.9 km north of it.
            localityRow("QUARTER", "BD1001", "Centre Bazar", 23.860, 89.240),
            localityRow("QUARTER", "BD1001", "Nearby Para", 23.868, 89.240),
            // A row that belongs to a *different* district, deliberately placed closer to the Kushtia
            // points below than any Kushtia locality is.
            localityRow("NEIGHBOURHOOD", "BD1002", "Jashore Bazar", 23.861, 89.241),
        ),
    )

    @Test
    fun `a precise fix at a locality matches that locality`() {
        val outcome = resolve(GeoPoint(23.860, 89.240), accuracy = 25.0, districtId = "BD1001")

        assertTrue("expected a match, got $outcome", outcome is LocalityResolution.Matched)
        val suggestion = (outcome as LocalityResolution.Matched).suggestion
        assertEquals("Centre Bazar", suggestion.reference.name)
        assertEquals("BD1001", suggestion.reference.districtId)
        assertEquals(BangladeshLocalityKind.QUARTER, suggestion.reference.kind)
        assertEquals(0.0, suggestion.distanceMetres, 1.0)
    }

    @Test
    fun `a fix within the match radius takes the nearest locality`() {
        // ~890 m north of Centre Bazar, so Nearby Para is nearer than it is.
        val outcome = resolve(GeoPoint(23.868, 89.240), accuracy = 30.0, districtId = "BD1001")

        val suggestion = (outcome as LocalityResolution.Matched).suggestion
        assertEquals("Nearby Para", suggestion.reference.name)
        assertEquals(0.0, suggestion.distanceMetres, 5.0)
    }

    @Test
    fun `a locality in another district is never matched, however close it is`() {
        // Jashore Bazar is ~120 m away from this point and Centre Bazar is ~100 m away, but this position
        // resolved to Kushtia: only Kushtia's localities are candidates, so the nearer district's name can
        // never be attached to it.
        val point = GeoPoint(23.8605, 89.2406)
        val jashore = GeoPoint(23.861, 89.241)
        val kushtia = GeoPoint(23.860, 89.240)
        assertTrue(
            "the fixture must actually place the other district's locality nearer",
            GeoMath.distanceMetres(point, jashore) < GeoMath.distanceMetres(point, kushtia),
        )

        val outcome = resolve(point, accuracy = 25.0, districtId = "BD1001")

        val suggestion = (outcome as LocalityResolution.Matched).suggestion
        assertEquals("Centre Bazar", suggestion.reference.name)
        assertEquals("BD1001", suggestion.reference.districtId)
    }

    @Test
    fun `a position whose accuracy circle does not fit the radius is not named`() {
        // ~1.9 km from Nearby Para (the nearest place in the district) with a 200 m accuracy: the true
        // position could be 2.1 km from the name, so the name is withheld and the distance is reported.
        val point = GeoPoint(23.885, 89.240)
        val distance = GeoMath.distanceMetres(point, GeoPoint(23.868, 89.240))
        assertTrue("the fixture must be inside the radius by distance", distance < LocalityResolver.MATCH_METRES)
        assertTrue(
            "the fixture must exceed it once the accuracy is added",
            distance + 200.0 > LocalityResolver.MATCH_METRES,
        )

        val outcome = resolve(point, accuracy = 200.0, districtId = "BD1001")

        assertTrue("expected no match, got $outcome", outcome is LocalityResolution.Unmatched)
        assertNotNull(
            "an unmatched position in a district that has localities reports the nearest distance",
            (outcome as LocalityResolution.Unmatched).nearestDistanceMetres,
        )
        assertEquals(distance, requireNotNull(outcome.nearestDistanceMetres), 1.0)
    }

    @Test
    fun `an accuracy the platform did not report cannot establish a neighbourhood`() {
        val outcome = resolve(GeoPoint(23.868, 89.240), accuracy = null, districtId = "BD1001")

        assertEquals(LocalityResolution.NotPreciseEnough, outcome)
    }

    @Test
    fun `a non-positive or non-finite accuracy is treated as unknown`() {
        assertEquals(
            LocalityResolution.NotPreciseEnough,
            resolve(GeoPoint(23.868, 89.240), accuracy = 0.0, districtId = "BD1001"),
        )
        assertEquals(
            LocalityResolution.NotPreciseEnough,
            resolve(GeoPoint(23.868, 89.240), accuracy = Double.NaN, districtId = "BD1001"),
        )
    }

    @Test
    fun `an approximate fix is named only when it is exactly on the place`() {
        // The rule is `distance + accuracy <= MATCH_METRES`, so a ±2 km fix can still be named when it is
        // at the locality's own point — the true position is then inside the radius by construction — and
        // cannot be named one metre further out.
        val atThePoint = resolve(GeoPoint(23.860, 89.240), accuracy = 2_000.0, districtId = "BD1001")
        assertTrue("a fix on the place is inside its own accuracy circle", atThePoint is LocalityResolution.Matched)

        val tenMetresOut = resolve(GeoPoint(23.86009, 89.240), accuracy = 2_000.0, districtId = "BD1001")
        assertTrue("ten metres further out leaves the radius", tenMetresOut is LocalityResolution.Unmatched)
    }

    @Test
    fun `a position with no resolved district is not named`() {
        val outcome = resolve(GeoPoint(23.860, 89.240), accuracy = 25.0, districtId = null)

        assertEquals(LocalityResolution.Unmatched(nearestDistanceMetres = null), outcome)
    }

    @Test
    fun `a district with no localities reports nothing to measure`() {
        // Bogura is in the fixture with no locality rows.
        val outcome = resolve(GeoPoint(24.62, 89.42), accuracy = 25.0, districtId = "BD2001")

        assertEquals(LocalityResolution.Unmatched(nearestDistanceMetres = null), outcome)
    }

    @Test
    fun `a dataset without a locality layer resolves nothing rather than failing`() {
        val bare = syntheticDataset()
        assertTrue("the bare fixture must have no locality layer", !bare.hasLocalities)

        assertEquals(
            LocalityResolution.Unmatched(nearestDistanceMetres = null),
            LocalityResolver.resolve(GeoPoint(23.860, 89.240), 25.0, "BD1001", bare),
        )
        assertEquals(
            LocalityResolution.Unmatched(nearestDistanceMetres = null),
            LocalityResolver.resolve(GeoPoint(23.860, 89.240), 25.0, "BD1001", null),
        )
    }

    @Test
    fun `a non-finite coordinate is unmatched without a distance`() {
        val outcome = resolve(GeoPoint(Double.NaN, 89.240), accuracy = 25.0, districtId = "BD1001")

        assertEquals(LocalityResolution.Unmatched(nearestDistanceMetres = null), outcome)
    }

    @Test
    fun `resolution is deterministic for the same input`() {
        val point = GeoPoint(23.868, 89.240)

        assertEquals(resolve(point, 25.0, "BD1001"), resolve(point, 25.0, "BD1001"))
    }

    @Test
    fun `the bundled layer names the Dhaka neighbourhoods it exists for`() {
        val bundled = BundledDataset.load(version = "test-bundled")

        // The published points of three of the localities the layer carries for Dhaka, taken from the
        // shipped file itself, so this asserts the file's content and not a transcription of it.
        val expected = mapOf(
            "Mirpur" to GeoPoint(23.812363, 90.364089),
            "Dhanmondi" to GeoPoint(23.747912, 90.374587),
            "Uttara" to GeoPoint(23.869328, 90.392689),
        )
        for ((name, point) in expected) {
            val outcome = LocalityResolver.resolve(point, 20.0, "BD3026", bundled)
            assertTrue("expected $name to match, got $outcome", outcome is LocalityResolution.Matched)
            val suggestion = (outcome as LocalityResolution.Matched).suggestion
            assertEquals(name, suggestion.reference.name)
            assertEquals("BD3026", suggestion.reference.districtId)
            // The name is paired with the district the administrative layer resolves to for Dhaka, which is
            // what makes the displayed label "Mirpur, Dhaka" rather than "Mirpur, Dhaka South City
            // Corporation".
            assertEquals("Dhaka", requireNotNull(bundled.area("BD3026")).name)
        }
    }

    @Test
    fun `a bundled district whose localities are all far away keeps the administrative answer`() {
        val bundled = BundledDataset.load(version = "test-bundled")

        // Patuakhali: the shipped layer has a single neighbourhood row for this district, ~22 km from the
        // district's own centre. A position at that centre is therefore named by district and not by
        // neighbourhood, which is the coarser-but-true answer the policy promises.
        val outcome = LocalityResolver.resolve(GeoPoint(22.165, 90.407), 20.0, "BD1078", bundled)

        assertTrue("expected no neighbourhood name, got $outcome", outcome is LocalityResolution.Unmatched)
        assertNotNull(
            "the nearest row was measured, so the district is reported as having locality data",
            (outcome as LocalityResolution.Unmatched).nearestDistanceMetres,
        )
    }

    private fun resolve(
        point: GeoPoint,
        accuracy: Double?,
        districtId: String?,
    ): LocalityResolution = LocalityResolver.resolve(point, accuracy, districtId, dataset)
}
