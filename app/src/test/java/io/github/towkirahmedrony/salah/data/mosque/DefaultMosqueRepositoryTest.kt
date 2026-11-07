package io.github.towkirahmedrony.salah.data.mosque

import io.github.towkirahmedrony.salah.core.domain.dataset.remote.NetworkFailureKind
import io.github.towkirahmedrony.salah.core.domain.location.GeoPoint
import io.github.towkirahmedrony.salah.core.domain.mosque.Mosque
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueCache
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueCacheSnapshot
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueDataOrigin
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueFailure
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueLoadResult
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueQuery
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueSearchConfig
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueSearchSource
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueSourceResult
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueWithDistance
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The repository's network-first, cache-second policy.
 *
 * The cases that matter are the partial ones: a failure after one usable page must keep that page, a
 * failure with nothing usable must fall back to the cache, and a cache write that fails must not turn
 * a successful search into a failure. Those are the states the screen has to distinguish, so they are
 * asserted at this layer rather than inferred from the UI.
 */
class DefaultMosqueRepositoryTest {

    private val query = MosqueQuery(
        center = GeoPoint(23.8103, 90.4125),
        radiusMetres = 5_000,
        pageSize = 2,
        maxPages = 3,
        language = "en",
    )

    private val clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC)

    @Test
    fun `successful pages are merged, filtered, sorted and written to the cache`() = runTest {
        val source = ScriptedSource(
            pages = mapOf(
                0 to success(
                    mosque("far", "Far Mosque", latitude = 23.8303),
                    mosque(
                        "not-a-mosque",
                        "Madrasah",
                        categories = listOf(Mosque.CATEGORY_ISLAMIC_WORSHIP),
                    ),
                ),
                2 to success(
                    mosque("near", "Near Mosque", latitude = 23.8123),
                    mosque("far", "Far Mosque", latitude = 23.8303),
                ),
            ),
        )
        val cache = FakeCache()

        val result = repository(source, cache).nearby(query)

        val success = result as MosqueLoadResult.Success
        assertEquals(MosqueDataOrigin.NETWORK, success.origin)
        assertEquals(listOf("near", "far"), success.mosques.map { it.mosque.placeId })
        assertNull(success.refreshFailure)
        // The cache stores the accepted set; its order is irrelevant because every read re-sorts.
        assertEquals(setOf("near", "far"), cache.upserted.map { it.placeId }.toSet())
        assertEquals(NOW, cache.upsertedAt)
        assertEquals(NOW - MosqueSearchConfig.DEFAULT_CACHE_MAX_AGE_MILLIS, cache.prunedBefore)
    }

    @Test
    fun `pagination stops after a short page`() = runTest {
        val source = ScriptedSource(
            pages = mapOf(
                0 to MosqueSourceResult.Success(
                    mosques = listOf(mosque("one", "One Mosque")),
                    featureCount = 1,
                ),
                2 to success(mosque("two", "Two Mosque"), mosque("three", "Three Mosque")),
            ),
        )

        repository(source, FakeCache()).nearby(query)

        assertEquals(listOf(0), source.requestedOffsets)
    }

    @Test
    fun `pagination stops at the configured page ceiling`() = runTest {
        val fullPage = success(mosque("a", "A Mosque"), mosque("b", "B Mosque"))
        val source = ScriptedSource(pages = mapOf(0 to fullPage, 2 to fullPage, 4 to fullPage))

        repository(source, FakeCache()).nearby(query.copy(maxPages = 2))

        assertEquals(listOf(0, 2), source.requestedOffsets)
    }

    @Test
    fun `a failure after a usable page keeps the usable page`() = runTest {
        val source = ScriptedSource(
            pages = mapOf(
                0 to success(mosque("one", "One Mosque"), mosque("two", "Two Mosque")),
                2 to MosqueSourceResult.HttpError(500),
            ),
        )
        val cache = FakeCache(snapshot = snapshot(mosque("cached", "Cached Mosque")))

        val result = repository(source, cache).nearby(query)

        val success = result as MosqueLoadResult.Success
        assertEquals(MosqueDataOrigin.NETWORK, success.origin)
        assertEquals(listOf("one", "two"), success.mosques.map { it.mosque.placeId })
        // The earlier page is real data; a later failure is not allowed to replace it with the cache.
        assertEquals(listOf("one", "two"), cache.upserted.map { it.placeId })
    }

    @Test
    fun `with no usable page the cache is returned and carries the refresh failure`() = runTest {
        val cache = FakeCache(snapshot = snapshot(mosque("cached", "Cached Mosque")))

        val result = repository(
            ScriptedSource(pages = mapOf(0 to MosqueSourceResult.HttpError(500))),
            cache,
        ).nearby(query)

        val success = result as MosqueLoadResult.Success
        assertEquals(MosqueDataOrigin.CACHE, success.origin)
        assertEquals(MosqueFailure.SERVER, success.refreshFailure)
        assertEquals(listOf("cached"), success.mosques.map { it.mosque.placeId })
        assertEquals(CACHED_AT, success.cachedAtEpochMillis)
        assertTrue(cache.upserted.isEmpty())
    }

    @Test
    fun `network failures map to the reasons the screen explains`() = runTest {
        val cases = mapOf(
            MosqueSourceResult.RateLimited to MosqueFailure.RATE_LIMITED,
            MosqueSourceResult.Malformed to MosqueFailure.MALFORMED,
            MosqueSourceResult.HttpError(401) to MosqueFailure.ACCESS_DENIED,
            MosqueSourceResult.HttpError(403) to MosqueFailure.ACCESS_DENIED,
            MosqueSourceResult.HttpError(429) to MosqueFailure.RATE_LIMITED,
            MosqueSourceResult.HttpError(500) to MosqueFailure.SERVER,
            MosqueSourceResult.NetworkFailure(NetworkFailureKind.TIMEOUT) to MosqueFailure.TIMEOUT,
            MosqueSourceResult.NetworkFailure(NetworkFailureKind.CONNECTION) to MosqueFailure.CONNECTION,
            MosqueSourceResult.NetworkFailure(NetworkFailureKind.UNKNOWN) to MosqueFailure.UNKNOWN,
            MosqueSourceResult.TooLarge(10L) to MosqueFailure.RESPONSE_TOO_LARGE,
        )

        cases.forEach { (sourceResult, expected) ->
            val result = repository(
                ScriptedSource(pages = mapOf(0 to sourceResult)),
                FakeCache(),
            ).nearby(query)

            assertEquals(
                "$sourceResult should map to $expected",
                MosqueLoadResult.Failure(expected),
                result,
            )
        }
    }

    @Test
    fun `an unconfigured key reports not configured and can still use the cache`() = runTest {
        val source = ScriptedSource(pages = emptyMap())
        val configured = MosqueSearchConfig(apiKey = "", baseUrl = MosqueSearchConfig.DEFAULT_BASE_URL)
        assertEquals(MosqueFailure.NOT_CONFIGURED, (repository(source, FakeCache(), configured).nearby(query) as MosqueLoadResult.Failure).reason)

        val cached = repository(
            source,
            FakeCache(snapshot = snapshot(mosque("cached", "Cached Mosque"))),
            configured,
        ).nearby(query) as MosqueLoadResult.Success

        assertEquals(MosqueDataOrigin.CACHE, cached.origin)
        assertEquals(MosqueFailure.NOT_CONFIGURED, cached.refreshFailure)
        assertTrue(source.requestedOffsets.isEmpty())
    }

    @Test
    fun `a cache read exception falls back to the original network failure`() = runTest {
        val cache = FakeCache(readError = IllegalStateException("database unavailable"))

        val result = repository(
            ScriptedSource(pages = mapOf(0 to MosqueSourceResult.HttpError(500))),
            cache,
        ).nearby(query)

        assertEquals(MosqueLoadResult.Failure(MosqueFailure.SERVER), result)
    }

    @Test
    fun `a cache write exception does not fail a successful search`() = runTest {
        val cache = FakeCache(writeError = IllegalStateException("disk full"))

        val result = repository(
            ScriptedSource(pages = mapOf(0 to success(mosque("one", "One Mosque")))),
            cache,
        ).nearby(query)

        val success = result as MosqueLoadResult.Success
        assertEquals(listOf("one"), success.mosques.map { it.mosque.placeId })
        assertEquals(MosqueDataOrigin.NETWORK, success.origin)
    }

    @Test
    fun `an empty network page is a successful empty result, not a cache fallback`() = runTest {
        val cache = FakeCache(snapshot = snapshot(mosque("cached", "Cached Mosque")))

        val result = repository(
            ScriptedSource(
                pages = mapOf(0 to MosqueSourceResult.Success(emptyList(), featureCount = 0)),
            ),
            cache,
        ).nearby(query)

        val success = result as MosqueLoadResult.Success
        assertEquals(MosqueDataOrigin.NETWORK, success.origin)
        assertTrue(success.mosques.isEmpty())
        assertTrue(cache.upserted.isEmpty())
    }

    @Test
    fun `results outside the radius are not shown`() = runTest {
        val source = ScriptedSource(
            pages = mapOf(
                0 to success(
                    mosque("near", "Near Mosque", latitude = 23.8123),
                    mosque("outside", "Outside Mosque", latitude = 23.9),
                ),
            ),
        )

        val success = repository(source, FakeCache()).nearby(query) as MosqueLoadResult.Success

        assertEquals(listOf("near"), success.mosques.map { it.mosque.placeId })
    }

    @Test
    fun `cachedNearby reads the cache without making a request`() = runTest {
        val source = ScriptedSource(pages = emptyMap())
        val cache = FakeCache(snapshot = snapshot(mosque("cached", "Cached Mosque")))

        val result = repository(source, cache).cachedNearby(query)

        val success = result as MosqueLoadResult.Success
        assertEquals(MosqueDataOrigin.CACHE, success.origin)
        assertTrue(source.requestedOffsets.isEmpty())
    }

    private fun repository(
        source: MosqueSearchSource,
        cache: MosqueCache,
        config: MosqueSearchConfig = MosqueSearchConfig(apiKey = "test-key"),
    ) = DefaultMosqueRepository(
        config = config,
        source = source,
        cache = cache,
        clock = clock,
    )

    private fun success(vararg mosques: Mosque) =
        MosqueSourceResult.Success(mosques = mosques.toList(), featureCount = mosques.size)

    private fun mosque(
        placeId: String,
        name: String,
        latitude: Double = 23.8103,
        longitude: Double = 90.4125,
        categories: List<String> = listOf(Mosque.CATEGORY_MOSQUE_SPECIFIC),
    ) = Mosque(
        placeId = placeId,
        name = name,
        address = null,
        location = GeoPoint(latitude, longitude),
        categories = categories,
    )

    private fun snapshot(vararg mosques: Mosque, cachedAt: Long = CACHED_AT) = MosqueCacheSnapshot(
        mosques = mosques.map { mosque ->
            MosqueWithDistance(
                mosque = mosque,
                distanceMetres = 10.0,
            )
        },
        cachedAtEpochMillis = cachedAt,
    )

    private class ScriptedSource(
        private val pages: Map<Int, MosqueSourceResult>,
    ) : MosqueSearchSource {

        val requestedOffsets: MutableList<Int> = mutableListOf()

        override suspend fun search(query: MosqueQuery, offset: Int): MosqueSourceResult {
            requestedOffsets += offset
            return pages[offset] ?: MosqueSourceResult.HttpError(404)
        }
    }

    private class FakeCache(
        var snapshot: MosqueCacheSnapshot? = null,
        var readError: Throwable? = null,
        var writeError: Throwable? = null,
    ) : MosqueCache {

        var upserted: List<Mosque> = emptyList()
            private set
        var upsertedAt: Long? = null
            private set
        var prunedBefore: Long? = null
            private set

        override suspend fun read(center: GeoPoint, radiusMetres: Int): MosqueCacheSnapshot? {
            readError?.let { throw it }
            return snapshot
        }

        override suspend fun upsert(mosques: Collection<Mosque>, fetchedAtEpochMillis: Long) {
            writeError?.let { throw it }
            upserted = mosques.toList()
            upsertedAt = fetchedAtEpochMillis
        }

        override suspend fun pruneBefore(cutoffEpochMillis: Long) {
            writeError?.let { throw it }
            prunedBefore = cutoffEpochMillis
        }
    }

    private companion object {
        const val NOW: Long = 1_800_000_000_000L
        const val CACHED_AT: Long = 1_700_000_000_000L
    }
}
