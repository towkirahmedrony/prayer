package io.github.towkirahmedrony.salah.core.domain.metalprice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * The published-price contract, read from synthetic responses.
 *
 * ## Every price here is a fixture
 *
 * None of the numbers below is a Bangladesh market price. They are chosen so that an arithmetic property is
 * checkable — an exact decimal that a `Double` cannot hold, a purity category, a currency — and the source name
 * says so. A test that asserted a real gold price would be asserting the market, not the code.
 *
 * ## What is being guarded
 *
 * Three things can go wrong silently in this reader, and each is asserted directly: a price read through binary
 * floating point (so `19550.07` arrives as something very slightly not `19550.07`), a record that exists being
 * treated as a record that is *approved*, and a missing or nonsensical price being turned into zero.
 */
class MetalPriceParserTest {

    private fun parsed(body: String): MetalPriceReadResult.Parsed {
        val result = MetalPriceParser.parse(body)
        assertTrue("expected a parsed result but was $result", result is MetalPriceReadResult.Parsed)
        return result as MetalPriceReadResult.Parsed
    }

    private fun record(
        metal: String = "GOLD",
        purity: String = "K22",
        price: String = "19550.07",
        currency: String = "BDT",
        active: String = "true",
        status: String = "VERIFIED",
        extra: String = "",
    ): String = "{\"metal_type\":\"$metal\",\"purity\":\"$purity\",\"price_per_gram\":$price," +
        "\"currency\":\"$currency\",\"is_active\":$active,\"verification_status\":\"$status\"" +
        (if (extra.isEmpty()) "" else ",$extra") + "}"

    @Test
    fun `a published gold and silver record are read, with their source and timestamps`() {
        val body = "[" +
            record(extra = "\"source\":\"Example Source\",\"source_url\":\"https://example.invalid\"," +
                "\"effective_at\":\"2026-09-30T00:00:00Z\",\"provider_last_update\":\"2026-09-30T05:00:00Z\"," +
                "\"fetched_at\":\"2026-09-30T06:00:00Z\",\"updated_at\":\"2026-09-30T06:05:00Z\"") + "," +
            record(metal = "SILVER", price = "150.25") +
            "]"

        val result = parsed(body)

        assertEquals(2, result.prices.size)
        assertEquals(0, result.rejections.size)
        val gold = result.prices.single { it.metal == MetalType.GOLD }
        assertEquals(MetalPurity.K22, gold.purity)
        assertEquals("BDT", gold.currency)
        assertEquals("Example Source", gold.source)
        assertEquals("https://example.invalid", gold.sourceUrl)
        assertEquals("2026-09-30T00:00:00Z", gold.effectiveAt)
        assertEquals("2026-09-30T05:00:00Z", gold.providerLastUpdate)
        assertEquals("2026-09-30T06:00:00Z", gold.fetchedAt)
        assertEquals("2026-09-30T06:05:00Z", gold.updatedAt)
        assertEquals(0, BigDecimal("19550.07").compareTo(gold.pricePerGram))
        assertEquals("19550.07", gold.pricePerGram.toPlainString())
    }

    @Test
    fun `the price is the exact decimal the service sent, not its nearest binary approximation`() {
        // The assertion that matters: the decimal 19550.07 and the nearest Double to it are different numbers.
        // Reading the JSON number's text gives the first; reading it as a Double would give the second.
        val price = parsed("[${record(price = "19550.07")}]").prices.single().pricePerGram

        assertEquals(0, BigDecimal("19550.07").compareTo(price))
        assertNotEquals(BigDecimal("19550.07"), BigDecimal(19550.07))
        assertNotEquals("19550.07", BigDecimal(19550.07).toPlainString())
    }

