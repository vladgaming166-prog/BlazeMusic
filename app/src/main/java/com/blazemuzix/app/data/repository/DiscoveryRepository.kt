package com.blazemuzix.app.data.repository

import com.blazemuzix.app.R
import com.blazemuzix.app.data.models.MediaItem
import com.blazemuzix.app.data.models.MediaType
import com.blazemuzix.app.data.models.Page
import com.blazemuzix.app.data.models.Section
import com.blazemuzix.app.data.models.SectionLayout
import com.blazemuzix.app.data.models.Source
import com.blazemuzix.app.network.ApiException
import com.blazemuzix.app.network.NetworkMonitor
import com.blazemuzix.app.providers.MusicProvider
import com.blazemuzix.app.providers.ProviderRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Aggregates every provider for the Home, Search and Shorts screens. */
class DiscoveryRepository(
    private val providers: ProviderRegistry,
    private val library: LibraryRepository,
    private val network: NetworkMonitor
) {

    val hasOnlineProviders: Boolean get() = providers.configuredOnline.isNotEmpty()

    data class HomeResult(
        val sections: List<Section>,
        /** Errors from individual providers; shown only when nothing loaded. */
        val errors: List<Throwable>,
        val offline: Boolean
    )

    suspend fun home(): HomeResult = coroutineScope {
        val offline = !network.isOnline
        val recent = async { runCatching { library.recentlyPlayed(20) }.getOrDefault(emptyList()) }
        val favorites = async { runCatching { library.favorites() }.getOrDefault(emptyList()) }
        val local = async { runCatching { providers.local.homeSections() }.getOrDefault(emptyList()) }
        val onlineJobs = if (offline) emptyList() else providers.configuredOnline.map { p ->
            async { runCatching { p.homeSections() } }
        }

        val sections = ArrayList<Section>()
        val errors = ArrayList<Throwable>()

        recent.await().takeIf { it.isNotEmpty() }?.let {
            sections.add(Section("recent", R.string.section_recently_played, it, SectionLayout.CARDS))
        }
        val localSections = local.await()
        val onlineSections = ArrayList<Section>()
        for (job in onlineJobs) {
            job.await().fold(onSuccess = { onlineSections.addAll(it) }, onFailure = { errors.add(it) })
        }
        // Order: recently played, recently added, favorites, albums, artists, then any online sections.
        localSections.firstOrNull()?.let { sections.add(it) }
        favorites.await().takeIf { it.isNotEmpty() }?.let {
            sections.add(Section("favorites", R.string.section_favorites, it.take(20), SectionLayout.CARDS))
        }
        sections.addAll(localSections.drop(1))
        sections.addAll(onlineSections)
        HomeResult(sections, errors, offline)
    }

    /**
     * Search state for one provider: results are merged across providers and
     * each keeps its own pagination token so "Load more" works per source.
     */
    data class ProviderCursor(val provider: MusicProvider, var nextToken: String?, var exhausted: Boolean = false, var error: Throwable? = null)

    data class SearchResult(val items: List<MediaItem>, val cursors: List<ProviderCursor>, val errors: List<Throwable>) {
        val hasMore: Boolean get() = cursors.any { !it.exhausted && it.error == null }
    }

    suspend fun search(query: String, type: MediaType?, cursors: List<ProviderCursor>? = null): SearchResult = coroutineScope {
        val active = cursors ?: buildCursors(type)
        val offline = !network.isOnline
        val jobs = active.filter { !it.exhausted && it.error == null }.map { cursor ->
            async {
                if (cursor.provider.requiresNetwork && offline) {
                    cursor.error = ApiException.Offline()
                    return@async emptyList<MediaItem>()
                }
                try {
                    val page: Page<MediaItem> = cursor.provider.search(query, type, cursor.nextToken)
                    cursor.nextToken = page.nextPageToken
                    cursor.exhausted = page.nextPageToken == null
                    page.items
                } catch (e: ApiException) {
                    cursor.error = e
                    cursor.exhausted = true
                    emptyList()
                }
            }
        }
        val lists = ArrayList(jobs.map { it.await() })
        if ((type == null || type == MediaType.PLAYLIST) && cursors == null) {
            lists.add(0, localPlaylistsMatching(query))
        }
        SearchResult(interleave(lists), active, active.mapNotNull { it.error })
    }

    private suspend fun localPlaylistsMatching(query: String): List<MediaItem> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return runCatching { library.playlists() }.getOrDefault(emptyList())
            .filter { it.name.lowercase().contains(q) }
            .map { with(com.blazemuzix.app.ui.library.LibraryViewModel) { it.toMediaItem() } }
    }

    private fun buildCursors(type: MediaType?): List<ProviderCursor> {
        val list = ArrayList<ProviderCursor>()
        if (type == null || type in providers.local.supportedSearchTypes) list.add(ProviderCursor(providers.local, null))
        for (p in providers.configuredOnline) {
            if (type == null || type in p.supportedSearchTypes || (type == MediaType.SONG && MediaType.VIDEO in p.supportedSearchTypes)) {
                list.add(ProviderCursor(p, null))
            }
        }
        return list
    }

    suspend fun shorts(pageToken: String?): Page<MediaItem> {
        if (!network.isOnline) throw ApiException.Offline()
        val provider = providers.youtube
        if (!provider.isConfigured) throw ApiException.NotConfigured(provider.displayName)
        return provider.shorts(pageToken)
    }

    suspend fun collectionItems(item: MediaItem, pageToken: String?): Page<MediaItem> {
        val provider = providers.forSource(item.source) ?: return Page.empty()
        if (provider.requiresNetwork && !network.isOnline) throw ApiException.Offline()
        return provider.collectionItems(item, pageToken)
    }

    fun providerFor(source: Source): MusicProvider? = providers.forSource(source)

    companion object {
        /** Round-robin merge so results from all providers are visible immediately. */
        fun interleave(lists: List<List<MediaItem>>): List<MediaItem> {
            val result = ArrayList<MediaItem>(lists.sumOf { it.size })
            val seen = HashSet<String>()
            var i = 0
            var added = true
            while (added) {
                added = false
                for (list in lists) {
                    if (i < list.size) {
                        val item = list[i]
                        if (seen.add(item.id)) result.add(item)
                        added = true
                    }
                }
                i++
            }
            return result
        }
    }
}
