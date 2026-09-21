package com.blazemuzix.app.auth

import org.json.JSONObject

/**
 * Snapshot of the signed-in user. Passwords are never stored. Cloud sessions keep
 * JWTs in [SecureStore]; Google keeps the public profile and re-issues tokens.
 */
data class AuthUser(
    val id: String,
    val email: String?,
    val displayName: String?,
    val photoUrl: String?,
    val provider: String = PROVIDER_GOOGLE,
    val username: String? = null,
    val bio: String? = null,
    val cloudId: String? = null,
    val publicProfile: Boolean = true
) {
    fun toCacheJson(): String = JSONObject().apply {
        put("id", id)
        put("email", email)
        put("displayName", displayName)
        put("photoUrl", photoUrl)
        put("provider", provider)
        put("username", username)
        put("bio", bio)
        put("cloudId", cloudId)
        put("publicProfile", publicProfile)
    }.toString()

    companion object {
        const val PROVIDER_GOOGLE = "google"
        const val PROVIDER_EMAIL = "email"

        fun fromCacheJson(raw: String?): AuthUser? {
            if (raw.isNullOrBlank()) return null
            return try {
                val json = JSONObject(raw)
                val id = json.optString("id")
                if (id.isEmpty()) null else AuthUser(
                    id = id,
                    email = json.optString("email").takeIf { it.isNotBlank() },
                    displayName = json.optString("displayName").takeIf { it.isNotBlank() },
                    photoUrl = json.optString("photoUrl").takeIf { it.isNotBlank() },
                    provider = json.optString("provider").ifBlank { PROVIDER_EMAIL },
                    username = json.optString("username").takeIf { it.isNotBlank() },
                    bio = json.optString("bio").takeIf { it.isNotBlank() },
                    cloudId = json.optString("cloudId").takeIf { it.isNotBlank() },
                    publicProfile = json.optBoolean("publicProfile", true)
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
