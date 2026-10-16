package io.github.towkirahmedrony.salah.data.metalprice

import io.github.towkirahmedrony.salah.core.domain.dataset.remote.NetworkFailureKind
import io.github.towkirahmedrony.salah.core.domain.dataset.remote.RemoteFetch
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceFailure
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceParser
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceReadResult
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceRejectionReason
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceRemoteConfig
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceRepository
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPurity
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalType
import io.github.towkirahmedrony.salah.core.domain.metalprice.PublishedMetalPrice
import io.github.towkirahmedrony.salah.core.domain.metalprice.ReferencePricePolicy
import io.github.towkirahmedrony.salah.core.domain.metalprice.ReferencePriceResult
import io.github.towkirahmedrony.salah.core.domain.metalprice.ReferencePrices
import io.github.towkirahmedrony.salah.core.domain.metalprice.RemoteMetalPriceSource

/**
 * The published reference prices, read once per request from the published-price records.
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
 * * **More than one** such record ⇒ [ReferencePriceResult.Conflicting]. Two approved rates for the same
 *   metal, purity and currency are an ambiguity in the published data, and picking the newer, the lower or
 *   the first would be inventing a policy the sources do not state. The app reports it and falls back.
 * * A record for this metal that **could not be read as a price at all** — a missing field, or a price that is
 *   absent, zero, negative or unreadable ⇒ [ReferencePriceResult.Failed], reported rather than silently
 *   treated as "none published", because a broken publication and an empty one are different problems.
 *
 * The two metals are resolved from the same response but **independently**: a conflict or a rejection on one
 * metal's records never stops the other metal's rate being used.
 *
 * ## What it never does
 *
 * It never invents a price. There is no fallback constant here, no "last known" value and no default rate: if
 * the service has nothing usable, the answer is that there is nothing usable. It also never writes, and it
 * accepts no user data — see [MetalPriceRepository].
 */
class SupabaseMetalPriceRepository(
    private val source: RemoteMetalPriceSource,
    private val config: MetalPriceRemoteConfig,
    /** The purity an automatic default is taken from. */
    private val purity: MetalPurity = ReferencePricePolicy.DEFAULT_PURITY,
    /** The currency an automatic default must be stated in. */
    private val currency: String = ReferencePricePolicy.DEFAULT_CURRENCY,
) : MetalPriceRepository {

    override suspend fun publishedReferencePrices(): ReferencePrices {
        if (!config.isConfigured) {
            return ReferencePrices.failed(MetalPriceFailure.NOT_CONFIGURED)
        }
        return when (val fetched = source.fetchPublishedRows()) {
            is RemoteFetch.Success -> fromBody(fetched.value)
            // A 404 means the table or view is not exposed in this deployment — a backend dependency, not an
            // empty publication. Reported as such, and the calculator keeps working with manual entry.
            is RemoteFetch.NotFound -> ReferencePrices.failed(MetalPriceFailure.TABLE_UNAVAILABLE)
            is RemoteFetch.HttpError -> ReferencePrices.failed(
                if (fetched.status == HTTP_UNAUTHORIZED || fetched.status == HTTP_FORBIDDEN) {
                    MetalPriceFailure.ACCESS_DENIED
                } else {
                    MetalPriceFailure.SERVER
                },
            )

            is RemoteFetch.NetworkFailure -> ReferencePrices.failed(fetched.kind.toFailure())
            is RemoteFetch.TooLarge -> ReferencePrices.failed(MetalPriceFailure.RESPONSE_TOO_LARGE)
        }
    }

    private fun fromBody(body: String): ReferencePrices = when (val read = MetalPriceParser.parse(body)) {
        is MetalPriceReadResult.Malformed -> ReferencePrices.failed(MetalPriceFailure.MALFORMED)
        is MetalPriceReadResult.NoRecords -> ReferencePrices.notPublished()
        is MetalPriceReadResult.Parsed -> ReferencePrices(
            gold = select(MetalType.GOLD, read),
            silver = select(MetalType.SILVER, read),
        )
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

    /**
     * True for a rejection that means the row is not a readable price record at all.
     *
     * The distinction is what a user is told. [MetalPriceRejectionReason.NON_BDT_CURRENCY] and
     * [MetalPriceRejectionReason.UNKNOWN_PURITY] describe a well-formed price that plainly is not the
     * `K22`/`BDT` basis this app selects from, so the metal reaches [ReferencePriceResult.NotPublished] — the
     * same state as a record that is merely inactive or unverified. Everything else means the row itself could
     * not be read: a required field was absent, or the price was missing, zero, negative or unreadable, or the
     * metal or verification status was outside the contract. Those are reported as
     * [MetalPriceFailure.INVALID_DATA], so a broken publication is never mistaken for an empty one.
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
