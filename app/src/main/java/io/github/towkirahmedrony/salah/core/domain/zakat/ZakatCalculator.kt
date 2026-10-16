package io.github.towkirahmedrony.salah.core.domain.zakat

import io.github.towkirahmedrony.salah.core.model.ZakatComputation
import io.github.towkirahmedrony.salah.core.model.ZakatDebtTreatment
import io.github.towkirahmedrony.salah.core.model.ZakatHawl
import io.github.towkirahmedrony.salah.core.model.ZakatInputs
import io.github.towkirahmedrony.salah.core.model.ZakatJewelleryTreatment
import io.github.towkirahmedrony.salah.core.model.ZakatLine
import io.github.towkirahmedrony.salah.core.model.ZakatLineField
import io.github.towkirahmedrony.salah.core.model.ZakatLineTreatment
import io.github.towkirahmedrony.salah.core.model.ZakatMetal
import io.github.towkirahmedrony.salah.core.model.ZakatMoney
import io.github.towkirahmedrony.salah.core.model.ZakatNisabMetal
import io.github.towkirahmedrony.salah.core.model.ZakatQuantityUnit
import io.github.towkirahmedrony.salah.core.model.ZakatResult
import io.github.towkirahmedrony.salah.core.model.ZakatStatus
import io.github.towkirahmedrony.salah.core.model.ZakatValuationBasis
import java.math.BigDecimal

/**
 * The Zakat calculation, and nothing else.
 *
 * Pure, total, deterministic and free of Android: it takes a [ZakatInputs] and returns a [ZakatResult], and
 * it reads no clock, no preference, no network and no device. Every rule it applies is recorded with the
 * source that supports it in `docs/ZAKAT_METHODOLOGY.md`, and this file's comments name the section that
 * applies at each step. Where two recognised positions differ, the choice is an input and the result reports
 * which one was used — this class never picks for the user and never averages the two.
 *
 * ## The flow, which is also the order of the result
 *
 * 1. value each category, reporting each as an included, excluded or incomplete line (§4);
 * 2. total the included rows;
 * 3. total the deductions, which is zero unless the selected treatment deducts (§4.5);
 * 4. net = total − deductions, **floored at zero** so that a debt larger than the assets cannot produce a
 *    negative zakat;
 * 5. compute the nisab from the selected weight pair and the selected metal's price (§3, §6);
 * 6. compare **unrounded** net wealth to the nisab with `>=`;
 * 7. apply 2.5% and round once, to currency scale — and only when the threshold is met and nothing is
 *    missing.
 *
 * ## What "nothing is missing" means, and why no amount is produced without it
 *
 * A row with data but without the value it needs — a gold weight with no price, an amount in another currency
 * with no rate — makes the result an [ZakatStatus.ESTIMATE] and leaves [ZakatResult.zakatDue] **null**. The
 * alternative, totalling the rows that are complete and quietly ignoring the rest, would present a partial sum
 * as an answer, and the user has no way to tell a category they left blank from one the app decided to skip.
 * The rows are reported as [ZakatLineTreatment.INCOMPLETE] and the screen names them.
 *
 * ## The all-one-metal rule
 *
 * `docs/ZAKAT_METHODOLOGY.md` §3.3 records it as a rule rather than a preference: if the user's zakatable
 * assets consist entirely of gold, the gold nisab must be used, and likewise for silver. This class enforces
 * it and reports the override, so the screen can say the nisab was changed and why. It is the one place the
 * user's own choice is not final, and it says so.
 */
object ZakatCalculator {

