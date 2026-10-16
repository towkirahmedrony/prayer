package io.github.towkirahmedrony.salah.feature.zakat

import io.github.towkirahmedrony.salah.core.model.ZakatJewelleryTreatment
import io.github.towkirahmedrony.salah.core.model.ZakatLineField
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
    ): ZakatUiState.Ready = factory.create(form = form, locale = locale, today = date)

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
        // 7.5 tola is 87.4785 g, which at 12,000 is 1,049,742 — just under the 1,049,760 nisab, because the
        // published constant and the tola arithmetic differ in the fifth place. docs/ZAKAT_METHODOLOGY.md §2.1.
        val tola = state(
            formWith(
                ZakatFormField.GOLD_WEIGHT to "7.5",
                ZakatFormField.GOLD_PRICE to "12000",
            ).copy(unit = ZakatMetalUnit.TOLA),
        )
        assertEquals("1049742.00", tola.result.totalAssets)
        assertNull(tola.result.due)
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
    }
}
