package io.github.towkirahmedrony.salah.core.domain.mosque

import io.github.towkirahmedrony.salah.core.domain.location.GeoMath
import io.github.towkirahmedrony.salah.core.domain.location.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mosque finder's filtering and ordering rules.
 *
 * These are the rules that decide whether the answer to "which mosques are near me?" contains a
 * mosque or a madrasah, and whether the list is actually nearest-first. They are pure functions, so
 * every case here is asserted directly rather than through the screen.
 */
class MosqueGeoTest {

    @Test
    fun `a mosque-specific category is accepted even when the name says nothing`() {
        val mosque = mosque(
            name = "Baitul Aman",
            categories = listOf(Mosque.CATEGORY_MOSQUE_SPECIFIC),
        )

        assertTrue(MosqueGeo.isMosque(mosque))
        assertTrue(MosqueGeo.isUsable(mosque))
    }

    @Test
    fun `the broader Islamic category is accepted only when the name names a mosque`() {
        val names = listOf(
            "Baitul Mukarram",
            "Jame Masjid",
            "Gulshan Central Mosque",
            "Moshjid-e-Noor",
            "মসজিদে নববী",
            "Shahi Jame Mosque",
        )

        names.forEach { name ->
            assertTrue(
                "$name should be recognised as a mosque",
                MosqueGeo.isMosque(
                    mosque(name = name, categories = listOf(Mosque.CATEGORY_ISLAMIC_WORSHIP)),
                ),
            )
        }
    }

    @Test
    fun `Islamic institutions without a mosque word are rejected from the broader category`() {
        val names = listOf(
            "Noorani Madrasah",
            "Hafizia Maktab",
            "Shah Ali Dargah",
            "Khankah Shirajia",
            "Darul Aman Orphanage",
        )

        names.forEach { name ->
            assertFalse(
                "$name is not a mosque and must not be shown",
                MosqueGeo.isMosque(
                    mosque(name = name, categories = listOf(Mosque.CATEGORY_ISLAMIC_WORSHIP)),
                ),
            )
        }
    }

    @Test
    fun `a name that contains both a mosque word and an institution word is kept`() {
        // The building names a mosque even though it also names the school attached to it.
        val candidate = mosque(
            name = "Sayednogor Mosjid Madrasa",
            categories = listOf(Mosque.CATEGORY_ISLAMIC_WORSHIP),
        )

        assertTrue(MosqueGeo.isMosque(candidate))
    }

    @Test
    fun `a feature without a mosque category is rejected regardless of its name`() {
        val candidate = mosque(
            name = "Central Mosque",
            categories = listOf("religion.place_of_worship.christian"),
        )

        assertFalse(MosqueGeo.isMosque(candidate))
    }

    @Test
    fun `unusable features are dropped from normalization`() {
        val usable = mosque(placeId = "a", name = "A Mosque")
        val blankId = mosque(placeId = " ", name = "B Mosque")
        val blankName = mosque(placeId = "c", name = " ")
        val notFinite = mosque(
            placeId = "d",
            name = "D Mosque",
            location = GeoPoint(Double.NaN, 90.4),
        )
        val notMosque = mosque(placeId = "e", name = "E Madrasah")

        val normalized = MosqueGeo.normalize(
            listOf(usable, blankId, blankName, notFinite, notMosque),
        )

        assertEquals(listOf("a"), normalized.map { it.placeId })
    }

    @Test
    fun `normalization keeps the first occurrence of a repeated place id`() {
        val first = mosque(placeId = "same", name = "First Mosque", address = "First address")
        val second = mosque(placeId = "same", name = "Second Mosque", address = "Second address")

        val normalized = MosqueGeo.normalize(listOf(first, second))

        assertEquals(1, normalized.size)
        assertEquals("First Mosque", normalized.single().name)
        assertEquals("First address", normalized.single().address)
    }

    @Test
    fun `nearest first orders by locally calculated distance`() {
        val center = GeoPoint(23.8103, 90.4125) // Dhaka
        val farther = mosque(
            placeId = "farther",
            name = "Farther Mosque",
            location = GeoPoint(23.8303, 90.4125),
        )
        val nearer = mosque(
            placeId = "nearer",
            name = "Nearer Mosque",
            location = GeoPoint(23.8123, 90.4125),
        )

        val ordered = MosqueGeo.nearestFirst(center, listOf(farther, nearer))

        assertEquals(listOf("nearer", "farther"), ordered.map { it.mosque.placeId })
        assertTrue(ordered.first().distanceMetres < ordered.last().distanceMetres)
        assertEquals(
            GeoMath.distanceMetres(center, nearer.location),
            ordered.first().distanceMetres,
            0.001,
        )
    }

    @Test
    fun `equal distances are ordered by name so the list is deterministic`() {
        val center = GeoPoint(0.0, 0.0)
        val b = mosque(placeId = "b", name = "B Mosque", location = GeoPoint(0.001, 0.0))
        val a = mosque(placeId = "a", name = "A Mosque", location = GeoPoint(0.001, 0.0))

        val ordered = MosqueGeo.nearestFirst(center, listOf(b, a))

        assertEquals(listOf("a", "b"), ordered.map { it.mosque.placeId })
    }

    @Test
    fun `results outside the radius are removed even when the provider returned them`() {
        val center = GeoPoint(0.0, 0.0)
        val inside = mosque(placeId = "inside", name = "Inside Mosque", location = GeoPoint(0.002, 0.0))
        val outside = mosque(placeId = "outside", name = "Outside Mosque", location = GeoPoint(0.02, 0.0))

        val within = MosqueGeo.nearestFirst(
            center = center,
            mosques = listOf(inside, outside),
            radiusMetres = 500,
        )

        assertEquals(listOf("inside"), within.map { it.mosque.placeId })
    }

    private fun mosque(
        placeId: String = "place-1",
        name: String = "Baitul Mukarram",
        address: String? = null,
        location: GeoPoint = GeoPoint(23.8103, 90.4125),
        categories: List<String> = listOf(Mosque.CATEGORY_ISLAMIC_WORSHIP),
    ) = Mosque(
        placeId = placeId,
        name = name,
        address = address,
        location = location,
        categories = categories,
    )
}
