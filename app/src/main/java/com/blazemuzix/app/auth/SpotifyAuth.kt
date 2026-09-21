package com.blazemuzix.app.auth

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.blazemuzix.app.BuildConfig
import com.blazemuzix.app.network.ApiException
import com.blazemuzix.app.network.HttpClient
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Official Spotify Authorization Code + PKCE. Only the public client id is
 * required in the APK; the code verifier never leaves the device. Refresh
 * tokens are stored in [SecureStore].
 */
class SpotifyAuth(
    context: Context,
    private val http: HttpClient,
    private val store: SecureStore
) {
    private val app = context.applicationContext
    val clientId: String get() = BuildConfig.SPOTIFY_CLIENT_ID
    val redirectUri: String get() = BuildConfig.SPOTIFY_REDIRECT_URI
    val isClientConfigured: Boolean get() = clientId.isNotBlank()
    val isSignedIn: Boolean get() = !store.get(KEY_REFRESH).isNullOrEmpty() || !store.get(KEY_ACCESS).isNullOrEmpty()

    fun displayName(): String? = store.get(KEY_NAME)
    fun userId(): String? = store.get(KEY_USER)

    fun beginLogin(): Uri? {
        if (!isClientConfigured) return null
        val verifier = randomUrl(64)
        store.put(KEY_VERIFIER, verifier)
        val challenge = sha256B64(verifier)
        val state = randomUrl(24)
        store.put(KEY_STATE, state)
        return Uri.parse(AUTHORIZE).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("state", state)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("show_dialog", "false")
            .build()
    }

    suspend fun completeLogin(uri: Uri): Boolean {
        val error = uri.getQueryParameter("error")
        if (!error.isNullOrEmpty()) return false
        val state = uri.getQueryParameter("state")
        if (state.isNullOrEmpty() || state != store.get(KEY_STATE)) return false
        val code = uri.getQueryParameter("code") ?: return false
        val verifier = store.get(KEY_VERIFIER) ?: return false
        val json = http.postForm(
            TOKEN,
            emptyMap(),
            mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to redirectUri,
                "client_id" to clientId,
                "code_verifier" to verifier
            )
        )
        persistTokens(json)
        store.remove(KEY_VERIFIER)
        store.remove(KEY_STATE)
        runCatching { fetchMe() }
        return true
    }

    suspend fun validAccessToken(): String? {
        val token = store.get(KEY_ACCESS)
        val expires = store.get(KEY_EXPIRES)?.toLongOrNull() ?: 0L
        if (!token.isNullOrEmpty() && System.currentTimeMillis() < expires - 30_000) return token
        val refresh = store.get(KEY_REFRESH) ?: return null
        return try {
            val json = http.postForm(
                TOKEN,
                emptyMap(),
                mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to refresh,
                    "client_id" to clientId
                )
            )
            persistTokens(json)
            store.get(KEY_ACCESS)
        } catch (_: ApiException) {
            null
        }
    }

    fun signOut() {
        store.remove(KEY_ACCESS)
        store.remove(KEY_REFRESH)
        store.remove(KEY_EXPIRES)
        store.remove(KEY_NAME)
        store.remove(KEY_USER)
        store.remove(KEY_VERIFIER)
        store.remove(KEY_STATE)
    }

    private fun persistTokens(json: JSONObject) {
        val access = json.optString("access_token")
        if (access.isNotEmpty()) store.put(KEY_ACCESS, access)
        val refresh = json.optString("refresh_token")
        if (refresh.isNotEmpty()) store.put(KEY_REFRESH, refresh)
        val expiresIn = json.optLong("expires_in", 3600)
        store.put(KEY_EXPIRES, (System.currentTimeMillis() + expiresIn * 1000).toString())
    }

    private suspend fun fetchMe() {
        val token = store.get(KEY_ACCESS) ?: return
        val me = http.getJson(
            "https://api.spotify.com/v1/me",
            mapOf("Authorization" to "Bearer $token"),
            cacheTtlMs = 0L
        )
        store.put(KEY_NAME, me.optString("display_name").ifEmpty { me.optString("id") })
        store.put(KEY_USER, me.optString("id"))
    }

    private fun randomUrl(bytes: Int): String {
        val raw = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(raw, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun sha256B64(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    companion object {
        private const val AUTHORIZE = "https://accounts.spotify.com/authorize"
        private const val TOKEN = "https://accounts.spotify.com/api/token"
        private const val SCOPES = "user-read-email user-read-private playlist-read-private playlist-read-collaborative user-library-read user-top-read user-read-recently-played"
        private const val KEY_ACCESS = "sp_access"
        private const val KEY_REFRESH = "sp_refresh"
        private const val KEY_EXPIRES = "sp_expires"
        private const val KEY_VERIFIER = "sp_verifier"
        private const val KEY_STATE = "sp_state"
        private const val KEY_NAME = "sp_name"
        private const val KEY_USER = "sp_user"
    }
}
