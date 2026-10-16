package io.github.towkirahmedrony.salah.data.metalprice

import io.github.towkirahmedrony.salah.core.domain.dataset.remote.RemoteFetch
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceParser
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceRemoteConfig
import io.github.towkirahmedrony.salah.core.domain.metalprice.RemoteMetalPriceSource
import io.github.towkirahmedrony.salah.data.dataset.remote.HttpTransport
import io.github.towkirahmedrony.salah.data.dataset.remote.HttpTransportResult
import io.github.towkirahmedrony.salah.data.dataset.remote.percentEncode

/**
 * The Supabase implementation of [RemoteMetalPriceSource], over the shared transport port.
 *
 * One read-only PostgREST query against the published-price table, reached with the publishable (anon) key —
 * the same non-privileged key the app's other remote reads use:
 *
 * ```text
 * GET {url}/rest/v1/metal_prices?is_active=eq.true&select=<contract columns>
 * ```
 *
 * **Why only `is_active` is filtered server-side.** Whether a record is *published* is cheap to narrow at the
 * server, and it keeps inactive rows — an operator's retired rate — off the wire entirely. Everything else,
 * including the verification status and the currency, is re-checked by the parser: a client must be safe
 * against a database whose policies are looser than this app expects, and the database must be safe against a
 * client that forgets to check. Both ends enforcing the same rule is deliberate.
 *
 * **This is not the market-price provider.** Nothing here contacts the upstream provider or any other
 * third-party service; the provider is read by the administrator workflow that publishes these records, which
 * is outside this app. The app reads only what has already been approved and published.
 *
 * **This is not a security boundary.** Row-level security decides which rows a client may read; the URL is a
 * query, not a permission. The app never carries a privileged key, and an access-denied response is reported
 * and left alone rather than worked around.
 */
class SupabaseRemoteMetalPriceSource(
    private val transport: HttpTransport,
    private val config: MetalPriceRemoteConfig,
) : RemoteMetalPriceSource {

    override suspend fun fetchPublishedRows(): RemoteFetch<String> {
        val url = buildString {
            append(config.baseUrl.trimEnd('/'))
            append("/rest/v1/")
            append(percentEncode(config.table))
            append("?is_active=eq.true&select=")
            append(MetalPriceParser.SELECTED_COLUMNS.joinToString(","))
        }
        return when (
            val result = transport.get(url, restHeaders(), config.requestTimeoutMillis, MAX_RESPONSE_BYTES)
        ) {
            is HttpTransportResult.Response -> when {
                result.status in SUCCESS_RANGE -> RemoteFetch.Success(String(result.body, Charsets.UTF_8))
                // A 404 from PostgREST on a table-shaped URL means the table (or the policy that would expose
                // it) is not there, which is a deployment dependency rather than "no price published".
                result.status == HTTP_NOT_FOUND -> RemoteFetch.NotFound
                else -> RemoteFetch.HttpError(result.status)
            }

            is HttpTransportResult.Failure -> RemoteFetch.NetworkFailure(result.kind)
            is HttpTransportResult.TooLarge -> RemoteFetch.TooLarge(result.limitBytes)
        }
    }

    private fun restHeaders(): Map<String, String> = mapOf(
        "apikey" to config.anonKey,
        "Authorization" to "Bearer ${config.anonKey}",
        "Accept" to "application/json",
    )

    private companion object {
        val SUCCESS_RANGE = 200..299
        const val HTTP_NOT_FOUND = 404

        /**
         * The largest response accepted.
         *
         * The published rates are a handful of rows; a ceiling stops a misrouted response — an HTML error
         * page, or an entire table — being pulled into memory as if it were the contract.
         */
        const val MAX_RESPONSE_BYTES = 256L * 1024
    }
}
