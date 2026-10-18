package io.github.towkirahmedrony.salah.core.domain.metalprice

/**
 * The published reference prices, as the calculator's screen reads them.
 *
 * One method, and deliberately no others: the app may read published reference prices and may do nothing else
 * with them. There is no method that accepts a user's holding, a weight, a debt or a result, so personal
 * wealth cannot reach this boundary — the type makes the privacy rule structural.
 *
 * The repository stores only **published market rates** — never a user's holdings, weight or debt — so the
 * offline cache is public data and the Zakat form remains purely in memory. A user's own edited price still
 * lives only in the calculator's view model for as long as the screen is open.
 */
interface MetalPriceRepository {

    /**
     * Reads the currently published reference prices, refreshing the local cache when the service answers.
     *
     * Never throws for a remote failure. When the refresh cannot be used, the last validated cached set is
     * returned and [ReferencePrices.refreshFailure] says why it is being shown instead of a fresh one.
     */
    suspend fun publishedReferencePrices(): ReferencePrices

    /**
     * The last validated set stored on this device, or `null` when there is none.
     *
     * Performs no request, so the screen can show a saved rate immediately — before, and independently of, any
     * refresh. The default is `null` so a repository with no storage (and every test fake) needs no change; it
     * is not a statement that caching is optional in the shipping implementation.
     */
    suspend fun cachedReferencePrices(): ReferencePrices? = null

    companion object {
        /**
         * A repository with no price service behind it.
         *
         * The behaviour an unconfigured build gets, and the behaviour a caller that has no price source should
         * get: no published price, manual entry stays available, nothing reaches the network.
         */
        val UNCONFIGURED: MetalPriceRepository = object : MetalPriceRepository {
            override suspend fun publishedReferencePrices(): ReferencePrices = ReferencePrices.notPublished()
            override suspend fun cachedReferencePrices(): ReferencePrices? = null
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

    /**
     * The last validated rates on this device, without a request.
     *
     * A separate operation rather than a flag on [invoke] so the two questions — "what is stored?" and "what
     * does the service say now?" — cannot be confused at a call site, and so opening the screen can show a
     * saved rate while the refresh is still in flight.
     */
    suspend fun cached(): ReferencePrices? = repository.cachedReferencePrices()
}
