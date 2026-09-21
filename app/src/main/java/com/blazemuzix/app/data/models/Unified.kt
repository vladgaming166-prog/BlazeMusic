package com.blazemuzix.app.data.models

/**
 * Unified catalog types for 2.0. Every search/home result is a [MediaItem] that
 * already carries its [Source] (YouTube, Spotify, Local). These typealiases
 * exist so call sites can be explicit about *kind* without a second parallel
 * model that would drift out of sync.
 *
 * There is no fake catalog: a UnifiedTrack is a real MediaItem of type SONG,
 * from whichever provider actually returned it.
 */
typealias UnifiedTrack = MediaItem
typealias UnifiedAlbum = MediaItem
typealias UnifiedArtist = MediaItem
typealias UnifiedPlaylist = MediaItem
typealias UnifiedVideo = MediaItem

object UnifiedCatalog {
    fun asTrack(item: MediaItem): UnifiedTrack? = item.takeIf { it.type == MediaType.SONG }
    fun asAlbum(item: MediaItem): UnifiedAlbum? = item.takeIf { it.type == MediaType.ALBUM }
    fun asArtist(item: MediaItem): UnifiedArtist? = item.takeIf { it.type == MediaType.ARTIST }
    fun asPlaylist(item: MediaItem): UnifiedPlaylist? = item.takeIf { it.type == MediaType.PLAYLIST }
    fun asVideo(item: MediaItem): UnifiedVideo? = item.takeIf { it.type == MediaType.VIDEO || it.type == MediaType.SHORT }
}
