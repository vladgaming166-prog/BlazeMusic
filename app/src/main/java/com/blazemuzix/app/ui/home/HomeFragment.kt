package com.blazemuzix.app.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.blazemuzix.app.BlazeApp
import com.blazemuzix.app.R
import com.blazemuzix.app.data.models.MediaItem
import com.blazemuzix.app.data.models.Section
import com.blazemuzix.app.data.models.UiState
import com.blazemuzix.app.player.PlayerController
import com.blazemuzix.app.ui.MainActivity
import com.blazemuzix.app.ui.common.ItemActions
import com.blazemuzix.app.ui.common.ItemActionsSheet
import com.blazemuzix.app.ui.common.StateView
import com.blazemuzix.app.ui.settings.SettingsActivity
import com.blazemuzix.app.utils.visible
import java.util.Calendar

class HomeFragment : Fragment(R.layout.fragment_home), SectionAdapter.Listener {

    private val viewModel: HomeViewModel by viewModels()
    private lateinit var adapter: SectionAdapter
    private lateinit var state: StateView
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var list: RecyclerView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()
        view.findViewById<TextView>(R.id.home_greeting).text = greeting()
        view.findViewById<View>(R.id.home_settings).setOnClickListener {
            startActivity(Intent(context, SettingsActivity::class.java))
        }
        list = view.findViewById(R.id.home_list)
        refresh = view.findViewById(R.id.home_refresh)
        state = StateView(view.findViewById(R.id.home_state))

        adapter = SectionAdapter(this)
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
        list.setItemViewCacheSize(3)
        refresh.setColorSchemeResources(R.color.bm_primary)
        refresh.setOnRefreshListener { viewModel.load(userRefresh = true) }

        viewModel.state.observe(viewLifecycleOwner) { render(it) }
        viewModel.refreshing.observe(viewLifecycleOwner) { refresh.isRefreshing = it == true }
        PlayerController.state.observe(viewLifecycleOwner) { adapter.nowPlayingId = it.current?.id }
        BlazeApp.graph(context).library.changes.observe(viewLifecycleOwner) { if (it > 0 && isResumed) viewModel.load(userRefresh = false) }
        BlazeApp.graph(context).network.online.observe(viewLifecycleOwner) { online ->
            if (online && viewModel.state.value is UiState.Offline) viewModel.load()
        }
        viewModel.loadIfNeeded()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) viewModel.loadIfNeeded()
    }

    private fun render(uiState: UiState<List<Section>>) {
        when (uiState) {
            is UiState.Loading -> {
                list.visible(false)
                state.loading()
            }
            is UiState.Success -> {
                list.visible(true)
                state.hide()
                adapter.submitList(uiState.data)
            }
            is UiState.Empty -> {
                list.visible(false)
                state.empty(
                    uiState.title ?: getString(R.string.home_empty_title),
                    uiState.message ?: getString(R.string.home_empty_message),
                    R.drawable.ic_music_note,
                    getString(R.string.nav_library)
                ) { (activity as? MainActivity)?.selectTab(R.id.nav_library) }
            }
            is UiState.Error -> {
                list.visible(false)
                state.error(uiState.error) { viewModel.load() }
            }
            is UiState.Offline -> {
                list.visible(false)
                state.offline({ viewModel.load() })
            }
        }
    }

    private fun greeting(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return getString(
            when {
                hour < 12 -> R.string.home_greeting_morning
                hour < 18 -> R.string.home_greeting_afternoon
                else -> R.string.home_greeting_evening
            }
        )
    }

    override fun onItemClick(section: Section, item: MediaItem) {
        ItemActions.primary(requireActivity(), item, section.items)
    }

    override fun onItemMore(section: Section, item: MediaItem) {
        ItemActionsSheet.show(parentFragmentManager, item, section.items)
    }
}
