package com.blazemuzix.app.ui.settings

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.blazemuzix.app.BlazeApp
import com.blazemuzix.app.BuildConfig
import com.blazemuzix.app.R
import com.blazemuzix.app.data.prefs.AppPreferences
import com.blazemuzix.app.utils.Artwork
import com.blazemuzix.app.utils.Formatters
import com.blazemuzix.app.utils.applySystemBarInsets
import com.blazemuzix.app.utils.toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<View>(android.R.id.content).applySystemBarInsets(top = true, bottom = true)
        findViewById<Toolbar>(R.id.settings_toolbar).setNavigationOnClickListener { finish() }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.settings_container, SettingsFragment()).commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat(), SharedPreferences.OnSharedPreferenceChangeListener {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            val graph = BlazeApp.graph(requireContext())

            findPreference<Preference>(AppPreferences.KEY_VERSION)?.summary = BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"
            findPreference<Preference>(AppPreferences.KEY_PROVIDER_YOUTUBE)?.summary =
                getString(if (graph.youtubeProvider.isConfigured) R.string.settings_provider_status_configured else R.string.settings_provider_status_missing)
            findPreference<Preference>(AppPreferences.KEY_PROVIDER_SPOTIFY)?.summary =
                getString(if (graph.spotifyProvider.isConfigured) R.string.settings_provider_status_configured else R.string.settings_provider_status_missing)

            findPreference<Preference>(AppPreferences.KEY_CLEAR_CACHE)?.setOnPreferenceClickListener {
                clearCache()
                true
            }
            findPreference<Preference>(AppPreferences.KEY_ABOUT)?.setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.settings_about)
                    .setMessage(getString(R.string.about_message, BuildConfig.VERSION_NAME))
                    .setPositiveButton(R.string.action_close, null)
                    .show()
                true
            }
            findPreference<Preference>(AppPreferences.KEY_LICENSES)?.setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.settings_licenses)
                    .setMessage(R.string.licenses_text)
                    .setPositiveButton(R.string.action_close, null)
                    .show()
                true
            }
            findPreference<ListPreference>(AppPreferences.KEY_CACHE_LIMIT)?.let { pref ->
                if (pref.value == null) pref.value = graph.prefs.cacheLimitMb.toString()
            }
            refreshStorageInfo()
        }

        override fun onResume() {
            super.onResume()
            preferenceScreen.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onPause() {
            super.onPause()
            preferenceScreen.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            when (key) {
                AppPreferences.KEY_THEME -> BlazeApp.graph(requireContext()).prefs.applyTheme()
                AppPreferences.KEY_LOW_END, AppPreferences.KEY_DATA_SAVER -> Artwork.clearMemoryCache(requireContext())
            }
        }

        private fun refreshStorageInfo() {
            val context = context ?: return
            val graph = BlazeApp.graph(context)
            lifecycleScope.launch {
                val (cacheBytes, localCount, localBytes) = withContext(Dispatchers.IO) {
                    val cache = Artwork.diskCacheSizeBytes(context) + graph.responseCache.sizeBytes()
                    val songs = if (graph.localProvider.hasPermission()) graph.localProvider.allSongs() else emptyList()
                    var bytes = 0L
                    for (song in songs) {
                        val path = song.localPath ?: continue
                        try { bytes += java.io.File(path).length() } catch (_: Exception) {}
                    }
                    Triple(cache, songs.size, bytes)
                }
                findPreference<Preference>(AppPreferences.KEY_CACHE_SIZE)?.summary = getString(R.string.settings_cache_size_summary, Formatters.bytes(cacheBytes))
                findPreference<Preference>(AppPreferences.KEY_STORAGE_INFO)?.summary =
                    getString(R.string.settings_storage_info_summary, localCount, Formatters.bytes(localBytes))
            }
        }

        private fun clearCache() {
            val context = requireContext()
            val graph = BlazeApp.graph(context)
            lifecycleScope.launch {
                Artwork.clearMemoryCache(context)
                Artwork.clearDiskCache(context)
                withContext(Dispatchers.IO) { graph.responseCache.clear() }
                context.toast(R.string.settings_cache_cleared)
                refreshStorageInfo()
            }
        }
    }
}
