package com.blazemuzix.app.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.blazemuzix.app.BlazeApp
import com.blazemuzix.app.data.models.MediaItem
import com.blazemuzix.app.data.models.MediaType
import com.blazemuzix.app.data.models.Playback
import com.blazemuzix.app.data.models.Playlist
import com.blazemuzix.app.data.models.Source
import com.blazemuzix.app.data.models.UiState
import com.blazemuzix.app.data.prefs.AppPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class LibraryTab { SONGS, ALBUMS, ARTISTS, PLAYLISTS, FAVORITES, RECENT, FOLDERS, DOWNLOADS, OFFLINE }

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = BlazeApp.graph(app)
    private val _state = MutableLiveData<UiState<List<MediaItem>>>(UiState.Loading)
    val state: LiveData<UiState<List<MediaItem>>> get() = _state

    var tab: LibraryTab = LibraryTab.entries.getOrElse(graph.prefs.lastLibraryTab) { LibraryTab.SONGS }
        private set

    var sort: String = graph.prefs.librarySort
        private set

    /** Current instant filter text; applied on top of the loaded list. */
    var filter: String = ""
        private set

    /** Items for the current tab before filtering; used to show the "no matches" state honestly. */
    var unfilteredCount: Int = 0
        private set

    private var loaded: List<MediaItem> = emptyList()
    private var job: Job? = null

    fun selectTab(newTab: LibraryTab) {
        if (tab == newTab) return
        tab = newTab
        graph.prefs.lastLibraryTab = newTab.ordinal
        load()
    }

    fun setSort(newSort: String) {
        sort = newSort
        graph.prefs.librarySort = newSort
        publish()
    }

    fun setFilter(text: String) {
        filter = text.trim()
        publish()
    }

    /** Re-reads the tab the user is on (prefs may have been changed from Home). */
    fun syncTabFromPrefs(): Boolean {
        val stored = LibraryTab.entries.getOrElse(graph.prefs.lastLibraryTab) { tab }
        if (stored == tab) return false
        tab = stored
        load()
        return true
    }

    fun load(forceRescan: Boolean = false) {
        job?.cancel()
        if (_state.value !is UiState.Success) _state.value = UiState.Loading
        job = viewModelScope.launch {
            try {
                loaded = when (tab) {
                    LibraryTab.SONGS -> graph.localProvider.allSongs(forceRefresh = forceRescan)
                    LibraryTab.ALBUMS -> graph.localProvider.albums()
                    LibraryTab.ARTISTS -> graph.localProvider.artists()
                    LibraryTab.PLAYLISTS -> graph.library.playlists().map { it.toMediaItem() }
                    LibraryTab.FAVORITES -> graph.library.favorites()
                    LibraryTab.RECENT -> graph.library.recentlyPlayed()
                    LibraryTab.FOLDERS -> graph.localProvider.folders()
                    LibraryTab.DOWNLOADS -> graph.localProvider.downloads()
                    LibraryTab.OFFLINE -> graph.localProvider.allSongs()
                }
                publish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UiState.Error(e)
            }
        }
    }

    private fun publish() {
        unfilteredCount = loaded.size
        val filtered = if (filter.isEmpty()) loaded else {
            val q = filter.lowercase()
            loaded.filter {
                it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.album?.lowercase()?.contains(q) == true
            }
        }
        val sorted = if (tab == LibraryTab.RECENT) filtered else applySort(filtered)
        _state.value = if (sorted.isEmpty()) UiState.Empty() else UiState.Success(sorted)
    }

    private fun applySort(items: List<MediaItem>): List<MediaItem> = when (sort) {
        AppPreferences.SORT_RECENTLY_ADDED -> items.sortedWith(
            compareByDescending<MediaItem> { it.dateAdded }.thenByDescending { it.providerId.toLongOrNull() ?: 0L }
        )
        AppPreferences.SORT_RECENTLY_PLAYED -> {
            val order = recentOrder
            items.sortedWith(compareBy<MediaItem> { order[it.id] ?: Int.MAX_VALUE }.thenBy { it.title.lowercase() })
        }
        AppPreferences.SORT_ARTIST -> items.sortedWith(compareBy<MediaItem> { it.artist.lowercase() }.thenBy { it.title.lowercase() })
        AppPreferences.SORT_ALBUM -> items.sortedWith(compareBy<MediaItem> { it.album?.lowercase() ?: "\uffff" }.thenBy { it.title.lowercase() })
        AppPreferences.SORT_DURATION -> items.sortedByDescending { it.durationMs }
        else -> items.sortedBy { it.title.lowercase() }
    }

    /** id -> rank in the recently played list; refreshed lazily when the sort needs it. */
    private var recentOrder: Map<String, Int> = emptyMap()

    fun refreshRecentOrder(onDone: () -> Unit) {
        viewModelScope.launch {
            recentOrder = runCatching { graph.library.recentlyPlayed(500) }.getOrDefault(emptyList())
                .mapIndexed { index, item -> item.id to index }.toMap()
            onDone()
        }
    }

    fun rescan() {
        graph.localProvider.invalidate()
        load(forceRescan = true)
    }

    companion object {
        const val PLAYLIST_PREFIX = "pl:"

        fun Playlist.toMediaItem(): MediaItem = MediaItem(
            id = MediaItem.makeId(Source.LOCAL, MediaType.PLAYLIST, "$PLAYLIST_PREFIX$id"),
            source = Source.LOCAL,
            type = MediaType.PLAYLIST,
            title = name,
            artist = "BlazeMuzix",
            artworkUrl = artworkUrl,
            providerId = "$PLAYLIST_PREFIX$id",
            playback = Playback.DIRECT,
            trackCount = trackCount
        )

        fun playlistIdOf(item: MediaItem): Long? =
            if (item.source == Source.LOCAL && item.type == MediaType.PLAYLIST && item.providerId.startsWith(PLAYLIST_PREFIX)) {
                item.providerId.removePrefix(PLAYLIST_PREFIX).toLongOrNull()
            } else {
                null
            }
    }
}
