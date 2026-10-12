package io.github.towkirahmedrony.salah.core.domain.location

import io.github.towkirahmedrony.salah.feature.common.LocationFormState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AreaResolverTest {

    @Test
    fun `manual area selection resolves a division to its districts`() {
        val resolver = AreaResolver(BangladeshLocationDataset())
        val districts = resolver.districtsForDivision("dhaka")
        assertTrue("Dhaka division must have districts", districts.isNotEmpty())
        assertTrue("All returned districts must stay inside their division", districts.all { it.divisionId == "dhaka" })
        assertTrue("Districts must be unique", districts.distinctBy { it.id }.size == districts.size)
    }

    @Test
    fun `district selection resolves all its upazilas`() {
        val resolver = AreaResolver(BangladeshLocationDataset())
        val upazilas = resolver.upazilasForDistrict("chandpur")
        assertTrue("Chandpur district must have upazilas", upazilas.isNotEmpty())
        assertTrue("All returned upazilas must belong to the selected district", upazilas.all { it.districtId == "chandpur" })
        assertTrue("Upazilas must be unique", upazilas.distinctBy { it.id }.size == upazilas.size)
    }

    @Test
    fun `choosing an area leaves only the representative coordinates available`() {
        val state = LocationFormState().apply {
            divisionId = "dhaka"
            districtId = "naturshah"
            upazilaId = "shahidullah"
            coordinates = LocationCoords(23.7432, 90.4024)
        }
        val selected = state.selectedArea
        assertNotNull("A selected area must have all administrative identifiers", selected)
        assertEquals("Division id", "dhaka", selected.divisionId)
        assertEquals("District id", "naturshah", selected.districtId)
        assertEquals("Upazila id", "shahidullah", selected.upazilaId)
        assertTrue("Selected area must resolve to coordinates", selected.coordinates != null)
    }

    @Test
    fun `the area reference is not a fresh GPS fix`() {
        val state = LocationFormState().apply {
            divisionId = "dhaka"
            districtId = "naturshah"
            upazilaId = "shahidullah"
            coordinates = LocationCoords(23.7432, 90.4024)
        }
        val selected = state.selectedArea
        assertNotNull("Selected area must have reference coordinates", selected)
        assertTrue("Selected area coordinates must be real region coordinates", selected.validCoordinates())
    }

    @Test
    fun `resolution preserves the administrative hierarchy`() {
        val state = LocationFormState().apply {
            divisionId = "chittagong"
            districtId = "chandpur"
            upazilaId = "chandpur-2"
            coordinates = LocationCoords(22.8344, 90.7839)
        }
        val selected = state.selectedArea
        assertNotNull("Hierarchical selections must resolve to an area", selected)
        assertEquals("Division id", "chittagong", selected.divisionId)
        assertEquals("District id", "chandpur", selected.districtId)
        assertEquals("Upazila id", "chandpur-2", selected.upazilaId)
    }

    @Test
    fun `unresolved areas do not have coordinates`() {
        val state = LocationFormState().apply {
            divisionId = "barisal"
            districtId = "bhola"
            upazilaId = "anjuman"
            coordinates = null
        }
        val selected = state.selectedArea
        assertNotNull("Unresolved areas must still resolve to an area", selected)
        assertNull("Unresolved area coordinates must be null", selected.coordinates)
        assertTrue("Unresolved area must not be inside Bangladesh", !selected.insideBangladesh())
    }

    @Test
    fun `the resolved area still satisfies the location policy after manual selection`() {
        val resolver = AreaResolver(BangladeshLocationDataset())
        val area = resolver.areaFor("dhaka", "naturshah", "shahidullah")
        assertNotNull("Resolution must return an area even for sparse data", area)
        assertTrue("Resolved areas must be inside Bangladesh", area.insideBangladesh())
        assertTrue("Resolved areas must have valid reference coordinates", area.validCoordinates())
    }
}
