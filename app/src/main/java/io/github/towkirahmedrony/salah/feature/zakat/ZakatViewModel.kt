package io.github.towkirahmedrony.salah.feature.zakat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.towkirahmedrony.salah.core.domain.metalprice.LoadPublishedReferencePrices
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceFailure
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceRepository
import io.github.towkirahmedrony.salah.core.domain.metalprice.ReferencePriceResult
import io.github.towkirahmedrony.salah.core.domain.metalprice.ReferencePrices
import io.github.towkirahmedrony.salah.core.domain.metalprice.availablePriceText
import io.github.towkirahmedrony.salah.core.domain.repository.UserPreferencesRepository
import io.github.towkirahmedrony.salah.core.model.ZakatDebtTreatment
import io.github.towkirahmedrony.salah.core.model.ZakatInputs
import io.github.towkirahmedrony.salah.core.model.ZakatInventoryBasis
import io.github.towkirahmedrony.salah.core.model.ZakatJewelleryTreatment
import io.github.towkirahmedrony.salah.core.model.ZakatMetalUnit
import io.github.towkirahmedrony.salah.core.model.ZakatNisabMetal
import io.github.towkirahmedrony.salah.core.model.ZakatNisabWeight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.util.Locale

/**
 * Holds the Zakat form and turns it into the screen's state.
 *
 * ## Nothing is saved, and that is the design rather than an omission
 *
 * The values a user enters here are the most sensitive data this app ever handles: balances, holdings and
 * debts. Salah keeps **none** of them. There is no repository, no DataStore key, no file and no in-memory store
 * that outlives the screen — the form lives in this view model, is recalculated on every keystroke, and is gone
 * when the screen is. That is the strongest privacy posture available and it needs no disclosure beyond the
 * sentence on the screen that says so.
 *
 * The cost is real and is stated rather than hidden: the values do not survive the process being killed. A user
 * who backgrounds Salah for long enough to lose the process comes back to an empty form. Persisting them would
 * trade a guaranteed-absent copy of their finances for one more keystroke's convenience, and the brief's privacy
 * requirement asks for the opposite. See `docs/ZAKAT_METHODOLOGY.md` §8.4.
 *
 * ## What this class does not do
 *
 * It does not parse, calculate, format or decide anything. It holds the form, filters keystrokes, and delegates
 * to [ZakatUiStateFactory], which delegates the arithmetic to
 * [io.github.towkirahmedrony.salah.core.domain.zakat.ZakatCalculator]. The result is regenerated from the form
 * on every change rather than cached, so there is no second copy of the answer that could disagree with the
 * inputs.
 *
 * ## The one thing it fetches, and why that changes nothing above
 *
 * The published gold and silver reference prices are read once when the screen is created and then only when
 * the user asks (see [refreshReferencePrices]). The calculator never waits for them: it is given whatever price
 * the form holds, a published rate is only *offered* into a price field the user has left alone, and a failed
 * read leaves manual entry exactly as it was. A published price is an input like any other, never a dependency
 * of the arithmetic.
 */
