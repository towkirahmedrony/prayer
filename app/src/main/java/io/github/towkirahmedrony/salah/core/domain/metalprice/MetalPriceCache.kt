package io.github.towkirahmedrony.salah.core.domain.metalprice

/**
 * One published rate as it was stored, with when *this device* fetched it.
 *
 * The timestamp is per metal rather than per snapshot because gold and silver are published as one response
 * but can be adopted independently: a response whose silver row is unusable must not make a preserved gold
 * rate look freshly fetched, and vice versa.
 */
data class CachedMetalRate(
    val price: PublishedMetalPrice,
    /** When this rate was last accepted from the service, in epoch milliseconds. */
    val fetchedAtEpochMillis: Long,
)

/**
 * The last validated published rates, held between process lifetimes.
 *
 * This is a cache of **published market data**, not of anything a user entered. It holds at most one
 * [PublishedMetalPrice] per metal — the same record the repository already selected as the K22/BDT basis —
 * together with the moment that metal's rate was accepted. Nothing about a user's holdings, weights or debts
 * can reach this type, and the Zakat form itself is still never written anywhere.
 *
 * A metal may be `null` when no validated rate for it is stored. The repository keeps a stored rate when a
 * later response does not publish that metal, so an omitted rate cannot silently erase a known-good one.
 */
data class MetalPriceSnapshot(
    val gold: CachedMetalRate? = null,
    val silver: CachedMetalRate? = null,
) {

    /** True when the snapshot holds no rate at all. Such a snapshot is never written. */
    val isEmpty: Boolean
        get() = gold == null && silver == null

    /** The stored rate for [metal], when there is one. */
    fun rateFor(metal: MetalType): CachedMetalRate? = when (metal) {
        MetalType.GOLD -> gold
        MetalType.SILVER -> silver
    }

    /**
     * True when [other] holds the same rates, ignoring when each was fetched.
     *
     * Prices are compared numerically rather than by `BigDecimal.equals`, which treats `19550` and
     * `19550.00` as different scales of the same number. Rewriting the cache because the service rendered
     * the same rate with another scale would be a write with no information in it. Every other field is
     * compared exactly, so a changed source, effective date or provider timestamp is still a change.
     */
    fun hasSameRatesAs(other: MetalPriceSnapshot?): Boolean {
        if (other == null) return false
        return samePrice(gold?.price, other.gold?.price) && samePrice(silver?.price, other.silver?.price)
    }

    private fun samePrice(left: PublishedMetalPrice?, right: PublishedMetalPrice?): Boolean {
        if (left == null || right == null) return left == null && right == null
        return left.metal == right.metal &&
            left.purity == right.purity &&
            left.currency.equals(right.currency, ignoreCase = true) &&
            left.pricePerGram.compareTo(right.pricePerGram) == 0 &&
            left.source == right.source &&
            left.sourceUrl == right.sourceUrl &&
            left.overrideActive == right.overrideActive &&
            left.effectiveAt == right.effectiveAt &&
            left.providerLastUpdate == right.providerLastUpdate &&
            left.fetchedAt == right.fetchedAt &&
            left.updatedAt == right.updatedAt
    }
}

/**
 * This snapshot as the screen consumes it, each metal classified against [nowEpochMillis] using its own
 * acquisition time.
 *
 * A metal the snapshot does not hold is [ReferencePriceResult.NotPublished] — no validated rate is stored for
 * it — rather than a failure. [refreshFailure] is carried beside the results so a failed *check* never turns a
 * stored *rate* into an unavailable one.
 */
fun MetalPriceSnapshot.asReferencePrices(
    nowEpochMillis: Long,
    policy: MetalPriceFreshnessPolicy,
    refreshFailure: MetalPriceFailure? = null,
): ReferencePrices = ReferencePrices(
    gold = gold.asResult(nowEpochMillis, policy),
    silver = silver.asResult(nowEpochMillis, policy),
    refreshFailure = refreshFailure,
)

