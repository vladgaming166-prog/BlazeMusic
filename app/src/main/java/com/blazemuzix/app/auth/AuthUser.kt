package com.blazemuzix.app.auth

/**
 * Local snapshot of the signed-in Google account. The ID token is never stored;
 * Google Sign-In reissues it when needed. Passwords never exist in this app.
 */
data class AuthUser(
    val id: String,
    val email: String?,
    val displayName: String?,
    val photoUrl: String?,
    val provider: String = PROVIDER_GOOGLE
) {
    companion object {
        const val PROVIDER_GOOGLE = "google"
    }
}
