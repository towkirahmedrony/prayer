package io.github.towkirahmedrony.salah.data.metalprice

import io.github.towkirahmedrony.salah.core.domain.dataset.remote.NetworkFailureKind
import io.github.towkirahmedrony.salah.core.domain.dataset.remote.RemoteFetch
import io.github.towkirahmedrony.salah.core.domain.metalprice.CachedMetalRate
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceCache
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceFailure
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceFreshness
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceFreshnessPolicy
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceRemoteConfig
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceSnapshot
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPurity
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalType
import io.github.towkirahmedrony.salah.core.domain.metalprice.PublishedMetalPrice
import io.github.towkirahmedrony.salah.core.domain.metalprice.ReferencePriceResult
import io.github.towkirahmedrony.salah.core.domain.metalprice.RemoteMetalPriceSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The offline path: what is kept, what is adopted, and what a failure must never destroy.
 *
 * The source and the cache are both fakes, so no test here reaches a network or a filesystem. The rates are
 * labelled fixtures; `FileMetalPriceCacheTest` covers the real file.
 */
class SupabaseMetalPriceRepositoryCacheTest {

    private val config = MetalPriceRemoteConfig(baseUrl = "https://example.invalid", anonKey = "publishable-key")
    private val now: Long = Instant.parse("2026-10-18T12:00:00Z").toEpochMilli()

    private fun clockAt(epochMillis: Long): Clock =
        Clock.fixed(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC)

    private fun repository(
        source: FakeMetalPriceSource,
        cache: FakeMetalPriceCache = FakeMetalPriceCache(),
        clock: Clock = clockAt(now),
    ): SupabaseMetalPriceRepository = SupabaseMetalPriceRepository(
        source = source,
        config = config,
        cache = cache,
        clock = clock,
        freshnessPolicy = MetalPriceFreshnessPolicy(),
    )

    private fun record(
        metal: String,
        price: String,
        purity: String = "K22",
        currency: String = "BDT",
        active: Boolean = true,
        status: String = "VERIFIED",
    ): String = buildString {
        append("{\"metal_type\":\"$metal\",\"purity\":\"$purity\",\"price_per_gram\":$price,")
        append("\"currency\":\"$currency\",\"is_active\":$active,\"verification_status\":\"$status\"}")
    }

    private fun body(vararg rows: String): String = rows.joinToString(prefix = "[", postfix = "]", separator = ",")

    private fun rate(metal: MetalType, price: String, fetchedAt: Long): CachedMetalRate = CachedMetalRate(
        price = PublishedMetalPrice(
            metal = metal,
            purity = MetalPurity.K22,
            pricePerGram = BigDecimal(price),
            currency = "BDT",
        ),
        fetchedAtEpochMillis = fetchedAt,
    )

    private fun cachedSnapshot(
        gold: String? = "18000",
        silver: String? = "300",
        goldFetchedAt: Long = now - ONE_DAY,
        silverFetchedAt: Long = now - ONE_DAY,
    ): MetalPriceSnapshot = MetalPriceSnapshot(
        gold = gold?.let { price -> rate(MetalType.GOLD, price, goldFetchedAt) },
        silver = silver?.let { price -> rate(MetalType.SILVER, price, silverFetchedAt) },
    )

    // --- Persistence and restoration ------------------------------------------------------------

    @Test
    fun `a validated response is cached and restored after repository recreation`() = runTest {
        val cache = FakeMetalPriceCache()
        val source = FakeMetalPriceSource(
            RemoteFetch.Success(body(record("GOLD", "19550.07"), record("SILVER", "360.25"))),
        )
        repository(source, cache).publishedReferencePrices()

        // A new repository over the same storage, with the service now unreachable.
        val offlineSource = FakeMetalPriceSource(RemoteFetch.NetworkFailure(NetworkFailureKind.CONNECTION))
        val restored = repository(offlineSource, cache).cachedReferencePrices()

        val gold = restored?.gold as ReferencePriceResult.Available
        val silver = restored.silver as ReferencePriceResult.Available
        assertEquals("19550.07", gold.price.pricePerGram.toPlainString())
        assertEquals("360.25", silver.price.pricePerGram.toPlainString())
        assertEquals(0, offlineSource.calls)
    }

    @Test
    fun `a cached price is available without any request`() = runTest {
        val cache = FakeMetalPriceCache(cachedSnapshot())
        val source = FakeMetalPriceSource(RemoteFetch.Success("[]"))

        val prices = repository(source, cache).cachedReferencePrices()

        assertTrue(prices?.gold is ReferencePriceResult.Available)
        assertEquals(0, source.calls)
    }

