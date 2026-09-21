package com.blazemuzix.app.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.session.MediaButtonReceiver
import com.blazemuzix.app.BlazeApp
import com.blazemuzix.app.R
import com.blazemuzix.app.data.models.MediaItem
import com.blazemuzix.app.data.prefs.AppPreferences
import com.blazemuzix.app.ui.player.PlayerActivity
import com.blazemuzix.app.utils.Artwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground media playback service built on android.media.MediaPlayer, which is
 * available and stable on every supported Android version (4.4 → 15+).
 *
 *  - background / screen-off playback via a foreground service + wake mode
 *  - MediaSessionCompat for lock-screen, Bluetooth and headset controls
 *  - MediaStyle notification with previous / play-pause / next
 *  - audio focus handling (pause on loss, duck on transient loss)
 *  - pauses when headphones are unplugged
 *  - releases everything and stops itself when idle
 */
class PlaybackService : MediaBrowserServiceCompat() {

    private lateinit var prefs: AppPreferences
    private lateinit var session: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var player: MediaPlayer? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var artworkJob: Job? = null

    private var queue: MutableList<MediaItem> = ArrayList()
    private var order: MutableList<Int> = ArrayList()
    private var orderPos = -1
    private var shuffle = false
    private var repeat = RepeatMode.OFF
    private var prepared = false
    private var playWhenReady = false
    private var pendingSeek = -1L
    private var resumeOnFocusGain = false
    private var noisyRegistered = false
    private var currentArt: Bitmap? = null
    private var currentArtId: String? = null
    private var isForeground = false
    private var sleepUntil: Long = 0L
    private val sleepRunnable = Runnable {
        pause()
        sleepUntil = 0L
        PlayerController.publishSleep(0L)
        publish()
    }

    private val currentIndex: Int get() = if (orderPos in order.indices) order[orderPos] else -1
    private val currentItem: MediaItem? get() = queue.getOrNull(currentIndex)

    // -------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        prefs = BlazeApp.graph(this).prefs
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        shuffle = prefs.shuffleByDefault
        repeat = when (prefs.repeatMode) {
            AppPreferences.REPEAT_ALL -> RepeatMode.ALL
            AppPreferences.REPEAT_ONE -> RepeatMode.ONE
            else -> RepeatMode.OFF
        }
        createChannel()
        session = MediaSessionCompat(this, "BlazeMuzix").apply {
            @Suppress("DEPRECATION")
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(sessionCallback)
            setSessionActivity(contentIntent())
        }
        sessionToken = session.sessionToken
        publish()
        restorePersistedQueue()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(session, intent)
        when (intent?.action) {
            ACTION_PLAY_QUEUE -> {
                val items = pendingQueue ?: emptyList()
                pendingQueue = null
                if (items.isNotEmpty()) {
                    if (intent.hasExtra(EXTRA_SHUFFLE)) shuffle = intent.getBooleanExtra(EXTRA_SHUFFLE, shuffle)
                    setQueue(items, intent.getIntExtra(EXTRA_INDEX, 0))
                } else {
                    stopIfIdle()
                }
            }
            ACTION_ADD_TO_QUEUE -> {
                val items = pendingQueue ?: emptyList()
                pendingQueue = null
                addToQueue(items, intent.getBooleanExtra(EXTRA_PLAY_NEXT, false))
            }
            ACTION_PLAY -> if (queue.isEmpty()) restorePersistedQueue(play = true) else play()
            ACTION_PAUSE -> pause()
            ACTION_TOGGLE -> if (queue.isEmpty()) restorePersistedQueue(play = true) else if (player?.isPlaying == true) pause() else play()
            ACTION_NEXT -> next(userInitiated = true)
            ACTION_PREVIOUS -> previous()
            ACTION_SEEK -> seekTo(intent.getLongExtra(EXTRA_POSITION, 0L))
            ACTION_SKIP_TO -> skipToQueueIndex(intent.getIntExtra(EXTRA_INDEX, 0))
            ACTION_REMOVE_FROM_QUEUE -> removeFromQueue(intent.getIntExtra(EXTRA_INDEX, -1))
            ACTION_TOGGLE_SHUFFLE -> toggleShuffle()
            ACTION_CYCLE_REPEAT -> cycleRepeat()
            ACTION_STOP -> stopPlayback()
            ACTION_SLEEP -> setSleepTimer(intent.getLongExtra(EXTRA_SLEEP_MS, 0L))
            ACTION_MOVE_QUEUE -> moveQueueItem(intent.getIntExtra(EXTRA_FROM, -1), intent.getIntExtra(EXTRA_TO, -1))
            else -> Unit
        }
        // Android 8+ requires a foreground notification shortly after startForegroundService();
        // when nothing is loaded we stop immediately instead, which also satisfies that rule.
        if (currentItem == null) {
            stopIfIdle()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isForeground) {
            goForeground(buildNotification())
        }
        return START_NOT_STICKY
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot =
        BrowserRoot("blazemuzix_root", null)

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        result.sendResult(mutableListOf())
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (player?.isPlaying != true) stopPlayback()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        artworkJob?.cancel()
        scope.cancel()
        releasePlayer()
        abandonFocus()
        unregisterNoisy()
        session.isActive = false
        session.release()
        PlayerController.publish(PlayerState())
        PlayerController.publishPosition(0L)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ queue

