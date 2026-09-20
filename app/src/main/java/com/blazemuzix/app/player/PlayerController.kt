package com.blazemuzix.app.player

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.blazemuzix.app.data.models.MediaItem

/**
 * Process-wide bridge between the UI and [PlaybackService]. The service publishes
 * state here; the UI observes it and sends commands through intents so the
 * service is started on demand and stopped when idle (no binder plumbing needed).
 */
object PlayerController {

    private val _state = MutableLiveData(PlayerState())
    val state: LiveData<PlayerState> get() = _state

    private val _position = MutableLiveData(0L)
    val position: LiveData<Long> get() = _position

    val current: PlayerState get() = _state.value ?: PlayerState()

    internal fun publish(state: PlayerState) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) _state.value = state else _state.postValue(state)
    }

    internal fun publishPosition(ms: Long) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) _position.value = ms else _position.postValue(ms)
    }

    // ------------------------------------------------------------ commands

    /** Replaces the queue with [items] and starts at [startIndex]. Only directly playable items are kept. */
    fun playQueue(context: Context, items: List<MediaItem>, startIndex: Int = 0, shuffle: Boolean? = null) {
        val playable = items.filter { it.canPlayDirect }
        if (playable.isEmpty()) return
        val target = items.getOrNull(startIndex)
        val index = playable.indexOfFirst { it.id == target?.id }.takeIf { it >= 0 } ?: 0
        PlaybackService.pendingQueue = playable
        val intent = serviceIntent(context, PlaybackService.ACTION_PLAY_QUEUE).apply {
            putExtra(PlaybackService.EXTRA_INDEX, index)
            if (shuffle != null) putExtra(PlaybackService.EXTRA_SHUFFLE, shuffle)
        }
        start(context, intent)
    }

    fun play(context: Context, item: MediaItem) = playQueue(context, listOf(item), 0)

    fun addToQueue(context: Context, item: MediaItem, playNext: Boolean = false) {
        if (!item.canPlayDirect) return
        if (!current.hasContent) {
            play(context, item)
            return
        }
        PlaybackService.pendingQueue = listOf(item)
        start(context, serviceIntent(context, PlaybackService.ACTION_ADD_TO_QUEUE).putExtra(PlaybackService.EXTRA_PLAY_NEXT, playNext))
    }

    fun togglePlayPause(context: Context) = send(context, PlaybackService.ACTION_TOGGLE)
    fun pause(context: Context) = send(context, PlaybackService.ACTION_PAUSE)
    fun resume(context: Context) = send(context, PlaybackService.ACTION_PLAY)
    fun next(context: Context) = send(context, PlaybackService.ACTION_NEXT)
    fun previous(context: Context) = send(context, PlaybackService.ACTION_PREVIOUS)
    fun toggleShuffle(context: Context) = send(context, PlaybackService.ACTION_TOGGLE_SHUFFLE)
    fun cycleRepeat(context: Context) = send(context, PlaybackService.ACTION_CYCLE_REPEAT)
    fun stop(context: Context) = send(context, PlaybackService.ACTION_STOP)

    fun seekTo(context: Context, positionMs: Long) =
        send(context, PlaybackService.ACTION_SEEK, PlaybackService.EXTRA_POSITION to positionMs)

    fun skipToQueueIndex(context: Context, index: Int) =
        send(context, PlaybackService.ACTION_SKIP_TO, PlaybackService.EXTRA_INDEX to index)

    fun removeFromQueue(context: Context, index: Int) =
        send(context, PlaybackService.ACTION_REMOVE_FROM_QUEUE, PlaybackService.EXTRA_INDEX to index)

    private fun send(context: Context, action: String, vararg extras: Pair<String, Any>) {
        if (!current.hasContent && action != PlaybackService.ACTION_STOP) return
        val intent = serviceIntent(context, action)
        for ((k, v) in extras) {
            when (v) {
                is Int -> intent.putExtra(k, v)
                is Long -> intent.putExtra(k, v)
                is Boolean -> intent.putExtra(k, v)
                is String -> intent.putExtra(k, v)
            }
        }
        start(context, intent)
    }

    private fun serviceIntent(context: Context, action: String) =
        Intent(context, PlaybackService::class.java).setAction(action)

    private fun start(context: Context, intent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        } catch (_: Exception) {
            // Background start restrictions: the user must interact again; state stays consistent.
        }
    }
}
