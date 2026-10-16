package io.github.towkirahmedrony.salah.core.domain.zakat

import io.github.towkirahmedrony.salah.core.model.ZakatDebtTreatment
import io.github.towkirahmedrony.salah.core.model.ZakatInputs
import io.github.towkirahmedrony.salah.core.model.ZakatInventoryBasis
import io.github.towkirahmedrony.salah.core.model.ZakatJewelleryTreatment
import io.github.towkirahmedrony.salah.core.model.ZakatLineField
import io.github.towkirahmedrony.salah.core.model.ZakatLineTreatment
import io.github.towkirahmedrony.salah.core.model.ZakatMetalUnit
import io.github.towkirahmedrony.salah.core.model.ZakatMoney
import io.github.towkirahmedrony.salah.core.model.ZakatNisabMetal
import io.github.towkirahmedrony.salah.core.model.ZakatNisabWeight
import io.github.towkirahmedrony.salah.core.model.ZakatResult
import io.github.towkirahmedrony.salah.core.model.ZakatStatus
import io.github.towkirahmedrony.salah.core.model.ZakatValuationBasis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The Zakat calculation, checked against arithmetic done by hand.
 *
 * ## How these expected values were produced — and what that does and does not prove
 *
 * Every expected amount below was computed **by hand, from the rules in `docs/ZAKAT_METHODOLOGY.md`, before
 * the implementation was written**, and the working is reproduced in that document's §10 so a reader can
 * check it without running anything. No expectation here was obtained by running this code and copying its
 * output: a test whose expectation comes from the implementation asserts only that the implementation is
 * self-consistent, which is the failure mode the brief warns about.
 *
 * **What passing proves:** that the code computes what §6 of that document says it should, in decimal, with
 * no floating-point drift and with rounding in exactly one place.
 *
 * **What passing does not prove:** that the rules are right. That is a question about sources, answered in
 * the register (§11), not here. Salah is not religiously certified and no test in this file claims it is.
 *
 * ## Two ways of comparing an amount, on purpose
 *
 * [assertMoney] compares **by value**, ignoring the number of trailing zeros, because `5 tola × 11.6638`
 * is `58.3190` as a `BigDecimal` and `58.319` as a quantity — the same amount, and a test that failed on
 * the difference would be testing `BigDecimal`'s scale arithmetic rather than the zakat calculation.
 *
 * [assertRoundedAmount] compares the **exact text**, and is used only where the scale is the assertion: the
 * amount payable is rounded to two places by policy, so `30000.00` and `30000` are *not* the same result
 * there, and asserting the text is how that policy is pinned.
 *
 * ## Conventions
 *
 * Each case names the methodology it assumes, because several produce **different answers** under a different
 * recognised position — a difference the tests assert rather than smooth over. All prices are inputs, so
 * nothing here depends on a market, a network or a clock.
 */
class ZakatCalculatorTest {

    private val valuationDate: LocalDate = LocalDate.of(2026, 10, 16)

    /** 12,000 BDT per gram of gold — chosen so every product in this file is checkable by hand. */
    private val goldPrice = "12000"

    /** 150 BDT per gram of silver. */
    private val silverPrice = "150"

    // --- Assertions -----------------------------------------------------------------------------

    /** Asserts [actual] denotes the same amount as [expected], whatever the trailing zeros. */
    private fun assertMoney(expected: String, actual: ZakatMoney?) {
        val money = requireNotNull(actual) { "expected an amount of $expected but there was none" }
        assertEquals(
            "expected $expected but was ${money.toPlainString()}",
            0,
            BigDecimal(expected).compareTo(money.amount),
        )
    }

    /** Asserts [actual] is exactly [expected] to the digit, including the number of decimal places. */
    private fun assertRoundedAmount(expected: String, actual: ZakatMoney?) {
        val money = requireNotNull(actual) { "expected a payable amount of $expected but there was none" }
        assertEquals(expected, money.toPlainString())
    }

    /** Asserts the exact decimal [actual] equals [expected] by value. */
    private fun assertDecimal(expected: String, actual: BigDecimal?) {
        val decimal = requireNotNull(actual) { "expected a decimal of $expected but there was none" }
        assertEquals("expected $expected but was ${decimal.toPlainString()}", 0, BigDecimal(expected).compareTo(decimal))
    }

    private fun ZakatResult.line(field: ZakatLineField) = lines.single { line -> line.field == field }

    // --- Input builder --------------------------------------------------------------------------

