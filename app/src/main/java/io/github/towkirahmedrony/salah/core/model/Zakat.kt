package io.github.towkirahmedrony.salah.core.model

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate

/**
 * Everything the Zakat calculator works with.
 *
 * ## The rule this file exists to keep
 *
 * **No monetary value in this feature is ever a `Double` or a `Float`.** Money is [ZakatMoney], which is a
 * `BigDecimal`, and every amount in [ZakatInputs] and [ZakatResult] is one. Binary floating point cannot
 * represent `0.1`, so `0.1 + 0.2` is not `0.3`; across the several additions, multiplications and one
 * comparison a zakat calculation performs, that error is not only visible in the last place — it can flip
 * the comparison against the nisab, which is the one decision the whole calculation turns on. A person a
 * hundredth of a gram below the threshold would be told they owe zakat, or the reverse.
 *
 * ## Rounding policy, in full
 *
 * * Every intermediate value — category amounts, the asset total, the net wealth, the nisab — is carried at
 *   **full precision, unrounded**. Nothing is rounded on the way through.
 * * The comparison against the nisab uses those unrounded values, so rounding can never move a user across
 *   the threshold.
 * * **Exactly one value is rounded: the zakat due**, to [CURRENCY_SCALE] decimal places, half-up. It is
 *   rounded because it is the amount a person actually pays, and money is paid in hundredths. Every other
 *   figure is reported exactly and formatted for display by the screen.
 *
 * `ZakatCalculatorTest` pins this: the same calculation performed with intermediate rounding to two places
 * is asserted **not** to be the result.
 *
 * ## What is not here
 *
 * No category's rules are inferred from another's. Agricultural produce, livestock, minerals, shares and
 * real estate held for resale are absent deliberately — each has a different rate or a different trigger,
 * and `docs/ZAKAT_METHODOLOGY.md` §4.6 records why each is out of scope rather than approximated.
 */

// ---------------------------------------------------------------------------------------------
// Money
// ---------------------------------------------------------------------------------------------

/**
 * A monetary amount, exact in decimal.
 *
 * A value class over [BigDecimal], so it costs no allocation and — more importantly — **cannot be built
 * from a `Double`**. There is no constructor taking a floating-point number anywhere on this type, which is
 * what makes "no binary floating point in the money path" a property of the code rather than a convention
 * someone has to remember. Amounts come in as text (what a user typed, after the input layer has validated
 * and stripped separators) or from another [ZakatMoney].
 *
 * Arithmetic is exact and unrounded except where a scale is named: [roundedToCurrency] is the only rounding
 * in the feature, and [ZakatCalculator] calls it once.
 */
