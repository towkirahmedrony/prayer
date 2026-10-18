package io.github.towkirahmedrony.salah.feature.zakat

import androidx.annotation.StringRes
import io.github.towkirahmedrony.salah.core.model.ZakatDebtTreatment
import io.github.towkirahmedrony.salah.core.model.ZakatInventoryBasis
import io.github.towkirahmedrony.salah.core.model.ZakatJewelleryTreatment
import io.github.towkirahmedrony.salah.core.model.ZakatInputs
import io.github.towkirahmedrony.salah.core.model.ZakatLineField
import io.github.towkirahmedrony.salah.core.model.ZakatMetal
import io.github.towkirahmedrony.salah.core.model.ZakatMetalUnit
import io.github.towkirahmedrony.salah.core.model.ZakatNisabMetal
import io.github.towkirahmedrony.salah.core.model.ZakatNisabWeight

/**
 * What the Zakat screen renders.
 *
 * ## Where the sentences live
 *
 * Nothing here is a sentence. A row carries a **resource id** and an amount carries a **formatted value**, so
 * the composable resolves the words and this layer only decides which words apply. That is the same split the
 * counter and Home use, and it is what lets the whole state be asserted in a unit test with no device.
 *
 * ## What identifies a row
 *
 * A row's identity is an enum — [ZakatFormField] for form rows, [ZakatLineField] for result rows — never a
 * translated label. Resource ids are for display only. A label as an identifier would break the moment the
 * interface language changed, which is the mistake the brief names.
 *
 * ## Two enums, because there are two different sets
 *
 * The form has twelve rows and the calculation has nine, and they are not the same rows: a metal's **weight**
 * and its **price** are two inputs but one result line, and a foreign amount and its rate are two inputs but
 * one line. Modelling them as one enum would force a price to be a line the engine does not have, so the form
 * has its own identifier and maps onto the result's where a match exists.
 */
sealed interface ZakatUiState {

    /** The interface-language preference has not been read yet, so no state can be produced. */
    data object Loading : ZakatUiState

    /** The form and its result. */
    data class Ready(
        val currencyChoices: List<ZakatChoice<String>>,
        val unitChoices: List<ZakatChoice<ZakatMetalUnit>>,
        val nisabWeightChoices: List<ZakatChoice<ZakatNisabWeight>>,
        val nisabMetalChoices: List<ZakatChoice<ZakatNisabMetal>>,
        val jewelleryChoices: List<ZakatChoice<ZakatJewelleryTreatment>>,
        val debtChoices: List<ZakatChoice<ZakatDebtTreatment>>,
        val inventoryBasisChoices: List<ZakatChoice<ZakatInventoryBasis>>,
        /** The user's own metal choice, which the result may have overridden. */
        val nisabMetalOverridden: Boolean,
        /**
         * The label of the metal whose nisab was forced, or `null`.
         *
         * Carried as a resource id so the screen can say *which* metal forced the change without doing the
         * lookup itself — the rule is in the engine, and the screen only reports it.
         */
        @StringRes val forcedNisabMetalRes: Int?,
        /** The asset rows, in the order the screen presents them. */
        val assetFields: List<ZakatFieldState>,
        /** The liability rows. */
        val liabilityFields: List<ZakatFieldState>,
        /**
         * The seed date text, so the field is a controlled input.
         *
         * Exactly what the user typed except for its digits, which are rewritten to Western numerals as they
         * are entered — a Bengali or Arabic keyboard produces digits `LocalDate` cannot read, and the field
         * shows the date in a form that can be parsed while keeping every separator and letter the user typed.
         */
        val hawlDateText: String,
        /** True when [hawlDateText] is neither empty nor a date that can be read. */
        val hawlDateInvalid: Boolean,
        val result: ZakatResultView,
        /**
         * The published gold and silver reference prices, and whether each could be used.
         *
         * Always two entries — gold first, then silver — so the screen renders a stable pair whatever the
         * availability, rather than a list whose shape changes with the network.
         */
        val referencePrices: List<ZakatReferencePriceState>,
        /** True while a reference-price retrieval is in flight, so the refresh control can say so. */
        val refreshingReferencePrices: Boolean,
        /** True when something has been entered, so "clear" has something to clear. */
        val canClear: Boolean,
        /** The five asset groups with their status and share of the counted total. */
        val assetCategories: List<ZakatAssetCategoryState> = emptyList(),
        /** The debts step: the entries, their status, and how they move the total. */
        val debts: ZakatDebtsView = ZakatDebtsView(),
        /** Arithmetic beside the engine's result: the nisab meter, and the other debt setting's outcome. */
        val resultInsight: ZakatResultInsight? = null,
    ) : ZakatUiState
}

