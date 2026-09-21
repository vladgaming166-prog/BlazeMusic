package com.blazemuzix.app.auth

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.blazemuzix.app.BlazeApp
import com.blazemuzix.app.R
import com.blazemuzix.app.cloud.PasswordRules
import com.blazemuzix.app.network.ApiException
import com.blazemuzix.app.ui.MainActivity
import com.blazemuzix.app.utils.Appearance
import com.blazemuzix.app.utils.Artwork
import com.blazemuzix.app.utils.applySystemBarInsets
import com.blazemuzix.app.utils.dp
import com.blazemuzix.app.utils.toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class CreateAccountActivity : AppCompatActivity() {

    private val auth get() = BlazeApp.graph(this).auth
    private var avatarUri: Uri? = null

    private val googleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        when (val google = auth.handleGoogleResult(result.data)) {
            is AuthResult.Success -> lifecycleScope.launch {
                try {
                    goHome(auth.completeGoogle(google))
                } catch (e: Exception) {
                    showError(human(e))
                }
            }
            AuthResult.Cancelled -> Unit
            AuthResult.Misconfigured -> showError(auth.google.describeStatus(10))
            is AuthResult.Failed -> showError(google.message)
        }
    }

    private val photoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        avatarUri = uri
        if (uri != null) {
            Artwork.load(findViewById(R.id.auth_avatar), uri.toString(), com.blazemuzix.app.data.models.MediaType.USER, dp(72), dp(36))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Appearance.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_create_account)
        Appearance.decorate(this, findViewById(R.id.auth_root))
        findViewById<View>(R.id.auth_root).applySystemBarInsets(top = true, bottom = true)
        findViewById<View>(R.id.auth_back).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.auth_choose_photo).setOnClickListener { photoLauncher.launch("image/*") }
        findViewById<ImageView>(R.id.auth_avatar).setOnClickListener { photoLauncher.launch("image/*") }
        findViewById<MaterialButton>(R.id.auth_submit).setOnClickListener { submit() }
        findViewById<MaterialButton>(R.id.auth_google).setOnClickListener { startGoogle() }
        findViewById<TextInputEditText>(R.id.auth_password).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val score = PasswordRules.strength(s?.toString().orEmpty())
                findViewById<TextView>(R.id.auth_strength).text = PasswordRules.strengthLabel(score)
            }
        })
        val parts = listOfNotNull(auth.cloudNotice(), auth.googleNotice())
        findViewById<TextView>(R.id.auth_notice).text =
            (listOf(getString(R.string.auth_create_notice)) + parts).joinToString("\n\n")
        findViewById<MaterialButton>(R.id.auth_submit).isEnabled = auth.cloudConfigured
    }

    private fun submit() {
        val username = findViewById<TextInputEditText>(R.id.auth_username).text?.toString().orEmpty()
        val email = findViewById<TextInputEditText>(R.id.auth_email).text?.toString().orEmpty()
        val password = findViewById<TextInputEditText>(R.id.auth_password).text?.toString().orEmpty()
        val confirm = findViewById<TextInputEditText>(R.id.auth_confirm).text?.toString().orEmpty()
        showError(null)
        if (!auth.cloudConfigured) {
            showError(getString(R.string.auth_cloud_not_configured))
            return
        }
        setLoading(true)
        lifecycleScope.launch {
            try {
                val user = auth.signUp(email, password, confirm, username)
                val uri = avatarUri
                if (uri != null && auth.hasCloudSession()) {
                    runCatching { BlazeApp.graph(this@CreateAccountActivity).cloud.uploadAvatar(uri) }
                }
                goHome(user)
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
        val intent = auth.google.signInIntent() ?: run {
            showError(getString(R.string.auth_error_generic)); return
        }
        googleLauncher.launch(intent)
    }

    private fun goHome(user: AuthUser) {
        BlazeApp.graph(this).prefs.welcomeCompleted = true
        toast(getString(R.string.auth_signed_in, user.displayName ?: user.email ?: ""))
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    private fun setLoading(loading: Boolean) {
        findViewById<ProgressBar>(R.id.auth_progress).visibility = if (loading) View.VISIBLE else View.GONE
        findViewById<MaterialButton>(R.id.auth_submit).isEnabled = !loading && auth.cloudConfigured
    }

    private fun showError(message: String?) {
        val view = findViewById<TextView>(R.id.auth_error)
        view.visibility = if (message.isNullOrBlank()) View.GONE else View.VISIBLE
        view.text = message
    }

    private fun human(e: Throwable): String = when (e) {
        is ApiException.NotConfigured -> getString(R.string.auth_cloud_not_configured)
        is ApiException.Offline -> getString(R.string.state_offline_message)
        is ApiException.InvalidRequest -> e.detail ?: e.message.orEmpty()
        is ApiException.Unauthorized -> "Could not create the account with those credentials."
        else -> e.message ?: getString(R.string.auth_error_generic)
    }
}
