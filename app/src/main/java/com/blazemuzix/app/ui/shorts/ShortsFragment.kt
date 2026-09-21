package com.blazemuzix.app.ui.shorts

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.blazemuzix.app.BlazeApp
import com.blazemuzix.app.R
import com.blazemuzix.app.data.models.MediaItem
import com.blazemuzix.app.data.models.UiState
import com.blazemuzix.app.player.PlayerController
import com.blazemuzix.app.providers.YouTubeProvider
import com.blazemuzix.app.ui.common.ItemActions
import com.blazemuzix.app.ui.common.StateView
import com.blazemuzix.app.ui.common.WebPlayerActivity
import com.blazemuzix.app.utils.DeviceProfile
import com.blazemuzix.app.utils.ExternalActions
import com.blazemuzix.app.utils.toast
import com.blazemuzix.app.utils.visible
import kotlinx.coroutines.launch

/**
 * Blaze Shorts: a vertical, page-snapping feed of short music videos from the
 * official YouTube Data API. A single WebView (official YouTube embed) is
 * created lazily and moved between pages; nothing is preloaded.
 */
class ShortsFragment : Fragment(R.layout.fragment_shorts), ShortsAdapter.Listener {

    private val viewModel: ShortsViewModel by viewModels()
    private lateinit var list: RecyclerView
    private lateinit var state: StateView
    private lateinit var adapter: ShortsAdapter
    private lateinit var layoutManager: LinearLayoutManager
    private var webView: WebView? = null
    private var playingHolder: ShortsAdapter.Holder? = null
    private var inlineVideoAllowed = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()
        val prefs = BlazeApp.graph(context).prefs
        inlineVideoAllowed = DeviceProfile.supportsInlineVideo(context) && !prefs.lowEndMode

        list = view.findViewById(R.id.shorts_list)
        state = StateView(view.findViewById(R.id.shorts_state))
        adapter = ShortsAdapter(this)
        layoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
        list.layoutManager = layoutManager
        list.adapter = adapter
        list.setHasFixedSize(true)
        list.setItemViewCacheSize(1)
        PagerSnapHelper().attachToRecyclerView(list)
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    val index = layoutManager.findFirstCompletelyVisibleItemPosition()
                    if (index != RecyclerView.NO_POSITION) onPageSettled(index)
                }
            }
        })

        viewModel.state.observe(viewLifecycleOwner) { render(it) }
        BlazeApp.graph(context).library.changes.observe(viewLifecycleOwner) { refreshVisibleToggles() }
        BlazeApp.graph(context).network.online.observe(viewLifecycleOwner) { online ->
            if (online && viewModel.state.value is UiState.Offline) viewModel.load()
        }
        viewModel.loadIfNeeded()
    }

    private fun render(uiState: UiState<List<MediaItem>>) {
        when (uiState) {
            is UiState.Loading -> { list.visible(false); state.loading() }
            is UiState.Success -> { list.visible(true); state.hide(); adapter.submitList(uiState.data) }
            is UiState.Empty -> {
                list.visible(false)
                state.empty(getString(R.string.shorts_empty_title), getString(R.string.shorts_empty_message), R.drawable.ic_bolt)
            }
            is UiState.Error -> { list.visible(false); state.error(uiState.error) { viewModel.load() } }
            is UiState.Offline -> { list.visible(false); state.offline({ viewModel.load() }) }
        }
    }

    private fun onPageSettled(index: Int) {
        if (playingHolder != null && (playingHolder?.bindingAdapterPosition ?: -1) != index) stopInlinePlayback()
        viewModel.onPageShown(index)
    }

    // ---------------------------------------------------------- playback

    override fun onPlay(item: MediaItem, holder: ShortsAdapter.Holder) {
        val context = requireContext()
        if (item.source == com.blazemuzix.app.data.models.Source.CLOUD) {
            val url = item.playbackUri ?: item.externalUrl
            if (url.isNullOrBlank()) {
                context.toast(R.string.shorts_cloud_empty)
                return
            }
            ExternalActions.openUrl(context, url)
            lifecycleScope.launch { BlazeApp.graph(context).library.recordPlayed(item) }
            return
        }
        if (!BlazeApp.graph(context).network.isOnline) {
            context.toast(R.string.state_offline_title)
            return
        }
        PlayerController.pause(context)
        if (!inlineVideoAllowed) {
            WebPlayerActivity.start(context, item)
            return
        }
        val web = ensureWebView() ?: run { WebPlayerActivity.start(context, item); return }
        stopInlinePlayback()
        playingHolder = holder
        holder.attachPlayer(web)
        web.loadUrl(YouTubeProvider.embedUrl(item.providerId, autoplay = true))
        lifecycleScope.launch { BlazeApp.graph(context).library.recordPlayed(item) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(): WebView? {
        webView?.let { return it }
        val context = context ?: return null
        val web = try { WebView(context) } catch (_: Exception) { return null }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        web.setBackgroundColor(0xFF000000.toInt())
        web.webViewClient = object : WebViewClient() {
            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url != null && !url.contains("/embed/")) { ExternalActions.openUrl(requireContext(), url); return true }
                return false
            }
        }
        webView = web
        return web
    }

    private fun stopInlinePlayback() {
        val web = webView
        playingHolder?.detachPlayer()
        playingHolder = null
        if (web != null) {
            web.loadUrl("about:blank")
            web.onPause()
        }
    }

    override fun onPause() {
        super.onPause()
        stopInlinePlayback()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) stopInlinePlayback() else viewModel.loadIfNeeded()
    }

    override fun onDestroyView() {
        stopInlinePlayback()
        webView?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        webView = null
        super.onDestroyView()
    }

    // ------------------------------------------------------------ actions

    override fun onSave(item: MediaItem, holder: ShortsAdapter.Holder) {
        val library = BlazeApp.graph(requireContext()).library
        lifecycleScope.launch {
            val saved = library.toggleSaved(item)
            context?.toast(if (saved) R.string.saved_added else R.string.saved_removed)
            holder.refreshToggles()
        }
    }

    override fun onFavorite(item: MediaItem, holder: ShortsAdapter.Holder) {
        val library = BlazeApp.graph(requireContext()).library
        lifecycleScope.launch {
            val fav = library.toggleFavorite(item)
            context?.toast(if (fav) R.string.favorite_added else R.string.favorite_removed)
            holder.refreshToggles()
        }
    }

    override fun onShare(item: MediaItem) = ExternalActions.share(requireContext(), item)

    override fun onOpen(item: MediaItem) = ItemActions.openExternal(requireContext(), item)

    override fun isSaved(item: MediaItem): Boolean = BlazeApp.graph(requireContext()).library.isSaved(item.id)

    override fun isFavorite(item: MediaItem): Boolean = BlazeApp.graph(requireContext()).library.isFavorite(item.id)

    override fun noticeFor(item: MediaItem): String? {
        val prefs = BlazeApp.graph(requireContext()).prefs
        return when {
            !inlineVideoAllowed -> getString(R.string.shorts_low_end_notice)
            prefs.dataSaver -> getString(R.string.shorts_data_saver_notice)
            else -> null
        }
    }

    private fun refreshVisibleToggles() {
        for (i in 0 until list.childCount) {
            (list.getChildViewHolder(list.getChildAt(i)) as? ShortsAdapter.Holder)?.refreshToggles()
        }
    }
}
