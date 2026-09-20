package com.blazemuzix.app

import android.content.Context
import android.os.Build
import androidx.multidex.MultiDexApplication
import com.blazemuzix.app.data.cache.ResponseCache
import com.blazemuzix.app.data.db.BlazeDatabase
import com.blazemuzix.app.data.prefs.AppPreferences
import com.blazemuzix.app.data.repository.DiscoveryRepository
import com.blazemuzix.app.data.repository.LibraryRepository
import com.blazemuzix.app.network.HttpClient
import com.blazemuzix.app.network.NetworkMonitor
import com.blazemuzix.app.providers.LocalMusicProvider
import com.blazemuzix.app.providers.ProviderRegistry
import com.blazemuzix.app.providers.SpotifyProvider
import com.blazemuzix.app.providers.YouTubeProvider
import com.blazemuzix.app.utils.Artwork
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

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
        graph.prefs.ensureDefaults()
        graph.prefs.applyTheme()
        appScope.launch { graph.library.warmUp() }
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
        val localProvider = LocalMusicProvider(context)
        val youtubeProvider = YouTubeProvider(http, BuildConfig.YOUTUBE_API_KEY)
        val spotifyProvider = SpotifyProvider(http, BuildConfig.SPOTIFY_CLIENT_ID, BuildConfig.SPOTIFY_CLIENT_SECRET)
        val providers = ProviderRegistry(localProvider, youtubeProvider, spotifyProvider)
        val discovery = DiscoveryRepository(providers, library, network)
    }

    companion object {
        fun get(context: Context): BlazeApp = context.applicationContext as BlazeApp
        fun graph(context: Context): Graph = get(context).graph

        val isAtLeastLollipop: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
    }
}
