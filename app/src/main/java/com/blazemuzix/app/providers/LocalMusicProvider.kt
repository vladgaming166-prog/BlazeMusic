package com.blazemuzix.app.providers

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.blazemuzix.app.data.models.MediaItem
import com.blazemuzix.app.data.models.MediaType
import com.blazemuzix.app.data.models.Page
import com.blazemuzix.app.data.models.Playback
import com.blazemuzix.app.data.models.Section
import com.blazemuzix.app.data.models.SectionLayout
import com.blazemuzix.app.data.models.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the music already on the device through Android's MediaStore. Works on
 * API 19 (legacy storage permission) through API 35 (READ_MEDIA_AUDIO).
 */
class LocalMusicProvider(context: Context) : MusicProvider {

    private val appContext = context.applicationContext

    override val source = Source.LOCAL
    override val displayName = "This device"
    override val isConfigured = true
    override val requiresNetwork = false
    override val supportedSearchTypes = setOf(MediaType.SONG, MediaType.ALBUM, MediaType.ARTIST)

    @Volatile
    private var cache: List<MediaItem>? = null

    val requiredPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun hasPermission(): Boolean {
        // Storage permission only became a runtime permission on Android 6.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return ContextCompat.checkSelfPermission(appContext, requiredPermission) == PackageManager.PERMISSION_GRANTED
    }

    fun invalidate() {
        cache = null
    }

    /** All local songs sorted by title. Cached until [invalidate]. */
    suspend fun allSongs(forceRefresh: Boolean = false): List<MediaItem> = withContext(Dispatchers.IO) {
        if (!forceRefresh) cache?.let { return@withContext it }
        if (!hasPermission()) return@withContext emptyList()
        val result = query()
        cache = result
        result
    }

    suspend fun downloads(): List<MediaItem> = allSongs().filter { it.localPath?.contains("/Download", ignoreCase = true) == true }

    suspend fun albums(): List<MediaItem> = allSongs()
        .filter { !it.album.isNullOrBlank() }
        .groupBy { it.album!!.lowercase() + "|" + it.artist.lowercase() }
        .map { (_, tracks) ->
            val first = tracks.first()
            MediaItem(
                id = MediaItem.makeId(Source.LOCAL, MediaType.ALBUM, first.album!!.hashCode().toString() + first.artist.hashCode()),
                source = Source.LOCAL,
                type = MediaType.ALBUM,
                title = first.album,
                artist = first.artist,
                artworkUrl = first.artworkUrl,
                providerId = first.album,
                playback = Playback.DIRECT,
                trackCount = tracks.size
            )
        }
        .sortedBy { it.title.lowercase() }

    suspend fun artists(): List<MediaItem> = allSongs()
        .groupBy { it.artist.lowercase() }
        .map { (_, tracks) ->
            val first = tracks.first()
            MediaItem(
                id = MediaItem.makeId(Source.LOCAL, MediaType.ARTIST, first.artist.hashCode().toString()),
                source = Source.LOCAL,
                type = MediaType.ARTIST,
                title = first.artist,
                artist = "${tracks.size} songs",
                artworkUrl = first.artworkUrl,
                providerId = first.artist,
                playback = Playback.DIRECT,
                trackCount = tracks.size
            )
        }
        .sortedBy { it.title.lowercase() }

    override suspend fun search(query: String, type: MediaType?, pageToken: String?): Page<MediaItem> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return Page.empty()
        val items: List<MediaItem> = when (type) {
            MediaType.ALBUM -> albums().filter { it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) }
            MediaType.ARTIST -> artists().filter { it.title.lowercase().contains(q) }
            null, MediaType.SONG -> allSongs().filter {
                it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.album?.lowercase()?.contains(q) == true
            }
            else -> emptyList()
        }
        return Page(items.take(200), null)
    }

    override suspend fun homeSections(): List<Section> {
        val songs = allSongs()
        if (songs.isEmpty()) return emptyList()
        return listOf(
            Section("local_recent", "On this device", songs.sortedByDescending { it.providerId.toLongOrNull() ?: 0L }.take(20), SectionLayout.CARDS, Source.LOCAL)
        )
    }

    override suspend fun collectionItems(item: MediaItem, pageToken: String?): Page<MediaItem> {
        val songs = allSongs()
        val items = when (item.type) {
            MediaType.ALBUM -> songs.filter { it.album.equals(item.title, ignoreCase = true) && it.artist.equals(item.artist, ignoreCase = true) }
            MediaType.ARTIST -> songs.filter { it.artist.equals(item.title, ignoreCase = true) }
            else -> emptyList()
        }
        return Page(items, null)
    }

    /** Local files are the user's own; they are always available offline. */
    override fun supportsOfflineDownload(item: MediaItem): Boolean = false

    private fun query(): List<MediaItem> {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA
        )
        val selection = MediaStore.Audio.Media.IS_MUSIC + " != 0 AND " + MediaStore.Audio.Media.DURATION + " > 20000"
        val list = ArrayList<MediaItem>()
        try {
            appContext.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, null,
                MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC"
            )?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataIdx = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                while (c.moveToNext()) {
                    val id = c.getLong(idIdx)
                    val artist = c.getString(artistIdx)?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING } ?: "Unknown artist"
                    val album = c.getString(albumIdx)?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING }
                    val albumId = c.getLong(albumIdIdx)
                    val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    list.add(
                        MediaItem(
                            id = MediaItem.makeId(Source.LOCAL, MediaType.SONG, id.toString()),
                            source = Source.LOCAL,
                            type = MediaType.SONG,
                            title = c.getString(titleIdx)?.takeIf { it.isNotBlank() } ?: "Unknown title",
                            artist = artist,
                            album = album,
                            artworkUrl = if (albumId > 0) albumArtUri(albumId).toString() else null,
                            durationMs = c.getLong(durationIdx),
                            playbackUri = contentUri.toString(),
                            providerId = id.toString(),
                            playback = Playback.DIRECT,
                            localPath = if (dataIdx >= 0) c.getString(dataIdx) else null
                        )
                    )
                }
            }
        } catch (_: Exception) {
            // Permission revoked mid-query or provider error: return what we have.
        }
        return list
    }

    companion object {
        private val ALBUM_ART_URI: Uri = Uri.parse("content://media/external/audio/albumart")

        fun albumArtUri(albumId: Long): Uri = ContentUris.withAppendedId(ALBUM_ART_URI, albumId)
    }
}
