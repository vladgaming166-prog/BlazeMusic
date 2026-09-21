package com.blazemuzix.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager
import com.blazemuzix.app.utils.DeviceProfile

/** All user settings. Stored locally; nothing is synced anywhere. */
class AppPreferences(context: Context) {

    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    private val deviceLowEnd = DeviceProfile.isLowEndDevice(context)

    var theme: String
        get() = prefs.getString(KEY_THEME, THEME_SYSTEM) ?: THEME_SYSTEM
        set(value) = prefs.edit().putString(KEY_THEME, value).apply()

    val autoplay: Boolean get() = prefs.getBoolean(KEY_AUTOPLAY, true)
    val shuffleByDefault: Boolean get() = prefs.getBoolean(KEY_SHUFFLE, false)
    val repeatMode: String get() = prefs.getString(KEY_REPEAT, REPEAT_OFF) ?: REPEAT_OFF

    val reducedAnimations: Boolean
        get() = prefs.getBoolean(KEY_REDUCED_ANIMATIONS, deviceLowEnd)

    val dataSaver: Boolean get() = prefs.getBoolean(KEY_DATA_SAVER, false)

    /** Explicit user choice wins; otherwise detected automatically. */
    val lowEndMode: Boolean
        get() = if (prefs.contains(KEY_LOW_END)) prefs.getBoolean(KEY_LOW_END, deviceLowEnd) else deviceLowEnd

    val cacheLimitMb: Int
        get() = (prefs.getString(KEY_CACHE_LIMIT, null)?.toIntOrNull()) ?: if (deviceLowEnd) 64 else 150

    var lastLibraryTab: Int
        get() = prefs.getInt(KEY_LIBRARY_TAB, 0)
        set(value) = prefs.edit().putInt(KEY_LIBRARY_TAB, value).apply()

    // ------------------------------------------------------------ appearance

    val accent: String get() = prefs.getString(KEY_ACCENT, ACCENT_GREEN) ?: ACCENT_GREEN
    val dynamicAccent: Boolean get() = prefs.getBoolean(KEY_DYNAMIC_ACCENT, false)
    val roundedUi: Boolean get() = prefs.getBoolean(KEY_ROUNDED_UI, true)
    val compactMode: Boolean get() = prefs.getBoolean(KEY_COMPACT, false)
    val showArtwork: Boolean get() = prefs.getBoolean(KEY_SHOW_ARTWORK, true)
    val showArtist: Boolean get() = prefs.getBoolean(KEY_SHOW_ARTIST, true)
    val showAlbum: Boolean get() = prefs.getBoolean(KEY_SHOW_ALBUM, false)
    val showDuration: Boolean get() = prefs.getBoolean(KEY_SHOW_DURATION, true)
    val showSourceBadge: Boolean get() = prefs.getBoolean(KEY_SHOW_BADGE, false)
    val miniPlayerEnabled: Boolean get() = prefs.getBoolean(KEY_MINI_PLAYER, true)
    val backgroundStyle: String get() = prefs.getString(KEY_BACKGROUND_STYLE, BACKGROUND_PLAIN) ?: BACKGROUND_PLAIN
    val navigationStyle: String get() = prefs.getString(KEY_NAV_STYLE, NAV_LABELED) ?: NAV_LABELED
    val statusBarStyle: String get() = prefs.getString(KEY_STATUS_BAR, BAR_MATCH) ?: BAR_MATCH
    val navigationBarStyle: String get() = prefs.getString(KEY_NAV_BAR, BAR_MATCH) ?: BAR_MATCH

    // ---------------------------------------------------------------- player

    val playerLargeArtwork: Boolean get() = prefs.getBoolean(KEY_PLAYER_LARGE_ART, true)
    val playerArtworkBackground: Boolean get() = prefs.getBoolean(KEY_PLAYER_ART_BACKGROUND, true) && !reducedAnimations && !lowEndMode
    val playerShowQueue: Boolean get() = prefs.getBoolean(KEY_PLAYER_QUEUE, true)
    val playerShowShuffle: Boolean get() = prefs.getBoolean(KEY_PLAYER_SHUFFLE, true)
    val playerShowRepeat: Boolean get() = prefs.getBoolean(KEY_PLAYER_REPEAT, true)
    val playerShowFavorite: Boolean get() = prefs.getBoolean(KEY_PLAYER_FAVORITE, true)
    val playerShowSeekBar: Boolean get() = prefs.getBoolean(KEY_PLAYER_SEEK, true)

