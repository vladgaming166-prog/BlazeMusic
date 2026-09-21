package com.blazemuzix.app.ui.search

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.blazemuzix.app.R
import com.blazemuzix.app.data.models.MediaItem
import com.blazemuzix.app.data.models.SearchFilter
import com.blazemuzix.app.data.models.SectionLayout
import com.blazemuzix.app.network.ApiException
import com.blazemuzix.app.player.PlayerController
import com.blazemuzix.app.ui.common.ItemActions
import com.blazemuzix.app.ui.common.ItemActionsSheet
import com.blazemuzix.app.ui.common.ListEntry
import com.blazemuzix.app.ui.common.MediaAdapter
import com.blazemuzix.app.ui.common.StateView
import com.blazemuzix.app.utils.toErrorCopy
import com.blazemuzix.app.utils.toast
import com.blazemuzix.app.utils.visible
import com.google.android.material.chip.ChipGroup

class SearchFragment : Fragment(R.layout.fragment_search), MediaAdapter.Listener {

    private val viewModel: SearchViewModel by viewModels()
    private lateinit var input: EditText
    private lateinit var clear: ImageButton
    private lateinit var results: RecyclerView
    private lateinit var historyContainer: View
    private lateinit var historyList: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var state: StateView
    private lateinit var adapter: MediaAdapter
    private lateinit var historyAdapter: HistoryAdapter
    private var suppressWatcher = false
    private var announcedErrors: Set<String> = emptySet()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        input = view.findViewById(R.id.search_input)
        clear = view.findViewById(R.id.search_clear)
        results = view.findViewById(R.id.search_results)
        historyContainer = view.findViewById(R.id.search_history_container)
        historyList = view.findViewById(R.id.search_history)
        progress = view.findViewById(R.id.search_progress)
        state = StateView(view.findViewById(R.id.search_state))

