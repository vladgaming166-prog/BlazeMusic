package com.blazemuzix.app.auth

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.blazemuzix.app.R
import com.blazemuzix.app.utils.Appearance
import com.blazemuzix.app.utils.ExternalActions
import com.blazemuzix.app.utils.applySystemBarInsets
import com.google.android.material.button.MaterialButton

/**
 * BlazeMuzix has no password database. Account recovery is Google's official flow.
 */
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
        findViewById<MaterialButton>(R.id.auth_google).apply {
            setText(R.string.auth_forgot_action)
            setOnClickListener { ExternalActions.openUrl(this@ForgotPasswordActivity, "https://accounts.google.com/signin/recovery") }
        }
        findViewById<TextView>(R.id.auth_create).visibility = View.GONE
        findViewById<TextView>(R.id.auth_forgot).visibility = View.GONE
        findViewById<TextView>(R.id.auth_notice).visibility = View.GONE
    }
}
