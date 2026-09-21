package com.blazemuzix.app.auth

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.blazemuzix.app.R
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.blazemuzix.app.BuildConfig

/**
 * Official Google Sign-In. There is no invented email/password database:
 * Google is the identity provider. When [BuildConfig.GOOGLE_WEB_CLIENT_ID] is
 * set, an OpenID ID token is requested; without it we still request the public
 * profile/email (enough to personalise the local account screen).
 *
 * Failures never crash the process — they surface as [AuthResult].
 */
class GoogleAuth(context: Context) {

    private val app = context.applicationContext
    private val prefs: SharedPreferences =
        app.getSharedPreferences("blaze_account", Context.MODE_PRIVATE)

    private val _user = MutableLiveData<AuthUser?>(readCached())
    val user: LiveData<AuthUser?> get() = _user
    val current: AuthUser? get() = _user.value

    val webClientIdConfigured: Boolean get() = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

    fun playServicesStatus(): Int =
        try {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(app)
        } catch (_: Exception) {
            ConnectionResult.SERVICE_MISSING
        }

    fun playServicesAvailable(): Boolean = playServicesStatus() == ConnectionResult.SUCCESS

    fun signInIntent(): Intent? {
        return try {
            client().signInIntent
        } catch (_: Exception) {
            null
        }
    }

    fun handleSignInResult(data: Intent?): AuthResult {
        if (data == null) return AuthResult.Cancelled
        return try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(ApiException::class.java)
            val user = account.toUser() ?: return AuthResult.Failed(app.getString(R.string.auth_error_generic))
            cache(user)
            _user.value = user
            AuthResult.Success(user)
        } catch (e: ApiException) {
            when (e.statusCode) {
                GoogleSignInStatusCodes.SIGN_IN_CANCELLED,
                GoogleSignInStatusCodes.SIGN_IN_CURRENTLY_IN_PROGRESS -> AuthResult.Cancelled
                GoogleSignInStatusCodes.NETWORK_ERROR -> AuthResult.Failed(app.getString(R.string.auth_error_network))
                GoogleSignInStatusCodes.DEVELOPER_ERROR -> AuthResult.Misconfigured
                else -> AuthResult.Failed(app.getString(R.string.auth_error_generic))
            }
        } catch (_: Exception) {
            AuthResult.Failed(app.getString(R.string.auth_error_generic))
        }
    }

    fun restoreFromLastAccount() {
        try {
            val account = GoogleSignIn.getLastSignedInAccount(app) ?: return
            val user = account.toUser() ?: return
            cache(user)
            _user.postValue(user)
        } catch (_: Exception) {
            // Missing Play services or cancelled restore: stay signed out.
        }
    }

    fun signOut(onDone: () -> Unit = {}) {
        try {
            client().signOut().addOnCompleteListener {
                clear()
                onDone()
            }
        } catch (_: Exception) {
            clear()
            onDone()
        }
    }

    private fun client(): GoogleSignInClient {
        val builder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
        val web = BuildConfig.GOOGLE_WEB_CLIENT_ID
        if (web.isNotBlank()) builder.requestIdToken(web)
        return GoogleSignIn.getClient(app, builder.build())
    }

    private fun GoogleSignInAccount.toUser(): AuthUser? {
        val id = id ?: return null
        return AuthUser(
            id = id,
            email = email,
            displayName = displayName,
            photoUrl = photoUrl?.toString()
        )
    }

    private fun cache(user: AuthUser) {
        prefs.edit()
            .putString("id", user.id)
            .putString("email", user.email)
            .putString("name", user.displayName)
            .putString("photo", user.photoUrl)
            .apply()
    }

    private fun readCached(): AuthUser? {
        val id = prefs.getString("id", null) ?: return null
        return AuthUser(
            id = id,
            email = prefs.getString("email", null),
            displayName = prefs.getString("name", null),
            photoUrl = prefs.getString("photo", null)
        )
    }

    private fun clear() {
        prefs.edit().clear().apply()
        _user.postValue(null)
    }
}

sealed class AuthResult {
    data class Success(val user: AuthUser) : AuthResult()
    object Cancelled : AuthResult()
    object Misconfigured : AuthResult()
    data class Failed(val message: String) : AuthResult()
}