    @Test
    fun `a price sent as a JSON string is read just as exactly`() {
        // PostgREST renders a numeric column either way depending on how the query is shaped.
        val price = parsed("[${record(price = "\"19550.07\"")}]").prices.single().pricePerGram

        assertEquals("19550.07", price.toPlainString())
    }

    @Test
    fun `every purity category the contract names is read`() {
        listOf("K22", "K21", "K18", "TRADITIONAL").forEach { purity ->
            val price = parsed("[${record(purity = purity)}]").prices.single()
            assertEquals(MetalPurity.of(purity), price.purity)
        }
    }

    @Test
    fun `an unexpected metal type is rejected and no price is taken from it`() {
        val result = parsed("[${record(metal = "PLATINUM")}]")

        assertTrue(result.prices.isEmpty())
        assertEquals(1, result.rejections.size)
        assertEquals(MetalPriceRejectionReason.UNKNOWN_METAL_TYPE, result.rejections.single().reason)
    }

    @Test
    fun `an unknown purity is rejected and reported against its metal`() {
        val result = parsed("[${record(purity = "K24")}]")

        assertTrue(result.prices.isEmpty())
        assertEquals(MetalPriceRejectionReason.UNKNOWN_PURITY, result.rejections.single().reason)
        assertEquals(MetalType.GOLD, result.rejections.single().metal)
    }

    @Test
    fun `a record in another currency is rejected`() {
        val result = parsed("[${record(currency = "USD")}]")

        assertTrue(result.prices.isEmpty())
        assertEquals(MetalPriceRejectionReason.NON_BDT_CURRENCY, result.rejections.single().reason)
    }

    @Test
    fun `a missing, zero or negative price is rejected rather than read as zero`() {
        listOf(
            "{\"metal_type\":\"GOLD\",\"purity\":\"K22\",\"currency\":\"BDT\"," +
                "\"is_active\":true,\"verification_status\":\"VERIFIED\"}",
            record(price = "0"),
            record(price = "0.00"),
            record(price = "-1"),
            record(price = "null"),
            record(price = "\"not a number\""),
        ).forEach { body ->
            val result = parsed("[$body]")
            assertTrue("no price must be taken from $body", result.prices.isEmpty())
            assertEquals(
                "expected an invalid-price rejection for $body",
                MetalPriceRejectionReason.INVALID_PRICE,
                result.rejections.single().reason,
            )
        }
    }

    @Test
    fun `a record missing a required field is rejected`() {
        val bodies = listOf(
            "{\"purity\":\"K22\",\"price_per_gram\":100,\"currency\":\"BDT\"," +
                "\"is_active\":true,\"verification_status\":\"VERIFIED\"}",
            "{\"metal_type\":\"GOLD\",\"price_per_gram\":100,\"currency\":\"BDT\"," +
                "\"is_active\":true,\"verification_status\":\"VERIFIED\"}",
            "{\"metal_type\":\"GOLD\",\"purity\":\"K22\",\"price_per_gram\":100," +
                "\"is_active\":true,\"verification_status\":\"VERIFIED\"}",
            "{\"metal_type\":\"GOLD\",\"purity\":\"K22\",\"price_per_gram\":100,\"currency\":\"BDT\"," +
                "\"verification_status\":\"VERIFIED\"}",
            "{\"metal_type\":\"GOLD\",\"purity\":\"K22\",\"price_per_gram\":100,\"currency\":\"BDT\"," +
                "\"is_active\":true}",
        )

        bodies.forEach { body ->
            val result = parsed("[$body]")
            assertTrue(result.prices.isEmpty())
            assertEquals(
                "expected a missing-field rejection for $body",
                MetalPriceRejectionReason.MISSING_FIELD,
                result.rejections.single().reason,
            )
        }
    }

    @Test
    fun `an inactive or unverified record is not a published price`() {
        val result = parsed(
            "[" +
                record(metal = "GOLD", active = "false") + "," +
                record(metal = "SILVER", status = "PENDING") + "," +
                record(metal = "SILVER", status = "REJECTED") +
                "]",
        )

        assertTrue("an unpublished record must not become a price", result.prices.isEmpty())
        // Not a *problem* either: a retired or unreviewed record is a normal state of the publication process.
        assertTrue(result.rejections.isEmpty())
        assertEquals(3, result.ignoredCount)
    }

    @Test
    fun `an unrecognised verification status is reported rather than guessed at`() {
        val result = parsed("[${record(status = "APPROVED")}]")

        assertEquals(
            MetalPriceRejectionReason.UNKNOWN_VERIFICATION_STATUS,
            result.rejections.single().reason,
        )
    }

    @Test
    fun `a manual override flag is read as it arrives and defaults to false when absent`() {
        val withOverride = parsed("[${record(extra = "\"override_active\":true")}]").prices.single()
        val without = parsed("[${record()}]").prices.single()

        assertTrue(withOverride.overrideActive)
        assertEquals(false, without.overrideActive)
    }

    @Test
    fun `a malformed response is reported rather than thrown`() {
        listOf("not json", "{", "[{\"metal_type\":", "\"a string\"", "42").forEach { body ->
            val result = MetalPriceParser.parse(body)
            assertTrue(
                "expected a malformed result for $body but was $result",
                result is MetalPriceReadResult.Malformed,
            )
        }
    }

    @Test
    fun `an empty result is reported as no records rather than as a parse failure`() {
        assertEquals(MetalPriceReadResult.NoRecords, MetalPriceParser.parse("[]"))
    }

    @Test
    fun `a record that is not a JSON object is reported and skipped`() {
        val result = parsed("[\"a string\",${record()}]")

        assertEquals(1, result.prices.size)
        assertEquals(1, result.rejections.size)
        assertEquals(MetalPriceRejectionReason.MISSING_FIELD, result.rejections.single().reason)
    }

    @Test
    fun `a bare object is accepted, because one filtered row may arrive either way`() {
        val result = parsed(record())

        assertEquals(1, result.prices.size)
    }

    @Test
    fun `the columns the query selects are the columns the reader understands`() {
        // One mirror of the database contract: the request and the parser cannot drift apart.
        assertEquals(
            listOf(
                MetalPriceParser.FIELD_METAL_TYPE,
                MetalPriceParser.FIELD_PURITY,
                MetalPriceParser.FIELD_PRICE_PER_GRAM,
                MetalPriceParser.FIELD_CURRENCY,
                MetalPriceParser.FIELD_SOURCE,
                MetalPriceParser.FIELD_SOURCE_URL,
                MetalPriceParser.FIELD_OVERRIDE_ACTIVE,
                MetalPriceParser.FIELD_IS_ACTIVE,
                MetalPriceParser.FIELD_VERIFICATION_STATUS,
                MetalPriceParser.FIELD_EFFECTIVE_AT,
                MetalPriceParser.FIELD_PROVIDER_LAST_UPDATE,
                MetalPriceParser.FIELD_FETCHED_AT,
                MetalPriceParser.FIELD_UPDATED_AT,
            ),
            MetalPriceParser.SELECTED_COLUMNS,
        )
    }
}
