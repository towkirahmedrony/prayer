package io.github.towkirahmedrony.salah.core.domain.mosque

import io.github.towkirahmedrony.salah.core.domain.location.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind an offline map download: the area a radius becomes, the tiles that area
 * contains, and the cases where no honest plan exists.
 *
 * These are pure rules with no Android and no map engine, so they are tested on the JVM. What the
 * engine actually downloads is covered by the store's contract tests with a fake, and by the real
 * MapLibre database on a device.
 */
class OfflineMapAreaTest {

    private val dhaka = GeoPoint(23.8103, 90.4125)

    @Test
    fun `a search radius produces bounds that contain the centre`() {
        val plan = OfflineMapArea.plan(dhaka, 5_000)!!

        assertTrue(plan.bounds.contains(dhaka))
        assertTrue(plan.bounds.north > dhaka.latitude)
        assertTrue(plan.bounds.south < dhaka.latitude)
        assertTrue(plan.bounds.east > dhaka.longitude)
        assertTrue(plan.bounds.west < dhaka.longitude)
    }

    @Test
    fun `the default area is bounded and affordable`() {
        val plan = OfflineMapArea.plan(dhaka, 5_000)!!

        assertTrue(plan.isWithinLimit)
        assertTrue(plan.tileCount > 0)
        // The estimate includes the style, glyphs and sprites, so it must exceed the tile cost alone.
        assertTrue(plan.estimatedBytes > plan.tileCount * OfflineMapArea.BYTES_PER_TILE)
    }

    @Test
    fun `the estimate grows with every added zoom level`() {
        val coarse = OfflineMapArea.plan(dhaka, 5_000, minZoom = 10.0, maxZoom = 12.0)!!
        val detailed = OfflineMapArea.plan(dhaka, 5_000, minZoom = 10.0, maxZoom = 14.0)!!

        assertTrue(detailed.tileCount > coarse.tileCount)
        assertTrue(detailed.estimatedBytes > coarse.estimatedBytes)
    }

    @Test
    fun `no plan is invented for an unusable centre or radius`() {
        assertNull(OfflineMapArea.plan(GeoPoint(Double.NaN, 90.0), 5_000))
        assertNull(OfflineMapArea.plan(dhaka, 0))
        assertNull(OfflineMapArea.plan(dhaka, 5_000, minZoom = 16.0, maxZoom = 10.0))
    }

    @Test
    fun `a circle crossing the antimeridian widens to the whole longitude range`() {
        val bounds = MapBounds.around(GeoPoint(0.0, 179.99), 5_000)!!

        assertEquals(180.0, bounds.east, 0.0)
        assertEquals(-180.0, bounds.west, 0.0)
    }

    @Test
    fun `a near-polar circle widens to the whole longitude range`() {
        val bounds = MapBounds.around(GeoPoint(89.999, 0.0), 5_000)!!

        assertEquals(180.0, bounds.east, 0.0)
        assertEquals(-180.0, bounds.west, 0.0)
    }

    @Test
    fun `a whole-globe area is refused rather than offered as one download`() {
        val plan = OfflineMapArea.plan(GeoPoint(0.0, 179.99), 5_000)

        assertTrue(plan == null || !plan.isWithinLimit)
    }

    @Test
    fun `stored bounds report whether they cover a point`() {
        val bounds = MapBounds(north = 24.0, south = 23.0, east = 91.0, west = 90.0)
        val region = OfflineMapRegion(
            id = 1L,
            label = "Dhaka",
            styleUrl = "https://example.test/style.json",
            bounds = bounds,
            minZoom = 10.0,
            maxZoom = 16.0,
            downloadedBytes = 0L,
            isComplete = true,
            createdAtEpochMillis = 0L,
        )

        assertTrue(bounds.isValid)
        assertTrue(region.covers(dhaka))
        assertFalse(region.covers(GeoPoint(10.0, 10.0)))
        assertFalse(region.covers(null))
        assertFalse(MapBounds(north = 23.0, south = 24.0, east = 91.0, west = 90.0).isValid)
    }
}
