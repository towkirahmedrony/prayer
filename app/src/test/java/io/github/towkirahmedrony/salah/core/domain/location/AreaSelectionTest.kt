package io.github.towkirahmedrony.salah.core.domain.location

import io.github.towkirahmedrony.salah.core.model.BangladeshAreaLevel
import io.github.towkirahmedrony.salah.testing.syntheticDataset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that keep a manual division/district/upazila choice internally consistent.
 *
 * The state is the guard: an id that does not belong under the chosen parent is ignored rather than
 * stored, so a half-valid hierarchy cannot exist even for one frame.
 */
class AreaSelectionTest {

    private val dataset = syntheticDataset()

    @Test
    fun `an empty selection offers only the divisions and is not complete`() {
        val selection = AreaSelection.EMPTY

        assertNull(selection.deepestLevel)
        assertFalse(selection.isComplete)
        assertNull(selection.reference(dataset))
        assertNull(selection.resolvedArea(dataset))
        assertEquals(dataset.divisions.size, selection.divisions(dataset).size)
        assertTrue(selection.districts(dataset).isEmpty())
        assertTrue(selection.upazilas(dataset).isEmpty())
    }

    @Test
    fun `choosing a division exposes its districts and resets the deeper levels`() {
        val selection = AreaSelection.EMPTY.selectDivision("BD10", dataset)

        assertEquals("BD10", selection.divisionId)
        assertNull(selection.districtId)
        assertNull(selection.upazilaId)
        assertEquals(BangladeshAreaLevel.DIVISION, selection.deepestLevel)
        assertTrue(selection.districts(dataset).isNotEmpty())
        assertTrue("all districts belong to the chosen division", selection.districts(dataset).all { it.parentId == "BD10" })
    }

    @Test
    fun `an unknown or wrong-level id is ignored when choosing a division`() {
        val selection = AreaSelection.EMPTY

        assertEquals(selection, selection.selectDivision("nope", dataset))
        // BD1001 is a district, not a division.
        assertEquals(selection, selection.selectDivision("BD1001", dataset))
    }

    @Test
    fun `choosing a district requires it to belong to the chosen division`() {
        val underKhulna = AreaSelection.EMPTY.selectDivision("BD10", dataset)

        val chosen = underKhulna.selectDistrict("BD1001", dataset)
        assertEquals("BD1001", chosen.districtId)
        assertEquals(BangladeshAreaLevel.DISTRICT, chosen.deepestLevel)
        assertTrue(chosen.upazilas(dataset).all { it.parentId == "BD1001" })

        // BD2001 is a district of the other division and must be refused.
        assertEquals(underKhulna, underKhulna.selectDistrict("BD2001", dataset))
    }

    @Test
    fun `choosing a district before a division is refused`() {
        assertEquals(AreaSelection.EMPTY, AreaSelection.EMPTY.selectDistrict("BD1001", dataset))
    }

    @Test
    fun `choosing an upazila requires it to belong to the chosen district`() {
        val base = AreaSelection.EMPTY
            .selectDivision("BD10", dataset)
            .selectDistrict("BD1001", dataset)

        val chosen = base.selectUpazila("BD1001001", dataset)
        assertEquals("BD1001001", chosen.upazilaId)
        assertEquals(BangladeshAreaLevel.UPAZILA, chosen.deepestLevel)
        assertTrue(chosen.isComplete)

        // BD2002001 belongs to the other division's district and must be refused.
        assertEquals(base, base.selectUpazila("BD2001001", dataset))
    }

    @Test
    fun `changing the division clears the district and the upazila`() {
        val before = complete()
        val after = before.selectDivision("BD20", dataset)

        assertEquals("BD20", after.divisionId)
        assertNull(after.districtId)
        assertNull(after.upazilaId)
    }

    @Test
    fun `changing the district clears the upazila`() {
        val before = complete()
        val after = before.selectDistrict("BD1002", dataset)

        assertEquals("BD1001", before.districtId)
        assertEquals("BD1002", after.districtId)
        assertNull(after.upazilaId)
    }

    @Test
    fun `choosing the same value again leaves the selection unchanged`() {
        val complete = complete()

        assertEquals(complete, complete.selectDivision("BD10", dataset))
        assertEquals(complete, complete.selectDistrict("BD1001", dataset))
        assertEquals(complete, complete.selectUpazila("BD1001001", dataset))
    }

    @Test
    fun `clearing the deepest levels works one and two levels at a time`() {
        val complete = complete()

        assertEquals(AreaSelection(divisionId = "BD10", districtId = "BD1001"), complete.clearUpazila())
        assertEquals(AreaSelection(divisionId = "BD10"), complete.clearDistrict())
    }

    @Test
    fun `a complete selection resolves to a reference and the upazila's coordinate`() {
        val complete = complete()

        assertTrue(complete.isComplete)
        val reference = complete.reference(dataset)
        assertNotNull(reference)
        assertEquals("BD10", reference?.divisionId)
        assertEquals("BD1001", reference?.districtId)
        assertEquals("BD1001001", reference?.upazilaId)

        val area = complete.resolvedArea(dataset)
        assertNotNull(area)
        assertEquals("BD1001001", area?.id)
        assertEquals("Kumarkhali", area?.name)
    }

    @Test
    fun `an incomplete selection has no reference`() {
        val divisionOnly = AreaSelection.EMPTY.selectDivision("BD10", dataset)
        val districtOnly = divisionOnly.selectDistrict("BD1001", dataset)

        assertNull(divisionOnly.reference(dataset))
        assertNull(districtOnly.reference(dataset))
        assertNull(districtOnly.resolvedArea(dataset))
    }

    @Test
    fun `a selection referencing an id the dataset no longer has is rejected at save time`() {
        // Bypasses the state guards on purpose: this models a selection restored from a newer dataset
        // whose ids this build does not know, which must never resolve into a location.
        val stale = AreaSelection(divisionId = "BD10", districtId = "BD1001", upazilaId = "BD9999999")

        assertNull(stale.reference(dataset))
        assertNull(stale.resolvedArea(dataset))
    }

    private fun complete(): AreaSelection = AreaSelection.EMPTY
        .selectDivision("BD10", dataset)
        .selectDistrict("BD1001", dataset)
        .selectUpazila("BD1001001", dataset)
}
