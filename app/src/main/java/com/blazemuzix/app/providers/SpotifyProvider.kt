package com.blazemuzix.app.providers

import android.util.Base64
import com.blazemuzix.app.R
import com.blazemuzix.app.data.cache.ResponseCache
import com.blazemuzix.app.data.models.MediaItem
import com.blazemuzix.app.data.models.MediaType
import com.blazemuzix.app.data.models.Page
import com.blazemuzix.app.data.models.Playback
import com.blazemuzix.app.data.models.Section
import com.blazemuzix.app.data.models.SectionLayout
import com.blazemuzix.app.data.models.Source
import com.blazemuzix.app.network.ApiException
import com.blazemuzix.app.network.HttpClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * Spotify through the official Web API using the Client Credentials flow.
 *
 * Catalog browsing/search is fully supported. Full-length playback requires the
 * Spotify app, so tracks are exposed as "Open in Spotify"; when Spotify supplies
 * an official 30-second preview URL, BlazeMuzix can play that preview directly.
 * Downloads are never offered (not permitted by Spotify's terms).
 */
class SpotifyProvider(
    private val http: HttpClient,
    private val clientId: String,
    private val clientSecret: String,
    private val market: () -> String = { Locale.getDefault().country.ifEmpty { "US" } }
) : MusicProvider {

    override val source = Source.SPOTIFY
    override val displayName = "Spotify"
    override val isConfigured: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()
    override val supportedSearchTypes = setOf(MediaType.SONG, MediaType.ALBUM, MediaType.ARTIST, MediaType.PLAYLIST)

    private val tokenMutex = Mutex()
    @Volatile private var accessToken: String? = null
    @Volatile private var tokenExpiresAt: Long = 0L

    private fun requireConfigured() {
        if (!isConfigured) throw ApiException.NotConfigured(displayName)
    }

    private suspend fun token(): String {
        accessToken?.let { if (System.currentTimeMillis() < tokenExpiresAt - 30_000) return it }
        return tokenMutex.withLock {
            accessToken?.let { if (System.currentTimeMillis() < tokenExpiresAt - 30_000) return@withLock it }
            val basic = Base64.encodeToString("$clientId:$clientSecret".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val json = try {
                http.postForm(TOKEN_URL, mapOf("Authorization" to "Basic $basic"), mapOf("grant_type" to "client_credentials"))
            } catch (e: ApiException.InvalidRequest) {
                throw ApiException.Unauthorized(displayName)
            }
            val token = json.optString("access_token")
            if (token.isEmpty()) throw ApiException.Unauthorized(displayName)
            accessToken = token
            tokenExpiresAt = System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000
            token
        }
    }

    private suspend fun api(path: String, params: Map<String, String?>, ttl: Long): JSONObject {
        requireConfigured()
        val url = "$API/$path?" + HttpClient.query(params)
        val headers = mapOf("Authorization" to "Bearer ${token()}")
        return try {
            http.getJson(url, headers, cacheTtlMs = ttl, cacheKey = "spotify:$url")
        } catch (e: ApiException.Unauthorized) {
            // Token may have been revoked; refresh once.
            accessToken = null
            http.getJson(url, mapOf("Authorization" to "Bearer ${token()}"), cacheTtlMs = ttl, cacheKey = "spotify:$url")
        }
    }

    override suspend fun search(query: String, type: MediaType?, pageToken: String?): Page<MediaItem> {
        requireConfigured()
        val types = when (type) {
            null -> "track,album,artist,playlist"
            MediaType.SONG -> "track"
            MediaType.ALBUM -> "album"
            MediaType.ARTIST -> "artist"
            MediaType.PLAYLIST -> "playlist"
            else -> return Page.empty()
        }
        val offset = pageToken?.toIntOrNull() ?: 0
        val limit = if (type == null) 10 else 20
        val json = api(
            "search",
            mapOf("q" to query, "type" to types, "limit" to limit.toString(), "offset" to offset.toString(), "market" to market()),
            ResponseCache.TTL_SHORT
        )
        val items = ArrayList<MediaItem>()
        var hasMore = false
        for (key in listOf("tracks", "albums", "artists", "playlists")) {
            val block = json.optJSONObject(key) ?: continue
            val arr = block.optJSONArray("items") ?: continue
            if (!block.isNull("next") && block.optString("next").isNotEmpty()) hasMore = true
            items.addAll(parseItems(arr, key))
        }
        return Page(items, if (hasMore && offset + limit < MAX_OFFSET) (offset + limit).toString() else null)
    }

    override suspend fun homeSections(): List<Section> {
        requireConfigured()
        return coroutineScope {
            val releases = async { runCatching { newReleases() }.getOrDefault(emptyList()) }
            val popular = async { runCatching { popularTracks() }.getOrDefault(emptyList()) }
            listOfNotNull(
                releases.await().takeIf { it.isNotEmpty() }?.let {
                    Section("sp_new_releases", R.string.section_new_releases, it, SectionLayout.CARDS, Source.SPOTIFY)
                },
                popular.await().takeIf { it.isNotEmpty() }?.let {
                    Section("sp_popular", R.string.section_popular, it, SectionLayout.ROWS, Source.SPOTIFY)
                }
            )
        }
    }

    private suspend fun newReleases(): List<MediaItem> {
        val json = api("browse/new-releases", mapOf("limit" to "20", "country" to market()), ResponseCache.TTL_LONG)
        return parseItems(json.optJSONObject("albums")?.optJSONArray("items"), "albums")
    }

    private suspend fun popularTracks(): List<MediaItem> {
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val json = api(
            "search",
            mapOf("q" to "year:$year", "type" to "track", "limit" to "10", "market" to market()),
            ResponseCache.TTL_LONG
        )
        return parseItems(json.optJSONObject("tracks")?.optJSONArray("items"), "tracks")
    }

    override suspend fun collectionItems(item: MediaItem, pageToken: String?): Page<MediaItem> {
        requireConfigured()
        val offset = pageToken?.toIntOrNull() ?: 0
        return when (item.type) {
            MediaType.ALBUM -> {
                val json = api("albums/${item.providerId}/tracks", mapOf("limit" to "50", "offset" to offset.toString(), "market" to market()), ResponseCache.TTL_LONG)
                val tracks = parseItems(json.optJSONArray("items"), "tracks").map {
                    it.copy(album = item.title, artworkUrl = it.artworkUrl ?: item.artworkUrl)
                }
                Page(tracks, nextOffset(json, offset, 50))
            }
            MediaType.PLAYLIST -> {
                val json = api(
                    "playlists/${item.providerId}/tracks",
                    mapOf("limit" to "50", "offset" to offset.toString(), "market" to market(), "fields" to "next,items(track(id,name,duration_ms,preview_url,external_urls,artists(name),album(name,images)))"),
                    ResponseCache.TTL_MEDIUM
                )
                val arr = json.optJSONArray("items") ?: JSONArray()
                val tracks = ArrayList<MediaItem>()
                for (i in 0 until arr.length()) {
                    val track = arr.optJSONObject(i)?.optJSONObject("track") ?: continue
                    parseTrack(track)?.let(tracks::add)
                }
                Page(tracks, nextOffset(json, offset, 50))
            }
            MediaType.ARTIST -> {
                val json = api("artists/${item.providerId}/top-tracks", mapOf("market" to market()), ResponseCache.TTL_LONG)
                Page(parseItems(json.optJSONArray("tracks"), "tracks"), null)
            }
            else -> Page.empty()
        }
    }

    private fun nextOffset(json: JSONObject, offset: Int, limit: Int): String? =
        if (!json.isNull("next") && json.optString("next").isNotEmpty()) (offset + limit).toString() else null

    // ------------------------------------------------------------- parsing

    private fun parseItems(arr: JSONArray?, kind: String): List<MediaItem> {
        if (arr == null) return emptyList()
        val list = ArrayList<MediaItem>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val item = when (kind) {
                "tracks" -> parseTrack(obj)
                "albums" -> parseAlbum(obj)
                "artists" -> parseArtist(obj)
                "playlists" -> parsePlaylist(obj)
                else -> null
            }
            item?.let(list::add)
        }
        return list
    }

    private fun parseTrack(t: JSONObject): MediaItem? {
        val id = t.optString("id").takeIf { it.isNotEmpty() } ?: return null
        val album = t.optJSONObject("album")
        val preview = t.optStringOrNull("preview_url")
        return MediaItem(
            id = MediaItem.makeId(Source.SPOTIFY, MediaType.SONG, id),
            source = Source.SPOTIFY,
            type = MediaType.SONG,
            title = t.optString("name"),
            artist = artists(t.optJSONArray("artists")),
            album = album?.optString("name"),
            artworkUrl = image(album?.optJSONArray("images")),
            durationMs = t.optLong("duration_ms", 0L),
            playbackUri = preview,
            externalUrl = t.optJSONObject("external_urls")?.optString("spotify")?.ifEmpty { null } ?: "https://open.spotify.com/track/$id",
            providerId = id,
            playback = if (preview != null) Playback.DIRECT else Playback.EXTERNAL,
            previewOnly = preview != null
        )
    }

    private fun parseAlbum(a: JSONObject): MediaItem? {
        val id = a.optString("id").takeIf { it.isNotEmpty() } ?: return null
        return MediaItem(
            id = MediaItem.makeId(Source.SPOTIFY, MediaType.ALBUM, id),
            source = Source.SPOTIFY,
            type = MediaType.ALBUM,
            title = a.optString("name"),
            artist = artists(a.optJSONArray("artists")),
            artworkUrl = image(a.optJSONArray("images")),
            externalUrl = a.optJSONObject("external_urls")?.optString("spotify")?.ifEmpty { null } ?: "https://open.spotify.com/album/$id",
            providerId = id,
            playback = Playback.EXTERNAL,
            trackCount = a.optInt("total_tracks", 0)
        )
    }

    private fun parseArtist(a: JSONObject): MediaItem? {
        val id = a.optString("id").takeIf { it.isNotEmpty() } ?: return null
        return MediaItem(
            id = MediaItem.makeId(Source.SPOTIFY, MediaType.ARTIST, id),
            source = Source.SPOTIFY,
            type = MediaType.ARTIST,
            title = a.optString("name"),
            artist = a.optJSONArray("genres")?.let { g -> (0 until minOf(2, g.length())).joinToString(", ") { g.optString(it) } }?.ifEmpty { "Artist" } ?: "Artist",
            artworkUrl = image(a.optJSONArray("images")),
            externalUrl = a.optJSONObject("external_urls")?.optString("spotify")?.ifEmpty { null } ?: "https://open.spotify.com/artist/$id",
            providerId = id,
            playback = Playback.NONE
        )
    }

    private fun parsePlaylist(p: JSONObject): MediaItem? {
        val id = p.optString("id").takeIf { it.isNotEmpty() } ?: return null
        return MediaItem(
            id = MediaItem.makeId(Source.SPOTIFY, MediaType.PLAYLIST, id),
            source = Source.SPOTIFY,
            type = MediaType.PLAYLIST,
            title = p.optString("name"),
            artist = p.optJSONObject("owner")?.optString("display_name")?.ifEmpty { null } ?: "Playlist",
            artworkUrl = image(p.optJSONArray("images")),
            externalUrl = p.optJSONObject("external_urls")?.optString("spotify")?.ifEmpty { null } ?: "https://open.spotify.com/playlist/$id",
            providerId = id,
            playback = Playback.EXTERNAL,
            trackCount = p.optJSONObject("tracks")?.optInt("total", 0) ?: 0
        )
    }

    private fun artists(arr: JSONArray?): String {
        if (arr == null || arr.length() == 0) return "Unknown artist"
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") }.filter { it.isNotEmpty() }.joinToString(", ")
    }

    /** Prefer the ~300px image; the largest one is only used as a fallback. */
    private fun image(images: JSONArray?): String? {
        if (images == null || images.length() == 0) return null
        var best: String? = null
        var bestDiff = Int.MAX_VALUE
        for (i in 0 until images.length()) {
            val img = images.optJSONObject(i) ?: continue
            val w = img.optInt("width", 300)
            val diff = kotlin.math.abs(w - 300)
            if (diff < bestDiff) {
                bestDiff = diff
                best = img.optString("url")
            }
        }
        return best
    }

    companion object {
        private const val API = "https://api.spotify.com/v1"
        private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
        /** Spotify's search endpoint caps offset+limit at 1000. */
        private const val MAX_OFFSET = 1000

        fun embedUrl(type: MediaType, id: String): String {
            val path = when (type) {
                MediaType.ALBUM -> "album"
                MediaType.PLAYLIST -> "playlist"
                MediaType.ARTIST -> "artist"
                else -> "track"
            }
            return "https://open.spotify.com/embed/$path/$id?utm_source=generator&theme=0"
        }
    }
}
