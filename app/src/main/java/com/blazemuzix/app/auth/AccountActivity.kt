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
import com.blazemuzix.app.ui.cloud.ProfileActivity
import com.blazemuzix.app.ui.settings.SettingsActivity
import com.blazemuzix.app.utils.Appearance
import com.blazemuzix.app.utils.Artwork
import com.blazemuzix.app.utils.applySystemBarInsets
import com.blazemuzix.app.utils.dp
import com.blazemuzix.app.utils.toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
        val user = graph.auth.current
        val name = findViewById<TextView>(R.id.account_name)
        val email = findViewById<TextView>(R.id.account_email)
        val photo = findViewById<ImageView>(R.id.account_photo)
        val googleStatus = findViewById<TextView>(R.id.account_google_status)
        val spotifyStatus = findViewById<TextView>(R.id.account_spotify_status)
        val signOut = findViewById<MaterialButton>(R.id.account_sign_out)
        val signIn = findViewById<MaterialButton>(R.id.account_sign_in)
        val spotifyBtn = findViewById<MaterialButton>(R.id.account_spotify)

        if (user != null) {
            name.text = user.displayName ?: user.username ?: getString(R.string.auth_signed_in_fallback)
            email.text = user.email ?: ""
            Artwork.load(photo, user.photoUrl, com.blazemuzix.app.data.models.MediaType.USER, dp(72), dp(36))
            googleStatus.text = when {
                graph.auth.hasCloudSession() -> getString(R.string.provider_status_cloud_signed_in)
                user.provider == AuthUser.PROVIDER_GOOGLE -> getString(R.string.provider_status_signed_in)
                else -> getString(R.string.provider_status_signed_in)
            }
            signOut.visibility = View.VISIBLE
            signIn.visibility = View.GONE
            photo.setOnClickListener {
                startActivity(Intent(this, ProfileActivity::class.java).putExtra(ProfileActivity.EXTRA_ID, user.cloudId ?: user.id))
            }
        } else {
            name.setText(R.string.auth_guest)
            email.setText(R.string.auth_guest_message)
            photo.setImageResource(R.drawable.ic_person)
            googleStatus.text = graph.auth.cloudNotice() ?: getString(R.string.provider_status_signed_out)
            signOut.visibility = View.GONE
            signIn.visibility = View.VISIBLE
            photo.setOnClickListener(null)
        }
        signIn.setOnClickListener { startActivity(Intent(this, SignInActivity::class.java)) }
        signOut.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.auth_sign_out)
                .setPositiveButton(R.string.auth_sign_out) { _, _ ->
                    graph.auth.signOut {
                        toast(R.string.auth_signed_out)
                        bind()
                    }
                }
                .setNeutralButton(R.string.auth_delete_account) { _, _ -> confirmDelete() }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
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
        lifecycleScope.launch { }
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.auth_delete_account)
            .setMessage(R.string.auth_delete_confirm)
            .setPositiveButton(R.string.auth_delete_account) { _, _ ->
                lifecycleScope.launch {
                    try {
                        BlazeApp.graph(this@AccountActivity).auth.deleteAccount()
                        toast(R.string.auth_deleted)
                        bind()
                    } catch (e: Exception) {
                        toast(e.message ?: getString(R.string.auth_cloud_not_configured))
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
}
