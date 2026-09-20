package com.blazemuzix.app.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.blazemuzix.app.BlazeApp
import com.blazemuzix.app.data.models.Section
import com.blazemuzix.app.data.models.UiState
import com.blazemuzix.app.network.ApiException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = BlazeApp.graph(app)
    private val _state = MutableLiveData<UiState<List<Section>>>(UiState.Loading)
    val state: LiveData<UiState<List<Section>>> get() = _state
    val refreshing = MutableLiveData(false)
    private var job: Job? = null
    private var loadedOnce = false

    fun loadIfNeeded() {
        if (!loadedOnce) load()
    }

    fun load(userRefresh: Boolean = false) {
        job?.cancel()
        if (userRefresh) refreshing.value = true else if (!loadedOnce) _state.value = UiState.Loading
        job = viewModelScope.launch {
            try {
                val result = graph.discovery.home()
                loadedOnce = true
                _state.value = when {
                    result.sections.isNotEmpty() -> UiState.Success(result.sections)
                    result.offline -> UiState.Offline
                    result.errors.isNotEmpty() && result.errors.all { it is ApiException.NotConfigured } -> UiState.Empty(
                        graph.discovery.let { getApplication<Application>().getString(com.blazemuzix.app.R.string.home_no_providers_title) },
                        getApplication<Application>().getString(com.blazemuzix.app.R.string.home_no_providers_message)
                    )
                    result.errors.isNotEmpty() -> UiState.Error(result.errors.first())
                    !graph.discovery.hasOnlineProviders -> UiState.Empty(
                        getApplication<Application>().getString(com.blazemuzix.app.R.string.home_no_providers_title),
                        getApplication<Application>().getString(com.blazemuzix.app.R.string.home_no_providers_message)
                    )
                    else -> UiState.Empty()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.value = UiState.Error(e)
            } finally {
                refreshing.value = false
            }
        }
    }
}