    @Suppress("LongParameterList")
    private fun inputs(
        cash: String? = null,
        foreignCurrencyAmount: String? = null,
        foreignCurrencyRate: String? = null,
        goldWeight: String? = null,
        goldPricePerGram: String? = goldPrice,
        silverWeight: String? = null,
        silverPricePerGram: String? = silverPrice,
        metalUnit: ZakatMetalUnit = ZakatMetalUnit.GRAM,
        preciousMetalsArePersonalJewellery: Boolean = false,
        inventoryValue: String? = null,
        inventoryBasis: ZakatInventoryBasis = ZakatInventoryBasis.MARKET_VALUE,
        collectibleReceivables: String? = null,
        doubtfulReceivables: String? = null,
        deductibleLiabilities: String? = null,
        longTermLiabilities: String? = null,
        nisabWeight: ZakatNisabWeight = ZakatNisabWeight.INTERNATIONAL,
        nisabMetal: ZakatNisabMetal = ZakatNisabMetal.GOLD,
        jewelleryTreatment: ZakatJewelleryTreatment = ZakatJewelleryTreatment.ZAKATABLE,
        debtTreatment: ZakatDebtTreatment = ZakatDebtTreatment.DEDUCT,
        hawlSeedDate: LocalDate? = null,
    ) = ZakatInputs(
        cash = cash?.let(::BigDecimal),
        foreignCurrencyAmount = foreignCurrencyAmount?.let(::BigDecimal),
        foreignCurrencyRate = foreignCurrencyRate?.let(::BigDecimal),
        goldWeight = goldWeight?.let(::BigDecimal),
        goldPricePerGram = goldPricePerGram?.let(::BigDecimal),
        silverWeight = silverWeight?.let(::BigDecimal),
        silverPricePerGram = silverPricePerGram?.let(::BigDecimal),
        metalUnit = metalUnit,
        preciousMetalsArePersonalJewellery = preciousMetalsArePersonalJewellery,
        inventoryValue = inventoryValue?.let(::BigDecimal),
        inventoryBasis = inventoryBasis,
        collectibleReceivables = collectibleReceivables?.let(::BigDecimal),
        doubtfulReceivables = doubtfulReceivables?.let(::BigDecimal),
        deductibleLiabilities = deductibleLiabilities?.let(::BigDecimal),
        longTermLiabilities = longTermLiabilities?.let(::BigDecimal),
        nisabWeight = nisabWeight,
        nisabMetal = nisabMetal,
        jewelleryTreatment = jewelleryTreatment,
        debtTreatment = debtTreatment,
        hawlSeedDate = hawlSeedDate,
        valuationDate = valuationDate,
    )

    // --- Case A: above the nisab, gold only -----------------------------------------------------

    @Test
    fun `case A - gold above the nisab pays two and a half percent`() {
        // 100 g × 12,000 = 1,200,000.  Nisab 87.48 g × 12,000 = 1,049,760.
        // 1,200,000 ≥ 1,049,760, so 1,200,000 × 0.025 = 30,000.
        val result = ZakatCalculator.calculate(inputs(goldWeight = "100"))

        assertEquals(ZakatStatus.CALCULATED, result.status)
        assertMoney("1200000", result.totalEligibleAssets)
        assertMoney("1049760", result.nisab)
        assertTrue(result.nisabMet!!)
        assertRoundedAmount("30000.00", result.zakatDue)
    }

    // --- Case B: below the nisab ----------------------------------------------------------------

    @Test
    fun `case B - wealth below the nisab owes nothing`() {
        // 500,000 + (10 g × 12,000 = 120,000) = 620,000, against a nisab of 1,049,760.
        val result = ZakatCalculator.calculate(inputs(cash = "500000", goldWeight = "10"))

        assertEquals(ZakatStatus.BELOW_NISAB, result.status)
        assertMoney("620000", result.netZakatableWealth)
        assertEquals(false, result.nisabMet)
        // No amount at all, rather than an amount of zero: "you owe nothing" and "the amount is 0.00" are
        // different statements, and only the first is true here.
        assertNull(result.zakatDue)
    }

    // --- Case C: exactly at the nisab -----------------------------------------------------------

