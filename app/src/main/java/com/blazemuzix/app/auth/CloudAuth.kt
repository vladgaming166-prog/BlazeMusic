package com.blazemuzix.app.auth

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.blazemuzix.app.cloud.CloudClient
import com.blazemuzix.app.cloud.CloudConfig
import com.blazemuzix.app.cloud.CloudProfile
import com.blazemuzix.app.cloud.PasswordRules
import com.blazemuzix.app.network.ApiException
import com.blazemuzix.app.network.HttpClient
import org.json.JSONObject

/**
 * Supabase Auth (email/password + optional Google ID token). Tokens live in
 * [SecureStore]. There is no local password database.
 */
class CloudAuth(
    private val http: HttpClient,
    private val store: SecureStore
) {
    val isConfigured: Boolean get() = CloudConfig.isConfigured

    private val _user = MutableLiveData<AuthUser?>(readCached())
    val user: LiveData<AuthUser?> get() = _user
    val current: AuthUser? get() = _user.value

    fun accessToken(): String? {
        val token = store.get(KEY_ACCESS)
        val expires = store.get(KEY_EXPIRES)?.toLongOrNull() ?: 0L
        if (!token.isNullOrEmpty() && System.currentTimeMillis() < expires - 15_000) return token
        return token
    }

    suspend fun validAccessToken(): String? {
        val token = store.get(KEY_ACCESS)
        val expires = store.get(KEY_EXPIRES)?.toLongOrNull() ?: 0L
        if (!token.isNullOrEmpty() && System.currentTimeMillis() < expires - 30_000) return token
        return refresh()
    }

    fun isSignedIn(): Boolean = !store.get(KEY_ACCESS).isNullOrEmpty() || !store.get(KEY_REFRESH).isNullOrEmpty()

    suspend fun signUp(email: String, password: String, confirm: String, username: String): AuthUser {
        requireConfigured()
        PasswordRules.email(email)?.let { throw ApiException.InvalidRequest(it) }
        PasswordRules.validate(password, confirm)?.let { throw ApiException.InvalidRequest(it) }
        PasswordRules.username(username)?.let { throw ApiException.InvalidRequest(it) }
        val body = JSONObject()
            .put("email", email.trim())
            .put("password", password)
            .put("data", JSONObject().put("username", username.trim()))
        val json = http.postJson("${CloudConfig.url}/auth/v1/signup", anonHeaders(), body)
        persistSession(json)
        val user = userFromAuth(json)
        _user.postValue(user)
        return user
    }

    suspend fun signIn(email: String, password: String): AuthUser {
        requireConfigured()
        PasswordRules.email(email)?.let { throw ApiException.InvalidRequest(it) }
        if (password.isEmpty()) throw ApiException.InvalidRequest("Enter your password.")
        val json = http.postJson(
            "${CloudConfig.url}/auth/v1/token?grant_type=password",
            anonHeaders(),
            JSONObject().put("email", email.trim()).put("password", password)
        )
        persistSession(json)
        val user = userFromAuth(json)
        _user.postValue(user)
        return user
    }

    suspend fun signInWithGoogleIdToken(idToken: String): AuthUser {
        requireConfigured()
        val json = http.postJson(
            "${CloudConfig.url}/auth/v1/token?grant_type=id_token",
            anonHeaders(),
            JSONObject().put("provider", "google").put("id_token", idToken)
        )
        persistSession(json)
        val user = userFromAuth(json)
        _user.postValue(user)
        return user
    }

    suspend fun requestPasswordReset(email: String) {
        requireConfigured()
        PasswordRules.email(email)?.let { throw ApiException.InvalidRequest(it) }
        http.postJson(
            "${CloudConfig.url}/auth/v1/recover",
            anonHeaders(),
            JSONObject().put("email", email.trim())
        )
    }

    suspend fun changePassword(newPassword: String, confirm: String) {
        requireConfigured()
        PasswordRules.validate(newPassword, confirm)?.let { throw ApiException.InvalidRequest(it) }
        val token = validAccessToken() ?: throw ApiException.Unauthorized("BlazeMuzix Cloud")
        putUser(JSONObject().put("password", newPassword), token)
    }

    suspend fun changeEmail(newEmail: String) {
        requireConfigured()
        PasswordRules.email(newEmail)?.let { throw ApiException.InvalidRequest(it) }
        val token = validAccessToken() ?: throw ApiException.Unauthorized("BlazeMuzix Cloud")
        putUser(JSONObject().put("email", newEmail.trim()), token)
    }

    suspend fun deleteAccount() {
        requireConfigured()
        val token = validAccessToken() ?: throw ApiException.Unauthorized("BlazeMuzix Cloud")
        http.postJson(
            "${CloudConfig.url}/rest/v1/rpc/delete_own_account",
            mapOf(
                "apikey" to CloudConfig.anonKey,
                "Authorization" to "Bearer $token",
                "Content-Type" to "application/json"
            ),
            JSONObject()
        )
        signOut()
    }

    suspend fun restore(): AuthUser? {
        if (!isConfigured || !isSignedIn()) return current
        return try {
            refresh()
            current
        } catch (_: Exception) {
            signOut()
            null
        }
    }

    fun signOut() {
        store.remove(KEY_ACCESS)
        store.remove(KEY_REFRESH)
        store.remove(KEY_EXPIRES)
        store.remove(KEY_USER)
        _user.postValue(null)
    }

    fun applyProfile(profile: CloudProfile) {
        val existing = current ?: return
        val updated = existing.copy(
            displayName = profile.username ?: existing.displayName,
            photoUrl = profile.avatar ?: existing.photoUrl,
            username = profile.username,
            bio = profile.bio,
            email = profile.email ?: existing.email
        )
        store.put(KEY_USER, updated.toCacheJson())
        _user.postValue(updated)
    }

    private suspend fun refresh(): String? {
        val refresh = store.get(KEY_REFRESH) ?: return null
        return try {
            val json = http.postJson(
                "${CloudConfig.url}/auth/v1/token?grant_type=refresh_token",
                anonHeaders(),
                JSONObject().put("refresh_token", refresh)
            )
            persistSession(json)
            store.get(KEY_ACCESS)
        } catch (_: ApiException) {
            signOut()
            null
        }
    }

    private fun persistSession(json: JSONObject) {
        val access = json.optString("access_token")
        if (access.isEmpty()) {
            // Email confirmation required: signup returned a user but no session.
            val user = json.optJSONObject("user")
            if (user != null) store.put(KEY_USER, userFromMap(user, provider = AuthUser.PROVIDER_EMAIL).toCacheJson())
            return
        }
        store.put(KEY_ACCESS, access)
        val refresh = json.optString("refresh_token")
        if (refresh.isNotEmpty()) store.put(KEY_REFRESH, refresh)
        val expiresIn = json.optLong("expires_in", 3600)
        store.put(KEY_EXPIRES, (System.currentTimeMillis() + expiresIn * 1000).toString())
        val userJson = json.optJSONObject("user")
        if (userJson != null) store.put(KEY_USER, userFromMap(userJson, providerFrom(json)).toCacheJson())
    }

    private fun userFromAuth(json: JSONObject): AuthUser {
        val userJson = json.optJSONObject("user") ?: JSONObject(store.get(KEY_USER) ?: "{}")
        return userFromMap(userJson, providerFrom(json))
    }

    private fun providerFrom(json: JSONObject): String {
        val identities = json.optJSONObject("user")?.optJSONArray("identities")
        val provider = identities?.optJSONObject(0)?.optString("provider")
        return if (provider == "google") AuthUser.PROVIDER_GOOGLE else AuthUser.PROVIDER_EMAIL
    }

    private fun userFromMap(user: JSONObject, provider: String): AuthUser {
        val meta = user.optJSONObject("user_metadata") ?: JSONObject()
        return AuthUser(
            id = user.optString("id"),
            email = user.optString("email").takeIf { it.isNotBlank() },
            displayName = meta.optString("username").ifBlank { meta.optString("full_name").ifBlank { user.optString("email") } },
            photoUrl = meta.optString("avatar_url").takeIf { it.isNotBlank() },
            provider = provider,
            username = meta.optString("username").takeIf { it.isNotBlank() },
            cloudId = user.optString("id").takeIf { it.isNotBlank() }
        )
    }

    private fun readCached(): AuthUser? {
        val raw = store.get(KEY_USER) ?: return null
        return AuthUser.fromCacheJson(raw)
    }

    private fun requireConfigured() {
        if (!isConfigured) throw ApiException.NotConfigured("BlazeMuzix Cloud")
    }

    private fun anonHeaders(): Map<String, String> = mapOf(
        "apikey" to CloudConfig.anonKey,
        "Authorization" to "Bearer ${CloudConfig.anonKey}"
    )

    private suspend fun putUser(body: JSONObject, token: String): JSONObject {
        return http.putJson(
            "${CloudConfig.url}/auth/v1/user",
            mapOf(
                "apikey" to CloudConfig.anonKey,
                "Authorization" to "Bearer $token",
                "Content-Type" to "application/json"
            ),
            body
        )
    }

    companion object {
        private const val KEY_ACCESS = "sb_access"
        private const val KEY_REFRESH = "sb_refresh"
        private const val KEY_EXPIRES = "sb_expires"
        private const val KEY_USER = "sb_user"
    }
}