    private fun setQueue(items: List<MediaItem>, startIndex: Int, autoPlay: Boolean = true) {
        queue = ArrayList(items)
        rebuildOrder(startIndex.coerceIn(0, queue.lastIndex))
        prepareCurrent(autoPlay = autoPlay)
    }

    private fun addToQueue(items: List<MediaItem>, playNext: Boolean) {
        if (items.isEmpty()) return
        if (queue.isEmpty()) {
            setQueue(items, 0)
            return
        }
        val fresh = items.filter { item -> queue.none { it.id == item.id } }
        if (fresh.isEmpty()) {
            publish()
            return
        }
        val current = currentIndex
        val insertAt = if (playNext) current + 1 else queue.size
        queue.addAll(insertAt, fresh)
        // Items are inserted after the current one, so its index is unchanged.
        rebuildOrder(current, keepShuffleState = true, appended = fresh.size, insertAt = insertAt, playNext = playNext)
        publish()
    }

    private fun rebuildOrder(
        currentIdx: Int,
        keepShuffleState: Boolean = false,
        appended: Int = 0,
        insertAt: Int = -1,
        playNext: Boolean = false
    ) {
        if (!shuffle) {
            order = (0 until queue.size).toMutableList()
            orderPos = currentIdx
            return
        }
        if (keepShuffleState && appended > 0 && insertAt >= 0) {
            // Shift existing indices >= insertAt, then place new indices right after the current one (or randomly at the end).
            val shifted = order.map { if (it >= insertAt) it + appended else it }.toMutableList()
            val newIdx = (insertAt until insertAt + appended).toMutableList()
            if (playNext) shifted.addAll(orderPos + 1, newIdx) else shifted.addAll(newIdx.shuffled())
            order = shifted
            return
        }
        val rest = (0 until queue.size).filter { it != currentIdx }.shuffled()
        order = (listOf(currentIdx) + rest).toMutableList()
        orderPos = 0
    }

    private fun removeFromQueue(index: Int) {
        if (index !in queue.indices) return
        val current = currentIndex
        val removingCurrent = index == current
        val wasPlaying = player?.isPlaying == true
        queue.removeAt(index)
        val removedOrderPos = order.indexOf(index)
        order = order.filter { it != index }.map { if (it > index) it - 1 else it }.toMutableList()
        if (queue.isEmpty()) {
            stopPlayback()
            return
        }
        if (removingCurrent) {
            orderPos = removedOrderPos.coerceIn(0, order.lastIndex)
            prepareCurrent(autoPlay = wasPlaying)
        } else {
            val newCurrent = if (current > index) current - 1 else current
            orderPos = order.indexOf(newCurrent).coerceAtLeast(0)
            publish()
        }
    }

    private fun skipToQueueIndex(index: Int) {
        if (index !in queue.indices) return
        orderPos = order.indexOf(index)
        prepareCurrent(autoPlay = true)
    }

    private fun toggleShuffle() {
        shuffle = !shuffle
        val current = currentIndex
        rebuildOrder(current)
        publish()
    }

    private fun cycleRepeat() {
        repeat = when (repeat) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        publish()
    }

    private fun moveQueueItem(from: Int, to: Int) {
        if (from !in queue.indices || to !in queue.indices || from == to) return
        val currentId = currentItem?.id
        val item = queue.removeAt(from)
        queue.add(to, item)
        rebuildOrder(queue.indexOfFirst { it.id == currentId }.coerceAtLeast(0))
        publish()
    }