    @Test
    fun `case C - wealth exactly at the nisab is treated as reaching it`() {
        // 87.48 g × 12,000 = 1,049,760, exactly the threshold. The comparison is `>=`.
        // The assumption is stated because sources word this differently: Islamic Relief describes the
        // obligation as attaching to wealth "above the nisab" while defining nisab as the minimum a Muslim
        // "must own", and the classical formulation is that nisab is reached. Salah treats reaching it as
        // meeting it, and this test pins that so the choice is visible rather than incidental.
        // 1,049,760 × 0.025 = 26,244.
        val result = ZakatCalculator.calculate(inputs(goldWeight = "87.48"))

        assertEquals(ZakatStatus.CALCULATED, result.status)
        assertMoney("1049760", result.netZakatableWealth)
        assertMoney("1049760", result.nisab)
        assertTrue(result.nisabMet!!)
        assertRoundedAmount("26244.00", result.zakatDue)
    }

    @Test
    fun `one hundredth of a gram below the nisab owes nothing`() {
        // The boundary from the other side, in hundredths — the place a floating-point total would be most
        // likely to lose. 87.47 g × 12,000 = 1,049,640 < 1,049,760.
        val result = ZakatCalculator.calculate(inputs(goldWeight = "87.47"))

        assertEquals(ZakatStatus.BELOW_NISAB, result.status)
        assertMoney("1049640", result.netZakatableWealth)
        assertNull(result.zakatDue)
    }

    // --- Case D: the silver nisab, which is the lower threshold ---------------------------------

    @Test
    fun `case D - the silver nisab makes the same wealth zakatable`() {
        // Nisab 612.36 g × 150 = 91,854. Cash 200,000 ≥ 91,854 ⇒ 200,000 × 0.025 = 5,000.
        val result = ZakatCalculator.calculate(inputs(cash = "200000", nisabMetal = ZakatNisabMetal.SILVER))

        assertEquals(ZakatStatus.CALCULATED, result.status)
        assertMoney("91854", result.nisab)
        assertRoundedAmount("5000.00", result.zakatDue)
    }

    @Test
    fun `case D - the same wealth owes nothing against the gold nisab`() {
        // The disagreement recorded in the methodology document §3.3, expressed as a number: 200,000 against
        // 1,049,760. Same wealth, same rate, opposite answer — which is why the metal is the user's choice and
        // why neither is presented as the agreed one.
        val result = ZakatCalculator.calculate(inputs(cash = "200000"))

        assertEquals(ZakatStatus.BELOW_NISAB, result.status)
        assertNull(result.zakatDue)
    }

    // --- Case E: the debt treatment changes the answer ------------------------------------------

    @Test
    fun `case E - a deductible liability removes the obligation under the Hanafi treatment`() {
        // Assets 500,000 + 720,000 = 1,220,000. Liability 400,000 ⇒ net 820,000 < 1,049,760 ⇒ nothing.
        val result = ZakatCalculator.calculate(
            inputs(cash = "500000", goldWeight = "60", deductibleLiabilities = "400000"),
        )

        assertEquals(ZakatStatus.BELOW_NISAB, result.status)
        assertMoney("820000", result.netZakatableWealth)
        assertMoney("400000", result.totalDeductibleLiabilities)
        assertNull(result.zakatDue)
    }

    @Test
    fun `case E - the same inputs pay zakat under the Shafi'i treatment`() {
        // Debt not deducted ⇒ 1,220,000 ≥ 1,049,760 ⇒ 1,220,000 × 0.025 = 30,500.
        val result = ZakatCalculator.calculate(
            inputs(
                cash = "500000",
                goldWeight = "60",
                deductibleLiabilities = "400000",
                debtTreatment = ZakatDebtTreatment.DO_NOT_DEDUCT,
            ),
        )

        assertEquals(ZakatStatus.CALCULATED, result.status)
        assertMoney("1220000", result.netZakatableWealth)
        assertMoney("0", result.totalDeductibleLiabilities)
        assertRoundedAmount("30500.00", result.zakatDue)
        // The liability is still reported, marked excluded, so a user can see it was read and not applied.
        val liability = result.line(ZakatLineField.DEDUCTIBLE_LIABILITIES)
        assertEquals(ZakatLineTreatment.EXCLUDED, liability.treatment)
        assertMoney("400000", liability.amount)
    }

    // --- Case F: a liability larger than the assets ---------------------------------------------

    @Test
    fun `case F - net wealth is floored at zero and never produces a negative zakat`() {
        // 100,000 − 300,000 = −200,000 ⇒ floored to 0.
        val result = ZakatCalculator.calculate(inputs(cash = "100000", deductibleLiabilities = "300000"))

        assertMoney("0", result.netZakatableWealth)
        assertFalse(result.netZakatableWealth.isNegative)
        assertEquals(ZakatStatus.BELOW_NISAB, result.status)
        assertNull(result.zakatDue)
    }

