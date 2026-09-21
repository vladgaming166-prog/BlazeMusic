package com.blazemuzix.app.providers

import com.blazemuzix.app.data.models.MediaItem
import com.blazemuzix.app.data.models.MediaType
import com.blazemuzix.app.data.models.Page
import com.blazemuzix.app.data.models.Section
import com.blazemuzix.app.data.models.Source

/**
 * A replaceable music/content source. New services can be added by implementing
 * this interface and registering it in [ProviderRegistry]; no UI changes needed.
 *
 * Implementations MUST only use official, documented APIs and must never bypass
 * authentication, DRM, ads, geo or download restrictions of the service.
 */
interface MusicProvider {
    val source: Source
    val displayName: String

    /** False when credentials are missing; the UI then shows an honest status. */
    val isConfigured: Boolean

    /** True when the provider needs internet access. */
    val requiresNetwork: Boolean get() = true

    /** Which result types [search] can return. */
    val supportedSearchTypes: Set<MediaType>

    suspend fun search(query: String, type: MediaType?, pageToken: String?): Page<MediaItem>

    /** Home screen sections; each provider decides what it can legitimately offer. */
    suspend fun homeSections(): List<Section>

    /** Short-form music content, when the provider offers it. */
    suspend fun shorts(pageToken: String?): Page<MediaItem> = Page.empty()

    /** Tracks of an album/playlist, or top items of an artist. */
    suspend fun collectionItems(item: MediaItem, pageToken: String?): Page<MediaItem> = Page.empty()

    /** Whether offline download of this item is permitted by the provider. */
    fun supportsOfflineDownload(item: MediaItem): Boolean = false
}
