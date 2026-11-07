package io.github.towkirahmedrony.salah.data.mosque

import io.github.towkirahmedrony.salah.core.domain.mosque.Mosque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Geoapify Places GeoJSON mapping.
 *
 * The response uses GeoJSON's `[longitude, latitude]` order while the rest of Salah uses
 * latitude/longitude. That swap is the single easiest thing to get wrong in this feature, so it is
 * asserted against a real-shaped response rather than assumed. The malformed-feature cases matter as
 * much: one bad feature must not discard its siblings, and a response that is not a
 * FeatureCollection must be reported as malformed rather than silently producing an empty list.
 */
class GeoapifyMosqueParserTest {

    @Test
    fun `coordinates are swapped into latitude then longitude`() {
        val body = featureCollection(
            """
            {
              "type": "Feature",
              "properties": {
                "name": "Baitul Mukarram",
                "place_id": "abc123",
                "categories": ["religion.place_of_worship.islam"]
              },
              "geometry": { "type": "Point", "coordinates": [90.4125, 23.8103] }
            }
            """,
        )

        val page = GeoapifyMosqueParser.parse(body.toByteArray())

        val mosque = page!!.mosques.single()
        assertEquals(23.8103, mosque.location.latitude, 0.0)
        assertEquals(90.4125, mosque.location.longitude, 0.0)
    }

    @Test
    fun `a full response maps every displayed field`() {
        val body = featureCollection(
            """
            {
              "type": "Feature",
              "properties": {
                "name": "  Jame Masjid  ",
                "formatted": "  Dhanmondi, Dhaka  ",
                "place_id": "  place-42  ",
                "categories": [
                  "religion.place_of_worship.islam",
                  "tourism.sights.place_of_worship.mosque"
                ],
                "datasource": {
                  "raw": {
                    "phone": "+880 1700 000000",
                    "website": "https://example.org/masjid"
                  }
                }
              },
              "geometry": { "type": "Point", "coordinates": [90.38, 23.74] }
            }
            """,
        )

        val mosque = GeoapifyMosqueParser.parse(body.toByteArray())!!.mosques.single()

        assertEquals("Jame Masjid", mosque.name)
        assertEquals("Dhanmondi, Dhaka", mosque.address)
        assertEquals("place-42", mosque.placeId)
        assertEquals(
            listOf(
                Mosque.CATEGORY_ISLAMIC_WORSHIP,
                Mosque.CATEGORY_MOSQUE_SPECIFIC,
            ),
            mosque.categories,
        )
        assertEquals("+880 1700 000000", mosque.phone)
        assertEquals("https://example.org/masjid", mosque.website)
    }

    @Test
    fun `address lines are joined when no formatted address is present`() {
        val body = featureCollection(
            """
            {
              "type": "Feature",
              "properties": {
                "name": "Noor Mosque",
                "place_id": "p1",
                "address_line1": "12 Road 5",
                "address_line2": "Dhanmondi, Dhaka"
              },
              "geometry": { "type": "Point", "coordinates": [90.38, 23.74] }
            }
            """,
        )

        val mosque = GeoapifyMosqueParser.parse(body.toByteArray())!!.mosques.single()

        assertEquals("12 Road 5, Dhanmondi, Dhaka", mosque.address)
    }

    @Test
    fun `absent optional fields stay absent rather than becoming placeholders`() {
        val body = featureCollection(
            """
            {
              "type": "Feature",
              "properties": {
                "name": "Noor Mosque",
                "place_id": "p1"
              },
              "geometry": { "type": "Point", "coordinates": [90.38, 23.74] }
            }
            """,
        )

        val mosque = GeoapifyMosqueParser.parse(body.toByteArray())!!.mosques.single()

        assertNull(mosque.address)
        assertNull(mosque.phone)
        assertNull(mosque.website)
        assertTrue(mosque.categories.isEmpty())
    }

    @Test
    fun `a feature without a place id gets a stable coordinate-derived identity`() {
        val properties = """
            "properties": { "name": "  Noor   Mosque  " },
            "geometry": { "type": "Point", "coordinates": [90.38, 23.74] }
        """
        val first = GeoapifyMosqueParser.parse(
            featureCollection("""{ "type": "Feature", $properties }""").toByteArray(),
        )!!.mosques.single()
        val second = GeoapifyMosqueParser.parse(
            featureCollection("""{ "type": "Feature", $properties }""").toByteArray(),
        )!!.mosques.single()

        assertTrue(first.placeId.startsWith("geo:"))
        assertEquals(first.placeId, second.placeId)
        assertNotEquals("", first.placeId)
    }

    @Test
    fun `one malformed feature does not discard its siblings`() {
        val body = featureCollection(
            // No geometry.
            """
            { "type": "Feature", "properties": { "name": "Broken Mosque", "place_id": "bad" } }
            """,
            // No name.
            """
            {
              "type": "Feature",
              "properties": { "place_id": "nameless" },
              "geometry": { "type": "Point", "coordinates": [90.38, 23.74] }
            }
            """,
            // Coordinates are not numbers.
            """
            {
              "type": "Feature",
              "properties": { "name": "String Coordinates Mosque", "place_id": "strings" },
              "geometry": { "type": "Point", "coordinates": ["90.38", "23.74"] }
            }
            """,
            // A valid sibling.
            """
            {
              "type": "Feature",
              "properties": { "name": "Good Mosque", "place_id": "good" },
              "geometry": { "type": "Point", "coordinates": [90.38, 23.74] }
            }
            """,
        )

        val page = GeoapifyMosqueParser.parse(body.toByteArray())!!

        assertEquals(4, page.featureCount)
        assertEquals(listOf("good"), page.mosques.map { it.placeId })
    }

    @Test
    fun `a root that is not a FeatureCollection is malformed`() {
        assertNull(GeoapifyMosqueParser.parse(ByteArray(0)))
        assertNull(GeoapifyMosqueParser.parse("not json".toByteArray()))
        assertNull(GeoapifyMosqueParser.parse("[]".toByteArray()))
        assertNull(GeoapifyMosqueParser.parse("""{ "type": "FeatureCollection" }""".toByteArray()))
        assertNull(
            GeoapifyMosqueParser.parse(
                """{ "type": "Feature", "features": [] }""".toByteArray(),
            ),
        )
    }

    @Test
    fun `an empty FeatureCollection is a readable empty page`() {
        val page = GeoapifyMosqueParser.parse(featureCollection().toByteArray())

        assertEquals(0, page!!.featureCount)
        assertTrue(page.mosques.isEmpty())
    }

    private fun featureCollection(vararg features: String): String =
        """{ "type": "FeatureCollection", "features": [${features.joinToString(",")}] }"""
}
