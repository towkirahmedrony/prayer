package io.github.towkirahmedrony.salah.data.mosque

import io.github.towkirahmedrony.salah.core.domain.dataset.remote.NetworkFailureKind
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueCacheRead
import io.github.towkirahmedrony.salah.core.domain.mosque.Mosque
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueCache
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueDataOrigin
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueFailure
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueGeo
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueLoadResult
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueQuery
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueRepository
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueSearchConfig
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueSearchSource
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueSourceResult
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueWithDistance
import io.github.towkirahmedrony.salah.core.domain.network.AlwaysOnlineNetworkStatusProvider
import io.github.towkirahmedrony.salah.core.domain.network.NetworkStatusProvider
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException

/**
 * The mosque finder's repository: network first, cache when the network cannot answer.
 *
 * ## The order of operations
 *
 *  1. Ask the service for page 1, then page 2 *only if* page 1 was full. A short page is the
 *     service's own statement that there is nothing after it, so it is never followed by a pointless
 *     request. The page ceiling bounds a misbehaving service and a very dense city.
 *  2. Merge every page by identity, then run the one filter and the one distance sort the cache read
 *     also runs. Duplicates between pages, non-mosque Islamic institutions and unusable features all
 *     disappear at this point.
 *  3. Write the accepted mosques to the cache and prune rows older than the configured age. Both are
 *     best-effort: a database that cannot be written must not turn a successful search into a
 *     failure, because the list in memory is still correct.
 *  4. If no request can be made at all (no key, or the device is plainly offline), read the cache
 *     immediately instead of spending a timeout on a request that cannot succeed.
 *  5. If no page succeeded, read the cache. A cached list is returned as a success with
 *     [MosqueLoadResult.Success.refreshFailure] set, which is how the screen says "these are real
 *     results, and the refresh did not succeed" without replacing them with an error.
 *
 * ## What is deliberately not done
 *
 *  * **No automatic retry.** A rate limit or a failed request is reported; retrying inside the
 *    repository would spend the user's battery and the service's quota without the user asking.
 *  * **No background prefetch.** The cache describes places the user actually searched around, not
 *    a region downloaded in advance. Map tiles are a separate concern with separate terms; see
 *    `docs/MOSQUE_FINDER.md`.
 *  * **No Place Details call.** The identifiers the search returned are identities, not promises of a
 *    second endpoint; every displayed field came in this response.
 */
