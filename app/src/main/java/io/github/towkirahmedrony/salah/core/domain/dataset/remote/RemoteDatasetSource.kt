package io.github.towkirahmedrony.salah.core.domain.dataset.remote

/**
 * How a network operation failed, in the only distinctions the synchroniser acts on.
 *
 * Deliberately coarse. The synchroniser's response to every kind here is the same — keep the active
 * dataset and retry later — so the value exists to be *logged* and to decide whether a retry is worth
 * attempting soon, not to drive different recovery paths. A finer taxonomy would be code nobody
 * branches on.
 */
enum class NetworkFailureKind {
    /** The request exceeded its timeout. Transient by nature. */
    TIMEOUT,

    /** The host could not be reached or the connection failed — DNS, socket, TLS. */
    CONNECTION,

    /** A failure that is neither of the above, kept so nothing is silently dropped. */
    UNKNOWN,
}

/**
 * The result of a single remote fetch.
 *
 * A value rather than an exception, matching the rest of Salah: a network failure is an ordinary,
 * expected outcome of asking a service something, and the caller decides what it means. Keeping it a
 * value is also what makes every failure path directly testable, which matters most for the paths that
 * must not corrupt local data.
 */
sealed interface RemoteFetch<out T> {

    /** The fetch succeeded; [value] is the response body. */
    data class Success<T>(val value: T) : RemoteFetch<T>

    /**
     * The resource does not exist (HTTP 404) — a missing view/table, or a payload object the publication
     * names but Storage does not hold.
     *
     * **Not an empty publication slot.** A slot with no current publication is a `200 []` body and is
     * reported as [Success]; conflating the two would let "the endpoint is missing" be handled as "there
     * is nothing to update".
     */
    data object NotFound : RemoteFetch<Nothing>

    /** The server answered with an error status. */
    data class HttpError(val status: Int) : RemoteFetch<Nothing>

    /** The request never produced an HTTP response. */
    data class NetworkFailure(val kind: NetworkFailureKind) : RemoteFetch<Nothing>

    /** The response exceeded the size the caller was willing to accept. */
    data class TooLarge(val limitBytes: Long) : RemoteFetch<Nothing>
}

/**
 * The remote dataset publication source, as a port.
 *
 * Two operations, both of them "fetch bytes from a URL the caller already knows": read the current
 * publication manifest for a dataset slot, and read one immutable payload object. Nothing above this
 * port knows about Supabase, HTTP verbs, headers or JSON — which is what lets the synchroniser be tested
 * against a fake that returns a manifest and a payload without a server, and what would let a different
 * publication backend be swapped in without touching the synchroniser.
 */
interface RemoteDatasetSource {

    /**
     * Fetches the current publication manifest for [datasetId].
     *
     * The body is returned as raw text; parsing it belongs to [DatasetPublicationParser] so the parser
     * and its tests do not depend on any transport.
     */
    suspend fun fetchManifest(datasetId: String): RemoteFetch<String>

    /**
     * Fetches the payload object at [objectPath], refusing a body larger than [maxBytes].
     *
     * The size ceiling is a parameter rather than a constant on the source because it is a property of
     * the *payload being fetched* (a location dataset today, something else later), not of the transport.
     */
    suspend fun fetchPayload(objectPath: String, maxBytes: Long): RemoteFetch<ByteArray>
}

/**
 * The non-secret remote configuration.
 *
 * [baseUrl] is the Supabase project URL and [anonKey] is the publishable/anon client key. Neither is a
 * secret in the sense a service-role key is: both are designed to be embedded in a client and are
 * constrained by row-level security. **No privileged key belongs here** — a `service_role` key, secret
 * key or database password in the app would bypass every policy this design relies on. See
 * `docs/DATABASE-SCHEMA.md`.
 *
 * `isConfigured` is the guard the whole feature hangs off: an empty configuration is a normal state (a build
 * that supplies none, or a test constructing this type with its own defaults), and every code path treats it
 * as "do nothing, keep working offline" rather than as an error. The app must open and function with no
 * remote configuration at all, so the class defaults stay blank even though the shipped build supplies the
 * project's own public endpoint and publishable key through `BuildConfig`.
 *
 * The URL and key are judged by [SupabaseConfiguration], the one definition of "configured" shared with the
 * prayer-configuration and published-price slots, so a blank or malformed value makes *every* remote read
 * decline to attempt a request rather than letting one of them build a URL that cannot resolve.
 */
data class DatasetRemoteConfig(
    val baseUrl: String = "",
    val anonKey: String = "",
    val bucket: String = DEFAULT_BUCKET,
    val datasetId: String = DEFAULT_DATASET_ID,

    /** How long a single request may take, in milliseconds. */
    val requestTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {

    /** True when enough is configured to attempt a fetch. */
    val isConfigured: Boolean
        get() = SupabaseConfiguration.isValidBaseUrl(baseUrl) &&
            SupabaseConfiguration.isValidClientKey(anonKey) &&
            bucket.isNotBlank() &&
            datasetId.isNotBlank()

    companion object {
        /** The dataset slot this phase synchronises. */
        const val DEFAULT_DATASET_ID: String = "bd_locations"

        /** The Storage bucket published payloads live in. Not public; read through RLS. */
        const val DEFAULT_BUCKET: String = "datasets"

        /** Ten seconds, applied to both connect and read. */
        const val DEFAULT_TIMEOUT_MILLIS: Long = 10_000
    }
}
