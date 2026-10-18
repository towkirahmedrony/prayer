package io.github.towkirahmedrony.salah.feature.zakat

import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceFailure
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceFreshness
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPurity
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalType
import io.github.towkirahmedrony.salah.core.domain.metalprice.PublishedMetalPrice
import io.github.towkirahmedrony.salah.core.domain.metalprice.ReferencePriceResult
import io.github.towkirahmedrony.salah.core.domain.metalprice.ReferencePrices
import io.github.towkirahmedrony.salah.core.model.ZakatJewelleryTreatment
import io.github.towkirahmedrony.salah.core.model.ZakatLineField
import io.github.towkirahmedrony.salah.core.model.ZakatMetal
import io.github.towkirahmedrony.salah.core.model.ZakatMetalUnit
import io.github.towkirahmedrony.salah.core.model.ZakatNisabMetal
import io.github.towkirahmedrony.salah.core.model.ZakatNisabWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

/**
 * The screen's state, produced from a form and a locale.
 *
 * ## What these tests are and are not
 *
 * `ZakatCalculatorTest` checks the arithmetic against hand-computed values. This checks the layer between the
 * keyboard and the engine, which is where three things can go wrong that no arithmetic test would catch: text
 * that is not a number being read as zero, a row that needs a value being reported as complete, and numbers being
 * formatted in the wrong script.
 *
 * ## Why nothing here names a resource id
 *
 * No test in this file references `R.string.…`. The state carries resource ids for labels, and asserting them
 * would only restate the mapping — but it would also make these tests depend on the `R` class being on the unit
 * test classpath, which nothing else in this project relies on. So the assertions are about **behaviour**: which
 * rows exist, in what order, with what amounts, in what script, and with which option selected. The words
 * themselves are covered by `LocalizationResourcesTest`, which reads the resource files directly.
 *
 * ## Conventions
 *
 * The price is 12,000 per gram and the silver price 150 per gram, so every amount below is checkable by hand.
 * The valuation date is fixed, because the factory takes it as a parameter precisely so a test can pin it.
 */
class ZakatUiStateFactoryTest {

    private val factory = ZakatUiStateFactory()
    private val today: LocalDate = LocalDate.of(2026, 10, 16)
    private val english: Locale = Locale.ENGLISH
    private val bengali: Locale = Locale.forLanguageTag("bn")

    private fun state(
        form: ZakatForm,
        locale: Locale = english,
        date: LocalDate = today,
        referencePrices: ReferencePrices = ReferencePrices.notPublished(),
        manualPriceFields: Set<ZakatFormField> = emptySet(),
        refreshingReferencePrices: Boolean = false,
    ): ZakatUiState.Ready = factory.create(
        form = form,
        locale = locale,
        today = date,
        referencePrices = referencePrices,
        manualPriceFields = manualPriceFields,
        refreshingReferencePrices = refreshingReferencePrices,
    )

    /**
     * A synthetic published record.
     *
     * Every value here is a clearly labelled fixture, never a real Bangladesh market price: the point of these
     * tests is which record is selected and how its fields are read, not what gold costs.
     */
    private fun publishedPrice(metal: MetalType, price: String): PublishedMetalPrice = PublishedMetalPrice(
        metal = metal,
        purity = MetalPurity.K22,
        pricePerGram = BigDecimal(price),
        currency = "BDT",
        source = "Salah test fixture",
        sourceUrl = "https://example.invalid/fixture",
        effectiveAt = "2026-09-30T00:00:00Z",
        providerLastUpdate = "2026-09-30T05:00:00Z",
        fetchedAt = "2026-09-30T06:00:00Z",
        updatedAt = "2026-09-30T06:05:00Z",
    )