    // --- Case G: unit conversion ----------------------------------------------------------------

    @Test
    fun `case G - tola is converted to grams`() {
        // 5 tola × 11.6638 = 58.319 g. 58.319 × 12,000 = 699,828.
        val result = ZakatCalculator.calculate(inputs(goldWeight = "5", metalUnit = ZakatMetalUnit.TOLA))

        val gold = result.line(ZakatLineField.GOLD)
        assertDecimal("58.319", gold.computation!!.quantity)
        assertMoney("699828", gold.amount)
        // Below the nisab, so no amount — but the valuation is what this case is about.
        assertEquals(ZakatStatus.BELOW_NISAB, result.status)
    }

    @Test
    fun `vori is the same mass as tola, so the arithmetic cannot differ by label`() {
        val tola = ZakatCalculator.calculate(inputs(goldWeight = "7.5", metalUnit = ZakatMetalUnit.TOLA))
        val vori = ZakatCalculator.calculate(inputs(goldWeight = "7.5", metalUnit = ZakatMetalUnit.VORI))

        assertEquals(
            tola.line(ZakatLineField.GOLD).amount!!.amount,
            vori.line(ZakatLineField.GOLD).amount!!.amount,
        )
        // 7.5 × 11.6638 = 87.4785 g — which is NOT the 87.48 g the sources publish as the nisab, as
        // docs/ZAKAT_METHODOLOGY.md §2.1 records. The conversion and the constant are separate figures.
        assertDecimal("87.4785", vori.line(ZakatLineField.GOLD).computation!!.quantity)
    }

    // --- Case H: rounding ----------------------------------------------------------------------

    @Test
    fun `case H - the amount is rounded once, half up, and not from a rounded intermediate`() {
        // 1,234,567.89 × 0.025 = 30,864.19725 ⇒ half-up to two places ⇒ 30,864.20.
        // Rounding an intermediate value to two places first would give 30,864.19. The assertion that the
        // answer is not that is the difference between a documented rounding policy and an accident.
        val result = ZakatCalculator.calculate(inputs(cash = "1234567.89"))

        assertEquals(ZakatStatus.CALCULATED, result.status)
        assertRoundedAmount("30864.20", result.zakatDue)
        // The unrounded net wealth is reported exactly, so a reader can see where 30,864.19725 came from.
        assertMoney("1234567.89", result.netZakatableWealth)
    }

    @Test
    fun `exact amounts are not distorted by the rounding step`() {
        // 1,000,000 × 0.025 = 25,000 exactly. A half-up step must leave an exact amount alone.
        // The silver nisab (91,854) is used so that the wealth is genuinely above the threshold: against the
        // gold nisab of 1,049,760 a million would owe nothing and there would be no amount to round.
        val result = ZakatCalculator.calculate(
            inputs(cash = "1000000", nisabMetal = ZakatNisabMetal.SILVER),
        )

        assertEquals(ZakatStatus.CALCULATED, result.status)
        assertRoundedAmount("25000.00", result.zakatDue)
    }

    @Test
    fun `a trailing five rounds up rather than to even`() {
        // 1,000,000.20 × 0.025 = 25,000.005 exactly ⇒ half-up gives 25,000.01, half-even would give
        // 25,000.00. Salah rounds half-up, because that is what a person paying money does.
        val result = ZakatCalculator.calculate(
            inputs(cash = "1000000.20", nisabMetal = ZakatNisabMetal.SILVER),
        )

        assertEquals(ZakatStatus.CALCULATED, result.status)
        assertMoney("91854", result.nisab)
        assertRoundedAmount("25000.01", result.zakatDue)
    }

    // --- Case I: incomplete input --------------------------------------------------------------

    @Test
    fun `case I - a weight with no price is incomplete, not zero`() {
        // 20 g of gold and no price. The gold is not worth 0, and it is not worth 240,000 either — Salah
        // cannot know, so it says so and produces no amount.
        val result = ZakatCalculator.calculate(
            inputs(cash = "50000", goldWeight = "20", goldPricePerGram = null),
        )

        val gold = result.line(ZakatLineField.GOLD)
        assertEquals(ZakatLineTreatment.INCOMPLETE, gold.treatment)
        assertNull(gold.amount)
        assertEquals(ZakatStatus.ESTIMATE, result.status)
        assertTrue(result.incompleteFields.contains(ZakatLineField.GOLD))
        assertNull(result.zakatDue)
        // The cash that *was* valued is still reported, so the screen can show how far it got.
        assertMoney("50000", result.totalEligibleAssets)
    }

