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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class LibraryTab { LOCAL, FAVORITES, RECENT, PLAYLISTS, SAVED, DOWNLOADS }

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = BlazeApp.graph(app)
    private val _state = MutableLiveData<UiState<List<MediaItem>>>(UiState.Loading)
    val state: LiveData<UiState<List<MediaItem>>> get() = _state

    var tab: LibraryTab = LibraryTab.entries.getOrElse(graph.prefs.lastLibraryTab) { LibraryTab.LOCAL }
        private set

    private var job: Job? = null

    /** Set by the fragment when the user denied storage permission. */
    val permissionDenied = MutableLiveData(false)

    fun selectTab(newTab: LibraryTab) {
        tab = newTab
        graph.prefs.lastLibraryTab = newTab.ordinal
        load()
    }

    fun load(forceRescan: Boolean = false) {
        job?.cancel()
        if (_state.value !is UiState.Success) _state.value = UiState.Loading
        job = viewModelScope.launch {
            try {
                val items: List<MediaItem> = when (tab) {
                    LibraryTab.LOCAL -> graph.localProvider.allSongs(forceRefresh = forceRescan)
                    LibraryTab.FAVORITES -> graph.library.favorites()
                    LibraryTab.RECENT -> graph.library.recentlyPlayed()
                    LibraryTab.PLAYLISTS -> graph.library.playlists().map { it.toMediaItem() }
                    LibraryTab.SAVED -> graph.library.saved()
                    LibraryTab.DOWNLOADS -> graph.localProvider.downloads()
                }
                _state.value = if (items.isEmpty()) UiState.Empty() else UiState.Success(items)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UiState.Error(e)
            }
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
