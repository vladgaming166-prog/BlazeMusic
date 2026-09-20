package com.blazemuzix.app

import com.blazemuzix.app.data.models.MediaItem
import com.blazemuzix.app.data.models.MediaType
import com.blazemuzix.app.data.models.Playback
import com.blazemuzix.app.data.models.Source
import com.blazemuzix.app.data.models.StorageTag
import com.blazemuzix.app.data.repository.DiscoveryRepository
import com.blazemuzix.app.providers.YouTubeProvider
import com.blazemuzix.app.utils.Formatters
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaItemTest {

    private fun item(id: String, source: Source = Source.LOCAL) = MediaItem(
        id = MediaItem.makeId(source, MediaType.SONG, id),
        source = source,
        type = MediaType.SONG,
        title = "Title $id",
        artist = "Artist",
        album = "Album",
        durationMs = 123_000,
        playbackUri = "content://media/external/audio/media/$id",
        providerId = id,
        playback = Playback.DIRECT,
        localPath = "/storage/emulated/0/Music/$id.mp3"
    )

    @Test
    fun jsonRoundTrip() {
        val original = item("42")
        val restored = MediaItem.fromJson(JSONObject(original.toJson().toString()))
        assertEquals(original, restored)
    }

    @Test
    fun storageTagDistinguishesDownloads() {
        assertEquals(StorageTag.LOCAL, item("1").storageTag)
        assertEquals(StorageTag.DOWNLOADED, item("2").copy(localPath = "/storage/emulated/0/Download/x.mp3").storageTag)
        assertEquals(StorageTag.ONLINE, item("3", Source.SPOTIFY).copy(localPath = null).storageTag)
    }

    @Test
    fun externalOnlyItemsAreNotDirectlyPlayable() {
        val spotify = item("s", Source.SPOTIFY).copy(playback = Playback.EXTERNAL, playbackUri = null)
        assertFalse(spotify.canPlayDirect)
        assertTrue(item("l").canPlayDirect)
    }

    @Test
    fun interleaveMergesRoundRobinWithoutDuplicates() {
        val a = listOf(item("1"), item("2"), item("3"))
        val b = listOf(item("x", Source.SPOTIFY), item("1"))
        val merged = DiscoveryRepository.interleave(listOf(a, b))
        assertEquals(listOf("local:song:1", "spotify:song:x", "local:song:2", "local:song:3"), merged.map { it.id })
    }

    @Test
    fun parsesIsoDurations() {
        assertEquals(0L, YouTubeProvider.parseIsoDuration(null))
        assertEquals(45_000L, YouTubeProvider.parseIsoDuration("PT45S"))
        assertEquals(3_723_000L, YouTubeProvider.parseIsoDuration("PT1H2M3S"))
        assertEquals(240_000L, YouTubeProvider.parseIsoDuration("PT4M"))
    }

    @Test
    fun formatsDurations() {
        assertEquals("--:--", Formatters.duration(0))
        assertEquals("2:03", Formatters.duration(123_000))
        assertEquals("1:02:03", Formatters.duration(3_723_000))
        assertEquals("1.5 MB", Formatters.bytes(1_572_864))
    }

    @Test
    fun fromJsonOrNullRejectsGarbage() {
        assertNull(MediaItem.fromJsonOrNull("not json"))
        assertNull(MediaItem.fromJsonOrNull(null))
    }
}