    @Test
    fun `a foreign currency amount with no rate is incomplete`() {
        val result = ZakatCalculator.calculate(
            inputs(cash = "10000", foreignCurrencyAmount = "500", foreignCurrencyRate = null),
        )

        assertEquals(ZakatStatus.ESTIMATE, result.status)
        assertTrue(result.incompleteFields.contains(ZakatLineField.FOREIGN_CURRENCY))
        assertNull(result.zakatDue)
        assertMoney("10000", result.totalEligibleAssets)
    }

    @Test
    fun `no gold held still needs a gold price when the nisab is gold based`() {
        // The threshold is a weight × a price. Without the price there is no threshold, so the result cannot
        // claim to have compared anything — even though the user holds no gold at all.
        val result = ZakatCalculator.calculate(inputs(cash = "500000", goldPricePerGram = null))

        assertNull(result.nisab)
        assertNull(result.nisabMet)
        assertEquals(ZakatStatus.ESTIMATE, result.status)
        assertTrue(result.incompleteFields.contains(ZakatLineField.GOLD))
        assertNull(result.zakatDue)
    }

    @Test
    fun `nothing entered produces an empty state rather than a zero zakat`() {
        val result = ZakatCalculator.calculate(
            ZakatInputs(goldPricePerGram = null, silverPricePerGram = null, valuationDate = valuationDate),
        )

        assertEquals(ZakatStatus.NOTHING_ENTERED, result.status)
        assertTrue(result.lines.isEmpty())
        assertNull(result.zakatDue)
        assertMoney("0", result.totalEligibleAssets)
    }

    @Test
    fun `a category entered as zero is answered, not missing`() {
        // The user typed 0. That is a statement, and it is included — with no amount due, since zero is
        // below the nisab.
        val result = ZakatCalculator.calculate(inputs(cash = "0"))

        val cash = result.line(ZakatLineField.CASH)
        assertEquals(ZakatLineTreatment.INCLUDED, cash.treatment)
        assertMoney("0", cash.amount)
        assertTrue(result.incompleteFields.isEmpty())
        assertEquals(ZakatStatus.BELOW_NISAB, result.status)
    }

    // --- Case J: the two nisab weight sets ------------------------------------------------------

    @Test
    fun `case J - 86 grams of gold is the boundary the two nisab weight sets disagree about`() {
        // 86 g × 12,000 = 1,032,000.
        //   International: 87.48 g × 12,000 = 1,049,760 ⇒ below ⇒ nothing.
        //   Bangladesh:    85 g    × 12,000 = 1,020,000 ⇒ met   ⇒ 1,032,000 × 0.025 = 25,800.
        val international = ZakatCalculator.calculate(
            inputs(goldWeight = "86", nisabWeight = ZakatNisabWeight.INTERNATIONAL),
        )
        val bangladesh = ZakatCalculator.calculate(
            inputs(goldWeight = "86", nisabWeight = ZakatNisabWeight.BANGLADESH),
        )

        assertMoney("1049760", international.nisab)
        assertEquals(ZakatStatus.BELOW_NISAB, international.status)
        assertNull(international.zakatDue)

        assertMoney("1020000", bangladesh.nisab)
        assertEquals(ZakatStatus.CALCULATED, bangladesh.status)
        assertRoundedAmount("25800.00", bangladesh.zakatDue)
    }

    @Test
    fun `the nisab weight is the published figure and not a tola recomputation`() {
        // 7.5 tola at 11.6638 g/tola is 87.4785 g, not 87.48 g. Salah carries what its sources publish
        // rather than recomputing it, so the constant and the arithmetic differ in the fifth place and the
        // code follows the source — docs/ZAKAT_METHODOLOGY.md §2.1.
        assertDecimal("87.48", ZakatNisabWeight.INTERNATIONAL.goldGrams)
        assertDecimal("612.36", ZakatNisabWeight.INTERNATIONAL.silverGrams)
        assertDecimal("85", ZakatNisabWeight.BANGLADESH.goldGrams)
        assertDecimal("595", ZakatNisabWeight.BANGLADESH.silverGrams)
    }

    // --- Jewellery: the other disagreement that changes the answer -------------------------------

    @Test
    fun `personal jewellery is zakatable under the Hanafi treatment`() {
        val result = ZakatCalculator.calculate(
            inputs(goldWeight = "100", preciousMetalsArePersonalJewellery = true),
        )

        assertEquals(ZakatStatus.CALCULATED, result.status)
        assertMoney("1200000", result.totalEligibleAssets)
        assertRoundedAmount("30000.00", result.zakatDue)
    }

