package com.blazemuzix.app.data.repository

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.blazemuzix.app.data.db.BlazeDatabase
import com.blazemuzix.app.data.db.BlazeDatabase.Companion.T_FAVORITES
import com.blazemuzix.app.data.db.BlazeDatabase.Companion.T_PLAYLISTS
import com.blazemuzix.app.data.db.BlazeDatabase.Companion.T_PLAYLIST_ITEMS
import com.blazemuzix.app.data.db.BlazeDatabase.Companion.T_QUEUE
import com.blazemuzix.app.data.db.BlazeDatabase.Companion.T_QUEUE_META
import com.blazemuzix.app.data.db.BlazeDatabase.Companion.T_RECENT
import com.blazemuzix.app.data.db.BlazeDatabase.Companion.T_SAVED
import com.blazemuzix.app.data.db.BlazeDatabase.Companion.T_SEARCH_HISTORY
import com.blazemuzix.app.data.models.MediaItem
import com.blazemuzix.app.data.models.Playlist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Collections

/**
 * Everything the user keeps on the device: favorites, saved items, recently
 * played, search history and playlists. Observers watch [changes] and reload.
 */
class LibraryRepository(private val db: BlazeDatabase) {

    private val _changes = MutableLiveData(0)
    val changes: LiveData<Int> get() = _changes

    private val favoriteIds: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val savedIds: MutableSet<String> = Collections.synchronizedSet(HashSet())

    @Volatile
    private var idsLoaded = false

    private fun notifyChanged() {
        _changes.postValue((_changes.value ?: 0) + 1)
    }

    // ------------------------------------------------------------------ ids

    suspend fun warmUp() = withContext(Dispatchers.IO) { ensureIds() }

    private fun ensureIds() {
        if (idsLoaded) return
        synchronized(this) {
            if (idsLoaded) return
            favoriteIds.addAll(readIds(T_FAVORITES))
            savedIds.addAll(readIds(T_SAVED))
            idsLoaded = true
        }
    }

