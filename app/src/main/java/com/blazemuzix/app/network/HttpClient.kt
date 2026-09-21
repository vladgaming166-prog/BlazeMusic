package com.blazemuzix.app.network

import com.blazemuzix.app.data.cache.ResponseCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Small, dependency-free HTTP client built on HttpURLConnection (works on API 19).
 *
 *  - asynchronous (coroutines on Dispatchers.IO), never touches the main thread
 *  - connect/read timeouts
 *  - bounded retry with exponential backoff for transient failures
 *  - offline detection before a request is even attempted
 *  - request cancellation (connection is disconnected when the coroutine is cancelled)
 *  - rate-limit handling (HTTP 429 / 403 quota errors)
 *  - at most [MAX_CONCURRENT] simultaneous requests
 *  - optional response caching with TTL
 */
class HttpClient(
    private val networkMonitor: NetworkMonitor,
    private val cache: ResponseCache
) {

    init {
        Tls12SocketFactory.installIfNeeded()
    }

    private val semaphore = Semaphore(MAX_CONCURRENT)

    /** GET [url] and return the response body as text. Caches for [cacheTtlMs] when > 0. */
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        cacheTtlMs: Long = 0L,
        cacheKey: String = url,
        allowStaleWhenOffline: Boolean = true
    ): String {
        if (cacheTtlMs > 0) {
            cache.get(cacheKey, cacheTtlMs)?.let { return it }
        }
        if (!networkMonitor.isOnline) {
            if (allowStaleWhenOffline) cache.get(cacheKey, Long.MAX_VALUE)?.let { return it }
            throw ApiException.Offline()
        }
        val body = execute("GET", url, headers, null, null)
        if (cacheTtlMs > 0) cache.put(cacheKey, body)
        return body
    }

    suspend fun getJson(
        url: String,
        headers: Map<String, String> = emptyMap(),
        cacheTtlMs: Long = 0L,
        cacheKey: String = url
    ): JSONObject = parse(get(url, headers, cacheTtlMs, cacheKey))

    /** POST an application/x-www-form-urlencoded body. Never cached, never retried on 4xx. */
    suspend fun postForm(url: String, headers: Map<String, String>, form: Map<String, String>): JSONObject {
        if (!networkMonitor.isOnline) throw ApiException.Offline()
        val body = form.entries.joinToString("&") { (k, v) -> encode(k) + "=" + encode(v) }
        val merged = HashMap(headers).apply { put("Content-Type", "application/x-www-form-urlencoded") }
        return parse(execute("POST", url, merged, body.toByteArray(Charsets.UTF_8), null))
    }

    private fun parse(raw: String): JSONObject = try {
        JSONObject(raw)
    } catch (e: Exception) {
        throw ApiException.Parse(e)
    }

    private suspend fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
        contentType: String?
    ): String = semaphore.withPermit {
        var attempt = 0
        var lastError: ApiException? = null
        while (attempt <= MAX_RETRIES) {
            try {
                return@withPermit withContext(Dispatchers.IO) { rawRequest(method, url, headers, body, contentType) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                lastError = e
                val transient = e is ApiException.ServerError || e is ApiException.Timeout || e is ApiException.Network
                if (!transient || attempt == MAX_RETRIES) throw e
                if (!networkMonitor.isOnline) throw ApiException.Offline()
                delay(BASE_BACKOFF_MS shl attempt)
                attempt++
            }
        }
        throw lastError ?: ApiException.Network()
    }

    private suspend fun rawRequest(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
        contentType: String?
    ): String = suspendCancellableCoroutine { cont ->
        var connection: HttpURLConnection? = null
        cont.invokeOnCancellation {
            try { connection?.disconnect() } catch (_: Exception) {}
        }
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                useCaches = false
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Encoding", "gzip")
                setRequestProperty("User-Agent", USER_AGENT)
                contentType?.let { setRequestProperty("Content-Type", it) }
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                if (body != null) {
                    doOutput = true
                    setFixedLengthStreamingMode(body.size)
                    outputStream.use { it.write(body) }
                }
            }
            val code = connection.responseCode
            val stream: InputStream? = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.let { readBody(it, connection.contentEncoding) } ?: ""
            if (code in 200..299) {
                cont.resume(text)
            } else {
                cont.resumeWithException(mapHttpError(code, text, connection))
            }
        } catch (e: ApiException) {
            if (cont.isActive) cont.resumeWithException(e)
        } catch (e: SocketTimeoutException) {
            if (cont.isActive) cont.resumeWithException(ApiException.Timeout(e))
        } catch (e: UnknownHostException) {
            if (cont.isActive) cont.resumeWithException(if (networkMonitor.isOnline) ApiException.Network(e) else ApiException.Offline())
        } catch (e: IOException) {
            if (cont.isActive) cont.resumeWithException(ApiException.Network(e))
        } catch (e: Exception) {
            if (cont.isActive) cont.resumeWithException(ApiException.Network(e))
        } finally {
            try { connection?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun readBody(stream: InputStream, encoding: String?): String {
        val input = if (encoding.equals("gzip", ignoreCase = true)) GZIPInputStream(stream) else stream
        return input.bufferedReader(Charsets.UTF_8).use(BufferedReader::readText)
    }

    private fun mapHttpError(code: Int, body: String, connection: HttpURLConnection): ApiException {
        val reason = try {
            val json = JSONObject(body)
            json.optJSONObject("error")?.let { err ->
                err.optJSONArray("errors")?.optJSONObject(0)?.optString("reason")
                    ?: err.optString("message")
            } ?: json.optString("error_description").ifEmpty { json.optString("error") }
        } catch (_: Exception) {
            ""
        }
        return when (code) {
            401 -> ApiException.Unauthorized(hostLabel(connection))
            403 -> if (reason.contains("quota", true) || reason.contains("rateLimit", true) || reason.contains("limit", true)) {
                ApiException.RateLimited()
            } else {
                ApiException.Unauthorized(hostLabel(connection))
            }
            429 -> ApiException.RateLimited(connection.getHeaderField("Retry-After")?.toIntOrNull() ?: 0)
            400, 404, 422 -> ApiException.InvalidRequest(reason.ifEmpty { null })
            in 500..599 -> ApiException.ServerError(code)
            else -> ApiException.Network(IOException("HTTP $code $reason"))
        }
    }

    private fun hostLabel(connection: HttpURLConnection): String {
        val host = connection.url.host ?: return "Provider"
        return when {
            host.contains("googleapis") -> "YouTube"
            host.contains("spotify") -> "Spotify"
            else -> host
        }
    }

    companion object {
        const val MAX_CONCURRENT = 4
        private const val MAX_RETRIES = 2
        private const val BASE_BACKOFF_MS = 600L
        private val CONNECT_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10).toInt()
        private val READ_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(15).toInt()
        private const val USER_AGENT = "BlazeMuzix/1.0 (Android)"

        fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

        /** Builds a query string from non-null parameters. */
        fun query(params: Map<String, String?>): String =
            params.entries.filter { it.value != null }
                .joinToString("&") { (k, v) -> encode(k) + "=" + encode(v!!) }
    }
}
