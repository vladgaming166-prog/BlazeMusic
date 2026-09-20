package com.blazemuzix.app.ui.common

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.blazemuzix.app.R
import com.blazemuzix.app.data.models.MediaItem
import com.blazemuzix.app.data.models.MediaType
import com.blazemuzix.app.data.models.Playback
import com.blazemuzix.app.data.models.SectionLayout
import com.blazemuzix.app.data.models.Source
import com.blazemuzix.app.data.models.StorageTag
import com.blazemuzix.app.utils.Artwork
import com.blazemuzix.app.utils.Formatters
import com.blazemuzix.app.utils.dp
import com.blazemuzix.app.utils.label
import com.blazemuzix.app.utils.visible
import com.google.android.material.button.MaterialButton

/** Entries a media list can contain. */
sealed class ListEntry {
    abstract val key: String

    data class Header(val text: String) : ListEntry() {
        override val key get() = "header:$text"
    }

    data class Media(val item: MediaItem) : ListEntry() {
        override val key get() = item.id
    }

    /** Footer: [loading] shows a spinner, [exhausted] shows the end-of-results note. */
    data class LoadMore(val loading: Boolean, val exhausted: Boolean) : ListEntry() {
        override val key get() = "load_more"
    }

    companion object {
        fun of(items: List<MediaItem>): List<ListEntry> = items.map { Media(it) }
    }
}

