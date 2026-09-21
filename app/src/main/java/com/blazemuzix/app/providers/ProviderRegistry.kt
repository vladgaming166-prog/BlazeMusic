package com.blazemuzix.app.providers

import com.blazemuzix.app.data.models.Source

/** Holds every registered [MusicProvider]. Adding a service = adding it here. */
class ProviderRegistry(
    val local: LocalMusicProvider,
    val youtube: YouTubeProvider,
    val spotify: SpotifyProvider,
    val cloud: CloudMusicProvider,
    /** When false the app is a local-only player: online providers are never consulted or shown. */
    val onlineEnabled: Boolean = true
) {
    val all: List<MusicProvider> = if (onlineEnabled) listOf(local, youtube, spotify, cloud) else listOf(local)

    val online: List<MusicProvider> get() = all.filter { it.requiresNetwork }

    val configuredOnline: List<MusicProvider> get() = online.filter { it.isConfigured }

    fun forSource(source: Source): MusicProvider? = all.firstOrNull { it.source == source }
        ?: if (onlineEnabled && source == Source.YOUTUBE_MUSIC) youtube else null
}
