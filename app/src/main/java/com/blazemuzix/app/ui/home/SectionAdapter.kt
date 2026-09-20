package com.blazemuzix.app.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.blazemuzix.app.R
import com.blazemuzix.app.data.models.MediaItem
import com.blazemuzix.app.data.models.Section
import com.blazemuzix.app.data.models.SectionLayout
import com.blazemuzix.app.ui.common.MediaAdapter
import com.blazemuzix.app.utils.visible

/** Vertical list of Home sections; each section hosts its own horizontal (or vertical) list. */
class SectionAdapter(private val listener: Listener) : ListAdapter<Section, SectionAdapter.Holder>(DIFF) {

    interface Listener {
        fun onItemClick(section: Section, item: MediaItem)
        fun onItemMore(section: Section, item: MediaItem)
    }

    private val pool = RecyclerView.RecycledViewPool()
    private val scrollStates = HashMap<String, Int>()
    var nowPlayingId: String? = null
        set(value) {
            field = value
            notifyItemRangeChanged(0, itemCount, PAYLOAD_PLAYING)
        }

    override fun getItemViewType(position: Int): Int = getItem(position).layout.ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_section, parent, false)
        return Holder(view, SectionLayout.entries[viewType], pool, listener)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position), nowPlayingId, scrollStates)
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_PLAYING)) holder.adapter.nowPlayingId = nowPlayingId else super.onBindViewHolder(holder, position, payloads)
    }

    override fun onViewRecycled(holder: Holder) {
        holder.saveScroll(scrollStates)
    }

    class Holder(view: View, layout: SectionLayout, pool: RecyclerView.RecycledViewPool, private val listener: Listener) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.section_title)
        private val source: TextView = view.findViewById(R.id.section_source)
        private val list: RecyclerView = view.findViewById(R.id.section_list)
        private var section: Section? = null
        val adapter = MediaAdapter(layout, object : MediaAdapter.Listener {
            override fun onItemClick(item: MediaItem, position: Int) {
                section?.let { listener.onItemClick(it, item) }
            }

            override fun onItemMore(item: MediaItem) {
                section?.let { listener.onItemMore(it, item) }
            }
        })

        init {
            val horizontal = layout != SectionLayout.ROWS
            list.layoutManager = LinearLayoutManager(view.context, if (horizontal) RecyclerView.HORIZONTAL else RecyclerView.VERTICAL, false)
            list.setRecycledViewPool(pool)
            list.adapter = adapter
            list.setHasFixedSize(false)
            list.isNestedScrollingEnabled = false
            if (horizontal) list.setItemViewCacheSize(4)
            if (!horizontal) {
                list.setPadding(0, 0, 0, 0)
            }
        }

        fun bind(section: Section, nowPlayingId: String?, scrollStates: Map<String, Int>) {
            this.section = section
            title.text = section.title
            source.visible(section.source != null)
            source.text = section.source?.label
            adapter.nowPlayingId = nowPlayingId
            // Row sections are capped so the Home screen stays fast to scroll.
            adapter.submitItems(if (section.layout == SectionLayout.ROWS) section.items.take(8) else section.items)
            (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(scrollStates[section.id] ?: 0, 0)
        }

        fun saveScroll(scrollStates: MutableMap<String, Int>) {
            val id = section?.id ?: return
            scrollStates[id] = (list.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition().coerceAtLeast(0)
        }
    }

    companion object {
        private const val PAYLOAD_PLAYING = "playing"
        val DIFF = object : DiffUtil.ItemCallback<Section>() {
            override fun areItemsTheSame(oldItem: Section, newItem: Section) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Section, newItem: Section) = oldItem == newItem
        }
    }
}