    /** A published-price reading with a rate for whichever metals are named. */
    private fun publishedPrices(
        gold: String? = null,
        silver: String? = null,
        freshness: MetalPriceFreshness = MetalPriceFreshness.CURRENT,
        cachedAt: Long? = null,
    ): ReferencePrices = ReferencePrices(
        gold = gold
            ?.let { price ->
                ReferencePriceResult.Available(
                    publishedPrice(MetalType.GOLD, price),
                    freshness = freshness,
                    cachedAtEpochMillis = cachedAt,
                )
            }
            ?: ReferencePriceResult.NotPublished,
        silver = silver
            ?.let { price ->
                ReferencePriceResult.Available(
                    publishedPrice(MetalType.SILVER, price),
                    freshness = freshness,
                    cachedAtEpochMillis = cachedAt,
                )
            }
            ?: ReferencePriceResult.NotPublished,
    )

    private fun formWith(vararg entries: Pair<ZakatFormField, String>): ZakatForm =
        entries.fold(ZakatForm()) { form, (field, value) -> form.withValue(field, value) }

    private fun goldAtTwelveThousand(): ZakatForm = formWith(
        ZakatFormField.GOLD_WEIGHT to "100",
        ZakatFormField.GOLD_PRICE to "12000",
    )

    // --- Rows -----------------------------------------------------------------------------------

    @Test
    fun `every input row is offered, in the documented order`() {
        val ready = state(ZakatForm())

        // The form has more rows than the calculation has lines: a metal's weight and its price are two inputs
        // but one result row, which is why the two sets are modelled separately.
        assertEquals(
            listOf(
                ZakatFormField.CASH,
                ZakatFormField.FOREIGN_CURRENCY,
                ZakatFormField.FOREIGN_RATE,
                ZakatFormField.GOLD_WEIGHT,
                ZakatFormField.GOLD_PRICE,
                ZakatFormField.SILVER_WEIGHT,
                ZakatFormField.SILVER_PRICE,
                ZakatFormField.INVENTORY,
                ZakatFormField.COLLECTIBLE_RECEIVABLES,
                ZakatFormField.DOUBTFUL_RECEIVABLES,
            ),
            ready.assetFields.map { field -> field.field },
        )
        assertEquals(
            listOf(ZakatFormField.DEDUCTIBLE_LIABILITIES, ZakatFormField.LONG_TERM_LIABILITIES),
            ready.liabilityFields.map { field -> field.field },
        )
    }

    @Test
    fun `a row the user has not touched is empty and not an error`() {
        val ready = state(ZakatForm())

        ready.assetFields.forEach { field ->
            assertEquals("", field.value)
            assertFalse("a blank field must not be reported as invalid", field.isInvalid)
        }
        assertFalse(ready.canClear)
    }

    // --- Text that is not a number --------------------------------------------------------------

    @Test
    fun `text that is not a number is reported against its own field`() {
        val ready = state(formWith(ZakatFormField.CASH to "twelve thousand"))

        val cash = ready.assetFields.single { field -> field.field == ZakatFormField.CASH }
        assertTrue("the field must say it is wrong", cash.isInvalid)
        // Every other field is untouched, so the error is attributed rather than general.
        ready.assetFields
            .filter { field -> field.field != ZakatFormField.CASH }
            .forEach { field -> assertFalse(field.isInvalid) }
    }

    @Test
    fun `an unreadable amount is not silently treated as zero`() {
        // The failure this guards against is the worst kind: a total that looks complete and is not. Nothing was
        // calculated, so no total is shown at all.
        val ready = state(formWith(ZakatFormField.CASH to "12,000"))

        assertNull(ready.result.totalAssets)
        assertNull(ready.result.netWealth)
        assertNull(ready.result.due)
        assertTrue(ready.canClear)
    }

    @Test
    fun `a negative amount is rejected rather than reduced from the total`() {
        val ready = state(formWith(ZakatFormField.CASH to "-5000"))

        assertTrue(ready.assetFields.single { field -> field.field == ZakatFormField.CASH }.isInvalid)
        assertNull(ready.result.totalAssets)
    }

