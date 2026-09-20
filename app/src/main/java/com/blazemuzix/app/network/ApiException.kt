package com.blazemuzix.app.network

/** Typed network/provider failures so every screen can show the right message. */
sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Offline : ApiException("No internet connection")
    class Timeout(cause: Throwable? = null) : ApiException("Request timed out", cause)
    class RateLimited(val retryAfterSeconds: Int = 0) : ApiException("Rate limit reached")
    class Unauthorized(val provider: String) : ApiException("$provider rejected the credentials")
    class InvalidRequest(val detail: String? = null) : ApiException(detail ?: "Invalid request")
    class ServerError(val code: Int) : ApiException("Server error $code")
    class NotConfigured(val provider: String) : ApiException("$provider is not configured")
    class Parse(cause: Throwable? = null) : ApiException("Unexpected response format", cause)
    class Network(cause: Throwable? = null) : ApiException("Network error", cause)
}