    /** Corner radius (dp) for artwork and cards; 0 when the user prefers square UI. */
    val cornerRadiusDp: Int get() = if (roundedUi) 12 else 2
    val artworkRadiusDp: Int get() = if (roundedUi) 8 else 2

    var librarySort: String
        get() = prefs.getString(KEY_LIBRARY_SORT, SORT_TITLE) ?: SORT_TITLE
        set(value) = prefs.edit().putString(KEY_LIBRARY_SORT, value).apply()

    /**
     * Bumped whenever a visual setting changes so activities that are already
     * running can recreate themselves once instead of watching every key.
     */
    val appearanceVersion: Int get() = prefs.getInt(KEY_APPEARANCE_VERSION, 0)

    fun bumpAppearanceVersion() = prefs.edit().putInt(KEY_APPEARANCE_VERSION, appearanceVersion + 1).apply()

    fun isAppearanceKey(key: String?): Boolean = key != null && key in APPEARANCE_KEYS

    var notificationPermissionAsked: Boolean
        get() = prefs.getBoolean(KEY_NOTIF_ASKED, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIF_ASKED, value).apply()

    var welcomeCompleted: Boolean
        get() = prefs.getBoolean(KEY_WELCOME, false)
        set(value) = prefs.edit().putBoolean(KEY_WELCOME, value).apply()

    val libraryStyle: String get() = prefs.getString(KEY_LIBRARY_STYLE, STYLE_LIST) ?: STYLE_LIST
    val performanceMode: Boolean get() = lowEndMode

    fun ensureDefaults() {
        if (!prefs.contains(KEY_LOW_END)) prefs.edit().putBoolean(KEY_LOW_END, deviceLowEnd).apply()
        if (!prefs.contains(KEY_REDUCED_ANIMATIONS)) prefs.edit().putBoolean(KEY_REDUCED_ANIMATIONS, deviceLowEnd).apply()
        if (!prefs.contains(KEY_CACHE_LIMIT)) prefs.edit().putString(KEY_CACHE_LIMIT, cacheLimitMb.toString()).apply()
    }

    fun applyTheme() {
        AppCompatDelegate.setDefaultNightMode(
            when (theme) {
                THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                } else {
                    // Older systems have no dark-mode toggle; follow battery saver like AppCompat recommends.
                    AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY
                }
            }
        )
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        const val KEY_THEME = "pref_theme"
        const val KEY_AUTOPLAY = "pref_autoplay"
        const val KEY_SHUFFLE = "pref_shuffle"
        const val KEY_REPEAT = "pref_repeat"
        const val KEY_REDUCED_ANIMATIONS = "pref_reduced_animations"
        const val KEY_DATA_SAVER = "pref_data_saver"
        const val KEY_LOW_END = "pref_low_end_mode"
        const val KEY_CACHE_LIMIT = "pref_cache_limit"
        const val KEY_CLEAR_CACHE = "pref_clear_cache"
        const val KEY_CACHE_SIZE = "pref_cache_size"
        const val KEY_STORAGE_INFO = "pref_storage_info"
        const val KEY_ABOUT = "pref_about"
        const val KEY_VERSION = "pref_version"
        const val KEY_LICENSES = "pref_licenses"
        const val KEY_PRIVACY = "pref_privacy"
        private const val KEY_LIBRARY_TAB = "library_tab"
        private const val KEY_NOTIF_ASKED = "notif_asked"
        private const val KEY_WELCOME = "welcome_completed"
        const val KEY_LIBRARY_STYLE = "pref_library_style"
        const val KEY_PERFORMANCE = "pref_low_end_mode"
        const val STYLE_LIST = "list"
        const val STYLE_GRID = "grid"

