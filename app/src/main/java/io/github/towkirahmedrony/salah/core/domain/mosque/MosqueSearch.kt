package io.github.towkirahmedrony.salah.core.domain.mosque

import io.github.towkirahmedrony.salah.core.domain.dataset.remote.NetworkFailureKind
import io.github.towkirahmedrony.salah.core.domain.location.GeoPoint

/**
 * The non-secret configuration of the mosque search.
 *
 * [apiKey] is supplied through `BuildConfig.GEOAPIFY_API_KEY`; a blank value is a normal "not
 * configured" state, never a crash. The key is deliberately absent from every diagnostic message and
 * from the toString form below, because a configuration object can end up in a log without anyone
 * meaning to put it there.
 *
 * The radius is deliberately modest. A mosque finder is a "what is around me" question, not a
 * directory of a whole district, and a smaller circle keeps the first response complete enough that
 * pagination is rarely needed.
 */
data class MosqueSearchConfig(
    val apiKey: String = "",
    val baseUrl: String = DEFAULT_BASE_URL,
    val radiusMetres: Int = DEFAULT_RADIUS_METRES,
    val pageSize: Int = DEFAULT_PAGE_SIZE,
    val maxPages: Int = DEFAULT_MAX_PAGES,
    val requestTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
    val cacheMaxAgeMillis: Long = DEFAULT_CACHE_MAX_AGE_MILLIS,
) {
    /** True when a request can be attempted at all. */
    val isConfigured: Boolean
        get() = apiKey.isNotBlank() && baseUrl.startsWith("https://")

    /** The categories requested from the provider; see [Mosque.CATEGORY_MOSQUE_SPECIFIC]. */
    val categories: List<String>
        get() = listOf(Mosque.CATEGORY_MOSQUE_SPECIFIC, Mosque.CATEGORY_ISLAMIC_WORSHIP)

    override fun toString(): String =
        "MosqueSearchConfig(baseUrl=$baseUrl, radiusMetres=$radiusMetres, pageSize=$pageSize, " +
            "maxPages=$maxPages, configured=$isConfigured)"

    companion object {
        const val DEFAULT_BASE_URL: String = "https://api.geoapify.com/v2/places"
        const val DEFAULT_RADIUS_METRES: Int = 5_000
        const val DEFAULT_PAGE_SIZE: Int = 50
        const val DEFAULT_MAX_PAGES: Int = 3
        const val DEFAULT_TIMEOUT_MILLIS: Long = 12_000
        const val DEFAULT_MAX_RESPONSE_BYTES: Long = 2_000_000

        /** Cached results older than this are still readable but are pruned on the next write. */
        const val DEFAULT_CACHE_MAX_AGE_MILLIS: Long = 30L * 24L * 60L * 60L * 1_000L
    }
}

/** One nearby-mosque search. */
data class MosqueQuery(
    val center: GeoPoint,
    val radiusMetres: Int,
    val pageSize: Int,
    val maxPages: Int,
    val language: String,
)

/** A single response from the places service. */
sealed interface MosqueSourceResult {

    /**
     * A readable response.
     *
     * [featureCount] is the number of features the service returned before the app's filtering, and
     * is what pagination uses to decide whether another page exists. [mosques] may be smaller than it
     * because malformed or non-mosque features are discarded.
     */
    data class Success(
        val mosques: List<Mosque>,
        val featureCount: Int,
    ) : MosqueSourceResult

    /** No API key (or an invalid endpoint), so no request was made. */
    data object NotConfigured : MosqueSourceResult

    /** The service answered with a non-success status. */
    data class HttpError(val status: Int) : MosqueSourceResult

    /** The service rate-limited the request (HTTP 429). */
    data object RateLimited : MosqueSourceResult

    /** The request never produced an HTTP response. */
    data class NetworkFailure(val kind: NetworkFailureKind) : MosqueSourceResult

    /** The response was valid HTTP but not the GeoJSON shape this feature can read. */
    data object Malformed : MosqueSourceResult