private fun CachedMetalRate?.asResult(
    nowEpochMillis: Long,
    policy: MetalPriceFreshnessPolicy,
): ReferencePriceResult {
    if (this == null) return ReferencePriceResult.NotPublished
    return ReferencePriceResult.Available(
        price = price,
        freshness = policy.classify(fetchedAtEpochMillis, nowEpochMillis),
        cachedAtEpochMillis = fetchedAtEpochMillis,
    )
}

/**
 * Where the last validated published rates are kept between process lifetimes.
 *
 * Deliberately tiny: read the snapshot, write the snapshot. There is no per-metal write and no append, so a
 * replacement is one atomic commit and a caller can never leave gold updated and silver half-written. A
 * failure to read is reported as `null` — the app then behaves as though it has no cache and asks the
 * service — rather than throwing into the screen.
 */
interface MetalPriceCache {

    /** The stored snapshot, or `null` when there is none or it cannot be trusted. Never throws. */
    suspend fun read(): MetalPriceSnapshot?

    /** Replaces the stored snapshot. Never throws; a cache write failure must not fail a calculation. */
    suspend fun write(snapshot: MetalPriceSnapshot)

    companion object {
        /** A cache that stores nothing. The behaviour of a build with no writable storage. */
        val NONE: MetalPriceCache = object : MetalPriceCache {
            override suspend fun read(): MetalPriceSnapshot? = null
            override suspend fun write(snapshot: MetalPriceSnapshot) = Unit
        }
    }
}

/**
 * How old a cached published rate is, as the screen states it.
 *
 * All three states are **usable**: a cached rate is still a valid published rate, and the calculator stays
 * fully available offline. The distinction is disclosure, not permission — [STALE] tells the user the rate
 * has not been refreshed for long enough that they should check it, and manual entry remains one tap away.
 */
enum class MetalPriceFreshness {
    /** Fetched within the current window. */
    CURRENT,

    /** Fetched earlier than the current window but within the stale window. */
    CACHED,

    /** Fetched before the stale window. Still usable, but labelled as possibly out of date. */
    STALE,
}

/**
 * The one place the age of a cached rate becomes a word.
 *
 * The thresholds are a product decision, stated rather than inferred:
 *
 *  * **current** — fetched in the last 24 hours. Bangladesh gold and silver rates are published on a daily
 *    cadence, so a rate less than a day old is within one publication cycle.
 *  * **stale** — fetched more than 7 days ago. A week is long enough that a rate can plausibly have moved
 *    materially, and long enough that continuing to call it current would be misleading.
 *
 * No threshold gates use: a stale price is still offered, still labelled, and still never presented as the
 * live market rate. A clock that moved backwards yields [MetalPriceFreshness.CURRENT] rather than a negative
 * age, because a device clock is not evidence that the data is fresher than it is; the stored timestamp is
 * still what the screen shows.
 */
class MetalPriceFreshnessPolicy(
    private val currentForMillis: Long = DEFAULT_CURRENT_FOR_MILLIS,
    private val staleAfterMillis: Long = DEFAULT_STALE_AFTER_MILLIS,
) {

    /** [fetchedAtEpochMillis] classified against [nowEpochMillis]. */
    fun classify(fetchedAtEpochMillis: Long, nowEpochMillis: Long): MetalPriceFreshness {
        val age = nowEpochMillis - fetchedAtEpochMillis
        return when {
            age <= currentForMillis -> MetalPriceFreshness.CURRENT
            age <= staleAfterMillis -> MetalPriceFreshness.CACHED
            else -> MetalPriceFreshness.STALE
        }
    }

    companion object {
        /** One day. */
        const val DEFAULT_CURRENT_FOR_MILLIS: Long = 24L * 60L * 60L * 1_000L

        /** Seven days. */
        const val DEFAULT_STALE_AFTER_MILLIS: Long = 7L * 24L * 60L * 60L * 1_000L
    }
}