class ZakatViewModel(
    private val userPreferencesRepository: UserPreferencesRepository,
    private val uiStateFactory: ZakatUiStateFactory,
    /**
     * Retrieves the published reference prices.
     *
     * Injected so the screen is testable against a fake that never reaches a network, and so a build with no
     * price service configured delivers "nothing published" rather than an error. It takes no user data: see
     * [LoadPublishedReferencePrices].
     */
    private val loadReferencePrices: LoadPublishedReferencePrices =
        LoadPublishedReferencePrices(MetalPriceRepository.UNCONFIGURED),
    /**
     * The clock the default valuation date comes from — nothing else reads it. The screen dates its own
     * calculation with it, so "as at" means the day the user was looking at.
     */
    private val clock: Clock = Clock.systemUTC(),
) : ViewModel() {

    private val form = MutableStateFlow(ZakatForm())

    /** The latest published-price read and how far it got. In flight at construction, because it is asked now. */
    private val referencePrices = MutableStateFlow(ReferencePrices.loading())

    /**
     * Which price fields the user has typed into this session.
     *
     * In memory only, for the life of the screen, and deliberately so: this is the smallest state that
     * distinguishes a published default from a figure the user chose, and remembering it beyond the session
     * would mean writing something about their calculation down. Cleared by [clearAll].
     */
    private val manualPriceFields = MutableStateFlow(emptySet<ZakatFormField>())

    /** True while a published-price read is in flight, so the refresh control can say so. */
    private val refreshingReferencePrices = MutableStateFlow(false)

    init {
        refreshReferencePrices()
    }

    /**
     * What the screen draws.
     *
     * Starts at [ZakatUiState.Loading] until the interface-language preference has been read: the numerals and
     * the field labels both depend on it, so there is no state that could be produced before it arrives.
     */
    val uiState: StateFlow<ZakatUiState> = combine(
        form,
        userPreferencesRepository.appLanguage,
        referencePrices,
        manualPriceFields,
        refreshingReferencePrices,
    ) { current, language, prices, manualPrices, refreshing ->
        uiStateFactory.create(
            form = current,
            // `null` is the "follow the device" choice, which an unset preference must still mean.
            locale = language.locale() ?: Locale.getDefault(),
            today = LocalDate.now(clock),
            referencePrices = prices,
            manualPriceFields = manualPrices,
            refreshingReferencePrices = refreshing,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MILLIS),
        initialValue = ZakatUiState.Loading,
    )

    /**
     * Reads the published reference prices again.
     *
     * Called once when the screen is created, and afterwards only when the user asks. It is never driven by a
     * keystroke or a recomposition, and a second read is not started while one is in flight, so a screen opening
     * and a user tapping refresh cannot produce two concurrent reads of the same thing.
     *
     * A user's own edited price is **never** replaced: the factory fills only a price field the user has left
     * alone. A failure leaves the screen exactly as usable as it was.
     */
    fun refreshReferencePrices() {
        if (refreshingReferencePrices.value) return
        refreshingReferencePrices.value = true
        // Only a metal with no usable rate is marked in flight again, so a refresh does not blank out a rate the
        // screen is already showing.
        referencePrices.update { current ->
            if (current.isLoading) {
                current
            } else {
                ReferencePrices(
                    gold = if (current.gold is ReferencePriceResult.Available) {
                        current.gold
                    } else {
                        ReferencePriceResult.Loading
                    },
                    silver = if (current.silver is ReferencePriceResult.Available) {
                        current.silver
                    } else {
                        ReferencePriceResult.Loading
                    },
                )
            }
        }
        viewModelScope.launch {
            val result = try {
                loadReferencePrices()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // A retrieval the repository could not report as a value is still not allowed to take the screen
                // down: it becomes "no published price" and manual entry carries on. Nothing about the user's
                // entries appears in this outcome, or in any log of it.
                ReferencePrices.failed(MetalPriceFailure.NETWORK)
            }
            referencePrices.value = result
            refreshingReferencePrices.value = false
        }
    }

    /** Records what the user typed into one field, after filtering what the field can hold. */
    fun onValueChange(field: ZakatFormField, value: String) {
        val filtered = filterDecimalInput(value)
        form.update { current -> current.withValue(field, filtered) }
        if (field in PRICE_FIELDS) {
            // The user has taken this price over for the session. Recorded in memory only, so a published rate
            // can never overwrite it and so nothing about their calculation is written anywhere.
            manualPriceFields.update { current -> current + field }
        }
    }

    /**
     * Records the seed date text.
     *
     * Bengali and Arabic-Indic digits are rewritten to Western ones as they are typed, so the field shows the
     * date in the user's own numerals while the parser reads ISO digits. Nothing is dropped: a date is parsed
     * rather than filtered, and the `-` separators have to survive.
     */
    fun onHawlDateChange(value: String) {
        form.update { current -> current.copy(hawlSeedDateText = normalizeDateDigits(value)) }
    }

    /**
     * Drops the user's own price for [field], so the published reference price applies again.
     *
     * The only way a manually edited price is ever replaced is by asking for it. The field is emptied rather
     * than overwritten with the published figure, and the factory then fills it from whatever rate is actually
     * in force — so what the user sees is exactly the number that would be used, and not a copy of it.
     */
    fun onRestoreReferencePrice(field: ZakatFormField) {
        if (field !in PRICE_FIELDS) return
        form.update { current -> current.withValue(field, "") }
        manualPriceFields.update { current -> current - field }
    }

    fun onCurrencyChange(currency: String) {
        form.update { current -> current.copy(currency = currency) }
    }

    fun onUnitChange(unit: ZakatMetalUnit) {
        form.update { current -> current.copy(unit = unit) }
    }

    fun onNisabWeightChange(weight: ZakatNisabWeight) {
        form.update { current -> current.copy(nisabWeight = weight) }
    }

    fun onNisabMetalChange(metal: ZakatNisabMetal) {
        form.update { current -> current.copy(nisabMetal = metal) }
    }

    fun onJewelleryChange(treatment: ZakatJewelleryTreatment) {
        form.update { current -> current.copy(jewelleryTreatment = treatment) }
    }

    fun onDebtTreatmentChange(treatment: ZakatDebtTreatment) {
        form.update { current -> current.copy(debtTreatment = treatment) }
    }

    fun onInventoryBasisChange(basis: ZakatInventoryBasis) {
        form.update { current -> current.copy(inventoryBasis = basis) }
    }

    /**
     * Empties the form.
     *
     * A reset of in-memory state and nothing else — there is no stored copy to clear, which is why this needs no
     * warning about what might be left behind elsewhere. The confirmation in front of it is the screen's, and it
     * exists because retyping a dozen figures is a real loss.
     */
    fun clearAll() {
        form.value = ZakatForm()
        // "Clear everything" is also the statement that no price in the form is the user's own any more, so a
        // published reference price (if there is one) applies again. The published rate was never stored, so
        // there is nothing to reload for it to be shown.
        manualPriceFields.value = emptySet()
    }

    companion object {
        /** How long the state stays live after the screen stops observing it. */
        const val SUBSCRIBE_TIMEOUT_MILLIS: Long = 5_000L

        private const val MAX_DECIMAL_PLACES = 12

        /**
         * The price fields a published reference price may fill when the user has left them alone.
         *
         * A set rather than two checks scattered through the class, so "which fields can a reference price
         * stand in for" has one answer.
         */
        val PRICE_FIELDS: Set<ZakatFormField> =
            setOf(ZakatFormField.GOLD_PRICE, ZakatFormField.SILVER_PRICE)

        /**
         * [value] with Bengali and Arabic-Indic digits rewritten as Western ones, everything else untouched.
         *
         * The Hawl field takes structured text rather than a number, so unlike [filterDecimalInput] this drops
         * nothing: a borrowed implementation of the same mapping lives in [ZakatDigits], and the date parser
         * behind this field reads the result as ISO digits.
         */
        fun normalizeDateDigits(value: String): String = ZakatDigits.toWestern(value)

        /**
         * Keeps what a numeric field can hold and drops the rest, writing every digit in Western form.
         *
         * A filter rather than a validation, so the field never contains something it cannot be turned into a
         * number: pasting `12,000.50` yields `12000.50`, a second decimal point is ignored, and a stray letter
         * never reaches the form. A negative sign is dropped too — no amount in this feature can be negative, and
         * letting one in only to reject it later would report an error where a correction is what is wanted.
         *
         * ## Why the digits are rewritten
         *
         * A Bengali keyboard produces Bengali numerals, and so does an Arabic one. `BigDecimal` reads neither, so
         * a user typing `১২৫০০` on their own keyboard would be told their number is not a number — an error about
         * the interface rather than about what they typed. Every digit in the ranges below is therefore mapped to
         * its Western equivalent as it is typed, and the field shows `12500` while the user's keyboard is still
         * Bengali. The *display* of amounts stays in the interface language's numerals, which is a separate rule
         * applied by [ZakatUiStateFactory].
         *
         * The mapping is arithmetic rather than a lookup table: each of these ranges is ten consecutive code
         * points in ascending digit order, so the offset within the range *is* the digit. A table would be ten
         * numbers per script with the chance of a typo in any of them.
         *
         * What survives is still *text*, and it is still the factory that decides whether it is a number: the
         * filter makes typing pleasant, and [io.github.towkirahmedrony.salah.core.model.ZakatMoney.parse] remains
         * the single authority on what a valid amount is.
         */
        fun filterDecimalInput(value: String): String {
            val builder = StringBuilder()
            var seenPoint = false
            var decimals = 0

            value.forEach { character ->
                val digit = ZakatDigits.westernDigit(character)
                when {
                    digit != null && (!seenPoint || decimals < MAX_DECIMAL_PLACES) -> {
                        builder.append(digit)
                        if (seenPoint) decimals += 1
                    }

                    // A leading point is kept, with a zero in front of it. ".5" is half a unit, and dropping
                    // the point would silently turn it into five units — a mistake in the user's figure that
                    // nothing later in the flow could detect, because "5" is a perfectly valid amount. A point
                    // with nothing after it is likewise kept, because "0." is a step towards "0.50".
                    character == '.' && !seenPoint -> {
                        if (builder.isEmpty()) builder.append('0')
                        builder.append('.')
                        seenPoint = true
                    }

                    else -> Unit
                }
            }
            return builder.toString()
        }
    }
}

