package io.github.towkirahmedrony.salah.configuration

import io.github.towkirahmedrony.salah.BuildConfig
import io.github.towkirahmedrony.salah.core.domain.dataset.remote.DatasetRemoteConfig
import io.github.towkirahmedrony.salah.core.domain.dataset.remote.SupabaseConfiguration
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceRemoteConfig
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.remote.PrayerConfigRemoteConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What "the app is configured for Supabase" means, and what it does not mean.
 *
 * The three remote reads in Salah — the location-dataset publication, the prayer-configuration publication
 * and the published reference prices — each hold their own configuration, and each is guarded by the same
 * `isConfigured` question. This test pins that question to **one** definition: the same URL/key pair must
 * produce the same answer for all three, because a build where one slot attempts a request and another
 * refuses is a build whose behaviour no one can reason about. The paired assertions are the point; any of
 * them alone would pass with three divergent rules.
 *
 * **What this cannot check.** That the project exists, that the key is accepted, or that any row is
 * readable. Those are answered by the project and by row-level security at request time — a well-formed
 * configuration is not access, and the tests here deliberately do not pretend otherwise. See
 * `docs/SUPABASE_SETUP.md`. The transport itself is exercised by its own tests against a fake, and the real
 * socket path is not covered here or anywhere in the JVM suite.
 *
 * No real credential appears in this file: every key below is a labelled fixture, and the build's own
 * compiled values are only ever checked for *shape*, never printed.
 */
class SupabaseConfigurationTest {

    private val validUrl = "https://project.supabase.co"
    private val validKey = "sb_publishable_fixture_not_a_credential"

    /** The three configuration gates, all built from the same pair, so they can be compared directly. */
    private fun gates(baseUrl: String, anonKey: String): List<Boolean> = listOf(
        DatasetRemoteConfig(baseUrl = baseUrl, anonKey = anonKey).isConfigured,
        PrayerConfigRemoteConfig(baseUrl = baseUrl, anonKey = anonKey).isConfigured,
        MetalPriceRemoteConfig(baseUrl = baseUrl, anonKey = anonKey).isConfigured,
    )

    // A valid pair configures every gate ----------------------------------------------------------

    @Test
    fun `a valid URL and a valid publishable key configure all three gates`() {
        assertEquals(listOf(true, true, true), gates(validUrl, validKey))
    }

    @Test
    fun `a trailing slash is a valid project URL and normalises to the same endpoint`() {
        // The sources build `{url}/rest/v1/...` and trim the trailing slash, so both spellings must be
        // accepted here rather than one of them silently producing a request the other would have sent.
        assertTrue(SupabaseConfiguration.isValidBaseUrl("$validUrl/"))
        assertEquals(listOf(true, true, true), gates("$validUrl/", validKey))
    }

    // A missing or blank half configures nothing --------------------------------------------------

    @Test
    fun `a missing URL configures none of the gates`() {
        assertEquals(listOf(false, false, false), gates(baseUrl = "", anonKey = validKey))
    }

    @Test
    fun `a blank URL configures none of the gates`() {
        assertEquals(listOf(false, false, false), gates(baseUrl = "   ", anonKey = validKey))
    }

    @Test
    fun `a missing key configures none of the gates`() {
        assertEquals(listOf(false, false, false), gates(baseUrl = validUrl, anonKey = ""))
    }

    @Test
    fun `a blank key configures none of the gates`() {
        assertEquals(listOf(false, false, false), gates(baseUrl = validUrl, anonKey = " \t "))
    }

    @Test
    fun `a key carrying embedded whitespace is rejected rather than sent as a broken header`() {
        assertFalse(SupabaseConfiguration.isValidClientKey("sb_publishable_key\ntrailing"))
        assertEquals(listOf(false, false, false), gates(baseUrl = validUrl, anonKey = "two words"))
    }

    // A malformed URL is rejected, not attempted --------------------------------------------------

    @Test
    fun `a malformed URL configures none of the gates`() {
        listOf(
            "not-a-url",
            "project.supabase.co", // no scheme
            "ftp://project.supabase.co", // not http(s)
            "https://", // no host
            "https:// project.supabase.co", // whitespace inside the authority
            "ht!tp://project.supabase.co", // unparsable
            "/rest/v1/project",
        ).forEach { malformed ->
            assertFalse(
                "'$malformed' must not be accepted as a Supabase project URL",
                SupabaseConfiguration.isValidBaseUrl(malformed),
            )
            assertEquals(
                "'$malformed' must configure none of the gates",
                listOf(false, false, false),
                gates(baseUrl = malformed, anonKey = validKey),
            )
        }
    }

    // The holder defaults stay offline ------------------------------------------------------------

    @Test
    fun `a default-constructed configuration is offline for every gate`() {
        // The classes' own defaults are blank on purpose: a value that reaches the app has to come from the
        // build (BuildConfig), so the offline path can be exercised by construction and no test depends on
        // whatever the build happened to be configured with.
        assertEquals(
            listOf(false, false, false),
            listOf(
                DatasetRemoteConfig().isConfigured,
                PrayerConfigRemoteConfig().isConfigured,
                MetalPriceRemoteConfig().isConfigured,
            ),
        )
    }

    // The compiled fields are shaped correctly, whatever they are --------------------------------

    @Test
    fun `the compiled Supabase fields are blank or well formed, and never half configured`() {
        // Checked as *shape*, never as an exact value: an override supplied with `-P...` or through the
        // environment is a supported build, so a test that pinned the project URL would fail on exactly the
        // builds it must allow. What is asserted instead is that the Gradle -> BuildConfig mapping cannot
        // deliver a value the gates would misinterpret — and that neither half can arrive alone.
        val url = BuildConfig.SALAH_SUPABASE_URL
        val key = BuildConfig.SALAH_SUPABASE_ANON_KEY

        assertTrue(
            "SALAH_SUPABASE_URL is neither blank nor a valid URL, so no value could configure the app",
            url.isBlank() || SupabaseConfiguration.isValidBaseUrl(url),
        )
        assertTrue(
            "SALAH_SUPABASE_ANON_KEY is neither blank nor a valid client key",
            key.isBlank() || SupabaseConfiguration.isValidClientKey(key),
        )
        assertEquals(
            "the URL and the publishable key must be supplied together or not at all",
            url.isBlank(),
            key.isBlank(),
        )
    }
}