    @Test
    fun `a network failure preserves the previous cache and reports why it is shown`() = runTest {
        val cache = FakeMetalPriceCache(cachedSnapshot())
        val source = FakeMetalPriceSource(RemoteFetch.NetworkFailure(NetworkFailureKind.TIMEOUT))

        val prices = repository(source, cache).publishedReferencePrices()

        assertEquals(MetalPriceFailure.TIMEOUT, prices.refreshFailure)
        assertEquals(0, cache.writes)
        assertEquals(
            "18000",
            (prices.gold as ReferencePriceResult.Available).price.pricePerGram.toPlainString(),
        )
        assertEquals(
            "300",
            (prices.silver as ReferencePriceResult.Available).price.pricePerGram.toPlainString(),
        )
    }

    @Test
    fun `malformed JSON preserves the previous cache`() = runTest {
        val cache = FakeMetalPriceCache(cachedSnapshot())
        val source = FakeMetalPriceSource(RemoteFetch.Success("{ not json"))

        val prices = repository(source, cache).publishedReferencePrices()

        assertEquals(MetalPriceFailure.MALFORMED, prices.refreshFailure)
        assertEquals(0, cache.writes)
        assertTrue(prices.gold is ReferencePriceResult.Available)
    }

    @Test
    fun `an unavailable table preserves the previous cache`() = runTest {
        val cache = FakeMetalPriceCache(cachedSnapshot())

        val prices = repository(FakeMetalPriceSource(RemoteFetch.NotFound), cache).publishedReferencePrices()

        assertEquals(MetalPriceFailure.TABLE_UNAVAILABLE, prices.refreshFailure)
        assertEquals(0, cache.writes)
        assertTrue(prices.silver is ReferencePriceResult.Available)
    }

    // --- Complete, partial and invalid responses ------------------------------------------------

    @Test
    fun `a response that omits silver cannot erase the cached silver rate`() = runTest {
        val oldSilverAt = now - 3 * ONE_DAY
        val cache = FakeMetalPriceCache(cachedSnapshot(silverFetchedAt = oldSilverAt))
        val source = FakeMetalPriceSource(RemoteFetch.Success(body(record("GOLD", "19550"))))

        val prices = repository(source, cache).publishedReferencePrices()

        val gold = prices.gold as ReferencePriceResult.Available
        val silver = prices.silver as ReferencePriceResult.Available
        assertEquals("19550", gold.price.pricePerGram.toPlainString())
        assertEquals(MetalPriceFreshness.CURRENT, gold.freshness)
        // Silver was not republished, so it is neither deleted nor made to look freshly fetched.
        assertEquals("300", silver.price.pricePerGram.toPlainString())
        assertEquals(MetalPriceFreshness.CACHED, silver.freshness)
        assertEquals(oldSilverAt, silver.cachedAtEpochMillis)
        assertEquals(oldSilverAt, cache.stored?.silver?.fetchedAtEpochMillis)
    }

    @Test
    fun `an invalid silver row preserves silver while adopting valid gold`() = runTest {
        val cache = FakeMetalPriceCache(cachedSnapshot())
        val source = FakeMetalPriceSource(
            RemoteFetch.Success(body(record("GOLD", "19550"), record("SILVER", "0"))),
        )

        val prices = repository(source, cache).publishedReferencePrices()

        assertEquals(MetalPriceFailure.INVALID_DATA, prices.refreshFailure)
        assertEquals(
            "19550",
            (prices.gold as ReferencePriceResult.Available).price.pricePerGram.toPlainString(),
        )
        // The zero price is never stored, and the known-good silver rate is not lost.
        assertEquals(
            "300",
            (prices.silver as ReferencePriceResult.Available).price.pricePerGram.toPlainString(),
        )
        // The response as a set was not valid, so the stored snapshot was not replaced at all.
        assertEquals(0, cache.writes)
        assertEquals("18000", cache.stored?.gold?.price?.pricePerGram?.toPlainString())
        assertEquals("300", cache.stored?.silver?.price?.pricePerGram?.toPlainString())
    }

    @Test
    fun `conflicting gold records are reported and the cached gold rate is preserved`() = runTest {
        val cache = FakeMetalPriceCache(cachedSnapshot())
        val source = FakeMetalPriceSource(
            RemoteFetch.Success(
                body(
                    record("GOLD", "19550"),
                    record("GOLD", "21000"),
                    record("SILVER", "360"),
                ),
            ),
        )

        val prices = repository(source, cache).publishedReferencePrices()

        assertEquals(MetalPriceFailure.CONFLICTING, prices.refreshFailure)
        assertEquals(
            "18000",
            (prices.gold as ReferencePriceResult.Available).price.pricePerGram.toPlainString(),
        )
        // The unambiguous silver rate from the same response is still offered for this session, but the
        // ambiguous response is not persisted.
        assertEquals(
            "360",
            (prices.silver as ReferencePriceResult.Available).price.pricePerGram.toPlainString(),
        )
        assertEquals(0, cache.writes)
        assertEquals("300", cache.stored?.silver?.price?.pricePerGram?.toPlainString())
    }