    @Test
    fun `personal jewellery is exempt under the majority treatment, and the amount it removes is shown`() {
        val result = ZakatCalculator.calculate(
            inputs(
                cash = "1100000",
                goldWeight = "100",
                preciousMetalsArePersonalJewellery = true,
                jewelleryTreatment = ZakatJewelleryTreatment.PERSONAL_USE_EXEMPT,
            ),
        )

        val gold = result.line(ZakatLineField.GOLD)
        // Excluded, but still reported with its value, so a user can see what the ruling they chose removed
        // from their total: 1,200,000 of gold is out, leaving 1,100,000.
        assertEquals(ZakatLineTreatment.EXCLUDED, gold.treatment)
        assertMoney("1200000", gold.amount)
        assertMoney("1100000", result.totalEligibleAssets)
        assertRoundedAmount("27500.00", result.zakatDue)
    }

    @Test
    fun `the jewellery treatment is only consulted when the metals are jewellery`() {
        // Investment gold under the majority ruling is still zakatable — the exemption is for personal
        // adornment, and the flag is what distinguishes the two.
        val result = ZakatCalculator.calculate(
            inputs(
                goldWeight = "100",
                preciousMetalsArePersonalJewellery = false,
                jewelleryTreatment = ZakatJewelleryTreatment.PERSONAL_USE_EXEMPT,
            ),
        )

        assertMoney("1200000", result.totalEligibleAssets)
        assertEquals(ZakatStatus.CALCULATED, result.status)
    }

    // --- The all-one-metal nisab rule -----------------------------------------------------------

    @Test
    fun `holding only gold forces the gold nisab even when silver was chosen`() {
        // docs/ZAKAT_METHODOLOGY.md §3.3: this is a rule, not a preference.
        val result = ZakatCalculator.calculate(
            inputs(goldWeight = "100", nisabMetal = ZakatNisabMetal.SILVER),
        )

        assertEquals(ZakatNisabMetal.GOLD, result.nisabMetalUsed)
        assertTrue(result.nisabMetalOverridden)
        assertMoney("1049760", result.nisab)
    }

    @Test
    fun `holding only silver forces the silver nisab`() {
        val result = ZakatCalculator.calculate(
            inputs(silverWeight = "1000", nisabMetal = ZakatNisabMetal.GOLD),
        )

        assertEquals(ZakatNisabMetal.SILVER, result.nisabMetalUsed)
        assertTrue(result.nisabMetalOverridden)
        // 612.36 × 150 = 91,854; 1,000 g × 150 = 150,000 ⇒ 150,000 × 0.025 = 3,750.
        assertMoney("91854", result.nisab)
        assertRoundedAmount("3750.00", result.zakatDue)
    }

    @Test
    fun `holding both metals leaves the user's choice alone`() {
        val result = ZakatCalculator.calculate(
            inputs(goldWeight = "10", silverWeight = "10", nisabMetal = ZakatNisabMetal.SILVER),
        )

        assertEquals(ZakatNisabMetal.SILVER, result.nisabMetalUsed)
        assertFalse(result.nisabMetalOverridden)
    }

    @Test
    fun `jewellery excluded under the majority ruling does not force that metal's nisab`() {
        // The rule is about zakatable assets: gold that has been excluded is not held, for its purposes.
        val result = ZakatCalculator.calculate(
            inputs(
                goldWeight = "100",
                silverWeight = "500",
                preciousMetalsArePersonalJewellery = true,
                jewelleryTreatment = ZakatJewelleryTreatment.PERSONAL_USE_EXEMPT,
                nisabMetal = ZakatNisabMetal.SILVER,
            ),
        )

        assertEquals(ZakatNisabMetal.SILVER, result.nisabMetalUsed)
        assertFalse(result.nisabMetalOverridden)
    }

    // --- Receivables ----------------------------------------------------------------------------

    @Test
    fun `a collectible receivable is included`() {
        val result = ZakatCalculator.calculate(inputs(collectibleReceivables = "1100000"))

        assertMoney("1100000", result.totalEligibleAssets)
        assertRoundedAmount("27500.00", result.zakatDue)
    }

    @Test
    fun `a doubtful receivable is excluded but reported`() {
        // Hanafi: ownership of a debt that may never be paid is incomplete.
        val result = ZakatCalculator.calculate(inputs(cash = "1100000", doubtfulReceivables = "500000"))

        val doubtful = result.line(ZakatLineField.DOUBTFUL_RECEIVABLES)
        assertEquals(ZakatLineTreatment.EXCLUDED, doubtful.treatment)
        assertMoney("500000", doubtful.amount)
        assertMoney("1100000", result.totalEligibleAssets)
    }

