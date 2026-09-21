package com.blazemuzix.app.ui.library

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blazemuzix.app.BlazeApp
import com.blazemuzix.app.R
import com.blazemuzix.app.data.models.MediaItem
import com.blazemuzix.app.data.models.MediaType
import com.blazemuzix.app.data.models.SectionLayout
import com.blazemuzix.app.network.ApiException
import com.blazemuzix.app.player.PlayerController
import com.blazemuzix.app.ui.common.ItemActions
import com.blazemuzix.app.ui.common.ItemActionsSheet
import com.blazemuzix.app.ui.common.ListEntry
import com.blazemuzix.app.ui.common.MediaAdapter
import com.blazemuzix.app.ui.common.Navigator
import com.blazemuzix.app.ui.common.StateView
import com.blazemuzix.app.utils.Artwork
import com.blazemuzix.app.utils.dp
import com.blazemuzix.app.utils.label
import com.blazemuzix.app.utils.toast
import com.blazemuzix.app.utils.visible
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Tracks of an album / playlist / artist from any provider, or of a user playlist.
 * Paginates with "Load more" until the provider reports the end.
 */
class DetailListFragment : Fragment(R.layout.fragment_detail_list), MediaAdapter.Listener {

    private lateinit var item: MediaItem
    private lateinit var adapter: MediaAdapter
    private lateinit var state: StateView
    private lateinit var list: RecyclerView
    private lateinit var countView: TextView
    private lateinit var play: MaterialButton
    private lateinit var shuffle: MaterialButton
    private lateinit var open: MaterialButton

    private var items: List<MediaItem> = emptyList()
    private var nextToken: String? = null
    private var exhausted = false
    private var loading = false
    private var job: Job? = null
    private val userPlaylistId: Long? get() = LibraryViewModel.playlistIdOf(item)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        item = MediaItem.fromJsonOrNull(requireArguments().getString(ARG_ITEM)) ?: run { parentFragmentManager.popBackStack(); return }
        val context = requireContext()

        view.findViewById<View>(R.id.detail_back).setOnClickListener { parentFragmentManager.popBackStack() }
        view.findViewById<TextView>(R.id.detail_toolbar_title).text = item.type.label(context)
        view.findViewById<View>(R.id.detail_more).setOnClickListener { ItemActionsSheet.show(parentFragmentManager, item) }
        view.findViewById<TextView>(R.id.detail_title).text = item.title
        view.findViewById<TextView>(R.id.detail_subtitle).text = item.artist
        view.findViewById<TextView>(R.id.detail_source).text = item.source.label
        countView = view.findViewById(R.id.detail_count)
        Artwork.load(view.findViewById<ImageView>(R.id.detail_artwork), item, view.dp(96), view.dp(12))

        play = view.findViewById(R.id.detail_play)
        shuffle = view.findViewById(R.id.detail_shuffle)
        open = view.findViewById(R.id.detail_open)
        play.setOnClickListener { playAll(false) }
        shuffle.setOnClickListener { playAll(true) }
        open.text = ItemActions.openLabel(context, item)
        open.setOnClickListener { ItemActions.openExternal(context, item) }
        open.visible(!item.isLocal && item.externalUrl != null)

        list = view.findViewById(R.id.detail_list)
        state = StateView(view.findViewById(R.id.detail_state))
        adapter = MediaAdapter(SectionLayout.ROWS, this)
        list.layoutManager = LinearLayoutManager(context)
        androidx.recyclerview.widget.DividerItemDecoration(context, androidx.recyclerview.widget.DividerItemDecoration.VERTICAL).apply {
            androidx.appcompat.content.res.AppCompatResources.getDrawable(context, R.drawable.divider_inset)?.let { setDrawable(it) }
            list.addItemDecoration(this)
        }
        list.adapter = adapter

        PlayerController.state.observe(viewLifecycleOwner) { adapter.nowPlayingId = it.current?.id }
        if (userPlaylistId != null) {
            BlazeApp.graph(context).library.changes.observe(viewLifecycleOwner) { if (it > 0) load(reset = true) }
        }
        load(reset = true)
    }

    private fun load(reset: Boolean) {
        if (loading) return
        loading = true
        if (reset) {
            items = emptyList()
            nextToken = null
            exhausted = false
            if (adapter.itemCount == 0) state.loading()
        } else {
            render()
        }
        job = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val graph = BlazeApp.graph(requireContext())
                val playlistId = userPlaylistId
                if (playlistId != null) {
                    items = graph.library.playlistItems(playlistId)
                    exhausted = true
                } else {
                    val page = graph.discovery.collectionItems(item, nextToken)
                    val seen = items.map { it.id }.toHashSet()
                    items = items + page.items.filter { seen.add(it.id) }
                    nextToken = page.nextPageToken
                    exhausted = nextToken == null || page.items.isEmpty()
                }
                loading = false
                render()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loading = false
                if (items.isEmpty()) {
                    list.visible(false)
                    if (e is ApiException.Offline) state.offline({ load(reset = true) }) else state.error(e) { load(reset = true) }
                } else {
                    context?.toast(R.string.state_error_message)
                    render()
                }
            }
        }
    }

    private fun render() {
        if (items.isEmpty() && !loading) {
            list.visible(false)
            state.empty(
                getString(R.string.empty_generic_title),
                if (userPlaylistId != null) getString(R.string.playlist_empty_message) else getString(R.string.empty_generic_message),
                R.drawable.ic_music_note
            )
            play.visible(false)
            shuffle.visible(false)
            return
        }
        state.hide()
        list.visible(true)
        val entries = ArrayList<ListEntry>(items.size + 1)
        items.forEach { entries.add(ListEntry.Media(it)) }
        if (!exhausted || loading) entries.add(ListEntry.LoadMore(loading = loading, exhausted = exhausted))
        adapter.submitList(entries)
        countView.text = getString(R.string.playlist_track_count, if (item.trackCount > items.size) item.trackCount else items.size)
        val playable = items.count { it.canPlayDirect }
        play.visible(playable > 0)
        shuffle.visible(playable > 1)
        if (playable == 0 && !item.isLocal) play.visible(false)
    }

    private fun playAll(shuffle: Boolean) {
        val playable = items.filter { it.canPlayDirect }
        if (playable.isEmpty()) return
        val start = if (shuffle) playable.indices.random() else 0
        PlayerController.playQueue(requireContext(), playable, start, shuffle)
        (activity as? Navigator)?.openPlayer()
    }

    override fun onItemClick(item: MediaItem, position: Int) {
        ItemActions.primary(requireActivity(), item, items)
    }

    override fun onItemMore(item: MediaItem) {
        val extras = ArrayList<ItemActionsSheet.ExtraAction>()
        userPlaylistId?.let { playlistId ->
            extras.add(ItemActionsSheet.ExtraAction(R.drawable.ic_delete, getString(R.string.action_remove_from_playlist)) {
                viewLifecycleOwner.lifecycleScope.launch { BlazeApp.graph(requireContext()).library.removeFromPlaylist(playlistId, item.id) }
            })
        }
        ItemActionsSheet.show(parentFragmentManager, item, items, extras)
    }

    override fun onLoadMore() {
        if (!exhausted) load(reset = false)
    }

    companion object {
        private const val ARG_ITEM = "item"

        fun newInstance(item: MediaItem): DetailListFragment = DetailListFragment().apply {
            arguments = Bundle().apply { putString(ARG_ITEM, item.toJson().toString()) }
        }

        fun isBrowsable(item: MediaItem): Boolean = item.type == MediaType.ALBUM || item.type == MediaType.PLAYLIST || item.type == MediaType.ARTIST
    }
}