class MediaAdapter(
    private val layout: SectionLayout,
    private val listener: Listener
) : ListAdapter<ListEntry, RecyclerView.ViewHolder>(DIFF) {

    interface Listener {
        fun onItemClick(item: MediaItem, position: Int)
        fun onItemMore(item: MediaItem) {}
        fun onLoadMore() {}
    }

    var nowPlayingId: String? = null
        set(value) {
            if (field == value) return
            val old = field
            field = value
            currentList.forEachIndexed { index, entry ->
                if (entry is ListEntry.Media && (entry.item.id == old || entry.item.id == value)) notifyItemChanged(index, PAYLOAD_PLAYING)
            }
        }

    /** When true, rows show LOCAL / DOWNLOADED / SAVED / ONLINE storage tags. */
    var showStorageTags: Boolean = false

    fun submitItems(items: List<MediaItem>) = submitList(ListEntry.of(items))

    fun mediaItems(): List<MediaItem> = currentList.mapNotNull { (it as? ListEntry.Media)?.item }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is ListEntry.Header -> TYPE_HEADER
        is ListEntry.LoadMore -> TYPE_LOAD_MORE
        is ListEntry.Media -> when (layout) {
            SectionLayout.ROWS -> TYPE_ROW
            SectionLayout.CARDS -> TYPE_CARD
            SectionLayout.WIDE_CARDS -> TYPE_WIDE_CARD
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(inflater.inflate(R.layout.item_list_header, parent, false))
            TYPE_LOAD_MORE -> LoadMoreHolder(inflater.inflate(R.layout.item_load_more, parent, false), listener)
            TYPE_ROW -> RowHolder(inflater.inflate(R.layout.item_media_row, parent, false), listener)
            TYPE_WIDE_CARD -> CardHolder(inflater.inflate(R.layout.item_media_card_wide, parent, false), listener, wide = true)
            else -> CardHolder(inflater.inflate(R.layout.item_media_card, parent, false), listener, wide = false)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val entry = getItem(position)) {
            is ListEntry.Header -> (holder as HeaderHolder).bind(entry)
            is ListEntry.LoadMore -> (holder as LoadMoreHolder).bind(entry)
            is ListEntry.Media -> when (holder) {
                is RowHolder -> holder.bind(entry.item, entry.item.id == nowPlayingId, showStorageTags)
                is CardHolder -> holder.bind(entry.item)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_PLAYING) && holder is RowHolder) {
            val entry = getItem(position) as? ListEntry.Media ?: return
            holder.setPlaying(entry.item.id == nowPlayingId)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        when (holder) {
            is RowHolder -> Artwork.clear(holder.artwork)
            is CardHolder -> Artwork.clear(holder.artwork)
        }
    }

    // ------------------------------------------------------------- holders

    class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val text: TextView = view.findViewById(R.id.header_text)
        fun bind(entry: ListEntry.Header) {
            text.text = entry.text
        }
    }

    class LoadMoreHolder(view: View, private val listener: Listener) : RecyclerView.ViewHolder(view) {
        private val button: MaterialButton = view.findViewById(R.id.load_more_button)
        private val progress: ProgressBar = view.findViewById(R.id.load_more_progress)
        private val end: TextView = view.findViewById(R.id.load_more_end)

        fun bind(entry: ListEntry.LoadMore) {
            button.visible(!entry.loading && !entry.exhausted)
            progress.visible(entry.loading)
            end.visible(entry.exhausted && !entry.loading)
            button.setOnClickListener { listener.onLoadMore() }
        }
    }

    class RowHolder(view: View, private val listener: Listener) : RecyclerView.ViewHolder(view) {
        val artwork: ImageView = view.findViewById(R.id.row_artwork)
        private val playing: ImageView = view.findViewById(R.id.row_playing_indicator)
        private val title: TextView = view.findViewById(R.id.row_title)
        private val subtitle: TextView = view.findViewById(R.id.row_subtitle)
        private val source: TextView = view.findViewById(R.id.row_source)
        private val tag: TextView = view.findViewById(R.id.row_tag)
        private val duration: TextView = view.findViewById(R.id.row_duration)
        private val more: ImageButton = view.findViewById(R.id.row_more)
        private var item: MediaItem? = null

        init {
            view.setOnClickListener { item?.let { listener.onItemClick(it, bindingAdapterPosition) } }
            more.setOnClickListener { item?.let { listener.onItemMore(it) } }
        }

        fun bind(item: MediaItem, isPlaying: Boolean, showStorageTags: Boolean) {
            this.item = item
            val context = itemView.context
            title.text = item.title
            subtitle.text = subtitleFor(item)
            source.text = item.source.label
            duration.text = if (item.durationMs > 0) Formatters.duration(item.durationMs) else ""
            duration.visible(item.durationMs > 0)
            val tagText = when {
                showStorageTags -> when (item.storageTag) {
                    StorageTag.LOCAL -> context.getString(R.string.tag_local)
                    StorageTag.DOWNLOADED -> context.getString(R.string.tag_downloaded)
                    StorageTag.SAVED -> context.getString(R.string.tag_saved)
                    StorageTag.ONLINE -> context.getString(R.string.tag_online)
                }
                item.previewOnly -> context.getString(R.string.tag_preview)
                item.playback == Playback.EXTERNAL -> context.getString(R.string.badge_open_in_app)
                else -> null
            }
            tag.visible(tagText != null)
            tag.text = tagText
            Artwork.load(artwork, item, itemView.dp(56), itemView.dp(8))
            artwork.contentDescription = context.getString(R.string.cd_artwork, item.title)
            setPlaying(isPlaying)
            itemView.contentDescription = "${item.title}, ${item.artist}, ${item.source.label}"
        }

        fun setPlaying(isPlaying: Boolean) = playing.visible(isPlaying)

        private fun subtitleFor(item: MediaItem): String {
            val context = itemView.context
            val type = item.type.label(context)
            return when (item.type) {
                MediaType.SONG, MediaType.VIDEO, MediaType.SHORT -> item.artist
                MediaType.ALBUM -> if (item.trackCount > 0) "$type · ${item.artist} · ${item.trackCount}" else "$type · ${item.artist}"
                MediaType.ARTIST -> "$type · ${item.artist}"
                MediaType.PLAYLIST -> if (item.trackCount > 0) "$type · ${item.artist} · ${context.getString(R.string.playlist_track_count, item.trackCount)}" else "$type · ${item.artist}"
            }
        }
    }

    class CardHolder(view: View, private val listener: Listener, private val wide: Boolean) : RecyclerView.ViewHolder(view) {
        val artwork: ImageView = view.findViewById(R.id.card_artwork)
        private val title: TextView = view.findViewById(R.id.card_title)
        private val subtitle: TextView = view.findViewById(R.id.card_subtitle)
        private val badge: TextView = view.findViewById(R.id.card_badge)
        private var item: MediaItem? = null

        init {
            view.setOnClickListener { item?.let { listener.onItemClick(it, bindingAdapterPosition) } }
            view.setOnLongClickListener { item?.let { listener.onItemMore(it) }; true }
        }

        fun bind(item: MediaItem) {
            this.item = item
            title.text = item.title
            subtitle.text = when (item.type) {
                MediaType.ARTIST -> itemView.context.getString(R.string.type_artist)
                MediaType.ALBUM, MediaType.PLAYLIST -> "${item.type.label(itemView.context)} · ${item.artist}"
                else -> item.artist
            } + " · " + item.source.label
            val badgeText = when {
                item.durationMs > 0 && (item.type == MediaType.VIDEO || item.type == MediaType.SHORT) -> Formatters.duration(item.durationMs)
                item.previewOnly -> itemView.context.getString(R.string.tag_preview)
                else -> null
            }
            badge.visible(badgeText != null)
            badge.text = badgeText
            val size = if (wide) itemView.dp(220) else itemView.dp(148)
            Artwork.load(artwork, item, size, itemView.dp(12))
            artwork.contentDescription = itemView.context.getString(R.string.cd_artwork, item.title)
            itemView.contentDescription = "${item.title}, ${item.artist}, ${item.source.label}"
        }
    }

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ROW = 1
        const val TYPE_CARD = 2
        const val TYPE_WIDE_CARD = 3
        const val TYPE_LOAD_MORE = 4
        private const val PAYLOAD_PLAYING = "playing"

        val DIFF = object : DiffUtil.ItemCallback<ListEntry>() {
            override fun areItemsTheSame(oldItem: ListEntry, newItem: ListEntry) = oldItem.key == newItem.key
            override fun areContentsTheSame(oldItem: ListEntry, newItem: ListEntry) = oldItem == newItem
        }

        fun sourceLabel(source: Source): String = source.label
    }
}