    private fun setSleepTimer(durationMs: Long) {
        handler.removeCallbacks(sleepRunnable)
        sleepUntil = if (durationMs <= 0L) 0L else System.currentTimeMillis() + durationMs
        if (sleepUntil > 0L) handler.postDelayed(sleepRunnable, durationMs)
        PlayerController.publishSleep(sleepUntil)
        publish()
    }

    private fun restorePersistedQueue(play: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            val saved = runCatching { BlazeApp.graph(this@PlaybackService).library.loadQueue() }.getOrNull() ?: return@launch
            if (saved.items.isEmpty()) return@launch
            launch(Dispatchers.Main) {
                if (queue.isNotEmpty()) {
                    if (play) play()
                    return@launch
                }
                shuffle = saved.shuffle
                repeat = when (saved.repeat) {
                    "all" -> RepeatMode.ALL
                    "one" -> RepeatMode.ONE
                    else -> RepeatMode.OFF
                }
                setQueue(saved.items, saved.index, autoPlay = play)
            }
        }
    }

    private fun persistQueueAsync() {
        val snapshot = queue.toList()
        val idx = currentIndex
        val sh = shuffle
        val rp = when (repeat) {
            RepeatMode.ALL -> "all"
            RepeatMode.ONE -> "one"
            else -> "off"
        }
        scope.launch(Dispatchers.IO) {
            runCatching {
                val lib = BlazeApp.graph(this@PlaybackService).library
                if (snapshot.isEmpty()) lib.clearQueue() else lib.persistQueue(snapshot, idx, sh, rp)
            }
        }
    }

    // --------------------------------------------------------------- playback

    private fun ensurePlayer(): MediaPlayer {
        player?.let { return it }
        val mp = MediaPlayer()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
        } else {
            @Suppress("DEPRECATION")
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC)
        }
        mp.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
        mp.setOnPreparedListener {
            prepared = true
            if (pendingSeek >= 0) {
                it.seekTo(pendingSeek.toInt())
                pendingSeek = -1
            }
            if (playWhenReady) startPlayer() else publish()
        }
        mp.setOnCompletionListener { onCompleted() }
        mp.setOnErrorListener { _, _, _ ->
            prepared = false
            publish(error = getString(R.string.player_error))
            // Skip a broken item automatically so playback keeps flowing.
            handler.postDelayed({ if (hasNext()) next(userInitiated = false) else stopForegroundKeepNotification() }, 800)
            true
        }
        mp.setOnBufferingUpdateListener { _, _ -> }
        player = mp
        return mp
    }

    private fun prepareCurrent(autoPlay: Boolean) {
        val item = currentItem ?: run { stopPlayback(); return }
        val uri = item.playbackUri ?: run { next(userInitiated = false); return }
        val mp = ensurePlayer()
        prepared = false
        playWhenReady = autoPlay
        pendingSeek = -1
        try {
            mp.reset()
            mp.setDataSource(applicationContext, Uri.parse(uri))
            mp.prepareAsync()
        } catch (e: Exception) {
            publish(error = getString(R.string.player_error))
            handler.postDelayed({ if (hasNext()) next(userInitiated = false) else stopForegroundKeepNotification() }, 800)
            return
        }
        session.isActive = true
        updateMetadata(item)
        publish(buffering = true)
        scope.launch { BlazeApp.graph(this@PlaybackService).library.recordPlayed(item) }
    }

    private fun play() {
        val item = currentItem ?: return
        if (!prepared) {
            playWhenReady = true
            if (player == null) prepareCurrent(autoPlay = true)
            return
        }
        if (item.playbackUri == null) return
        startPlayer()
    }

    private fun startPlayer() {
        if (!requestFocus()) return
        val mp = player ?: return
        try {
            mp.start()
        } catch (_: IllegalStateException) {
            prepareCurrent(autoPlay = true)
            return
        }
        handler.removeCallbacks(idleStop)
        registerNoisy()
        session.isActive = true
        publish()
        goForeground(buildNotification())
        startTicker()
    }

    private fun pause() {
        val mp = player ?: return
        try {
            if (mp.isPlaying) mp.pause()
        } catch (_: IllegalStateException) {
        }
        playWhenReady = false
        stopTicker()
        unregisterNoisy()
        publish()
        stopForegroundKeepNotification()
        // Free resources if the user does not come back within a few minutes.
        handler.removeCallbacks(idleStop)
        handler.postDelayed(idleStop, IDLE_STOP_MS)
    }

    private fun seekTo(positionMs: Long) {
        val mp = player ?: return
        if (prepared) {
            try {
                mp.seekTo(positionMs.toInt())
            } catch (_: IllegalStateException) {
            }
            PlayerController.publishPosition(positionMs)
            publish()
        } else {
            pendingSeek = positionMs
        }
    }

    private fun hasNext(): Boolean = orderPos < order.lastIndex || repeat == RepeatMode.ALL

    private fun next(userInitiated: Boolean) {
        if (order.isEmpty()) return
        if (orderPos < order.lastIndex) {
            orderPos++
        } else if (repeat == RepeatMode.ALL || userInitiated) {
            orderPos = 0
        } else {
            return
        }
        prepareCurrent(autoPlay = true)
    }

    private fun previous() {
        val mp = player
        if (mp != null && prepared && safePosition() > 3_000) {
            seekTo(0)
            return
        }
        if (order.isEmpty()) return
        orderPos = if (orderPos > 0) orderPos - 1 else if (repeat == RepeatMode.ALL) order.lastIndex else 0
        prepareCurrent(autoPlay = true)
    }

    private fun onCompleted() {
        when {
            repeat == RepeatMode.ONE -> { seekTo(0); startPlayer() }
            orderPos < order.lastIndex -> next(userInitiated = false)
            repeat == RepeatMode.ALL -> { orderPos = 0; prepareCurrent(autoPlay = true) }
            prefs.autoplay -> continueWithLocalMusic()
            else -> finishQueue()
        }
    }

    /** Autoplay: when the queue ends, continue with other songs on the device. */
    private fun continueWithLocalMusic() {
        scope.launch {
            val local = try {
                BlazeApp.graph(this@PlaybackService).localProvider.allSongs()
            } catch (_: Exception) {
                emptyList()
            }
            val inQueue = queue.map { it.id }.toHashSet()
            val more = local.filter { it.id !in inQueue }.shuffled().take(AUTOPLAY_BATCH)
            if (more.isEmpty()) {
                finishQueue()
            } else {
                queue.addAll(more)
                order.addAll((queue.size - more.size until queue.size).toList())
                orderPos++
                prepareCurrent(autoPlay = true)
            }
        }
    }

    private fun finishQueue() {
        playWhenReady = false
        stopTicker()
        PlayerController.publishPosition(0L)
        try { player?.seekTo(0) } catch (_: Exception) {}
        publish()
        stopForegroundKeepNotification()
        handler.postDelayed(idleStop, IDLE_STOP_MS)
    }

    private fun stopPlayback() {
        stopTicker()
        releasePlayer()
        queue.clear()
        order.clear()
        orderPos = -1
        abandonFocus()
        unregisterNoisy()
        session.isActive = false
        PlayerController.publish(PlayerState(shuffle = shuffle, repeat = repeat))
        PlayerController.publishPosition(0L)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        isForeground = false
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        scope.launch(Dispatchers.IO) { runCatching { BlazeApp.graph(this@PlaybackService).library.clearQueue() } }
        stopSelf()
    }

    private fun stopIfIdle() {
        if (currentItem == null) stopPlayback()
    }

    private val idleStop = Runnable { if (player?.isPlaying != true) stopPlayback() }

    private fun releasePlayer() {
        try {
            player?.reset()
            player?.release()
        } catch (_: Exception) {
        }
        player = null
        prepared = false
    }

    private fun safePosition(): Long = try {
        if (prepared) player?.currentPosition?.toLong() ?: 0L else 0L
    } catch (_: Exception) {
        0L
    }

    private fun safeDuration(): Long {
        val fromPlayer = try { if (prepared) player?.duration?.toLong() ?: 0L else 0L } catch (_: Exception) { 0L }
        return if (fromPlayer > 0) fromPlayer else currentItem?.durationMs ?: 0L
    }

    // -------------------------------------------------------------- ticker

    private val ticker = object : Runnable {
        override fun run() {
            PlayerController.publishPosition(safePosition())
            updateSessionState()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun startTicker() {
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun stopTicker() {
        handler.removeCallbacks(ticker)
        PlayerController.publishPosition(safePosition())
    }

    // ---------------------------------------------------------- audio focus

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> { resumeOnFocusGain = false; pause() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { resumeOnFocusGain = player?.isPlaying == true; pause() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> try { player?.setVolume(0.25f, 0.25f) } catch (_: Exception) {}
            AudioManager.AUDIOFOCUS_GAIN -> {
                try { player?.setVolume(1f, 1f) } catch (_: Exception) {}
                if (resumeOnFocusGain) { resumeOnFocusGain = false; play() }
            }
        }
    }

    private fun requestFocus(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                )
                .setOnAudioFocusChangeListener(focusListener)
                .setWillPauseWhenDucked(false)
                .build().also { audioFocusRequest = it }
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(focusListener)
            }
        } catch (_: Exception) {
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        ContextCompat.registerReceiver(
            this, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_EXPORTED
        )
        noisyRegistered = true
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        try { unregisterReceiver(noisyReceiver) } catch (_: Exception) {}
        noisyRegistered = false
    }

    // -------------------------------------------------------- media session

    private val sessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() = play()
        override fun onPause() = pause()
        override fun onSkipToNext() = next(userInitiated = true)
        override fun onSkipToPrevious() = previous()
        override fun onStop() = stopPlayback()
        override fun onSeekTo(pos: Long) = seekTo(pos)
        override fun onSkipToQueueItem(id: Long) = skipToQueueIndex(id.toInt())
        override fun onSetShuffleMode(shuffleMode: Int) {
            shuffle = shuffleMode != PlaybackStateCompat.SHUFFLE_MODE_NONE
            rebuildOrder(currentIndex)
            publish()
        }
        override fun onSetRepeatMode(repeatMode: Int) {
            repeat = when (repeatMode) {
                PlaybackStateCompat.REPEAT_MODE_ONE -> RepeatMode.ONE
                PlaybackStateCompat.REPEAT_MODE_ALL, PlaybackStateCompat.REPEAT_MODE_GROUP -> RepeatMode.ALL
                else -> RepeatMode.OFF
            }
            publish()
        }
    }

    private fun updateMetadata(item: MediaItem) {
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, item.id)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, item.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, item.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, item.album ?: item.source.label)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, item.durationMs)
        if (currentArtId == item.id) currentArt?.let { builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
        session.setMetadata(builder.build())

        if (currentArtId != item.id) {
            currentArt = null
            currentArtId = item.id
            artworkJob?.cancel()
            artworkJob = scope.launch {
                val size = if (prefs.lowEndMode) 192 else 320
                val bitmap = Artwork.loadBitmap(this@PlaybackService, item.artworkUrl, size)
                if (currentItem?.id == item.id) {
                    currentArt = bitmap
                    if (bitmap != null) {
                        session.setMetadata(builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap).build())
                    }
                    refreshNotification()
                }
            }
        }
    }

    private fun updateSessionState() {
        val playing = player?.isPlaying == true
        val state = when {
            currentItem == null -> PlaybackStateCompat.STATE_STOPPED
            !prepared -> PlaybackStateCompat.STATE_BUFFERING
            playing -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        val actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_STOP or PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE or
            PlaybackStateCompat.ACTION_SET_REPEAT_MODE
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, safePosition(), if (playing) 1f else 0f)
                .build()
        )
        session.setShuffleMode(if (shuffle) PlaybackStateCompat.SHUFFLE_MODE_ALL else PlaybackStateCompat.SHUFFLE_MODE_NONE)
        session.setRepeatMode(
            when (repeat) {
                RepeatMode.OFF -> PlaybackStateCompat.REPEAT_MODE_NONE
                RepeatMode.ALL -> PlaybackStateCompat.REPEAT_MODE_ALL
                RepeatMode.ONE -> PlaybackStateCompat.REPEAT_MODE_ONE
            }
        )
    }

    // -------------------------------------------------------------- publish

    private fun publish(buffering: Boolean = false, error: String? = null) {
        updateSessionState()
        PlayerController.publish(
            PlayerState(
                current = currentItem,
                queue = queue.toList(),
                index = currentIndex,
                isPlaying = player?.isPlaying == true,
                isBuffering = buffering || (currentItem != null && !prepared && error == null),
                shuffle = shuffle,
                repeat = repeat,
                durationMs = safeDuration(),
                error = error,
                sleepUntilEpoch = sleepUntil
            )
        )
        persistQueueAsync()
        if (currentItem != null) refreshNotification()
    }

    // --------------------------------------------------------- notification

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_playback), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.notification_channel_playback_desc)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    private fun pendingFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

    private fun contentIntent(): PendingIntent {
        val intent = Intent(this, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(this, 1, intent, pendingFlags())
    }

    private fun actionIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(this, requestCode, Intent(this, PlaybackService::class.java).setAction(action), pendingFlags())

    private fun buildNotification(): Notification {
        val item = currentItem
        val playing = player?.isPlaying == true
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_blaze)
            .setContentTitle(item?.title ?: getString(R.string.app_name))
            .setContentText(item?.artist ?: "")
            .setSubText(item?.album)
            .setLargeIcon(currentArt)
            .setContentIntent(contentIntent())
            .setDeleteIntent(actionIntent(ACTION_STOP, 10))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(playing)
            .setColor(ContextCompat.getColor(this, R.color.blaze_green))
            .addAction(notifIcon(R.drawable.ic_skip_previous, R.drawable.ic_notif_prev), getString(R.string.action_previous), actionIntent(ACTION_PREVIOUS, 11))
            .addAction(
                if (playing) notifIcon(R.drawable.ic_pause, R.drawable.ic_notif_pause) else notifIcon(R.drawable.ic_play, R.drawable.ic_notif_play),
                getString(if (playing) R.string.action_pause else R.string.action_play),
                actionIntent(ACTION_TOGGLE, 12)
            )
            .addAction(notifIcon(R.drawable.ic_skip_next, R.drawable.ic_notif_next), getString(R.string.action_next), actionIntent(ACTION_NEXT, 13))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(actionIntent(ACTION_STOP, 14))
            )
        return builder.build()
    }

    /** SystemUI on API < 21 cannot inflate vector drawables, so notification actions fall back to PNGs there. */
    private fun notifIcon(vector: Int, legacyPng: Int): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) vector else legacyPng

    private fun goForeground(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForeground = true
        } catch (_: Exception) {
            // e.g. missing notification permission on Android 13+: playback still works in the foreground.
        }
    }

    private fun stopForegroundKeepNotification() {
        if (isForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            isForeground = false
        }
        refreshNotification()
    }

    private fun refreshNotification() {
        if (currentItem == null) return
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    companion object {
        const val CHANNEL_ID = "blazemuzix_playback"
        const val NOTIFICATION_ID = 4201
        private const val TICK_MS = 500L
        private const val IDLE_STOP_MS = 5 * 60 * 1000L
        private const val AUTOPLAY_BATCH = 20

        const val ACTION_PLAY_QUEUE = "com.blazemuzix.app.action.PLAY_QUEUE"
        const val ACTION_ADD_TO_QUEUE = "com.blazemuzix.app.action.ADD_TO_QUEUE"
        const val ACTION_PLAY = "com.blazemuzix.app.action.PLAY"
        const val ACTION_PAUSE = "com.blazemuzix.app.action.PAUSE"
        const val ACTION_TOGGLE = "com.blazemuzix.app.action.TOGGLE"
        const val ACTION_NEXT = "com.blazemuzix.app.action.NEXT"
        const val ACTION_PREVIOUS = "com.blazemuzix.app.action.PREVIOUS"
        const val ACTION_SEEK = "com.blazemuzix.app.action.SEEK"
        const val ACTION_SKIP_TO = "com.blazemuzix.app.action.SKIP_TO"
        const val ACTION_REMOVE_FROM_QUEUE = "com.blazemuzix.app.action.REMOVE_FROM_QUEUE"
        const val ACTION_TOGGLE_SHUFFLE = "com.blazemuzix.app.action.TOGGLE_SHUFFLE"
        const val ACTION_CYCLE_REPEAT = "com.blazemuzix.app.action.CYCLE_REPEAT"
        const val ACTION_STOP = "com.blazemuzix.app.action.STOP"
        const val ACTION_SLEEP = "com.blazemuzix.app.action.SLEEP"
        const val ACTION_MOVE_QUEUE = "com.blazemuzix.app.action.MOVE_QUEUE"

        const val EXTRA_INDEX = "index"
        const val EXTRA_POSITION = "position"
        const val EXTRA_SHUFFLE = "shuffle"
        const val EXTRA_PLAY_NEXT = "play_next"
        const val EXTRA_SLEEP_MS = "sleep_ms"
        const val EXTRA_FROM = "from"
        const val EXTRA_TO = "to"

        /**
         * Queues are handed over in-process instead of through Intent extras to
         * avoid the 1 MB Binder transaction limit with large local libraries.
         */
        @Volatile
        internal var pendingQueue: List<MediaItem>? = null
    }
}
