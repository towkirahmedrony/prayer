package io.github.towkirahmedrony.salah.data.mosque

import io.github.towkirahmedrony.salah.core.domain.location.GeoPoint
import io.github.towkirahmedrony.salah.core.domain.mosque.Mosque
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Room-backed cache's read, update and pruning behaviour.
 *
 * The DAO is faked rather than opened as a real database: the pure decisions this class makes — the
 * bounding box, the exact radius filter, the distance sort, the upsert-by-identity and the age cutoff
 * — are what would regress, while Room's SQL generation has its own compile-time verification. The
 * fake DAO records what the cache asked Room to do so the queries' shape is asserted too.
 */
class RoomMosqueCacheTest {

    private val center = GeoPoint(0.0, 0.0)

    @Test
    fun `an empty table produces no snapshot`() = runTest {
        val cache = RoomMosqueCache(FakeDao())

        assertNull(cache.read(center, radiusMetres = 1_000))
    }

    @Test
    fun `a stored mosque is read back and its distance is calculated from the query centre`() = runTest {
        val dao = FakeDao()
        val cache = RoomMosqueCache(dao)
        cache.upsert(
            listOf(mosque("a", "A Mosque", latitude = 0.002)),
            fetchedAtEpochMillis = NOW,
        )

        val snapshot = cache.read(center, radiusMetres = 1_000)!!

        val item = snapshot.mosques.single()
        assertEquals("a", item.mosque.placeId)
        assertTrue(item.distanceMetres > 200.0 && item.distanceMetres < 230.0)
        assertEquals(NOW, snapshot.cachedAtEpochMillis)
    }

    @Test
    fun `the rectangle query is used but the exact circle decides membership`() = runTest {
        val dao = FakeDao()
        val cache = RoomMosqueCache(dao)
        // Inside the 1 km bounding square (~0.009 degrees) but about 1.2 km away on the diagonal.
        cache.upsert(
            listOf(
                mosque("inside", "Inside Mosque", latitude = 0.005, longitude = 0.0),
                mosque("corner", "Corner Mosque", latitude = 0.008, longitude = 0.008),
            ),
            fetchedAtEpochMillis = NOW,
        )

        val snapshot = cache.read(center, radiusMetres = 1_000)!!

        assertEquals(listOf("inside"), snapshot.mosques.map { it.mosque.placeId })
        assertNotNull(dao.lastBounds)
    }

    @Test
    fun `results are nearest-first`() = runTest {
        val cache = RoomMosqueCache(FakeDao())
        cache.upsert(
            listOf(
                mosque("far", "Far Mosque", latitude = 0.008),
                mosque("near", "Near Mosque", latitude = 0.001),
                mosque("middle", "Middle Mosque", latitude = 0.004),
            ),
            fetchedAtEpochMillis = NOW,
        )

        val snapshot = cache.read(center, radiusMetres = 2_000)!!

        assertEquals(
            listOf("near", "middle", "far"),
            snapshot.mosques.map { it.mosque.placeId },
        )
    }

    @Test
    fun `upserting the same identity updates the row instead of duplicating it`() = runTest {
        val dao = FakeDao()
        val cache = RoomMosqueCache(dao)
        cache.upsert(
            listOf(mosque("same", "Old Name", latitude = 0.001)),
            fetchedAtEpochMillis = NOW,
        )
        cache.upsert(
            listOf(mosque("same", "New Name", latitude = 0.002)),
            fetchedAtEpochMillis = NOW + 500,
        )

        val snapshot = cache.read(center, radiusMetres = 1_000)!!

        assertEquals(1, dao.rows.size)
        assertEquals("New Name", snapshot.mosques.single().mosque.name)
        assertEquals(NOW + 500, snapshot.cachedAtEpochMillis)
    }

    @Test
    fun `duplicate identities inside one upsert are collapsed before reaching the DAO`() = runTest {
        val dao = FakeDao()
        val cache = RoomMosqueCache(dao)

        cache.upsert(
            listOf(
                mosque("same", "First", latitude = 0.001),
                mosque("same", "Last", latitude = 0.001),
            ),
            fetchedAtEpochMillis = NOW,
        )

        assertEquals(1, dao.rows.size)
    }

    @Test
    fun `non-mosque and unusable rows are not stored`() = runTest {
        val dao = FakeDao()
        val cache = RoomMosqueCache(dao)

        cache.upsert(
            listOf(
                mosque("good", "Good Mosque", latitude = 0.001),
                mosque(
                    placeId = "madrasah",
                    name = "Madrasah",
                    latitude = 0.002,
                    categories = listOf(Mosque.CATEGORY_ISLAMIC_WORSHIP),
                ),
                mosque("blank", " ", latitude = 0.003),
            ),
            fetchedAtEpochMillis = NOW,
        )

        assertEquals(listOf("good"), dao.rows.values.map { it.placeId })
    }

