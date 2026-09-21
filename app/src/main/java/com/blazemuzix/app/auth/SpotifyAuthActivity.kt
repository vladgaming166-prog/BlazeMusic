package com.blazemuzix.app.auth

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.blazemuzix.app.BlazeApp
import com.blazemuzix.app.R
import com.blazemuzix.app.ui.MainActivity
import com.blazemuzix.app.utils.toast
import kotlinx.coroutines.launch

/**
 * Catcher for `blazemuzix://callback` after the official Spotify authorize page.
 */
class SpotifyAuthActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri == null) {
            finish()
            return
        }
        lifecycleScope.launch {
            val ok = runCatching { BlazeApp.graph(this@SpotifyAuthActivity).spotifyAuth.completeLogin(uri) }.getOrDefault(false)
            toast(if (ok) R.string.auth_spotify_signed_in else R.string.auth_spotify_failed)
            startActivity(Intent(this@SpotifyAuthActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }
    }
}