    /**
     * The result for [inputs].
     *
     * Total: every combination of inputs produces a result, including an empty one. A calculation with
     * nothing entered returns [ZakatStatus.NOTHING_ENTERED] rather than an error or a zakat of zero, because
     * zero and "not answered" are different statements about a person's wealth.
     */
    fun calculate(inputs: ZakatInputs): ZakatResult {
        val gold = preciousMetalLine(
            field = ZakatLineField.GOLD,
            weight = inputs.goldWeight,
            pricePerGram = inputs.goldPricePerGram,
            gramsPerUnit = inputs.metalUnit.gramsPerUnit,
            exemptAsPersonalJewellery = inputs.preciousMetalsArePersonalJewellery &&
                inputs.jewelleryTreatment == ZakatJewelleryTreatment.PERSONAL_USE_EXEMPT,
        )
        val silver = preciousMetalLine(
            field = ZakatLineField.SILVER,
            weight = inputs.silverWeight,
            pricePerGram = inputs.silverPricePerGram,
            gramsPerUnit = inputs.metalUnit.gramsPerUnit,
            exemptAsPersonalJewellery = inputs.preciousMetalsArePersonalJewellery &&
                inputs.jewelleryTreatment == ZakatJewelleryTreatment.PERSONAL_USE_EXEMPT,
        )

        val lines = listOfNotNull(
            directLine(ZakatLineField.CASH, inputs.cash),
            foreignCurrencyLine(inputs),
            gold,
            silver,
            directLine(
                field = ZakatLineField.INVENTORY,
                value = inputs.inventoryValue,
                basis = when (inputs.inventoryBasis) {
                    io.github.towkirahmedrony.salah.core.model.ZakatInventoryBasis.MARKET_VALUE ->
                        ZakatValuationBasis.MARKET_VALUE

                    io.github.towkirahmedrony.salah.core.model.ZakatInventoryBasis.LANDED_COST ->
                        ZakatValuationBasis.LANDED_COST
                },
            ),
            // A strong debt is the creditor's wealth, so it is included — §4.4.
            directLine(ZakatLineField.COLLECTIBLE_RECEIVABLES, inputs.collectibleReceivables),
            // A doubtful debt is excluded under the Hanafi ruling, because ownership of it is incomplete.
            directLine(
                field = ZakatLineField.DOUBTFUL_RECEIVABLES,
                value = inputs.doubtfulReceivables,
                treatment = ZakatLineTreatment.EXCLUDED,
            ),
            // Deducted only where the selected treatment deducts; otherwise shown as excluded, with the
            // reason, so the user can see their input was read and deliberately not applied — §4.5.
            directLine(
                field = ZakatLineField.DEDUCTIBLE_LIABILITIES,
                value = inputs.deductibleLiabilities,
                treatment = if (inputs.debtTreatment == ZakatDebtTreatment.DEDUCT) {
                    ZakatLineTreatment.INCLUDED
                } else {
                    ZakatLineTreatment.EXCLUDED
                },
            ),
            // Never deducted, under either treatment, and always shown with its reason — §4.5.
            directLine(
                field = ZakatLineField.LONG_TERM_LIABILITIES,
                value = inputs.longTermLiabilities,
                treatment = ZakatLineTreatment.EXCLUDED,
            ),
        )

        val assetLines = lines.filter { line ->
            line.treatment == ZakatLineTreatment.INCLUDED && !line.field.isLiability
        }
        val deductedLines = lines.filter { line ->
            line.treatment == ZakatLineTreatment.INCLUDED && line.field.isLiability
        }

        val totalAssets = assetLines.sumAmounts()
        val totalDeducted = deductedLines.sumAmounts()
        // Floored: a liability larger than the assets means no zakat, never a negative one — §10 Case F.
        val netWealth = (totalAssets - totalDeducted).coerceAtLeastZero()

        val forcedMetal = nisabMetalFor(inputs, gold, silver)
        val nisabMetalOverridden = forcedMetal != inputs.nisabMetal
        val nisabGrams = when (forcedMetal) {
            ZakatNisabMetal.GOLD -> inputs.nisabWeight.goldGrams
            ZakatNisabMetal.SILVER -> inputs.nisabWeight.silverGrams
        }
        val nisabPrice = when (forcedMetal) {
            ZakatNisabMetal.GOLD -> inputs.goldPricePerGram
            ZakatNisabMetal.SILVER -> inputs.silverPricePerGram
        }
        // The nisab is a weight; comparing it to a currency balance needs a price, and the price is the
        // user's. Without it the threshold cannot be stated, so it is reported as unknown rather than as
        // zero — which would make every wealth above the nisab — or as infinite, which would make none.
        val nisab = nisabPrice?.let { price -> ZakatMoney.ofDecimal(price.multiply(nisabGrams)) }

        val incompleteFields = incompleteFields(inputs, gold, silver, forcedMetal)

        val nisabMet = nisab?.let { threshold -> netWealth >= threshold }

        val status = when {
            lines.isEmpty() -> ZakatStatus.NOTHING_ENTERED
            incompleteFields.isNotEmpty() || nisab == null -> ZakatStatus.ESTIMATE
            nisabMet == false -> ZakatStatus.BELOW_NISAB
            else -> ZakatStatus.CALCULATED
        }

        val zakatDue = if (status == ZakatStatus.CALCULATED) {
            // The one rounding in the feature, and the only place it is applied.
            (netWealth * ZakatResult.RATE).roundedToCurrency()
        } else {
            null
        }

        return ZakatResult(
            status = status,
            currency = inputs.currency,
            valuationDate = inputs.valuationDate,
            lines = lines,
            totalEligibleAssets = totalAssets,
            totalDeductibleLiabilities = totalDeducted,
            netZakatableWealth = netWealth,
            nisab = nisab,
            nisabUsed = inputs.nisabWeight,
            nisabMetalUsed = forcedMetal,
            nisabMetalOverridden = nisabMetalOverridden,
            nisabGrams = nisabGrams,
            nisabPricePerGram = nisabPrice,
            nisabMet = nisabMet,
            zakatDue = zakatDue,
            zakatRate = ZakatResult.RATE,
            jewelleryTreatment = inputs.jewelleryTreatment,
            debtTreatment = inputs.debtTreatment,
            inventoryBasis = inputs.inventoryBasis,
            incompleteFields = incompleteFields,
            dependsOnUserSuppliedPrice = (assetLines.any { line -> line.computation?.unitPrice != null }) ||
                nisab != null,
            hawl = inputs.hawlSeedDate?.let { seed ->
                ZakatHawl(
                    seedDate = seed,
                    estimatedAnniversary = seed.plusDays(
                        ZakatHawl.APPROXIMATE_LUNAR_YEAR_DAYS.toLong(),
                    ),
                )
            },
        )
    }

