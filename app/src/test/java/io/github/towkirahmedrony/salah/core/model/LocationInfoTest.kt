package io.github.towkirahmedrony.salah.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocationInfoTest {

    @Test
    fun `accepts a valid location and normalises the time zone`() {
        val location = LocationInfo.create(
            label = "  Dhaka  ",
            latitude = 23.8103,
            longitude = 90.4125,
            timeZoneId = " Asia/Dhaka ",
        )

        requireNotNull(location)
        assertEquals("Dhaka", location.label)
        assertEquals("Asia/Dhaka", location.timeZoneId)
        assertEquals(LocationOrigin.MANUAL_ENTRY, location.origin)
    }

    @Test
    fun `rejects a blank label`() {
        assertNull(
            LocationInfo.create(
                label = "   ",
                latitude = 23.0,
                longitude = 90.0,
                timeZoneId = "Asia/Dhaka",
            ),
        )
    }

    @Test
    fun `rejects a latitude outside the valid range`() {
        assertNull(
            LocationInfo.create(
                label = "Nowhere",
                latitude = 91.0,
                longitude = 90.0,
                timeZoneId = "Asia/Dhaka",
            ),
        )
        assertNull(
            LocationInfo.create(
                label = "Nowhere",
                latitude = -91.0,
                longitude = 90.0,
                timeZoneId = "Asia/Dhaka",
            ),
        )
    }

    @Test
    fun `rejects a longitude outside the valid range`() {
        assertNull(
            LocationInfo.create(
                label = "Nowhere",
                latitude = 23.0,
                longitude = 181.0,
                timeZoneId = "Asia/Dhaka",
            ),
        )
    }

    @Test
    fun `rejects a non IANA time zone`() {
        assertNull(
            LocationInfo.create(
                label = "Broken",
                latitude = 23.0,
                longitude = 90.0,
                timeZoneId = "not/a/zone",
            ),
        )
    }

    @Test
    fun `rejects a NaN coordinate`() {
        assertNull(
            LocationInfo.create(
                label = "Broken",
                latitude = Double.NaN,
                longitude = 90.0,
                timeZoneId = "Asia/Dhaka",
            ),
        )
    }
}
