package io.github.towkirahmedrony.salah.data.prayerconfig.remote

import io.github.towkirahmedrony.salah.core.domain.dataset.remote.JsonReader
import io.github.towkirahmedrony.salah.core.domain.dataset.remote.JsonValue
import io.github.towkirahmedrony.salah.core.domain.dataset.remote.RemoteFetch
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.remote.PrayerConfigRemoteConfig
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.remote.RemotePrayerConfigSource
import io.github.towkirahmedrony.salah.data.dataset.remote.HttpTransport
import io.github.towkirahmedrony.salah.data.dataset.remote.HttpTransportResult
import io.github.towkirahmedrony.salah.data.dataset.remote.percentEncode

/**
 * The Supabase implementation of [RemotePrayerConfigSource], over the shared transport port.
 *
 * Two read-only PostgREST queries against the same view, both reached with the publishable (anon) key:
 *
 * ```text
 * GET {url}/rest/v1/prayer_config_current_publication?configuration_id=eq.{slot}&limit=1&select=<manifest columns>
 * GET {url}/rest/v1/prayer_config_current_publication?configuration_id=eq.{slot}&revision_id=eq.{rev}&select=payload
 * ```
 *
 * The first deliberately omits `payload`, so an unchanged revision — one the app already accepts — costs
 * one small request with no configuration content. The second is issued only when the revision changed,
 * and filters on the revision id, so a pointer that moves between the two calls yields an empty result the
 * app treats as "nothing to do" rather than as a mismatched payload.
 *
 * The payload column is `text`, not `jsonb`, on purpose: PostgREST returns a `jsonb` value re-serialised,
 * which would change the byte sequence the published checksum was taken over. As `text` it comes back as a
 * JSON string, and the app uses its exact characters. Which rows a client may see is decided by row-level
 * security on the server; these URLs are not a security boundary and are not treated as one.
 */
class SupabaseRemotePrayerConfigSource(
    private val transport: HttpTransport,
    private val config: PrayerConfigRemoteConfig,
    private val manifestView: String = MANIFEST_VIEW,
) : RemotePrayerConfigSource {

    override suspend fun fetchManifest(): RemoteFetch<String> {
        val url = buildString {
            append(baseUrl())
            append("/rest/v1/")
            append(manifestView)
            append("?configuration_id=eq.")
            append(percentEncode(config.configurationId))
            append("&limit=1&select=")
            append(MANIFEST_COLUMNS)
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

    override suspend fun fetchContent(revisionId: String): RemoteFetch<String> {
        val url = buildString {
            append(baseUrl())
            append("/rest/v1/")
            append(manifestView)
            append("?configuration_id=eq.")
            append(percentEncode(config.configurationId))
            append("&revision_id=eq.")
            append(percentEncode(revisionId))
            append("&limit=1&select=payload")
        }
        val body = when (
            val result = transport.get(url, restHeaders(), config.requestTimeoutMillis, MAX_CONTENT_BYTES)
        ) {
            is HttpTransportResult.Response -> when {
                result.status in SUCCESS_RANGE -> String(result.body, Charsets.UTF_8)
                result.status == HTTP_NOT_FOUND -> return RemoteFetch.NotFound
                else -> return RemoteFetch.HttpError(result.status)
            }
            is HttpTransportResult.Failure -> return RemoteFetch.NetworkFailure(result.kind)
            is HttpTransportResult.TooLarge -> return RemoteFetch.TooLarge(result.limitBytes)
        }

        // A response that is a well-formed empty array means the revision is no longer current.
        val payload = extractPayload(body) ?: return RemoteFetch.NotFound
        return RemoteFetch.Success(payload)
    }

    /** The `payload` string of the first row, or `null` when the body is not that shape. */
    private fun extractPayload(body: String): String? {
        val root = JsonReader.parse(body) ?: return null
        val value = when (root) {
            is JsonValue.ArrayValue -> root.elements.firstOrNull() ?: return null
            is JsonValue.ObjectValue -> root
            else -> return null
        }
        return value.stringOrNull(FIELD_PAYLOAD)?.takeIf { it.isNotBlank() }
    }

    private fun baseUrl(): String = config.baseUrl.trimEnd('/')

    private fun restHeaders(): Map<String, String> = mapOf(
        "apikey" to config.anonKey,
        "Authorization" to "Bearer ${config.anonKey}",
        "Accept" to "application/json",
    )

    private companion object {
        const val MANIFEST_VIEW = "prayer_config_current_publication"
        const val FIELD_PAYLOAD = "payload"
        val SUCCESS_RANGE = 200..299
        const val HTTP_NOT_FOUND = 404

        const val MANIFEST_COLUMNS =
            "configuration_id,revision_id,revision_number,schema_version,status,checksum,published_at,notes"

        /** A manifest is one small row; tens of kilobytes is already far beyond a real one. */
        const val MAX_MANIFEST_BYTES = 64L * 1024

        /** A configuration is a few kilobytes even with many area overrides. */
        const val MAX_CONTENT_BYTES = 512L * 1024
    }
}
