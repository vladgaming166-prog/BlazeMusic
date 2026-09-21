package com.blazemuzix.app.cloud

import android.content.Context
import com.blazemuzix.app.network.ApiException
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Offline copies of tracks the signed-in user uploaded. Protected Spotify/YouTube
 * audio is never stored here.
 */
class CloudCache(context: Context) {
    private val dir = File(context.filesDir, "cloud-offline").apply { mkdirs() }
    private val index = context.getSharedPreferences("cloud_offline", Context.MODE_PRIVATE)

    fun fileFor(trackId: String): File = File(dir, "$trackId.bin")

    fun isCached(trackId: String): Boolean = fileFor(trackId).exists() && index.contains(trackId)

    fun applyLocal(track: CloudTrack): CloudTrack {
        val file = fileFor(track.id)
        return if (file.exists()) track.copy(audioUrl = "file://${file.absolutePath}") else track
    }

    fun remove(trackId: String) {
        fileFor(trackId).delete()
        index.edit().remove(trackId).apply()
    }

    fun cachedTracks(): List<CloudTrack> {
        return index.all.mapNotNull { (_, value) ->
            val raw = value as? String ?: return@mapNotNull null
            try {
                applyLocal(CloudTrack.from(JSONObject(raw)))
            } catch (_: Exception) {
                null
            }
        }
    }

    fun download(track: CloudTrack, onProgress: (Int) -> Unit) {
        val url = track.audioUrl
        if (url.isBlank() || url.startsWith("file:")) {
            throw ApiException.InvalidRequest("This track has no downloadable audio URL.")
        }
        if (!url.startsWith("https://")) {
            throw ApiException.InvalidRequest("Offline cache only accepts HTTPS audio from BlazeMuzix Cloud.")
        }
        val dest = fileFor(track.id)
        val tmp = File(dir, track.id + ".tmp")
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 120_000
            connection.instanceFollowRedirects = true
            val code = connection.responseCode
            if (code !in 200..299) throw ApiException.ServerError(code)
            val total = connection.contentLength
            connection.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(16 * 1024)
                    var copied = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        copied += n
                        if (total > 0) onProgress(((copied * 100) / total).toInt().coerceIn(0, 99))
                    }
                }
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) tmp.copyTo(dest, overwrite = true)
            index.edit().putString(track.id, trackToJson(track)).apply()
            onProgress(100)
        } catch (e: ApiException) {
            tmp.delete()
            throw e
        } catch (e: Exception) {
            tmp.delete()
            throw ApiException.Network(e)
        } finally {
            try { connection?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun trackToJson(track: CloudTrack): String = JSONObject().apply {
        put("id", track.id)
        put("owner_id", track.ownerId)
        put("title", track.title)
        put("artist", track.artist)
        put("description", track.description)
        put("album", track.album)
        put("genre", track.genre)
        put("year", track.year)
        put("audio_url", track.audioUrl)
        put("cover_url", track.coverUrl)
        put("duration_ms", track.durationMs)
        put("visibility", track.visibility)
        put("play_count", track.playCount)
        put("like_count", track.likeCount)
        put("created_at", track.createdAt)
    }.toString()
}
