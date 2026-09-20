package com.blazemuzix.app.providers

import com.blazemuzix.app.data.models.Source

/** Holds every registered [MusicProvider]. Adding a service = adding it here. */
class ProviderRegistry(
    val local: LocalMusicProvider,
    val youtube: YouTubeProvider,
    val spotify: SpotifyProvider
) {
    val all: List<MusicProvider> = listOf(local, youtube, spotify)

    val online: List<MusicProvider> get() = all.filter { it.requiresNetwork }

    val configuredOnline: List<MusicProvider> get() = online.filter { it.isConfigured }

    fun forSource(source: Source): MusicProvider? = all.firstOrNull { it.source == source }
        ?: if (source == Source.YOUTUBE_MUSIC) youtube else null
}
