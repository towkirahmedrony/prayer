package io.github.towkirahmedrony.salah.data.metalprice

import io.github.towkirahmedrony.salah.core.domain.dataset.remote.NetworkFailureKind
import io.github.towkirahmedrony.salah.core.domain.dataset.remote.RemoteFetch
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceFailure
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceParser
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceReadResult
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
 * * No such record ⇒ [ReferencePriceResult.NotPublished], and manual entry stays available.
 * * **More than one** such record ⇒ [ReferencePriceResult.Conflicting]. Two approved rates for the same
 *   metal, purity and currency are an ambiguity in the published data, and picking the newer, the lower or
 *   the first would be inventing a policy the sources do not state. The app reports it and falls back.
 * * A record that existed but could not be used ⇒ [ReferencePriceResult.Failed], reported rather than
 *   silently treated as "none published".
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

            // A record for this metal was present but unusable. That is reported rather than folded into
            // "nothing published", because the two states tell an operator different things.
            read.rejections.any { rejection -> rejection.metal == metal } ->
                ReferencePriceResult.Failed(MetalPriceFailure.INVALID_DATA)

            else -> ReferencePriceResult.NotPublished
        }
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
