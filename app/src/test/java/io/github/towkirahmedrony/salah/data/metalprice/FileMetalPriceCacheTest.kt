package io.github.towkirahmedrony.salah.data.metalprice

import io.github.towkirahmedrony.salah.core.domain.metalprice.CachedMetalRate
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceSnapshot
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPurity
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalType
import io.github.towkirahmedrony.salah.core.domain.metalprice.PublishedMetalPrice
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files

/**
 * The cache on disk: what is restored, what is refused, and what is never written.
 *
 * Every read uses a fresh cache instance, so the assertions are about the file rather than about an in-memory
 * copy. The rates are labelled fixtures, not claims about the Bangladesh market.
 */
class FileMetalPriceCacheTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("salah-metal-price-cache").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun cache(): FileMetalPriceCache = FileMetalPriceCache(root)

    private fun rate(metal: MetalType, price: String, fetchedAt: Long): CachedMetalRate = CachedMetalRate(
        price = PublishedMetalPrice(
            metal = metal,
            purity = MetalPurity.K22,
            pricePerGram = BigDecimal(price),
            currency = "BDT",
            source = "Salah test fixture",
            sourceUrl = "https://example.invalid/fixture",
            effectiveAt = "2026-10-18T00:00:00Z",
            providerLastUpdate = "2026-10-18T05:00:00Z",
            fetchedAt = "2026-10-18T06:00:00Z",
            updatedAt = "2026-10-18T06:05:00Z",
        ),
        fetchedAtEpochMillis = fetchedAt,
    )

    private fun snapshot(
        goldPrice: String? = "19550.07",
        silverPrice: String? = "360.25",
        goldFetchedAt: Long = 1_700_000_000_000L,
        silverFetchedAt: Long = 1_700_000_100_000L,
    ): MetalPriceSnapshot = MetalPriceSnapshot(
        gold = goldPrice?.let { price -> rate(MetalType.GOLD, price, goldFetchedAt) },
        silver = silverPrice?.let { price -> rate(MetalType.SILVER, price, silverFetchedAt) },
    )

    @Test
    fun `a written snapshot is restored by a fresh cache instance`() = runTest {
        val written = snapshot()
        cache().write(written)

        val restored = cache().read()

        assertEquals(written, restored)
    }

    @Test
    fun `each metal keeps its own acquisition time`() = runTest {
        cache().write(snapshot(goldFetchedAt = 111L, silverFetchedAt = 222L))

        val restored = cache().read()

        assertEquals(111L, restored?.gold?.fetchedAtEpochMillis)
        assertEquals(222L, restored?.silver?.fetchedAtEpochMillis)
    }

    @Test
    fun `the exact decimal digits survive the round trip`() = runTest {
        cache().write(snapshot(goldPrice = "19550.07", silverPrice = "360.2500"))

        val restored = cache().read()

        // Value and scale: the cache is not a place where the service's digits may be normalised.
        assertEquals(0, BigDecimal("19550.07").compareTo(restored?.gold?.price?.pricePerGram))
        assertEquals("19550.07", restored?.gold?.price?.pricePerGram?.toPlainString())
        assertEquals("360.2500", restored?.silver?.price?.pricePerGram?.toPlainString())
    }

    @Test
    fun `an empty snapshot is not written at all`() = runTest {
        cache().write(MetalPriceSnapshot())

        assertNull(cache().read())
        assertFalse(File(root, "cache.json").exists())
    }

    @Test
    fun `only one metal may be cached when the other has no published rate`() = runTest {
        cache().write(snapshot(silverPrice = null))

        val restored = cache().read()

        assertEquals(MetalType.GOLD, restored?.gold?.price?.metal)
        assertNull(restored?.silver)
    }

    @Test
    fun `a truncated file is refused rather than partly read`() = runTest {
        cache().write(snapshot())
        File(root, "cache.json").writeText(File(root, "cache.json").readText().dropLast(5))

        assertNull(cache().read())
    }

    @Test
    fun `an unknown schema version is refused`() = runTest {
        cache().write(snapshot())
        val file = File(root, "cache.json")
        file.writeText(file.readText().replace("\"schema_version\":1", "\"schema_version\":2"))

        assertNull(cache().read())
    }

    @Test
    fun `a row that is no longer published is refused`() = runTest {
        cache().write(snapshot())
        val file = File(root, "cache.json")
        file.writeText(file.readText().replace("\"is_active\":true", "\"is_active\":false"))

        assertNull(cache().read())
    }

    @Test
    fun `a row edited into another currency is refused`() = runTest {
        cache().write(snapshot())
        val file = File(root, "cache.json")
        file.writeText(file.readText().replace("\"currency\":\"BDT\"", "\"currency\":\"USD\""))

        assertNull(cache().read())
    }

    @Test
    fun `a row without its cache timestamp is refused`() = runTest {
        cache().write(snapshot())
        val file = File(root, "cache.json")
        file.writeText(file.readText().replace(",\"cache_fetched_at_epoch_millis\":1700000000000", ""))

        assertNull(cache().read())
    }

    @Test
    fun `two rows for one metal are refused`() = runTest {
        cache().write(snapshot())
        val file = File(root, "cache.json")
        val rows = file.readText().substringAfter("\"prices\":[").substringBeforeLast("]")
        val gold = rows.substringBefore("},") + "}"
        // Duplicate the gold row verbatim: a file that could mean two prices for one metal is not a cache.
        file.writeText("{\"schema_version\":1,\"prices\":[$gold,$gold]}")

        assertNull(cache().read())
    }

    @Test
    fun `no temporary file is left behind`() = runTest {
        cache().write(snapshot())

        assertTrue(File(root, "cache.json").isFile)
        assertFalse(File(root, "cache.json.tmp").exists())
    }
}
