package io.github.towkirahmedrony.salah.core.domain.location

import io.github.towkirahmedrony.salah.core.model.BangladeshAreaRef
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.LocationOrigin
import io.github.towkirahmedrony.salah.testing.syntheticDataset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The derived setup gate: whether Salah can use the stored location, and if not, why.
 *
 * "Derived" is the point — the answer comes from the location itself rather than a stored flag, so a
 * usable location and a completed setup can never disagree.
 */
class LocationSetupTest {

    private val dataset = syntheticDataset()

    @Test
    fun `no stored location with a usable dataset requires setup`() {
        val state = LocationSetup.evaluate(stored = null, dataset = dataset)

        assertEquals(LocationSetupState.Required(reason = LocationSetupReason.NO_LOCATION), state)
        assertTrue(state.requiresSetup)
    }

    @Test
    fun `no stored location and no dataset reports the dataset as unavailable`() {
        val state = LocationSetup.evaluate(stored = null, dataset = null)

        assertTrue(state is LocationSetupState.DatasetUnavailable)
        assertTrue(state.requiresSetup)
    }

    @Test
    fun `no stored location and an unusable dataset reports the dataset as unavailable`() {
        val unusable = BangladeshLocationDataset.parse(
            version = "test-unusable",
            areasText = "DIVISION\tBD10\tBD\tKhulna\t\t22.80\t89.30",
            boundaryText = "",
        )

        assertFalse(unusable.isUsable)
        assertTrue(LocationSetup.evaluate(stored = null, dataset = unusable) is LocationSetupState.DatasetUnavailable)
    }

    @Test
    fun `a stored location outside the coarse bounds is rejected as unsupported`() {
        val london = location("London", 51.5074, -0.1278)

        val state = LocationSetup.evaluate(stored = london, dataset = dataset)

        assertEquals(
            LocationSetupState.Required(reason = LocationSetupReason.OUTSIDE_SUPPORTED_COUNTRY, stored = london),
            state,
        )
        assertTrue(state.requiresSetup)
    }

    @Test
    fun `a stored location inside the boundary is complete`() {
        val kumarkhali = location("Kumarkhali", 23.86, 89.24)

        val state = LocationSetup.evaluate(stored = kumarkhali, dataset = dataset)

        assertEquals(LocationSetupState.Complete(kumarkhali), state)
        assertFalse(state.requiresSetup)
    }

    @Test
    fun `a stored location inside the coarse bounds but well outside the boundary is rejected`() {
        // Inside the country's bounding rectangle, but far outside the bundled boundary polygon.
        val bad = location("Elsewhere", 26.0, 92.0)

        val state = LocationSetup.evaluate(stored = bad, dataset = dataset)

        assertEquals(
            LocationSetupState.Required(reason = LocationSetupReason.OUTSIDE_SUPPORTED_COUNTRY, stored = bad),
            state,
        )
    }

    @Test
    fun `a stored location within the boundary tolerance is kept`() {
        // ~2 km east of the synthetic boundary's eastern edge: outside the polygon, inside the
        // tolerance. The legitimate border-adjacent case must not be thrown away.
        val nearBorder = location("Near the border", 23.5, 90.02)

        val state = LocationSetup.evaluate(stored = nearBorder, dataset = dataset)

        assertEquals(LocationSetupState.Complete(nearBorder), state)
    }

    @Test
    fun `a stored location whose area the dataset no longer recognises is reported`() {
        val missingArea = location(
            label = "Kushtia",
            latitude = 23.86,
            longitude = 89.24,
            area = BangladeshAreaRef(
                divisionId = "BD10",
                divisionName = "Khulna",
                districtId = "BD1001",
                districtName = "Kushtia",
                upazilaId = "BD9999999",
                upazilaName = "Ghost",
            ),
        )

        val state = LocationSetup.evaluate(stored = missingArea, dataset = dataset)

        assertEquals(
            LocationSetupState.Required(reason = LocationSetupReason.AREA_NOT_IN_DATASET, stored = missingArea),
            state,
        )
    }

    @Test
    fun `a stored location whose area the dataset recognises is complete`() {
        val division = requireNotNull(dataset.area("BD10"))
        val district = requireNotNull(dataset.area("BD1001"))
        val upazila = requireNotNull(dataset.area("BD1001001"))
        val withArea = location(
            label = "Kumarkhali",
            latitude = 23.86,
            longitude = 89.24,
            area = BangladeshAreaRef.of(division, district, upazila),
        )

        assertEquals(LocationSetupState.Complete(withArea), LocationSetup.evaluate(withArea, dataset))
    }

    @Test
    fun `a usable location survives an unreadable dataset`() {
        // A dataset that failed to load must not lock out a user whose coordinates are already valid.
        val dhaka = location("Inside the bounds", 23.5, 89.0)

        assertEquals(LocationSetupState.Complete(dhaka), LocationSetup.evaluate(stored = dhaka, dataset = null))
    }

    @Test
    fun `an area-less location inside the bounds is complete without an area check`() {
        val plain = location("Manual", 23.5, 89.0)

        assertNull(plain.area)
        assertEquals(LocationSetupState.Complete(plain), LocationSetup.evaluate(stored = plain, dataset = dataset))
    }

    private fun location(
        label: String,
        latitude: Double,
        longitude: Double,
        area: BangladeshAreaRef? = null,
    ): LocationInfo = requireNotNull(
        LocationInfo.create(
            label = label,
            latitude = latitude,
            longitude = longitude,
            timeZoneId = "Asia/Dhaka",
            origin = LocationOrigin.MANUAL_ENTRY,
            area = area,
        ),
    )
}