    @Test
    fun `a zero is a value, and is included`() {
        val ready = state(formWith(ZakatFormField.CASH to "0"))

        val cash = ready.assetFields.single { field -> field.field == ZakatFormField.CASH }
        assertFalse(cash.isInvalid)
        assertEquals("0.00", ready.result.totalAssets)
        // Below the nisab, so nothing is due — but the row was counted, which is the difference being asserted.
        assertNull(ready.result.due)
    }

    // --- Localised numbers ----------------------------------------------------------------------

    @Test
    fun `amounts are written in the interface language's numerals`() {
        val englishState = state(goldAtTwelveThousand(), locale = english)
        val bengaliState = state(goldAtTwelveThousand(), locale = bengali)

        // 100 g × 12,000 = 1,200,000, against a gold nisab of 87.48 g × 12,000 = 1,049,760.
        assertEquals("1200000.00", englishState.result.totalAssets)
        assertEquals("১২০০০০০.০০", bengaliState.result.totalAssets)
        // The same calculation, the same money, two scripts.
        assertTrue(bengaliState.result.totalAssets!!.any { character -> character in '০'..'৯' })
        assertFalse(bengaliState.result.totalAssets!!.any { character -> character in '0'..'9' })
    }

    @Test
    fun `the payable amount is written to two places in either script`() {
        // 1,200,000 × 0.025 = 30,000.
        assertEquals("30000.00", state(goldAtTwelveThousand()).result.due)
        assertEquals("৩০০০০.০০", state(goldAtTwelveThousand(), locale = bengali).result.due)
    }

    @Test
    fun `the rate is shown as a percentage, localised`() {
        assertEquals("2.5%", state(ZakatForm()).result.rateLabel)
        assertEquals("২.৫%", state(ZakatForm(), locale = bengali).result.rateLabel)
    }

    // --- The breakdown --------------------------------------------------------------------------

    @Test
    fun `each valued category appears as a row with its amount`() {
        val ready = state(
            formWith(
                ZakatFormField.CASH to "500000",
                ZakatFormField.GOLD_WEIGHT to "100",
                ZakatFormField.GOLD_PRICE to "12000",
            ),
        )

        val gold = ready.result.rows.single { row -> row.field == ZakatLineField.GOLD }
        assertEquals("1200000.00", gold.amount)
        assertFalse(gold.isDeduction)
        // The price row is an input, not a result line, so two valued categories produce two rows and no more.
        assertEquals(2, ready.result.rows.size)
        // Both rows were counted, so both carry the same treatment. Identity between them is the assertion —
        // the state carries a resource id rather than the domain enum, and naming that id here would restate a
        // mapping this file deliberately does not depend on.
        assertEquals(1, ready.result.rows.map { row -> row.treatmentRes }.distinct().size)
    }

    @Test
    fun `an excluded row still shows the amount the ruling removed`() {
        // The majority ruling on personal jewellery: the gold is out of the total, and the row says how much it
        // was worth, so the user can see what their choice cost them. The cash beside it shows the contrast —
        // one row counts, one does not, and the difference is visible in the treatment each row carries.
        val ready = state(
            formWith(
                ZakatFormField.CASH to "500000",
                ZakatFormField.GOLD_WEIGHT to "100",
                ZakatFormField.GOLD_PRICE to "12000",
            ).copy(
                jewelleryTreatment = ZakatJewelleryTreatment.PERSONAL_USE_EXEMPT,
            ),
        )

        val gold = ready.result.rows.single { row -> row.field == ZakatLineField.GOLD }
        val cash = ready.result.rows.single { row -> row.field == ZakatLineField.CASH }
        assertEquals("1200000.00", gold.amount)
        assertNotEquals(
            "an excluded row must not carry the treatment of a counted one",
            cash.treatmentRes,
            gold.treatmentRes,
        )
        // The total holds the cash and none of the gold, which is what "excluded" means here.
        assertEquals("500000.00", ready.result.totalAssets)
        assertNull(ready.result.due)
    }

