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

    private var appearanceVersion = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        com.blazemuzix.app.utils.Appearance.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        appearanceVersion = BlazeApp.graph(this).prefs.appearanceVersion
        findViewById<View>(android.R.id.content).applySystemBarInsets(top = true, bottom = true)
        com.blazemuzix.app.utils.Appearance.decorate(this, findViewById(R.id.settings_container))
        findViewById<Toolbar>(R.id.settings_toolbar).setNavigationOnClickListener { finish() }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.settings_container, SettingsFragment()).commit()
        }
    }

    override fun onResume() {
        super.onResume()
        val prefs = BlazeApp.graph(this).prefs
        if (appearanceVersion != prefs.appearanceVersion) {
            appearanceVersion = prefs.appearanceVersion
            recreate()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat(), SharedPreferences.OnSharedPreferenceChangeListener {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            findPreference<androidx.preference.SwitchPreferenceCompat>(AppPreferences.KEY_DYNAMIC_ACCENT)?.let { pref ->
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
                    pref.isEnabled = false
                    pref.isChecked = false
                    pref.summary = getString(R.string.settings_dynamic_accent_unavailable)
                }
            }
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.LOLLIPOP) {
                findPreference<Preference>(AppPreferences.KEY_STATUS_BAR)?.apply { isEnabled = false; summary = getString(R.string.settings_bar_style_note) }
                findPreference<Preference>(AppPreferences.KEY_NAV_BAR)?.apply { isEnabled = false; summary = getString(R.string.settings_bar_style_note) }
            }
            val graph = BlazeApp.graph(requireContext())
            bindProviders(graph)
            findPreference<Preference>("pref_provider_spotify")?.setOnPreferenceClickListener {
                startActivity(android.content.Intent(requireContext(), com.blazemuzix.app.auth.AccountActivity::class.java))
                true
            }
            findPreference<Preference>("pref_account")?.apply {
                summary = graph.auth.current?.email ?: getString(R.string.auth_guest)
                setOnPreferenceClickListener {
                    startActivity(android.content.Intent(requireContext(), com.blazemuzix.app.auth.AccountActivity::class.java))
                    true
                }
            }
            findPreference<Preference>("pref_privacy")?.setOnPreferenceClickListener {
                startActivity(android.content.Intent(requireContext(), com.blazemuzix.app.auth.AccountActivity::class.java))
                true
            }

            findPreference<Preference>(AppPreferences.KEY_VERSION)?.summary = BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"

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
            bindProviders(BlazeApp.graph(requireContext()))
        }

        private fun bindProviders(graph: com.blazemuzix.app.BlazeApp.Graph) {
            val statuses = graph.providerManager.statuses()
            statuses.forEach { status ->
                val key = when (status.source) {
                    com.blazemuzix.app.data.models.Source.YOUTUBE -> "pref_provider_youtube"
                    com.blazemuzix.app.data.models.Source.SPOTIFY -> "pref_provider_spotify"
                    com.blazemuzix.app.data.models.Source.CLOUD -> "pref_provider_cloud"
                    else -> "pref_provider_local"
                }
                findPreference<Preference>(key)?.summary = status.detail
            }
            findPreference<Preference>("pref_account")?.summary =
                graph.auth.current?.email ?: getString(R.string.auth_guest)
        }

        override fun onPause() {
            super.onPause()
            preferenceScreen.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            val prefs = BlazeApp.graph(requireContext()).prefs
            when (key) {
                AppPreferences.KEY_THEME -> prefs.applyTheme()
                AppPreferences.KEY_LOW_END, AppPreferences.KEY_DATA_SAVER -> Artwork.clearMemoryCache(requireContext())
            }
            // Visual settings: bump once so Main/Player/Settings recreate with the new look.
            if (prefs.isAppearanceKey(key)) {
                prefs.bumpAppearanceVersion()
                if (key == AppPreferences.KEY_ACCENT || key == AppPreferences.KEY_DYNAMIC_ACCENT || key == AppPreferences.KEY_BACKGROUND_STYLE ||
                    key == AppPreferences.KEY_STATUS_BAR || key == AppPreferences.KEY_NAV_BAR
                ) {
                    activity?.recreate()
                }
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
