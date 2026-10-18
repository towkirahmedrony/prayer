package io.github.towkirahmedrony.salah.feature.zakat

import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.domain.calendar.CalendarFormatting
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceFailure
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceFreshness
import io.github.towkirahmedrony.salah.core.domain.metalprice.ReferencePriceResult
import io.github.towkirahmedrony.salah.core.domain.metalprice.ReferencePrices
import io.github.towkirahmedrony.salah.core.domain.zakat.ZakatCalculator
import io.github.towkirahmedrony.salah.core.model.ZakatDebtTreatment
import io.github.towkirahmedrony.salah.core.model.ZakatInputs
import io.github.towkirahmedrony.salah.core.model.ZakatInventoryBasis
import io.github.towkirahmedrony.salah.core.model.ZakatJewelleryTreatment
import io.github.towkirahmedrony.salah.core.model.ZakatLineField
import io.github.towkirahmedrony.salah.core.model.ZakatLineTreatment
import io.github.towkirahmedrony.salah.core.model.ZakatMetal
import io.github.towkirahmedrony.salah.core.model.ZakatMetalUnit
import io.github.towkirahmedrony.salah.core.model.ZakatMoney
import io.github.towkirahmedrony.salah.core.model.ZakatNisabMetal
import io.github.towkirahmedrony.salah.core.model.ZakatNisabWeight
import io.github.towkirahmedrony.salah.core.model.ZakatResult
import io.github.towkirahmedrony.salah.core.model.ZakatStatus
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
     *
     * [referencePrices] and [manualPriceFields] are how a published gold or silver rate reaches the calculator
     * without ever being persisted: a price field the user has not touched is filled from the published record
     * for this reading only. [manualPriceFields] is the session's own record of which price fields the user
     * typed into, so a published rate never overwrites a figure the user chose — see [ZakatForm].
     */
    fun create(
        form: ZakatForm,
        locale: Locale,
        today: LocalDate,
        referencePrices: ReferencePrices = ReferencePrices.notPublished(),
        manualPriceFields: Set<ZakatFormField> = emptySet(),
        refreshingReferencePrices: Boolean = false,
    ): ZakatUiState.Ready {
        // The form as the engine and the screen see it: the user's own entries, with a published reference
        // price standing in for a price field the user has not typed into. Both the calculation and every
        // displayed value read the merged form, so the two can never disagree about which price is in force.
        val effectiveForm = form.mergedWithReferencePrices(referencePrices, manualPriceFields)
        val parsed = parse(effectiveForm, today)
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
            // The **metal** held, not the nisab it selects: the sentence is "you hold only gold, so its nisab
            // is applied", and naming "gold nisab" in that slot would read as "you hold only the gold nisab".
            forcedNisabMetalRes = if (result.nisabMetalOverridden) {
                metalLabelFor(
                    when (result.nisabMetalUsed) {
                        ZakatNisabMetal.GOLD -> ZakatMetal.GOLD
                        ZakatNisabMetal.SILVER -> ZakatMetal.SILVER
                    },
                )
            } else {
                null
            },
            assetFields = ASSET_FIELDS.map { field -> fieldState(field, effectiveForm, parsed.invalid) },
            liabilityFields = LIABILITY_FIELDS.map { field -> fieldState(field, effectiveForm, parsed.invalid) },
            // The field is a controlled input, so the text shown is exactly what the user typed.
            hawlDateText = form.hawlSeedDateText,
            hawlDateInvalid = isHawlDateInvalid(ZakatDigits.toWestern(form.hawlSeedDateText), parsed.hawlDate),
            result = resultView(result, locale),
            referencePrices = referencePricesView(referencePrices, manualPriceFields, locale),
            refreshingReferencePrices = refreshingReferencePrices,
            canClear = form.hasAnythingEntered(),
            assetCategories = buildAssetCategories(result, parsed.invalid) { amount -> money(amount, locale) },
            debts = buildDebtsView(effectiveForm, result) { amount -> money(amount, locale) },
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
        // Bengali and Arabic-Indic digits are rewritten to Western ones first, so a user typing their own
        // numerals is not told their date is unreadable because of the script their keyboard produces.
        val hawlDate = ZakatDigits.toWestern(form.hawlSeedDateText).trim()
            .takeIf { text -> text.isNotEmpty() }
            ?.let { text ->
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
            // Choosing the majority ruling is the statement that the metals entered are personal jewellery rather
            // than savings — that is what the option's own label says, and Salah offers no separate switch for it.
            // The engine takes the two facts apart so a future screen could; this one derives one from the other.
            preciousMetalsArePersonalJewellery =
                form.jewelleryTreatment == ZakatJewelleryTreatment.PERSONAL_USE_EXEMPT,
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
                ZakatStatus.NOTHING_ENTERED -> R.string.zakat_status_nothing
                ZakatStatus.CALCULATED -> R.string.zakat_status_calculated
                ZakatStatus.ESTIMATE -> R.string.zakat_status_estimate
                // A wealth below the nisab is a *completed* calculation whose answer is that no payment is due
                // under the rules in force. That answer is distinct from both "calculated an amount" and "an
                // estimate waiting for a value", so it has its own word rather than borrowing either.
                ZakatStatus.BELOW_NISAB -> R.string.zakat_status_below_nisab
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
            // A missing price is named as a missing **price**, and a missing rate as a missing **rate**: the
            // row's own weight label would say "gold weight" when the weight was typed and it is the price that
            // is absent, which points the user at the wrong field.
            incompleteLabels = result.incompleteFields.map { field -> incompleteLabelFor(field) },
            hawlEstimateDate = result.hawl?.let { hawl ->
                formatting.gregorianDayMonthYear(hawl.estimatedAnniversary, locale)
            },
            hawlEstimateDays = result.hawl?.let { hawl ->
                formatting.numerals(hawl.approximateYearDays, locale)
            },
            showsPriceNote = hasAnything && result.dependsOnUserSuppliedPrice,
            currency = result.currency,
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

    /**
     * The label of the **metal** a result row is about — gold or silver, as a substance.
     *
     * Distinct from [labelFor] applied to the metal's weight or price field: those name an *input*, and this
     * names the metal itself. Reusing the nisab labels here is the mapping mistake these tests exist for —
     * "you hold only gold nisab" is not a sentence about a holding.
     */
    private fun metalLabelFor(metal: ZakatMetal): Int = when (metal) {
        ZakatMetal.GOLD -> R.string.zakat_metal_gold
        ZakatMetal.SILVER -> R.string.zakat_metal_silver
    }

    /**
     * The label for a field the calculation still needs, named for what is actually missing.
     *
     * A missing gold price is reported against the **price** field, not the weight: the weight was typed and
     * the price was not, so naming the weight sends the user to a field that is already correct. The same
     * applies to a missing silver price and to a missing foreign-currency rate.
     */
    private fun incompleteLabelFor(field: ZakatLineField): Int = when (field) {
        ZakatLineField.FOREIGN_CURRENCY -> labelFor(ZakatFormField.FOREIGN_RATE)
        ZakatLineField.GOLD -> labelFor(ZakatFormField.GOLD_PRICE)
        ZakatLineField.SILVER -> labelFor(ZakatFormField.SILVER_PRICE)
        else -> lineLabelFor(field)
    }

    /**
     * True when the Hawl date text is neither empty, nor a date that can be read, nor a date still being typed.
     *
     * The third case is the one that matters: a user types `2026-10-01` one character at a time, and every
     * prefix of it before the last digit is not a date. Reporting an error at each of those keystrokes would
     * flash a validation message over a field the user is in the middle of filling in, and would make the field
     * look wrong on the way to being right. A partial ISO date is therefore not an error while it is shorter
     * than a completed date and still shaped like one.
     *
     * Anything else — a completed-looking date that does not parse, or free text such as `next Ramadan` — is an
     * error, because it is neither complete nor on its way to being complete.
     */
    private fun isHawlDateInvalid(
        rawText: String,
        parsedDate: LocalDate?,
    ): Boolean {
        val trimmed = rawText.trim()

        if (trimmed.isEmpty() || parsedDate != null) {
            return false
        }

        val partialIsoPattern =
            Regex("""^\d{1,4}(-\d{0,2}(-\d{0,2})?)?$""")

        if (trimmed.length < 10 && partialIsoPattern.matches(trimmed)) {
            return false
        }

        return true
    }

    /**
     * The published reference prices as the screen shows them, gold first and silver second.
     *
     * Each metal is rendered from its own result, so one metal having no published rate or a conflicting one
     * changes nothing about the other. Nothing here invents a source, a timestamp or a price: a label is only
     * produced from a field the record actually carries.
     */
    private fun referencePricesView(
        referencePrices: ReferencePrices,
        manualPriceFields: Set<ZakatFormField>,
        locale: Locale,
    ): List<ZakatReferencePriceState> = listOf(
        referencePriceState(
            metal = ZakatMetal.GOLD,
            priceField = ZakatFormField.GOLD_PRICE,
            result = referencePrices.gold,
            manualPriceFields = manualPriceFields,
            refreshFailure = referencePrices.refreshFailure,
            locale = locale,
        ),
        referencePriceState(
            metal = ZakatMetal.SILVER,
            priceField = ZakatFormField.SILVER_PRICE,
            result = referencePrices.silver,
            manualPriceFields = manualPriceFields,
            refreshFailure = referencePrices.refreshFailure,
            locale = locale,
        ),
    )

    private fun referencePriceState(
        metal: ZakatMetal,
        priceField: ZakatFormField,
        result: ReferencePriceResult,
        manualPriceFields: Set<ZakatFormField>,
        refreshFailure: MetalPriceFailure?,
        locale: Locale,
    ): ZakatReferencePriceState {
        val available = result as? ReferencePriceResult.Available
        // A failure is reported against the rate it actually affects. A current gold rate must not be captioned
        // "the last check failed" because silver's row was unusable: the silver card says that already.
        val failureForThisMetal = refreshFailure.takeIf {
            available == null || available.freshness != MetalPriceFreshness.CURRENT
        }
        val manuallyEdited = priceField in manualPriceFields
        return ZakatReferencePriceState(
            metal = metal,
            metalRes = metalLabelFor(metal),
            priceField = priceField,
            status = when (result) {
                ReferencePriceResult.Loading -> ZakatReferencePriceStatus.LOADING
                is ReferencePriceResult.Available -> when (result.freshness) {
                    MetalPriceFreshness.CURRENT -> ZakatReferencePriceStatus.AVAILABLE
                    MetalPriceFreshness.CACHED -> ZakatReferencePriceStatus.CACHED
                    MetalPriceFreshness.STALE -> ZakatReferencePriceStatus.STALE
                }
                ReferencePriceResult.NotPublished -> ZakatReferencePriceStatus.UNAVAILABLE
                is ReferencePriceResult.Conflicting -> ZakatReferencePriceStatus.FAILED
                is ReferencePriceResult.Failed -> ZakatReferencePriceStatus.FAILED
            },
            statusRes = when (result) {
                ReferencePriceResult.Loading -> R.string.zakat_price_status_loading
                is ReferencePriceResult.Available -> when (result.freshness) {
                    MetalPriceFreshness.CURRENT -> R.string.zakat_price_status_available
                    MetalPriceFreshness.CACHED -> R.string.zakat_price_status_cached
                    MetalPriceFreshness.STALE -> R.string.zakat_price_status_stale
                }
                ReferencePriceResult.NotPublished -> R.string.zakat_price_status_unavailable
                is ReferencePriceResult.Conflicting -> R.string.zakat_price_status_conflict
                is ReferencePriceResult.Failed -> R.string.zakat_price_status_failed
            },
            // The unit is carried as a resource and a code rather than as a joined sentence: composing "BDT
            // per gram" here would fix the English word order into a Bengali screen, and this class has no
            // context with which to read a translated string. The screen joins the two.
            unitLabelRes = R.string.zakat_price_unit,
            currencyCode = available?.price?.currency ?: ZakatInputs.DEFAULT_CURRENCY,
            pricePerGram = available?.price?.pricePerGram?.let { price -> plain(price, locale) },
            purityLabel = available?.price?.purity?.name,
            sourceLabel = available?.price?.source,
            effectiveLabel = available?.price?.effectiveAt?.let { raw -> timestampLabel(raw, locale) },
            providerUpdatedLabel = available?.price?.providerLastUpdate?.let { raw -> timestampLabel(raw, locale) },
            fetchedLabel = available?.price?.fetchedAt?.let { raw -> timestampLabel(raw, locale) },
            updatedLabel = available?.price?.updatedAt?.let { raw -> timestampLabel(raw, locale) },
            // Only labelled when it adds something: a rate fetched by this screen is simply current, while a
            // saved copy (or a saved copy shown after a failed check) has to say when it was stored.
            lastRefreshLabel = available
                ?.takeIf { price -> price.freshness != MetalPriceFreshness.CURRENT || failureForThisMetal != null }
                ?.cachedAtEpochMillis
                ?.let { epoch -> epochDateLabel(epoch, locale) },
            messageRes = messageFor(result, failureForThisMetal),
            manuallyEdited = manuallyEdited,
            canRestoreReference = manuallyEdited && available != null,
        )
    }

    /**
     * The explanation for a state that is not a current, successful published rate, or `null` when it is.
     *
     * A saved rate is never silent about its age: [MetalPriceFreshness.CACHED] says it is the stored copy,
     * [MetalPriceFreshness.STALE] says it is old enough to check, and a failed refresh beside a usable saved
     * rate says which of the two is being shown and why.
     */
    private fun messageFor(result: ReferencePriceResult, refreshFailure: MetalPriceFailure?): Int? = when (result) {
        ReferencePriceResult.Loading -> null
        is ReferencePriceResult.Available -> when {
            refreshFailure != null -> R.string.zakat_price_refresh_failed_saved
            result.freshness == MetalPriceFreshness.STALE -> R.string.zakat_price_stale_note
            result.freshness == MetalPriceFreshness.CACHED -> R.string.zakat_price_cached_note
            else -> null
        }
        ReferencePriceResult.NotPublished -> R.string.zakat_price_unavailable
        is ReferencePriceResult.Conflicting -> R.string.zakat_price_conflict
        is ReferencePriceResult.Failed -> when (result.failure) {
            MetalPriceFailure.NOT_CONFIGURED -> R.string.zakat_price_not_configured
            MetalPriceFailure.TABLE_UNAVAILABLE -> R.string.zakat_price_table_unavailable
            MetalPriceFailure.ACCESS_DENIED -> R.string.zakat_price_access_denied
            MetalPriceFailure.MALFORMED,
            MetalPriceFailure.INVALID_DATA,
            -> R.string.zakat_price_invalid

            // A duplicate pair is the ambiguity itself, not an unreadable row: it gets the conflict message
            // whether it is reported per metal or, with no cache to fall back to, for the whole read.
            MetalPriceFailure.CONFLICTING -> R.string.zakat_price_conflict

            MetalPriceFailure.TIMEOUT,
            MetalPriceFailure.CONNECTION,
            MetalPriceFailure.NETWORK,
            MetalPriceFailure.SERVER,
            MetalPriceFailure.RESPONSE_TOO_LARGE,
            -> R.string.zakat_price_retrieval_failed
        }
    }

    /**
     * [epochMillis] as the civil date it fell on in the device's zone, in the interface language's numerals.
     *
     * Only the date is shown: the cache records when *this device* completed a check, and the time of day adds
     * no information a Zakat user needs while making the source of the figure unmistakable.
     */
    private fun epochDateLabel(epochMillis: Long, locale: Locale): String {
        val date = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        return formatting.gregorianDayMonthYear(date, locale)
    }

    /**
     * [raw] as a readable date when it is an ISO timestamp, or the text itself when it is not.
     *
     * A published record's timestamps are ISO-8601 strings and may carry a time and an offset. Only the date
     * part is shown, in the interface language's numerals, because the app has no way to establish which
     * timezone a provider meant and a bare date is the honest reading of what the record states. A value that
     * is not a date at all is shown as it arrived rather than reformatted into something it is not.
     */
    private fun timestampLabel(raw: String, locale: Locale): String {
        val trimmed = raw.trim()
        val date = try {
            LocalDate.parse(trimmed.take(ISO_DATE_LENGTH))
        } catch (_: DateTimeParseException) {
            null
        }
        return if (date == null) {
            formatting.localizeDigits(trimmed, locale)
        } else {
            formatting.gregorianDayMonthYear(date, locale)
        }
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

        /**
         * How many leading characters of a timestamp are its date.
         *
         * `YYYY-MM-DD` is ten characters, and the rest of an ISO-8601 timestamp is the time and its offset —
         * which the app does not show, because it cannot establish the zone the publisher meant.
         */
        const val ISO_DATE_LENGTH: Int = 10
    }
}
