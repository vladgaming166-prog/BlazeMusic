package com.blazemuzix.app.ui.cloud

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.blazemuzix.app.BlazeApp
import com.blazemuzix.app.R
import com.blazemuzix.app.cloud.CloudTrack
import com.blazemuzix.app.player.PlayerController
import com.blazemuzix.app.ui.common.ItemActions
import com.blazemuzix.app.utils.Appearance
import com.blazemuzix.app.utils.Artwork
import com.blazemuzix.app.utils.ExternalActions
import com.blazemuzix.app.utils.Formatters
import com.blazemuzix.app.utils.applySystemBarInsets
import com.blazemuzix.app.utils.dp
import com.blazemuzix.app.utils.toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class TrackActivity : AppCompatActivity() {

    private var track: CloudTrack? = null
    private var liked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        Appearance.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_track)
        Appearance.decorate(this, findViewById(R.id.track_root))
        findViewById<View>(R.id.track_root).applySystemBarInsets(top = true, bottom = true)
        findViewById<View>(R.id.track_back).setOnClickListener { finish() }
        val id = intent.getStringExtra(EXTRA_ID)
        if (id.isNullOrBlank()) {
            finish(); return
        }
        lifecycleScope.launch { load(id) }
    }

    private suspend fun load(id: String) {
        val graph = BlazeApp.graph(this)
        val loaded = try {
            graph.cloud.track(id)
        } catch (e: Exception) {
            toast(e.message ?: getString(R.string.upload_failed))
            finish(); return
        }
        if (loaded == null) {
            toast(getString(R.string.state_invalid_request_message))
            finish(); return
        }
        track = loaded
        bind(loaded)
        liked = runCatching { graph.cloud.isLiked(id) }.getOrDefault(false)
        refreshLike()
        bindComments(id)
    }

    private fun bind(track: CloudTrack) {
        val item = track.toMediaItem()
        Artwork.load(findViewById(R.id.track_cover), item, dp(220), dp(16))
        findViewById<TextView>(R.id.track_title).text = track.title
        findViewById<TextView>(R.id.track_artist).text = track.artist
        findViewById<TextView>(R.id.track_description).text = track.description.orEmpty()
        findViewById<TextView>(R.id.track_meta).text = listOfNotNull(
            Formatters.duration(track.durationMs).takeIf { track.durationMs > 0 },
            getString(R.string.track_play_count, track.playCount),
            getString(R.string.track_like_count, track.likeCount),
            track.genre,
            if (track.year > 0) track.year.toString() else null
        ).joinToString(" · ")
        findViewById<MaterialButton>(R.id.track_play).setOnClickListener {
            PlayerController.play(this, item)
        }
        findViewById<MaterialButton>(R.id.track_like).setOnClickListener { toggleLike() }
        findViewById<MaterialButton>(R.id.track_playlist).setOnClickListener { ItemActions.addToPlaylist(this, item) }
        findViewById<MaterialButton>(R.id.track_share).setOnClickListener {
            val shareable = item.copy(externalUrl = track.audioUrl.takeIf { track.visibility == "public" })
            if (shareable.externalUrl != null) ExternalActions.share(this, shareable) else toast(R.string.auth_guest_message)
        }
        findViewById<MaterialButton>(R.id.track_report).setOnClickListener { report() }
        val graph = BlazeApp.graph(this)
        val mine = graph.auth.hasCloudSession() && graph.cloud.myId() == track.ownerId
        findViewById<MaterialButton>(R.id.track_offline).visibility = if (mine) View.VISIBLE else View.GONE
        findViewById<MaterialButton>(R.id.track_offline).setOnClickListener {
            lifecycleScope.launch {
                try {
                    graph.cloud.cacheOwnTrack(track) {}
                    toast(R.string.offline_own_done)
                } catch (e: Exception) {
                    toast(e.message ?: getString(R.string.offline_own_only))
                }
            }
        }
        findViewById<View>(R.id.track_artist).setOnClickListener {
            startActivity(android.content.Intent(this, ProfileActivity::class.java).putExtra(ProfileActivity.EXTRA_ID, track.ownerId))
        }
        findViewById<MaterialButton>(R.id.track_comment_send).setOnClickListener { sendComment() }
    }

    private fun refreshLike() {
        findViewById<MaterialButton>(R.id.track_like).setText(if (liked) R.string.action_unfavorite else R.string.action_favorite)
    }

    private fun toggleLike() {
        val id = track?.id ?: return
        lifecycleScope.launch {
            try {
                liked = BlazeApp.graph(this@TrackActivity).cloud.likeTrack(id)
                refreshLike()
            } catch (e: Exception) {
                toast(e.message ?: getString(R.string.auth_guest_message))
            }
        }
    }

    private fun sendComment() {
        val id = track?.id ?: return
        val body = findViewById<EditText>(R.id.track_comment).text?.toString().orEmpty()
        lifecycleScope.launch {
            try {
                BlazeApp.graph(this@TrackActivity).cloud.addComment(id, body)
                findViewById<EditText>(R.id.track_comment).setText("")
                bindComments(id)
            } catch (e: Exception) {
                toast(e.message ?: getString(R.string.comment_sign_in))
            }
        }
    }

    private suspend fun bindComments(id: String) {
        val container = findViewById<LinearLayout>(R.id.track_comments)
        container.removeAllViews()
        val comments = runCatching { BlazeApp.graph(this).cloud.comments(id) }.getOrDefault(emptyList())
        if (comments.isEmpty()) {
            val empty = TextView(this)
            empty.setText(R.string.comment_hint)
            empty.setTextAppearance(this, R.style.TextAppearance_BlazeMuzix_Caption)
            container.addView(empty)
            return
        }
        for (c in comments) {
            val tv = TextView(this)
            tv.text = c.body
            tv.setPadding(0, dp(8), 0, dp(8))
            container.addView(tv)
        }
    }

    private fun report() {
        val id = track?.id ?: return
        val reasons = arrayOf(getString(R.string.report_copyright), getString(R.string.report_abuse), getString(R.string.report_spam), getString(R.string.report_other))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.report_title)
            .setItems(reasons) { _, which ->
                lifecycleScope.launch {
                    try {
                        BlazeApp.graph(this@TrackActivity).cloud.report("track", id, reasons[which])
                        toast(R.string.report_sent)
                    } catch (e: Exception) {
                        toast(e.message ?: getString(R.string.auth_guest_message))
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_ID = "track_id"
    }
}
