package io.github.towkirahmedrony.salah.core.domain.metalprice

/**
 * The published reference prices, as the calculator's screen reads them.
 *
 * One method, and deliberately no others: the app may read published reference prices and may do nothing else
 * with them. There is no method that accepts a user's holding, a weight, a debt or a result, so personal
 * wealth cannot reach this boundary — the type makes the privacy rule structural.
 *
 * "Retrieve" rather than "cache": the repository may keep nothing between calls, and nothing in the Zakat
 * feature persists a price. A user's own edited price lives only in the calculator's view model for as long
 * as the screen is open.
 */
interface MetalPriceRepository {

    /** Reads the currently published reference prices. Read-only; safe to call more than once. */
    suspend fun publishedReferencePrices(): ReferencePrices

    companion object {
        /**
         * A repository with no price service behind it.
         *
         * The behaviour an unconfigured build gets, and the behaviour a caller that has no price source should
         * get: no published price, manual entry stays available, nothing reaches the network.
         */
        val UNCONFIGURED: MetalPriceRepository = object : MetalPriceRepository {
            override suspend fun publishedReferencePrices(): ReferencePrices = ReferencePrices.notPublished()
        }
    }
}

/**
 * Retrieves the published reference prices for the Zakat screen.
 *
 * Thin by design: it is the named boundary between the screen's view model and the price repository, so the
 * view model depends on a use case rather than on the repository's surface, and so the one operation the
 * feature performs is greppable and testable in isolation.
 *
 * It takes **no arguments**, which is the point: there is no parameter through which a user's assets, metals,
 * weights, liabilities or results could be passed to a remote service. The only thing this can do is ask for
 * published records.
 */
class LoadPublishedReferencePrices(
    private val repository: MetalPriceRepository,
) {

    /** The published reference prices, or the reason there are none. Never throws for a remote failure. */
    suspend operator fun invoke(): ReferencePrices = repository.publishedReferencePrices()
}
