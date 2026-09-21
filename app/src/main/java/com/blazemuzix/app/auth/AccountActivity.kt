package com.blazemuzix.app.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.blazemuzix.app.BlazeApp
import com.blazemuzix.app.R
import com.blazemuzix.app.ui.settings.SettingsActivity
import com.blazemuzix.app.utils.Appearance
import com.blazemuzix.app.utils.Artwork
import com.blazemuzix.app.utils.applySystemBarInsets
import com.blazemuzix.app.utils.dp
import com.blazemuzix.app.utils.toast
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class AccountActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        Appearance.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_account)
        Appearance.decorate(this, findViewById(R.id.account_root))
        findViewById<View>(R.id.account_root).applySystemBarInsets(top = true, bottom = true)
        findViewById<View>(R.id.account_back).setOnClickListener { finish() }
        findViewById<View>(R.id.account_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        bind()
    }

    override fun onResume() {
        super.onResume()
        bind()
    }

    private fun bind() {
        val graph = BlazeApp.graph(this)
        val user = graph.googleAuth.current
        val name = findViewById<TextView>(R.id.account_name)
        val email = findViewById<TextView>(R.id.account_email)
        val photo = findViewById<ImageView>(R.id.account_photo)
        val googleStatus = findViewById<TextView>(R.id.account_google_status)
        val spotifyStatus = findViewById<TextView>(R.id.account_spotify_status)
        val signOut = findViewById<MaterialButton>(R.id.account_sign_out)
        val signIn = findViewById<MaterialButton>(R.id.account_sign_in)
        val spotifyBtn = findViewById<MaterialButton>(R.id.account_spotify)

        if (user != null) {
            name.text = user.displayName ?: getString(R.string.auth_signed_in_fallback)
            email.text = user.email ?: ""
            Artwork.load(photo, user.photoUrl, com.blazemuzix.app.data.models.MediaType.ARTIST, dp(72), dp(36))
            googleStatus.text = getString(R.string.provider_status_signed_in)
            signOut.visibility = View.VISIBLE
            signIn.visibility = View.GONE
        } else {
            name.setText(R.string.auth_guest)
            email.setText(R.string.auth_guest_message)
            photo.setImageResource(R.drawable.ic_person)
            googleStatus.setText(R.string.provider_status_signed_out)
            signOut.visibility = View.GONE
            signIn.visibility = View.VISIBLE
        }
        signIn.setOnClickListener { startActivity(Intent(this, SignInActivity::class.java)) }
        signOut.setOnClickListener {
            graph.googleAuth.signOut {
                toast(R.string.auth_signed_out)
                bind()
            }
        }

        val sp = graph.spotifyAuth
        if (sp.isSignedIn) {
            spotifyStatus.text = getString(R.string.provider_status_spotify_signed_in, sp.displayName() ?: "")
            spotifyBtn.setText(R.string.auth_spotify_sign_out)
            spotifyBtn.setOnClickListener {
                sp.signOut()
                toast(R.string.auth_spotify_signed_out)
                bind()
            }
        } else {
            spotifyStatus.text = if (sp.isClientConfigured) getString(R.string.provider_status_spotify_sign_in)
            else getString(R.string.provider_status_spotify_missing)
            spotifyBtn.setText(R.string.auth_spotify_sign_in)
            spotifyBtn.setOnClickListener {
                if (!sp.isClientConfigured) {
                    toast(R.string.provider_status_spotify_missing)
                    return@setOnClickListener
                }
                val uri = sp.beginLogin()
                if (uri != null) startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
        }
        lifecycleScope.launch { /* keep activity alive for signOut callback */ }
    }
}
