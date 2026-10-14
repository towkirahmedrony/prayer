package io.github.towkirahmedrony.salah.data.location

import io.github.towkirahmedrony.salah.core.domain.DiagnosticsLogger
import io.github.towkirahmedrony.salah.core.domain.SalahDiagnosticEvents
import io.github.towkirahmedrony.salah.core.domain.location.GeoPoint
import io.github.towkirahmedrony.salah.core.domain.location.NominatimAddressParser
import io.github.towkirahmedrony.salah.core.domain.location.ReverseGeocodeResult
import io.github.towkirahmedrony.salah.core.domain.location.ReverseGeocoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Reverse geocoding against a Nominatim-compatible endpoint, over HTTPS.
 *
 * **Why no HTTP client dependency.** Salah has no networking stack — every feature before this one is
 * offline — so adding OkHttp or Ktor plus a serialization plugin for one GET request would be a large
 * toolchain change for a small need. `HttpURLConnection` is in the platform, is what a one-shot request
 * should use, and keeps the dependency list unchanged. When a second network call appears, that is the
 * moment to introduce a client, not before.
 *
 * **Where the endpoint comes from.** `BuildConfig.GEOCODER_BASE_URL`, so a build can point at a
 * self-hosted instance or a paid provider without touching this class, and no key or URL is written into
 * the source. The default is OpenStreetMap's public Nominatim, whose usage policy requires a real
 * `User-Agent` identifying the application — supplied from `BuildConfig.GEOCODER_USER_AGENT` — a
 * maximum of one request per second across the whole application, and caching; this class therefore
 * relies on the view model's debounce and in-memory cache rather than firing a request per frame. See
 * `docs/MAP_PICKER.md` for the operating limits.
 *
 * **Nothing here throws.** A timeout, an absent connection, a rate limit, a non-200 status and an
 * unparsable body are all [ReverseGeocodeResult.Unavailable], and the picker saves the coordinates
 * regardless. Cancellation is the one exception: it is rethrown so a request for a point the user has
 * already moved away from is genuinely cancelled instead of being reported as a lookup failure.
 *
 * **Nothing here describes the user.** Diagnostics record a reason code and whether components were
 * found, never the latitude, the longitude or the returned address.
 */
class HttpReverseGeocoder(
    private val baseUrl: String,
    private val userAgent: String,
    private val logger: DiagnosticsLogger,
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
) : ReverseGeocoder {

    override suspend fun reverse(point: GeoPoint): ReverseGeocodeResult {
        if (!point.isFinite) return ReverseGeocodeResult.Unavailable(REASON_INVALID_POINT)
        return withContext(Dispatchers.IO) {
            try {
                request(point)
            } catch (cancellation: CancellationException) {
                // Not a failure: the caller cancelled this lookup because the selected point changed.
                throw cancellation
            } catch (ignored: SocketTimeoutException) {
                unavailable(REASON_TIMEOUT)
            } catch (ignored: IOException) {
                unavailable(REASON_NETWORK)
            } catch (ignored: Exception) {
                unavailable(REASON_FAILED)
            }
        }
    }

    private suspend fun request(point: GeoPoint): ReverseGeocodeResult {
        val url = URL(
            "$baseUrl/reverse?format=jsonv2&addressdetails=1&zoom=$ZOOM&accept-language=en" +
                "&lat=${point.latitude}&lon=${point.longitude}",
        )
        val connection = url.openConnection() as HttpURLConnection
        // `return` on the `try`: the block's value is the parsed result, and the `finally` still runs on
        // every path, including the early return for a non-200 status and the rethrow of a cancellation.
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = true
            // Required by the provider's usage policy: a stock library User-Agent is refused.
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Language", "en")

            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                return when (status) {
                    HTTP_TOO_MANY_REQUESTS -> unavailable(REASON_RATE_LIMITED)
                    else -> unavailable("http_$status")
                }
            }

            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            // A cancelled lookup must not continue into parsing after the selection changed.
            currentCoroutineContext().ensureActive()

            val result = NominatimAddressParser.parse(body)
            when (result) {
                is ReverseGeocodeResult.Found -> logger.info(
                    SalahDiagnosticEvents.LOCATION_MAP_GEOCODER_RESOLVED,
                    mapOf(
                        "hasPlaceName" to (result.address.placeName != null),
                        "hasLocality" to (result.address.locality != null),
                        "hasRoad" to (result.address.road != null),
                    ),
                )

                is ReverseGeocodeResult.NotFound -> logger.info(
                    SalahDiagnosticEvents.LOCATION_MAP_GEOCODER_UNAVAILABLE,
                    mapOf("reason" to result.reason),
                )

                is ReverseGeocodeResult.Unavailable -> logger.warn(
                    SalahDiagnosticEvents.LOCATION_MAP_GEOCODER_UNAVAILABLE,
                    mapOf("reason" to result.reason),
                )
            }
            result
        } finally {
            connection.disconnect()
        }
    }

    private fun unavailable(reason: String): ReverseGeocodeResult.Unavailable {
        logger.warn(SalahDiagnosticEvents.LOCATION_MAP_GEOCODER_UNAVAILABLE, mapOf("reason" to reason))
        return ReverseGeocodeResult.Unavailable(reason)
    }

    companion object {
        /** Address components at building scale, without asking the provider for a full POI record. */
        const val ZOOM: Int = 16

        const val DEFAULT_CONNECT_TIMEOUT_MILLIS: Int = 8_000
        const val DEFAULT_READ_TIMEOUT_MILLIS: Int = 8_000

        const val REASON_INVALID_POINT: String = "invalid_point"
        const val REASON_NETWORK: String = "network"
        const val REASON_TIMEOUT: String = "timeout"
        const val REASON_RATE_LIMITED: String = "rate_limited"
        const val REASON_FAILED: String = "failed"

        private const val HTTP_TOO_MANY_REQUESTS: Int = 429
    }
}
