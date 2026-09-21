package com.blazemuzix.app.ui.common

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.blazemuzix.app.BlazeApp
import com.blazemuzix.app.R
import com.blazemuzix.app.data.models.MediaItem
import com.blazemuzix.app.data.models.Source
import com.blazemuzix.app.player.PlayerController
import com.blazemuzix.app.providers.SpotifyProvider
import com.blazemuzix.app.providers.YouTubeProvider
import com.blazemuzix.app.utils.ExternalActions
import com.blazemuzix.app.utils.toast
import com.blazemuzix.app.utils.visible
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

/**
 * Plays online items through the provider's official embedded web player
 * (YouTube IFrame embed / Spotify embed). BlazeMuzix never touches the media
 * streams themselves, so ads, DRM and playback rules stay with the provider.
 */
class WebPlayerActivity : AppCompatActivity() {

    private var webView: WebView? = null
    private lateinit var item: MediaItem
    private lateinit var state: StateView
    private var customView: View? = null
    private var chromeClient: WebChromeClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        item = MediaItem.fromJsonOrNull(intent.getStringExtra(EXTRA_ITEM)) ?: run { finish(); return }
        setContentView(R.layout.activity_web_player)

        val toolbar = findViewById<Toolbar>(R.id.web_toolbar)
        toolbar.title = item.source.label
        toolbar.setNavigationOnClickListener { finish() }

        findViewById<TextView>(R.id.web_title).text = item.title
        findViewById<TextView>(R.id.web_subtitle).text = "${item.artist} · ${item.source.label}"
        findViewById<TextView>(R.id.web_notice).text = getString(R.string.web_player_notice, item.source.label)
        state = StateView(findViewById(R.id.web_state))

        val openButton = findViewById<MaterialButton>(R.id.web_open_app)
        openButton.text = ItemActions.openLabel(this, item)
        openButton.setOnClickListener { ItemActions.openExternal(this, item) }

        val library = BlazeApp.graph(this).library
        val favorite = findViewById<ImageButton>(R.id.web_favorite)
        val save = findViewById<ImageButton>(R.id.web_save)
        fun refreshButtons() {
            favorite.setImageResource(if (library.isFavorite(item.id)) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
            save.setImageResource(if (library.isSaved(item.id)) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border)
        }
        refreshButtons()
        favorite.setOnClickListener {
            lifecycleScope.launch { toast(if (library.toggleFavorite(item)) R.string.favorite_added else R.string.favorite_removed); refreshButtons() }
        }
        save.setOnClickListener {
            lifecycleScope.launch { toast(if (library.toggleSaved(item)) R.string.saved_added else R.string.saved_removed); refreshButtons() }
        }
        findViewById<ImageButton>(R.id.web_share).setOnClickListener { ExternalActions.share(this, item) }

        // Local audio and the embedded player must not play at the same time.
        PlayerController.pause(this)
        lifecycleScope.launch { library.recordPlayed(item) }
        setupWebView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val container = findViewById<FrameLayout>(R.id.web_container)
        val progress = findViewById<ProgressBar>(R.id.web_progress)
        val network = BlazeApp.graph(this).network
        if (!network.isOnline) {
            progress.visible(false)
            state.offline({ recreate() }, getString(R.string.state_offline_message))
            return
        }
        val web = try {
            WebView(this)
        } catch (_: Exception) {
            progress.visible(false)
            state.show(R.drawable.ic_error_outline, getString(R.string.state_error_title), getString(R.string.state_error_message),
                ItemActions.openLabel(this, item)) { ItemActions.openExternal(this, item) }
            return
        }
        webView = web
        container.addView(web, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val prefs = BlazeApp.graph(this).prefs
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = prefs.dataSaver
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        web.setBackgroundColor(0xFF0B0D0C.toInt())
        val chromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null) return
                customView = view
                container.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }

            override fun onHideCustomView() {
                customView?.let { container.removeView(it) }
                customView = null
            }
        }
        this.chromeClient = chromeClient
        web.webChromeClient = chromeClient
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progress.visible(true)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visible(false)
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = handleNavigation(url)

            @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                handleNavigation(request?.url?.toString())

            @RequiresApi(Build.VERSION_CODES.M)
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) showLoadError()
            }

            @Suppress("DEPRECATION")
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                if (failingUrl == embedUrl()) showLoadError()
            }
        }
        web.loadUrl(embedUrl())
    }

    /** Keep the embed inside; anything leaving the embed domain opens the official app/browser. */
    private fun handleNavigation(url: String?): Boolean {
        if (url == null) return false
        val host = Uri.parse(url).host ?: return false
        val allowed = host.endsWith("youtube.com") && url.contains("/embed/") || host == "open.spotify.com" && url.contains("/embed")
        if (allowed) return false
        ExternalActions.openUrl(this, url)
        return true
    }

    private fun showLoadError() {
        findViewById<ProgressBar>(R.id.web_progress).visible(false)
        state.show(
            R.drawable.ic_cloud_off,
            getString(R.string.state_provider_unavailable_title),
            getString(R.string.state_provider_unavailable_message),
            ItemActions.openLabel(this, item)
        ) { ItemActions.openExternal(this, item) }
    }

    private fun embedUrl(): String = when (item.source) {
        Source.SPOTIFY -> SpotifyProvider.embedUrl(item.type, item.providerId)
        Source.YOUTUBE, Source.YOUTUBE_MUSIC -> YouTubeProvider.embedUrl(item.providerId, autoplay = !BlazeApp.graph(this).prefs.dataSaver)
        else -> item.externalUrl ?: item.playbackUri ?: "about:blank"
    }

    override fun onPause() {
        super.onPause()
        webView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (customView != null) {
            chromeClient?.onHideCustomView()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onDestroy() {
        webView?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.stopLoading()
            it.loadUrl("about:blank")
            it.destroy()
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ITEM = "item"

        fun start(context: Context, item: MediaItem) {
            context.startActivity(Intent(context, WebPlayerActivity::class.java).putExtra(EXTRA_ITEM, item.toJson().toString()))
        }
    }
}
