package com.blazemuzix.app.providers

import com.blazemuzix.app.data.models.Source

/**
 * Capabilities a [MusicProvider] may honestly expose. The UI reads these and
 * hides actions the provider does not support (never a dead button).
 */
enum class ProviderCapability {
    SEARCH,
    METADATA,
    PLAYBACK,
    BACKGROUND_PLAYBACK,
    DOWNLOAD,
    OFFLINE,
    PLAYLISTS,
    LIKES,
    ARTISTS,
    ALBUMS,
    SHORTS
}

data class ProviderStatus(
    val source: Source,
    val displayName: String,
    val configured: Boolean,
    val signedIn: Boolean,
    val available: Boolean,
    val capabilities: Set<ProviderCapability>,
    val detail: String
)

data class ProviderError(
    val source: Source,
    val cause: Throwable
)

data class ProviderResult<T>(
    val value: T,
    val errors: List<ProviderError> = emptyList()
)

sealed class ProviderAuthentication {
    object None : ProviderAuthentication()
    object GoogleSignIn : ProviderAuthentication()
    object SpotifyPkce : ProviderAuthentication()
    object ApiKey : ProviderAuthentication()
}
