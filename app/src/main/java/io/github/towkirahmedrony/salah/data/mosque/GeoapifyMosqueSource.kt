package io.github.towkirahmedrony.salah.data.mosque

import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueQuery
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueSearchConfig
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueSearchSource
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueSourceResult
import io.github.towkirahmedrony.salah.data.dataset.remote.HttpTransport
import io.github.towkirahmedrony.salah.data.dataset.remote.HttpTransportResult
import java.net.URLEncoder
import java.util.Locale

/**
 * The Geoapify Places implementation of [MosqueSearchSource].
 *
 * **Endpoint.** `GET https://api.geoapify.com/v2/places` with:
 *
 *  * `categories` — both the mosque-specific `tourism.sights.place_of_worship.mosque` and the
 *    broader `religion.place_of_worship.islam`; see [io.github.towkirahmedrony.salah.core.domain.mosque.MosqueGeo]
 *    for why both are requested and how the broader one is filtered;
 *  * `filter=circle:longitude,latitude,radius` — the hard geographic boundary;
 *  * `bias=proximity:longitude,latitude` — orders the service's own results by distance;
 *  * `limit`, `offset`, `lang` and `apiKey`.
 *
 * No Place Details endpoint is called: every field shown comes from this one response.
 *
 * **The key.** It is read from [MosqueSearchConfig], which is built from
 * `BuildConfig.GEOAPIFY_API_KEY`. A blank key is [MosqueSourceResult.NotConfigured] and no request
 * is built. The key appears only in the request URL the transport sends and is never logged or
 * included in a failure value.
 *
 * **Cancellation.** The transport is a suspend call; cancellation propagates through it, and this
 * class adds no `runCatching` around it, so navigating away stops the request rather than leaving a
 * background coroutine behind.
 */
class GeoapifyMosqueSource(
    private val transport: HttpTransport,
    private val config: MosqueSearchConfig,
) : MosqueSearchSource {

    override suspend fun search(query: MosqueQuery, offset: Int): MosqueSourceResult {
        if (!config.isConfigured || !query.center.isFinite) {
            return MosqueSourceResult.NotConfigured
        }

        val limit = query.pageSize.coerceIn(1, MAX_PAGE_SIZE)
        val safeOffset = offset.coerceAtLeast(0)
        val url = buildUrl(query, limit = limit, offset = safeOffset)

        return when (val response = transport.get(
            url = url,
            headers = REQUEST_HEADERS,
            timeoutMillis = config.requestTimeoutMillis,
            maxBytes = config.maxResponseBytes,
        )) {
            is HttpTransportResult.Failure -> MosqueSourceResult.NetworkFailure(response.kind)
            is HttpTransportResult.TooLarge -> MosqueSourceResult.TooLarge(response.limitBytes)
            is HttpTransportResult.Response -> when {
                response.status == HTTP_UNAUTHORIZED || response.status == HTTP_FORBIDDEN ->
                    MosqueSourceResult.HttpError(response.status)

                response.status == HTTP_TOO_MANY_REQUESTS -> MosqueSourceResult.RateLimited
                response.status !in HTTP_SUCCESS_RANGE -> MosqueSourceResult.HttpError(response.status)
                else -> response.body.toSourceResult()
            }
        }
    }

    private fun ByteArray.toSourceResult(): MosqueSourceResult {
        val page = GeoapifyMosqueParser.parse(this) ?: return MosqueSourceResult.Malformed
        return MosqueSourceResult.Success(
            mosques = page.mosques,
            featureCount = page.featureCount,
        )
    }

    /**
     * Builds the request URL.
     *
     * Every value is encoded as one query component. The categories are joined with a comma *before*
     * encoding, because Geoapify expects a comma-separated list in a single parameter, not repeated
     * parameters. Coordinates are written with six decimals — about 11 cm — which is far finer than a
     * search radius and keeps the URL readable in a diagnostic trace.
     */
    internal fun buildUrl(query: MosqueQuery, limit: Int, offset: Int): String {
        val center = query.center
        val language = query.language.trim().ifEmpty { DEFAULT_LANGUAGE }
        val categories = config.categories.joinToString(separator = ",")
        val filter = "circle:%.6f,%.6f,%d".format(
            Locale.ROOT,
            center.longitude,
            center.latitude,
            query.radiusMetres,
        )
        val bias = "proximity:%.6f,%.6f".format(Locale.ROOT, center.longitude, center.latitude)

        val parameters = listOf(
            "categories" to categories,
            "filter" to filter,
            "bias" to bias,
            "limit" to limit.toString(),
            "offset" to offset.toString(),
            "lang" to language,
            "apiKey" to config.apiKey,
        )

        return buildString {
            append(config.baseUrl)
            append('?')
            parameters.forEachIndexed { index, (name, value) ->
                if (index > 0) append('&')
                append(name)
                append('=')
                append(URLEncoder.encode(value, Charsets.UTF_8.name()))
            }
        }
    }

    private companion object {
        val REQUEST_HEADERS = mapOf("Accept" to "application/json")
        val HTTP_SUCCESS_RANGE = 200..299
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val MAX_PAGE_SIZE = 500
        const val DEFAULT_LANGUAGE = "en"
    }
}
