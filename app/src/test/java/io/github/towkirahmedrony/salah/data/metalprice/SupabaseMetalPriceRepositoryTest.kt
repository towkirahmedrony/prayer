package io.github.towkirahmedrony.salah.data.metalprice

import io.github.towkirahmedrony.salah.core.domain.dataset.remote.NetworkFailureKind
import io.github.towkirahmedrony.salah.core.domain.dataset.remote.RemoteFetch
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceFailure
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceRemoteConfig
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPurity
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalType
import io.github.towkirahmedrony.salah.core.domain.metalprice.ReferencePriceResult
import io.github.towkirahmedrony.salah.core.domain.metalprice.ReferencePrices
import io.github.towkirahmedrony.salah.core.domain.metalprice.RemoteMetalPriceSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Which published record the app uses, and what it does when it cannot use one.
 *
 * The source is faked, so no test here reaches a network and the prices are labelled fixtures. What is being
 * asserted is the *selection*: that exactly one approved rate is used, that two are a reported conflict rather
 * than an arbitrary pick, that an unpublished record is never a price, that one metal's failure never stops the
 * other metal's rate, and that nothing anywhere invents a fallback price.
 */
class SupabaseMetalPriceRepositoryTest {

    private val config = MetalPriceRemoteConfig(baseUrl = "https://example.invalid", anonKey = "publishable-key")

    private fun repository(
        response: RemoteFetch<String>,
        configured: Boolean = true,
    ): Pair<SupabaseMetalPriceRepository, FakeRemoteMetalPriceSource> {
        val source = FakeRemoteMetalPriceSource(response)
        val effectiveConfig = if (configured) config else MetalPriceRemoteConfig()
        return SupabaseMetalPriceRepository(source, effectiveConfig) to source
    }

    private fun record(
        metal: String,
        price: String = "19550.07",
        purity: String = "K22",
        currency: String = "BDT",
        active: String = "true",
        status: String = "VERIFIED",
    ): String = "{\"metal_type\":\"$metal\",\"purity\":\"$purity\",\"price_per_gram\":$price," +
        "\"currency\":\"$currency\",\"is_active\":$active,\"verification_status\":\"$status\"}"

    @Test
    fun `a published gold and silver rate are both selected exactly`() = runTest {
        val (repository, _) = repository(
            RemoteFetch.Success("[${record("GOLD", price = "19550.07")},${record("SILVER", price = "150.25")}]"),
        )

        val prices = repository.publishedReferencePrices()

        val gold = prices.gold as ReferencePriceResult.Available
        val silver = prices.silver as ReferencePriceResult.Available
        assertEquals(0, BigDecimal("19550.07").compareTo(gold.price.pricePerGram))
        assertEquals(0, BigDecimal("150.25").compareTo(silver.price.pricePerGram))
        assertEquals("150.25", silver.price.pricePerGram.toPlainString())
        // The exact digits, not their nearest binary approximation.
        assertNotEquals(BigDecimal("19550.07"), BigDecimal(19550.07))
    }

    @Test
    fun `the K22 rate is the one selected when other purities are published too`() = runTest {
        val (repository, _) = repository(
            RemoteFetch.Success(
                "[" +
                    record("GOLD", price = "20000", purity = "K21") + "," +
                    record("GOLD", price = "18000", purity = "K18") + "," +
                    record("GOLD", price = "19550.07", purity = "K22") + "," +
                    record("GOLD", price = "17000", purity = "TRADITIONAL") +
                    "]",
            ),
        )

        val gold = repository.publishedReferencePrices().gold as ReferencePriceResult.Available

        assertEquals(MetalPurity.K22, gold.price.purity)
        assertEquals("19550.07", gold.price.pricePerGram.toPlainString())
    }

    @Test
    fun `only a non-K22 purity being published is no published rate, not broken data`() = runTest {
        // A K21 gold row is a perfectly well-formed price — it is just not the K22 basis this app selects from,
        // exactly as an inactive or unverified K22 row is a well-formed price that is not published. Both leave
        // the metal with no published reference rate rather than reporting the publication as unusable, which is
        // the state a user is told about and the reason the two are kept apart.
        val (repository, _) = repository(
            RemoteFetch.Success("[${record("GOLD", price = "20000", purity = "K21")}]"),
        )

        assertEquals(ReferencePriceResult.NotPublished, repository.publishedReferencePrices().gold)
    }

    @Test
    fun `two approved rates for the same metal are a conflict, not a choice`() = runTest {
        val (repository, _) = repository(
            RemoteFetch.Success(
                "[" +
                    record("GOLD", price = "19550.07") + "," +
                    record("GOLD", price = "21000") + "," +
                    record("SILVER", price = "150.25") +
                    "]",
            ),
        )

        val prices = repository.publishedReferencePrices()

        val gold = prices.gold as ReferencePriceResult.Conflicting
        assertEquals(MetalType.GOLD, gold.metal)
        // The conflict is confined to the metal it belongs to.
        assertTrue(prices.silver is ReferencePriceResult.Available)
    }

