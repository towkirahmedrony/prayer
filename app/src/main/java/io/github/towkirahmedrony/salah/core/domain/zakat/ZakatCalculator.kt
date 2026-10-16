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
import io.github.towkirahmedrony.salah.core.model.ZakatMetalUnit
import io.github.towkirahmedrony.salah.core.model.ZakatMoney
import io.github.towkirahmedrony.salah.core.model.ZakatNisabMetal
import io.github.towkirahmedrony.salah.core.model.ZakatNisabWeight
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
     * The international gold nisab in the traditional unit: 7.5 tola (vori).
     *
     * The published 87.48 g figure is the traditional threshold stated in grams, and 7.5 × 11.6638 g =
     * 87.4785 g — five ten-thousandths of a gram *below* the published constant. A user who enters "7.5"
     * in tola therefore holds the traditional nisab exactly, and comparing their converted 87.4785 g
     * against the 87.48 g constant would tell them they are below the threshold they actually meet. The
     * unit's own exact multiple is used when the unit is a traditional one, so the boundary is the one the
     * unit defines (see `docs/ZAKAT_METHODOLOGY.md` §2.1 and §8.2).
     */
    private val GOLD_NISAB_TOLA = BigDecimal("7.5")

    /** The international silver nisab in the traditional unit: 52.5 tola (vori). See [GOLD_NISAB_TOLA]. */
    private val SILVER_NISAB_TOLA = BigDecimal("52.5")

    /**
     * The nisab weight in grams that applies for [nisabWeight], [metal] and [unit].
     *
     * For a **gram** unit the published constants are used unchanged. For a **tola or vori** unit under the
     * international weights, the threshold is the traditional figure — 7.5 tola of gold, 52.5 tola of
     * silver — multiplied by the configured [ZakatMetalUnit.gramsPerUnit], so a user who enters the exact
     * traditional nisab meets it rather than falling a fraction of a gram short. The Bangladesh weights are
     * published only in grams and stay as configured whatever unit is selected.
     *
     * Exact decimal arithmetic throughout: no `Double`, no `Float`, and no duplicated special case — every
     * caller reads the threshold through here.
     */
    private fun effectiveNisabGrams(
        nisabWeight: ZakatNisabWeight,
        metal: ZakatNisabMetal,
        unit: ZakatMetalUnit,
    ): BigDecimal = when {
        nisabWeight == ZakatNisabWeight.INTERNATIONAL && unit != ZakatMetalUnit.GRAM ->
            when (metal) {
                ZakatNisabMetal.GOLD -> GOLD_NISAB_TOLA.multiply(unit.gramsPerUnit)
                ZakatNisabMetal.SILVER -> SILVER_NISAB_TOLA.multiply(unit.gramsPerUnit)
            }

        metal == ZakatNisabMetal.GOLD -> nisabWeight.goldGrams
        else -> nisabWeight.silverGrams
    }

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

        val forcedMetal = nisabMetalFor(inputs, lines, gold, silver)
        val nisabMetalOverridden = forcedMetal != inputs.nisabMetal
        val nisabGrams = effectiveNisabGrams(inputs.nisabWeight, forcedMetal, inputs.metalUnit)
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
     *    screen can show the working;
     *  * **weight entered as zero ⇒ an included (or excluded) row worth exactly zero, with no price asked
     *    for.** A user who types `0` has answered the question: they hold none of this metal. Making the row
     *    incomplete because no price was also entered would report a question as unanswered when it was
     *    answered, and would stop a calculation that needs no price at all to be complete.
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

        // A zero weight is worth zero whatever the price is, so the price is not required and the row is not
        // incomplete. The exemption still applies, so a zero-weight holding of personal jewellery stays
        // excluded rather than being counted as a zero-valued included asset.
        if (grams.signum() == 0) {
            return ZakatLine(
                field = field,
                treatment = if (exemptAsPersonalJewellery) {
                    ZakatLineTreatment.EXCLUDED
                } else {
                    ZakatLineTreatment.INCLUDED
                },
                amount = ZakatMoney.ZERO,
                computation = ZakatComputation(
                    quantity = grams,
                    quantityUnit = ZakatQuantityUnit.GRAMS,
                    unitPrice = pricePerGram,
                    baseAmount = BigDecimal.ZERO,
                    basis = ZakatValuationBasis.USER_ENTERED,
                ),
            )
        }

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
     * An amount **entered as zero** is the exception: zero of any currency converts to zero at any rate, so
     * the row is included at zero and no rate is asked for. The user has answered the question, and asking
     * for a rate before accepting a "none held" answer would be a request rather than a correction.
     */
    private fun foreignCurrencyLine(inputs: ZakatInputs): ZakatLine? {
        val amount = inputs.foreignCurrencyAmount ?: return null

        if (amount.signum() == 0) {
            return ZakatLine(
                field = ZakatLineField.FOREIGN_CURRENCY,
                treatment = ZakatLineTreatment.INCLUDED,
                amount = ZakatMoney.ZERO,
                computation = ZakatComputation(
                    quantity = amount,
                    quantityUnit = ZakatQuantityUnit.OTHER_CURRENCY,
                    unitPrice = null,
                    baseAmount = BigDecimal.ZERO,
                    basis = ZakatValuationBasis.USER_ENTERED,
                ),
            )
        }

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
     *
     * The rule cannot be applied to a user who holds more than metal and metal alone: cash, a balance in
     * another currency, stock, a receivable or a liability that still needs a value all mean the wealth is
     * not *entirely* one metal, so [inputs]'s own [ZakatInputs.nisabMetal] is returned unchanged and the
     * user's selection is never overridden merely because a small quantity of the other metal exists.
     * Liabilities are never counted as "another asset" — a debt is what the total is reduced by, and reading
     * one as the holding that forces a different threshold would invert what it means (§4.5).
     */
    private fun nisabMetalFor(
        inputs: ZakatInputs,
        lines: List<ZakatLine>,
        gold: ZakatLine?,
        silver: ZakatLine?,
    ): ZakatNisabMetal {
        // A row that is neither a liability nor one of the two metals, and which either contributes an
        // amount or is waiting for a value, is wealth of another kind. Its presence means the assets are not
        // one metal alone, so the all-one-metal rule does not apply and the user's choice stands.
        val holdsAnotherZakatableAsset = lines.any { line ->
            !line.field.isLiability &&
                line.field != ZakatLineField.GOLD &&
                line.field != ZakatLineField.SILVER &&
                (line.isIncludedAndWorthSomething() || line.treatment == ZakatLineTreatment.INCOMPLETE)
        }
        if (holdsAnotherZakatableAsset) return inputs.nisabMetal

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

        if (inputs.foreignCurrencyAmount != null &&
            inputs.foreignCurrencyAmount.signum() > 0 &&
            inputs.foreignCurrencyRate == null
        ) {
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
