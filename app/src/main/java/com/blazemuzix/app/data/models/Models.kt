package com.blazemuzix.app.data.models

/** One page of provider results. [nextPageToken] is null when the provider has no more results. */
data class Page<T>(
    val items: List<T>,
    val nextPageToken: String? = null
) {
    val hasMore: Boolean get() = nextPageToken != null

    companion object {
        fun <T> empty(): Page<T> = Page(emptyList(), null)
    }
}

enum class SectionLayout { CARDS, WIDE_CARDS, ROWS }

/** A horizontal/vertical group of items on the Home screen. */
data class Section(
    val id: String,
    val title: String,
    val items: List<MediaItem>,
    val layout: SectionLayout = SectionLayout.CARDS,
    val source: Source? = null
)

data class Playlist(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val trackCount: Int,
    val artworkUrl: String? = null
)

/** Search filter tabs. `null` type means "everything". */
enum class SearchFilter(val type: MediaType?) {
    ALL(null),
    SONGS(MediaType.SONG),
    ALBUMS(MediaType.ALBUM),
    ARTISTS(MediaType.ARTIST),
    PLAYLISTS(MediaType.PLAYLIST),
    VIDEOS(MediaType.VIDEO)
}

/**
 * UI state shared by every screen. There is no "blank" state: every screen is
 * always in exactly one of these.
 */
sealed class UiState<out T> {
    object Loading : UiState<Nothing>()
    data class Success<T>(val data: T) : UiState<T>()
    data class Empty(val title: String? = null, val message: String? = null) : UiState<Nothing>()
    data class Error(val error: Throwable) : UiState<Nothing>()
    object Offline : UiState<Nothing>()
}
