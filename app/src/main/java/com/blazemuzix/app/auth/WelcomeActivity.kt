package com.blazemuzix.app.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.blazemuzix.app.BlazeApp
import com.blazemuzix.app.R
import com.blazemuzix.app.ui.MainActivity
import com.blazemuzix.app.utils.Appearance
import com.blazemuzix.app.utils.applySystemBarInsets
import com.google.android.material.button.MaterialButton

class WelcomeActivity : AppCompatActivity() {

    private val google get() = BlazeApp.graph(this).googleAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        Appearance.apply(this)
        super.onCreate(savedInstanceState)
        if (google.current != null || BlazeApp.graph(this).prefs.welcomeCompleted) {
            goHome(finishSelf = true)
            return
        }
        setContentView(R.layout.activity_welcome)
        Appearance.decorate(this, findViewById(R.id.welcome_root))
        findViewById<View>(R.id.welcome_root).applySystemBarInsets(top = true, bottom = true)
        findViewById<MaterialButton>(R.id.welcome_google).setOnClickListener { startActivity(Intent(this, SignInActivity::class.java)) }
        findViewById<MaterialButton>(R.id.welcome_skip).setOnClickListener {
            BlazeApp.graph(this).prefs.welcomeCompleted = true
            goHome(finishSelf = true)
        }
        findViewById<TextView>(R.id.welcome_create).setOnClickListener {
            startActivity(Intent(this, CreateAccountActivity::class.java))
        }
        if (!google.playServicesAvailable()) {
            findViewById<TextView>(R.id.welcome_notice).apply {
                visibility = View.VISIBLE
                text = getString(R.string.auth_play_services_missing)
            }
        }
    }

    private fun goHome(finishSelf: Boolean) {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        if (finishSelf) finish()
    }
}