    // --- Liabilities that are never deducted ----------------------------------------------------

    @Test
    fun `a long-term liability is never deducted, under either treatment`() {
        val hanafi = ZakatCalculator.calculate(inputs(cash = "1100000", longTermLiabilities = "5000000"))
        val shafii = ZakatCalculator.calculate(
            inputs(
                cash = "1100000",
                longTermLiabilities = "5000000",
                debtTreatment = ZakatDebtTreatment.DO_NOT_DEDUCT,
            ),
        )

        listOf(hanafi, shafii).forEach { result ->
            assertMoney("0", result.totalDeductibleLiabilities)
            assertMoney("1100000", result.netZakatableWealth)
            val longTerm = result.line(ZakatLineField.LONG_TERM_LIABILITIES)
            assertEquals(ZakatLineTreatment.EXCLUDED, longTerm.treatment)
            assertMoney("5000000", longTerm.amount)
        }
    }

    // --- Inventory ------------------------------------------------------------------------------

    @Test
    fun `the inventory valuation basis is applied and reported`() {
        val market = ZakatCalculator.calculate(
            inputs(inventoryValue = "1100000", inventoryBasis = ZakatInventoryBasis.MARKET_VALUE),
        )
        val landed = ZakatCalculator.calculate(
            inputs(inventoryValue = "1100000", inventoryBasis = ZakatInventoryBasis.LANDED_COST),
        )

        assertEquals(ZakatValuationBasis.MARKET_VALUE, market.line(ZakatLineField.INVENTORY).computation!!.basis)
        assertEquals(ZakatValuationBasis.LANDED_COST, landed.line(ZakatLineField.INVENTORY).computation!!.basis)
        assertEquals(ZakatInventoryBasis.LANDED_COST, landed.inventoryBasis)
        // The basis changes which figure the user should type, not the arithmetic applied to it.
        assertEquals(
            market.totalEligibleAssets.amount,
            landed.totalEligibleAssets.amount,
        )
    }

    // --- Foreign currency -----------------------------------------------------------------------

    @Test
    fun `foreign currency is converted at the user's rate, exactly`() {
        // 1,000 × 120 = 120,000. Not 119,999.99, which is what a binary fraction would risk.
        val result = ZakatCalculator.calculate(inputs(foreignCurrencyAmount = "1000", foreignCurrencyRate = "120"))

        val foreign = result.line(ZakatLineField.FOREIGN_CURRENCY)
        assertMoney("120000", foreign.amount)
        assertDecimal("120", foreign.computation!!.unitPrice)
    }

    @Test
    fun `a conversion that binary floating point could not represent stays exact`() {
        // 1,234,567.89 × 1.07 = 1,320,987.6423 exactly. Neither operand nor the product is representable in
        // binary floating point, which is the point of the test.
        val result = ZakatCalculator.calculate(
            inputs(foreignCurrencyAmount = "1234567.89", foreignCurrencyRate = "1.07"),
        )

        assertMoney("1320987.6423", result.line(ZakatLineField.FOREIGN_CURRENCY).amount)
    }

    // --- Aggregation, precision and scale -------------------------------------------------------

    @Test
    fun `every category is aggregated exactly, with no floating point drift`() {
        // 0.1 + 0.2 + 0.3 is 0.6 here, and 0.6000000000000001 in binary floating point.
        val result = ZakatCalculator.calculate(
            inputs(cash = "0.1", collectibleReceivables = "0.2", inventoryValue = "0.3"),
        )

        assertMoney("0.6", result.totalEligibleAssets)
    }

    @Test
    fun `a very large wealth is aggregated and rated without overflow`() {
        // 1,000,000,000,000 × 0.025 = 25,000,000,000 exactly. A Long could hold the net wealth; an Int could
        // not, and a Double would have lost the low digits long before this.
        val result = ZakatCalculator.calculate(inputs(cash = "1000000000000"))

        assertMoney("1000000000000", result.totalEligibleAssets)
        assertRoundedAmount("25000000000.00", result.zakatDue)
    }

    @Test
    fun `twenty decimal places survive the calculation`() {
        val result = ZakatCalculator.calculate(inputs(cash = "1000000000.12345678901234567890"))

        assertMoney("1000000000.12345678901234567890", result.totalEligibleAssets)
    }

