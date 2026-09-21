package com.blazemuzix.app.ui.library

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blazemuzix.app.BlazeApp
import com.blazemuzix.app.R
import com.blazemuzix.app.data.models.MediaItem
import com.blazemuzix.app.data.models.SectionLayout
import com.blazemuzix.app.data.models.UiState
import com.blazemuzix.app.data.prefs.AppPreferences
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
import java.util.LinkedHashSet

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
    private lateinit var filterRow: View
    private lateinit var filterField: EditText
    private lateinit var filterClear: View
    private lateinit var selectButton: View
    private lateinit var bulkBar: View
    private var permanentlyDenied = false
    private var selectionMode = false
    private val selected = LinkedHashSet<String>()
    private var usingGrid = false

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            viewModel.rescan()
        } else {
            permanentlyDenied = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !shouldShowRequestPermissionRationale(BlazeApp.graph(requireContext()).localProvider.requiredPermission)
            viewModel.load()
        }
    }

    private val deleteLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        viewModel.rescan()
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
        filterRow = view.findViewById(R.id.library_filter_row)
        filterField = view.findViewById(R.id.library_filter)
        filterClear = view.findViewById(R.id.library_filter_clear)
        selectButton = view.findViewById(R.id.library_select)
        bulkBar = view.findViewById(R.id.library_bulk)
        filterField.setText(viewModel.filter)
        filterField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                filterClear.visible(!s.isNullOrEmpty())
                viewModel.setFilter(s?.toString() ?: "")
            }
        })
        filterClear.setOnClickListener { filterField.setText("") }
        view.findViewById<View>(R.id.library_sort).setOnClickListener { showSortDialog() }
        selectButton.setOnClickListener { toggleSelectionMode() }
        view.findViewById<View>(R.id.library_bulk_queue).setOnClickListener { bulkQueue() }
        view.findViewById<View>(R.id.library_bulk_playlist).setOnClickListener { bulkPlaylist() }
        view.findViewById<View>(R.id.library_bulk_unfavorite).setOnClickListener { bulkUnfavorite() }
        view.findViewById<View>(R.id.library_bulk_delete).setOnClickListener { bulkDelete() }

        rebuildAdapter()

        val chips = view.findViewById<ChipGroup>(R.id.library_tabs)
        chips.check(chipIdFor(viewModel.tab))
        chips.setOnCheckedStateChangeListener { _, checkedIds ->
            val tab = when (checkedIds.firstOrNull()) {
                R.id.tab_albums -> LibraryTab.ALBUMS
                R.id.tab_artists -> LibraryTab.ARTISTS
                R.id.tab_playlists -> LibraryTab.PLAYLISTS
                R.id.tab_favorites -> LibraryTab.FAVORITES
                R.id.tab_recent -> LibraryTab.RECENT
                R.id.tab_folders -> LibraryTab.FOLDERS
                R.id.tab_downloads -> LibraryTab.DOWNLOADS
                R.id.tab_offline -> LibraryTab.OFFLINE
                R.id.tab_uploaded -> LibraryTab.UPLOADED
                else -> LibraryTab.SONGS
            }
            viewModel.selectTab(tab)
        }

        playAll.setOnClickListener { playAll(shuffle = false) }
        shuffle.setOnClickListener { playAll(shuffle = true) }
        addButton.setOnClickListener {
            if (viewModel.tab == LibraryTab.UPLOADED) {
                startActivity(Intent(requireContext(), com.blazemuzix.app.ui.cloud.UploadActivity::class.java))
            } else {
                ItemActions.newPlaylistDialog(requireActivity()) { viewModel.load() }
            }
        }
        rescanButton.setOnClickListener { requestPermissionOrScan() }

        viewModel.state.observe(viewLifecycleOwner) { render(it) }
        BlazeApp.graph(context).library.changes.observe(viewLifecycleOwner) { if (it > 0) viewModel.load() }
        PlayerController.state.observe(viewLifecycleOwner) { adapter.nowPlayingId = it.current?.id }
        viewModel.load()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            val grid = BlazeApp.graph(requireContext()).prefs.libraryStyle == AppPreferences.STYLE_GRID
            if (grid != usingGrid) {
                val items = adapter.mediaItems()
                rebuildAdapter()
                adapter.submitItems(items)
            }
            if (viewModel.syncTabFromPrefs()) {
                view?.findViewById<ChipGroup>(R.id.library_tabs)?.check(chipIdFor(viewModel.tab))
            }
        }
    }

    private fun chipIdFor(tab: LibraryTab) = when (tab) {
        LibraryTab.SONGS -> R.id.tab_songs
        LibraryTab.ALBUMS -> R.id.tab_albums
        LibraryTab.ARTISTS -> R.id.tab_artists
        LibraryTab.PLAYLISTS -> R.id.tab_playlists
        LibraryTab.FAVORITES -> R.id.tab_favorites
        LibraryTab.RECENT -> R.id.tab_recent
        LibraryTab.FOLDERS -> R.id.tab_folders
        LibraryTab.DOWNLOADS -> R.id.tab_downloads
        LibraryTab.OFFLINE -> R.id.tab_offline
        LibraryTab.UPLOADED -> R.id.tab_uploaded
    }

    private fun rebuildAdapter() {
        val grid = BlazeApp.graph(requireContext()).prefs.libraryStyle == AppPreferences.STYLE_GRID
        usingGrid = grid
        adapter = MediaAdapter(if (grid) SectionLayout.CARDS else SectionLayout.ROWS, this).apply {
            showStorageTags = true
            fillParent = grid
        }
        applySelectionToAdapter()
        list.layoutManager = if (grid) GridLayoutManager(requireContext(), 2) else LinearLayoutManager(requireContext())
        list.adapter = adapter
        if (!grid && list.itemDecorationCount == 0) {
            androidx.recyclerview.widget.DividerItemDecoration(requireContext(), androidx.recyclerview.widget.DividerItemDecoration.VERTICAL).apply {
                androidx.appcompat.content.res.AppCompatResources.getDrawable(requireContext(), R.drawable.divider_inset)?.let { setDrawable(it) }
                list.addItemDecoration(this)
            }
        }
        while (grid && list.itemDecorationCount > 0) list.removeItemDecorationAt(0)
    }

    private fun showSortDialog() {
        val values = arrayOf(
            AppPreferences.SORT_TITLE, AppPreferences.SORT_ARTIST, AppPreferences.SORT_ALBUM,
            AppPreferences.SORT_RECENTLY_ADDED, AppPreferences.SORT_RECENTLY_PLAYED, AppPreferences.SORT_DURATION
        )
        val labels = arrayOf(
            getString(R.string.sort_title), getString(R.string.sort_artist), getString(R.string.sort_album),
            getString(R.string.sort_recently_added), getString(R.string.sort_recently_played), getString(R.string.sort_duration)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.library_sort)
            .setSingleChoiceItems(labels, values.indexOf(viewModel.sort).coerceAtLeast(0)) { dialog, which ->
                val chosen = values[which]
                if (chosen == AppPreferences.SORT_RECENTLY_PLAYED) {
                    viewModel.refreshRecentOrder { viewModel.setSort(chosen) }
                } else {
                    viewModel.setSort(chosen)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun render(uiState: UiState<List<MediaItem>>) {
        val tab = viewModel.tab
        val local = BlazeApp.graph(requireContext()).localProvider
        val isLocalTab = tab == LibraryTab.SONGS || tab == LibraryTab.ALBUMS || tab == LibraryTab.ARTISTS ||
            tab == LibraryTab.FOLDERS || tab == LibraryTab.DOWNLOADS || tab == LibraryTab.OFFLINE
        addButton.visible(tab == LibraryTab.PLAYLISTS || tab == LibraryTab.UPLOADED)
        rescanButton.visible(isLocalTab)
        notice.visible(tab == LibraryTab.DOWNLOADS)
        val selectable = tab == LibraryTab.SONGS || tab == LibraryTab.FAVORITES || tab == LibraryTab.RECENT ||
            tab == LibraryTab.DOWNLOADS || tab == LibraryTab.OFFLINE
        selectButton.visible(selectable)
        if (selectionMode && !selectable) exitSelection()

        if (isLocalTab && !local.hasPermission()) {
            list.visible(false)
            toolbar.visible(false)
            filterRow.visible(false)
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
                filterRow.visible(false)
                list.visible(false)
                state.loading(if (isLocalTab) getString(R.string.scanning_local) else null)
            }
            is UiState.Success -> {
                state.hide()
                list.visible(true)
                filterRow.visible(true)
                val items = uiState.data
                adapter.submitItems(items)
                val playable = items.count { it.canPlayDirect && !it.type.isCollection }
                toolbar.visible(true)
                val collections = tab == LibraryTab.PLAYLISTS || tab == LibraryTab.ALBUMS || tab == LibraryTab.ARTISTS || tab == LibraryTab.FOLDERS
                count.text = if (collections) resources.getString(R.string.items_count, items.size) else resources.getString(R.string.playlist_track_count, items.size)
                if (selectionMode) count.text = getString(R.string.library_selected, selected.size)
                playAll.visible(playable > 0 && !selectionMode)
                shuffle.visible(playable > 1 && !selectionMode)
            }
            is UiState.Empty -> {
                toolbar.visible(false)
                list.visible(false)
                adapter.submitList(emptyList())
                if (viewModel.filter.isNotEmpty() && viewModel.unfilteredCount > 0) {
                    // The list has content; only the filter hides it.
                    filterRow.visible(true)
                    state.empty(getString(R.string.library_filter_empty), "", R.drawable.ic_search, getString(R.string.action_clear)) { filterField.setText("") }
                    return
                }
                filterRow.visible(false)
                val (title, message, icon) = when (tab) {
                    LibraryTab.SONGS -> Triple(R.string.library_local_empty_title, R.string.library_local_empty_message, R.drawable.ic_phone)
                    LibraryTab.ALBUMS -> Triple(R.string.library_albums_empty_title, R.string.library_albums_empty_message, R.drawable.ic_album)
                    LibraryTab.ARTISTS -> Triple(R.string.library_artists_empty_title, R.string.library_artists_empty_message, R.drawable.ic_person)
                    LibraryTab.FAVORITES -> Triple(R.string.library_favorites_empty_title, R.string.library_favorites_empty_message, R.drawable.ic_favorite_border)
                    LibraryTab.RECENT -> Triple(R.string.library_recent_empty_title, R.string.library_recent_empty_message, R.drawable.ic_history)
                    LibraryTab.PLAYLISTS -> Triple(R.string.library_playlists_empty_title, R.string.library_playlists_empty_message, R.drawable.ic_playlist_play)
                    LibraryTab.FOLDERS -> Triple(R.string.library_folders_empty_title, R.string.library_folders_empty_message, R.drawable.ic_folder)
                    LibraryTab.DOWNLOADS -> Triple(R.string.library_downloads_empty_title, R.string.library_downloads_empty_message, R.drawable.ic_download)
                    LibraryTab.OFFLINE -> Triple(R.string.library_local_empty_title, R.string.library_local_empty_message, R.drawable.ic_phone)
                    LibraryTab.UPLOADED -> Triple(R.string.library_uploaded_empty_title, R.string.library_uploaded_empty_message, R.drawable.ic_music_note)
                }
                val action = when (tab) {
                    LibraryTab.SONGS, LibraryTab.ALBUMS, LibraryTab.ARTISTS, LibraryTab.FOLDERS, LibraryTab.DOWNLOADS, LibraryTab.OFFLINE -> getString(R.string.action_rescan)
                    LibraryTab.PLAYLISTS -> getString(R.string.action_new_playlist)
                    LibraryTab.UPLOADED -> getString(R.string.home_quick_upload)
                    else -> null
                }
                state.empty(getString(title), getString(message), icon, action) {
                    when (tab) {
                        LibraryTab.PLAYLISTS -> ItemActions.newPlaylistDialog(requireActivity()) { viewModel.load() }
                        LibraryTab.UPLOADED -> startActivity(Intent(requireContext(), com.blazemuzix.app.ui.cloud.UploadActivity::class.java))
                        else -> requestPermissionOrScan()
                    }
                }
            }
            is UiState.Error -> {
                toolbar.visible(false)
                filterRow.visible(false)
                list.visible(false)
                state.error(uiState.error) { viewModel.load() }
            }
            is UiState.Offline -> {
                toolbar.visible(false)
                filterRow.visible(false)
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
        if (selectionMode) {
            toggleSelected(item)
            return
        }
        ItemActions.primary(requireActivity(), item, adapter.mediaItems())
    }

    override fun onItemLongClick(item: MediaItem): Boolean {
        if (!selectionMode) toggleSelectionMode()
        toggleSelected(item)
        return true
    }

    override fun onItemMore(item: MediaItem) {
        if (selectionMode) {
            toggleSelected(item)
            return
        }
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

    private fun toggleSelectionMode() {
        if (selectionMode) exitSelection() else {
            selectionMode = true
            applySelectionToAdapter()
            bulkBar.visible(true)
        }
    }

    private fun exitSelection() {
        selectionMode = false
        selected.clear()
        applySelectionToAdapter()
        bulkBar.visible(false)
        viewModel.state.value?.let { if (it is UiState.Success) render(it) }
    }

    private fun applySelectionToAdapter() {
        if (!::adapter.isInitialized) return
        adapter.selectionMode = selectionMode
        adapter.selectedIds = selected
    }

    private fun toggleSelected(item: MediaItem) {
        if (!selected.add(item.id)) selected.remove(item.id)
        applySelectionToAdapter()
        count.text = getString(R.string.library_selected, selected.size)
        if (selected.isEmpty()) exitSelection()
    }

    private fun selectedItems(): List<MediaItem> = adapter.mediaItems().filter { it.id in selected }

    private fun bulkQueue() {
        selectedItems().filter { it.canPlayDirect }.forEach { PlayerController.addToQueue(requireContext(), it) }
        requireContext().toast(R.string.added_to_queue)
        exitSelection()
    }

    private fun bulkPlaylist() {
        val items = selectedItems()
        if (items.isEmpty()) return
        ItemActions.newPlaylistDialog(requireActivity()) { id ->
            lifecycleScope.launch {
                BlazeApp.graph(requireContext()).library.addAllToPlaylist(id, items)
                context?.toast(R.string.playlist_created)
            }
        }
        exitSelection()
    }

    private fun bulkUnfavorite() {
        lifecycleScope.launch {
            BlazeApp.graph(requireContext()).library.removeFavorites(selected)
            context?.toast(R.string.favorite_removed)
            exitSelection()
        }
    }

    private fun bulkDelete() {
        val items = selectedItems().filter { it.source == com.blazemuzix.app.data.models.Source.LOCAL && it.canPlayDirect }
        if (items.isEmpty()) {
            requireContext().toast(R.string.delete_local_unavailable)
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(getString(R.string.delete_local_confirm, items.size))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                val outcome = BlazeApp.graph(requireContext()).localProvider.requestDelete(items)
                val pending = outcome.pendingIntent
                if (pending != null) {
                    try {
                        deleteLauncher.launch(IntentSenderRequest.Builder(pending).build())
                    } catch (_: Exception) {
                        requireContext().toast(R.string.delete_local_unavailable)
                    }
                } else {
                    requireContext().toast(getString(R.string.delete_local_done, outcome.deleted))
                    viewModel.rescan()
                }
                exitSelection()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
}
