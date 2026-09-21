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

    override fun onCreate(savedInstanceState: Bundle?) {
        Appearance.apply(this)
        super.onCreate(savedInstanceState)
        val graph = BlazeApp.graph(this)
        if (graph.auth.current != null || graph.prefs.welcomeCompleted) {
            goHome(finishSelf = true)
            return
        }
        setContentView(R.layout.activity_welcome)
        Appearance.decorate(this, findViewById(R.id.welcome_root))
        findViewById<View>(R.id.welcome_root).applySystemBarInsets(top = true, bottom = true)
        findViewById<MaterialButton>(R.id.welcome_google).setOnClickListener {
            startActivity(Intent(this, SignInActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.welcome_google).setText(R.string.welcome_sign_in_email)
        findViewById<MaterialButton>(R.id.welcome_skip).setOnClickListener {
            graph.prefs.welcomeCompleted = true
            goHome(finishSelf = true)
        }
        findViewById<TextView>(R.id.welcome_create).setOnClickListener {
            startActivity(Intent(this, CreateAccountActivity::class.java))
        }
        val notice = findViewById<TextView>(R.id.welcome_notice)
        val parts = listOfNotNull(graph.auth.cloudNotice(), graph.auth.googleNotice())
        if (parts.isNotEmpty()) {
            notice.visibility = View.VISIBLE
            notice.text = parts.joinToString("\n\n")
        }
    }

    private fun goHome(finishSelf: Boolean) {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        if (finishSelf) finish()
    }
}