        adapter = MediaAdapter(SectionLayout.ROWS, this)
        results.layoutManager = LinearLayoutManager(requireContext())
        results.adapter = adapter
        results.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0) hideKeyboard()
            }
        })

        historyAdapter = HistoryAdapter(
            onClick = { text ->
                suppressWatcher = true
                input.setText(text)
                input.setSelection(text.length)
                suppressWatcher = false
                viewModel.submit(text)
                hideKeyboard()
            },
            onRemove = { viewModel.removeHistory(it) }
        )
        historyList.layoutManager = LinearLayoutManager(requireContext())
        historyList.adapter = historyAdapter
        view.findViewById<View>(R.id.search_clear_history).setOnClickListener {
            viewModel.clearHistory()
            requireContext().toast(R.string.search_history_cleared)
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressWatcher) return
                val text = s?.toString().orEmpty()
                clear.visible(text.isNotEmpty())
                viewModel.onQueryChanged(text)
            }
        })
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                viewModel.submit(input.text.toString())
                hideKeyboard()
                true
            } else {
                false
            }
        }
        clear.setOnClickListener {
            input.setText("")
            input.requestFocus()
        }

        val chips = view.findViewById<ChipGroup>(R.id.search_filters)
        chips.setOnCheckedStateChangeListener { _, checkedIds ->
            val filter = when (checkedIds.firstOrNull()) {
                R.id.filter_songs -> SearchFilter.SONGS
                R.id.filter_albums -> SearchFilter.ALBUMS
                R.id.filter_artists -> SearchFilter.ARTISTS
                R.id.filter_playlists -> SearchFilter.PLAYLISTS
                R.id.filter_videos -> SearchFilter.VIDEOS
                R.id.filter_shorts -> SearchFilter.SHORTS
                R.id.filter_local -> SearchFilter.LOCAL
                R.id.filter_youtube -> SearchFilter.YOUTUBE
                R.id.filter_spotify -> SearchFilter.SPOTIFY
                else -> SearchFilter.ALL
            }
            viewModel.setFilter(filter)
        }

        viewModel.state.observe(viewLifecycleOwner) { render(it) }
        viewModel.history.observe(viewLifecycleOwner) { history ->
            historyAdapter.submitList(history)
            val showHistory = viewModel.state.value is SearchViewModel.State.Idle
            historyContainer.visible(showHistory && history.isNotEmpty())
            if (showHistory && history.isEmpty()) showIdleState()
        }
        PlayerController.state.observe(viewLifecycleOwner) { adapter.nowPlayingId = it.current?.id }
    }

    private fun render(s: SearchViewModel.State) {
        val hasHistory = !viewModel.history.value.isNullOrEmpty()
        progress.visible(false)
        when (s) {
            is SearchViewModel.State.Idle -> {
                results.visible(false)
                adapter.submitList(emptyList())
                historyContainer.visible(hasHistory)
                if (hasHistory) state.hide() else showIdleState()
            }
            is SearchViewModel.State.Loading -> {
                historyContainer.visible(false)
                state.hide()
                if (adapter.itemCount == 0) {
                    results.visible(false)
                    state.loading(null)
                } else {
                    progress.visible(true)
                }
            }
            is SearchViewModel.State.Success -> {
                historyContainer.visible(false)
                state.hide()
                results.visible(true)
                val entries = ArrayList<ListEntry>(s.results.items.size + 2)
                entries.add(ListEntry.Header(getString(R.string.search_results_count, s.results.items.size)))
                s.results.items.forEach { entries.add(ListEntry.Media(it)) }
                entries.add(ListEntry.LoadMore(loading = s.results.loadingMore, exhausted = !s.results.hasMore))
                adapter.submitList(entries)
                announceErrors(s.results.errors)
            }
            is SearchViewModel.State.Empty -> {
                historyContainer.visible(false)
                results.visible(false)
                adapter.submitList(emptyList())
                val relevant = s.errors.firstOrNull { it !is ApiException.NotConfigured }
                if (relevant != null) {
                    val copy = relevant.toErrorCopy(requireContext())
                    state.show(copy.iconRes, getString(R.string.search_no_results_title, s.query), copy.message, getString(R.string.action_retry)) {
                        viewModel.submit(s.query)
                    }
                } else {
                    state.empty(getString(R.string.search_no_results_title, s.query), getString(R.string.search_no_results_message), R.drawable.ic_search_off)
                }
            }
            is SearchViewModel.State.Error -> {
                historyContainer.visible(false)
                results.visible(false)
                adapter.submitList(emptyList())
                state.error(s.error) { viewModel.submit(s.query) }
            }
        }
    }

    private fun showIdleState() {
        state.empty(getString(R.string.search_empty_title), getString(R.string.search_empty_message), R.drawable.ic_search)
    }

    /** Partial provider failures are surfaced once as a toast; the results from other providers stay visible. */
    private fun announceErrors(errors: List<Throwable>) {
        val messages = errors.filter { it !is ApiException.NotConfigured }.map { it.toErrorCopy(requireContext()).title }.toSet()
        val fresh = messages - announcedErrors
        if (fresh.isNotEmpty()) requireContext().toast(fresh.first())
        announcedErrors = messages
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
    }

    override fun onItemClick(item: MediaItem, position: Int) {
        hideKeyboard()
        ItemActions.primary(requireActivity(), item, adapter.mediaItems().filter { it.canPlayDirect || it.id == item.id })
    }

    override fun onItemMore(item: MediaItem) {
        ItemActionsSheet.show(parentFragmentManager, item, adapter.mediaItems())
    }

    override fun onLoadMore() = viewModel.loadMore()

    // ------------------------------------------------------------ history

    private class HistoryAdapter(
        private val onClick: (String) -> Unit,
        private val onRemove: (String) -> Unit
    ) : ListAdapter<String, HistoryAdapter.Holder>(object : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(oldItem: String, newItem: String) = oldItem == newItem
        override fun areContentsTheSame(oldItem: String, newItem: String) = oldItem == newItem
    }) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_search_history, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val text = getItem(position)
            holder.text.text = text
            holder.itemView.setOnClickListener { onClick(text) }
            holder.remove.setOnClickListener { onRemove(text) }
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val text: TextView = view.findViewById(R.id.history_text)
            val remove: ImageButton = view.findViewById(R.id.history_remove)
        }
    }
}
