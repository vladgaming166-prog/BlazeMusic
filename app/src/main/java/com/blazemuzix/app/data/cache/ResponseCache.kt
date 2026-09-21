package com.blazemuzix.app.data.cache

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.blazemuzix.app.data.db.BlazeDatabase
import com.blazemuzix.app.data.db.BlazeDatabase.Companion.T_API_CACHE

/**
 * Metadata cache for provider JSON responses. Capped at [maxBytes]; the oldest
 * entries are evicted first. All methods are cheap and synchronous; callers run
 * them on the IO dispatcher through [com.blazemuzix.app.network.HttpClient].
 */
class ResponseCache(private val db: BlazeDatabase, private val maxBytes: Long = DEFAULT_MAX_BYTES) {

    fun get(key: String, ttlMs: Long): String? {
        val minCreated = if (ttlMs == Long.MAX_VALUE) Long.MIN_VALUE else System.currentTimeMillis() - ttlMs
        return try {
            db.readableDatabase.query(
                T_API_CACHE, arrayOf("body", "created_at"), "cache_key = ?", arrayOf(key), null, null, null
            ).use { c ->
                if (c.moveToFirst() && c.getLong(1) >= minCreated) c.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun put(key: String, body: String) {
        if (body.length > maxBytes / 4) return
        try {
            val values = ContentValues().apply {
                put("cache_key", key)
                put("body", body)
                put("created_at", System.currentTimeMillis())
                put("size", body.length)
            }
            val database = db.writableDatabase
            database.insertWithOnConflict(T_API_CACHE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            trim(database)
        } catch (_: Exception) {
        }
    }

    fun sizeBytes(): Long = try {
        db.readableDatabase.rawQuery("SELECT COALESCE(SUM(size),0) FROM $T_API_CACHE", null).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }
    } catch (_: Exception) {
        0L
    }

    fun clear() {
        try {
            db.writableDatabase.delete(T_API_CACHE, null, null)
        } catch (_: Exception) {
        }
    }

    private fun trim(database: SQLiteDatabase) {
        var total = sizeBytes()
        if (total <= maxBytes) return
        database.rawQuery("SELECT cache_key, size FROM $T_API_CACHE ORDER BY created_at ASC", null).use { c ->
            while (total > maxBytes && c.moveToNext()) {
                database.delete(T_API_CACHE, "cache_key = ?", arrayOf(c.getString(0)))
                total -= c.getLong(1)
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 6L * 1024 * 1024
        const val TTL_SHORT = 10 * 60 * 1000L
        const val TTL_MEDIUM = 60 * 60 * 1000L
        const val TTL_LONG = 6 * 60 * 60 * 1000L
    }
}
