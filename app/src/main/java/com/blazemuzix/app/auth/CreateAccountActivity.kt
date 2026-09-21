package com.blazemuzix.app.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.blazemuzix.app.BlazeApp
import com.blazemuzix.app.R
import com.blazemuzix.app.ui.MainActivity
import com.blazemuzix.app.utils.Appearance
import com.blazemuzix.app.utils.ExternalActions
import com.blazemuzix.app.utils.applySystemBarInsets
import com.blazemuzix.app.utils.toast
import com.google.android.material.button.MaterialButton

class CreateAccountActivity : AppCompatActivity() {

    private val google get() = BlazeApp.graph(this).googleAuth

    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        when (val auth = google.handleSignInResult(result.data)) {
            is AuthResult.Success -> {
                BlazeApp.graph(this).prefs.welcomeCompleted = true
                toast(getString(R.string.auth_signed_in, auth.user.displayName ?: auth.user.email ?: ""))
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                finish()
            }
            AuthResult.Cancelled -> Unit
            AuthResult.Misconfigured -> toast(R.string.auth_google_misconfigured)
            is AuthResult.Failed -> toast(auth.message)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Appearance.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sign_in)
        Appearance.decorate(this, findViewById(R.id.auth_root))
        findViewById<View>(R.id.auth_root).applySystemBarInsets(top = true, bottom = true)
        findViewById<View>(R.id.auth_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.auth_title).setText(R.string.auth_create_title)
        findViewById<TextView>(R.id.auth_subtitle).setText(R.string.auth_create_message)
        findViewById<MaterialButton>(R.id.auth_google).setText(R.string.auth_continue_google)
        findViewById<MaterialButton>(R.id.auth_google).setOnClickListener {
            if (!google.playServicesAvailable()) {
                toast(R.string.auth_play_services_missing)
                return@setOnClickListener
            }
            val intent = google.signInIntent()
            if (intent == null) toast(R.string.auth_error_generic) else launcher.launch(intent)
        }
        findViewById<TextView>(R.id.auth_create).apply {
            setText(R.string.auth_google_signup_link)
            setOnClickListener { ExternalActions.openUrl(this@CreateAccountActivity, "https://accounts.google.com/signup") }
        }
        findViewById<TextView>(R.id.auth_forgot).visibility = View.GONE
        val notice = findViewById<TextView>(R.id.auth_notice)
        notice.visibility = View.VISIBLE
        notice.setText(R.string.auth_create_notice)
    }
}