    @Test
    fun `a liability row is marked as a deduction`() {
        val ready = state(
            formWith(
                ZakatFormField.CASH to "1200000",
                ZakatFormField.DEDUCTIBLE_LIABILITIES to "200000",
            ),
        )

        val liability = ready.result.rows.single { row -> row.field == ZakatLineField.DEDUCTIBLE_LIABILITIES }
        assertTrue(liability.isDeduction)
        // 1,200,000 − 200,000 = 1,000,000, which is below the gold nisab of 1,049,760.
        assertEquals("1000000.00", ready.result.netWealth)
        assertNull(ready.result.due)
    }

    // --- Incomplete inputs ----------------------------------------------------------------------

    @Test
    fun `a weight with no price is named as still needing a value`() {
        val ready = state(formWith(ZakatFormField.GOLD_WEIGHT to "100"))

        assertEquals(1, ready.result.incompleteLabels.size)
        assertNull(ready.result.due)
        // The row itself is not an input error: the weight was typed correctly, and what is missing is a price.
        assertFalse(ready.assetFields.single { field -> field.field == ZakatFormField.GOLD_WEIGHT }.isInvalid)
    }

    @Test
    fun `a complete calculation names nothing as incomplete`() {
        val ready = state(goldAtTwelveThousand())

        assertTrue(ready.result.incompleteLabels.isEmpty())
        assertNotNull(ready.result.due)
    }

    @Test
    fun `nothing entered leaves the amounts absent rather than zero`() {
        val ready = state(ZakatForm())

        assertNull(ready.result.totalAssets)
        assertNull(ready.result.netWealth)
        assertNull(ready.result.nisabAmount)
        assertNull(ready.result.due)
        assertTrue(ready.result.rows.isEmpty())
    }

    // --- Methodology visibility -----------------------------------------------------------------

    @Test
    fun `both published nisab weight pairs are offered and exactly one is in force`() {
        val ready = state(ZakatForm())

        assertEquals(ZakatNisabWeight.entries.toList(), ready.nisabWeightChoices.map { choice -> choice.value })
        assertEquals(1, ready.nisabWeightChoices.count { choice -> choice.selected })
        assertEquals(ZakatNisabWeight.INTERNATIONAL, ready.nisabWeightChoices.single { it.selected }.value)
    }

    @Test
    fun `every methodology group marks exactly one option selected`() {
        val ready = state(ZakatForm())

        listOf(
            ready.currencyChoices.map { it.selected },
            ready.unitChoices.map { it.selected },
            ready.nisabWeightChoices.map { it.selected },
            ready.nisabMetalChoices.map { it.selected },
            ready.jewelleryChoices.map { it.selected },
            ready.debtChoices.map { it.selected },
            ready.inventoryBasisChoices.map { it.selected },
        ).forEach { selected ->
            assertEquals("a group has no option or more than one in force", 1, selected.count { it })
        }
    }

    @Test
    fun `the user's metal choice stays selected when the all-one-metal rule overrides it`() {
        // The rule is applied by the engine and reported separately, so the control does not move under the
        // user's finger while the screen explains why it was overridden.
        val ready = state(
            formWith(ZakatFormField.GOLD_WEIGHT to "100", ZakatFormField.GOLD_PRICE to "12000")
                .copy(nisabMetal = ZakatNisabMetal.SILVER),
        )

        assertEquals(ZakatNisabMetal.SILVER, ready.nisabMetalChoices.single { it.selected }.value)
        assertTrue(ready.nisabMetalOverridden)
        assertNotNull("the metal that forced the change must be named", ready.forcedNisabMetalRes)
    }

    @Test
    fun `no override is reported when the user's choice stands`() {
        val ready = state(goldAtTwelveThousand())

        assertEquals(ZakatNisabMetal.GOLD, ready.nisabMetalChoices.single { it.selected }.value)
        assertFalse(ready.nisabMetalOverridden)
        assertNull(ready.forcedNisabMetalRes)
    }

