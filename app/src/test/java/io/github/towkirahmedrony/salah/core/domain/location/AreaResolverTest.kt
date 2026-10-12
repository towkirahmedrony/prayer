package io.github.towkirahmedrony.salah.core.domain.location

import io.github.towkirahmedrony.salah.core.model.BangladeshAreaLevel
import io.github.towkirahmedrony.salah.testing.syntheticDataset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a device fix becomes an administrative area.
 *
 * The synthetic fixture is used for the level decisions because its geometry is obvious and stable, so
 * a failure reads as "the wrong level was chosen" rather than as a change in the bundled data.
 */
class AreaResolverTest {

    private val dataset = syntheticDataset()

    @Test
    fun `a fix at an upazila centre resolves to that upazila`() {
        // Kumarkhali's published centre, which is also inside Kushtia district.
        val resolution = AreaResolver.resolve(GeoPoint(23.86, 89.24), dataset)

        assertTrue("expected a suggestion, got $resolution", resolution is AreaResolution.Suggested)
        val suggestion = (resolution as AreaResolution.Suggested).suggestion
        assertEquals(BangladeshAreaLevel.UPAZILA, suggestion.matchedLevel)
        assertEquals("BD1001001", suggestion.reference.upazilaId)
        assertEquals("BD1001", suggestion.reference.districtId)
        assertEquals("BD10", suggestion.reference.divisionId)
        assertEquals("Kushtia", suggestion.reference.districtName)
        assertEquals(0.0, suggestion.distanceMetres, 1.0)
    }

    @Test
    fun `a fix within the district but beyond the upazila threshold resolves at district level`() {
        // One district whose only upazila centre is ~55 km away: too far to name the upazila, close
        // enough to name the district.
        val sparse = inlineDataset(
            row("DIVISION", "BD30", "BD", "Somewhere", "", "23.00", "90.00"),
            row("DISTRICT", "BD3001", "BD30", "Central", "", "23.00", "90.00"),
            row("UPAZILA", "BD3001001", "BD3001", "Far", "", "23.50", "90.00"),
        )

        val resolution = AreaResolver.resolve(GeoPoint(23.00, 90.00), sparse)

        assertTrue("expected a suggestion, got $resolution", resolution is AreaResolution.Suggested)
        val suggestion = (resolution as AreaResolution.Suggested).suggestion
        assertEquals(BangladeshAreaLevel.DISTRICT, suggestion.matchedLevel)
        assertEquals("BD3001", suggestion.reference.districtId)
        assertNull("a district-level match must not name an upazila", suggestion.reference.upazilaId)
        assertTrue("expected a district-scale distance, got ${suggestion.distanceMetres}", suggestion.distanceMetres > 20_000.0)
    }

    @Test
    fun `a fix further than every district threshold is reported as unresolved`() {
        val resolution = AreaResolver.resolve(GeoPoint(20.0, 88.5), dataset)

        assertTrue("expected unresolved, got $resolution", resolution is AreaResolution.Unresolved)
        val nearest = (resolution as AreaResolution.Unresolved).nearestDistanceMetres
        assertNotNull("an unresolved fix from a usable dataset reports how far the nearest centre was", nearest)
        assertTrue("nearest was $nearest m, should exceed the district threshold", requireNotNull(nearest) > AreaResolver.DISTRICT_MATCH_METRES)
    }

    @Test
    fun `a non-finite coordinate is unresolved without a distance`() {
        val resolution = AreaResolver.resolve(GeoPoint(Double.NaN, 90.0), dataset)

        assertEquals(AreaResolution.Unresolved(nearestDistanceMetres = null), resolution)
    }

    @Test
    fun `a dataset without a boundary cannot resolve anything`() {
        val usable = syntheticDataset()
        val noBoundary = inlineDataset(
            row("DIVISION", "BD10", "BD", "Khulna", "", "22.80", "89.30"),
            boundary = "",
        )

        assertTrue("the fixture with a boundary must be usable", usable.isUsable)
        assertTrue("a dataset without a boundary must not be usable", !noBoundary.isUsable)
        assertEquals(AreaResolution.DatasetUnavailable, AreaResolver.resolve(GeoPoint(22.8, 89.3), noBoundary))
    }

    @Test
    fun `resolution is deterministic for the same input`() {
        val point = GeoPoint(24.62, 89.42) // Sherpur's published centre under Bogura.

        val first = AreaResolver.resolve(point, dataset)
        val second = AreaResolver.resolve(point, dataset)

        assertEquals(first, second)
        assertTrue(first is AreaResolution.Suggested)
    }

    private fun inlineDataset(vararg rows: String, boundary: String = DEFAULT_BOUNDARY): BangladeshLocationDataset =
        BangladeshLocationDataset.parse(
            version = "test-inline",
            areasText = rows.joinToString("\n"),
            boundaryText = boundary,
        )

    private fun row(vararg fields: String): String = fields.joinToString("\t")
}

/** A square that contains every inline area in [AreaResolverTest] (20..26 N, 88..93 E). */
private val DEFAULT_BOUNDARY: String = listOf(
    "0\t20.0\t88.0",
    "0\t20.0\t93.0",
    "0\t26.0\t93.0",
    "0\t26.0\t88.0",
).joinToString("\n")
