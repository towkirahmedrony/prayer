package io.github.towkirahmedrony.salah.core.domain.metalprice

import java.math.BigDecimal

/**
 * A published reference price for one precious metal, and the vocabulary around it.
 *
 * ## What this is, and what it is not
 *
 * This is the *contract* between the published-price records an administrator approves and the calculator.
 * It describes a reference rate — "what one gram of this metal was taken to be worth at this point" — and
 * nothing else. It is **not** the value of any particular item a user owns: a 22K reference rate is a
 * market convention, not a valuation of a specific piece of jewellery, and the screen says so rather than
 * implying otherwise.
 *
 * ## Where the price is an exact decimal
 *
 * [PublishedMetalPrice.pricePerGram] is a [BigDecimal] and nothing on this type can be built from a `Double`
 * or a `Float`. A market price is money, and money that has passed through binary floating point is money
 * with an error in it; the price is read from the service's own digits and handed to the calculator
 * unchanged. See `docs/ZAKAT_METHODOLOGY.md` §8.5.
 *
 * ## What the app is allowed to do with it
 *
 * Only read. This domain has no write path, no administrative operation and no way to publish a price —
 * approving and publishing records is an administrator workflow that lives outside the app.
 */
data class PublishedMetalPrice(
    /** Which metal this rate is for. */
    val metal: MetalType,

    /** Which purity category the rate is stated in. */
    val purity: MetalPurity,

    /** The reference price of one gram, exact and strictly positive, in [currency]. */
    val pricePerGram: BigDecimal,

    /** The currency [pricePerGram] is stated in. Only published rates in BDT are used as a default. */
    val currency: String,

    /** The human-readable name of the organisation the price came from, when recorded. */
    val source: String? = null,

    /** A link to the source, when recorded. Never followed by the app. */
    val sourceUrl: String? = null,

    /** True when an administrator explicitly activated a manual override for this record. */
    val overrideActive: Boolean = false,

    /**
     * The time the price was verified as effective, when the publisher recorded one.
     *
     * This is the only timestamp that may be presented as an effective or reviewed date: it is a statement
     * about the price itself. The other two are statements about the plumbing — see [providerLastUpdate] and
     * [fetchedAt].
     */
    val effectiveAt: String? = null,

    /**
     * The time the upstream provider said it last changed the price, when recorded.
     *
     * Shown **as** a provider update, never as the moment the price became effective in the market: a
     * provider's own generation timestamp does not establish when a market price was actually reached.
     */
    val providerLastUpdate: String? = null,

    /**
     * The time the provider's response was fetched, when recorded.
     *
     * Shown as when the record was retrieved, which is all it establishes.
     */
    val fetchedAt: String? = null,

    /** The time the record was last updated, when recorded. */
    val updatedAt: String? = null,
)

/** Which of the two zakatable precious metals a reference price is for. */
enum class MetalType {
    GOLD,
    SILVER,
    ;

    companion object {
        /**
         * The metal named by [raw], case-insensitively, or `null` when it is not one this app knows.
         *
         * A value this app does not recognise is left alone rather than mapped onto the nearest known one:
         * an unknown metal is a contract problem to report, not a price to guess at.
         */
        fun of(raw: String): MetalType? =
            entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
    }
}

/**
 * The purity categories the published-price contract defines.
 *
 * [K22] is the app's default gold and silver reference basis, chosen because it is the common Bangladeshi
 * jewellery convention. It is a **default selection**, not a claim that a user's metal is 22K or that the
 * figure is what their item would fetch: the calculator applies whatever price the user's own entry ends up
 * holding, and the reference rate is a starting point they can edit.
 */
enum class MetalPurity {
    K22,
    K21,
    K18,

    /** The category used for a metal stated as traditional (non-karat) purity. */
    TRADITIONAL,
    ;

    companion object {
        /** The purity named by [raw], case-insensitively, or `null` when it is not a known category. */
        fun of(raw: String): MetalPurity? =
            entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
    }
}

/**
 * How far a published price has been through the review the contract describes.
 *
 * Only [VERIFIED] may be used as an automatic default. A [PENDING] or [REJECTED] record is published in the
 * sense that it exists, and must not be presented as an approved current rate — the app checks this field
 * itself rather than assuming a row that exists has been reviewed.
 */
enum class PriceVerificationStatus {
    VERIFIED,
    PENDING,
    REJECTED,
    ;

    companion object {
        /** The status named by [raw], case-insensitively, or `null` when it is not one this app knows. */
        fun of(raw: String): PriceVerificationStatus? =
            entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
    }
}

/**
 * The purity and currency this app uses as its automatic reference basis.
 *
 * Kept as named constants so the choice appears in one place: the default is a policy decision, and a
 * second copy of `"K22"` in a query string is how a policy and its implementation drift apart.
 */
object ReferencePricePolicy {

    /** The purity whose published rate is used as the automatic default for both metals. */
    val DEFAULT_PURITY: MetalPurity = MetalPurity.K22

    /** The currency an automatic default must be stated in. */
    const val DEFAULT_CURRENCY: String = "BDT"
}
