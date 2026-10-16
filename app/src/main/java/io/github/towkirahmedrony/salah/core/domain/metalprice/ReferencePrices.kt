package io.github.towkirahmedrony.salah.core.domain.metalprice

/**
 * Why a published reference price could not be used.
 *
 * Coarse on purpose: the screen's response to every one of these is the same — say the price could not be
 * used, keep manual entry working — so the distinctions exist to be *reported* (and asserted in tests), not
 * to drive different recovery paths. The one split that matters is between "the service has no published
 * rate" ([ReferencePriceResult.NotPublished]) and "the service could not be asked, or answered unusably",
 * because only the first is a normal, expected state.
 */
enum class MetalPriceFailure {
    /** No price service is configured in this build. A normal state, not an error. */
    NOT_CONFIGURED,

    /** The request exceeded its timeout. */
    TIMEOUT,

    /** The host could not be reached, or the connection failed. */
    CONNECTION,

    /** A request failure that is neither of the above. */
    NETWORK,

    /** The service answered with an error status. */
    SERVER,

    /** The service refused the read — the published-price policy does not allow it. */
    ACCESS_DENIED,

    /** The published-price table or view does not exist yet in the deployment. */
    TABLE_UNAVAILABLE,

    /** The response was larger than the app was willing to read. */
    RESPONSE_TOO_LARGE,

    /** The response could not be read as the published-price contract at all. */
    MALFORMED,

    /** The response contained records, but none usable as a published rate for one metal. */
    INVALID_DATA,
}

/**
 * What was found for one metal.
 *
 * [Loading] is a state of the *retrieval*, not of the data, and is represented here because the screen has to
 * distinguish "we are still asking" from "there is no published rate" — showing the second while the first
 * is true would state something the app does not know.
 */
sealed interface ReferencePriceResult {

    /** The retrieval is in flight. */
    data object Loading : ReferencePriceResult

    /** One published, verified, active rate for the metal. */
    data class Available(val price: PublishedMetalPrice) : ReferencePriceResult

    /**
     * The service answered, and has no published rate this app may use — either none at all, or none in the
     * default purity and currency.
     */
    data object NotPublished : ReferencePriceResult

    /**
     * The service returned more than one usable published rate for the same metal, purity and currency.
     *
     * Not a price to pick from: choosing one of two conflicting approvals would be inventing a policy. The
     * app reports the conflict and falls back to manual entry, and the duplicate records are an
     * administrator-side problem.
     */
    data class Conflicting(val metal: MetalType, val detail: String) : ReferencePriceResult

    /** The price could not be retrieved or could not be used. */
    data class Failed(val failure: MetalPriceFailure) : ReferencePriceResult
}

/**
 * The published reference rates, as one read.
 *
 * The two metals are separate results on purpose: a service that has a gold rate and no silver rate, or that
 * returns one conflicting record for one metal, must not stop the other metal's price being used. One request
 * produces both, so a *request* failure does affect both, but a *data* outcome never does.
 */
data class ReferencePrices(
    val gold: ReferencePriceResult,
    val silver: ReferencePriceResult,
) {

    /** The retrieval result for [metal]. */
    fun forMetal(metal: MetalType): ReferencePriceResult = when (metal) {
        MetalType.GOLD -> gold
        MetalType.SILVER -> silver
    }

    /** True while either metal's retrieval is still in flight. */
    val isLoading: Boolean
        get() = gold == ReferencePriceResult.Loading || silver == ReferencePriceResult.Loading

    companion object {
        /** Both metals awaiting an answer. The state a screen starts in. */
        fun loading(): ReferencePrices = ReferencePrices(
            gold = ReferencePriceResult.Loading,
            silver = ReferencePriceResult.Loading,
        )

        /** Both metals with no published rate — the state when there is nothing to retrieve. */
        fun notPublished(): ReferencePrices = ReferencePrices(
            gold = ReferencePriceResult.NotPublished,
            silver = ReferencePriceResult.NotPublished,
        )

        /** Both metals failed the same way. */
        fun failed(failure: MetalPriceFailure): ReferencePrices = ReferencePrices(
            gold = ReferencePriceResult.Failed(failure),
            silver = ReferencePriceResult.Failed(failure),
        )
    }
}

/**
 * The exact price text for a metal, or `null` when no published rate may be used.
 *
 * The text is [BigDecimal.toPlainString] of the exact value the service published — never a rounded or
 * reformatted approximation — because this is the string the calculator parses back into a `BigDecimal`.
 */
fun ReferencePriceResult.availablePriceText(): String? =
    (this as? ReferencePriceResult.Available)?.price?.pricePerGram?.toPlainString()