/**
 * Everything the user has entered, as text.
 *
 * Text rather than parsed values, because a field the user is halfway through typing is not a value: "12." is a
 * step towards "12.50" and must survive in the form exactly as typed. Parsing happens once, in
 * [ZakatUiStateFactory], where a blank field and an unreadable one can be told apart.
 *
 * ## Why the choices live here too
 *
 * The methodology selections are part of what the user has entered, so they live in the same object and are
 * cleared by the same action. They are not stored anywhere either.
 *
 * ## The jewellery choice *is* the jewellery flag
 *
 * Selecting the majority ruling is the statement that the metals entered are personal jewellery rather than
 * savings: that is what the option's own label says, and Salah does not offer a separate switch for it, because
 * the option is that switch. It means a user who holds gold both as jewellery and as savings cannot express the
 * split in one calculation — recorded as a limitation in `docs/ZAKAT_METHODOLOGY.md` §9.11 rather than papered
 * over with a control that would suggest the two are handled separately when they are not.
 */
data class ZakatForm(
    val currency: String = ZakatInputs.DEFAULT_CURRENCY,
    val values: Map<ZakatFormField, String> = emptyMap(),
    val unit: ZakatMetalUnit = ZakatMetalUnit.GRAM,
    val inventoryBasis: ZakatInventoryBasis = ZakatInventoryBasis.MARKET_VALUE,
    val nisabWeight: ZakatNisabWeight = ZakatNisabWeight.INTERNATIONAL,
    val nisabMetal: ZakatNisabMetal = ZakatNisabMetal.GOLD,
    val jewelleryTreatment: ZakatJewelleryTreatment = ZakatJewelleryTreatment.ZAKATABLE,
    val debtTreatment: ZakatDebtTreatment = ZakatDebtTreatment.DEDUCT,
    val hawlSeedDateText: String = "",
) {
    /** What [field] holds, or the empty string when nothing has been entered. */
    fun text(field: ZakatFormField): String = values[field].orEmpty()

    fun withValue(field: ZakatFormField, value: String): ZakatForm =
        copy(values = values + (field to value))

    /**
     * This form with a published reference price standing in for a price field the user has left alone.
     *
     * A price field is filled only when **all three** hold: the user has not typed into it this session (it is
     * absent from [manualPriceFields]), it currently holds nothing, and a usable published rate exists for that
     * metal. Anything else leaves the user's own text exactly as it is — a manually edited price is never
     * silently replaced by a published one.
     *
     * The result is a *view* of the form for one rendering, not a write: nothing is stored, and the published
     * price disappears with the screen like every other value here. It lives here rather than in the state
     * factory so that the calculation and the displayed field are both read from the same merged form, and can
     * therefore never disagree about which price is in force.
     */
    fun mergedWithReferencePrices(
        referencePrices: ReferencePrices,
        manualPriceFields: Set<ZakatFormField>,
    ): ZakatForm {
        val merged = values.toMutableMap()

        fun fill(field: ZakatFormField, result: ReferencePriceResult) {
            if (field in manualPriceFields) return
            if (!merged[field].isNullOrBlank()) return
            val text = result.availablePriceText() ?: return
            merged[field] = text
        }

        fill(ZakatFormField.GOLD_PRICE, referencePrices.gold)
        fill(ZakatFormField.SILVER_PRICE, referencePrices.silver)
        return if (merged == values) this else copy(values = merged)
    }

    /**
     * True when the user has entered anything at all.
     *
     * Drives whether "clear everything" has anything to do, and it is deliberately generous: a half-typed field
     * counts, because the user would still lose it.
     */
    fun hasAnythingEntered(): Boolean =
        values.values.any { value -> value.isNotBlank() } || hawlSeedDateText.isNotBlank()
}
