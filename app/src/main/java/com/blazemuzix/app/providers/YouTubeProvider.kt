package com.blazemuzix.app.providers

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
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * YouTube through the official YouTube Data API v3 (API key from BuildConfig).
 *
 * Playback happens through YouTube's official embedded player (IFrame embed)
 * or the YouTube app. Stream URLs are never extracted; downloads are not
 * offered because YouTube's terms do not permit them for third-party apps.
 */
class YouTubeProvider(
    private val http: HttpClient,
    private val apiKey: String,
    private val region: () -> String = { Locale.getDefault().country.ifEmpty { "US" } }
) : MusicProvider {

    override val source = Source.YOUTUBE
    override val displayName = "YouTube"
    override val isConfigured: Boolean get() = apiKey.isNotBlank()
    override val supportedSearchTypes = setOf(MediaType.VIDEO, MediaType.PLAYLIST, MediaType.ARTIST, MediaType.SHORT)

    private fun requireConfigured() {
        if (!isConfigured) throw ApiException.NotConfigured(displayName)
    }

    override suspend fun search(query: String, type: MediaType?, pageToken: String?): Page<MediaItem> {
        requireConfigured()
        val ytType = when (type) {
            null -> "video,playlist,channel"
            MediaType.VIDEO, MediaType.SONG, MediaType.SHORT -> "video"
            MediaType.PLAYLIST, MediaType.ALBUM -> "playlist"
            MediaType.ARTIST -> "channel"
        }
        val params = linkedMapOf<String, String?>(
            "part" to "snippet",
            "q" to query,
            "type" to ytType,
            "maxResults" to "25",
            "safeSearch" to "moderate",
            "pageToken" to pageToken,
            "key" to apiKey
        )
        if (ytType == "video") {
            params["videoCategoryId"] = MUSIC_CATEGORY
            params["videoEmbeddable"] = "true"
            if (type == MediaType.SHORT) params["videoDuration"] = "short"
        }
        val json = http.getJson("$BASE/search?" + HttpClient.query(params), cacheTtlMs = ResponseCache.TTL_SHORT)
        val items = parseSearchItems(json.optJSONArray("items"))
        return Page(enrichDurations(items), json.optStringOrNull("nextPageToken"))
    }

    override suspend fun homeSections(): List<Section> {
        requireConfigured()
        return coroutineScope {
            val trending = async { runCatching { trending() }.getOrDefault(emptyList()) }
            val playlists = async { runCatching { musicPlaylists() }.getOrDefault(emptyList()) }
            listOfNotNull(
                trending.await().takeIf { it.isNotEmpty() }?.let {
                    Section("yt_trending", R.string.section_trending, it, SectionLayout.WIDE_CARDS, Source.YOUTUBE)
                },
                playlists.await().takeIf { it.isNotEmpty() }?.let {
                    Section("yt_playlists", R.string.section_youtube_playlists, it, SectionLayout.CARDS, Source.YOUTUBE)
                }
            )
        }
    }

    private suspend fun trending(): List<MediaItem> {
        val params = mapOf(
            "part" to "snippet,contentDetails",
            "chart" to "mostPopular",
            "videoCategoryId" to MUSIC_CATEGORY,
            "regionCode" to region(),
            "maxResults" to "25",
            "key" to apiKey
        )
        val json = http.getJson("$BASE/videos?" + HttpClient.query(params), cacheTtlMs = ResponseCache.TTL_MEDIUM)
        return parseVideos(json.optJSONArray("items"))
    }

    private suspend fun musicPlaylists(): List<MediaItem> {
        val params = mapOf(
            "part" to "snippet",
            "q" to "music playlist ${currentYear()}",
            "type" to "playlist",
            "maxResults" to "15",
            "key" to apiKey
        )
        val json = http.getJson("$BASE/search?" + HttpClient.query(params), cacheTtlMs = ResponseCache.TTL_LONG)
        return parseSearchItems(json.optJSONArray("items"))
    }

    override suspend fun shorts(pageToken: String?): Page<MediaItem> {
        requireConfigured()
        val params = mapOf(
            "part" to "snippet",
            "q" to "music #shorts",
            "type" to "video",
            "videoDuration" to "short",
            "videoCategoryId" to MUSIC_CATEGORY,
            "videoEmbeddable" to "true",
            "order" to "relevance",
            "regionCode" to region(),
            "maxResults" to "12",
            "pageToken" to pageToken,
            "key" to apiKey
        )
        val json = http.getJson("$BASE/search?" + HttpClient.query(params), cacheTtlMs = ResponseCache.TTL_SHORT)
        val items = parseSearchItems(json.optJSONArray("items")).map { it.copy(type = MediaType.SHORT, id = MediaItem.makeId(Source.YOUTUBE, MediaType.SHORT, it.providerId)) }
        return Page(enrichDurations(items), json.optStringOrNull("nextPageToken"))
    }

    override suspend fun collectionItems(item: MediaItem, pageToken: String?): Page<MediaItem> {
        requireConfigured()
        return when (item.type) {
            MediaType.PLAYLIST, MediaType.ALBUM -> {
                val params = mapOf(
                    "part" to "snippet,contentDetails",
                    "playlistId" to item.providerId,
                    "maxResults" to "50",
                    "pageToken" to pageToken,
                    "key" to apiKey
                )
                val json = http.getJson("$BASE/playlistItems?" + HttpClient.query(params), cacheTtlMs = ResponseCache.TTL_MEDIUM)
                val items = ArrayList<MediaItem>()
                val arr = json.optJSONArray("items") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val entry = arr.optJSONObject(i) ?: continue
                    val snippet = entry.optJSONObject("snippet") ?: continue
                    val videoId = entry.optJSONObject("contentDetails")?.optString("videoId")
                        ?: snippet.optJSONObject("resourceId")?.optString("videoId") ?: continue
                    if (videoId.isEmpty() || snippet.optString("title") == "Private video" || snippet.optString("title") == "Deleted video") continue
                    items.add(video(videoId, snippet, 0L, snippet.optString("videoOwnerChannelTitle").ifEmpty { snippet.optString("channelTitle") }))
                }
                Page(enrichDurations(items), json.optStringOrNull("nextPageToken"))
            }
            MediaType.ARTIST -> {
                val params = mapOf(
                    "part" to "snippet",
                    "channelId" to item.providerId,
                    "type" to "video",
                    "order" to "viewCount",
                    "maxResults" to "25",
                    "pageToken" to pageToken,
                    "key" to apiKey
                )
                val json = http.getJson("$BASE/search?" + HttpClient.query(params), cacheTtlMs = ResponseCache.TTL_MEDIUM)
                Page(enrichDurations(parseSearchItems(json.optJSONArray("items"))), json.optStringOrNull("nextPageToken"))
            }
            else -> Page.empty()
        }
    }

    // ------------------------------------------------------------- parsing

    private fun parseSearchItems(arr: JSONArray?): List<MediaItem> {
        if (arr == null) return emptyList()
        val list = ArrayList<MediaItem>(arr.length())
        for (i in 0 until arr.length()) {
            val entry = arr.optJSONObject(i) ?: continue
            val idObj = entry.optJSONObject("id") ?: continue
            val snippet = entry.optJSONObject("snippet") ?: continue
            when (idObj.optString("kind")) {
                "youtube#video" -> list.add(video(idObj.optString("videoId"), snippet, 0L))
                "youtube#playlist" -> {
                    val id = idObj.optString("playlistId")
                    list.add(
                        MediaItem(
                            id = MediaItem.makeId(Source.YOUTUBE, MediaType.PLAYLIST, id),
                            source = Source.YOUTUBE,
                            type = MediaType.PLAYLIST,
                            title = snippet.optString("title"),
                            artist = snippet.optString("channelTitle"),
                            artworkUrl = thumbnail(snippet),
                            externalUrl = "https://www.youtube.com/playlist?list=$id",
                            providerId = id,
                            playback = Playback.EMBEDDED
                        )
                    )
                }
                "youtube#channel" -> {
                    val id = idObj.optString("channelId")
                    list.add(
                        MediaItem(
                            id = MediaItem.makeId(Source.YOUTUBE, MediaType.ARTIST, id),
                            source = Source.YOUTUBE,
                            type = MediaType.ARTIST,
                            title = snippet.optString("title"),
                            artist = "Channel",
                            artworkUrl = thumbnail(snippet),
                            externalUrl = "https://www.youtube.com/channel/$id",
                            providerId = id,
                            playback = Playback.NONE
                        )
                    )
                }
            }
        }
        return list
    }

    private fun parseVideos(arr: JSONArray?): List<MediaItem> {
        if (arr == null) return emptyList()
        val list = ArrayList<MediaItem>(arr.length())
        for (i in 0 until arr.length()) {
            val entry = arr.optJSONObject(i) ?: continue
            val snippet = entry.optJSONObject("snippet") ?: continue
            val duration = parseIsoDuration(entry.optJSONObject("contentDetails")?.optString("duration"))
            list.add(video(entry.optString("id"), snippet, duration))
        }
        return list
    }

    private fun video(videoId: String, snippet: JSONObject, durationMs: Long, artistOverride: String? = null): MediaItem =
        MediaItem(
            id = MediaItem.makeId(Source.YOUTUBE, MediaType.VIDEO, videoId),
            source = Source.YOUTUBE,
            type = MediaType.VIDEO,
            title = decodeHtml(snippet.optString("title")),
            artist = decodeHtml(artistOverride ?: snippet.optString("channelTitle")),
            artworkUrl = thumbnail(snippet),
            durationMs = durationMs,
            externalUrl = "https://www.youtube.com/watch?v=$videoId",
            providerId = videoId,
            playback = Playback.EMBEDDED
        )

    /** Adds durations with a single batched videos.list call (up to 50 ids). */
    private suspend fun enrichDurations(items: List<MediaItem>): List<MediaItem> {
        val ids = items.filter { it.type == MediaType.VIDEO || it.type == MediaType.SHORT }.map { it.providerId }.distinct().take(50)
        if (ids.isEmpty()) return items
        val json = try {
            http.getJson(
                "$BASE/videos?" + HttpClient.query(mapOf("part" to "contentDetails", "id" to ids.joinToString(","), "key" to apiKey)),
                cacheTtlMs = ResponseCache.TTL_LONG
            )
        } catch (_: ApiException) {
            return items
        }
        val durations = HashMap<String, Long>()
        val arr = json.optJSONArray("items") ?: return items
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            durations[e.optString("id")] = parseIsoDuration(e.optJSONObject("contentDetails")?.optString("duration"))
        }
        return items.map { item -> durations[item.providerId]?.let { item.copy(durationMs = it) } ?: item }
    }

    private fun thumbnail(snippet: JSONObject): String? {
        val thumbs = snippet.optJSONObject("thumbnails") ?: return null
        return (thumbs.optJSONObject("high") ?: thumbs.optJSONObject("medium") ?: thumbs.optJSONObject("default"))?.optString("url")
    }

    private fun currentYear(): Int = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)

    companion object {
        private const val BASE = "https://www.googleapis.com/youtube/v3"
        private const val MUSIC_CATEGORY = "10"

        private val ISO_DURATION = Regex("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?")

        fun parseIsoDuration(value: String?): Long {
            if (value.isNullOrEmpty()) return 0L
            val m = ISO_DURATION.matchEntire(value) ?: return 0L
            val h = m.groupValues[1].toLongOrNull() ?: 0L
            val min = m.groupValues[2].toLongOrNull() ?: 0L
            val s = m.groupValues[3].toLongOrNull() ?: 0L
            return ((h * 60 + min) * 60 + s) * 1000
        }

        fun decodeHtml(text: String): String = text
            .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")

        fun embedUrl(videoId: String, autoplay: Boolean): String =
            "https://www.youtube.com/embed/$videoId?playsinline=1&rel=0&modestbranding=1&autoplay=${if (autoplay) 1 else 0}"
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).takeIf { it.isNotEmpty() && it != "null" }
}
