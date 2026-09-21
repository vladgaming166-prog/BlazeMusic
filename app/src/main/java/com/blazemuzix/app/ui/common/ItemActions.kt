package com.blazemuzix.app.ui.common

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.blazemuzix.app.BlazeApp
import com.blazemuzix.app.R
import com.blazemuzix.app.data.models.MediaItem
import com.blazemuzix.app.data.models.MediaType
import com.blazemuzix.app.data.models.Playback
import com.blazemuzix.app.data.models.Source
import com.blazemuzix.app.player.PlayerController
import com.blazemuzix.app.ui.player.PlayerActivity
import com.blazemuzix.app.utils.Artwork
import com.blazemuzix.app.utils.ExternalActions
import com.blazemuzix.app.utils.dp
import com.blazemuzix.app.utils.toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/** Screens that can open collections / the player implement this (MainActivity does). */
interface Navigator {
    fun openCollection(item: MediaItem)
    fun openPlayer()
}

/**
 * The single place that decides what a user can do with an item, based on what
 * the provider actually permits. It never shows a fake "Download" or "Play".
 */
object ItemActions {

    fun primaryActionLabel(context: Context, item: MediaItem): String = when {
        item.canPlayDirect && item.previewOnly -> context.getString(R.string.action_play_preview)
        item.canPlayDirect -> context.getString(R.string.action_play)
        item.playback == Playback.EMBEDDED -> context.getString(R.string.action_watch_here)
        else -> openLabel(context, item)
    }

    fun openLabel(context: Context, item: MediaItem): String = when (item.source) {
        Source.SPOTIFY -> context.getString(R.string.action_open_in_spotify)
        Source.YOUTUBE -> context.getString(R.string.action_open_in_youtube)
        Source.YOUTUBE_MUSIC -> context.getString(R.string.action_open_in_youtube_music)
        Source.LOCAL -> context.getString(R.string.action_play)
    }

    fun capabilityDescription(context: Context, item: MediaItem): String = when {
        item.isLocal -> context.getString(R.string.badge_offline_ready)
        item.previewOnly -> context.getString(R.string.preview_only_notice)
        item.playback == Playback.EMBEDDED -> context.getString(R.string.playback_via_official, item.source.label)
        item.playback == Playback.EXTERNAL -> context.getString(R.string.playback_via_official, item.source.label)
        else -> context.getString(R.string.offline_download_unavailable)
    }

    /**
     * Performs the primary action for an item within [queueContext] (the list it
     * was tapped in so the whole list becomes the queue).
     */
    fun primary(activity: FragmentActivity, item: MediaItem, queueContext: List<MediaItem> = listOf(item), index: Int = 0) {
        val navigator = activity as? Navigator
        when {
            item.type.isCollection && item.source == Source.LOCAL -> navigator?.openCollection(item)
            item.type.isCollection -> navigator?.openCollection(item)
            item.canPlayDirect -> {
                val startIndex = queueContext.indexOfFirst { it.id == item.id }.takeIf { it >= 0 } ?: index
                PlayerController.playQueue(activity, queueContext, startIndex)
                navigator?.openPlayer() ?: activity.startActivity(Intent(activity, PlayerActivity::class.java))
            }
            item.playback == Playback.EMBEDDED -> WebPlayerActivity.start(activity, item)
            else -> openExternal(activity, item)
        }
    }

    fun openExternal(context: Context, item: MediaItem) {
        if (!ExternalActions.openExternal(context, item)) context.toast(R.string.state_error_title)
    }

    fun addToPlaylist(activity: FragmentActivity, item: MediaItem) {
        val library = BlazeApp.graph(activity).library
        activity.lifecycleScope.launch {
            val playlists = library.playlists()
            val names = playlists.map { it.name } + activity.getString(R.string.action_new_playlist)
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.action_add_to_playlist)
                .setItems(names.toTypedArray()) { _, which ->
                    if (which == playlists.size) {
                        newPlaylistDialog(activity) { id -> addTo(activity, id, names.getOrNull(which) ?: "", item) }
                    } else {
                        addTo(activity, playlists[which].id, playlists[which].name, item)
                    }
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    private fun addTo(activity: FragmentActivity, playlistId: Long, name: String, item: MediaItem) {
        val library = BlazeApp.graph(activity).library
        activity.lifecycleScope.launch {
            val added = library.addToPlaylist(playlistId, item)
            val playlistName = library.playlist(playlistId)?.name ?: name
            activity.toast(activity.getString(if (added) R.string.playlist_item_added else R.string.playlist_item_exists, playlistName))
        }
    }

    fun newPlaylistDialog(activity: FragmentActivity, onCreated: (Long) -> Unit) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_text_input, null)
        val input = view.findViewById<TextView>(R.id.dialog_input)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.action_new_playlist)
            .setView(view)
            .setPositiveButton(R.string.action_create) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) return@setPositiveButton
                activity.lifecycleScope.launch {
                    val id = BlazeApp.graph(activity).library.createPlaylist(name)
                    activity.toast(R.string.playlist_created)
                    onCreated(id)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
}

