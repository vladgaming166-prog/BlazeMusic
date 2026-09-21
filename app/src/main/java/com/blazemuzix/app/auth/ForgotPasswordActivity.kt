package com.blazemuzix.app.auth

import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.blazemuzix.app.BlazeApp
import com.blazemuzix.app.R
import com.blazemuzix.app.network.ApiException
import com.blazemuzix.app.utils.Appearance
import com.blazemuzix.app.utils.ExternalActions
import com.blazemuzix.app.utils.applySystemBarInsets
import com.blazemuzix.app.utils.toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class ForgotPasswordActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        Appearance.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sign_in)
        Appearance.decorate(this, findViewById(R.id.auth_root))
        findViewById<View>(R.id.auth_root).applySystemBarInsets(top = true, bottom = true)
        findViewById<View>(R.id.auth_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.auth_title).setText(R.string.auth_forgot_title)
        findViewById<TextView>(R.id.auth_subtitle).setText(R.string.auth_forgot_message)
        findViewById<View>(R.id.auth_password_layout).visibility = View.GONE
        findViewById<CheckBox>(R.id.auth_remember).visibility = View.GONE
        findViewById<TextView>(R.id.auth_forgot).visibility = View.GONE
        findViewById<TextView>(R.id.auth_create).visibility = View.GONE
        val submit = findViewById<MaterialButton>(R.id.auth_submit)
        val google = findViewById<MaterialButton>(R.id.auth_google)
        val graph = BlazeApp.graph(this)
        submit.setText(R.string.auth_forgot_action)
        google.setText(R.string.auth_forgot_google)
        google.setOnClickListener { ExternalActions.openUrl(this, "https://accounts.google.com/signin/recovery") }
        submit.isEnabled = graph.auth.cloudConfigured
        val notice = findViewById<TextView>(R.id.auth_notice)
        notice.visibility = View.VISIBLE
        notice.text = graph.auth.cloudNotice() ?: ""
        submit.setOnClickListener {
            val email = findViewById<TextInputEditText>(R.id.auth_email).text?.toString().orEmpty()
            val error = findViewById<TextView>(R.id.auth_error)
            error.visibility = View.GONE
            if (!graph.auth.cloudConfigured) {
                error.visibility = View.VISIBLE
                error.setText(R.string.auth_cloud_not_configured)
                return@setOnClickListener
            }
            findViewById<ProgressBar>(R.id.auth_progress).visibility = View.VISIBLE
            lifecycleScope.launch {
                try {
                    graph.auth.requestPasswordReset(email)
                    toast(R.string.auth_forgot_sent)
                    finish()
                } catch (e: Exception) {
                    error.visibility = View.VISIBLE
                    error.text = if (e is ApiException.InvalidRequest) e.detail ?: e.message else (e.message ?: getString(R.string.auth_cloud_not_configured))
                } finally {
                    findViewById<ProgressBar>(R.id.auth_progress).visibility = View.GONE
                }
            }
        }
    }
}
