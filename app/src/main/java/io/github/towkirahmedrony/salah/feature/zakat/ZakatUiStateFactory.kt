package io.github.towkirahmedrony.salah.feature.zakat

import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.domain.calendar.CalendarFormatting
import io.github.towkirahmedrony.salah.core.domain.zakat.ZakatCalculator
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
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Turns the raw form into the screen's state, and the form into the engine's input.
 *
 * ## This class is the text-to-domain boundary, and deliberately the only one
 *
 * A user types text. The engine takes `BigDecimal`s and enums. Something has to be the place where one becomes
 * the other, and putting it here rather than in the view model means the whole conversion is testable without
 * a `ViewModel`, a coroutine or a device — and that the view model stays a holder of the form rather than a
 * place where parsing quietly accumulates.
 *
 * The conversion is **total**: no input can make it throw. Text that is not a non-negative decimal becomes a
 * `null` input *and* an [ZakatFieldState.isInvalid] flag, so the field is reported as wrong rather than as
 * zero. That is the engine's own "not answered vs. zero" distinction carried one layer further out.
 *
 * ## Why blank and invalid are different
 *
 * Blank means the user has not answered yet, and the engine treats it as absent — the row stays out of the
 * breakdown entirely. Invalid means the user typed something that is not a number, and the screen says so
 * against the field. Neither is silently read as zero.
 *
 * ## Amounts are formatted here, in the interface language's numerals
 *
 * Every amount goes through [CalendarFormatting], Salah's one rule for digits, so an English screen shows
 * Western numerals and a Bengali one shows Bengali. Currency amounts are written to two decimal places — the
 * scale the engine rounds the payable amount to — with **no thousands separator**: a separator is a
 * locale-specific character this project has not verified for every supported currency, and a wrong separator
 * is worse than none. The rate is formatted as a percentage, with the sign kept because it is a symbol rather
 * than a word.
 */