    @Test
    fun `an unpublished, unverified or foreign-currency record is never the rate selected`() = runTest {
        val (repository, _) = repository(
            RemoteFetch.Success(
                "[" +
                    record("GOLD", active = "false") + "," +
                    record("GOLD", status = "PENDING") + "," +
                    record("GOLD", status = "REJECTED") + "," +
                    record("GOLD", currency = "USD") +
                    "]",
            ),
        )

        assertEquals(ReferencePriceResult.NotPublished, repository.publishedReferencePrices().gold)
    }

    @Test
    fun `a record that is present but unusable is reported rather than treated as absent`() = runTest {
        val (repository, _) = repository(
            RemoteFetch.Success("[${record("SILVER", price = "0")}]"),
        )

        val prices = repository.publishedReferencePrices()

        assertEquals(ReferencePriceResult.Failed(MetalPriceFailure.INVALID_DATA), prices.silver)
        // Nothing was published for gold, which is a different statement from "gold's record was unusable".
        assertEquals(ReferencePriceResult.NotPublished, prices.gold)
    }

    @Test
    fun `one metal failing never stops the other metal's rate being used`() = runTest {
        val (repository, _) = repository(
            RemoteFetch.Success(
                "[" +
                    record("GOLD", price = "19550.07") + "," +
                    record("SILVER", price = "-5") +
                    "]",
            ),
        )

        val prices = repository.publishedReferencePrices()

        assertTrue(prices.gold is ReferencePriceResult.Available)
        assertEquals(ReferencePriceResult.Failed(MetalPriceFailure.INVALID_DATA), prices.silver)
    }

    @Test
    fun `an empty publication is not published, not a failure`() = runTest {
        val (repository, _) = repository(RemoteFetch.Success("[]"))

        assertEquals(ReferencePrices.notPublished(), repository.publishedReferencePrices())
    }

    @Test
    fun `a missing table is reported as a deployment dependency`() = runTest {
        val (repository, _) = repository(RemoteFetch.NotFound)

        assertEquals(
            ReferencePrices.failed(MetalPriceFailure.TABLE_UNAVAILABLE),
            repository.publishedReferencePrices(),
        )
    }

    @Test
    fun `a refused read is reported as access denied rather than worked around`() = runTest {
        listOf(401, 403).forEach { status ->
            val (repository, _) = repository(RemoteFetch.HttpError(status))
            assertEquals(
                ReferencePrices.failed(MetalPriceFailure.ACCESS_DENIED),
                repository.publishedReferencePrices(),
            )
        }
    }

    @Test
    fun `a server error and a network failure are reported by kind`() = runTest {
        val (serverError, _) = repository(RemoteFetch.HttpError(500))
        assertEquals(
            ReferencePrices.failed(MetalPriceFailure.SERVER),
            serverError.publishedReferencePrices(),
        )

        val (timeout, _) = repository(RemoteFetch.NetworkFailure(NetworkFailureKind.TIMEOUT))
        assertEquals(
            ReferencePrices.failed(MetalPriceFailure.TIMEOUT),
            timeout.publishedReferencePrices(),
        )

        val (connection, _) = repository(RemoteFetch.NetworkFailure(NetworkFailureKind.CONNECTION))
        assertEquals(
            ReferencePrices.failed(MetalPriceFailure.CONNECTION),
            connection.publishedReferencePrices(),
        )
    }

    @Test
    fun `a malformed response is reported rather than thrown`() = runTest {
        val (repository, _) = repository(RemoteFetch.Success("not json"))

        assertEquals(
            ReferencePrices.failed(MetalPriceFailure.MALFORMED),
            repository.publishedReferencePrices(),
        )
    }

    @Test
    fun `an oversized response is reported rather than read`() = runTest {
        val (repository, _) = repository(RemoteFetch.TooLarge(1024))

        assertEquals(
            ReferencePrices.failed(MetalPriceFailure.RESPONSE_TOO_LARGE),
            repository.publishedReferencePrices(),
        )
    }

    @Test
    fun `an unconfigured build does nothing at all`() = runTest {
        val (repository, source) = repository(RemoteFetch.Success("[]"), configured = false)

        assertEquals(
            ReferencePrices.failed(MetalPriceFailure.NOT_CONFIGURED),
            repository.publishedReferencePrices(),
        )
        // The important part: no request was attempted, so an unconfigured build cannot reach anything.
        assertEquals(0, source.calls)
    }
}

/** A source that answers from memory and counts how many times it was asked. */
private class FakeRemoteMetalPriceSource(private val response: RemoteFetch<String>) : RemoteMetalPriceSource {

    var calls: Int = 0
        private set

    override suspend fun fetchPublishedRows(): RemoteFetch<String> {
        calls += 1
        return response
    }
}
