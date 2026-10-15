package io.github.towkirahmedrony.salah.data.dataset.remote

import io.github.towkirahmedrony.salah.core.domain.dataset.remote.NetworkFailureKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * The outcome of one HTTP GET.
 *
 * Separate from [io.github.towkirahmedrony.salah.core.domain.dataset.remote.RemoteFetch] because the two
 * live on different sides of a boundary: this speaks in HTTP status codes and byte bodies, while the
 * remote source above it speaks in "here is a manifest" and "here is a payload". Keeping the translation
 * in one place means the transport can be faked with a status code and a byte array in a unit test, and
 * the source above it can be tested with a fake that never touches a socket.
 */
sealed interface HttpTransportResult {

    /** The server answered. [status] is the HTTP status; [body] is the response body, possibly empty. */
    data class Response(val status: Int, val body: ByteArray) : HttpTransportResult {
        // ByteArray equality is identity-based by default, which would make two equal responses unequal.
        // The response is a value, so it compares as one.
        override fun equals(other: Any?): Boolean =
            this === other || (other is Response && status == other.status && body.contentEquals(other.body))

        override fun hashCode(): Int = 31 * status + body.contentHashCode()
    }

    /** No HTTP response was produced. */
    data class Failure(val kind: NetworkFailureKind) : HttpTransportResult

    /** The body exceeded the caller's limit and was not read in full. */
    data class TooLarge(val limitBytes: Long) : HttpTransportResult
}

/**
 * A single, minimal HTTP surface for Salah's remote dataset reads.
 *
 * One method, one verb, no connection pooling, no interceptors, no response cache. Salah has exactly two
 * remote requests — a small manifest and one immutable payload — and this port is deliberately too small
 * to grow into a general HTTP client. `docs/ARCHITECTURE.md` §13 explains why a general client
 * (Retrofit/OkHttp/Ktor) was not introduced for two GETs.
 *
 * Android-free on purpose — it mentions no `android.*` type — so a JVM unit test can substitute a fake
 * without the platform.
 */
interface HttpTransport {

    /**
     * Performs a GET of [url] with [headers], applying [timeoutMillis] to connect and read, and refusing
     * a body larger than [maxBytes].
     */
    suspend fun get(
        url: String,
        headers: Map<String, String>,
        timeoutMillis: Long,
        maxBytes: Long,
    ): HttpTransportResult
}

/**
 * The real transport: `java.net.HttpURLConnection`.
 *
 * **Why this and not OkHttp.** It is in the JDK, so it adds no dependency, no version-catalogue entry and
 * no new failure mode, and it is entirely adequate for two sequential GETs with explicit timeouts and a
 * body-size ceiling. A general HTTP client would be a large dependency earning its keep only if the app
 * had many endpoints, retries, interceptors or a cache — which it deliberately does not. This is the one
 * class in the feature that is not unit-tested against a socket; it is exercised only through the fake
 * transport in tests and, in reality, by the app. That is stated plainly rather than papered over with a
 * test that would only be testing the JDK.
 *
 * Redirects are **not** followed: the manifest and payload URLs are computed from configured values and
 * returned paths, and silently following a `Location` header would let a response redirect the app to a
 * different host. A response that needs a redirect is reported as its status instead.
 */
class UrlConnectionHttpTransport : HttpTransport {

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        timeoutMillis: Long,
        maxBytes: Long,
    ): HttpTransportResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMillis.toInt()
                readTimeout = timeoutMillis.toInt()
                instanceFollowRedirects = false
                // Ask for an identity body so the byte count that arrives is the byte count that was
                // sent, and the size ceiling applies to the real payload rather than a compressed one.
                setRequestProperty("Accept-Encoding", "identity")
                for ((name, value) in headers) setRequestProperty(name, value)
            }

            val status = connection.responseCode
            if (status !in SUCCESS_RANGE) {
                connection.errorStream?.close()
                return@withContext HttpTransportResult.Response(status, ByteArray(0))
            }

            val stream = connection.inputStream
            val body = stream.use { readBounded(it, maxBytes) }
            HttpTransportResult.Response(status, body)
        } catch (tooLarge: BodyTooLargeException) {
            HttpTransportResult.TooLarge(tooLarge.limitBytes)
        } catch (timeout: SocketTimeoutException) {
            HttpTransportResult.Failure(NetworkFailureKind.TIMEOUT)
        } catch (io: IOException) {
            // UnknownHostException, ConnectException, SSLException and a plain connection reset are all
            // IOException subclasses; they are one outcome here for the reason given on NetworkFailureKind.
            HttpTransportResult.Failure(NetworkFailureKind.CONNECTION)
        } catch (error: Exception) {
            HttpTransportResult.Failure(NetworkFailureKind.UNKNOWN)
        } finally {
            connection?.disconnect()
        }
    }

    /** Reads at most [maxBytes] from [input], failing rather than truncating past the ceiling. */
    private fun readBounded(input: InputStream, maxBytes: Long): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(CHUNK_BYTES)
        var total = 0L
        while (true) {
            val count = input.read(chunk)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw BodyTooLargeException(maxBytes)
            buffer.write(chunk, 0, count)
        }
        return buffer.toByteArray()
    }

    /** Signals a body past the ceiling to the one handler above; never escapes this class. */
    private class BodyTooLargeException(val limitBytes: Long) : IOException()

    private companion object {
        const val CHUNK_BYTES = 16 * 1024
        val SUCCESS_RANGE = 200..299
    }
}
