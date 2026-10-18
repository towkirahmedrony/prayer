package io.github.towkirahmedrony.salah.data.metalprice

import io.github.towkirahmedrony.salah.core.domain.dataset.remote.NetworkFailureKind
import io.github.towkirahmedrony.salah.core.domain.dataset.remote.RemoteFetch
import io.github.towkirahmedrony.salah.core.domain.metalprice.CachedMetalRate
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceCache
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceFailure
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceFreshness
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceFreshnessPolicy
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceParser
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceReadResult
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceRejectionReason
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceRemoteConfig
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceRepository
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceSnapshot
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPurity
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalType
import io.github.towkirahmedrony.salah.core.domain.metalprice.PublishedMetalPrice
import io.github.towkirahmedrony.salah.core.domain.metalprice.ReferencePricePolicy
import io.github.towkirahmedrony.salah.core.domain.metalprice.ReferencePriceResult
import io.github.towkirahmedrony.salah.core.domain.metalprice.ReferencePrices
import io.github.towkirahmedrony.salah.core.domain.metalprice.RemoteMetalPriceSource
import io.github.towkirahmedrony.salah.core.domain.metalprice.asReferencePrices
import java.time.Clock

/**
 * The published reference prices, offline-first.
 *
 * ## What it selects, and what it refuses to select
 *
 * For each metal it looks for a record that is **published** (active and verified — the parser has already
 * discarded the rest), in the [purity] this app uses by default, and stated in [currency]. Exactly one such
 * record is used.
 *
 * * No such record ⇒ [ReferencePriceResult.NotPublished], and manual entry stays available. A row that exists
 *   but is inactive, unverified, of another purity or in another currency is no such record — it is a price,
 *   just not this app's published basis — so it too leaves the metal at [ReferencePriceResult.NotPublished].
 * * **More than one** such record ⇒ that metal's refresh is refused as ambiguous. Two approved rates for the
 *   same metal, purity and currency are an ambiguity in the published data, and picking the newer, the lower
 *   or the first would be inventing a policy the sources do not state. The stored rate for that metal, if
 *   there is one, stays in force and the screen reports the conflict.
 * * A record for this metal that **could not be read as a price at all** — a missing field, or a price that is
 *   absent, zero, negative or unreadable ⇒ that metal's refresh is refused and its stored rate is preserved.
 *   A broken publication is different from an empty one, and neither may quietly erase a valid rate.
 *
 * ## The cache
 *
 * Each metal is validated and adopted independently. The merged snapshot is written in one atomic commit only
 * when the whole response passed — a valid metal may be shown fresh for the session, but an unusable or
 * ambiguous row leaves the stored set untouched. A metal the response does not publish keeps the rate already
 * stored rather than being deleted: an omitted rate is not evidence that the publisher withdrew it, and doing
 * otherwise would let a partial response erase silver while refreshing gold. A retained rate is **never
 * labelled current**, even when it is recent — the current publication did not serve it — and it keeps its own
 * acquisition time, so preserving one metal never makes it look as fresh as the other.
 *
 * On a read failure — no configuration, timeout, connection failure, HTTP error or malformed JSON — the
 * stored set is returned with [ReferencePrices.refreshFailure] describing why it is being shown. An unusable
 * or ambiguous row is reported the same way beside the other metal's fresh rate. With no stored set, the
 * failure is returned as before. Nothing advances a successful-refresh timestamp after a failed read,
 * because a failed read writes nothing at all.
 *
 * An unchanged set is not written again. The response is still reported as a current, successful check for
 * this session ([MetalPriceFreshness.CURRENT]), but the file keeps the acquisition time of the content it
 * already holds, so an identical rate does not cause repeated disk writes.
 *
 * ## What it never does
 *
 * It never invents a price. There is no fallback constant here and no default rate: if neither the service
 * nor the cache has anything usable, the answer is that there is nothing usable. It never writes a user's
 * data — the cache holds only published market rates — and it accepts no user holdings at all.
 */
