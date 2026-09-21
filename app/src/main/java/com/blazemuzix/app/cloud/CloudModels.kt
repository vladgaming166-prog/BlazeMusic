package com.blazemuzix.app.cloud

import com.blazemuzix.app.BuildConfig
import com.blazemuzix.app.data.models.MediaItem
import com.blazemuzix.app.data.models.MediaType
import com.blazemuzix.app.data.models.Playback
import com.blazemuzix.app.data.models.Source
import org.json.JSONObject

object CloudConfig {
    val url: String get() = BuildConfig.SUPABASE_URL.trimEnd('/')
    val anonKey: String get() = BuildConfig.SUPABASE_ANON_KEY
    val isConfigured: Boolean get() = url.startsWith("https://") && anonKey.isNotBlank()
}

data class CloudProfile(
    val id: String,
    val username: String?,
    val email: String?,
    val avatar: String?,
    val bio: String?,
    val publicProfile: Boolean,
    val createdAt: String?
) {
    fun toMediaItem(): MediaItem = MediaItem(
        id = MediaItem.makeId(Source.CLOUD, MediaType.USER, id),
        source = Source.CLOUD,
        type = MediaType.USER,
        title = username ?: email ?: "User",
        artist = bio?.takeIf { it.isNotBlank() } ?: "BlazeMuzix Cloud",
        artworkUrl = avatar,
        providerId = id,
        playback = Playback.NONE,
        externalUrl = null
    )

    companion object {
        fun from(json: JSONObject): CloudProfile = CloudProfile(
            id = json.optString("id"),
            username = json.optString("username").takeIf { it.isNotBlank() },
            email = json.optString("email").takeIf { it.isNotBlank() },
            avatar = json.optString("avatar").takeIf { it.isNotBlank() },
            bio = json.optString("bio").takeIf { it.isNotBlank() },
            publicProfile = json.optBoolean("public_profile", true),
            createdAt = json.optString("created_at").takeIf { it.isNotBlank() }
        )
    }
}

data class CloudTrack(
    val id: String,
    val ownerId: String,
    val title: String,
    val artist: String,
    val description: String?,
    val album: String?,
    val genre: String?,
    val year: Int,
    val audioUrl: String,
    val coverUrl: String?,
    val durationMs: Long,
    val visibility: String,
    val playCount: Long,
    val likeCount: Long,
    val createdAt: String?
) {
    fun toMediaItem(): MediaItem = MediaItem(
        id = MediaItem.makeId(Source.CLOUD, MediaType.SONG, id),
        source = Source.CLOUD,
        type = MediaType.SONG,
        title = title,
        artist = artist,
        album = album,
        artworkUrl = coverUrl,
        durationMs = durationMs,
        playbackUri = audioUrl,
        providerId = id,
        playback = Playback.DIRECT,
        genre = genre,
        year = year,
        dateAdded = 0L
    )

    companion object {
        fun from(json: JSONObject): CloudTrack = CloudTrack(
            id = json.optString("id"),
            ownerId = json.optString("owner_id"),
            title = json.optString("title"),
            artist = json.optString("artist").ifBlank { "Unknown artist" },
            description = json.optString("description").takeIf { it.isNotBlank() },
            album = json.optString("album").takeIf { it.isNotBlank() },
            genre = json.optString("genre").takeIf { it.isNotBlank() },
            year = json.optInt("year", 0),
            audioUrl = json.optString("audio_url"),
            coverUrl = json.optString("cover_url").takeIf { it.isNotBlank() },
            durationMs = json.optLong("duration_ms", 0L),
            visibility = json.optString("visibility", "public"),
            playCount = json.optLong("play_count", 0L),
            likeCount = json.optLong("like_count", 0L),
            createdAt = json.optString("created_at").takeIf { it.isNotBlank() }
        )
    }
}