class DefaultMosqueRepository(
    private val config: MosqueSearchConfig,
    private val source: MosqueSearchSource,
    private val cache: MosqueCache,
    private val clock: Clock = Clock.systemUTC(),
    /**
     * The cheap "is there obviously no network?" hint, consulted before a request.
     *
     * A device in airplane mode used to spend a full connect timeout on a request that could not
     * succeed before the cache was even read. This gate removes that wait: a plainly offline device
     * goes straight to the cache and reports [MosqueFailure.NETWORK_UNAVAILABLE] as the refresh
     * outcome. It fails open — an inconclusive answer attempts the request and lets the transport
     * decide, because a false "offline" would hide fresh data behind a stale cache.
     */
    private val networkStatus: NetworkStatusProvider = AlwaysOnlineNetworkStatusProvider,
) : MosqueRepository {

    override suspend fun nearby(query: MosqueQuery): MosqueLoadResult {
        if (!config.isConfigured) {
            return cachedOrFailure(query, MosqueFailure.NOT_CONFIGURED)
        }

        if (!isOnline()) {
            return cachedOrFailure(query, MosqueFailure.NETWORK_UNAVAILABLE)
        }

        val pageSize = query.pageSize.coerceIn(1, MAX_PAGE_SIZE)
        val maxPages = query.maxPages.coerceIn(1, MAX_PAGES_CEILING)

        val collected = LinkedHashMap<String, Mosque>()
        var offset = 0
        var pagesRead = 0
        var failure: MosqueFailure? = null

        while (pagesRead < maxPages) {
            when (val page = source.search(query, offset)) {
                is MosqueSourceResult.Success -> {
                    pagesRead += 1
                    for (mosque in page.mosques) {
                        collected.putIfAbsent(mosque.placeId, mosque)
                    }
                    if (page.featureCount < pageSize) break
                    offset += pageSize
                }
                else -> {
                    failure = page.toFailure()
                    break
                }
            }
        }

        // Nothing usable came back at all: fall back to the cache, and report why the refresh did
        // not happen. A failure after a usable page is not discarded — the usable page wins.
        if (pagesRead == 0) {
            return cachedOrFailure(query, failure ?: MosqueFailure.UNKNOWN)
        }

        val mosques = MosqueGeo.normalize(collected.values.toList())
        val nearest = MosqueGeo.nearestFirst(
            center = query.center,
            mosques = mosques,
            radiusMetres = query.radiusMetres,
        )

        writeCache(mosques)
        return MosqueLoadResult.Success(
            mosques = nearest,
            origin = MosqueDataOrigin.NETWORK,
        )
    }

    override suspend fun cachedNearby(query: MosqueQuery): MosqueCacheRead {
        if (!query.center.isFinite || query.radiusMetres <= 0) return MosqueCacheRead.Empty

        val snapshot = try {
            cache.read(query.center, query.radiusMetres)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Reported as its own case, not as "no saved mosques": a corrupt cache is a device
            // problem and the screen says so instead of pretending the area was never searched.
            return MosqueCacheRead.Unavailable
        }

        return snapshot
            ?.let { value -> MosqueCacheRead.Available(value) }
            ?: MosqueCacheRead.Empty
    }

    /** Fails open: only a definite "no network" skips the request. */
    private suspend fun isOnline(): Boolean = try {
        networkStatus.isOnline()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        true
    }

    /** Reads the cache and packages it as the fallback outcome, or reports [reason]. */
    private suspend fun cachedOrFailure(
        query: MosqueQuery,
        reason: MosqueFailure,
    ): MosqueLoadResult {
        val snapshot = readCache(query)
            ?: return MosqueLoadResult.Failure(reason)

        return MosqueLoadResult.Success(
            mosques = snapshot.mosques,
            origin = MosqueDataOrigin.CACHE,
            cachedAtEpochMillis = snapshot.cachedAtEpochMillis,
            refreshFailure = reason,
        )
    }

    /**
     * Reads the cache, treating a database exception as "no cache".
     *
     * Cancellation is rethrown: a screen that went away must stop the work, not receive a failure
     * value it has no one left to show.
     */
    private suspend fun readCache(query: MosqueQuery) = try {
        cache.read(query.center, query.radiusMetres)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }

    /** Writes and prunes the cache without letting a storage failure fail the search. */
    private suspend fun writeCache(mosques: List<Mosque>) {
        if (mosques.isEmpty()) return
        val now = clock.millis()
        try {
            cache.upsert(mosques, now)
            val maxAge = config.cacheMaxAgeMillis
            if (maxAge > 0) {
                cache.pruneBefore(now - maxAge)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // The list in memory is still correct. A later refresh will try the write again.
        }
    }

    private fun MosqueSourceResult.toFailure(): MosqueFailure = when (this) {
        is MosqueSourceResult.Success -> MosqueFailure.UNKNOWN
        MosqueSourceResult.NotConfigured -> MosqueFailure.NOT_CONFIGURED
        MosqueSourceResult.RateLimited -> MosqueFailure.RATE_LIMITED
        MosqueSourceResult.Malformed -> MosqueFailure.MALFORMED
        is MosqueSourceResult.HttpError -> when (status) {
            HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> MosqueFailure.ACCESS_DENIED
            HTTP_TOO_MANY_REQUESTS -> MosqueFailure.RATE_LIMITED
            else -> MosqueFailure.SERVER
        }
        is MosqueSourceResult.NetworkFailure -> when (kind) {
            NetworkFailureKind.TIMEOUT -> MosqueFailure.TIMEOUT
            NetworkFailureKind.CONNECTION -> MosqueFailure.CONNECTION
            NetworkFailureKind.UNKNOWN -> MosqueFailure.UNKNOWN
        }
        is MosqueSourceResult.TooLarge -> MosqueFailure.RESPONSE_TOO_LARGE
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_TOO_MANY_REQUESTS = 429

        /** A ceiling that survives a configuration typo without asking for hundreds of pages. */
        const val MAX_PAGES_CEILING = 10

        /** Matches the source's own URL bound, so the offset steps by exactly the page size sent. */
        const val MAX_PAGE_SIZE = 500
    }
}

/** A small convenience for code that needs the nearest mosque without re-reading the result type. */
internal fun MosqueLoadResult.Success.nearestMosque(): MosqueWithDistance? = mosques.firstOrNull()
