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

    var notificationPermissionAsked: Boolean
        get() = prefs.getBoolean(KEY_NOTIF_ASKED, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIF_ASKED, value).apply()

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
        const val KEY_PROVIDER_YOUTUBE = "pref_provider_youtube"
        const val KEY_PROVIDER_SPOTIFY = "pref_provider_spotify"
        const val KEY_PROVIDER_LOCAL = "pref_provider_local"
        const val KEY_PRIVACY = "pref_privacy"
        private const val KEY_LIBRARY_TAB = "library_tab"
        private const val KEY_NOTIF_ASKED = "notif_asked"

        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        const val THEME_SYSTEM = "system"

        const val REPEAT_OFF = "off"
        const val REPEAT_ALL = "all"
        const val REPEAT_ONE = "one"
    }
}