class ZakatUiStateFactory(
    private val formatting: CalendarFormatting = CalendarFormatting(),
) {

    /**
     * The state for one reading of the form.
     *
     * [today] is passed in rather than read here, so the valuation date is the caller's decision and a test can
     * pin it. Nothing in this class reads a clock.
     */
    fun create(form: ZakatForm, locale: Locale, today: LocalDate): ZakatUiState.Ready {
        val parsed = parse(form, today)
        val result = ZakatCalculator.calculate(parsed.inputs)

        return ZakatUiState.Ready(
            currencyChoices = ZakatInputs.SUPPORTED_CURRENCIES.map { code ->
                // No resource: an ISO code is written the same way in every language.
                ZakatChoice(value = code, labelRes = NO_LABEL_RESOURCE, selected = code == form.currency)
            },
            unitChoices = choices(ZakatMetalUnit.entries, form.unit) { unit ->
                when (unit) {
                    ZakatMetalUnit.GRAM -> R.string.zakat_unit_gram
                    ZakatMetalUnit.TOLA -> R.string.zakat_unit_tola
                    ZakatMetalUnit.VORI -> R.string.zakat_unit_vori
                }
            },
            nisabWeightChoices = choices(ZakatNisabWeight.entries, form.nisabWeight) { weight ->
                when (weight) {
                    ZakatNisabWeight.INTERNATIONAL -> R.string.zakat_nisab_international
                    ZakatNisabWeight.BANGLADESH -> R.string.zakat_nisab_bangladesh
                }
            },
            // The user's own choice, not the effective one: the override is reported separately, so the row
            // never silently moves under the user's finger.
            nisabMetalChoices = choices(ZakatNisabMetal.entries, form.nisabMetal) { metal ->
                when (metal) {
                    ZakatNisabMetal.GOLD -> R.string.zakat_nisab_gold
                    ZakatNisabMetal.SILVER -> R.string.zakat_nisab_silver
                }
            },
            jewelleryChoices = choices(ZakatJewelleryTreatment.entries, form.jewelleryTreatment) { treatment ->
                when (treatment) {
                    ZakatJewelleryTreatment.ZAKATABLE -> R.string.zakat_jewellery_zakatable
                    ZakatJewelleryTreatment.PERSONAL_USE_EXEMPT -> R.string.zakat_jewellery_exempt
                }
            },
            debtChoices = choices(ZakatDebtTreatment.entries, form.debtTreatment) { treatment ->
                when (treatment) {
                    ZakatDebtTreatment.DEDUCT -> R.string.zakat_debts_deduct
                    ZakatDebtTreatment.DO_NOT_DEDUCT -> R.string.zakat_debts_keep
                }
            },
            inventoryBasisChoices = choices(ZakatInventoryBasis.entries, form.inventoryBasis) { basis ->
                when (basis) {
                    ZakatInventoryBasis.MARKET_VALUE -> R.string.zakat_inventory_market
                    ZakatInventoryBasis.LANDED_COST -> R.string.zakat_inventory_landed
                }
            },
            nisabMetalOverridden = result.nisabMetalOverridden,
            forcedNisabMetalRes = if (result.nisabMetalOverridden) {
                when (result.nisabMetalUsed) {
                    ZakatNisabMetal.GOLD -> R.string.zakat_nisab_gold
                    ZakatNisabMetal.SILVER -> R.string.zakat_nisab_silver
                }
            } else {
                null
            },
            assetFields = ASSET_FIELDS.map { field -> fieldState(field, form, parsed.invalid) },
            liabilityFields = LIABILITY_FIELDS.map { field -> fieldState(field, form, parsed.invalid) },
            hawlDateText = form.hawlSeedDateText,
            hawlDateInvalid = form.hawlSeedDateText.isNotBlank() && parsed.hawlDate == null,
            result = resultView(result, locale),
            canClear = form.hasAnythingEntered(),
        )
    }

    // --- The form -> engine boundary -------------------------------------------------------------

    /** What the form parsed to, and which rows were unreadable. */
    private class ParsedForm(
        val inputs: ZakatInputs,
        val invalid: Set<ZakatFormField>,
        val hawlDate: LocalDate?,
    )

    private fun parse(form: ZakatForm, today: LocalDate): ParsedForm {
        val invalid = mutableSetOf<ZakatFormField>()

        fun amount(field: ZakatFormField): BigDecimal? {
            val text = form.text(field).trim()
            if (text.isEmpty()) return null
            val money = ZakatMoney.parse(text)
            if (money == null) {
                invalid += field
                return null
            }
            return money.amount
        }

        // The seed date is an ISO date. It is parsed rather than validated per keystroke so that a user can
        // type "2026-1" on the way to "2026-10-01" without the field reporting an error at every character.
        val hawlDate = form.hawlSeedDateText.trim().takeIf { text -> text.isNotEmpty() }?.let { text ->
            try {
                LocalDate.parse(text)
            } catch (_: DateTimeParseException) {
                null
            }
        }

        val inputs = ZakatInputs(
            currency = form.currency,
            cash = amount(ZakatFormField.CASH),
            foreignCurrencyAmount = amount(ZakatFormField.FOREIGN_CURRENCY),
            foreignCurrencyRate = amount(ZakatFormField.FOREIGN_RATE),
            goldWeight = amount(ZakatFormField.GOLD_WEIGHT),
            goldPricePerGram = amount(ZakatFormField.GOLD_PRICE),
            silverWeight = amount(ZakatFormField.SILVER_WEIGHT),
            silverPricePerGram = amount(ZakatFormField.SILVER_PRICE),
            metalUnit = form.unit,
            preciousMetalsArePersonalJewellery = form.preciousMetalsArePersonalJewellery,
            inventoryValue = amount(ZakatFormField.INVENTORY),
            inventoryBasis = form.inventoryBasis,
            collectibleReceivables = amount(ZakatFormField.COLLECTIBLE_RECEIVABLES),
            doubtfulReceivables = amount(ZakatFormField.DOUBTFUL_RECEIVABLES),
            deductibleLiabilities = amount(ZakatFormField.DEDUCTIBLE_LIABILITIES),
            longTermLiabilities = amount(ZakatFormField.LONG_TERM_LIABILITIES),
            nisabWeight = form.nisabWeight,
            nisabMetal = form.nisabMetal,
            jewelleryTreatment = form.jewelleryTreatment,
            debtTreatment = form.debtTreatment,
            hawlSeedDate = hawlDate,
            valuationDate = today,
        )
        return ParsedForm(inputs, invalid, hawlDate)
    }

    // --- The engine -> screen boundary -----------------------------------------------------------

    private fun resultView(result: ZakatResult, locale: Locale): ZakatResultView {
        val hasAnything = result.status != ZakatStatus.NOTHING_ENTERED

        return ZakatResultView(
            statusRes = when (result.status) {
                // A wealth below the nisab is a *completed* calculation whose answer is "nothing is due", so it
                // reports as a calculation and lets the below-the-nisab row say which way it went. There is no
                // separate word for it because no separate answer is being given.
                ZakatStatus.NOTHING_ENTERED -> R.string.zakat_status_nothing
                ZakatStatus.CALCULATED -> R.string.zakat_status_calculated
                ZakatStatus.ESTIMATE -> R.string.zakat_status_estimate
                ZakatStatus.BELOW_NISAB -> R.string.zakat_status_calculated
            },
            rows = result.lines.map { line ->
                ZakatResultRowView(
                    field = line.field,
                    labelRes = lineLabelFor(line.field),
                    amount = line.amount?.let { amount -> money(amount, locale) },
                    treatmentRes = when (line.treatment) {
                        ZakatLineTreatment.INCLUDED -> R.string.zakat_line_included
                        ZakatLineTreatment.EXCLUDED -> R.string.zakat_line_excluded
                        ZakatLineTreatment.INCOMPLETE -> R.string.zakat_line_incomplete
                    },
                    isDeduction = line.field == ZakatLineField.DEDUCTIBLE_LIABILITIES ||
                        line.field == ZakatLineField.LONG_TERM_LIABILITIES,
                )
            },
            totalAssets = if (hasAnything) money(result.totalEligibleAssets, locale) else null,
            deductions = if (hasAnything) money(result.totalDeductibleLiabilities, locale) else null,
            netWealth = if (hasAnything) money(result.netZakatableWealth, locale) else null,
            nisabGramsLabel = if (result.nisab == null) null else plain(result.nisabGrams, locale),
            nisabPriceLabel = result.nisabPricePerGram?.let { price -> plain(price, locale) },
            nisabAmount = result.nisab?.let { threshold -> money(threshold, locale) },
            metRes = result.nisabMet?.let { met ->
                if (met) R.string.zakat_result_over else R.string.zakat_result_under
            },
            due = result.zakatDue?.let { amount -> money(amount, locale) },
            rateLabel = percent(result.zakatRate, locale),
            incompleteLabels = result.incompleteFields.map { field -> lineLabelFor(field) },
            hawlEstimateDate = result.hawl?.let { hawl ->
                formatting.gregorianDayMonthYear(hawl.estimatedAnniversary, locale)
            },
            hawlEstimateDays = result.hawl?.let { hawl ->
                formatting.numerals(hawl.approximateYearDays, locale)
            },
            showsPriceNote = hasAnything && result.dependsOnUserSuppliedPrice,
        )
    }

    // --- Small helpers --------------------------------------------------------------------------

    private fun fieldState(
        field: ZakatFormField,
        form: ZakatForm,
        invalid: Set<ZakatFormField>,
    ): ZakatFieldState = ZakatFieldState(
        field = field,
        labelRes = labelFor(field),
        helpRes = helpFor(field),
        value = form.text(field),
        isInvalid = field in invalid,
    )

    private fun <T> choices(values: List<T>, selected: T, labelRes: (T) -> Int): List<ZakatChoice<T>> =
        values.map { value -> ZakatChoice(value = value, labelRes = labelRes(value), selected = value == selected) }

    /** [amount] written to two decimal places, in the interface language's numerals. */
    private fun money(amount: ZakatMoney, locale: Locale): String =
        formatting.localizeDigits(amount.roundedToCurrency().toPlainString(), locale)

    /** [value] written as it stands, without trailing zeros, in the interface language's numerals. */
    private fun plain(value: BigDecimal, locale: Locale): String =
        formatting.localizeDigits(value.stripTrailingZeros().toPlainString(), locale)

    /** [rate] as a percentage. The sign is appended because it is a symbol, not a translatable word. */
    private fun percent(rate: BigDecimal, locale: Locale): String =
        formatting.localizeDigits(rate.movePointRight(2).stripTrailingZeros().toPlainString(), locale) + PERCENT_SIGN

    /**
     * The label of a **form** row.
     *
     * The one place a field's enum becomes a resource, so a label cannot drift between the field, the
     * breakdown row and the "still needed" list — all three read this.
     */
    private fun labelFor(field: ZakatFormField): Int = when (field) {
        ZakatFormField.CASH -> R.string.zakat_field_cash
        ZakatFormField.FOREIGN_CURRENCY -> R.string.zakat_field_foreign_amount
        ZakatFormField.FOREIGN_RATE -> R.string.zakat_field_foreign_rate
        ZakatFormField.GOLD_WEIGHT -> R.string.zakat_field_gold_weight
        ZakatFormField.GOLD_PRICE -> R.string.zakat_field_gold_price
        ZakatFormField.SILVER_WEIGHT -> R.string.zakat_field_silver_weight
        ZakatFormField.SILVER_PRICE -> R.string.zakat_field_silver_price
        ZakatFormField.INVENTORY -> R.string.zakat_field_inventory
        ZakatFormField.COLLECTIBLE_RECEIVABLES -> R.string.zakat_field_collectible
        ZakatFormField.DOUBTFUL_RECEIVABLES -> R.string.zakat_field_doubtful
        ZakatFormField.DEDUCTIBLE_LIABILITIES -> R.string.zakat_field_deductible
        ZakatFormField.LONG_TERM_LIABILITIES -> R.string.zakat_field_long_term
    }

    /** The help line for a form row, or `null` where the label says everything. */
    private fun helpFor(field: ZakatFormField): Int? = when (field) {
        ZakatFormField.CASH -> R.string.zakat_help_cash
        ZakatFormField.FOREIGN_CURRENCY -> R.string.zakat_help_foreign
        ZakatFormField.FOREIGN_RATE -> null
        ZakatFormField.GOLD_WEIGHT -> R.string.zakat_help_metals
        ZakatFormField.GOLD_PRICE -> null
        ZakatFormField.SILVER_WEIGHT -> null
        ZakatFormField.SILVER_PRICE -> null
        ZakatFormField.INVENTORY -> null
        ZakatFormField.COLLECTIBLE_RECEIVABLES -> R.string.zakat_help_collectible
        ZakatFormField.DOUBTFUL_RECEIVABLES -> R.string.zakat_help_doubtful
        ZakatFormField.DEDUCTIBLE_LIABILITIES -> R.string.zakat_help_deductible
        ZakatFormField.LONG_TERM_LIABILITIES -> R.string.zakat_help_long_term
    }

    /** The label of a **result** row, mapped onto the form label it shares. */
    private fun lineLabelFor(field: ZakatLineField): Int = when (field) {
        ZakatLineField.CASH -> labelFor(ZakatFormField.CASH)
        ZakatLineField.FOREIGN_CURRENCY -> labelFor(ZakatFormField.FOREIGN_CURRENCY)
        ZakatLineField.GOLD -> labelFor(ZakatFormField.GOLD_WEIGHT)
        ZakatLineField.SILVER -> labelFor(ZakatFormField.SILVER_WEIGHT)
        ZakatLineField.INVENTORY -> labelFor(ZakatFormField.INVENTORY)
        ZakatLineField.COLLECTIBLE_RECEIVABLES -> labelFor(ZakatFormField.COLLECTIBLE_RECEIVABLES)
        ZakatLineField.DOUBTFUL_RECEIVABLES -> labelFor(ZakatFormField.DOUBTFUL_RECEIVABLES)
        ZakatLineField.DEDUCTIBLE_LIABILITIES -> labelFor(ZakatFormField.DEDUCTIBLE_LIABILITIES)
        ZakatLineField.LONG_TERM_LIABILITIES -> labelFor(ZakatFormField.LONG_TERM_LIABILITIES)
    }

    companion object {
        /** The asset rows, in the order the screen presents them. */
        val ASSET_FIELDS: List<ZakatFormField> = listOf(
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
        )

        /** The liability rows. Two, and only the first can reduce the total — see §4.5. */
        val LIABILITY_FIELDS: List<ZakatFormField> = listOf(
            ZakatFormField.DEDUCTIBLE_LIABILITIES,
            ZakatFormField.LONG_TERM_LIABILITIES,
        )

        /** The marker for an option whose label is a code rather than a translated word. */
        const val NO_LABEL_RESOURCE: Int = 0

        /** The sign that makes a rate a percentage. */
        const val PERCENT_SIGN: String = "%"
    }
}