    private fun readIds(table: String): List<String> = try {
        db.readableDatabase.query(table, arrayOf("item_id"), null, null, null, null, null).use { c ->
            val list = ArrayList<String>(c.count)
            while (c.moveToNext()) list.add(c.getString(0))
            list
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun isFavorite(id: String): Boolean = favoriteIds.contains(id)
    fun isSaved(id: String): Boolean = savedIds.contains(id)

    // ------------------------------------------------------------ favorites

    suspend fun toggleFavorite(item: MediaItem): Boolean = withContext(Dispatchers.IO) {
        ensureIds()
        val nowFavorite = if (favoriteIds.contains(item.id)) {
            db.writableDatabase.delete(T_FAVORITES, "item_id = ?", arrayOf(item.id))
            favoriteIds.remove(item.id)
            false
        } else {
            upsertItem(T_FAVORITES, item, "added_at")
            favoriteIds.add(item.id)
            true
        }
        notifyChanged()
        nowFavorite
    }

    suspend fun favorites(): List<MediaItem> = withContext(Dispatchers.IO) {
        readItems(T_FAVORITES, "added_at DESC", null)
    }

    // ---------------------------------------------------------------- saved

    suspend fun toggleSaved(item: MediaItem): Boolean = withContext(Dispatchers.IO) {
        ensureIds()
        val nowSaved = if (savedIds.contains(item.id)) {
            db.writableDatabase.delete(T_SAVED, "item_id = ?", arrayOf(item.id))
            savedIds.remove(item.id)
            false
        } else {
            upsertItem(T_SAVED, item, "added_at")
            savedIds.add(item.id)
            true
        }
        notifyChanged()
        nowSaved
    }

    suspend fun saved(): List<MediaItem> = withContext(Dispatchers.IO) {
        readItems(T_SAVED, "added_at DESC", null)
    }

    // --------------------------------------------------------------- recent

    suspend fun recordPlayed(item: MediaItem) = withContext(Dispatchers.IO) {
        try {
            upsertItem(T_RECENT, item, "played_at")
            val database = db.writableDatabase
            database.execSQL(
                "DELETE FROM $T_RECENT WHERE item_id NOT IN (SELECT item_id FROM $T_RECENT ORDER BY played_at DESC LIMIT $MAX_RECENT)"
            )
        } catch (_: Exception) {
        }
        notifyChanged()
    }

    suspend fun recentlyPlayed(limit: Int = MAX_RECENT): List<MediaItem> = withContext(Dispatchers.IO) {
        readItems(T_RECENT, "played_at DESC", limit)
    }

    suspend fun clearRecentlyPlayed() = withContext(Dispatchers.IO) {
        db.writableDatabase.delete(T_RECENT, null, null)
        notifyChanged()
    }

    // ------------------------------------------------------- search history

    suspend fun recordSearch(query: String) = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext
        try {
            val values = ContentValues().apply {
                put("query", q)
                put("searched_at", System.currentTimeMillis())
            }
            val database = db.writableDatabase
            database.insertWithOnConflict(T_SEARCH_HISTORY, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            database.execSQL(
                "DELETE FROM $T_SEARCH_HISTORY WHERE query NOT IN (SELECT query FROM $T_SEARCH_HISTORY ORDER BY searched_at DESC LIMIT $MAX_HISTORY)"
            )
        } catch (_: Exception) {
        }
    }

    suspend fun searchHistory(prefix: String? = null, limit: Int = MAX_HISTORY): List<String> = withContext(Dispatchers.IO) {
        try {
            val where = if (prefix.isNullOrBlank()) null else "query LIKE ? ESCAPE '\\'"
            val args = if (prefix.isNullOrBlank()) null else arrayOf(prefix.trim().replace("%", "\\%") + "%")
            db.readableDatabase.query(T_SEARCH_HISTORY, arrayOf("query"), where, args, null, null, "searched_at DESC", limit.toString()).use { c ->
                val list = ArrayList<String>(c.count)
                while (c.moveToNext()) list.add(c.getString(0))
                list
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun removeSearch(query: String) = withContext(Dispatchers.IO) {
        db.writableDatabase.delete(T_SEARCH_HISTORY, "query = ?", arrayOf(query))
    }

    suspend fun clearSearchHistory() = withContext(Dispatchers.IO) {
        db.writableDatabase.delete(T_SEARCH_HISTORY, null, null)
    }

    // ------------------------------------------------------------ playlists

    suspend fun playlists(): List<Playlist> = withContext(Dispatchers.IO) {
        try {
            db.readableDatabase.rawQuery(
                """SELECT p.id, p.name, p.created_at,
                    (SELECT COUNT(*) FROM $T_PLAYLIST_ITEMS i WHERE i.playlist_id = p.id) AS cnt,
                    (SELECT json FROM $T_PLAYLIST_ITEMS i WHERE i.playlist_id = p.id ORDER BY position ASC LIMIT 1) AS first_json
                   FROM $T_PLAYLISTS p ORDER BY p.created_at DESC""", null
            ).use { c ->
                val list = ArrayList<Playlist>(c.count)
                while (c.moveToNext()) {
                    val artwork = MediaItem.fromJsonOrNull(c.getString(4))?.artworkUrl
                    list.add(Playlist(c.getLong(0), c.getString(1), c.getLong(2), c.getInt(3), artwork))
                }
                list
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun createPlaylist(name: String): Long = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("name", name.trim())
            put("created_at", System.currentTimeMillis())
        }
        val id = db.writableDatabase.insert(T_PLAYLISTS, null, values)
        notifyChanged()
        id
    }

    suspend fun renamePlaylist(id: Long, name: String) = withContext(Dispatchers.IO) {
        db.writableDatabase.update(T_PLAYLISTS, ContentValues().apply { put("name", name.trim()) }, "id = ?", arrayOf(id.toString()))
        notifyChanged()
    }

    suspend fun deletePlaylist(id: Long) = withContext(Dispatchers.IO) {
        val database = db.writableDatabase
        database.delete(T_PLAYLIST_ITEMS, "playlist_id = ?", arrayOf(id.toString()))
        database.delete(T_PLAYLISTS, "id = ?", arrayOf(id.toString()))
        notifyChanged()
    }

    suspend fun playlist(id: Long): Playlist? = withContext(Dispatchers.IO) {
        playlists().firstOrNull { it.id == id }
    }

    /** @return true when added, false when the item was already in the playlist. */
    suspend fun addToPlaylist(playlistId: Long, item: MediaItem): Boolean = withContext(Dispatchers.IO) {
        val database = db.writableDatabase
        val exists = database.query(
            T_PLAYLIST_ITEMS, arrayOf("item_id"), "playlist_id = ? AND item_id = ?",
            arrayOf(playlistId.toString(), item.id), null, null, null
        ).use { it.moveToFirst() }
        if (exists) return@withContext false
        val position = database.rawQuery(
            "SELECT COALESCE(MAX(position), -1) + 1 FROM $T_PLAYLIST_ITEMS WHERE playlist_id = ?",
            arrayOf(playlistId.toString())
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val values = ContentValues().apply {
            put("playlist_id", playlistId)
            put("item_id", item.id)
            put("json", item.toJson().toString())
            put("position", position)
            put("added_at", System.currentTimeMillis())
        }
        database.insert(T_PLAYLIST_ITEMS, null, values)
        notifyChanged()
        true
    }

    suspend fun removeFromPlaylist(playlistId: Long, itemId: String) = withContext(Dispatchers.IO) {
        db.writableDatabase.delete(T_PLAYLIST_ITEMS, "playlist_id = ? AND item_id = ?", arrayOf(playlistId.toString(), itemId))
        notifyChanged()
    }

    suspend fun playlistItems(playlistId: Long): List<MediaItem> = withContext(Dispatchers.IO) {
        try {
            db.readableDatabase.query(
                T_PLAYLIST_ITEMS, arrayOf("json"), "playlist_id = ?", arrayOf(playlistId.toString()),
                null, null, "position ASC"
            ).use { readJsonColumn(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun reorderPlaylist(playlistId: Long, from: Int, to: Int) = withContext(Dispatchers.IO) {
        val items = playlistItems(playlistId).toMutableList()
        if (from !in items.indices || to !in items.indices || from == to) return@withContext
        val moved = items.removeAt(from)
        items.add(to, moved)
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            database.delete(T_PLAYLIST_ITEMS, "playlist_id = ?", arrayOf(playlistId.toString()))
            items.forEachIndexed { index, item ->
                database.insert(T_PLAYLIST_ITEMS, null, ContentValues().apply {
                    put("playlist_id", playlistId)
                    put("item_id", item.id)
                    put("json", item.toJson().toString())
                    put("position", index)
                    put("added_at", System.currentTimeMillis())
                })
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        notifyChanged()
    }

    suspend fun removeFavorites(ids: Collection<String>) = withContext(Dispatchers.IO) {
        ensureIds()
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            for (id in ids) {
                database.delete(T_FAVORITES, "item_id = ?", arrayOf(id))
                favoriteIds.remove(id)
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        notifyChanged()
    }

    suspend fun addAllToPlaylist(playlistId: Long, items: List<MediaItem>) = withContext(Dispatchers.IO) {
        for (item in items) addToPlaylist(playlistId, item)
    }

    suspend fun createPlaylistWith(name: String, items: List<MediaItem>): Long {
        val id = createPlaylist(name)
        addAllToPlaylist(id, items)
        return id
    }

    data class PersistedQueue(val items: List<MediaItem>, val index: Int, val shuffle: Boolean, val repeat: String)

    fun persistQueue(items: List<MediaItem>, index: Int, shuffle: Boolean, repeat: String) {
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            database.delete(T_QUEUE, null, null)
            items.forEachIndexed { i, item ->
                database.insert(T_QUEUE, null, ContentValues().apply {
                    put("position", i)
                    put("json", item.toJson().toString())
                })
            }
            fun meta(k: String, v: String) {
                database.insertWithOnConflict(
                    T_QUEUE_META, null,
                    ContentValues().apply { put("k", k); put("v", v) },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            meta("index", index.toString())
            meta("shuffle", shuffle.toString())
            meta("repeat", repeat)
            database.setTransactionSuccessful()
        } catch (_: Exception) {
        } finally {
            database.endTransaction()
        }
    }

    fun loadQueue(): PersistedQueue? = try {
        val items = db.readableDatabase.query(T_QUEUE, arrayOf("json"), null, null, null, null, "position ASC")
            .use { readJsonColumn(it) }
        if (items.isEmpty()) null else {
            val meta = HashMap<String, String>()
            db.readableDatabase.query(T_QUEUE_META, arrayOf("k", "v"), null, null, null, null, null).use { c ->
                while (c.moveToNext()) meta[c.getString(0)] = c.getString(1)
            }
            PersistedQueue(
                items,
                meta["index"]?.toIntOrNull() ?: 0,
                meta["shuffle"] == "true",
                meta["repeat"] ?: "off"
            )
        }
    } catch (_: Exception) {
        null
    }

    fun clearQueue() {
        try {
            db.writableDatabase.delete(T_QUEUE, null, null)
            db.writableDatabase.delete(T_QUEUE_META, null, null)
        } catch (_: Exception) {
        }
    }

    // -------------------------------------------------------------- helpers

    private fun upsertItem(table: String, item: MediaItem, timeColumn: String) {
        val values = ContentValues().apply {
            put("item_id", item.id)
            put("json", item.toJson().toString())
            put(timeColumn, System.currentTimeMillis())
        }
        db.writableDatabase.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun readItems(table: String, orderBy: String, limit: Int?): List<MediaItem> = try {
        db.readableDatabase.query(table, arrayOf("json"), null, null, null, null, orderBy, limit?.toString())
            .use { readJsonColumn(it) }
    } catch (_: Exception) {
        emptyList()
    }

    private fun readJsonColumn(c: Cursor): List<MediaItem> {
        val list = ArrayList<MediaItem>(c.count)
        while (c.moveToNext()) MediaItem.fromJsonOrNull(c.getString(0))?.let(list::add)
        return list
    }

    companion object {
        const val MAX_RECENT = 100
        const val MAX_HISTORY = 30
    }
}
