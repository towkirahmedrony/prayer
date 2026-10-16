package io.github.towkirahmedrony.salah.data.metalprice

import io.github.towkirahmedrony.salah.core.domain.dataset.remote.NetworkFailureKind
import io.github.towkirahmedrony.salah.core.domain.dataset.remote.RemoteFetch
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceParser
import io.github.towkirahmedrony.salah.core.domain.metalprice.MetalPriceRemoteConfig
import io.github.towkirahmedrony.salah.data.dataset.remote.HttpTransport
import io.github.towkirahmedrony.salah.data.dataset.remote.HttpTransportResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one request this app makes for a published price, asserted against a fake transport.
 *
 * No socket is involved and no live Supabase project is touched. What is checked is the shape of the request —
 * the table, the filter that keeps retired rows off the wire, the columns the parser depends on, and the
 * **non-privileged** key — together with the mapping from an HTTP outcome to the port's own vocabulary.
 */
class SupabaseRemoteMetalPriceSourceTest {

    private val config = MetalPriceRemoteConfig(
        baseUrl = "https://example.invalid/",
        anonKey = "publishable-key",
    )

    private fun source(transport: HttpTransport) =
        SupabaseRemoteMetalPriceSource(transport = transport, config = config)

    @Test
    fun `the query reads the published-price table and only its active rows`() = runTest {
        val transport = RecordingHttpTransport(HttpTransportResult.Response(200, "[]".toByteArray()))

        source(transport).fetchPublishedRows()

        val url = requireNotNull(transport.lastUrl)
        assertTrue(url.startsWith("https://example.invalid/rest/v1/metal_prices?"))
        assertTrue("retired rows must be filtered out", url.contains("is_active=eq.true"))
        MetalPriceParser.SELECTED_COLUMNS.forEach { column ->
            assertTrue("the query does not select $column", url.contains(column))
        }
    }

    @Test
    fun `the request carries the publishable key and never a privileged one`() = runTest {
        val transport = RecordingHttpTransport(HttpTransportResult.Response(200, "[]".toByteArray()))

        source(transport).fetchPublishedRows()

        assertEquals("publishable-key", transport.lastHeaders["apikey"])
        assertEquals("Bearer publishable-key", transport.lastHeaders["Authorization"])
        // Only the two non-privileged headers and the accept header are ever sent.
        assertEquals(setOf("apikey", "Authorization", "Accept"), transport.lastHeaders.keys)
    }

    @Test
    fun `the request applies the configured timeout and a body-size ceiling`() = runTest {
        val transport = RecordingHttpTransport(HttpTransportResult.Response(200, "[]".toByteArray()))

        source(transport).fetchPublishedRows()

        assertEquals(config.requestTimeoutMillis, transport.lastTimeoutMillis)
        assertTrue("a response without a ceiling could be pulled in whole", transport.lastMaxBytes > 0)
    }

    @Test
    fun `a success is the response body, and a 404 is a missing table`() = runTest {
        val ok = source(RecordingHttpTransport(HttpTransportResult.Response(200, "[1]".toByteArray())))
        assertEquals(RemoteFetch.Success("[1]"), ok.fetchPublishedRows())

        val missing = source(RecordingHttpTransport(HttpTransportResult.Response(404, ByteArray(0))))
        assertEquals(RemoteFetch.NotFound, missing.fetchPublishedRows())
    }

    @Test
    fun `an error status is reported as itself`() = runTest {
        val refused = source(RecordingHttpTransport(HttpTransportResult.Response(403, ByteArray(0))))
        assertEquals(RemoteFetch.HttpError(403), refused.fetchPublishedRows())
    }

    @Test
    fun `a transport failure and an oversized body keep their own kinds`() = runTest {
        val failed = source(
            RecordingHttpTransport(HttpTransportResult.Failure(NetworkFailureKind.CONNECTION)),
        )
        assertEquals(RemoteFetch.NetworkFailure(NetworkFailureKind.CONNECTION), failed.fetchPublishedRows())

        val tooLarge = source(RecordingHttpTransport(HttpTransportResult.TooLarge(1024)))
        assertEquals(RemoteFetch.TooLarge(1024), tooLarge.fetchPublishedRows())
    }
}

/** A transport that records what it was asked for and answers with one canned result. */
private class RecordingHttpTransport(private val result: HttpTransportResult) : HttpTransport {

    var lastUrl: String? = null
        private set

    var lastHeaders: Map<String, String> = emptyMap()
        private set

    var lastTimeoutMillis: Long = 0
        private set

    var lastMaxBytes: Long = 0
        private set

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        timeoutMillis: Long,
        maxBytes: Long,
    ): HttpTransportResult {
        lastUrl = url
        lastHeaders = headers
        lastTimeoutMillis = timeoutMillis
        lastMaxBytes = maxBytes
        return result
    }
}
