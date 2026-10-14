package io.github.towkirahmedrony.salah.core.domain.location

import io.github.towkirahmedrony.salah.testing.BundledDataset
import io.github.towkirahmedrony.salah.testing.localityRow
import io.github.towkirahmedrony.salah.testing.syntheticDataset
import io.github.towkirahmedrony.salah.testing.syntheticLocalities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides whether a silently acquired device fix may replace the stored location.
 *
 * This is the Qibla screen's write path, which has no confirmation step, so the cases the setup flow
 * tolerates are refused here and the stored location is preserved instead.
 */
class DeviceFixAdoptionPolicyTest {

    private val dataset = syntheticDataset()

    @Test
    fun `a fix inside Bangladesh that resolves to an area is accepted with its country and area`() {
        val adoption = DeviceFixAdoptionPolicy.decide(GeoPoint(23.86, 89.24), 25.0, dataset)

        assertTrue("expected acceptance, got $adoption", adoption is DeviceFixAdoption.Accepted)
        val accepted = adoption as DeviceFixAdoption.Accepted
        assertEquals("BD", accepted.countryCode)
        assertEquals("BD1001001", accepted.reference.upazilaId)
        assertEquals("BD1001", accepted.reference.districtId)
        assertEquals(dataset.version, accepted.datasetVersion)
    }

    @Test
    fun `an accepted fix carries the neighbourhood its accuracy supports`() {
        // One acceptance, two records: the Qibla screen adopts silently and the location screen shows a
        // fix for confirmation, and both must write the same name for the same position. The locality is
        // resolved here so the silent path cannot lose it.
        val withLocality = syntheticDataset(
            localities = syntheticLocalities(
                localityRow("QUARTER", "BD1001", "Centre Bazar", 23.86, 89.24),
            ),
        )

        val precise = DeviceFixAdoptionPolicy.decide(GeoPoint(23.86, 89.24), 25.0, withLocality)
        assertTrue("expected acceptance, got $precise", precise is DeviceFixAdoption.Accepted)
        val locality = requireNotNull((precise as DeviceFixAdoption.Accepted).locality)
        assertEquals("Centre Bazar", locality.name)
        assertEquals("BD1001", locality.districtId)

        // The same position at an approximate accuracy is still adopted — the country and area rules are
        // what they were — but no neighbourhood is claimed for it.
        val approximate = DeviceFixAdoptionPolicy.decide(GeoPoint(23.86, 89.24), 3_000.0, withLocality)
        assertTrue("expected acceptance, got $approximate", approximate is DeviceFixAdoption.Accepted)
        assertNull(
            "a fix accurate only to a few kilometres must not name a neighbourhood",
            (approximate as DeviceFixAdoption.Accepted).locality,
        )
    }

    @Test
    fun `a fix far outside Bangladesh is rejected and keeps the stored location`() {
        val adoption = DeviceFixAdoptionPolicy.decide(GeoPoint(26.0, 92.0), 25.0, dataset)

        assertTrue("expected rejection, got $adoption", adoption is DeviceFixAdoption.Rejected)
        val rejected = adoption as DeviceFixAdoption.Rejected
        assertEquals(DeviceFixRejectionReason.OUTSIDE_BANGLADESH, rejected.reason)
        assertTrue(
            "distance $rejected should exceed the tolerance",
            requireNotNull(rejected.distanceMetres) > BangladeshLocationPolicy.BOUNDARY_TOLERANCE_METRES,
        )
    }

    @Test
    fun `a fix within the boundary tolerance is rejected as outside`() {
        // ~2 km beyond the synthetic boundary: geometrically outside Bangladesh, and there is no
        // confirmation step to catch a neighbouring country's territory.
        val adoption = DeviceFixAdoptionPolicy.decide(GeoPoint(23.5, 90.02), 25.0, dataset)

        assertTrue("expected rejection, got $adoption", adoption is DeviceFixAdoption.Rejected)
        val rejected = adoption as DeviceFixAdoption.Rejected
        assertEquals(DeviceFixRejectionReason.OUTSIDE_BANGLADESH, rejected.reason)
        assertTrue("distance $rejected should be within the tolerance", requireNotNull(rejected.distanceMetres) <= BangladeshLocationPolicy.BOUNDARY_TOLERANCE_METRES)
    }

    @Test
    fun `a fix inside Bangladesh that cannot be attributed to an area is rejected`() {
        // Inside the synthetic boundary, but >75 km from every district centre.
        val adoption = DeviceFixAdoptionPolicy.decide(GeoPoint(22.1, 88.6), 25.0, dataset)

        assertTrue("expected rejection, got $adoption", adoption is DeviceFixAdoption.Rejected)
        assertEquals(DeviceFixRejectionReason.AREA_UNRESOLVED, (adoption as DeviceFixAdoption.Rejected).reason)
    }

    @Test
    fun `an unreadable dataset rejects rather than guessing`() {
        assertEquals(
            DeviceFixAdoption.Rejected(DeviceFixRejectionReason.DATASET_UNAVAILABLE),
            DeviceFixAdoptionPolicy.decide(GeoPoint(23.86, 89.24), 25.0, null),
        )

        val unusable = BangladeshLocationDataset.parse(
            version = "test-unusable",
            areasText = "DIVISION\tBD10\tBD\tKhulna\t\t22.80\t89.30",
            boundaryText = "",
        )
        assertEquals(
            DeviceFixAdoption.Rejected(DeviceFixRejectionReason.DATASET_UNAVAILABLE),
            DeviceFixAdoptionPolicy.decide(GeoPoint(22.8, 89.3), 25.0, unusable),
        )
    }

    @Test
    fun `a non-finite coordinate is rejected`() {
        assertEquals(
            DeviceFixAdoption.Rejected(DeviceFixRejectionReason.DATASET_UNAVAILABLE),
            DeviceFixAdoptionPolicy.decide(GeoPoint(Double.NaN, 89.24), 25.0, dataset),
        )
    }

    @Test
    fun `against the real bundled data a Bangladeshi centre is accepted and an Indian city is not`() {
        val bundled = BundledDataset.load(version = "test-bundled")
        val district = bundled.districts.first()

        val accepted = DeviceFixAdoptionPolicy.decide(GeoPoint(district.latitude, district.longitude), 25.0, bundled)
        assertTrue("expected an accepted real fix, got $accepted", accepted is DeviceFixAdoption.Accepted)
        assertEquals("BD", (accepted as DeviceFixAdoption.Accepted).countryCode)
        assertNotNull("a real accepted fix records its dataset version", accepted.datasetVersion)

        // Agartala, India, is ~3.4 km from the simplified border and must not replace a stored location.
        val rejected = DeviceFixAdoptionPolicy.decide(GeoPoint(23.8315, 91.2868), 25.0, bundled)
        assertTrue("expected Agartala to be rejected, got $rejected", rejected is DeviceFixAdoption.Rejected)
        assertEquals(DeviceFixRejectionReason.OUTSIDE_BANGLADESH, (rejected as DeviceFixAdoption.Rejected).reason)
    }
}
