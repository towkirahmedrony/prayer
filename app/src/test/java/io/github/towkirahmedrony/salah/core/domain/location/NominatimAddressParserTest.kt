package io.github.towkirahmedrony.salah.core.domain.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapping from a provider's response to Salah's address model.
 *
 * This is where "do not invent missing address components" is enforced, so the cases are mostly about
 * what is *left out*: a payload with no neighbourhood produces no neighbourhood, an unparsable body is
 * reported as unavailable rather than partially read, and a response with nothing usable is `NotFound`
 * rather than an empty `Found`.
 */
class NominatimAddressParserTest {

    @Test
    fun `reads place locality road district division and country`() {
        val result = NominatimAddressParser.parse(
            """
            {
              "display_name": "Dhanmondi, Dhaka, Dhaka Division, Bangladesh",
              "name": "Dhanmondi",
              "address": {
                "suburb": "Dhanmondi",
                "road": "Mirpur Road",
                "state_district": "Dhaka",
                "state": "Dhaka Division",
                "country": "Bangladesh",
                "country_code": "bd"
              }
            }
            """.trimIndent(),
        )

        val address = (result as ReverseGeocodeResult.Found).address
        assertEquals("Dhanmondi", address.placeName)
        assertEquals("Dhanmondi", address.locality)
        assertEquals("Mirpur Road", address.road)
        assertEquals("Dhaka", address.district)
        assertEquals("Dhaka Division", address.division)
        assertEquals("Bangladesh", address.country)
        // The provider lower-cases the code; the app compares an upper-case ISO code.
        assertEquals("BD", address.countryCode)
        assertEquals("Dhanmondi", address.primaryName)
    }

    @Test
    fun `a component the provider does not publish stays absent`() {
        val result = NominatimAddressParser.parse(
            """{"display_name":"Somewhere","address":{"county":"Kushtia","country":"Bangladesh"}}""",
        )
        val address = (result as ReverseGeocodeResult.Found).address
        assertNull("no suburb in the payload must not become a locality", address.locality)
        assertNull("no road in the payload must not become a road", address.road)
        // `county` is accepted as the district key, so this one is present.
        assertEquals("Kushtia", address.district)
        assertEquals("Somewhere", address.primaryName)
    }

    @Test
    fun `falls back from place name to a populated place key`() {
        val result = NominatimAddressParser.parse(
            """{"display_name":"A village","address":{"village":"Char Fasson"}}""",
        )
        val address = (result as ReverseGeocodeResult.Found).address
        assertEquals("Char Fasson", address.placeName)
        assertEquals("Char Fasson", address.locality)
    }

    @Test
    fun `a malformed body is unavailable rather than partially read`() {
        val result = NominatimAddressParser.parse("not json")
        assertTrue(result is ReverseGeocodeResult.Unavailable)
        assertEquals(
            NominatimAddressParser.REASON_MALFORMED,
            (result as ReverseGeocodeResult.Unavailable).reason,
        )
    }

    @Test
    fun `a well-formed response with no address is not found`() {
        val result = NominatimAddressParser.parse("""{"display_name":"   ","address":{"country_code":"bd"}}""")
        assertTrue(result is ReverseGeocodeResult.NotFound)
        assertEquals(
            NominatimAddressParser.REASON_NO_ADDRESS,
            (result as ReverseGeocodeResult.NotFound).reason,
        )
    }

    @Test
    fun `an empty address object is not a found address`() {
        val result = NominatimAddressParser.parse("""{"address":{}}""")
        assertTrue(result is ReverseGeocodeResult.NotFound)
    }
}
