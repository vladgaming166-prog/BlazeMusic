package com.blazemuzix.app.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.blazemuzix.app.BlazeApp
import com.blazemuzix.app.data.models.MediaItem
import com.blazemuzix.app.data.models.SearchFilter
import com.blazemuzix.app.data.repository.DiscoveryRepository
import com.blazemuzix.app.network.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = BlazeApp.graph(app)

    data class Results(
        val query: String,
        val items: List<MediaItem>,
        val hasMore: Boolean,
        val loadingMore: Boolean,
        val errors: List<Throwable>
    )

    sealed class State {
        object Idle : State()
        data class Loading(val query: String) : State()
        data class Success(val results: Results) : State()
        data class Empty(val query: String, val errors: List<Throwable>) : State()
        data class Error(val query: String, val error: Throwable) : State()
    }

    private val _state = MutableLiveData<State>(State.Idle)
    val state: LiveData<State> get() = _state

    private val _history = MutableLiveData<List<String>>(emptyList())
    val history: LiveData<List<String>> get() = _history

    var filter: SearchFilter = SearchFilter.ALL
        private set
    var query: String = ""
        private set

    private var searchJob: Job? = null
    private var cursors: List<DiscoveryRepository.ProviderCursor>? = null
    private var accumulated: List<MediaItem> = emptyList()

    init {
        refreshHistory()
    }

    fun refreshHistory(prefix: String? = null) {
        viewModelScope.launch { _history.value = graph.library.searchHistory(prefix) }
    }

    fun setFilter(newFilter: SearchFilter) {
        if (filter == newFilter) return
        filter = newFilter
        if (query.isNotBlank()) search(query, immediate = true, record = false)
    }

    fun onQueryChanged(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            clear()
            refreshHistory()
            return
        }
        refreshHistory(trimmed)
        search(trimmed, immediate = false, record = false)
    }

    fun submit(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        search(trimmed, immediate = true, record = true)
    }

    fun clear() {
        searchJob?.cancel()
        query = ""
        cursors = null
        accumulated = emptyList()
        _state.value = State.Idle
    }

    private fun search(text: String, immediate: Boolean, record: Boolean) {
        searchJob?.cancel()
        query = text
        searchJob = viewModelScope.launch {
            if (!immediate) delay(DEBOUNCE_MS)
            _state.value = State.Loading(text)
            if (record) graph.library.recordSearch(text).also { refreshHistory() }
            try {
                val result = graph.discovery.search(text, filter.type, null)
                cursors = result.cursors
                accumulated = result.items
                publish(text, result.errors, loadingMore = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = State.Error(text, e)
            }
        }
    }

    fun loadMore() {
        val current = _state.value as? State.Success ?: return
        if (!current.results.hasMore || current.results.loadingMore) return
        val text = query
        val activeCursors = cursors ?: return
        _state.value = State.Success(current.results.copy(loadingMore = true))
        searchJob = viewModelScope.launch {
            try {
                val result = graph.discovery.search(text, filter.type, activeCursors)
                val seen = accumulated.map { it.id }.toHashSet()
                accumulated = accumulated + result.items.filter { seen.add(it.id) }
                publish(text, result.errors, loadingMore = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                publish(text, listOf(e), loadingMore = false)
            }
        }
    }

    private fun publish(text: String, errors: List<Throwable>, loadingMore: Boolean) {
        val hasMore = cursors?.any { !it.exhausted && it.error == null } == true
        _state.value = if (accumulated.isEmpty()) {
            val fatal = errors.firstOrNull { it !is ApiException.NotConfigured }
            if (fatal != null && errors.size == (cursors?.size ?: 0)) State.Error(text, fatal) else State.Empty(text, errors)
        } else {
            State.Success(Results(text, accumulated, hasMore, loadingMore, errors))
        }
    }

    fun removeHistory(entry: String) {
        viewModelScope.launch {
            graph.library.removeSearch(entry)
            refreshHistory(query.ifBlank { null })
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            graph.library.clearSearchHistory()
            refreshHistory()
        }
    }

    companion object {
        private const val DEBOUNCE_MS = 450L
    }
}
