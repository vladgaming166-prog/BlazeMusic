package com.blazemuzix.app.cloud

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.webkit.MimeTypeMap
import com.blazemuzix.app.auth.CloudAuth
import com.blazemuzix.app.network.ApiException
import com.blazemuzix.app.network.HttpClient
import org.json.JSONObject
import java.util.UUID

/**
 * BlazeMuzix Cloud API (Supabase REST + Storage). Uses the user JWT or the
 * public anon key. Never a service-role key.
 */
class CloudRepository(
    private val context: Context,
    private val client: CloudClient,
    private val auth: CloudAuth,
    val cache: CloudCache
) {
    val configured: Boolean get() = CloudConfig.isConfigured
    val signedIn: Boolean get() = auth.isSignedIn()

    fun requireConfigured() {
        if (!configured) throw ApiException.NotConfigured("BlazeMuzix Cloud")
    }

    fun requireSignedIn() {
        requireConfigured()
        if (auth.accessToken().isNullOrEmpty()) throw ApiException.Unauthorized("BlazeMuzix Cloud")
    }

    fun myId(): String? = auth.current?.cloudId ?: auth.current?.id

    suspend fun myProfile(): CloudProfile? {
        val id = myId() ?: return null
        return profile(id)
    }

    suspend fun profile(id: String): CloudProfile? {
        requireConfigured()
        val arr = client.getArray("/rest/v1/profiles?id=eq.${enc(id)}&select=*")
        return if (arr.length() == 0) null else CloudProfile.from(arr.getJSONObject(0))
    }

    suspend fun searchUsers(query: String): List<CloudProfile> {
        requireConfigured()
        val q = like(query)
        val arr = client.getArray("/rest/v1/profiles?or=(username.ilike.$q,email.ilike.$q)&public_profile=eq.true&select=*&limit=20")
        return (0 until arr.length()).map { CloudProfile.from(arr.getJSONObject(it)) }
    }

    suspend fun updateProfile(username: String?, bio: String?, publicProfile: Boolean?): CloudProfile {
        requireSignedIn()
        val id = myId() ?: throw ApiException.Unauthorized("BlazeMuzix Cloud")
        username?.let { PasswordRules.username(it)?.let { err -> throw ApiException.InvalidRequest(err) } }
        val body = JSONObject()
        if (username != null) body.put("username", username.trim())
        if (bio != null) body.put("bio", bio.trim())
        if (publicProfile != null) body.put("public_profile", publicProfile)
        val json = client.patch("/rest/v1/profiles?id=eq.${enc(id)}", body)
        val obj = client.firstObject(json) ?: throw ApiException.Parse()
        val profile = CloudProfile.from(obj)
        auth.applyProfile(profile)
        return profile
    }

    suspend fun uploadAvatar(uri: Uri): String {
        requireSignedIn()
        val id = myId() ?: throw ApiException.Unauthorized("BlazeMuzix Cloud")
        val bytes = readBytes(uri, MAX_IMAGE_BYTES)
        val ext = extension(uri, "jpg")
        val path = "$id/avatar-${System.currentTimeMillis()}.$ext"
        val url = client.upload("avatars", path, bytes, mimeOf(ext, "image/jpeg"), null)
        updateProfileAvatar(url)
        return url
    }

    private suspend fun updateProfileAvatar(url: String) {
        val id = myId() ?: return
        client.patch("/rest/v1/profiles?id=eq.${enc(id)}", JSONObject().put("avatar", url))
        myProfile()?.let { auth.applyProfile(it) }
    }

    suspend fun recentTracks(limit: Int = 24): List<CloudTrack> = listTracks("order=created_at.desc", limit)
    suspend fun trendingTracks(limit: Int = 24): List<CloudTrack> = listTracks("order=like_count.desc,play_count.desc", limit)

    suspend fun tracksByGenre(genre: String, limit: Int = 24): List<CloudTrack> {
        requireConfigured()
        val arr = client.getArray("/rest/v1/tracks?visibility=eq.public&genre=eq.${enc(genre)}&select=*&order=created_at.desc&limit=$limit", cacheTtlMs = 30_000)
        return parseTracks(arr)
    }

    suspend fun genres(): List<String> {
        requireConfigured()
        val arr = client.getArray("/rest/v1/tracks?visibility=eq.public&select=genre&limit=200", cacheTtlMs = 60_000)
        val set = LinkedHashSet<String>()
        for (i in 0 until arr.length()) {
            val g = arr.getJSONObject(i).optString("genre")
            if (g.isNotBlank()) set.add(g)
        }
        return set.toList()
    }

    suspend fun newArtists(limit: Int = 20): List<CloudTrack> {
        val recent = recentTracks(80)
        val seen = HashSet<String>()
        return recent.filter { seen.add(it.artist.lowercase()) }.take(limit)
    }

    suspend fun searchTracks(query: String): List<CloudTrack> {
        requireConfigured()
        val q = like(query)
        val arr = client.getArray("/rest/v1/tracks?or=(title.ilike.$q,artist.ilike.$q,album.ilike.$q)&visibility=eq.public&select=*&limit=30")
        return parseTracks(arr)
    }

    suspend fun track(id: String): CloudTrack? {
        requireConfigured()
        val arr = client.getArray("/rest/v1/tracks?id=eq.${enc(id)}&select=*")
        if (arr.length() == 0) return null
        return CloudTrack.from(arr.getJSONObject(0)).let { cache.applyLocal(it) }
    }

    suspend fun myUploads(): List<CloudTrack> {
        requireSignedIn()
        val id = myId() ?: return emptyList()
        val arr = client.getArray("/rest/v1/tracks?owner_id=eq.${enc(id)}&select=*&order=created_at.desc")
        return parseTracks(arr)
    }

    suspend fun tracksByOwner(ownerId: String): List<CloudTrack> {
        requireConfigured()
        val arr = client.getArray("/rest/v1/tracks?owner_id=eq.${enc(ownerId)}&visibility=eq.public&select=*&order=created_at.desc")
        return parseTracks(arr)
    }

    suspend fun recommended(limit: Int = 20): List<CloudTrack> {
        val trending = runCatching { trendingTracks(limit) }.getOrDefault(emptyList())
        if (trending.isNotEmpty()) return trending
        return recentTracks(limit)
    }

    private suspend fun listTracks(order: String, limit: Int): List<CloudTrack> {
        requireConfigured()
        val arr = client.getArray("/rest/v1/tracks?visibility=eq.public&select=*&$order&limit=$limit", cacheTtlMs = 20_000)
        return parseTracks(arr)
    }

    private fun parseTracks(arr: org.json.JSONArray): List<CloudTrack> =
        (0 until arr.length()).map { cache.applyLocal(CloudTrack.from(arr.getJSONObject(it))) }

    data class UploadDraft(
        val audioUri: Uri,
        val coverUri: Uri?,
        val title: String,
        val artist: String,
        val description: String?,
        val album: String?,
        val genre: String?,
        val year: Int,
        val visibility: String = "public"
    )

    data class LocalFileInfo(
        val name: String,
        val sizeBytes: Long,
        val mime: String?,
        val durationMs: Long
    )

    fun inspectAudio(uri: Uri): LocalFileInfo {
        val cr = context.contentResolver
        var size = 0L
        var name = "audio"
        cr.query(uri, null, null, null, null)?.use { c ->
            val nameIdx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            val sizeIdx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                if (nameIdx >= 0) name = c.getString(nameIdx) ?: name
                if (sizeIdx >= 0) size = c.getLong(sizeIdx)
            }
        }
        val mime = cr.getType(uri)
        val duration = durationOf(uri)
        return LocalFileInfo(name, size, mime, duration)
    }

    fun validateAudio(info: LocalFileInfo) {
        if (info.sizeBytes <= 0L) throw ApiException.InvalidRequest("The audio file could not be read.")
        if (info.sizeBytes > MAX_AUDIO_BYTES) {
            throw ApiException.InvalidRequest("Audio files must be ${MAX_AUDIO_BYTES / (1024 * 1024)} MB or smaller.")
        }
        val mime = (info.mime ?: "").lowercase()
        val name = info.name.lowercase()
        val ok = mime.startsWith("audio/") ||
            name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".m4a") ||
            name.endsWith(".aac") || name.endsWith(".ogg") || name.endsWith(".oga") || name.endsWith(".flac")
        if (!ok) throw ApiException.InvalidRequest("Use MP3, WAV, M4A/AAC or OGG audio.")
    }

    suspend fun uploadTrack(draft: UploadDraft, onProgress: (Int) -> Unit): CloudTrack {
        requireSignedIn()
        val owner = myId() ?: throw ApiException.Unauthorized("BlazeMuzix Cloud")
        val title = draft.title.trim()
        if (title.isEmpty()) throw ApiException.InvalidRequest("Enter a track name.")
        val info = inspectAudio(draft.audioUri)
        validateAudio(info)
        val audioBytes = readBytes(draft.audioUri, MAX_AUDIO_BYTES)
        val ext = extension(draft.audioUri, "mp3")
        val trackKey = UUID.randomUUID().toString()
        val audioPath = "$owner/$trackKey.$ext"
        onProgress(5)
        val audioUrl = client.upload("audio", audioPath, audioBytes, mimeOf(ext, "audio/mpeg")) { p ->
            onProgress(5 + (p * 0.7).toInt())
        }
        var coverUrl: String? = null
        if (draft.coverUri != null) {
            val coverBytes = readBytes(draft.coverUri, MAX_IMAGE_BYTES)
            val cExt = extension(draft.coverUri, "jpg")
            coverUrl = client.upload("covers", "$owner/$trackKey.$cExt", coverBytes, mimeOf(cExt, "image/jpeg")) { p ->
                onProgress(75 + (p * 0.15).toInt())
            }
        }
        onProgress(92)
        val body = JSONObject()
            .put("title", title)
            .put("artist", draft.artist.trim().ifBlank { auth.current?.displayName ?: "Unknown artist" })
            .put("description", draft.description?.trim().orEmpty())
            .put("album", draft.album?.trim().orEmpty())
            .put("genre", draft.genre?.trim().orEmpty())
            .put("year", if (draft.year > 0) draft.year else JSONObject.NULL)
            .put("audio_url", audioUrl)
            .put("cover_url", coverUrl ?: JSONObject.NULL)
            .put("duration_ms", info.durationMs)
            .put("visibility", draft.visibility)
            .put("owner_id", owner)
        val json = client.post("/rest/v1/tracks", body)
        val obj = client.firstObject(json) ?: throw ApiException.Parse()
        onProgress(100)
        return CloudTrack.from(obj)
    }

    suspend fun deleteTrack(id: String) {
        requireSignedIn()
        client.delete("/rest/v1/tracks?id=eq.${enc(id)}")
        cache.remove(id)
    }

    suspend fun likeTrack(id: String): Boolean {
        requireSignedIn()
        val uid = myId() ?: throw ApiException.Unauthorized("BlazeMuzix Cloud")
        return try {
            client.post("/rest/v1/likes", JSONObject().put("user_id", uid).put("track_id", id))
            true
        } catch (e: ApiException.InvalidRequest) {
            client.delete("/rest/v1/likes?user_id=eq.${enc(uid)}&track_id=eq.${enc(id)}")
            false
        }
    }

    suspend fun isLiked(id: String): Boolean {
        val uid = myId() ?: return false
        if (!configured) return false
        val arr = client.getArray("/rest/v1/likes?user_id=eq.${enc(uid)}&track_id=eq.${enc(id)}&select=track_id")
        return arr.length() > 0
    }

    suspend fun likedTracks(): List<CloudTrack> {
        requireSignedIn()
        val uid = myId() ?: return emptyList()
        val likes = client.getArray("/rest/v1/likes?user_id=eq.${enc(uid)}&select=track_id,created_at&order=created_at.desc")
        if (likes.length() == 0) return emptyList()
        val ids = (0 until likes.length()).map { likes.getJSONObject(it).optString("track_id") }.filter { it.isNotEmpty() }
        if (ids.isEmpty()) return emptyList()
        val inList = ids.joinToString(",")
        val arr = client.getArray("/rest/v1/tracks?id=in.($inList)&select=*")
        val map = parseTracks(arr).associateBy { it.id }
        return ids.mapNotNull { map[it] }
    }

    suspend fun comments(trackId: String): List<CloudComment> {
        requireConfigured()
        val arr = client.getArray("/rest/v1/comments?track_id=eq.${enc(trackId)}&select=*&order=created_at.asc")
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            CloudComment(
                id = o.optString("id"),
                userId = o.optString("user_id"),
                trackId = o.optString("track_id"),
                body = o.optString("body"),
                createdAt = o.optString("created_at").takeIf { s -> s.isNotBlank() }
            )
        }
    }

    suspend fun addComment(trackId: String, body: String): CloudComment {
        requireSignedIn()
        val uid = myId() ?: throw ApiException.Unauthorized("BlazeMuzix Cloud")
        val text = body.trim()
        if (text.isEmpty()) throw ApiException.InvalidRequest("Write a comment first.")
        if (text.length > 2000) throw ApiException.InvalidRequest("Comments can be at most 2000 characters.")
        val json = client.post(
            "/rest/v1/comments",
            JSONObject().put("user_id", uid).put("track_id", trackId).put("body", text)
        )
        val o = client.firstObject(json) ?: throw ApiException.Parse()
        return CloudComment(o.optString("id"), uid, trackId, text, o.optString("created_at"))
    }

    suspend fun playlistsPublic(limit: Int = 24): List<CloudPlaylist> {
        requireConfigured()
        val arr = client.getArray("/rest/v1/playlists?public=eq.true&select=*&order=created_at.desc&limit=$limit", cacheTtlMs = 20_000)
        return parsePlaylists(arr)
    }

    suspend fun searchPlaylists(query: String): List<CloudPlaylist> {
        requireConfigured()
        val q = like(query)
        val arr = client.getArray("/rest/v1/playlists?name=ilike.$q&public=eq.true&select=*&limit=20")
        return parsePlaylists(arr)
    }

    suspend fun myPlaylists(): List<CloudPlaylist> {
        requireSignedIn()
        val id = myId() ?: return emptyList()
        val arr = client.getArray("/rest/v1/playlists?owner_id=eq.${enc(id)}&select=*&order=created_at.desc")
        return parsePlaylists(arr)
    }

    suspend fun playlistsByOwner(ownerId: String): List<CloudPlaylist> {
        requireConfigured()
        val arr = client.getArray("/rest/v1/playlists?owner_id=eq.${enc(ownerId)}&public=eq.true&select=*")
        return parsePlaylists(arr)
    }

    suspend fun playlist(id: String): CloudPlaylist? {
        requireConfigured()
        val arr = client.getArray("/rest/v1/playlists?id=eq.${enc(id)}&select=*")
        return if (arr.length() == 0) null else CloudPlaylist.from(arr.getJSONObject(0))
    }

    suspend fun playlistTracks(playlistId: String): List<CloudTrack> {
        requireConfigured()
        val rows = client.getArray("/rest/v1/playlist_tracks?playlist_id=eq.${enc(playlistId)}&select=track_id,position&order=position.asc")
        val ids = (0 until rows.length()).map { rows.getJSONObject(it).optString("track_id") }.filter { it.isNotEmpty() }
        if (ids.isEmpty()) return emptyList()
        val arr = client.getArray("/rest/v1/tracks?id=in.(${ids.joinToString(",")})&select=*")
        val map = parseTracks(arr).associateBy { it.id }
        return ids.mapNotNull { map[it] }
    }

    suspend fun createPlaylist(name: String, description: String?, public: Boolean, coverUri: Uri?): CloudPlaylist {
        requireSignedIn()
        val owner = myId() ?: throw ApiException.Unauthorized("BlazeMuzix Cloud")
        val n = name.trim()
        if (n.isEmpty()) throw ApiException.InvalidRequest("Enter a playlist name.")
        var coverUrl: String? = null
        if (coverUri != null) {
            val bytes = readBytes(coverUri, MAX_IMAGE_BYTES)
            val ext = extension(coverUri, "jpg")
            coverUrl = client.upload("covers", "$owner/pl-${System.currentTimeMillis()}.$ext", bytes, mimeOf(ext, "image/jpeg"), null)
        }
        val json = client.post(
            "/rest/v1/playlists",
            JSONObject()
                .put("owner_id", owner)
                .put("name", n)
                .put("description", description?.trim().orEmpty())
                .put("public", public)
                .put("cover_url", coverUrl ?: JSONObject.NULL)
        )
        val obj = client.firstObject(json) ?: throw ApiException.Parse()
        return CloudPlaylist.from(obj)
    }

    suspend fun updatePlaylist(id: String, name: String?, description: String?, public: Boolean?) {
        requireSignedIn()
        val body = JSONObject()
        if (name != null) body.put("name", name.trim())
        if (description != null) body.put("description", description.trim())
        if (public != null) body.put("public", public)
        client.patch("/rest/v1/playlists?id=eq.${enc(id)}", body)
    }

    suspend fun deletePlaylist(id: String) {
        requireSignedIn()
        client.delete("/rest/v1/playlists?id=eq.${enc(id)}")
    }

    suspend fun addToPlaylist(playlistId: String, trackId: String) {
        requireSignedIn()
        val existing = client.getArray("/rest/v1/playlist_tracks?playlist_id=eq.${enc(playlistId)}&select=position&order=position.desc&limit=1")
        val next = if (existing.length() == 0) 0 else existing.getJSONObject(0).optInt("position") + 1
        client.post(
            "/rest/v1/playlist_tracks",
            JSONObject().put("playlist_id", playlistId).put("track_id", trackId).put("position", next)
        )
    }

    suspend fun removeFromPlaylist(playlistId: String, trackId: String) {
        requireSignedIn()
        client.delete("/rest/v1/playlist_tracks?playlist_id=eq.${enc(playlistId)}&track_id=eq.${enc(trackId)}")
    }

    suspend fun reorderPlaylist(playlistId: String, trackIds: List<String>) {
        requireSignedIn()
        client.delete("/rest/v1/playlist_tracks?playlist_id=eq.${enc(playlistId)}")
        trackIds.forEachIndexed { index, id ->
            client.post(
                "/rest/v1/playlist_tracks",
                JSONObject().put("playlist_id", playlistId).put("track_id", id).put("position", index)
            )
        }
    }

    suspend fun followPlaylist(playlistId: String): Boolean {
        requireSignedIn()
        val uid = myId() ?: throw ApiException.Unauthorized("BlazeMuzix Cloud")
        return try {
            client.post("/rest/v1/playlist_follows", JSONObject().put("user_id", uid).put("playlist_id", playlistId))
            true
        } catch (_: ApiException.InvalidRequest) {
            client.delete("/rest/v1/playlist_follows?user_id=eq.${enc(uid)}&playlist_id=eq.${enc(playlistId)}")
            false
        }
    }

    suspend fun followedPlaylists(): List<CloudPlaylist> {
        requireSignedIn()
        val uid = myId() ?: return emptyList()
        val rows = client.getArray("/rest/v1/playlist_follows?user_id=eq.${enc(uid)}&select=playlist_id")
        val ids = (0 until rows.length()).map { rows.getJSONObject(it).optString("playlist_id") }.filter { it.isNotEmpty() }
        if (ids.isEmpty()) return emptyList()
        val arr = client.getArray("/rest/v1/playlists?id=in.(${ids.joinToString(",")})&select=*")
        return parsePlaylists(arr)
    }

    suspend fun followUser(userId: String): Boolean {
        requireSignedIn()
        val uid = myId() ?: throw ApiException.Unauthorized("BlazeMuzix Cloud")
        if (uid == userId) throw ApiException.InvalidRequest("You cannot follow yourself.")
        return try {
            client.post("/rest/v1/follows", JSONObject().put("follower_id", uid).put("following_id", userId))
            true
        } catch (_: ApiException.InvalidRequest) {
            client.delete("/rest/v1/follows?follower_id=eq.${enc(uid)}&following_id=eq.${enc(userId)}")
            false
        }
    }

    suspend fun isFollowing(userId: String): Boolean {
        val uid = myId() ?: return false
        val arr = client.getArray("/rest/v1/follows?follower_id=eq.${enc(uid)}&following_id=eq.${enc(userId)}&select=follower_id")
        return arr.length() > 0
    }

    suspend fun report(targetType: String, targetId: String, reason: String) {
        requireSignedIn()
        val uid = myId() ?: throw ApiException.Unauthorized("BlazeMuzix Cloud")
        val r = reason.trim()
        if (r.isEmpty()) throw ApiException.InvalidRequest("Choose a reason for the report.")
        client.post(
            "/rest/v1/reports",
            JSONObject()
                .put("reporter_id", uid)
                .put("target_type", targetType)
                .put("target_id", targetId)
                .put("reason", r)
        )
    }

    suspend fun incrementPlay(trackId: String) {
        if (!configured) return
        runCatching {
            client.post("/rest/v1/rpc/increment_play_count", JSONObject().put("track", trackId))
        }
    }

    suspend fun userShorts(limit: Int = 20): List<com.blazemuzix.app.data.models.MediaItem> {
        if (!configured) return emptyList()
        return try {
            val arr = client.getArray("/rest/v1/user_shorts?visibility=eq.public&select=*&order=created_at.desc&limit=$limit")
            (0 until arr.length()).map { CloudShort.from(arr.getJSONObject(it)).toMediaItem() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun myShorts(): List<com.blazemuzix.app.data.models.MediaItem> {
        requireSignedIn()
        val id = myId() ?: return emptyList()
        val arr = client.getArray("/rest/v1/user_shorts?owner_id=eq.${enc(id)}&select=*&order=created_at.desc")
        return (0 until arr.length()).map { CloudShort.from(arr.getJSONObject(it)).toMediaItem() }
    }

    suspend fun uploadShort(videoUri: Uri, title: String, onProgress: (Int) -> Unit): com.blazemuzix.app.data.models.MediaItem {
        requireSignedIn()
        val owner = myId() ?: throw ApiException.Unauthorized("BlazeMuzix Cloud")
        val t = title.trim()
        if (t.isEmpty()) throw ApiException.InvalidRequest("Enter a title for your short.")
        val info = inspectAudio(videoUri)
        if (info.sizeBytes > MAX_VIDEO_BYTES) throw ApiException.InvalidRequest("Shorts must be ${MAX_VIDEO_BYTES / (1024 * 1024)} MB or smaller.")
        val mime = (info.mime ?: "").lowercase()
        val name = info.name.lowercase()
        val ok = mime.startsWith("video/") || name.endsWith(".mp4") || name.endsWith(".webm") || name.endsWith(".3gp")
        if (!ok) throw ApiException.InvalidRequest("Use an MP4, WebM or 3GP video you own.")
        val bytes = readBytes(videoUri, MAX_VIDEO_BYTES)
        val ext = extension(videoUri, "mp4")
        val key = UUID.randomUUID().toString()
        onProgress(8)
        val url = client.upload("videos", "$owner/$key.$ext", bytes, mimeOf(ext, "video/mp4")) { p ->
            onProgress(8 + (p * 0.8).toInt())
        }
        val json = client.post(
            "/rest/v1/user_shorts",
            JSONObject()
                .put("owner_id", owner)
                .put("title", t)
                .put("video_url", url)
                .put("duration_ms", info.durationMs)
                .put("visibility", "public")
        )
        onProgress(100)
        val obj = client.firstObject(json) ?: throw ApiException.Parse()
        return CloudShort.from(obj).toMediaItem()
    }

    suspend fun cacheOwnTrack(track: CloudTrack, onProgress: (Int) -> Unit) {
        requireSignedIn()
        if (track.ownerId != myId()) {
            throw ApiException.InvalidRequest("Only the uploader can cache this track for offline playback.")
        }
        cache.download(track, onProgress)
    }

    private fun parsePlaylists(arr: org.json.JSONArray): List<CloudPlaylist> =
        (0 until arr.length()).map { CloudPlaylist.from(arr.getJSONObject(it)) }

    private fun like(query: String): String {
        val cleaned = query.trim().replace(",", " ").replace("(", " ").replace(")", " ")
        return "*" + HttpClient.encode(cleaned).replace("%20", "*") + "*"
    }

    private fun enc(value: String): String = HttpClient.encode(value)

    private fun extension(uri: Uri, fallback: String): String {
        val mime = context.contentResolver.getType(uri)
        val fromMime = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        if (!fromMime.isNullOrBlank()) return fromMime
        val path = uri.lastPathSegment ?: return fallback
        val dot = path.lastIndexOf('.')
        return if (dot >= 0) path.substring(dot + 1).lowercase() else fallback
    }

    private fun mimeOf(ext: String, fallback: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: fallback

    private fun durationOf(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun readBytes(uri: Uri, max: Int): ByteArray {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw ApiException.InvalidRequest("Could not open the selected file.")
        stream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                total += n
                if (total > max) throw ApiException.InvalidRequest("File is larger than ${max / (1024 * 1024)} MB.")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }

    companion object {
        const val MAX_AUDIO_BYTES = 25 * 1024 * 1024
        const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
        const val MAX_VIDEO_BYTES = 20 * 1024 * 1024
    }
}
