package com.blazemuzix.app.auth

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.blazemuzix.app.BuildConfig
import com.blazemuzix.app.R
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import java.security.MessageDigest

/**
 * Official Google Sign-In via play-services-auth 20.7.0 (last line that supports
 * minSdk 19). Credential Manager (`androidx.credentials`) requires minSdk 23 and
 * is therefore not a compile dependency — adding it would drop KitKat. This
 * class is the KitKat-compatible official path; on devices with Play services it
 * is the same Google identity used by Credential Manager.
 *
 * When [BuildConfig.GOOGLE_WEB_CLIENT_ID] is set, an OpenID ID token is requested
 * (needed to exchange with BlazeMuzix Cloud). Never put a client *secret* here.
 */
class GoogleAuth(context: Context) {

    private val app = context.applicationContext
    private val prefs: SharedPreferences =
        app.getSharedPreferences("blaze_account", Context.MODE_PRIVATE)

    private val _user = MutableLiveData<AuthUser?>(readCached())
    val user: LiveData<AuthUser?> get() = _user
    val current: AuthUser? get() = _user.value

    val webClientIdConfigured: Boolean get() = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

    /** Last ID token from a successful interactive sign-in. Not persisted. */
    var lastIdToken: String? = null
        private set

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
            lastIdToken = account.idToken
            val user = account.toUser() ?: return AuthResult.Failed(app.getString(R.string.auth_error_generic))
            cache(user)
            _user.value = user
            AuthResult.Success(user, account.idToken)
        } catch (e: ApiException) {
            AuthResult.Failed(describeStatus(e.statusCode), statusCode = e.statusCode)
        } catch (_: Exception) {
            AuthResult.Failed(app.getString(R.string.auth_error_generic))
        }
    }

    fun restoreFromLastAccount() {
        try {
            val account = GoogleSignIn.getLastSignedInAccount(app) ?: return
            lastIdToken = account.idToken
            val user = account.toUser() ?: return
            cache(user)
            _user.postValue(user)
        } catch (_: Exception) {
            // Missing Play services or cancelled restore: stay signed out.
        }
    }

    fun signOut(onDone: () -> Unit = {}) {
        lastIdToken = null
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

    fun packageName(): String = app.packageName

    fun signingSha1(): String = try {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners ?: emptyArray()
        } else {
            @Suppress("DEPRECATION")
            app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNATURES).signatures ?: emptyArray()
        }
        val sig = signatures.firstOrNull() ?: return ""
        val md = MessageDigest.getInstance("SHA-1")
        md.digest(sig.toByteArray()).joinToString(":") { b -> "%02X".format(b) }
    } catch (_: Exception) {
        ""
    }

    fun describeStatus(statusCode: Int): String {
        val pkg = packageName()
        val sha = signingSha1()
        val shaLine = if (sha.isNotEmpty()) app.getString(R.string.auth_google_sha1_line, sha) else ""
        return when (statusCode) {
            GoogleSignInStatusCodes.SIGN_IN_CANCELLED,
            12501 -> app.getString(R.string.auth_google_cancelled)
            GoogleSignInStatusCodes.SIGN_IN_CURRENTLY_IN_PROGRESS -> app.getString(R.string.auth_error_generic)
            GoogleSignInStatusCodes.NETWORK_ERROR -> app.getString(R.string.auth_error_network)
            GoogleSignInStatusCodes.DEVELOPER_ERROR,
            10 -> app.getString(R.string.auth_google_developer_error, pkg, shaLine)
            12500,
            GoogleSignInStatusCodes.SIGN_IN_FAILED -> app.getString(R.string.auth_google_failed_12500, pkg, shaLine)
            GoogleSignInStatusCodes.INVALID_ACCOUNT -> app.getString(R.string.auth_google_invalid_account)
            GoogleSignInStatusCodes.TIMEOUT -> app.getString(R.string.auth_error_network)
            else -> app.getString(R.string.auth_google_unknown, statusCode.toString())
        }
    }

    fun isDeveloperError(statusCode: Int): Boolean =
        statusCode == GoogleSignInStatusCodes.DEVELOPER_ERROR || statusCode == 10

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
            photoUrl = photoUrl?.toString(),
            provider = AuthUser.PROVIDER_GOOGLE
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
            photoUrl = prefs.getString("photo", null),
            provider = AuthUser.PROVIDER_GOOGLE
        )
    }

    private fun clear() {
        prefs.edit().clear().apply()
        _user.postValue(null)
    }
}

sealed class AuthResult {
    data class Success(val user: AuthUser, val idToken: String? = null) : AuthResult()
    object Cancelled : AuthResult()
    object Misconfigured : AuthResult()
    data class Failed(val message: String, val statusCode: Int = 0) : AuthResult()
}