class SupabaseMetalPriceRepository(
    private val source: RemoteMetalPriceSource,
    private val config: MetalPriceRemoteConfig,
    /** The purity an automatic default is taken from. */
    private val purity: MetalPurity = ReferencePricePolicy.DEFAULT_PURITY,
    /** The currency an automatic default must be stated in. */
    private val currency: String = ReferencePricePolicy.DEFAULT_CURRENCY,
    /** Where the last validated set is kept. The default stores nothing; the app binds a file cache. */
    private val cache: MetalPriceCache = MetalPriceCache.NONE,
    /** The clock the cache's age is measured against. */
    private val clock: Clock = Clock.systemUTC(),
    /** How fetched rates are labelled as current, cached or stale. */
    private val freshnessPolicy: MetalPriceFreshnessPolicy = MetalPriceFreshnessPolicy(),
) : MetalPriceRepository {

    /** The stored set, with no request. `null` when nothing trustworthy is stored. */
    override suspend fun cachedReferencePrices(): ReferencePrices? {
        val snapshot = readCache() ?: return null
        return snapshot.asReferencePrices(now(), freshnessPolicy)
    }

    override suspend fun publishedReferencePrices(): ReferencePrices {
        val cached = readCache()
        if (!config.isConfigured) {
            return fallback(cached, MetalPriceFailure.NOT_CONFIGURED)
        }
        return when (val fetched = source.fetchPublishedRows()) {
            is RemoteFetch.Success -> fromBody(fetched.value, cached)
            // A 404 means the table or view is not exposed in this deployment — a backend dependency, not an
            // empty publication. Reported as such, and the calculator keeps working with the cached price or
            // manual entry.
            is RemoteFetch.NotFound -> fallback(cached, MetalPriceFailure.TABLE_UNAVAILABLE)
            is RemoteFetch.HttpError -> fallback(
                cached,
                if (fetched.status == HTTP_UNAUTHORIZED || fetched.status == HTTP_FORBIDDEN) {
                    MetalPriceFailure.ACCESS_DENIED
                } else {
                    MetalPriceFailure.SERVER
                },
            )

            is RemoteFetch.NetworkFailure -> fallback(cached, fetched.kind.toFailure())
            is RemoteFetch.TooLarge -> fallback(cached, MetalPriceFailure.RESPONSE_TOO_LARGE)
        }
    }

    private suspend fun fromBody(body: String, cached: MetalPriceSnapshot?): ReferencePrices =
        when (val read = MetalPriceParser.parse(body)) {
            is MetalPriceReadResult.Malformed -> fallback(cached, MetalPriceFailure.MALFORMED)
            // A readable, empty publication is not a failure, and it does not delete a known-good rate: there
            // is no statement in an empty response that a previously published rate was withdrawn.
            is MetalPriceReadResult.NoRecords ->
                cached?.asReferencePrices(now(), freshnessPolicy) ?: ReferencePrices.notPublished()

            is MetalPriceReadResult.Parsed -> accept(read, cached)
        }

    /**
     * Adopts each metal's outcome independently, and writes only what actually changed.
     *
     * Gold and silver arrive in one response but are separate rates, and the existing contract deliberately
     * keeps one metal's problem from stopping the other. The failure mode this must prevent is the dangerous
     * direction: a malformed or ambiguous silver row must never make the stored silver rate disappear or
     * refresh gold while the preserved silver still looks current. Each stored rate carries its own
     * acquisition time, so a preserved rate keeps its real age.
     *
     * A metal the response does not publish keeps the stored rate. An omitted rate is not evidence that the
     * publisher withdrew it, and a partial adoption that deleted a metal would turn a bad response into lost
     * data.
     */
    private suspend fun accept(read: MetalPriceReadResult.Parsed, cached: MetalPriceSnapshot?): ReferencePrices {
        val now = now()
        val gold = adopt(select(MetalType.GOLD, read), cached?.gold, now)
        val silver = adopt(select(MetalType.SILVER, read), cached?.silver, now)

        // A row naming a metal this app does not know cannot be attributed to either result, but it is still a
        // defect in the publication and must be reported rather than silently skipped.
        val unattributed = read.rejections.any { rejection ->
            rejection.metal == null && rejection.reason.isUnreadableRow()
        }
        val failure = gold.failure ?: silver.failure
            ?: if (unattributed) MetalPriceFailure.INVALID_DATA else null

        // The cache is replaced only when the whole response passed. A valid metal may still be shown fresh for
        // this session, but an unusable or ambiguous row means the response as a set is not adopted: the last
        // known-good snapshot stays exactly as it was, so the next launch cannot inherit a half-updated set.
        val snapshot = MetalPriceSnapshot(gold = gold.rate, silver = silver.rate)
        if (failure == null && !snapshot.isEmpty && !snapshot.hasSameRatesAs(cached)) {
            cache.write(snapshot)
        }

        return ReferencePrices(
            gold = gold.result,
            silver = silver.result,
            refreshFailure = failure,
        )
    }

    /** One metal's result for this response, and the stored rate it leaves behind. */
    private class MetalOutcome(
        val rate: CachedMetalRate?,
        val result: ReferencePriceResult,
        val failure: MetalPriceFailure?,
    )

    private fun adopt(
        selection: ReferencePriceResult,
        existing: CachedMetalRate?,
        now: Long,
    ): MetalOutcome = when (selection) {
        is ReferencePriceResult.Available -> MetalOutcome(
            rate = CachedMetalRate(selection.price, now),
            result = ReferencePriceResult.Available(selection.price, freshness = MetalPriceFreshness.CURRENT),
            failure = null,
        )

        // Ambiguous: no new rate is adopted. A stored rate stays usable and labelled with its own age, while
        // the ambiguity is reported; with nothing stored, the conflict itself is the result.
        is ReferencePriceResult.Conflicting -> MetalOutcome(
            rate = existing,
            result = existing?.asRetained(now) ?: selection,
            failure = MetalPriceFailure.CONFLICTING,
        )

        // Unreadable: the same rule, with the row's own failure reported.
        is ReferencePriceResult.Failed -> MetalOutcome(
            rate = existing,
            result = existing?.asRetained(now) ?: selection,
            failure = selection.failure,
        )

        // Nothing published for this metal: keep what is stored, but never present it as current. An inactive,
        // unverified or omitted row is not a fresh price, so the retained rate is at best a saved one.
        ReferencePriceResult.NotPublished -> MetalOutcome(
            rate = existing,
            result = existing?.asRetained(now) ?: ReferencePriceResult.NotPublished,
            failure = null,
        )

        // `select` never returns a loading result; the branch keeps the `when` total if that changes.
        ReferencePriceResult.Loading -> MetalOutcome(
            rate = existing,
            result = existing?.asRetained(now) ?: ReferencePriceResult.NotPublished,
            failure = null,
        )
    }

    /**
     * A stored rate that this response did not republish or could not verify.
     *
     * It is usable, but it is **never labelled current**, even when it is only hours old: the current
     * publication did not serve it, so calling it current would say something the response contradicts. The
     * oldest it is called is [MetalPriceFreshness.CACHED].
     */
    private fun CachedMetalRate.asRetained(now: Long): ReferencePriceResult {
        val age = freshnessPolicy.classify(fetchedAtEpochMillis, now)
        return ReferencePriceResult.Available(
            price = price,
            freshness = if (age == MetalPriceFreshness.CURRENT) MetalPriceFreshness.CACHED else age,
            cachedAtEpochMillis = fetchedAtEpochMillis,
        )
    }

    /** The stored set when there is one, with [failure] recorded as the reason it is being shown. */
    private fun fallback(cached: MetalPriceSnapshot?, failure: MetalPriceFailure): ReferencePrices {
        if (cached == null) return ReferencePrices.failed(failure)
        return cached.asReferencePrices(now(), freshnessPolicy, refreshFailure = failure)
    }

    private fun select(metal: MetalType, read: MetalPriceReadResult.Parsed): ReferencePriceResult {
        val candidates: List<PublishedMetalPrice> = read.prices.filter { price ->
            price.metal == metal &&
                price.purity == purity &&
                price.currency.equals(currency, ignoreCase = true)
        }
        return when {
            candidates.size == 1 -> ReferencePriceResult.Available(candidates.single())
            candidates.size > 1 -> ReferencePriceResult.Conflicting(
                metal = metal,
                detail = "The published-price records hold ${candidates.size} usable ${metal.name.lowercase()} " +
                    "rates for ${purity.name}/$currency, so no single reference price can be chosen.",
            )

            // A row for this metal could not be read as a price record at all — a missing field, a price that
            // is absent, zero, negative or too precise, an unknown metal or verification status. That is
            // reported rather than folded into "nothing published", because the two states tell an operator
            // different things: one is a data problem, the other is an empty publication.
            read.rejections.any { rejection -> rejection.metal == metal && rejection.reason.isUnreadableRow() } ->
                ReferencePriceResult.Failed(MetalPriceFailure.INVALID_DATA)

            // Everything else — nothing for this metal, only rows that are inactive or unverified, only rows
            // in another purity, or only rows stated in another currency — is simply no rate in the basis this
            // app publishes from. Those rows are prices; they are just not *this* app's default basis, so
            // "no published reference price" is the honest answer and "the published data is broken" is not.
            else -> ReferencePriceResult.NotPublished
        }
    }

    private suspend fun readCache(): MetalPriceSnapshot? =
        runCatching { cache.read() }.getOrNull()

    private fun now(): Long = clock.millis()

    /**
     * True for a rejection that means the row is not a readable price record at all.
     *
     * The distinction is what a user is told. [MetalPriceRejectionReason.NON_BDT_CURRENCY] and
     * [MetalPriceRejectionReason.UNKNOWN_PURITY] describe a well-formed price that plainly is not the
     * `K22`/`BDT` basis this app selects from, so the metal reaches [ReferencePriceResult.NotPublished] — the
     * same state as a record that is merely inactive or unverified. Everything else means the row itself could
     * not be read: a required field was absent, or the price was missing, zero, negative or unreadable, or the
     * metal or verification status was outside the contract.
     */
    private fun MetalPriceRejectionReason.isUnreadableRow(): Boolean = when (this) {
        MetalPriceRejectionReason.NON_BDT_CURRENCY,
        MetalPriceRejectionReason.UNKNOWN_PURITY,
        -> false

        MetalPriceRejectionReason.MISSING_FIELD,
        MetalPriceRejectionReason.UNKNOWN_METAL_TYPE,
        MetalPriceRejectionReason.UNKNOWN_VERIFICATION_STATUS,
        MetalPriceRejectionReason.INVALID_PRICE,
        -> true
    }

    private fun NetworkFailureKind.toFailure(): MetalPriceFailure = when (this) {
        NetworkFailureKind.TIMEOUT -> MetalPriceFailure.TIMEOUT
        NetworkFailureKind.CONNECTION -> MetalPriceFailure.CONNECTION
        NetworkFailureKind.UNKNOWN -> MetalPriceFailure.NETWORK
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
    }
}