    // --- Categories -----------------------------------------------------------------------------

    /**
     * A row whose amount the user entered directly.
     *
     * Returns `null` when the field was not entered at all, which is how a row the user never touched stays
     * out of the breakdown. A row entered as `0` is kept and reported as an included zero: the user has
     * answered, and "I hold none of this" is a different statement from "I was not asked".
     */
    private fun directLine(
        field: ZakatLineField,
        value: BigDecimal?,
        treatment: ZakatLineTreatment = ZakatLineTreatment.INCLUDED,
        basis: ZakatValuationBasis? = null,
    ): ZakatLine? {
        val amount = value ?: return null
        return ZakatLine(
            field = field,
            treatment = treatment,
            amount = ZakatMoney.ofDecimal(amount),
            computation = ZakatComputation(
                quantity = amount,
                quantityUnit = ZakatQuantityUnit.NONE,
                unitPrice = null,
                baseAmount = amount,
                basis = basis ?: ZakatValuationBasis.USER_ENTERED,
            ),
        )
    }

    /**
     * A precious metal: weight, converted to grams, at the price per gram the user entered.
     *
     * Three outcomes, and the difference between them matters:
     *
     *  * no weight entered ⇒ no row, because the user was not asked about this metal;
     *  * weight entered, no price ⇒ an **incomplete** row with no amount, because a weight Salah cannot value
     *    is not a weight worth nothing;
     *  * both entered ⇒ an included row, with the conversion and the price recorded in its computation so the
     *    screen can show the working.
     *
     * [exemptAsPersonalJewellery] is applied here rather than by zeroing the weight: an exempt row keeps its
     * value in the breakdown and is marked excluded, so the user can see how much the majority ruling would
     * remove from their total.
     */
    private fun preciousMetalLine(
        field: ZakatLineField,
        weight: BigDecimal?,
        pricePerGram: BigDecimal?,
        gramsPerUnit: BigDecimal,
        exemptAsPersonalJewellery: Boolean,
    ): ZakatLine? {
        val enteredWeight = weight ?: return null
        // grams = weight × grams-per-unit. Exact: both operands are decimals, so no precision is lost even
        // when the unit is a tola with a repeating expansion.
        val grams = enteredWeight.multiply(gramsPerUnit)

        if (exemptAsPersonalJewellery) {
            val amount = pricePerGram?.let { price -> grams.multiply(price) }
            return ZakatLine(
                field = field,
                treatment = ZakatLineTreatment.EXCLUDED,
                amount = amount?.let { value -> ZakatMoney.ofDecimal(value) },
                computation = ZakatComputation(
                    quantity = grams,
                    quantityUnit = ZakatQuantityUnit.GRAMS,
                    unitPrice = pricePerGram,
                    baseAmount = amount ?: BigDecimal.ZERO,
                    basis = ZakatValuationBasis.USER_ENTERED,
                ),
            )
        }

        val price = pricePerGram ?: return ZakatLine(
            field = field,
            treatment = ZakatLineTreatment.INCOMPLETE,
            amount = null,
            computation = null,
        )

        val amount = grams.multiply(price)
        return ZakatLine(
            field = field,
            treatment = ZakatLineTreatment.INCLUDED,
            amount = ZakatMoney.ofDecimal(amount),
            computation = ZakatComputation(
                quantity = grams,
                quantityUnit = ZakatQuantityUnit.GRAMS,
                unitPrice = price,
                baseAmount = amount,
                basis = ZakatValuationBasis.USER_ENTERED,
            ),
        )
    }

