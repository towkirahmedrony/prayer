package io.github.towkirahmedrony.salah.core.domain.prayerconfig.remote

import io.github.towkirahmedrony.salah.core.domain.dataset.remote.RemoteFetch
import io.github.towkirahmedrony.salah.core.domain.dataset.remote.SupabaseConfiguration

/**
 * The remote prayer-configuration publication source, as a port.
 *
 * Two operations, both "fetch a small text response from the configured Supabase project": read the
 * current-publication manifest for the configuration slot, and read one revision's payload. Nothing above
 * this port knows about Supabase, HTTP verbs, headers or JSON — which is what lets the synchroniser be
 * tested against a fake that returns a manifest and a payload without a server.
 *
 * It reuses [io.github.towkirahmedrony.salah.core.domain.dataset.remote.RemoteFetch] rather than defining a
 * second result type, so the dataset path and the prayer-configuration path report network outcomes in the
 * same vocabulary. The transport itself is the same
 * [io.github.towkirahmedrony.salah.data.dataset.remote.HttpTransport]; no second networking client exists.
 */
interface RemotePrayerConfigSource {

    /**
     * Fetches the current-publication manifest for the configured configuration slot.
     *
     * The body is returned as raw text; parsing belongs to
     * [io.github.towkirahmedrony.salah.core.domain.prayerconfig.PrayerConfigurationParser] so the parser and
     * its tests do not depend on any transport.
     */
    suspend fun fetchManifest(): RemoteFetch<String>

    /**
     * Fetches the payload text for the revision named [revisionId].
     *
     * Fetched only when the manifest names a revision other than the one already accepted, so an unchanged
     * publication costs one small manifest request and no payload.
     */
    suspend fun fetchContent(revisionId: String): RemoteFetch<String>
}

/**
 * The non-secret prayer-configuration remote configuration.
 *
 * [baseUrl] is the Supabase project URL and [anonKey] is the publishable/anon client key — neither is a
 * secret in the sense a service-role key is. **No privileged key belongs here.** [configurationId] names
 * the publication slot (a future country or convention reuses the same schema under a different slot).
 *
 * [isConfigured] is the guard the whole feature hangs off: an empty configuration is a normal state, and
 * every code path treats it as "do nothing, keep working offline". The URL and key are judged by
 * [SupabaseConfiguration] — the same rule the dataset and published-price slots use — so the three slots
 * cannot disagree about whether the app is configured.
 */
data class PrayerConfigRemoteConfig(
    val baseUrl: String = "",
    val anonKey: String = "",
    val configurationId: String = DEFAULT_CONFIGURATION_ID,
    val requestTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {

    /** True when enough is configured to attempt a fetch. */
    val isConfigured: Boolean
        get() = SupabaseConfiguration.isValidBaseUrl(baseUrl) &&
            SupabaseConfiguration.isValidClientKey(anonKey) &&
            configurationId.isNotBlank()

    companion object {
        /** The single slot this phase publishes. */
        const val DEFAULT_CONFIGURATION_ID: String = "bd_prayer"

        /** Ten seconds, applied to both connect and read, matching the dataset source. */
        const val DEFAULT_TIMEOUT_MILLIS: Long = 10_000
    }
}