    @Test
    fun `a currency option's label is its code, because a code is not a translated word`() {
        val ready = state(ZakatForm())

        assertTrue(ready.currencyChoices.all { choice -> choice.labelIsValue })
        assertEquals("BDT", ready.currencyChoices.single { choice -> choice.selected }.value)
    }

    @Test
    fun `the weight unit is one choice for both metals`() {
        val ready = state(ZakatForm().copy(unit = ZakatMetalUnit.TOLA))

        assertEquals(ZakatMetalUnit.TOLA, ready.unitChoices.single { it.selected }.value)
        // 7.5 tola is 87.4785 g, and the international threshold in a traditional unit is 7.5 × 11.6638, so the
        // holding is exactly the nisab rather than the 0.0015 g short of the gram constant it would be if the
        // converted weight were compared with 87.48. 1,049,742 × 0.025 = 26,243.55.
        // docs/ZAKAT_METHODOLOGY.md §2.1 and §8.2.
        val tola = state(
            formWith(
                ZakatFormField.GOLD_WEIGHT to "7.5",
                ZakatFormField.GOLD_PRICE to "12000",
            ).copy(unit = ZakatMetalUnit.TOLA),
        )
        assertEquals("1049742.00", tola.result.totalAssets)
        assertEquals("1049742.00", tola.result.nisabAmount)
        assertEquals("26243.55", tola.result.due)
    }

    @Test
    fun `the price note is shown whenever a threshold exists`() {
        // The nisab is a weight times a price, so a result that has a threshold always rests on a price the user
        // supplied. The note must never be hidden behind a condition that could miss that.
        assertTrue(state(goldAtTwelveThousand()).result.showsPriceNote)
        assertNull(state(ZakatForm()).result.totalAssets)
    }

    // --- Hawl -----------------------------------------------------------------------------------

    @Test
    fun `a readable seed date produces an estimated anniversary, localised`() {
        val ready = state(
            formWith(ZakatFormField.CASH to "1200000").copy(hawlSeedDateText = "2026-01-01"),
            locale = bengali,
        )

        assertFalse(ready.hawlDateInvalid)
        // The date is formatted by the calendar rule, so the year is in Bengali numerals.
        assertNotNull(ready.result.hawlEstimateDate)
        assertTrue(ready.result.hawlEstimateDate!!.contains("২০২৬"))
        // 2026-01-01 + 354 days = 2026-12-21, and the day count is localised too.
        assertEquals("৩৫৪", ready.result.hawlEstimateDays)
    }

    @Test
    fun `an unreadable seed date is reported and produces no estimate`() {
        val ready = state(ZakatForm().copy(hawlSeedDateText = "next Ramadan"))

        assertTrue(ready.hawlDateInvalid)
        assertNull(ready.result.hawlEstimateDate)
        assertNull(ready.result.hawlEstimateDays)
    }

    @Test
    fun `no seed date means no estimate and no error`() {
        val ready = state(ZakatForm())

        assertFalse(ready.hawlDateInvalid)
        assertNull(ready.result.hawlEstimateDate)
    }

    // --- Clearing -------------------------------------------------------------------------------

    @Test
    fun `clearing is offered once anything has been entered`() {
        assertFalse(state(ZakatForm()).canClear)
        assertTrue(state(formWith(ZakatFormField.CASH to "1")).canClear)
        // A half-typed field counts: the user would still lose it.
        assertTrue(state(ZakatForm().copy(hawlSeedDateText = "2026")).canClear)
        // And a partially typed date is not an error — the field has something in it, on its way to a date.
        assertFalse(state(ZakatForm().copy(hawlSeedDateText = "2026")).hawlDateInvalid)
    }

    // --- What a missing value is called, and what the result is called -------------------------

