package com.blazemuzix.app.providers

import com.blazemuzix.app.R
import com.blazemuzix.app.cloud.CloudConfig
import com.blazemuzix.app.cloud.CloudRepository
import com.blazemuzix.app.data.models.MediaItem
import com.blazemuzix.app.data.models.MediaType
import com.blazemuzix.app.data.models.Page
import com.blazemuzix.app.data.models.Section
import com.blazemuzix.app.data.models.SectionLayout
import com.blazemuzix.app.data.models.Source
import com.blazemuzix.app.network.ApiException

/** User-owned catalog on BlazeMuzix Cloud. Direct HTTPS playback of uploaded audio. */
class CloudMusicProvider(
    private val cloud: CloudRepository
) : MusicProvider {
    override val source = Source.CLOUD
    override val displayName = "BlazeMuzix Cloud"
    override val isConfigured: Boolean get() = CloudConfig.isConfigured
    override val requiresNetwork = true
    override val supportedSearchTypes = setOf(
        MediaType.SONG, MediaType.ARTIST, MediaType.PLAYLIST, MediaType.USER, MediaType.SHORT, MediaType.ALBUM
    )
    override val capabilities = setOf(
        ProviderCapability.SEARCH,
        ProviderCapability.METADATA,
        ProviderCapability.PLAYBACK,
        ProviderCapability.BACKGROUND_PLAYBACK,
        ProviderCapability.OFFLINE,
        ProviderCapability.PLAYLISTS,
        ProviderCapability.LIKES,
        ProviderCapability.ARTISTS,
        ProviderCapability.ALBUMS,
        ProviderCapability.SHORTS,
        ProviderCapability.DOWNLOAD
    )

    override fun supportsOfflineDownload(item: MediaItem): Boolean =
        item.source == Source.CLOUD && item.type == MediaType.SONG && cloud.signedIn && item.providerId.isNotEmpty()

    override suspend fun search(query: String, type: MediaType?, pageToken: String?): Page<MediaItem> {
        if (!isConfigured) throw ApiException.NotConfigured(displayName)
        if (query.isBlank()) return Page.empty()
        if (pageToken != null) return Page.empty()
        val items = ArrayList<MediaItem>()
        when (type) {
            MediaType.USER -> items.addAll(cloud.searchUsers(query).map { it.toMediaItem() })
            MediaType.PLAYLIST -> items.addAll(cloud.searchPlaylists(query).map { it.toMediaItem() })
            MediaType.ARTIST -> items.addAll(cloud.searchTracks(query).distinctBy { it.artist.lowercase() }.map {
                it.toMediaItem().copy(
                    id = MediaItem.makeId(Source.CLOUD, MediaType.ARTIST, it.artist.hashCode().toString()),
                    type = MediaType.ARTIST,
                    title = it.artist,
                    playback = com.blazemuzix.app.data.models.Playback.NONE,
                    playbackUri = null
                )
            })
            MediaType.ALBUM -> items.addAll(cloud.searchTracks(query).filter { !it.album.isNullOrBlank() }.distinctBy { it.album }.map {
                it.toMediaItem().copy(
                    id = MediaItem.makeId(Source.CLOUD, MediaType.ALBUM, (it.album + it.artist).hashCode().toString()),
                    type = MediaType.ALBUM,
                    title = it.album ?: it.title,
                    playback = com.blazemuzix.app.data.models.Playback.NONE,
                    playbackUri = null
                )
            })
            MediaType.SHORT -> items.addAll(cloud.userShorts().filter {
                it.title.contains(query, true) || it.artist.contains(query, true)
            })
            else -> {
                items.addAll(cloud.searchTracks(query).map { it.toMediaItem() })
                if (type == null) {
                    items.addAll(cloud.searchPlaylists(query).map { it.toMediaItem() })
                    items.addAll(cloud.searchUsers(query).map { it.toMediaItem() })
                }
            }
        }
        return Page(items)
    }

    override suspend fun homeSections(): List<Section> {
        if (!isConfigured) return emptyList()
        val sections = ArrayList<Section>()
        cloud.recentTracks(16).map { it.toMediaItem() }.takeIf { it.isNotEmpty() }?.let {
            sections.add(Section("cloud_new", R.string.section_cloud_new, it, SectionLayout.CARDS, Source.CLOUD))
        }
        cloud.trendingTracks(16).map { it.toMediaItem() }.takeIf { it.isNotEmpty() }?.let {
            sections.add(Section("cloud_trending", R.string.section_cloud_trending, it, SectionLayout.CARDS, Source.CLOUD))
        }
        cloud.recommended(12).map { it.toMediaItem() }.takeIf { it.isNotEmpty() }?.let {
            sections.add(Section("cloud_recommended", R.string.section_cloud_recommended, it, SectionLayout.CARDS, Source.CLOUD))
        }
        cloud.newArtists(12).map {
            it.toMediaItem().copy(
                id = MediaItem.makeId(Source.CLOUD, MediaType.ARTIST, it.artist.hashCode().toString()),
                type = MediaType.ARTIST,
                title = it.artist,
                playback = com.blazemuzix.app.data.models.Playback.NONE,
                playbackUri = null
            )
        }.takeIf { it.isNotEmpty() }?.let {
            sections.add(Section("cloud_artists", R.string.section_cloud_artists, it, SectionLayout.CARDS, Source.CLOUD))
        }
        cloud.playlistsPublic(12).map { it.toMediaItem() }.takeIf { it.isNotEmpty() }?.let {
            sections.add(Section("cloud_playlists", R.string.section_cloud_playlists, it, SectionLayout.CARDS, Source.CLOUD))
        }
        return sections
    }

    override suspend fun shorts(pageToken: String?): Page<MediaItem> {
        if (!isConfigured) return Page.empty()
        if (pageToken != null) return Page.empty()
        return Page(cloud.userShorts())
    }

    override suspend fun collectionItems(item: MediaItem, pageToken: String?): Page<MediaItem> {
        if (!isConfigured) throw ApiException.NotConfigured(displayName)
        if (pageToken != null) return Page.empty()
        return when (item.type) {
            MediaType.PLAYLIST -> Page(cloud.playlistTracks(item.providerId).map { it.toMediaItem() })
            MediaType.USER -> Page(cloud.tracksByOwner(item.providerId).map { it.toMediaItem() })
            MediaType.ARTIST -> {
                val q = item.title
                Page(cloud.searchTracks(q).filter { it.artist.equals(q, true) }.map { it.toMediaItem() })
            }
            MediaType.ALBUM -> {
                val q = item.title
                Page(cloud.searchTracks(q).filter { it.album.equals(q, true) }.map { it.toMediaItem() })
            }
            else -> Page.empty()
        }
    }
}
