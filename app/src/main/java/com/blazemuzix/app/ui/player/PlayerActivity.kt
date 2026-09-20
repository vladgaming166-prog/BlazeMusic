package com.blazemuzix.app.ui.player

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
import com.blazemuzix.app.BlazeApp
import com.blazemuzix.app.R
import com.blazemuzix.app.data.models.MediaItem
import com.blazemuzix.app.player.PlayerController
import com.blazemuzix.app.player.PlayerState
import com.blazemuzix.app.player.RepeatMode
import com.blazemuzix.app.ui.common.ItemActionsSheet
import com.blazemuzix.app.utils.Artwork
import com.blazemuzix.app.utils.Formatters
import com.blazemuzix.app.utils.applySystemBarInsets
import com.blazemuzix.app.utils.toast
import com.blazemuzix.app.utils.visible
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class PlayerActivity : AppCompatActivity() {

    private lateinit var artwork: ImageView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var source: TextView
    private lateinit var previewNotice: TextView
    private lateinit var seek: SeekBar
    private lateinit var position: TextView
    private lateinit var duration: TextView
    private lateinit var playPause: ImageButton
    private lateinit var buffering: ProgressBar
    private lateinit var shuffle: ImageButton
    private lateinit var repeat: ImageButton
    private lateinit var favorite: ImageButton
    private lateinit var queueButton: MaterialButton
    private var userSeeking = false
    private var lastArtworkId: String? = null
    private var lastError: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        findViewById<View>(R.id.player_root).applySystemBarInsets(top = true, bottom = true)

        artwork = findViewById(R.id.player_artwork)
        title = findViewById(R.id.player_title)
        artist = findViewById(R.id.player_artist)
        source = findViewById(R.id.player_source)
        previewNotice = findViewById(R.id.player_preview_notice)
        seek = findViewById(R.id.player_seek)
        position = findViewById(R.id.player_position)
        duration = findViewById(R.id.player_duration)
        playPause = findViewById(R.id.player_play_pause)
        buffering = findViewById(R.id.player_buffering)
        shuffle = findViewById(R.id.player_shuffle)
        repeat = findViewById(R.id.player_repeat)
        favorite = findViewById(R.id.player_favorite)
        queueButton = findViewById(R.id.player_queue)

        findViewById<View>(R.id.player_collapse).setOnClickListener { finish() }
        findViewById<View>(R.id.player_more).setOnClickListener {
            PlayerController.current.current?.let { ItemActionsSheet.show(supportFragmentManager, it, PlayerController.current.queue) }
        }
        playPause.setOnClickListener { PlayerController.togglePlayPause(this) }
        findViewById<View>(R.id.player_next).setOnClickListener { PlayerController.next(this) }
        findViewById<View>(R.id.player_previous).setOnClickListener { PlayerController.previous(this) }
        shuffle.setOnClickListener { PlayerController.toggleShuffle(this) }
        repeat.setOnClickListener { PlayerController.cycleRepeat(this) }
        favorite.setOnClickListener { toggleFavorite() }
        queueButton.setOnClickListener { QueueSheet().show(supportFragmentManager, "queue") }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) position.text = Formatters.position(progressToMs(progress))
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                userSeeking = false
                PlayerController.seekTo(this@PlayerActivity, progressToMs(bar.progress))
            }
        })

        PlayerController.state.observe(this) { render(it) }
        PlayerController.position.observe(this) { pos ->
            if (userSeeking) return@observe
            val total = PlayerController.current.durationMs
            seek.progress = if (total > 0) (pos * 1000 / total).toInt().coerceIn(0, 1000) else 0
            position.text = Formatters.position(pos)
        }
        BlazeApp.graph(this).library.changes.observe(this) { renderFavorite(PlayerController.current.current) }
    }

    private fun progressToMs(progress: Int): Long {
        val total = PlayerController.current.durationMs
        return if (total > 0) progress * total / 1000 else 0L
    }

    private fun render(state: PlayerState) {
        val item = state.current
        if (item == null) {
            title.text = getString(R.string.player_nothing_playing)
            artist.text = getString(R.string.player_tap_to_start)
            source.text = ""
            previewNotice.visible(false)
            playPause.isEnabled = false
            duration.text = getString(R.string.default_time)
            seek.progress = 0
            lastArtworkId = null
            Artwork.load(artwork, null as MediaItem?, artworkSize(), resources.getDimensionPixelSize(R.dimen.corner_l))
            return
        }
        playPause.isEnabled = true
        title.text = item.title
        artist.text = item.artist + (item.album?.let { " · $it" } ?: "")
        source.text = getString(R.string.player_now_playing_from, item.source.label)
        previewNotice.visible(item.previewOnly)
        duration.text = Formatters.position(state.durationMs)
        playPause.setImageResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        playPause.contentDescription = getString(if (state.isPlaying) R.string.action_pause else R.string.action_play)
        buffering.visible(state.isBuffering)
        playPause.alpha = if (state.isBuffering) 0.5f else 1f

        val active = ContextCompat.getColor(this, R.color.blaze_green_bright)
        val inactive = ContextCompat.getColor(this, R.color.white_70)
        ImageViewCompat.setImageTintList(shuffle, android.content.res.ColorStateList.valueOf(if (state.shuffle) active else inactive))
        shuffle.contentDescription = getString(if (state.shuffle) R.string.player_shuffle_on else R.string.player_shuffle_off)
        repeat.setImageResource(if (state.repeat == RepeatMode.ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        ImageViewCompat.setImageTintList(repeat, android.content.res.ColorStateList.valueOf(if (state.repeat != RepeatMode.OFF) active else inactive))
        repeat.contentDescription = getString(
            when (state.repeat) {
                RepeatMode.OFF -> R.string.player_repeat_off
                RepeatMode.ALL -> R.string.player_repeat_all
                RepeatMode.ONE -> R.string.player_repeat_one
            }
        )
        queueButton.text = getString(R.string.title_queue) + " · " + state.queue.size
        renderFavorite(item)

        if (lastArtworkId != item.id) {
            lastArtworkId = item.id
            Artwork.load(artwork, item, artworkSize(), resources.getDimensionPixelSize(R.dimen.corner_l))
        }
        if (state.error != null && state.error != lastError) {
            lastError = state.error
            toast(state.error)
        } else if (state.error == null) {
            lastError = null
        }
    }

    private fun artworkSize(): Int {
        val lowEnd = BlazeApp.graph(this).prefs.lowEndMode
        val max = resources.getDimensionPixelSize(R.dimen.artwork_full_max)
        return if (lowEnd) max / 2 else max
    }

    private fun renderFavorite(item: MediaItem?) {
        val fav = item != null && BlazeApp.graph(this).library.isFavorite(item.id)
        favorite.setImageResource(if (fav) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
        favorite.contentDescription = getString(if (fav) R.string.action_unfavorite else R.string.action_favorite)
        ImageViewCompat.setImageTintList(
            favorite,
            android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, if (fav) R.color.blaze_green_bright else R.color.white))
        )
    }

    private fun toggleFavorite() {
        val item = PlayerController.current.current ?: return
        lifecycleScope.launch {
            val now = BlazeApp.graph(this@PlayerActivity).library.toggleFavorite(item)
            toast(if (now) R.string.favorite_added else R.string.favorite_removed)
        }
    }
}