    @Test
    fun `a missing price is named as a price rather than as the metal's weight`() {
        // The weight was typed and the price was not, so naming the weight sends the user to a field that is
        // already correct. The assertions compare the labels the state itself carries, so no test here has to
        // know a resource id.
        val gold = state(formWith(ZakatFormField.GOLD_WEIGHT to "100"))
        val goldWeightLabel = gold.assetFields.single { it.field == ZakatFormField.GOLD_WEIGHT }.labelRes
        val goldPriceLabel = gold.assetFields.single { it.field == ZakatFormField.GOLD_PRICE }.labelRes
        assertNotEquals(goldWeightLabel, goldPriceLabel)
        assertEquals(listOf(goldPriceLabel), gold.result.incompleteLabels)

        val silver = state(
            formWith(ZakatFormField.GOLD_PRICE to "12000", ZakatFormField.SILVER_WEIGHT to "100"),
        )
        val silverWeightLabel = silver.assetFields.single { it.field == ZakatFormField.SILVER_WEIGHT }.labelRes
        val silverPriceLabel = silver.assetFields.single { it.field == ZakatFormField.SILVER_PRICE }.labelRes
        assertNotEquals(silverWeightLabel, silverPriceLabel)
        assertEquals(listOf(silverPriceLabel), silver.result.incompleteLabels)

        val foreign = state(
            formWith(
                ZakatFormField.CASH to "1000",
                ZakatFormField.GOLD_PRICE to "12000",
                ZakatFormField.FOREIGN_CURRENCY to "500",
            ),
        )
        val foreignAmountLabel = foreign.assetFields.single { it.field == ZakatFormField.FOREIGN_CURRENCY }.labelRes
        val foreignRateLabel = foreign.assetFields.single { it.field == ZakatFormField.FOREIGN_RATE }.labelRes
        assertNotEquals(foreignAmountLabel, foreignRateLabel)
        assertEquals(listOf(foreignRateLabel), foreign.result.incompleteLabels)
    }

    @Test
    fun `a calculation, an estimate and a below-nisab result are told apart`() {
        val calculated = state(goldAtTwelveThousand()).result
        val estimate = state(formWith(ZakatFormField.GOLD_WEIGHT to "100")).result
        val belowNisab = state(
            formWith(ZakatFormField.CASH to "1000", ZakatFormField.GOLD_PRICE to "12000"),
        ).result

        assertEquals(3, listOf(calculated.statusRes, estimate.statusRes, belowNisab.statusRes).distinct().size)
        // A below-nisab result is a completed calculation whose answer is "no payment is due", so it must not
        // borrow the word used for a completed calculation that produced an amount.
        assertNotNull(calculated.due)
        assertNull(estimate.due)
        assertNull(belowNisab.due)
    }

    @Test
    fun `the currency the amounts are in reaches the result view`() {
        assertEquals("BDT", state(ZakatForm()).result.currency)
        assertEquals("USD", state(ZakatForm().copy(currency = "USD")).result.currency)
    }

    // --- The hawl date, while it is being typed ------------------------------------------------

    @Test
    fun `a partial ISO date is not reported as invalid while it is still being typed`() {
        listOf("2026", "2026-1", "2026-01-", "2026-01-0").forEach { partial ->
            val ready = state(ZakatForm().copy(hawlSeedDateText = partial))
            assertFalse("$partial must not be reported as invalid", ready.hawlDateInvalid)
            assertTrue("$partial must still count as entered", ready.canClear)
        }
    }

    @Test
    fun `a completed but impossible date, and an unrelated phrase, are reported as invalid`() {
        listOf("2026-99-99", "2026-13-01", "next Ramadan").forEach { text ->
            assertTrue(
                "$text must be reported as invalid",
                state(ZakatForm().copy(hawlSeedDateText = text)).hawlDateInvalid,
            )
        }
    }

    @Test
    fun `Bengali digits in the seed date are read as the date they denote`() {
        val ready = state(
            formWith(ZakatFormField.CASH to "1200000").copy(hawlSeedDateText = "২০২৬-০১-০১"),
        )

        assertFalse(ready.hawlDateInvalid)
        assertNotNull(ready.result.hawlEstimateDate)
    }

    // --- Reference prices at the state boundary ------------------------------------------------

