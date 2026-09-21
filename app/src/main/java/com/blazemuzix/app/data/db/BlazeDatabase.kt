package com.blazemuzix.app.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Plain SQLite (no annotation processors, works on API 19). Items are stored as
 * JSON snapshots so the Library works fully offline.
 */
class BlazeDatabase(context: Context) : SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE $T_FAVORITES (
                item_id TEXT PRIMARY KEY,
                json TEXT NOT NULL,
                added_at INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE $T_SAVED (
                item_id TEXT PRIMARY KEY,
                json TEXT NOT NULL,
                added_at INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE $T_RECENT (
                item_id TEXT PRIMARY KEY,
                json TEXT NOT NULL,
                played_at INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE $T_SEARCH_HISTORY (
                query TEXT PRIMARY KEY,
                searched_at INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE $T_PLAYLISTS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                created_at INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE $T_PLAYLIST_ITEMS (
                playlist_id INTEGER NOT NULL,
                item_id TEXT NOT NULL,
                json TEXT NOT NULL,
                position INTEGER NOT NULL,
                added_at INTEGER NOT NULL,
                PRIMARY KEY (playlist_id, item_id))"""
        )
        db.execSQL(
            """CREATE TABLE $T_API_CACHE (
                cache_key TEXT PRIMARY KEY,
                body TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                size INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE INDEX idx_recent_played ON $T_RECENT(played_at DESC)")
        db.execSQL("CREATE INDEX idx_cache_created ON $T_API_CACHE(created_at)")
        db.execSQL("CREATE INDEX idx_playlist_items ON $T_PLAYLIST_ITEMS(playlist_id, position)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // First schema version; future migrations go here.
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(false)
    }

    companion object {
        const val NAME = "blazemuzix.db"
        const val VERSION = 1

        const val T_FAVORITES = "favorites"
        const val T_SAVED = "saved_items"
        const val T_RECENT = "recently_played"
        const val T_SEARCH_HISTORY = "search_history"
        const val T_PLAYLISTS = "playlists"
        const val T_PLAYLIST_ITEMS = "playlist_items"
        const val T_API_CACHE = "api_cache"
    }
}
