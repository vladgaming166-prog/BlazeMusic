package com.blazemuzix.app.player

import com.blazemuzix.app.data.models.MediaItem

enum class RepeatMode { OFF, ALL, ONE }

data class PlayerState(
    val current: MediaItem? = null,
    val queue: List<MediaItem> = emptyList(),
    val index: Int = -1,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val durationMs: Long = 0L,
    val error: String? = null,
    val sleepUntilEpoch: Long = 0L
) {
    val hasContent: Boolean get() = current != null
}
