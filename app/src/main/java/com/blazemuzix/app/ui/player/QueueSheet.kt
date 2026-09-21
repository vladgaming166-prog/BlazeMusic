package com.blazemuzix.app.ui.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blazemuzix.app.BlazeApp
import com.blazemuzix.app.R
import com.blazemuzix.app.data.models.MediaItem
import com.blazemuzix.app.data.models.SectionLayout
import com.blazemuzix.app.player.PlayerController
import com.blazemuzix.app.ui.common.ItemActions
import com.blazemuzix.app.ui.common.ItemActionsSheet
import com.blazemuzix.app.ui.common.MediaAdapter
import com.blazemuzix.app.utils.dp
import com.blazemuzix.app.utils.toast
import com.blazemuzix.app.utils.visible
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

/** Current playback queue; tap to jump, drag to reorder, "more" to remove or add to a playlist. */
class QueueSheet : BottomSheetDialogFragment(), MediaAdapter.Listener {

    private lateinit var adapter: MediaAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.bottom_sheet_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<TextView>(R.id.sheet_title).text = getString(R.string.title_queue)
        val clear = view.findViewById<MaterialButton>(R.id.sheet_action)
        clear.visible(true)
        clear.text = getString(R.string.action_clear)
        clear.setOnClickListener { PlayerController.stop(requireContext()); dismissAllowingStateLoss() }
        val save = view.findViewById<MaterialButton>(R.id.sheet_action_secondary)
        save.visible(true)
        save.text = getString(R.string.action_save_queue)
        save.setOnClickListener {
            val queue = PlayerController.current.queue
            if (queue.isEmpty()) return@setOnClickListener
            ItemActions.newPlaylistDialog(requireActivity()) { id ->
                viewLifecycleOwner.lifecycleScope.launch {
                    BlazeApp.graph(requireContext()).library.addAllToPlaylist(id, queue)
                    context?.toast(getString(R.string.queue_saved, getString(R.string.title_queue)))
                }
            }
        }

        val list = view.findViewById<RecyclerView>(R.id.sheet_list)
        val empty = view.findViewById<TextView>(R.id.sheet_empty)
        empty.text = getString(R.string.player_queue_empty)
        adapter = MediaAdapter(SectionLayout.ROWS, this)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        list.layoutParams = list.layoutParams.apply { height = (resources.displayMetrics.heightPixels * 0.55f).toInt() }
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from < 0 || to < 0) return false
                PlayerController.moveQueueItem(requireContext(), from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
        }).attachToRecyclerView(list)

        PlayerController.state.observe(viewLifecycleOwner) { state ->
            adapter.nowPlayingId = state.current?.id
            adapter.submitItems(state.queue)
            empty.visible(state.queue.isEmpty())
            list.visible(state.queue.isNotEmpty())
            if (state.index >= 0 && savedInstanceState == null) list.post { list.scrollToPosition(state.index) }
        }
        view.minimumHeight = view.dp(200)
    }

    override fun onItemClick(item: MediaItem, position: Int) {
        val index = PlayerController.current.queue.indexOfFirst { it.id == item.id }
        if (index >= 0) PlayerController.skipToQueueIndex(requireContext(), index)
    }

    override fun onItemMore(item: MediaItem) {
        val index = PlayerController.current.queue.indexOfFirst { it.id == item.id }
        val extras = if (index >= 0) listOf(
            ItemActionsSheet.ExtraAction(R.drawable.ic_close, getString(R.string.action_remove_from_queue)) {
                PlayerController.removeFromQueue(requireContext(), index)
            }
        ) else emptyList()
        ItemActionsSheet.show(parentFragmentManager, item, PlayerController.current.queue, extras)
    }
}