data class CloudPlaylist(
    val id: String,
    val ownerId: String,
    val name: String,
    val description: String?,
    val coverUrl: String?,
    val public: Boolean,
    val createdAt: String?,
    val trackCount: Int = 0
) {
    fun toMediaItem(): MediaItem = MediaItem(
        id = MediaItem.makeId(Source.CLOUD, MediaType.PLAYLIST, id),
        source = Source.CLOUD,
        type = MediaType.PLAYLIST,
        title = name,
        artist = "BlazeMuzix Cloud",
        artworkUrl = coverUrl,
        providerId = id,
        playback = Playback.DIRECT,
        trackCount = trackCount
    )

    companion object {
        fun from(json: JSONObject): CloudPlaylist = CloudPlaylist(
            id = json.optString("id"),
            ownerId = json.optString("owner_id"),
            name = json.optString("name"),
            description = json.optString("description").takeIf { it.isNotBlank() },
            coverUrl = json.optString("cover_url").takeIf { it.isNotBlank() },
            public = json.optBoolean("public", true),
            createdAt = json.optString("created_at").takeIf { it.isNotBlank() }
        )
    }
}

data class CloudComment(
    val id: String,
    val userId: String,
    val trackId: String,
    val body: String,
    val createdAt: String?,
    val authorName: String? = null
)

data class CloudShort(
    val id: String,
    val ownerId: String,
    val title: String,
    val description: String?,
    val videoUrl: String,
    val thumbUrl: String?,
    val durationMs: Long,
    val visibility: String,
    val createdAt: String?
) {
    fun toMediaItem(): MediaItem = MediaItem(
        id = MediaItem.makeId(Source.CLOUD, MediaType.SHORT, id),
        source = Source.CLOUD,
        type = MediaType.SHORT,
        title = title,
        artist = "BlazeMuzix Cloud",
        artworkUrl = thumbUrl,
        durationMs = durationMs,
        playbackUri = videoUrl,
        providerId = id,
        playback = Playback.DIRECT,
        externalUrl = videoUrl
    )

    companion object {
        fun from(json: JSONObject): CloudShort = CloudShort(
            id = json.optString("id"),
            ownerId = json.optString("owner_id"),
            title = json.optString("title"),
            description = json.optString("description").takeIf { it.isNotBlank() },
            videoUrl = json.optString("video_url"),
            thumbUrl = json.optString("thumb_url").takeIf { it.isNotBlank() },
            durationMs = json.optLong("duration_ms", 0L),
            visibility = json.optString("visibility", "public"),
            createdAt = json.optString("created_at").takeIf { it.isNotBlank() }
        )
    }
}

object PasswordRules {
    fun validate(password: String, confirm: String): String? {
        if (password.length < 8) return "Password must be at least 8 characters."
        if (!password.any { it.isLetter() } || !password.any { it.isDigit() }) {
            return "Password must include a letter and a number."
        }
        if (password != confirm) return "Passwords do not match."
        return null
    }

    fun username(value: String): String? {
        val t = value.trim()
        if (t.length !in 3..24) return "Username must be 3–24 characters."
        if (!t.matches(Regex("^[A-Za-z0-9_]+$"))) return "Username can only use letters, numbers and underscore."
        return null
    }

    fun email(value: String): String? {
        val t = value.trim()
        if (t.length < 5 || !t.contains("@") || !t.contains(".")) return "Enter a valid email address."
        return null
    }

    fun strength(password: String): Int {
        var score = 0
        if (password.length >= 8) score++
        if (password.length >= 12) score++
        if (password.any { it.isLowerCase() } && password.any { it.isUpperCase() }) score++
        if (password.any { it.isDigit() }) score++
        if (password.any { !it.isLetterOrDigit() }) score++
        return score.coerceIn(0, 4)
    }

    fun strengthLabel(score: Int): String = when (score) {
        0, 1 -> "Weak password"
        2 -> "Fair password"
        3 -> "Good password"
        else -> "Strong password"
    }
}
