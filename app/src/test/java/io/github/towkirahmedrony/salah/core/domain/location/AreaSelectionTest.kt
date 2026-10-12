package io.github.towkirahmedrony.salah.core.domain.location

import io.github.towkirahmedrony.salah.feature.common.LocationFormState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AreaSelectionTest {

    @Test
    fun `division selection exposes its districts`() {
        val state = LocationFormState().apply {
            divisionId = "dhaka"
            districtId = null
            upazilaId = null
            coordinates = null
        }
        val districts = state.districts(state.divisionId)
        assertTrue("Dhaka must have districts", districts.isNotEmpty())
        assertTrue("Districts must belong to the selected division", districts.all { it.divisionId == "dhaka" })
        assertTrue("Districts must be unique", districts.distinctBy { it.id }.size == districts.size)
    }

    @Test
    fun `district selection exposes its upazilas`() {
        val state = LocationFormState().apply {
            divisionId = "chittagong"
            districtId = "chandpur"
            upazilaId = null
            coordinates = null
        }
        val upazilas = state.upazilas(state.divisionId, state.districtId)
        assertTrue("Chandpur must have upazilas", upazilas.isNotEmpty())
        assertTrue("Upazilas must belong to the selected district", upazilas.all { it.districtId == "chandpur" })
    }

    @Test
    fun `changing the division resets the district`() {
        val before = LocationFormState().apply {
            divisionId = "dhaka"
            districtId = "dhaka"
            upazilaId = "gulshan-2"
            coordinates = LocationCoords(23.7432, 90.4024)
        }
        val after = LocationFormState().apply {
            divisionId = "barisal"
        }
        assertNull("District must be cleared when the division changes", after.districtId)
        assertNull("Upazila must be cleared when the division changes", after.upazilaId)
        assertNull("Coordinates must be cleared when the division changes", after.coordinates)
    }

    @Test
    fun `changing the district resets the upazila`() {
        val before = LocationFormState().apply {
            divisionId = "dhaka"
            districtId = "dhaka"
            upazilaId = "gulshan-2"
            coordinates = LocationCoords(23.7432, 90.4024)
        }
        val after = LocationFormState().apply {
            divisionId = "dhaka"
            districtId = "naturshah"
        }
        assertNull("Upazila must be cleared when the district changes", after.upazilaId)
    }

    @Test
    fun `changing the division resets an incompatible upazila`() {
        val before = LocationFormState().apply {
            divisionId = "dhaka"
            districtId = "dhaka"
            upazilaId = "gulshan-2"
            coordinates = LocationCoords(23.7432, 90.4024)
        }
        val after = LocationFormState().apply {
            divisionId = "sylhet"
        }
        assertNull("Upazila must be cleared when the division changes", after.upazilaId)
    }

    @Test
    fun `changing the district resets an incompatible upazila`() {
        val before = LocationFormState().apply {
            divisionId = "dhaka"
            districtId = "dhaka"
            upazilaId = "gulshan-2"
            coordinates = LocationCoords(23.7432, 90.4024)
        }
        val after = LocationFormState().apply {
            divisionId = "dhaka"
            districtId = "naturshah"
        }
        assertNull("Upazila must be cleared when the district changes", after.upazilaId)
    }

    @Test
    fun `the cascading form validates only compatible selections`() {
        val state = LocationFormState().apply {
            divisionId = "dhaka"
            districtId = "dhaka"
            upazilaId = "gulshan-2"
            coordinates = LocationCoords(23.7432, 90.4024)
        }
        assertTrue("Completely compatible selections must validate", state.isValid())
        assertEquals("The selected upazila must belong to the selected district", "gulshan-2", state.upazila(state.divisionId, state.districtId))
    }

    @Test
    fun `an incompatible combination is rejected before saving`() {
        val state = LocationFormState().apply {
            divisionId = "chittagong"
            districtId = "dhaka"
            upazilaId = "gulshan-2"
            coordinates = LocationCoords(23.7432, 90.4024)
        }
        assertFalse("Incompatible divisions, districts and upazilas must reject", state.isValid())
        assertNull("The selected upazila must belong to the selected district", state.upazila(state.divisionId, state.districtId))
    }
}

class AreaResolverSelectionTest {

    @Test
    fun `division selection resolves all its districts`() {
        val resolver = AreaResolver(BangladeshLocationDataset())
        val districts = resolver.districtsForDivision("dhaka")
        assertTrue("Dhaka division must have districts", districts.isNotEmpty())
        assertTrue("All districts must stay inside their division", districts.all { it.divisionId == "dhaka" })
        assertTrue("Districts must be unique", districts.distinctBy { it.id }.size == districts.size)
    }

    @Test
    fun `district selection resolves all its upazilas`() {
        val resolver = AreaResolver(BangladeshLocationDataset())
        val upazilas = resolver.upazilasForDistrict("chandpur")
        assertTrue("Chandpur district must have upazilas", upazilas.isNotEmpty())
        assertTrue("All upazilas must belong to the selected district", upazilas.all { it.districtId == "chandpur" })
        assertTrue("Upazilas must be unique", upazilas.distinctBy { it.id }.size == upazilas.size)
    }

    @Test
    fun `changing the division resets an incompatible upazila`() {
        val before = LocationFormState().apply {
            divisionId = "dhaka"
            districtId = "dhaka"
            upazilaId = "gulshan-2"
            coordinates = LocationCoords(23.7432, 90.4024)
        }
        val after = LocationFormState().apply {
            divisionId = "sylhet"
        }
        assertNull("Upazila must be cleared when the division changes", after.upazilaId)
    }

    @Test
    fun `changing the district resets an incompatible upazila`() {
        val before = LocationFormState().apply {
            divisionId = "dhaka"
            districtId = "dhaka"
            upazilaId = "gulshan-2"
            coordinates = LocationCoords(23.7432, 90.4024)
        }
        val after = LocationFormState().apply {
            divisionId = "dhaka"
            districtId = "naturshah"
        }
        assertNull("Upazila must be cleared when the district changes", after.upazilaId)
    }
}