    @Test
    fun `an available published rate fills a price field the user has not touched`() {
        // 87.5 g × 19,550 = 1,710,625, above the 87.48 g gold nisab of 1,710,234.
        val ready = state(
            form = formWith(ZakatFormField.GOLD_WEIGHT to "87.5"),
            referencePrices = publishedPrices(gold = "19550.00"),
        )

        val priceField = ready.assetFields.single { it.field == ZakatFormField.GOLD_PRICE }
        // The field carries the record's own digits, unrounded, because that is the text the calculator parses.
        assertEquals("19550.00", priceField.value)
        assertFalse(priceField.isInvalid)
        assertEquals("1710625.00", ready.result.totalAssets)

        val gold = ready.referencePrices.single { it.metal == ZakatMetal.GOLD }
        assertEquals(ZakatReferencePriceStatus.AVAILABLE, gold.status)
        assertEquals("19550", gold.pricePerGram)
        assertEquals("K22", gold.purityLabel)
        assertEquals("BDT", gold.currencyCode)
        assertEquals("Salah test fixture", gold.sourceLabel)
        assertNotNull(gold.effectiveLabel)
        assertNotNull(gold.providerUpdatedLabel)
        assertNotNull(gold.fetchedLabel)
        assertNotNull(gold.updatedLabel)
        assertNull(gold.messageRes)
        assertFalse(gold.manuallyEdited)
        assertFalse(gold.canRestoreReference)
        // Gold first, then silver, whatever the availability.
        assertEquals(listOf(ZakatMetal.GOLD, ZakatMetal.SILVER), ready.referencePrices.map { it.metal })
    }

    @Test
    fun `a metal with no published rate leaves its price field empty and names the reason`() {
        val ready = state(
            form = formWith(ZakatFormField.GOLD_WEIGHT to "87.5"),
            referencePrices = publishedPrices(gold = "19550.00"),
        )

        assertEquals("", ready.assetFields.single { it.field == ZakatFormField.SILVER_PRICE }.value)
        val silver = ready.referencePrices.single { it.metal == ZakatMetal.SILVER }
        assertEquals(ZakatReferencePriceStatus.UNAVAILABLE, silver.status)
        assertNull(silver.pricePerGram)
        assertNull(silver.sourceLabel)
        assertNotNull(silver.messageRes)
    }

    @Test
    fun `no published rate at all leaves every price field empty and still accepts a typed one`() {
        val ready = state(formWith(ZakatFormField.GOLD_WEIGHT to "87.5"))

        assertEquals("", ready.assetFields.single { it.field == ZakatFormField.GOLD_PRICE }.value)
        ready.referencePrices.forEach { price ->
            assertEquals(ZakatReferencePriceStatus.UNAVAILABLE, price.status)
            assertNull(price.pricePerGram)
            assertNotNull(price.messageRes)
        }
        // The calculator is unaffected: the weight is still valued as needing a price, exactly as before.
        assertEquals(
            listOf(ready.assetFields.single { it.field == ZakatFormField.GOLD_PRICE }.labelRes),
            ready.result.incompleteLabels,
        )
    }

    @Test
    fun `a price the user typed is kept, and the published rate is offered back instead`() {
        val ready = state(
            form = formWith(
                ZakatFormField.GOLD_WEIGHT to "87.5",
                ZakatFormField.GOLD_PRICE to "12000",
            ),
            referencePrices = publishedPrices(gold = "19550.00"),
            manualPriceFields = setOf(ZakatFormField.GOLD_PRICE),
        )

        assertEquals("12000", ready.assetFields.single { it.field == ZakatFormField.GOLD_PRICE }.value)
        val gold = ready.referencePrices.single { it.metal == ZakatMetal.GOLD }
        assertTrue(gold.manuallyEdited)
        assertTrue(gold.canRestoreReference)
        assertEquals(ZakatFormField.GOLD_PRICE, gold.priceField)
    }

