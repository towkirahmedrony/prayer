package io.github.towkirahmedrony.salah.core.domain.location

import io.github.towkirahmedrony.salah.core.model.BangladeshAreaRef
import io.github.towkirahmedrony.salah.core.model.BangladeshLocalityKind
import io.github.towkirahmedrony.salah.core.model.BangladeshLocalityRef
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.LocationOrigin
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one rule for how a stored location is named.
 *
 * Every case here is a shape the app can actually produce: a verified locality, an administrative area
 * resolved only as far as its district, one of the twelve city corporations whose name already contains
 * its district, a typed name, and a record written before any of this existed.
 */
class LocationDisplayNameTest {

    private val dhaka = BangladeshAreaRef(
        divisionId = "BD30",
        divisionName = "Dhaka",
        districtId = "BD3026",
        districtName = "Dhaka",
    )

    private val dhakaSouth = BangladeshAreaRef(
        divisionId = "BD30",
        divisionName = "Dhaka",
        districtId = "BD3026",
        districtName = "Dhaka",
        upazilaId = "BD30262000",
        upazilaName = "Dhaka South City Corporation",
    )

    private val savar = dhaka.copy(upazilaId = "BD30260072", upazilaName = "Savar")

    private fun locality(name: String, districtId: String = "BD3026") = BangladeshLocalityRef(
        name = name,
        districtId = districtId,
        kind = BangladeshLocalityKind.SUBURB,
    )

    @Test
    fun `a verified locality is named with its district`() {
        val name = LocationDisplayName.of(
            origin = LocationOrigin.DEVICE_GPS,
            storedLabel = "GPS location",
            area = dhakaSouth,
            locality = locality("Mirpur"),
        )

        assertEquals("Mirpur, Dhaka", name)
    }

    @Test
    fun `the locality wins over the administrative unit it sits inside`() {
        // The same record without the locality is a city corporation; with it, the place the user is in.
        val withLocality = LocationDisplayName.of(
            origin = LocationOrigin.DEVICE_GPS,
            storedLabel = "GPS location",
            area = dhakaSouth,
            locality = locality("Uttara"),
        )
        val withoutLocality = LocationDisplayName.of(
            origin = LocationOrigin.DEVICE_GPS,
            storedLabel = "GPS location",
            area = dhakaSouth,
            locality = null,
        )

        assertEquals("Uttara, Dhaka", withLocality)
        assertEquals("Dhaka South City Corporation", withoutLocality)
    }

    @Test
    fun `a city corporation does not repeat the district it is named after`() {
        // The defect this policy exists to prevent: "Dhaka South City Corporation, Dhaka" states one fact
        // twice, and every one of the twelve corporations reads that way.
        val name = LocationDisplayName.of(
            origin = LocationOrigin.AREA_SELECTION,
            storedLabel = "Dhaka South City Corporation, Dhaka",
            area = dhakaSouth,
            locality = null,
        )

        assertEquals("Dhaka South City Corporation", name)
    }

    @Test
    fun `an upazila that does not contain the district name is still qualified by it`() {
        val name = LocationDisplayName.of(
            origin = LocationOrigin.AREA_SELECTION,
            storedLabel = "Savar, Dhaka",
            area = savar,
            locality = null,
        )

        assertEquals("Savar, Dhaka", name)
    }

    @Test
    fun `an upazila whose name merely starts like the district keeps both names`() {
        // "Dhamrai" contains the letters of "Dhaka" in order? It does not, and a rule that ignored that
        // would drop the district from a legitimate pair. The comparison is on the full district name.
        val dhamrai = dhaka.copy(upazilaId = "BD30260014", upazilaName = "Dhamrai")

        val name = LocationDisplayName.of(
            origin = LocationOrigin.AREA_SELECTION,
            storedLabel = "Dhamrai, Dhaka",
            area = dhamrai,
            locality = null,
        )

        assertEquals("Dhamrai, Dhaka", name)
    }

    @Test
    fun `a district-level match is named by the district alone`() {
        val name = LocationDisplayName.of(
            origin = LocationOrigin.DEVICE_GPS,
            storedLabel = "GPS location",
            area = dhaka,
            locality = null,
        )

        assertEquals("Dhaka", name)
    }

    @Test
    fun `a locality with no resolved area is named on its own rather than guessed at`() {
        val name = LocationDisplayName.of(
            origin = LocationOrigin.DEVICE_GPS,
            storedLabel = "GPS location",
            area = null,
            locality = locality("Mirpur"),
        )

        assertEquals("Mirpur", name)
    }

    @Test
    fun `a locality that merely repeats its district is not doubled`() {
        val name = LocationDisplayName.of(
            origin = LocationOrigin.DEVICE_GPS,
            storedLabel = "GPS location",
            area = dhaka,
            locality = locality("Dhaka"),
        )

        assertEquals("Dhaka", name)
    }

    @Test
    fun `a typed name is never replaced by what the app knows`() {
        // A manual entry is the user's own words for a place. Even with an area and a locality attached,
        // the name they typed is the name they get.
        val name = LocationDisplayName.of(
            origin = LocationOrigin.MANUAL_ENTRY,
            storedLabel = "  Home  ",
            area = savar,
            locality = locality("Mirpur"),
        )

        assertEquals("Home", name)
    }

    @Test
    fun `a record with no facts keeps its stored label`() {
        assertEquals(
            "GPS location",
            LocationDisplayName.of(
                origin = LocationOrigin.DEVICE_GPS,
                storedLabel = "GPS location",
                area = null,
                locality = null,
            ),
        )
        assertEquals(
            "Dhaka South City Corporation, Dhaka",
            // A record written by an earlier build whose area could not be read back: unchanged rather than
            // emptied, because "we cannot describe this" is not a description.
            LocationDisplayName.of(
                origin = LocationOrigin.DEVICE_GPS,
                storedLabel = "Dhaka South City Corporation, Dhaka",
                area = null,
                locality = null,
            ),
        )
    }

    @Test
    fun `a stored location is named from its own parts, not from its stored string`() {
        val location = requireNotNull(
            LocationInfo.create(
                label = "Dhaka South City Corporation, Dhaka",
                latitude = 23.7461,
                longitude = 90.3742,
                timeZoneId = "Asia/Dhaka",
                origin = LocationOrigin.DEVICE_GPS,
                accuracyMetres = 18.0,
                area = dhakaSouth,
                locality = locality("Dhanmondi"),
            ),
        )

        assertEquals("Dhanmondi, Dhaka", LocationDisplayName.of(location))
    }

    @Test
    fun `the same facts always produce the same name`() {
        val first = LocationDisplayName.of(
            origin = LocationOrigin.DEVICE_GPS,
            storedLabel = "GPS location",
            area = savar,
            locality = locality("Mirpur"),
        )

        assertEquals(first, LocationDisplayName.of(
            origin = LocationOrigin.DEVICE_GPS,
            storedLabel = "GPS location",
            area = savar,
            locality = locality("Mirpur"),
        ))
    }
}
