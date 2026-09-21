package com.blazemuzix.app

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.multidex.MultiDexApplication
import com.blazemuzix.app.auth.GoogleAuth
import com.blazemuzix.app.auth.SecureStore
import com.blazemuzix.app.auth.SpotifyAuth
import com.blazemuzix.app.data.cache.ResponseCache
import com.blazemuzix.app.data.db.BlazeDatabase
import com.blazemuzix.app.data.prefs.AppPreferences
import com.blazemuzix.app.data.repository.DiscoveryRepository
import com.blazemuzix.app.data.repository.LibraryRepository
import com.blazemuzix.app.network.HttpClient
import com.blazemuzix.app.network.NetworkMonitor
import com.blazemuzix.app.providers.LocalMusicProvider
import com.blazemuzix.app.providers.ProviderManager
import com.blazemuzix.app.providers.ProviderRegistry
import com.blazemuzix.app.providers.SpotifyProvider
import com.blazemuzix.app.providers.YouTubeProvider
import com.blazemuzix.app.utils.Artwork
import com.blazemuzix.app.utils.CrashDiagnostics
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application entry point. Dependencies are created lazily in [Graph] (a tiny
 * hand-written service locator keeps startup fast and avoids DI libraries).
 */
class BlazeApp : MultiDexApplication() {

    lateinit var graph: Graph
        private set

    /** Best-effort background work; a failure here must never take the process down. */
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            Log.w("BlazeApp", "Background task failed", e)
        }
    )

    override fun onCreate() {
        super.onCreate()
        CrashDiagnostics.install(this)
        graph = Graph(this)
        graph.prefs.ensureDefaults()
        graph.prefs.applyTheme()
        graph.googleAuth.restoreFromLastAccount()
        appScope.launch {
            graph.library.warmUp()
            val saved = runCatching { graph.library.loadQueue() }.getOrNull()
            if (saved != null && saved.items.isNotEmpty()) {
                val item = saved.items.getOrNull(saved.index.coerceIn(0, saved.items.lastIndex))
                com.blazemuzix.app.player.PlayerController.publish(
                    com.blazemuzix.app.player.PlayerState(
                        current = item,
                        queue = saved.items,
                        index = saved.index,
                        shuffle = saved.shuffle,
                        repeat = when (saved.repeat) {
                            "all" -> com.blazemuzix.app.player.RepeatMode.ALL
                            "one" -> com.blazemuzix.app.player.RepeatMode.ONE
                            else -> com.blazemuzix.app.player.RepeatMode.OFF
                        }
                    )
                )
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Artwork.trimMemory(this, level)
    }

    class Graph(context: Context) {
        val prefs = AppPreferences(context)
        val database = BlazeDatabase(context)
        val responseCache = ResponseCache(database)
        val network = NetworkMonitor(context)
        val http = HttpClient(network, responseCache)
        val library = LibraryRepository(database)
        val secureStore = SecureStore(context)
        val googleAuth = GoogleAuth(context)
        val spotifyAuth = SpotifyAuth(context, http, secureStore)
        val localProvider = LocalMusicProvider(context)
        val youtubeProvider = YouTubeProvider(http, BuildConfig.YOUTUBE_API_KEY)
        val spotifyProvider = SpotifyProvider(
            http,
            BuildConfig.SPOTIFY_CLIENT_ID,
            BuildConfig.SPOTIFY_CLIENT_SECRET,
            userAccessToken = { spotifyAuth.validAccessToken() },
            signedIn = { spotifyAuth.isSignedIn }
        )
        val providers = ProviderRegistry(localProvider, youtubeProvider, spotifyProvider, onlineEnabled = BuildConfig.ONLINE_PROVIDERS)
        val providerManager = ProviderManager(context, providers, googleAuth, spotifyAuth)
        val discovery = DiscoveryRepository(providers, library, network)
    }

    companion object {
        fun get(context: Context): BlazeApp = context.applicationContext as BlazeApp
        fun graph(context: Context): Graph = get(context).graph

        val isAtLeastLollipop: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
    }
}
