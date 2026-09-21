package com.blazemuzix.app.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.blazemuzix.app.BlazeApp
import com.blazemuzix.app.R
import com.blazemuzix.app.network.ApiException
import com.blazemuzix.app.ui.MainActivity
import com.blazemuzix.app.utils.Appearance
import com.blazemuzix.app.utils.applySystemBarInsets
import com.blazemuzix.app.utils.toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class SignInActivity : AppCompatActivity() {

    private val auth get() = BlazeApp.graph(this).auth

    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        handleGoogle(auth.handleGoogleResult(result.data))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Appearance.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sign_in)
        Appearance.decorate(this, findViewById(R.id.auth_root))
        findViewById<View>(R.id.auth_root).applySystemBarInsets(top = true, bottom = true)
        findViewById<View>(R.id.auth_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.auth_title).setText(R.string.auth_sign_in_title)
        findViewById<TextView>(R.id.auth_subtitle).setText(R.string.auth_sign_in_message)
        findViewById<MaterialButton>(R.id.auth_google).setOnClickListener { startGoogle() }
        findViewById<TextView>(R.id.auth_create).setOnClickListener {
            startActivity(Intent(this, CreateAccountActivity::class.java))
        }
        findViewById<TextView>(R.id.auth_forgot).setOnClickListener {
            startActivity(Intent(this, ForgotPasswordActivity::class.java))
        }
        findViewById<CheckBox>(R.id.auth_remember).isEnabled = false
        findViewById<CheckBox>(R.id.auth_remember).isChecked = true
    }

    private fun submitEmail() {
        val email = findViewById<TextInputEditText>(R.id.auth_email).text?.toString().orEmpty()
        val password = findViewById<TextInputEditText>(R.id.auth_password).text?.toString().orEmpty()
        showError(null)
        if (!auth.cloudConfigured) {
            showError(getString(R.string.auth_cloud_not_configured))
            return
        }
        setLoading(true)
        lifecycleScope.launch {
            try {
                val user = auth.signInEmail(email, password)
                done(user)
            } catch (e: Exception) {
                showError(human(e))
            } finally {
                setLoading(false)
            }
        }
    }

    private fun startGoogle() {
        if (!auth.google.playServicesAvailable()) {
            showError(getString(R.string.auth_play_services_missing))
            return
        }
        val intent = auth.google.signInIntent()
        if (intent == null) {
            showError(getString(R.string.auth_error_generic))
            return
        }
        launcher.launch(intent)
    }

    private fun handleGoogle(result: AuthResult) {
        when (result) {
            is AuthResult.Success -> {
                setLoading(true)
                lifecycleScope.launch {
                    try {
                        done(auth.completeGoogle(result))
                    } catch (e: Exception) {
                        // Google personalisation still applied; Cloud exchange may have failed.
                        showError(human(e))
                        if (auth.google.current != null) {
                            BlazeApp.graph(this@SignInActivity).prefs.welcomeCompleted = true
                        }
                    } finally {
                        setLoading(false)
                    }
                }
            }
            AuthResult.Cancelled -> Unit
            AuthResult.Misconfigured -> showError(auth.google.describeStatus(10))
            is AuthResult.Failed -> showError(result.message)
        }
    }

    private fun done(user: AuthUser) {
        BlazeApp.graph(this).prefs.welcomeCompleted = true
        toast(getString(R.string.auth_signed_in, user.displayName ?: user.email ?: ""))
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    private fun renderNotice() {
        val notice = findViewById<TextView>(R.id.auth_notice)
        val parts = listOfNotNull(auth.cloudNotice(), auth.googleNotice())
        notice.text = parts.joinToString("\n\n")
        notice.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
        findViewById<MaterialButton>(R.id.auth_submit).isEnabled = auth.cloudConfigured
        findViewById<CheckBox>(R.id.auth_remember).visibility = if (auth.cloudConfigured) View.VISIBLE else View.GONE
    }

    private fun setLoading(loading: Boolean) {
        findViewById<ProgressBar>(R.id.auth_progress).visibility = if (loading) View.VISIBLE else View.GONE
        findViewById<MaterialButton>(R.id.auth_submit).isEnabled = !loading && auth.cloudConfigured
        findViewById<MaterialButton>(R.id.auth_google).isEnabled = !loading
    }

    private fun showError(message: String?) {
        val view = findViewById<TextView>(R.id.auth_error)
        view.visibility = if (message.isNullOrBlank()) View.GONE else View.VISIBLE
        view.text = message
    }

    private fun human(e: Throwable): String = when (e) {
        is ApiException.NotConfigured -> getString(R.string.auth_cloud_not_configured)
        is ApiException.Offline -> getString(R.string.state_offline_message)
        is ApiException.Unauthorized -> "Email or password is incorrect, or the session expired."
        is ApiException.InvalidRequest -> e.detail ?: e.message ?: getString(R.string.auth_error_generic)
        is ApiException.Network, is ApiException.Timeout -> getString(R.string.auth_error_network)
        else -> e.message ?: getString(R.string.auth_error_generic)
    }
}
