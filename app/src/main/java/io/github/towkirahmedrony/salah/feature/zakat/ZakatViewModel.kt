package io.github.towkirahmedrony.salah.feature.zakat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.towkirahmedrony.salah.core.domain.repository.UserPreferencesRepository
import io.github.towkirahmedrony.salah.core.model.ZakatDebtTreatment
import io.github.towkirahmedrony.salah.core.model.ZakatInputs
import io.github.towkirahmedrony.salah.core.model.ZakatInventoryBasis
import io.github.towkirahmedrony.salah.core.model.ZakatJewelleryTreatment
import io.github.towkirahmedrony.salah.core.model.ZakatMetalUnit
import io.github.towkirahmedrony.salah.core.model.ZakatNisabMetal
import io.github.towkirahmedrony.salah.core.model.ZakatNisabWeight
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
 */
class ZakatViewModel(
    private val userPreferencesRepository: UserPreferencesRepository,
    private val uiStateFactory: ZakatUiStateFactory,
    /**
     * The clock the default valuation date comes from — nothing else reads it. The screen dates its own
     * calculation with it, so "as at" means the day the user was looking at.
     */
    private val clock: Clock = Clock.systemUTC(),
) : ViewModel() {

    private val form = MutableStateFlow(ZakatForm())

    /**
     * What the screen draws.
     *
     * Starts at [ZakatUiState.Loading] until the interface-language preference has been read: the numerals and
     * the field labels both depend on it, so there is no state that could be produced before it arrives.
     */
    val uiState: StateFlow<ZakatUiState> = combine(
        form,
        userPreferencesRepository.appLanguage,
    ) { current, language ->
        uiStateFactory.create(
            form = current,
            // `null` is the "follow the device" choice, which an unset preference must still mean.
            locale = language.locale() ?: Locale.getDefault(),
            today = LocalDate.now(clock),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MILLIS),
        initialValue = ZakatUiState.Loading,
    )

    /** Records what the user typed into one field, after filtering what the field can hold. */
    fun onValueChange(field: ZakatFormField, value: String) {
        form.update { current -> current.withValue(field, filterDecimalInput(value)) }
    }

    /** Records the seed date text, unfiltered, because a date is parsed rather than filtered. */
    fun onHawlDateChange(value: String) {
        form.update { current -> current.copy(hawlSeedDateText = value) }
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
    }

    companion object {
        /** How long the state stays live after the screen stops observing it. */
        const val SUBSCRIBE_TIMEOUT_MILLIS: Long = 5_000L

        private const val MAX_DECIMAL_PLACES = 12

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
                val digit = westernDigit(character)
                when {
                    digit != null && (!seenPoint || decimals < MAX_DECIMAL_PLACES) -> {
                        builder.append(digit)
                        if (seenPoint) decimals += 1
                    }

                    character == '.' && !seenPoint && builder.isNotEmpty() -> {
                        builder.append('.')
                        seenPoint = true
                    }

                    else -> Unit
                }
            }
            return builder.toString()
        }

        /**
         * [character] as a Western digit, or `null` when it is not a digit in any script this field accepts.
         *
         * Western, Bengali (U+09E6) and Arabic-Indic (U+0660) are the three scripts Salah's own language support
         * can produce, which is why they are the three mapped.
         */
        private fun westernDigit(character: Char): Char? = when (character) {
            in '0'..'9' -> character
            in '\u09E6'..'\u09EF' -> '0' + (character - '\u09E6')
            in '\u0660'..'\u0669' -> '0' + (character - '\u0660')
            else -> null
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
     * True when the user has entered anything at all.
     *
     * Drives whether "clear everything" has anything to do, and it is deliberately generous: a half-typed field
     * counts, because the user would still lose it.
     */
    fun hasAnythingEntered(): Boolean =
        values.values.any { value -> value.isNotBlank() } || hawlSeedDateText.isNotBlank()
}