    // --- Row shape: no category can be counted twice --------------------------------------------

    @Test
    fun `each category appears at most once in the breakdown`() {
        val result = ZakatCalculator.calculate(
            inputs(
                cash = "100",
                foreignCurrencyAmount = "1",
                foreignCurrencyRate = "1",
                goldWeight = "1",
                silverWeight = "1",
                inventoryValue = "1",
                collectibleReceivables = "1",
                doubtfulReceivables = "1",
                deductibleLiabilities = "1",
                longTermLiabilities = "1",
            ),
        )

        // There is no catch-all row, so the same holding cannot be entered in two places — the risk the brief
        // names is structural here rather than a warning the user has to heed.
        val fields = result.lines.map { line -> line.field }
        assertEquals("a category was reported twice", fields.size, fields.distinct().size)
        // Every category the calculator knows about was supplied above, and each produced exactly one row —
        // so the count is the enum's own size rather than a number that can drift when a field is added.
        assertEquals(ZakatLineField.entries.size, fields.size)
    }

    @Test
    fun `only the categories the user touched appear, in a stable order`() {
        val result = ZakatCalculator.calculate(inputs(silverWeight = "100", cash = "5000"))

        assertEquals(listOf(ZakatLineField.CASH, ZakatLineField.SILVER), result.lines.map { it.field })
    }

    // --- Hawl -----------------------------------------------------------------------------------

    @Test
    fun `an estimated anniversary is produced from the seed date`() {
        // 2026-01-01 + 354 days = 2026-12-21.
        val seed = LocalDate.of(2026, 1, 1)
        val result = ZakatCalculator.calculate(inputs(cash = "1100000", hawlSeedDate = seed))

        val hawl = result.hawl!!
        assertEquals(seed, hawl.seedDate)
        assertEquals(LocalDate.of(2026, 12, 21), hawl.estimatedAnniversary)
        assertEquals(354, hawl.approximateYearDays)
    }

    @Test
    fun `no seed date means no hawl estimate rather than one invented`() {
        val result = ZakatCalculator.calculate(inputs(cash = "1100000"))

        assertNull(result.hawl)
    }

    // --- Methodology reporting ------------------------------------------------------------------

    @Test
    fun `the result reports every choice that produced it`() {
        val result = ZakatCalculator.calculate(
            inputs(
                cash = "1100000",
                nisabWeight = ZakatNisabWeight.BANGLADESH,
                jewelleryTreatment = ZakatJewelleryTreatment.PERSONAL_USE_EXEMPT,
                debtTreatment = ZakatDebtTreatment.DO_NOT_DEDUCT,
                inventoryBasis = ZakatInventoryBasis.LANDED_COST,
            ),
        )

        // A screen cannot name the methodology in force unless the result carries it.
        assertEquals(ZakatNisabWeight.BANGLADESH, result.nisabUsed)
        assertEquals(ZakatJewelleryTreatment.PERSONAL_USE_EXEMPT, result.jewelleryTreatment)
        assertEquals(ZakatDebtTreatment.DO_NOT_DEDUCT, result.debtTreatment)
        assertEquals(ZakatInventoryBasis.LANDED_COST, result.inventoryBasis)
        assertDecimal("0.025", result.zakatRate)
    }

    @Test
    fun `a result resting on entered prices says so`() {
        assertTrue(ZakatCalculator.calculate(inputs(goldWeight = "100")).dependsOnUserSuppliedPrice)
        // Even a cash-only calculation depends on a price, because the threshold is a weight × a price.
        assertTrue(ZakatCalculator.calculate(inputs(cash = "1100000")).dependsOnUserSuppliedPrice)
    }

    @Test
    fun `the nisab weight and the price it was computed from are reported`() {
        val result = ZakatCalculator.calculate(inputs(goldWeight = "100"))

        assertDecimal("87.48", result.nisabGrams)
        assertDecimal("12000", result.nisabPricePerGram)
    }

    // --- The rate ------------------------------------------------------------------------------

    @Test
    fun `the rate is an exact decimal, not a binary fraction`() {
        assertEquals("0.025", ZakatResult.RATE.toPlainString())
        // The assertion that matters: the decimal 0.025 and the nearest Double to 0.025 are different
        // numbers. The rate is held as the former, so applying it multiplies exactly.
        assertNotEquals(BigDecimal("0.025"), BigDecimal(0.025))
        assertEquals(BigDecimal("25"), ZakatResult.RATE.movePointRight(3))
    }
}
