package com.blazemuzix.app.ui.shorts

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.blazemuzix.app.R
import com.blazemuzix.app.data.models.MediaItem
import com.blazemuzix.app.utils.Artwork
import com.blazemuzix.app.utils.visible

/**
 * Full-page vertical feed. Only artwork is bound per page; the single shared
 * video player is attached by [ShortsFragment] to the page that is on screen.
 */
class ShortsAdapter(private val listener: Listener) : ListAdapter<MediaItem, ShortsAdapter.Holder>(DIFF) {

    interface Listener {
        fun onPlay(item: MediaItem, holder: Holder)
        fun onSave(item: MediaItem, holder: Holder)
        fun onFavorite(item: MediaItem, holder: Holder)
        fun onShare(item: MediaItem)
        fun onOpen(item: MediaItem)
        fun isSaved(item: MediaItem): Boolean
        fun isFavorite(item: MediaItem): Boolean
        fun noticeFor(item: MediaItem): String?
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_short, parent, false), listener)

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    override fun onViewRecycled(holder: Holder) {
        holder.detachPlayer()
        Artwork.clear(holder.artwork)
    }

    class Holder(view: View, private val listener: Listener) : RecyclerView.ViewHolder(view) {
        val artwork: ImageView = view.findViewById(R.id.short_artwork)
        val playerContainer: FrameLayout = view.findViewById(R.id.short_player_container)
        val playButton: ImageButton = view.findViewById(R.id.short_play)
        private val save: ImageButton = view.findViewById(R.id.short_save)
        private val favorite: ImageButton = view.findViewById(R.id.short_favorite)
        private val share: ImageButton = view.findViewById(R.id.short_share)
        private val open: ImageButton = view.findViewById(R.id.short_open)
        private val title: TextView = view.findViewById(R.id.short_title)
        private val artist: TextView = view.findViewById(R.id.short_artist)
        private val source: TextView = view.findViewById(R.id.short_source)
        private val notice: TextView = view.findViewById(R.id.short_notice)
        var item: MediaItem? = null
            private set

        init {
            playButton.setOnClickListener { item?.let { listener.onPlay(it, this) } }
            save.setOnClickListener { item?.let { listener.onSave(it, this) } }
            favorite.setOnClickListener { item?.let { listener.onFavorite(it, this) } }
            share.setOnClickListener { item?.let { listener.onShare(it) } }
            open.setOnClickListener { item?.let { listener.onOpen(it) } }
        }

        fun bind(item: MediaItem) {
            this.item = item
            title.text = item.title
            artist.text = item.artist
            source.text = item.source.label
            val noticeText = listener.noticeFor(item)
            notice.visible(noticeText != null)
            notice.text = noticeText
            refreshToggles()
            detachPlayer()
            val width = itemView.resources.displayMetrics.widthPixels.coerceAtMost(1080)
            Artwork.load(artwork, item, width, 0)
            artwork.contentDescription = itemView.context.getString(R.string.cd_artwork, item.title)
        }

        fun refreshToggles() {
            val item = item ?: return
            save.setImageResource(if (listener.isSaved(item)) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border)
            save.contentDescription = itemView.context.getString(if (listener.isSaved(item)) R.string.action_unsave else R.string.action_save)
            favorite.setImageResource(if (listener.isFavorite(item)) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
            favorite.contentDescription = itemView.context.getString(if (listener.isFavorite(item)) R.string.action_unfavorite else R.string.action_favorite)
        }

        fun attachPlayer(view: View) {
            detachPlayer()
            (view.parent as? ViewGroup)?.removeView(view)
            playerContainer.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            playerContainer.visible(true)
            playButton.visible(false)
            artwork.visible(false)
        }

        fun detachPlayer() {
            playerContainer.removeAllViews()
            playerContainer.visible(false)
            playButton.visible(true)
            artwork.visible(true)
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<MediaItem>() {
            override fun areItemsTheSame(oldItem: MediaItem, newItem: MediaItem) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: MediaItem, newItem: MediaItem) = oldItem == newItem
        }
    }
}