/**
 * How far one metal's published reference price has got.
 *
 * The retrieval and the saved copy are separate facts, and both are stated: a rate can be available *and*
 * saved from an earlier check. Collapsing those into one state would either present a week-old rate as live or
 * hide a perfectly usable one.
 */
enum class ZakatReferencePriceStatus {
    /** The retrieval is in flight. */
    LOADING,

    /** A published, verified rate fetched within the current window. */
    AVAILABLE,

    /** A published rate restored from the local cache, fetched earlier than the current window. */
    CACHED,

    /** A cached rate older than the stale threshold. Still usable, but labelled as possibly out of date. */
    STALE,

    /** The service answered, and has no rate this app may use. */
    UNAVAILABLE,

    /** The rate could not be retrieved or could not be used. */
    FAILED,
}

/**
 * One metal's published reference price, as the screen renders it.
 *
 * [pricePerGram] and the unit are carried separately for the same reason the result's amounts are: the number
 * is formatted by one rule and the unit is a phrase by another. [unitLabelRes] holds the phrase and
 * [currencyCode] the code the record itself states. [sourceLabel], [effectiveLabel], [providerUpdatedLabel],
 * [fetchedLabel] and [updatedLabel] each say what their value actually is — a source name, a reviewed date, a
 * provider's own update, or merely when the record was retrieved or last changed — and none of them is
 * presented as proof that a market price was live at that moment.
 *
 * [manuallyEdited] is true when the user has typed their own value into the price field this session; the
 * screen shows that the published rate is not the figure in force, and offers to restore it.
 */
data class ZakatReferencePriceState(
    val metal: ZakatMetal,
    @StringRes val metalRes: Int,
    /** The price field this reference rate stands in for, so the screen can act on it. */
    val priceField: ZakatFormField,
    val status: ZakatReferencePriceStatus,
    @StringRes val statusRes: Int,
    /** The resource that words "_ per gram", so the unit of the figure is never left implicit. */
    @StringRes val unitLabelRes: Int,
    /** The currency code the price is stated in, taken from the record rather than assumed. */
    val currencyCode: String,
    /** The reference price per gram, formatted, or `null` when there is no usable published rate. */
    val pricePerGram: String?,
    /**
     * The purity category the rate is stated in, when a rate is shown.
     *
     * Carried as the contract's own code (`K22`, …) rather than translated: a karat is a unit, and the
     * published record states it in that form. It is shown beside the price so "BDT per gram" is never read
     * without knowing *which* gold's gram it prices.
     */
    val purityLabel: String?,
    /** The organisation the price came from, when the record names one. Never invented. */
    val sourceLabel: String?,
    /** The reviewed/effective date, when the record records one. */
    val effectiveLabel: String?,
    /** The provider's own last-update statement, when the record has one. */
    val providerUpdatedLabel: String?,
    /** When the record was fetched, when the record has one. */
    val fetchedLabel: String?,
    /** When the record was last updated, when the record has one. */
    val updatedLabel: String?,
    /**
     * When this copy was last fetched and saved on the device, when it came from the cache.
     *
     * Distinct from every label above: those are statements made by the published record, while this is a
     * statement about this device's own successful check. `null` for a rate fetched just now by this screen.
     */
    val lastRefreshLabel: String?,
    /**
     * The explanation to show when the rate cannot simply be used as it stands.
     *
     * That covers every state that is not a current, successful published rate *and* the one current rate that
     * is published in another currency. `null` means the rate is current and usable, so there is nothing to
     * explain.
     */
    @StringRes val messageRes: Int?,
    /**
     * True when this rate is stated in the currency the calculation is denominated in.
     *
     * A published rate is BDT per gram; the form may be in any supported currency. When the two differ the
     * rate is still a real published rate and is still shown, with its own currency code, but it **must not**
     * stand in for the price field: doing so would apply a taka figure as if it were dollars. False means the
     * field is left empty and manual entry is offered instead.
     */
    val applicableToCalculation: Boolean,

    /** True when the user has typed their own price for this metal this session. */
    val manuallyEdited: Boolean,
    /** True when the offered control should be "use the published reference price". */
    val canRestoreReference: Boolean,
)

/**
 * The rows a user can type into.
 *
 * Twelve inputs, which is more than the nine the calculation reports: see the note on [ZakatUiState].
 */