    /** The response exceeded the configured ceiling. */
    data class TooLarge(val limitBytes: Long) : MosqueSourceResult
}

/** Why a mosque list could not be loaded, in the distinctions the screen explains. */
enum class MosqueFailure {
    NOT_CONFIGURED,
    NETWORK_UNAVAILABLE,
    TIMEOUT,
    CONNECTION,
    RATE_LIMITED,
    ACCESS_DENIED,
    SERVER,
    MALFORMED,
    RESPONSE_TOO_LARGE,
    UNKNOWN,
}

/** The outcome of asking for nearby mosques. */
sealed interface MosqueLoadResult {

    /**
     * A usable list.
     *
     * [origin] says whether it came from the service or the cache. A cached list can also carry a
     * [refreshFailure], which is how the screen says both "these are real results" and "the refresh
     * did not succeed" without replacing the results with an error.
     */
    data class Success(
        val mosques: List<MosqueWithDistance>,
        val origin: MosqueDataOrigin,
        val cachedAtEpochMillis: Long? = null,
        val refreshFailure: MosqueFailure? = null,
    ) : MosqueLoadResult

    /** Nothing usable could be produced. */
    data class Failure(val reason: MosqueFailure) : MosqueLoadResult
}

/** A readable snapshot from the local cache. */
data class MosqueCacheSnapshot(
    val mosques: List<MosqueWithDistance>,
    val cachedAtEpochMillis: Long,
)

/**
 * The outcome of reading the cache without making a request.
 *
 * The three cases are distinct on purpose. "Nothing is stored here" is a normal first-run answer and
 * the screen shows an empty offline state for it; "the database could not be read" is a device
 * problem and is said so; only an [Available] snapshot is data. Collapsing them into a nullable or an
 * empty list would make the screen claim there are no saved mosques when there are, or claim a read
 * failure was an empty area.
 */
sealed interface MosqueCacheRead {

    /** Previously stored mosques around the requested centre. */
    data class Available(val snapshot: MosqueCacheSnapshot) : MosqueCacheRead

    /** The cache is healthy and holds nothing for this centre. */
    data object Empty : MosqueCacheRead

    /** The cache could not be read at all — usually a corrupt or unreadable database. */
    data object Unavailable : MosqueCacheRead
}

/**
 * Persistent storage of previously fetched mosques.
 *
 * The port speaks in domain values so the repository can be tested without a database, and the Room
 * implementation can be replaced without touching the search rules. Implementations must be safe to
 * call off the main thread.
 */
interface MosqueCache {

    /** The nearest cached mosques around [center], or `null` when nothing is stored near it. */
    suspend fun read(center: GeoPoint, radiusMetres: Int): MosqueCacheSnapshot?

    /**
     * Stores [mosques], replacing rows with the same identity rather than adding duplicates.
     *
     * [fetchedAtEpochMillis] is written on every row so the age of the whole area is one value.
     */
    suspend fun upsert(mosques: Collection<Mosque>, fetchedAtEpochMillis: Long)

    /** Removes rows acquired before [cutoffEpochMillis]; the cache is bounded by age, not by size. */
    suspend fun pruneBefore(cutoffEpochMillis: Long)
}

/** The mosque search, as the presentation layer sees it. */
interface MosqueRepository {

    /** Fetches nearby mosques, falling back to the cache when the service cannot be used. */
    suspend fun nearby(query: MosqueQuery): MosqueLoadResult

    /**
     * Reads the cache without making a request.
     *
     * Called before [nearby] so a saved list can be drawn immediately instead of after a network
     * attempt has failed. Implementations must not throw for a storage problem: they report
     * [MosqueCacheRead.Unavailable] instead.
     */
    suspend fun cachedNearby(query: MosqueQuery): MosqueCacheRead
}

/** The places service, as the repository sees it. */
interface MosqueSearchSource {

    /** Fetches one page starting at [offset]. */
    suspend fun search(query: MosqueQuery, offset: Int): MosqueSourceResult
}
