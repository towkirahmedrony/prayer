package io.github.towkirahmedrony.salah.data.mosque

import io.github.towkirahmedrony.salah.core.domain.dataset.remote.NetworkFailureKind
import io.github.towkirahmedrony.salah.core.domain.location.GeoPoint
import io.github.towkirahmedrony.salah.core.domain.mosque.Mosque
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueQuery
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueSearchConfig
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueSourceResult
import io.github.towkirahmedrony.salah.data.dataset.remote.HttpTransport
import io.github.towkirahmedrony.salah.data.dataset.remote.HttpTransportResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Geoapify Places request and its HTTP outcomes.
 *
 * The URL is asserted rather than trusted: the comma-separated category list, the longitude-first
 * circle filter, the proximity bias and the key all have to be in one request or the service returns
 * the wrong places. The failures are asserted as distinct values because the screen explains rate
 * limiting, a refused key and a dead connection differently.
 */
class GeoapifyMosqueSourceTest {

    private val query = MosqueQuery(
        center = GeoPoint(23.8103, 90.4125),
        radiusMetres = 5_000,
        pageSize = 50,
        maxPages = 3,
        language = "bn",
    )

    @Test
    fun `a blank key makes no request at all`() = runTest {
        val transport = RecordingTransport()
        val source = GeoapifyMosqueSource(
            transport = transport,
            config = MosqueSearchConfig(apiKey = ""),
        )

        val result = source.search(query, offset = 0)

        assertEquals(MosqueSourceResult.NotConfigured, result)
        assertEquals(0, transport.calls)
    }

    @Test
    fun `a non-finite centre makes no request at all`() = runTest {
        val transport = RecordingTransport()
        val source = source(transport)

        val result = source.search(
            query.copy(center = GeoPoint(Double.NaN, 90.0)),
            offset = 0,
        )

        assertEquals(MosqueSourceResult.NotConfigured, result)
        assertEquals(0, transport.calls)
    }

    @Test
    fun `the request carries both categories, the circle, the bias, paging and the key`() = runTest {
        val transport = RecordingTransport()
        val source = source(transport, apiKey = "key with space")

        source.search(query, offset = 100)

        val url = transport.lastUrl!!
        assertTrue(url.startsWith(MosqueSearchConfig.DEFAULT_BASE_URL + "?"))
        assertTrue(
            url.contains(
                "categories=tourism.sights.place_of_worship.mosque" +
                    "%2Creligion.place_of_worship.islam",
            ),
        )
        assertTrue(url.contains("filter=circle%3A90.412500%2C23.810300%2C5000"))
        assertTrue(url.contains("bias=proximity%3A90.412500%2C23.810300"))
        assertTrue(url.contains("limit=50"))
        assertTrue(url.contains("offset=100"))
        assertTrue(url.contains("lang=bn"))
        assertTrue(url.contains("apiKey=key+with+space"))
        assertEquals(mapOf("Accept" to "application/json"), transport.lastHeaders)
    }

    @Test
    fun `an empty language falls back to English`() = runTest {
        val transport = RecordingTransport()
        val source = source(transport)

        source.search(query.copy(language = "  "), offset = 0)

        assertTrue(transport.lastUrl!!.contains("lang=en"))
    }

    @Test
    fun `a readable response is mapped to a successful page`() = runTest {
        val transport = RecordingTransport(
            response = HttpTransportResult.Response(
                status = 200,
                body = """
                    {
                      "type": "FeatureCollection",
                      "features": [
                        {
                          "type": "Feature",
                          "properties": {
                            "name": "Baitul Mukarram",
                            "place_id": "p1",
                            "categories": ["religion.place_of_worship.islam"]
                          },
                          "geometry": { "type": "Point", "coordinates": [90.41, 23.81] }
                        }
                      ]
                    }
                """.trimIndent().toByteArray(),
            ),
        )

        val result = source(transport).search(query, offset = 0)

        val success = result as MosqueSourceResult.Success
        assertEquals(1, success.featureCount)
        assertEquals(listOf("p1"), success.mosques.map(Mosque::placeId))
    }

    @Test
    fun `an unreadable success body is malformed`() = runTest {
        val transport = RecordingTransport(
            response = HttpTransportResult.Response(status = 200, body = "<html>nope</html>".toByteArray()),
        )

        assertEquals(MosqueSourceResult.Malformed, source(transport).search(query, 0))
    }

    @Test
    fun `refused keys, rate limits and server errors keep their distinctions`() = runTest {
        assertEquals(
            MosqueSourceResult.HttpError(401),
            source(RecordingTransport(response = response(401))).search(query, 0),
        )
        assertEquals(
            MosqueSourceResult.HttpError(403),
            source(RecordingTransport(response = response(403))).search(query, 0),
        )
        assertEquals(
            MosqueSourceResult.RateLimited,
            source(RecordingTransport(response = response(429))).search(query, 0),
        )
        assertEquals(
            MosqueSourceResult.HttpError(503),
            source(RecordingTransport(response = response(503))).search(query, 0),
        )
    }

    @Test
    fun `a transport failure and an oversized body are reported as such`() = runTest {
        assertEquals(
            MosqueSourceResult.NetworkFailure(NetworkFailureKind.TIMEOUT),
            source(
                RecordingTransport(
                    response = HttpTransportResult.Failure(NetworkFailureKind.TIMEOUT),
                ),
            ).search(query, 0),
        )
        assertEquals(
            MosqueSourceResult.NetworkFailure(NetworkFailureKind.CONNECTION),
            source(
                RecordingTransport(
                    response = HttpTransportResult.Failure(NetworkFailureKind.CONNECTION),
                ),
            ).search(query, 0),
        )
        assertEquals(
            MosqueSourceResult.TooLarge(1234L),
            source(
                RecordingTransport(response = HttpTransportResult.TooLarge(1234L)),
            ).search(query, 0),
        )
    }

    @Test
    fun `the page size is bounded and a negative offset becomes zero`() = runTest {
        val transport = RecordingTransport()
        val source = source(transport)

        source.search(query.copy(pageSize = 10_000), offset = -5)

        val url = transport.lastUrl!!
        assertTrue(url.contains("limit=500"))
        assertTrue(url.contains("offset=0"))
        assertFalse(url.contains("offset=-5"))
    }

    private fun source(
        transport: HttpTransport,
        apiKey: String = "test-key",
    ) = GeoapifyMosqueSource(
        transport = transport,
        config = MosqueSearchConfig(apiKey = apiKey),
    )

    private fun response(status: Int) = HttpTransportResult.Response(status, ByteArray(0))

    private class RecordingTransport(
        private val response: HttpTransportResult = HttpTransportResult.Response(200, ByteArray(0)),
    ) : HttpTransport {

        var calls: Int = 0
            private set
        var lastUrl: String? = null
            private set
        var lastHeaders: Map<String, String>? = null
            private set

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            timeoutMillis: Long,
            maxBytes: Long,
        ): HttpTransportResult {
            calls++
            lastUrl = url
            lastHeaders = headers
            return response
        }
    }
}
