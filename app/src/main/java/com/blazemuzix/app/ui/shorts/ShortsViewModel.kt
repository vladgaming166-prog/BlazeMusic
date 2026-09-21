package com.blazemuzix.app.ui.shorts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.blazemuzix.app.BlazeApp
import com.blazemuzix.app.data.models.MediaItem
import com.blazemuzix.app.data.models.UiState
import com.blazemuzix.app.network.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ShortsViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = BlazeApp.graph(app)

    private val _state = MutableLiveData<UiState<List<MediaItem>>>(UiState.Loading)
    val state: LiveData<UiState<List<MediaItem>>> get() = _state

    private var items: List<MediaItem> = emptyList()
    private var nextToken: String? = null
    private var exhausted = false
    private var job: Job? = null
    private var loadedOnce = false

    /** Which page is on screen; the fragment decides which item gets the (single) player. */
    val activeIndex = MutableLiveData(0)

    fun loadIfNeeded() {
        if (!loadedOnce) load()
    }

    fun load() {
        job?.cancel()
        items = emptyList()
        nextToken = null
        exhausted = false
        _state.value = UiState.Loading
        job = viewModelScope.launch {
            try {
                val page = graph.discovery.shorts(null)
                loadedOnce = true
                items = page.items
                nextToken = page.nextPageToken
                exhausted = nextToken == null
                _state.value = if (items.isEmpty()) UiState.Empty() else UiState.Success(items)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException.Offline) {
                _state.value = UiState.Offline
            } catch (e: Exception) {
                _state.value = UiState.Error(e)
            }
        }
    }

    /** Fetches the next page when the user is a couple of items from the end. Never preloads video. */
    fun onPageShown(index: Int) {
        activeIndex.value = index
        if (exhausted || job?.isActive == true) return
        if (index < items.size - 3) return
        val token = nextToken ?: return
        job = viewModelScope.launch {
            try {
                val page = graph.discovery.shorts(token)
                val seen = items.map { it.id }.toHashSet()
                items = items + page.items.filter { seen.add(it.id) }
                nextToken = page.nextPageToken
                exhausted = nextToken == null || page.items.isEmpty()
                _state.value = UiState.Success(items)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep what we have; the user can keep scrolling the loaded items.
            }
        }
    }
}
