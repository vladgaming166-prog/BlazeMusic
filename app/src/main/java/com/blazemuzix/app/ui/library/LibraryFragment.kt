package com.blazemuzix.app.ui.library

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blazemuzix.app.BlazeApp
import com.blazemuzix.app.R
import com.blazemuzix.app.data.models.MediaItem
import com.blazemuzix.app.data.models.SectionLayout
import com.blazemuzix.app.data.models.UiState
import com.blazemuzix.app.player.PlayerController
import com.blazemuzix.app.ui.common.ItemActions
import com.blazemuzix.app.ui.common.ItemActionsSheet
import com.blazemuzix.app.ui.common.MediaAdapter
import com.blazemuzix.app.ui.common.Navigator
import com.blazemuzix.app.ui.common.StateView
import com.blazemuzix.app.utils.toast
import com.blazemuzix.app.utils.visible
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

class LibraryFragment : Fragment(R.layout.fragment_library), MediaAdapter.Listener {

    private val viewModel: LibraryViewModel by viewModels()
    private lateinit var adapter: MediaAdapter
    private lateinit var list: RecyclerView
    private lateinit var state: StateView
    private lateinit var count: TextView
    private lateinit var toolbar: View
    private lateinit var notice: View
    private lateinit var playAll: MaterialButton
    private lateinit var shuffle: MaterialButton
    private lateinit var addButton: View
    private lateinit var rescanButton: View
    private var permanentlyDenied = false

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            viewModel.rescan()
        } else {
            permanentlyDenied = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !shouldShowRequestPermissionRationale(BlazeApp.graph(requireContext()).localProvider.requiredPermission)
            viewModel.load()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()
        list = view.findViewById(R.id.library_list)
        state = StateView(view.findViewById(R.id.library_state))
        count = view.findViewById(R.id.library_count)
        toolbar = view.findViewById(R.id.library_toolbar)
        notice = view.findViewById(R.id.library_notice)
        playAll = view.findViewById(R.id.library_play_all)
        shuffle = view.findViewById(R.id.library_shuffle)
        addButton = view.findViewById(R.id.library_add)
        rescanButton = view.findViewById(R.id.library_rescan)

        adapter = MediaAdapter(SectionLayout.ROWS, this).apply { showStorageTags = true }
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter

        val chips = view.findViewById<ChipGroup>(R.id.library_tabs)
        chips.check(chipIdFor(viewModel.tab))
        chips.setOnCheckedStateChangeListener { _, checkedIds ->
            val tab = when (checkedIds.firstOrNull()) {
                R.id.tab_favorites -> LibraryTab.FAVORITES
                R.id.tab_recent -> LibraryTab.RECENT
                R.id.tab_playlists -> LibraryTab.PLAYLISTS
                R.id.tab_saved -> LibraryTab.SAVED
                R.id.tab_downloads -> LibraryTab.DOWNLOADS
                else -> LibraryTab.LOCAL
            }
            viewModel.selectTab(tab)
        }

        playAll.setOnClickListener { playAll(shuffle = false) }
        shuffle.setOnClickListener { playAll(shuffle = true) }
        addButton.setOnClickListener { ItemActions.newPlaylistDialog(requireActivity()) { viewModel.load() } }
        rescanButton.setOnClickListener { requestPermissionOrScan() }

        viewModel.state.observe(viewLifecycleOwner) { render(it) }
        BlazeApp.graph(context).library.changes.observe(viewLifecycleOwner) { if (it > 0) viewModel.load() }
        PlayerController.state.observe(viewLifecycleOwner) { adapter.nowPlayingId = it.current?.id }
        viewModel.load()
    }

    private fun chipIdFor(tab: LibraryTab) = when (tab) {
        LibraryTab.LOCAL -> R.id.tab_local
        LibraryTab.FAVORITES -> R.id.tab_favorites
        LibraryTab.RECENT -> R.id.tab_recent
        LibraryTab.PLAYLISTS -> R.id.tab_playlists
        LibraryTab.SAVED -> R.id.tab_saved
        LibraryTab.DOWNLOADS -> R.id.tab_downloads
    }

    private fun render(uiState: UiState<List<MediaItem>>) {
        val tab = viewModel.tab
        val local = BlazeApp.graph(requireContext()).localProvider
        val isLocalTab = tab == LibraryTab.LOCAL || tab == LibraryTab.DOWNLOADS
        addButton.visible(tab == LibraryTab.PLAYLISTS)
        rescanButton.visible(isLocalTab)
        notice.visible(tab == LibraryTab.DOWNLOADS)

        if (isLocalTab && !local.hasPermission()) {
            list.visible(false)
            toolbar.visible(false)
            adapter.submitList(emptyList())
            state.show(
                R.drawable.ic_lock,
                getString(R.string.library_permission_title),
                getString(if (permanentlyDenied) R.string.local_permission_denied_forever else R.string.library_permission_message),
                getString(if (permanentlyDenied) R.string.action_open_settings else R.string.action_grant_permission)
            ) { if (permanentlyDenied) openAppSettings() else requestPermissionOrScan() }
            return
        }

        when (uiState) {
            is UiState.Loading -> {
                toolbar.visible(false)
                list.visible(false)
                state.loading(if (isLocalTab) getString(R.string.scanning_local) else null)
            }
            is UiState.Success -> {
                state.hide()
                list.visible(true)
                val items = uiState.data
                adapter.submitItems(items)
                val playable = items.count { it.canPlayDirect && !it.type.isCollection }
                toolbar.visible(true)
                count.text = if (tab == LibraryTab.PLAYLISTS) resources.getString(R.string.items_count, items.size) else resources.getString(R.string.playlist_track_count, items.size)
                playAll.visible(playable > 0)
                shuffle.visible(playable > 1)
            }
            is UiState.Empty -> {
                toolbar.visible(false)
                list.visible(false)
                adapter.submitList(emptyList())
                val (title, message, icon) = when (tab) {
                    LibraryTab.LOCAL -> Triple(R.string.library_local_empty_title, R.string.library_local_empty_message, R.drawable.ic_phone)
                    LibraryTab.FAVORITES -> Triple(R.string.library_favorites_empty_title, R.string.library_favorites_empty_message, R.drawable.ic_favorite_border)
                    LibraryTab.RECENT -> Triple(R.string.library_recent_empty_title, R.string.library_recent_empty_message, R.drawable.ic_history)
                    LibraryTab.PLAYLISTS -> Triple(R.string.library_playlists_empty_title, R.string.library_playlists_empty_message, R.drawable.ic_playlist_play)
                    LibraryTab.SAVED -> Triple(R.string.library_saved_empty_title, R.string.library_saved_empty_message, R.drawable.ic_bookmark_border)
                    LibraryTab.DOWNLOADS -> Triple(R.string.library_downloads_empty_title, R.string.library_downloads_empty_message, R.drawable.ic_download)
                }
                val action = when (tab) {
                    LibraryTab.LOCAL, LibraryTab.DOWNLOADS -> getString(R.string.action_rescan)
                    LibraryTab.PLAYLISTS -> getString(R.string.action_new_playlist)
                    else -> null
                }
                state.empty(getString(title), getString(message), icon, action) {
                    when (tab) {
                        LibraryTab.PLAYLISTS -> ItemActions.newPlaylistDialog(requireActivity()) { viewModel.load() }
                        else -> requestPermissionOrScan()
                    }
                }
            }
            is UiState.Error -> {
                toolbar.visible(false)
                list.visible(false)
                state.error(uiState.error) { viewModel.load() }
            }
            is UiState.Offline -> {
                toolbar.visible(false)
                list.visible(false)
                state.offline({ viewModel.load() })
            }
        }
    }

    private fun requestPermissionOrScan() {
        val local = BlazeApp.graph(requireContext()).localProvider
        if (local.hasPermission()) {
            viewModel.rescan()
        } else {
            permissionLauncher.launch(local.requiredPermission)
        }
    }

    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + requireContext().packageName)))
        } catch (_: Exception) {
            requireContext().toast(R.string.state_error_title)
        }
    }

    private fun playAll(shuffle: Boolean) {
        val items = adapter.mediaItems().filter { it.canPlayDirect && !it.type.isCollection }
        if (items.isEmpty()) return
        val start = if (shuffle) (items.indices).random() else 0
        PlayerController.playQueue(requireContext(), items, start, shuffle)
        (activity as? Navigator)?.openPlayer()
    }

    override fun onItemClick(item: MediaItem, position: Int) {
        ItemActions.primary(requireActivity(), item, adapter.mediaItems())
    }

    override fun onItemMore(item: MediaItem) {
        val extras = ArrayList<ItemActionsSheet.ExtraAction>()
        LibraryViewModel.playlistIdOf(item)?.let { playlistId ->
            extras.add(ItemActionsSheet.ExtraAction(R.drawable.ic_delete, getString(R.string.action_delete)) {
                MaterialAlertDialogBuilder(requireContext())
                    .setMessage(getString(R.string.playlist_delete_confirm, item.title))
                    .setPositiveButton(R.string.action_delete) { _, _ ->
                        lifecycleScope.launch {
                            BlazeApp.graph(requireContext()).library.deletePlaylist(playlistId)
                            context?.toast(R.string.playlist_deleted)
                        }
                    }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
            })
        }
        ItemActionsSheet.show(parentFragmentManager, item, adapter.mediaItems(), extras)
    }
}