    @Test
    fun `an unchanged dataset is not written again`() = runTest {
        val oldAt = now - 2 * ONE_DAY
        val cache = FakeMetalPriceCache(
            cachedSnapshot(gold = "19550", silver = "360", goldFetchedAt = oldAt, silverFetchedAt = oldAt),
        )
        val source = FakeMetalPriceSource(
            RemoteFetch.Success(body(record("GOLD", "19550"), record("SILVER", "360"))),
        )

        val prices = repository(source, cache).publishedReferencePrices()

        assertEquals(0, cache.writes)
        // The stored acquisition times are unchanged, because the bytes on disk still say the same thing.
        assertEquals(oldAt, cache.stored?.gold?.fetchedAtEpochMillis)
        // The successful check is still reported as current for this session.
        assertEquals(MetalPriceFreshness.CURRENT, (prices.gold as ReferencePriceResult.Available).freshness)
    }

    @Test
    fun `an inactive record is never selected as a current price`() = runTest {
        val cache = FakeMetalPriceCache()
        val source = FakeMetalPriceSource(
            RemoteFetch.Success(body(record("GOLD", "19550", active = false))),
        )

        val prices = repository(source, cache).publishedReferencePrices()

        assertEquals(ReferencePriceResult.NotPublished, prices.gold)
        assertEquals(0, cache.writes)
    }

    @Test
    fun `an unsupported gold purity is ignored rather than guessed at`() = runTest {
        val cache = FakeMetalPriceCache()
        val source = FakeMetalPriceSource(RemoteFetch.Success(body(record("GOLD", "20000", purity = "K21"))))

        val prices = repository(source, cache).publishedReferencePrices()

        assertEquals(ReferencePriceResult.NotPublished, prices.gold)
    }

    @Test
    fun `a non-BDT record is ignored rather than reinterpreted`() = runTest {
        val cache = FakeMetalPriceCache()
        val source = FakeMetalPriceSource(RemoteFetch.Success(body(record("SILVER", "5", currency = "USD"))))

        val prices = repository(source, cache).publishedReferencePrices()

        assertEquals(ReferencePriceResult.NotPublished, prices.silver)
    }

    @Test
    fun `a missing price is distinct from a zero price`() = runTest {
        val noCache = FakeMetalPriceCache()
        val absent = repository(
            FakeMetalPriceSource(RemoteFetch.Success(body(record("SILVER", "0")))),
            noCache,
        ).publishedReferencePrices()

        assertEquals(ReferencePriceResult.Failed(MetalPriceFailure.INVALID_DATA), absent.silver)
        assertNull((absent.gold as? ReferencePriceResult.Available)?.price)
        assertEquals(ReferencePriceResult.NotPublished, absent.gold)
    }

    // --- Freshness ------------------------------------------------------------------------------

    @Test
    fun `a rate fetched within a day is current`() = runTest {
        val cache = FakeMetalPriceCache(cachedSnapshot(goldFetchedAt = now - ONE_DAY / 2))

        val prices = repository(FakeMetalPriceSource(RemoteFetch.Success("[]")), cache)
            .cachedReferencePrices()

        assertEquals(
            MetalPriceFreshness.CURRENT,
            (prices?.gold as ReferencePriceResult.Available).freshness,
        )
    }

    @Test
    fun `a saved rate older than a day is cached, and one older than a week is stale`() = runTest {
        val cache = FakeMetalPriceCache(cachedSnapshot(goldFetchedAt = now - 2 * ONE_DAY))

        val cached = repository(FakeMetalPriceSource(RemoteFetch.Success("[]")), cache)
            .cachedReferencePrices()?.gold as ReferencePriceResult.Available
        assertEquals(MetalPriceFreshness.CACHED, cached.freshness)

        val staleCache = FakeMetalPriceCache(cachedSnapshot(goldFetchedAt = now - 8 * ONE_DAY))
        val stale = repository(FakeMetalPriceSource(RemoteFetch.Success("[]")), staleCache)
            .cachedReferencePrices()?.gold as ReferencePriceResult.Available
        assertEquals(MetalPriceFreshness.STALE, stale.freshness)
    }

    private companion object {
        const val ONE_DAY = 24L * 60L * 60L * 1_000L
    }
}

/** A price source that answers from memory and counts its calls. */
private class FakeMetalPriceSource(private val response: RemoteFetch<String>) : RemoteMetalPriceSource {

    var calls: Int = 0
        private set

    override suspend fun fetchPublishedRows(): RemoteFetch<String> {
        calls += 1
        return response
    }
}

/** A cache that keeps one snapshot in memory and counts writes, so "no write" is assertable. */
private class FakeMetalPriceCache(var stored: MetalPriceSnapshot? = null) : MetalPriceCache {

    var writes: Int = 0
        private set

    override suspend fun read(): MetalPriceSnapshot? = stored

    override suspend fun write(snapshot: MetalPriceSnapshot) {
        writes += 1
        stored = snapshot
    }
}
