package io.github.towkirahmedrony.salah.core.domain.metalprice

import io.github.towkirahmedrony.salah.core.domain.dataset.remote.RemoteFetch

/**
 * The published reference-price source, as a port.
 *
 * One operation, and it is read-only by construction: it asks for the published-price rows and returns the
 * response body as text. There is no write method, no parameter for a user's holding, and no way for a caller
 * to send anything but the query itself — which is what makes "the app never uploads a Zakat input" a property
 * of the type rather than a promise.
 *
 * The body is returned unparsed so that [MetalPriceParser] — and its tests — depend on no transport.
 */
interface RemoteMetalPriceSource {

    /**
     * Fetches the published reference-price rows.
     *
     * The request must not contact the upstream market-price provider, and must not include any value a user
     * entered: it is one filtered read of records an administrator has already published.
     */
    suspend fun fetchPublishedRows(): RemoteFetch<String>
}

/**
 * The non-secret published-price configuration.
 *
 * [baseUrl] and [anonKey] are the same Supabase project URL and publishable/anon client key the rest of the
 * app already uses; **no privileged key belongs here**. A `service_role` or secret key in the app would
 * bypass the row-level security that decides which records a client may read, and the app must never carry
 * one.
 *
 * `isConfigured` is the guard the feature hangs off: an unconfigured build is a normal state, treated as
 * "no published price, keep manual entry" rather than as an error. The app opens and calculates with no
 * remote configuration at all.
 */
data class MetalPriceRemoteConfig(
    val baseUrl: String = "",
    val anonKey: String = "",

    /** The published-price table (or view) the query reads. */
    val table: String = DEFAULT_TABLE,

    /** How long a single request may take, in milliseconds. */
    val requestTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {

    /** True when enough is configured to attempt a read. */
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && anonKey.isNotBlank() && table.isNotBlank()

    companion object {
        /** The published-price table named by the integration contract. */
        const val DEFAULT_TABLE: String = "metal_prices"

        /** Ten seconds, applied to both connect and read — the same budget the other remote reads use. */
        const val DEFAULT_TIMEOUT_MILLIS: Long = 10_000
    }
}
