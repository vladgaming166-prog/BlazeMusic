package com.blazemuzix.app.auth

import android.content.Intent
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import com.blazemuzix.app.cloud.CloudConfig
import com.blazemuzix.app.cloud.CloudProfile
import com.blazemuzix.app.network.ApiException

/**
 * Unified account: Google (device personalisation) + BlazeMuzix Cloud
 * (email/password, uploads, playlists). Cloud session wins when both exist.
 * Local music never depends on this.
 */
class AuthRepository(
    val google: GoogleAuth,
    val cloud: CloudAuth
) {
    private val _user = MediatorLiveData<AuthUser?>()
    val user: LiveData<AuthUser?> get() = _user

    val current: AuthUser? get() = cloud.current ?: google.current

    val cloudConfigured: Boolean get() = CloudConfig.isConfigured
    val googleConfigured: Boolean get() = google.webClientIdConfigured

    init {
        _user.value = current
        _user.addSource(cloud.user) { _user.value = cloud.current ?: google.current }
        _user.addSource(google.user) { _user.value = cloud.current ?: google.current }
    }

    fun isSignedIn(): Boolean = current != null
    fun hasCloudSession(): Boolean = cloud.isSignedIn()

    suspend fun restore() {
        google.restoreFromLastAccount()
        cloud.restore()
        _user.postValue(cloud.current ?: google.current)
    }

    suspend fun signUp(email: String, password: String, confirm: String, username: String): AuthUser {
        if (!cloudConfigured) throw ApiException.NotConfigured("BlazeMuzix Cloud")
        val user = cloud.signUp(email, password, confirm, username)
        if (cloud.accessToken().isNullOrEmpty()) {
            throw ApiException.InvalidRequest(
                "Account created. Check your email to confirm the address, then sign in. Uploads stay locked until the session exists."
            )
        }
        return user
    }

    suspend fun signInEmail(email: String, password: String): AuthUser {
        if (!cloudConfigured) throw ApiException.NotConfigured("BlazeMuzix Cloud")
        return cloud.signIn(email, password)
    }

    suspend fun requestPasswordReset(email: String) {
        if (!cloudConfigured) throw ApiException.NotConfigured("BlazeMuzix Cloud")
        cloud.requestPasswordReset(email)
    }

    fun handleGoogleResult(data: Intent?): AuthResult = google.handleSignInResult(data)

    /**
     * After a Google success, optionally exchange the ID token with Supabase.
     * Google-only sessions still personalise the device if Cloud is missing.
     */
    suspend fun completeGoogle(result: AuthResult.Success): AuthUser {
        val token = result.idToken
        if (cloudConfigured && !token.isNullOrBlank()) {
            try {
                return cloud.signInWithGoogleIdToken(token)
            } catch (e: ApiException.NotConfigured) {
                throw e
            } catch (e: ApiException.InvalidRequest) {
                throw ApiException.InvalidRequest(
                    e.detail ?: "Google Sign-In succeeded on the device, but BlazeMuzix Cloud rejected the ID token. Enable the Google provider in Supabase and set the same Web client ID as GOOGLE_WEB_CLIENT_ID."
                )
            } catch (e: ApiException.Unauthorized) {
                throw ApiException.InvalidRequest(
                    "Google Sign-In succeeded, but BlazeMuzix Cloud could not accept the ID token. Enable Google under Supabase Authentication → Providers and use the Web client ID as serverClientId."
                )
            }
        }
        return result.user
    }

    fun signOut(onDone: () -> Unit = {}) {
        cloud.signOut()
        google.signOut(onDone)
    }

    suspend fun deleteAccount() {
        if (cloud.isSignedIn()) cloud.deleteAccount()
        google.signOut {}
    }

    fun applyProfile(profile: CloudProfile) = cloud.applyProfile(profile)

    fun googleNotice(): String? = when {
        !google.playServicesAvailable() -> "Google Play services are not available, so Continue with Google cannot run."
        !google.webClientIdConfigured -> "GOOGLE_WEB_CLIENT_ID is not set. Email/password still works when BlazeMuzix Cloud is configured. Google Sign-In can request profile/email but cannot mint an ID token for Cloud until you add a Web OAuth client."
        else -> null
    }

    fun cloudNotice(): String? = when {
        cloudConfigured -> null
        else -> "BlazeMuzix Cloud is not configured (SUPABASE_URL and SUPABASE_ANON_KEY). Email/password, uploads, cloud playlists and likes stay unavailable. Local music still works."
    }
}