    /**
     * An amount held in another currency, converted at the rate the user entered.
     *
     * Salah fetches no rate. The rate is the user's own and is printed beside the row, so the conversion is
     * theirs to check — `docs/ZAKAT_METHODOLOGY.md` §4.1 and §7.3. An amount with no rate is incomplete, not
     * zero: converting it at a rate Salah invented would put an amount of its own choosing into the total.
     */
    private fun foreignCurrencyLine(inputs: ZakatInputs): ZakatLine? {
        val amount = inputs.foreignCurrencyAmount ?: return null
        val rate = inputs.foreignCurrencyRate
            ?: return ZakatLine(
                field = ZakatLineField.FOREIGN_CURRENCY,
                treatment = ZakatLineTreatment.INCOMPLETE,
                amount = null,
                computation = null,
            )

        val converted = amount.multiply(rate)
        return ZakatLine(
            field = ZakatLineField.FOREIGN_CURRENCY,
            treatment = ZakatLineTreatment.INCLUDED,
            amount = ZakatMoney.ofDecimal(converted),
            computation = ZakatComputation(
                quantity = amount,
                quantityUnit = ZakatQuantityUnit.OTHER_CURRENCY,
                unitPrice = rate,
                baseAmount = converted,
                basis = ZakatValuationBasis.USER_ENTERED,
            ),
        )
    }

    // --- Nisab ----------------------------------------------------------------------------------

    /**
     * Which metal's nisab applies, after the all-one-metal rule of `docs/ZAKAT_METHODOLOGY.md` §3.3.
     *
     * The rule is about the assets that are *actually zakatable*, so a metal excluded as personal jewellery
     * does not count as held for this purpose. If the user's zakatable metal is only gold, the gold nisab
     * applies; only silver, the silver nisab; both, or neither, and the user's own choice stands.
     */
    private fun nisabMetalFor(
        inputs: ZakatInputs,
        gold: ZakatLine?,
        silver: ZakatLine?,
    ): ZakatNisabMetal {
        val goldCounts = gold.isIncludedAndWorthSomething()
        val silverCounts = silver.isIncludedAndWorthSomething()
        return when {
            goldCounts && !silverCounts -> ZakatNisabMetal.GOLD
            silverCounts && !goldCounts -> ZakatNisabMetal.SILVER
            else -> inputs.nisabMetal
        }
    }

    private fun ZakatLine?.isIncludedAndWorthSomething(): Boolean {
        val line = this ?: return false
        val amount = line.amount ?: return false
        return line.treatment == ZakatLineTreatment.INCLUDED && amount.amount.signum() > 0
    }

    // --- Incomplete inputs ----------------------------------------------------------------------

    /**
     * The rows that cannot be counted yet.
     *
     * Two kinds, and both are needed:
     *
     *  * a row with data and no price — gold, silver, or an amount in another currency;
     *  * the **nisab's own price**. A user with no gold at all still cannot be told whether they owe zakat
     *    against a gold threshold without a gold price, so the metal the threshold is measured in counts as
     *    incomplete even when the user holds none of it. Leaving this out would report a complete calculation
     *    whose threshold was never computed.
     */
    private fun incompleteFields(
        inputs: ZakatInputs,
        gold: ZakatLine?,
        silver: ZakatLine?,
        nisabMetal: ZakatNisabMetal,
    ): List<ZakatLineField> {
        val fields = mutableListOf<ZakatLineField>()

        if (inputs.foreignCurrencyAmount != null && inputs.foreignCurrencyRate == null) {
            fields += ZakatLineField.FOREIGN_CURRENCY
        }
        if (gold?.treatment == ZakatLineTreatment.INCOMPLETE) fields += ZakatLineField.GOLD
        if (silver?.treatment == ZakatLineTreatment.INCOMPLETE) fields += ZakatLineField.SILVER

        when (nisabMetal) {
            ZakatNisabMetal.GOLD -> if (inputs.goldPricePerGram == null) fields += ZakatLineField.GOLD
            ZakatNisabMetal.SILVER -> if (inputs.silverPricePerGram == null) fields += ZakatLineField.SILVER
        }

        return fields.distinct()
    }

    // --- Small helpers --------------------------------------------------------------------------

    /** The sum of the amounts on [this] list, exact, treating a null amount as nothing to add. */
    private fun List<ZakatLine>.sumAmounts(): ZakatMoney =
        fold(ZakatMoney.ZERO) { running, line ->
            line.amount?.let { amount -> running + amount } ?: running
        }

    /** True for the rows that reduce the total rather than add to it. */
    private val ZakatLineField.isLiability: Boolean
        get() = this == ZakatLineField.DEDUCTIBLE_LIABILITIES || this == ZakatLineField.LONG_TERM_LIABILITIES
}
