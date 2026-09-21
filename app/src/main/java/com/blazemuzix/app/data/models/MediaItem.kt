package com.blazemuzix.app.data.models

import org.json.JSONObject

/** Where an item comes from. Shown to the user on every card/row. */
enum class Source(val id: String, val label: String) {
    YOUTUBE("youtube", "YouTube"),
    YOUTUBE_MUSIC("youtube_music", "YouTube Music"),
    SPOTIFY("spotify", "Spotify"),
    LOCAL("local", "This device"),
    CLOUD("cloud", "BlazeMuzix Cloud");

    companion object {
        fun fromId(id: String?): Source = entries.firstOrNull { it.id == id } ?: LOCAL
    }
}

enum class MediaType(val id: String) {
    SONG("song"),
    ALBUM("album"),
    ARTIST("artist"),
    PLAYLIST("playlist"),
    VIDEO("video"),
    SHORT("short"),
    USER("user");

    val isCollection: Boolean get() = this == ALBUM || this == PLAYLIST || this == ARTIST

    companion object {
        fun fromId(id: String?): MediaType = entries.firstOrNull { it.id == id } ?: SONG
    }
}

/**
 * What BlazeMuzix is actually allowed to do with an item. The UI never shows a
 * "Play" button for something that can only be opened in the official app.
 */
enum class Playback(val id: String) {
    /** Playable directly through Android MediaPlayer (local files, official preview URLs). */
    DIRECT("direct"),
    /** Playable through the provider's official embedded web player. */
    EMBEDDED("embedded"),
    /** Can only be opened in the official app / website. */
    EXTERNAL("external"),
    /** Not playable itself (e.g. an artist); can be browsed or opened externally. */
    NONE("none");

    companion object {
        fun fromId(id: String?): Playback = entries.firstOrNull { it.id == id } ?: NONE
    }
}

/** Storage classification shown in the Library. */
enum class StorageTag { LOCAL, DOWNLOADED, SAVED, ONLINE }

data class MediaItem(
    /** Globally unique: "<source>:<type>:<providerId>". */
    val id: String,
    val source: Source,
    val type: MediaType,
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val durationMs: Long = 0L,
    /** URI that Android MediaPlayer can play (content:// for local, https:// preview). */
    val playbackUri: String? = null,
    /** Canonical https link to the item on the provider. */
    val externalUrl: String? = null,
    /** Provider-side id (YouTube video id, Spotify id, MediaStore id). */
    val providerId: String = "",
    val playback: Playback = Playback.NONE,
    /** True when [playbackUri] is only a short preview clip (e.g. Spotify 30s). */
    val previewOnly: Boolean = false,
    /** Number of tracks for collections, when known. */
    val trackCount: Int = 0,
    /** Local file path for MediaStore items (used to classify Downloads). */
    val localPath: String? = null,
    /** Seconds since epoch when the file was added to the device (local items only). */
    val dateAdded: Long = 0L,
    val genre: String? = null,
    val year: Int = 0,
    val trackNumber: Int = 0
) {
    val isLocal: Boolean get() = source == Source.LOCAL

    val storageTag: StorageTag
        get() = when {
            !isLocal -> StorageTag.ONLINE
            localPath?.contains("/Download", ignoreCase = true) == true -> StorageTag.DOWNLOADED
            else -> StorageTag.LOCAL
        }

    val canPlayDirect: Boolean get() = playback == Playback.DIRECT && !playbackUri.isNullOrEmpty()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("source", source.id)
        put("type", type.id)
        put("title", title)
        put("artist", artist)
        put("album", album)
        put("artworkUrl", artworkUrl)
        put("durationMs", durationMs)
        put("playbackUri", playbackUri)
        put("externalUrl", externalUrl)
        put("providerId", providerId)
        put("playback", playback.id)
        put("previewOnly", previewOnly)
        put("trackCount", trackCount)
        put("localPath", localPath)
        put("dateAdded", dateAdded)
        put("genre", genre)
        put("year", year)
        put("trackNumber", trackNumber)
    }

    companion object {
        fun fromJson(json: JSONObject): MediaItem = MediaItem(
            id = json.optString("id"),
            source = Source.fromId(json.optString("source")),
            type = MediaType.fromId(json.optString("type")),
            title = json.optString("title"),
            artist = json.optString("artist"),
            album = json.optStringOrNull("album"),
            artworkUrl = json.optStringOrNull("artworkUrl"),
            durationMs = json.optLong("durationMs", 0L),
            playbackUri = json.optStringOrNull("playbackUri"),
            externalUrl = json.optStringOrNull("externalUrl"),
            providerId = json.optString("providerId"),
            playback = Playback.fromId(json.optString("playback")),
            previewOnly = json.optBoolean("previewOnly", false),
            trackCount = json.optInt("trackCount", 0),
            localPath = json.optStringOrNull("localPath"),
            dateAdded = json.optLong("dateAdded", 0L),
            genre = json.optStringOrNull("genre"),
            year = json.optInt("year", 0),
            trackNumber = json.optInt("trackNumber", 0)
        )

        fun fromJsonOrNull(raw: String?): MediaItem? = try {
            if (raw.isNullOrEmpty()) null else fromJson(JSONObject(raw))
        } catch (_: Exception) {
            null
        }

        fun makeId(source: Source, type: MediaType, providerId: String) = "${source.id}:${type.id}:$providerId"
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val v = optString(key)
    return if (v.isEmpty() || v == "null") null else v
}
