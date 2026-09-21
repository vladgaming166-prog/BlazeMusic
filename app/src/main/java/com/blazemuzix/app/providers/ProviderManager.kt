package com.blazemuzix.app.providers

import android.content.Context
import com.blazemuzix.app.R
import com.blazemuzix.app.auth.GoogleAuth
import com.blazemuzix.app.auth.SpotifyAuth
import com.blazemuzix.app.data.models.Source

/**
 * UI-facing façade over [ProviderRegistry]. Adds capability sets and human
 * status strings so Settings / Home can adapt without talking to each provider.
 */
class ProviderManager(
    private val context: Context,
    private val registry: ProviderRegistry,
    private val googleAuth: GoogleAuth,
    private val spotifyAuth: SpotifyAuth
) {
    fun statuses(): List<ProviderStatus> = listOf(youtubeStatus(), spotifyStatus(), localStatus())

    fun youtubeStatus(): ProviderStatus {
        val configured = registry.youtube.isConfigured
        return ProviderStatus(
            source = Source.YOUTUBE,
            displayName = context.getString(R.string.provider_youtube),
            configured = configured,
            signedIn = false,
            available = configured && registry.onlineEnabled,
            capabilities = registry.youtube.capabilities,
            detail = when {
                !registry.onlineEnabled -> context.getString(R.string.provider_status_disabled)
                configured -> context.getString(R.string.provider_status_youtube_ready)
                else -> context.getString(R.string.provider_status_youtube_missing)
            }
        )
    }

    fun spotifyStatus(): ProviderStatus {
        val client = spotifyAuth.isClientConfigured
        val signedIn = spotifyAuth.isSignedIn
        val credentials = registry.spotify.isConfigured
        val available = registry.onlineEnabled && (signedIn || credentials)
        return ProviderStatus(
            source = Source.SPOTIFY,
            displayName = context.getString(R.string.provider_spotify),
            configured = client,
            signedIn = signedIn,
            available = available,
            capabilities = registry.spotify.capabilities,
            detail = when {
                !registry.onlineEnabled -> context.getString(R.string.provider_status_disabled)
                signedIn -> context.getString(R.string.provider_status_spotify_signed_in, spotifyAuth.displayName() ?: "")
                credentials -> context.getString(R.string.provider_status_spotify_catalog)
                client -> context.getString(R.string.provider_status_spotify_sign_in)
                else -> context.getString(R.string.provider_status_spotify_missing)
            }
        )
    }

    fun localStatus(): ProviderStatus = ProviderStatus(
        source = Source.LOCAL,
        displayName = context.getString(R.string.provider_local),
        configured = true,
        signedIn = googleAuth.current != null,
        available = true,
        capabilities = registry.local.capabilities,
        detail = if (registry.local.hasPermission()) {
            context.getString(R.string.provider_status_local_ready)
        } else {
            context.getString(R.string.provider_status_local_permission)
        }
    )
}