    @Test
    fun `a row that cannot describe a mosque is skipped`() = runTest {
        val dao = FakeDao()
        dao.rows["broken"] = CachedMosqueEntity(
            placeId = "",
            name = "Broken Mosque",
            address = null,
            latitude = 0.0,
            longitude = 0.0,
            categories = Mosque.CATEGORY_MOSQUE_SPECIFIC,
            phone = null,
            website = null,
            fetchedAtEpochMillis = NOW,
        )
        dao.rows["good"] = mosque("good", "Good Mosque", latitude = 0.001).toCachedEntity(NOW)

        val snapshot = RoomMosqueCache(dao).read(center, radiusMetres = 1_000)!!

        assertEquals(listOf("good"), snapshot.mosques.map { it.mosque.placeId })
    }

    @Test
    fun `pruneBefore deletes older rows and keeps the rest`() = runTest {
        val dao = FakeDao()
        val cache = RoomMosqueCache(dao)
        cache.upsert(listOf(mosque("old", "Old Mosque", latitude = 0.001)), NOW - 10_000)
        cache.upsert(listOf(mosque("new", "New Mosque", latitude = 0.002)), NOW - 1_000)

        cache.pruneBefore(NOW - 5_000)

        assertEquals(listOf("new"), dao.rows.values.map { it.placeId })
    }

    @Test
    fun `the newest fetch time is the snapshot timestamp`() = runTest {
        val cache = RoomMosqueCache(FakeDao())
        cache.upsert(listOf(mosque("a", "A Mosque", latitude = 0.001)), NOW - 10_000)
        cache.upsert(listOf(mosque("b", "B Mosque", latitude = 0.002)), NOW)

        val snapshot = cache.read(center, radiusMetres = 1_000)!!

        assertEquals(NOW, snapshot.cachedAtEpochMillis)
    }

    @Test
    fun `an invalid centre or radius is not queried`() = runTest {
        val dao = FakeDao()
        val cache = RoomMosqueCache(dao)

        assertNull(cache.read(GeoPoint(Double.NaN, 0.0), 1_000))
        assertNull(cache.read(center, radiusMetres = 0))
        assertEquals(0, dao.nearbyCalls)
    }

    @Test
    fun `the bounding box widens to the world near a pole instead of dividing by zero`() {
        val bounds = RoomMosqueCache(FakeDao()).boundsAround(
            center = GeoPoint(90.0, 12.0),
            radiusMetres = 1_000,
        )

        assertEquals(90.0, bounds.maxLatitude, 0.0)
        assertTrue(bounds.minLatitude <= 90.0)
        assertEquals(-180.0, bounds.minLongitude, 0.0)
        assertEquals(180.0, bounds.maxLongitude, 0.0)
    }

    private fun mosque(
        placeId: String,
        name: String,
        latitude: Double,
        longitude: Double = 0.0,
        categories: List<String> = listOf(Mosque.CATEGORY_MOSQUE_SPECIFIC),
    ) = Mosque(
        placeId = placeId,
        name = name,
        address = null,
        location = GeoPoint(latitude, longitude),
        categories = categories,
    )

    /**
     * A DAO that behaves like the generated one for the two operations this cache uses.
     *
     * `nearby` applies the same rectangle predicate as the SQL, then returns rows in insertion order,
     * which is exactly the unsorted input the cache is expected to sort itself.
     */
    private class FakeDao : MosqueDao {

        val rows: LinkedHashMap<String, CachedMosqueEntity> = LinkedHashMap()
        var nearbyCalls: Int = 0
            private set
        var lastBounds: CacheBounds? = null
            private set

        override suspend fun nearby(
            minLatitude: Double,
            maxLatitude: Double,
            minLongitude: Double,
            maxLongitude: Double,
        ): List<CachedMosqueEntity> {
            nearbyCalls++
            lastBounds = CacheBounds(minLatitude, maxLatitude, minLongitude, maxLongitude)
            return rows.values.filter { row ->
                row.latitude in minLatitude..maxLatitude &&
                    row.longitude in minLongitude..maxLongitude
            }
        }

        override suspend fun upsertAll(mosques: List<CachedMosqueEntity>) {
            mosques.forEach { row -> rows[row.placeId] = row }
        }

        override suspend fun pruneBefore(cutoffEpochMillis: Long): Int {
            val before = rows.size
            rows.entries.removeAll { entry -> entry.value.fetchedAtEpochMillis < cutoffEpochMillis }
            return before - rows.size
        }

        override suspend fun newestFetchTime(): Long? =
            rows.values.maxOfOrNull { row -> row.fetchedAtEpochMillis }
    }

    private companion object {
        const val NOW: Long = 1_800_000_000_000L
    }
}