/** Bottom sheet listing every legitimate action for one item. */
class ItemActionsSheet : BottomSheetDialogFragment() {

    data class ExtraAction(val iconRes: Int, val label: String, val onClick: () -> Unit)

    private var item: MediaItem? = null
    private var queueContext: List<MediaItem> = emptyList()
    private var extras: List<ExtraAction> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        item = MediaItem.fromJsonOrNull(arguments?.getString(ARG_ITEM))
        if (savedInstanceState != null || item == null) dismissAllowingStateLoss()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.bottom_sheet_item_actions, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val item = item ?: return
        val activity = requireActivity()
        val library = BlazeApp.graph(activity).library
        val context = view.context

        view.findViewById<TextView>(R.id.actions_title).text = item.title
        view.findViewById<TextView>(R.id.actions_subtitle).text = "${item.artist} · ${item.source.label}"
        view.findViewById<TextView>(R.id.actions_capability).text = ItemActions.capabilityDescription(context, item)
        Artwork.load(view.findViewById(R.id.actions_artwork), item, view.dp(56), view.dp(8))

        val container = view.findViewById<LinearLayout>(R.id.actions_container)
        fun add(iconRes: Int, label: String, onClick: () -> Unit) {
            val row = LayoutInflater.from(context).inflate(R.layout.item_action, container, false)
            row.findViewById<ImageView>(R.id.action_icon).setImageResource(iconRes)
            row.findViewById<TextView>(R.id.action_label).text = label
            row.setOnClickListener { onClick(); dismissAllowingStateLoss() }
            container.addView(row)
        }

        // Primary action
        when {
            item.type.isCollection -> add(R.drawable.ic_playlist_play, getString(R.string.action_see_all)) { (activity as? Navigator)?.openCollection(item) }
            item.canPlayDirect -> add(R.drawable.ic_play, ItemActions.primaryActionLabel(context, item)) { ItemActions.primary(activity, item, queueContext.ifEmpty { listOf(item) }) }
            item.playback == Playback.EMBEDDED -> add(R.drawable.ic_video, getString(R.string.action_watch_here)) { WebPlayerActivity.start(activity, item) }
        }
        if (item.canPlayDirect) {
            add(R.drawable.ic_playlist_play, getString(R.string.action_play_next)) { PlayerController.addToQueue(activity, item, playNext = true); context.toast(R.string.added_to_queue) }
            add(R.drawable.ic_queue, getString(R.string.action_add_to_queue)) { PlayerController.addToQueue(activity, item); context.toast(R.string.added_to_queue) }
        }
        if (!item.isLocal && item.externalUrl != null) {
            add(R.drawable.ic_open_in_new, ItemActions.openLabel(context, item)) { ItemActions.openExternal(activity, item) }
            if (item.source == Source.YOUTUBE && (item.type == MediaType.VIDEO || item.type == MediaType.SHORT)) {
                add(R.drawable.ic_music_note, getString(R.string.action_open_in_youtube_music)) { ExternalActions.openInYouTubeMusic(activity, item) }
            }
        }
        // Library
        val fav = library.isFavorite(item.id)
        add(if (fav) R.drawable.ic_favorite else R.drawable.ic_favorite_border, getString(if (fav) R.string.action_unfavorite else R.string.action_favorite)) {
            activity.lifecycleScope.launch {
                val now = library.toggleFavorite(item)
                context.toast(if (now) R.string.favorite_added else R.string.favorite_removed)
            }
        }
        if (!item.isLocal) {
            val saved = library.isSaved(item.id)
            add(if (saved) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border, getString(if (saved) R.string.action_unsave else R.string.action_save)) {
                activity.lifecycleScope.launch {
                    val now = library.toggleSaved(item)
                    context.toast(if (now) R.string.saved_added else R.string.saved_removed)
                }
            }
        }
        if (!item.type.isCollection) {
            add(R.drawable.ic_playlist_add, getString(R.string.action_add_to_playlist)) { ItemActions.addToPlaylist(activity, item) }
        }
        if (item.externalUrl != null) {
            add(R.drawable.ic_share, getString(R.string.action_share)) { ExternalActions.share(activity, item) }
        }
        for (extra in extras) add(extra.iconRes, extra.label, extra.onClick)
    }

    companion object {
        private const val ARG_ITEM = "item"

        fun show(fm: FragmentManager, item: MediaItem, queueContext: List<MediaItem> = emptyList(), extras: List<ExtraAction> = emptyList()) {
            if (fm.isStateSaved) return
            ItemActionsSheet().apply {
                arguments = Bundle().apply { putString(ARG_ITEM, item.toJson().toString()) }
                this.queueContext = queueContext
                this.extras = extras
            }.show(fm, "item_actions")
        }
    }
}