        const val KEY_ACCENT = "pref_accent"
        const val KEY_DYNAMIC_ACCENT = "pref_dynamic_accent"
        const val KEY_ROUNDED_UI = "pref_rounded_ui"
        const val KEY_COMPACT = "pref_compact_mode"
        const val KEY_SHOW_ARTWORK = "pref_show_artwork"
        const val KEY_SHOW_ARTIST = "pref_show_artist"
        const val KEY_SHOW_ALBUM = "pref_show_album"
        const val KEY_SHOW_DURATION = "pref_show_duration"
        const val KEY_SHOW_BADGE = "pref_show_badge"
        const val KEY_MINI_PLAYER = "pref_mini_player"
        const val KEY_BACKGROUND_STYLE = "pref_background_style"
        const val KEY_NAV_STYLE = "pref_nav_style"
        const val KEY_STATUS_BAR = "pref_status_bar_style"
        const val KEY_NAV_BAR = "pref_nav_bar_style"
        const val KEY_PLAYER_LARGE_ART = "pref_player_large_artwork"
        const val KEY_PLAYER_ART_BACKGROUND = "pref_player_artwork_background"
        const val KEY_PLAYER_QUEUE = "pref_player_show_queue"
        const val KEY_PLAYER_SHUFFLE = "pref_player_show_shuffle"
        const val KEY_PLAYER_REPEAT = "pref_player_show_repeat"
        const val KEY_PLAYER_FAVORITE = "pref_player_show_favorite"
        const val KEY_PLAYER_SEEK = "pref_player_show_seekbar"
        const val KEY_LIBRARY_SORT = "pref_library_sort"
        private const val KEY_APPEARANCE_VERSION = "appearance_version"

        val APPEARANCE_KEYS = setOf(
            KEY_THEME, KEY_ACCENT, KEY_DYNAMIC_ACCENT, KEY_ROUNDED_UI, KEY_COMPACT, KEY_SHOW_ARTWORK, KEY_SHOW_ARTIST,
            KEY_SHOW_ALBUM, KEY_SHOW_DURATION, KEY_SHOW_BADGE, KEY_MINI_PLAYER, KEY_BACKGROUND_STYLE, KEY_NAV_STYLE,
            KEY_STATUS_BAR, KEY_NAV_BAR, KEY_REDUCED_ANIMATIONS, KEY_LOW_END, KEY_PLAYER_LARGE_ART,
            KEY_PLAYER_ART_BACKGROUND, KEY_PLAYER_QUEUE, KEY_PLAYER_SHUFFLE, KEY_PLAYER_REPEAT, KEY_PLAYER_FAVORITE, KEY_PLAYER_SEEK,
            KEY_LIBRARY_STYLE
        )

        const val ACCENT_GREEN = "green"
        const val ACCENT_EMERALD = "emerald"
        const val ACCENT_MINT = "mint"
        const val ACCENT_LIME = "lime"
        const val ACCENT_TEAL = "teal"
        const val ACCENT_BLUE = "blue"
        const val ACCENT_PURPLE = "purple"
        const val ACCENT_ORANGE = "orange"
        const val ACCENT_RED = "red"

        const val BACKGROUND_PLAIN = "plain"
        const val BACKGROUND_TINTED = "tinted"

        const val NAV_LABELED = "labeled"
        const val NAV_SELECTED = "selected"
        const val NAV_UNLABELED = "unlabeled"

        const val BAR_MATCH = "match"
        const val BAR_ACCENT = "accent"
        const val BAR_BLACK = "black"

        const val SORT_RECENTLY_ADDED = "added"
        const val SORT_RECENTLY_PLAYED = "played"
        const val SORT_TITLE = "title"
        const val SORT_ARTIST = "artist"
        const val SORT_ALBUM = "album"
        const val SORT_DURATION = "duration"

        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        const val THEME_SYSTEM = "system"

        const val REPEAT_OFF = "off"
        const val REPEAT_ALL = "all"
        const val REPEAT_ONE = "one"
    }
}
