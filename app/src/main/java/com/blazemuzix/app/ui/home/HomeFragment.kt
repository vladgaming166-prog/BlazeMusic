package com.blazemuzix.app.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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
import com.blazemuzix.app.ui.common.Navigator
import com.blazemuzix.app.ui.library.LibraryTab
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
    private lateinit var quick: View

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.rescan() else viewModel.load()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()
        quick = view.findViewById(R.id.home_quick)
        view.findViewById<View>(R.id.quick_shuffle).setOnClickListener { shuffleAll() }
        view.findViewById<View>(R.id.quick_songs).setOnClickListener { openLibrary(LibraryTab.SONGS) }
        view.findViewById<View>(R.id.quick_albums).setOnClickListener { openLibrary(LibraryTab.ALBUMS) }
        view.findViewById<View>(R.id.quick_artists).setOnClickListener { openLibrary(LibraryTab.ARTISTS) }
        view.findViewById<TextView>(R.id.home_greeting).text = greeting()
        view.findViewById<View>(R.id.home_settings).setOnClickListener {
            startActivity(Intent(context, SettingsActivity::class.java))
        }
        view.findViewById<View>(R.id.home_account).setOnClickListener {
            startActivity(Intent(context, com.blazemuzix.app.auth.AccountActivity::class.java))
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
                quick.visible(false)
                state.loading()
            }
            is UiState.Success -> {
                list.visible(true)
                quick.visible(true)
                state.hide()
                adapter.submitList(uiState.data)
            }
            is UiState.Empty -> {
                list.visible(false)
                quick.visible(false)
                state.empty(
                    uiState.title ?: getString(R.string.home_no_music_title),
                    uiState.message ?: getString(R.string.home_no_music_message),
                    R.drawable.ic_music_note,
                    getString(R.string.action_scan_device)
                ) { scanDevice() }
            }
            is UiState.Error -> {
                list.visible(false)
                quick.visible(false)
                state.error(uiState.error) { viewModel.load() }
            }
            is UiState.Offline -> {
                list.visible(false)
                quick.visible(false)
                state.offline({ viewModel.load() })
            }
        }
    }

    private fun scanDevice() {
        val local = BlazeApp.graph(requireContext()).localProvider
        if (local.hasPermission()) viewModel.rescan() else permissionLauncher.launch(local.requiredPermission)
    }

    private fun openLibrary(tab: LibraryTab) {
        BlazeApp.graph(requireContext()).prefs.lastLibraryTab = tab.ordinal
        (activity as? MainActivity)?.selectTab(R.id.nav_library)
    }

    private fun shuffleAll() {
        val context = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val songs = BlazeApp.graph(context).localProvider.allSongs()
            if (songs.isEmpty()) return@launch
            PlayerController.playQueue(context, songs, songs.indices.random(), shuffle = true)
            (activity as? Navigator)?.openPlayer()
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