    @Test
    fun `a price field the user has cleared goes back to the published reference rate`() {
        val ready = state(
            form = formWith(ZakatFormField.GOLD_WEIGHT to "87.5", ZakatFormField.GOLD_PRICE to ""),
            referencePrices = publishedPrices(gold = "19550.00"),
        )

        assertEquals("19550.00", ready.assetFields.single { it.field == ZakatFormField.GOLD_PRICE }.value)
        assertFalse(ready.referencePrices.single { it.metal == ZakatMetal.GOLD }.manuallyEdited)
    }

    @Test
    fun `a retrieval in flight is shown as loading rather than as absent`() {
        val ready = state(form = ZakatForm(), referencePrices = ReferencePrices.loading())

        assertFalse(ready.refreshingReferencePrices)
        ready.referencePrices.forEach { price ->
            assertEquals(ZakatReferencePriceStatus.LOADING, price.status)
            assertNull(price.messageRes)
        }
    }

    @Test
    fun `the refresh flag reaches the state`() {
        assertTrue(state(ZakatForm(), refreshingReferencePrices = true).refreshingReferencePrices)
    }

    @Test
    fun `a saved rate is labelled as cached and still fills the price field`() {
        val cachedAt = Instant.parse("2026-10-15T07:30:00Z").toEpochMilli()
        val ready = state(
            form = formWith(ZakatFormField.GOLD_WEIGHT to "87.5"),
            referencePrices = publishedPrices(
                gold = "19550.00",
                freshness = MetalPriceFreshness.CACHED,
                cachedAt = cachedAt,
            ),
        )

        // Still an input like any other: a saved rate is usable, not a placeholder.
        assertEquals("19550.00", ready.assetFields.single { it.field == ZakatFormField.GOLD_PRICE }.value)
        assertEquals("1710625.00", ready.result.totalAssets)

        val gold = ready.referencePrices.single { it.metal == ZakatMetal.GOLD }
        assertEquals(ZakatReferencePriceStatus.CACHED, gold.status)
        assertNotNull(gold.lastRefreshLabel)
        assertNotNull(gold.messageRes)
    }

    @Test
    fun `a stale saved rate remains usable and is labelled as possibly out of date`() {
        val cachedAt = Instant.parse("2026-09-01T07:30:00Z").toEpochMilli()
        val ready = state(
            form = formWith(ZakatFormField.GOLD_WEIGHT to "87.5"),
            referencePrices = publishedPrices(
                gold = "19550.00",
                freshness = MetalPriceFreshness.STALE,
                cachedAt = cachedAt,
            ),
        )

        // Stale is a label, not a gate: the calculation still uses the published figure and manual entry stays
        // one tap away.
        assertEquals("19550.00", ready.assetFields.single { it.field == ZakatFormField.GOLD_PRICE }.value)
        assertEquals("1710625.00", ready.result.totalAssets)

        val gold = ready.referencePrices.single { it.metal == ZakatMetal.GOLD }
        assertEquals(ZakatReferencePriceStatus.STALE, gold.status)
        assertNotNull(gold.lastRefreshLabel)
        assertNotNull(gold.messageRes)
    }

    @Test
    fun `a failed refresh beside a saved rate still shows the rate, not a failure`() {
        val ready = state(
            form = formWith(ZakatFormField.GOLD_WEIGHT to "87.5"),
            referencePrices = publishedPrices(
                gold = "19550.00",
                freshness = MetalPriceFreshness.CACHED,
                cachedAt = Instant.parse("2026-10-17T07:30:00Z").toEpochMilli(),
            ).copy(refreshFailure = MetalPriceFailure.TIMEOUT),
        )

        assertEquals("19550.00", ready.assetFields.single { it.field == ZakatFormField.GOLD_PRICE }.value)
        assertEquals(
            ZakatReferencePriceStatus.CACHED,
            ready.referencePrices.single { it.metal == ZakatMetal.GOLD }.status,
        )
        assertNotNull(ready.referencePrices.single { it.metal == ZakatMetal.GOLD }.messageRes)
    }
}
