package io.github.towkirahmedrony.salah.data.dataset.remote

import io.github.towkirahmedrony.salah.core.domain.dataset.remote.DatasetRemoteConfig
import io.github.towkirahmedrony.salah.core.domain.dataset.remote.RemoteDatasetSource
import io.github.towkirahmedrony.salah.core.domain.dataset.remote.RemoteFetch

/**
 * The Supabase implementation of [RemoteDatasetSource], over the port in [HttpTransport].
 *
 * Two endpoints, both read-only and both reached with the publishable (anon) key:
 *
 * ```text
 * GET {url}/rest/v1/dataset_current_publication?dataset_id=eq.{slot}&limit=1&select=*
 * GET {url}/storage/v1/object/{bucket}/{path}
 * ```
 *
 * The first is a PostgREST query against a view that exposes only the *current, published* publication;
 * the second is the Storage object API for the immutable payload archive. Which rows a client may see and
 * which objects it may read is decided by row-level security on the server, not by these URLs — the URLs
 * are not a security boundary and are not treated as one. See `docs/DATABASE-SCHEMA.md`.
 *
 * **Why the view, and why one row.** Ask PostgREST for the *current* publication directly and there is no
 * way for the client to accidentally pick a draft or an archived release: the view has already filtered
 * to published rows and to the pointer's row, and the app still re-checks the status field. Both ends
 * enforcing the same rule is deliberate — the app must be safe against a misconfigured database, and the
 * database must be safe against a client that forgets to check.
 *
 * **The key is sent two ways.** PostgREST accepts the anon key as `apikey`, and the Storage API requires
 * it as a bearer token; sending both headers satisfies both without branching. It is the same
 * non-privileged key in each, and it is designed to be public.
 */
class SupabaseRemoteDatasetSource(
    private val transport: HttpTransport,
    private val config: DatasetRemoteConfig,
    /** The view PostgREST resolves the current publication through. */
    private val manifestView: String = MANIFEST_VIEW,
) : RemoteDatasetSource {

    override suspend fun fetchManifest(datasetId: String): RemoteFetch<String> {
        val url = buildString {
            append(baseUrl())
            append("/rest/v1/")
            append(manifestView)
            append("?dataset_id=eq.")
            append(percentEncode(datasetId))
            append("&limit=1&select=*")
        }
        return when (
            val result = transport.get(url, restHeaders(), config.requestTimeoutMillis, MAX_MANIFEST_BYTES)
        ) {
            is HttpTransportResult.Response -> when {
                result.status in SUCCESS_RANGE -> RemoteFetch.Success(String(result.body, Charsets.UTF_8))
                result.status == HTTP_NOT_FOUND -> RemoteFetch.NotFound
                else -> RemoteFetch.HttpError(result.status)
            }
            is HttpTransportResult.Failure -> RemoteFetch.NetworkFailure(result.kind)
            is HttpTransportResult.TooLarge -> RemoteFetch.TooLarge(result.limitBytes)
        }
    }

    override suspend fun fetchPayload(objectPath: String, maxBytes: Long): RemoteFetch<ByteArray> {
        val encodedPath = objectPath.split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/") { percentEncode(it) }
        val url = "${baseUrl()}/storage/v1/object/${percentEncode(config.bucket)}/$encodedPath"
        return when (val result = transport.get(url, storageHeaders(), config.requestTimeoutMillis, maxBytes)) {
            is HttpTransportResult.Response -> when {
                result.status in SUCCESS_RANGE -> RemoteFetch.Success(result.body)
                result.status == HTTP_NOT_FOUND -> RemoteFetch.NotFound
                else -> RemoteFetch.HttpError(result.status)
            }
            is HttpTransportResult.Failure -> RemoteFetch.NetworkFailure(result.kind)
            is HttpTransportResult.TooLarge -> RemoteFetch.TooLarge(result.limitBytes)
        }
    }

    private fun baseUrl(): String = config.baseUrl.trimEnd('/')

    private fun restHeaders(): Map<String, String> = mapOf(
        "apikey" to config.anonKey,
        "Authorization" to "Bearer ${config.anonKey}",
        "Accept" to "application/json",
    )

    private fun storageHeaders(): Map<String, String> = mapOf(
        "apikey" to config.anonKey,
        "Authorization" to "Bearer ${config.anonKey}",
        "Accept" to "application/octet-stream",
    )

    private companion object {
        const val MANIFEST_VIEW = "dataset_current_publication"
        val SUCCESS_RANGE = 200..299
        const val HTTP_NOT_FOUND = 404

        /**
         * The largest manifest accepted.
         *
         * A manifest is one small row; a few tens of kilobytes is already far beyond a real one. The
         * ceiling exists so a misrouted response (an HTML error page, an entire table) cannot be pulled
         * into memory as if it were a manifest.
         */
        const val MAX_MANIFEST_BYTES = 64L * 1024
    }
}

/**
 * Percent-encodes one URL path or query segment.
 *
 * Unreserved characters (RFC 3986 §2.3) pass through; everything else is escaped. Written out rather
 * than using `URLEncoder`, which encodes a space as `+` (correct for a form body, wrong in a path
 * segment) and leaves `*` unescaped.
 */
internal fun percentEncode(value: String): String {
    val builder = StringBuilder(value.length)
    for (byte in value.toByteArray(Charsets.UTF_8)) {
        val code = byte.toInt() and 0xFF
        val character = code.toChar()
        val unreserved = (character in 'a'..'z') || (character in 'A'..'Z') || (character in '0'..'9') ||
            character == '-' || character == '_' || character == '.' || character == '~'
        if (unreserved) {
            builder.append(character)
        } else {
            builder.append('%')
            builder.append(HEX_DIGITS[(code ushr 4) and 0x0F])
            builder.append(HEX_DIGITS[code and 0x0F])
        }
    }
    return builder.toString()
}

private const val HEX_DIGITS = "0123456789ABCDEF"