enum class ZakatFormField {
    CASH,
    FOREIGN_CURRENCY,
    FOREIGN_RATE,
    GOLD_WEIGHT,
    GOLD_PRICE,
    SILVER_WEIGHT,
    SILVER_PRICE,
    INVENTORY,
    COLLECTIBLE_RECEIVABLES,
    DOUBTFUL_RECEIVABLES,
    DEDUCTIBLE_LIABILITIES,
    LONG_TERM_LIABILITIES,
}

/**
 * One option in a group.
 *
 * [value] is the domain choice and [selected] says whether it is in force, so the state is a plain description
 * of what to draw rather than something the composable has to work out.
 *
 * [labelRes] is `0` for an option whose label is a **code** rather than a word — a currency is written
 * `BDT` in every language, and giving the eight ISO codes translated resources would mean eight strings per
 * language that are identical in both files. The screen renders [value] when there is no resource, so the code
 * still comes from data and never from a literal in a composable.
 */
data class ZakatChoice<T>(
    val value: T,
    @StringRes val labelRes: Int,
    val selected: Boolean,
) {
    /** True when this option's label is its value, because it has no translated form. */
    val labelIsValue: Boolean get() = labelRes == 0
}

/**
 * One editable numeric row.
 *
 * [isInvalid] is about the **text**, not about the calculation: it is true when what was typed is not a
 * non-negative decimal at all, which is a different thing from a value the calculator still needs. A field can
 * be valid and empty, and valid and incomplete in the result — the two are separate because the messages
 * differ: "that is not a number" is a correction, "a price is still needed" is a request.
 */
data class ZakatFieldState(
    val field: ZakatFormField,
    @StringRes val labelRes: Int,
    @StringRes val helpRes: Int?,
    /** Exactly what the user typed, so the field is controlled and nothing is reformatted under them. */
    val value: String,
    val isInvalid: Boolean,
)

/**
 * The calculation, as far as the screen can show it.
 *
 * The amounts are `null` when they do not apply rather than `"0"`: a deduction of zero in a calculation that
 * does not deduct is not the same statement as one the user entered, and the screen must be able to tell them
 * apart.
 *
 * The pairs of labels — [nisabGramsLabel] with [nisabPriceLabel], [hawlEstimateDate] with [hawlEstimateDays] —
 * are carried separately rather than pre-joined, because joining them would put a sentence's word order into
 * this class. The composable passes them to the resource that orders them, so a language that needs them the
 * other way round gets them the other way round.
 */
data class ZakatResultView(
    /** Which of the three states the result is in: nothing entered, an estimate, or a calculation. */
    @StringRes val statusRes: Int,
    val rows: List<ZakatResultRowView>,
    val totalAssets: String?,
    val deductions: String?,
    val netWealth: String?,
    /** The nisab weight as published, or `null`. */
    val nisabGramsLabel: String?,
    /** The price the nisab was valued at, or `null`. */
    val nisabPriceLabel: String?,
    val nisabAmount: String?,
    /** Whether the threshold is met, or `null` when it could not be computed. */
    @StringRes val metRes: Int?,
    /** The amount payable, or `null` when none is due or the calculation is incomplete. */
    val due: String?,
    /** The rate, localised, as it should read beside the amount. */
    val rateLabel: String,
    /** Labels of the rows that still need a value, for the "still needed" list. */
    val incompleteLabels: List<Int>,
    /** The estimated anniversary, or `null` when no seed date was given. */
    val hawlEstimateDate: String?,
    /** How many days the estimate assumes, or `null`. */
    val hawlEstimateDays: String?,
    /**
     * True when the result rests on a price the user supplied — which is whenever a threshold exists at all,
     * because the nisab is a weight times a price. Drives the note that says so, so a result never reads as if
     * Salah knew a price.
     */
    val showsPriceNote: Boolean,
    /**
     * The currency the amounts are in, as an ISO code.
     *
     * Carried so the screen can state **which** currency the figures beside it are in without appending it
     * to the formatted amounts themselves: the amount text is the number, unchanged by the code, so an
     * amount a test pins stays the amount it pins.
     */
    val currency: String = ZakatInputs.DEFAULT_CURRENCY,
)

/**
 * One row of the breakdown.
 *
 * [treatmentRes] says counted, not counted, or needs a value; [amount] is present even for a row that is not
 * counted, where it is the amount *not* counted, so a user can see what a ruling they chose removed.
 */
data class ZakatResultRowView(
    val field: ZakatLineField,
    @StringRes val labelRes: Int,
    val amount: String?,
    @StringRes val treatmentRes: Int,
    /** True for the rows that reduce the total rather than add to it. */
    val isDeduction: Boolean,
)