@JvmInline
value class ZakatMoney private constructor(val amount: BigDecimal) : Comparable<ZakatMoney> {

    /** This amount plus [other], at the wider of the two scales. Exact. */
    operator fun plus(other: ZakatMoney): ZakatMoney = ZakatMoney(amount.add(other.amount))

    /** This amount minus [other]. Exact, and **not** floored — see [ZakatCalculator] for where the floor is. */
    operator fun minus(other: ZakatMoney): ZakatMoney = ZakatMoney(amount.subtract(other.amount))

    /** This amount times an exact decimal [factor] — a rate, a price, a quantity. Exact. */
    operator fun times(factor: BigDecimal): ZakatMoney = ZakatMoney(amount.multiply(factor))

    /** This amount times an integer [factor]. Exact. */
    operator fun times(factor: Int): ZakatMoney = ZakatMoney(amount.multiply(BigDecimal(factor)))

    /** This amount divided by an exact decimal [divisor] at [DIVISION_PRECISION], for averages and ratios. */
    fun divide(divisor: BigDecimal): ZakatMoney =
        ZakatMoney(amount.divide(divisor, MathContext(DIVISION_PRECISION, RoundingMode.HALF_UP)))

    /** This amount, or [ZERO] if it is negative. The floor that stops a negative zakat existing. */
    fun coerceAtLeastZero(): ZakatMoney = if (amount.signum() < 0) ZERO else this

    override fun compareTo(other: ZakatMoney): Int = amount.compareTo(other.amount)

    /** True when this is exactly zero, by value rather than by scale — `0.00` and `0` are both zero. */
    val isZero: Boolean get() = amount.signum() == 0

    val isNegative: Boolean get() = amount.signum() < 0

    /**
     * This amount rounded to [CURRENCY_SCALE] places, half-up.
     *
     * The **only** rounding in the Zakat feature. Half-up rather than half-even because money is what a
     * person pays at a counter: a value ending in exactly five thousandths is rounded up, which is what a
     * human being does and what the four schools' arithmetical examples assume.
     */
    fun roundedToCurrency(): ZakatMoney = ZakatMoney(amount.setScale(CURRENCY_SCALE, RoundingMode.HALF_UP))

    /** This amount written without an exponent and with a decimal point, for a screen to format. */
    fun toPlainString(): String = amount.toPlainString()

    override fun toString(): String = toPlainString()

    companion object {
        /** How many decimal places a monetary amount is paid in. */
        const val CURRENCY_SCALE: Int = 2

        /**
         * Working precision for [divide] only.
         *
         * High enough that a ratio cannot lose a meaningful digit, and finite because `BigDecimal.divide`
         * throws on a non-terminating quotient without one. Nothing in the zakat flow divides — the rate is
         * an exact multiplication — so this exists for future ratios rather than for this calculation.
         */
        const val DIVISION_PRECISION: Int = 16

        val ZERO: ZakatMoney = ZakatMoney(BigDecimal.ZERO)

        /**
         * The amount [text] denotes, or `null` when it is not a plain non-negative decimal.
         *
         * A parser rather than a constructor, because the text comes from a user: it is total (no exception
         * for any input), it rejects anything that is not a decimal number, and it rejects negatives. A
         * negative amount is not a value this feature has a meaning for — a negative weight, price or balance
         * is a typo — and inventing one (taking an absolute value, or accepting it and netting it against
         * another category) would quietly change the total.
         */
        fun parse(text: String): ZakatMoney? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            val parsed = try {
                BigDecimal(trimmed)
            } catch (_: NumberFormatException) {
                return null
            }
            return if (parsed.signum() < 0) null else ZakatMoney(parsed)
        }

        /**
         * The amount [value] denotes.
         *
         * The factory the calculator uses, for values it has computed itself: the operands are already
         * exact decimals and already non-negative, because a weight, a price or a product of the two cannot
         * be negative in this feature. It rejects a negative all the same, so that an arithmetic mistake
         * downstream fails loudly instead of producing a negative amount that silently reduces a total.
         */
        fun ofDecimal(value: BigDecimal): ZakatMoney {
            require(value.signum() >= 0) { "a monetary amount cannot be negative" }
            return ZakatMoney(value)
        }

        /** The amount [value] denotes. Non-negative integers only, so no rounding is ever implied. */
        fun of(value: Long): ZakatMoney {
            require(value >= 0) { "a monetary amount cannot be negative" }
            return ZakatMoney(BigDecimal(value))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Method choices, each one a documented disagreement
// ---------------------------------------------------------------------------------------------

/**
 * Which published pair of nisab weights is used.
 *
 * Both are real, both are published by named institutions, and they differ by about 2.9% — enough to change
 * whether zakat is due at all for someone near the boundary (see `docs/ZAKAT_METHODOLOGY.md` §3.2 and §10
 * Case J). Salah offers both and labels each with its publisher; it does not call either the agreed one.
 */
enum class ZakatNisabWeight(val goldGrams: BigDecimal, val silverGrams: BigDecimal) {

    /**
     * 87.48 g gold / 612.36 g silver.
     *
     * The weights published by Islamic Relief Worldwide, Muslim Aid, National Zakat Foundation and The
     * Citizens Foundation, and the pair that follows from the classical 7.5 and 52.5 tola.
     */
    INTERNATIONAL(BigDecimal("87.48"), BigDecimal("612.36")),

    /**
     * 85 g gold / 595 g silver.
     *
     * The weights published by Dhaka Ahsania Mission, a Bangladeshi Zakat organisation. Salah records the
     * pair as a published threshold; the derivation behind it is **not** claimed here, because no retrieved
     * source states the unit masses it would rest on — see `docs/ZAKAT_METHODOLOGY.md` §3.2.
     */
    BANGLADESH(BigDecimal("85"), BigDecimal("595")),
}

/** Which metal's nisab a calculation is measured against. */
enum class ZakatNisabMetal { GOLD, SILVER }

/**
 * Which of the two principal zakatable metals a row of input refers to.
 *
 * Exists so the nisab rule of `docs/ZAKAT_METHODOLOGY.md` §3.3 can be implemented: if the user holds only
 * one of the two, that metal's nisab must be the one used.
 */
enum class ZakatMetal { GOLD, SILVER }

/**
 * How personal gold and silver are treated — the sharpest disagreement in the feature.
 *
 * The two options are two recognised positions, not a right answer and a wrong one:
 *
 *  * [ZAKATABLE] — the **Hanafi** ruling: zakat is due on all gold and silver, including jewellery that is
 *    worn, because the metals have intrinsic monetary value. Default for Salah, since its audience is
 *    predominantly Hanafi.
 *  * [PERSONAL_USE_EXEMPT] — the **majority** (Shafi'i, Maliki, Hanbali) ruling and the position of the MUIS
 *    Fatwa Committee: jewellery kept for permissible personal adornment is exempt.
 *
 * Sources for both, and for the fact that they are genuinely different, are in
 * `docs/ZAKAT_METHODOLOGY.md` §4.2.
 */
enum class ZakatJewelleryTreatment { ZAKATABLE, PERSONAL_USE_EXEMPT }

/**
 * Whether debts reduce zakatable wealth before the nisab comparison.
 *
 *  * [DEDUCT] — the **Hanafi** position (Sarakhsi, Kasani, Ibn 'Abidin): debt can prevent the obligation.
 *    Salah's default.
 *  * [DO_NOT_DEDUCT] — the relied-upon **Shafi'i** position: debt does not prevent the obligation at all.
 *
 * The Maliki position (debts affect hidden wealth only) is **not** offered, because it requires classifying
 * every asset as hidden or visible — see `docs/ZAKAT_METHODOLOGY.md` §4.5 and §9.3. The same inputs under
 * these two options can differ by the whole zakat amount; §10 Case E shows it.
 */
enum class ZakatDebtTreatment { DEDUCT, DO_NOT_DEDUCT }

/**
 * How business inventory is valued.
 *
 *  * [MARKET_VALUE] — the Hanafi answer from SeekersGuidance (citing Ibn 'Abidin): value the goods at what
 *    they are worth now. Salah's default.
 *  * [LANDED_COST] — Dhaka Ahsania Mission's published instruction: value stock at its landed cost.
 *
 * Two reputable sources, two answers, so the user chooses. `docs/ZAKAT_METHODOLOGY.md` §4.3.
 */
enum class ZakatInventoryBasis { MARKET_VALUE, LANDED_COST }

/** The unit a precious-metal weight is entered in. */
enum class ZakatMetalUnit(val gramsPerUnit: BigDecimal) {

    /** The unit every source in the register states the nisab in. */
    GRAM(BigDecimal.ONE),

    /**
     * The traditional South Asian unit: 11.6638 g, being 3/8 of a troy ounce.
     *
     * `docs/ZAKAT_METHODOLOGY.md` §2.1 shows the arithmetic that makes 7.5 tola equal 87.48 g to within
     * 0.002 g, which is why the tola factor and the published nisab are consistent.
     */
    TOLA(BigDecimal("11.6638")),

    /**
     * The Bengali name for the tola — the **same mass**, kept as a separate constant only so a screen can
     * label the field the way its audience reads it.
     *
     * A distinct enum entry rather than a duplicate factor, so that whichever name is shown the arithmetic is
     * identical and a weight cannot quietly differ between the two labels.
     */
    VORI(BigDecimal("11.6638")),
}

// ---------------------------------------------------------------------------------------------
// Input
// ---------------------------------------------------------------------------------------------

/**
 * Every field the calculator reads.
 *
 * ## Why everything is nullable
 *
 * `null` means **the user has not entered this**, and it is not the same as zero. That distinction is the
 * whole of the "incomplete input" behaviour: a gold weight of `20` with a `null` price cannot be valued, and
 * the calculator says so and reports the result as an estimate on incomplete inputs rather than treating the
 * gold as worth nothing. A user who types `0` has told Salah the field is zero and gets a complete answer;
 * a user who leaves it blank has not been asked yet.
 *
 * ## One field per category
 *
 * Each category below has exactly one field and there is no catch-all "other assets" row, so the same
 * holding cannot be counted twice in two categories. The one place a double entry is conceivable is the
 * liability pair, which is why the two are named for what they are — [deductibleLiabilities] is the amount
 * **due within the coming year**, and [longTermLiabilities] is the **whole outstanding balance** of a
 * longer-term debt, which is not deducted. See `docs/ZAKAT_METHODOLOGY.md` §4.5.
 */
data class ZakatInputs(
    /** The ISO code the user's amounts are in. Presented as a choice; BDT is the default for Salah. */
    val currency: String = DEFAULT_CURRENCY,

    /** Cash in hand and in bank, savings, current and fixed-deposit balances, as at [valuationDate]. */
    val cash: BigDecimal? = null,

    /** An amount held in another currency, before conversion. `null` when none is held. */
    val foreignCurrencyAmount: BigDecimal? = null,

    /** The rate the user used to convert [foreignCurrencyAmount] into [currency]. Theirs, not Salah's. */
    val foreignCurrencyRate: BigDecimal? = null,

    /** The weight of gold held, in [metalUnit]. Includes jewellery when the Hanafi ruling is selected. */
    val goldWeight: BigDecimal? = null,

    /** The price of one **gram** of gold in [currency], as entered by the user. Never fetched. */
    val goldPricePerGram: BigDecimal? = null,

    /** The weight of silver held, in [metalUnit]. */
    val silverWeight: BigDecimal? = null,

    /** The price of one **gram** of silver in [currency], as entered by the user. Never fetched. */
    val silverPricePerGram: BigDecimal? = null,

    /** The unit [goldWeight] and [silverWeight] are entered in. */
    val metalUnit: ZakatMetalUnit = ZakatMetalUnit.GRAM,

    /**
     * True when the precious metals entered are held as personal jewellery rather than as savings or stock.
     *
     * Only consulted when the treatment is [ZakatJewelleryTreatment.PERSONAL_USE_EXEMPT]; under the Hanafi
     * ruling it makes no difference, which is the ruling itself.
     */
    val preciousMetalsArePersonalJewellery: Boolean = false,

    /** The value of stock held for sale. Interpreted according to [inventoryBasis]. */
    val inventoryValue: BigDecimal? = null,

    /** How [inventoryValue] is to be read. */
    val inventoryBasis: ZakatInventoryBasis = ZakatInventoryBasis.MARKET_VALUE,

    /**
     * Money owed to the user by someone who acknowledges it and intends to pay — a "strong" debt.
     *
     * Included in zakatable wealth. The Hanafi position is that zakat on such a debt is obligatory each year
     * but is paid on receipt; Salah includes it at the valuation date and says so in the breakdown.
     */
    val collectibleReceivables: BigDecimal? = null,

    /**
     * Money owed by someone insolvent, denying the debt, or delaying payment — a "doubtful" debt.
     *
     * **Excluded** from zakatable wealth, and shown in the breakdown as excluded with the reason, following
     * the Hanafi ruling that ownership of such a debt is incomplete. It is a separate field from
     * [collectibleReceivables] because the two are treated differently; one "receivables" box could not
     * express the ruling.
     */
    val doubtfulReceivables: BigDecimal? = null,

    /** Debts **due now, or falling due within the coming year**. Deducted under [ZakatDebtTreatment.DEDUCT]. */
    val deductibleLiabilities: BigDecimal? = null,

    /**
     * The whole outstanding balance of longer-term debt, such as the remaining years of a mortgage.
     *
     * **Never deducted.** It is asked for so the breakdown can show it as an excluded item with its reason:
     * no source in the register permits deducting an entire multi-year balance, and the alternative to
     * asking is a user silently deducting it themselves, or wondering why it was ignored. Under both
     * treatments this value does not affect the result, and the result says so.
     */
    val longTermLiabilities: BigDecimal? = null,

    /** Which published nisab weight pair is used. */
    val nisabWeight: ZakatNisabWeight = ZakatNisabWeight.INTERNATIONAL,

    /** Which metal's nisab the wealth is measured against, when the user holds cash or mixed assets. */
    val nisabMetal: ZakatNisabMetal = ZakatNisabMetal.SILVER,

    /** How personal jewellery is treated. */
    val jewelleryTreatment: ZakatJewelleryTreatment = ZakatJewelleryTreatment.ZAKATABLE,

    /** How debts are treated. */
    val debtTreatment: ZakatDebtTreatment = ZakatDebtTreatment.DEDUCT,

    /**
     * The date the user's wealth first reached the nisab, or `null` if they do not know it.
     *
     * Used only to produce an **estimated** anniversary. See [ZakatHawl] for why Salah stops there.
     */
    val hawlSeedDate: LocalDate? = null,

    /** The date the values above are as at. Required, so the breakdown can state what it valued. */
    val valuationDate: LocalDate,
) {
    companion object {
        /** BDT, because Salah's audience is Bangladeshi. A default, not an assumption about every asset. */
        const val DEFAULT_CURRENCY: String = "BDT"

        /** The currencies the screen offers. Text codes, not an enum — nothing here needs a rate table. */
        val SUPPORTED_CURRENCIES: List<String> = listOf("BDT", "USD", "GBP", "EUR", "SAR", "AED", "MYR", "INR")
    }
}

// ---------------------------------------------------------------------------------------------
// Result
// ---------------------------------------------------------------------------------------------

/** One input row's outcome, so the screen can show what was counted and what was not. */
enum class ZakatLineTreatment {

    /** Counted towards eligible assets. */
    INCLUDED,

    /** Deliberately left out, with a reason the screen states. Not the same as missing. */
    EXCLUDED,

    /**
     * Cannot be counted because a value it needs is missing.
     *
     * Distinct from [EXCLUDED] on purpose: an excluded row was decided, an incomplete row is a question the
     * user has not answered, and the result's status depends on the difference.
     */
    INCOMPLETE,
}

/** Which input a line reports on, so a screen can lay out a breakdown without matching on strings. */
enum class ZakatLineField {
    CASH,
    FOREIGN_CURRENCY,
    GOLD,
    SILVER,
    INVENTORY,
    COLLECTIBLE_RECEIVABLES,
    DOUBTFUL_RECEIVABLES,
    DEDUCTIBLE_LIABILITIES,
    LONG_TERM_LIABILITIES,
}

/** What a line's amount was derived from, as data rather than as a sentence. */
data class ZakatComputation(
    /** The weight in grams, or the amount in the other currency, that was converted. */
    val quantity: BigDecimal,
    /** The unit [quantity] is in. */
    val quantityUnit: ZakatQuantityUnit,
    /** The price per gram, or the exchange rate, that was applied. `null` when the amount was entered directly. */
    val unitPrice: BigDecimal?,
    /** `quantity × unitPrice`, or the amount as entered. */
    val baseAmount: BigDecimal,
    /** Which valuation basis was applied, for a row where a choice exists. */
    val basis: ZakatValuationBasis? = null,
)

/** What a [ZakatComputation] quantity counts. */
enum class ZakatQuantityUnit { GRAMS, OTHER_CURRENCY, NONE }

/** A valuation basis a line was computed on, mirroring the enum a row's choice came from. */
enum class ZakatValuationBasis { MARKET_VALUE, LANDED_COST, USER_ENTERED }

/** One row of the breakdown. */
data class ZakatLine(
    val field: ZakatLineField,
    val treatment: ZakatLineTreatment,
    /** The amount this row contributes, exact. `null` for an [ZakatLineTreatment.INCOMPLETE] row. */
    val amount: ZakatMoney?,
    /** How the amount was arrived at, or `null` for a row entered directly with nothing to explain. */
    val computation: ZakatComputation? = null,
)

/**
 * How far the result can be relied on.
 *
 * The three states are worded and presented differently on screen, because the difference between "this is
 * the arithmetic" and "this is what the arithmetic gives if the numbers you typed are right" is the
 * difference between a calculation and a guess.
 */
enum class ZakatStatus {

    /** Nothing has been entered. Shown as an empty state, not as a zero zakat. */
    NOTHING_ENTERED,

    /** Every row with data has what it needs, and the threshold is met. */
    CALCULATED,

    /**
     * Either a row is missing a value it needs, or a user-supplied price or rate is doing the work, or the
     * threshold is not met.
     *
     * An estimate rather than nothing: the calculator still states what it can and says what is missing.
     */
    ESTIMATE,

    /** Net zakatable wealth is below the nisab. No amount is due. */
    BELOW_NISAB,
}

/** An estimated zakat anniversary, produced from a user-supplied seed date. */
data class ZakatHawl(
    val seedDate: LocalDate,
    /** The seed date plus [APPROXIMATE_LUNAR_YEAR_DAYS], as an estimate the user must verify. */
    val estimatedAnniversary: LocalDate,
    /** How many days from the seed date to the estimate. */
    val approximateYearDays: Int = APPROXIMATE_LUNAR_YEAR_DAYS,
) {
    companion object {
        /**
         * The lunar year, in days, used **only** to produce an estimate.
         *
         * `docs/ZAKAT_METHODOLOGY.md` §5: the sources say "one full lunar year" and, secondarily, "about 354
         * days". Salah uses 354 to place an approximate date on a screen and labels it an estimate. It does
         * not convert a Hijri date, because that needs a calendar with a documented month-length convention,
         * and it never states that a payment is due.
         */
        const val APPROXIMATE_LUNAR_YEAR_DAYS: Int = 354
    }
}

/**
 * The zakat calculator's answer, with the working shown.
 *
 * Every field of the flow in `docs/ZAKAT_METHODOLOGY.md` §6 is a property, so a screen can present the
 * calculation as a sequence rather than as one unexplained number: the assets by category ([lines]), their
 * total, the deductions, the net wealth, the threshold and where it came from, whether it was met, and the
 * amount.
 *
 * The choices that produced it are reported too — [nisabWeight], [nisabMetal], whether the metal was
 * overridden by the all-one-metal rule, the jewellery and debt treatments, and the inventory basis — so the
 * screen can name the methodology in force instead of asserting a result. Where two recognised positions
 * differ, that is the point: the reader can see which one they got.
 */
data class ZakatResult(
    val status: ZakatStatus,
    val currency: String,
    val valuationDate: LocalDate,

    /** Every row, in the order the screen should present them. */
    val lines: List<ZakatLine>,

    /** Sum of the [ZakatLineTreatment.INCLUDED] asset rows, exact. */
    val totalEligibleAssets: ZakatMoney,

    /** Sum of the rows deducted, exact. Zero when the selected treatment does not deduct. */
    val totalDeductibleLiabilities: ZakatMoney,

    /** [totalEligibleAssets] less [totalDeductibleLiabilities], floored at zero. Exact. */
    val netZakatableWealth: ZakatMoney,

    /** The nisab in [currency], or `null` when the price needed to compute it was not entered. */
    val nisab: ZakatMoney?,

    /** The nisab weight actually applied. */
    val nisabUsed: ZakatNisabWeight,

    /** The metal whose nisab was applied after the all-one-metal rule. */
    val nisabMetalUsed: ZakatNisabMetal,

    /** True when the all-one-metal rule overrode the user's metal choice. */
    val nisabMetalOverridden: Boolean,

    /** The nisab weight in grams that [nisab] was computed from. */
    val nisabGrams: BigDecimal,

    /** The price per gram the nisab was computed with. `null` when the price was not entered. */
    val nisabPricePerGram: BigDecimal?,

    /** Whether net wealth reaches the nisab. `null` when the nisab could not be computed. */
    val nisabMet: Boolean?,

    /** The amount due, rounded to currency scale, or `null` when no amount is due or the result is incomplete. */
    val zakatDue: ZakatMoney?,

    /** The rate applied. Reported so the screen never has to hardcode it. */
    val zakatRate: BigDecimal,

    val jewelleryTreatment: ZakatJewelleryTreatment,
    val debtTreatment: ZakatDebtTreatment,
    val inventoryBasis: ZakatInventoryBasis,

    /** Rows that need a value before the result can be complete. Empty when none do. */
    val incompleteFields: List<ZakatLineField>,

    /** True when any part of the result rests on a price or rate the user supplied rather than on a total. */
    val dependsOnUserSuppliedPrice: Boolean,

    val hawl: ZakatHawl?,
) {
    companion object {
        /**
         * The rate. 2.5%, as an exact decimal — `BigDecimal("0.025")`, never `0.025`.
         *
         * Held as an exact decimal so that applying it is a multiplication with no rounding at all; a
         * percentage expressed as a binary fraction would be inexact the moment it was written down.
         */
        val RATE: BigDecimal = BigDecimal("0.025")
    }
}
