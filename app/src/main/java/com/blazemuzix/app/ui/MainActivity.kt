package com.blazemuzix.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.blazemuzix.app.BlazeApp
import com.blazemuzix.app.R
import com.blazemuzix.app.data.models.MediaItem
import com.blazemuzix.app.player.PlayerController
import com.blazemuzix.app.player.PlayerState
import com.blazemuzix.app.ui.common.Navigator
import com.blazemuzix.app.ui.home.HomeFragment
import com.blazemuzix.app.ui.library.DetailListFragment
import com.blazemuzix.app.ui.library.LibraryFragment
import com.blazemuzix.app.ui.player.PlayerActivity
import com.blazemuzix.app.ui.search.SearchFragment
import com.blazemuzix.app.utils.Appearance
import com.blazemuzix.app.utils.Artwork
import com.blazemuzix.app.utils.applySystemBarInsets
import com.blazemuzix.app.utils.dp
import com.blazemuzix.app.utils.visible
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity(), Navigator {

    private lateinit var bottomNav: BottomNavigationView
    private lateinit var offlineBanner: View
    private lateinit var miniRoot: View
    private lateinit var miniArtwork: ImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniArtist: TextView
    private lateinit var miniPlayPause: ImageButton
    private lateinit var miniProgress: ProgressBar
    private var lastArtworkId: String? = null
    private var appearanceVersion = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        Appearance.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val prefs = BlazeApp.graph(this).prefs
        appearanceVersion = prefs.appearanceVersion
        val root = findViewById<View>(R.id.root)
        root.applySystemBarInsets(top = true, bottom = true)
        Appearance.decorate(this, root)

        bottomNav = findViewById(R.id.bottom_nav)
        offlineBanner = findViewById(R.id.offline_banner)
        miniRoot = findViewById(R.id.mini_player_root)
        miniArtwork = findViewById(R.id.mini_artwork)
        miniTitle = findViewById(R.id.mini_title)
        miniArtist = findViewById(R.id.mini_artist)
        miniPlayPause = findViewById(R.id.mini_play_pause)
        miniProgress = findViewById(R.id.mini_progress)

        if (savedInstanceState == null) {
            showTab(TAG_HOME)
        }
        bottomNav.setOnItemSelectedListener { menuItem ->
            popDetails()
            when (menuItem.itemId) {
                R.id.nav_home -> showTab(TAG_HOME)
                R.id.nav_search -> showTab(TAG_SEARCH)
                R.id.nav_library -> showTab(TAG_LIBRARY)
            }
            true
        }
        bottomNav.setOnItemReselectedListener { popDetails() }
        run {
            val accent = Appearance.accentColor(this)
            val normal = Appearance.color(this, android.R.attr.textColorSecondary)
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            val tint = android.content.res.ColorStateList(states, intArrayOf(accent, normal))
            bottomNav.itemIconTintList = tint
            bottomNav.itemTextColor = tint
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                miniProgress.progressTintList = android.content.res.ColorStateList.valueOf(accent)
            }
        }
        bottomNav.labelVisibilityMode = when (prefs.navigationStyle) {
            com.blazemuzix.app.data.prefs.AppPreferences.NAV_SELECTED -> com.google.android.material.navigation.NavigationBarView.LABEL_VISIBILITY_SELECTED
            com.blazemuzix.app.data.prefs.AppPreferences.NAV_UNLABELED -> com.google.android.material.navigation.NavigationBarView.LABEL_VISIBILITY_UNLABELED
            else -> com.google.android.material.navigation.NavigationBarView.LABEL_VISIBILITY_LABELED
        }

        findViewById<View>(R.id.mini_player_card).setOnClickListener { openPlayer() }
        miniPlayPause.setOnClickListener { PlayerController.togglePlayPause(this) }
        findViewById<View>(R.id.mini_next).setOnClickListener { PlayerController.next(this) }

        PlayerController.state.observe(this) { renderMiniPlayer(it) }
        PlayerController.position.observe(this) { pos ->
            val duration = PlayerController.current.durationMs
            miniProgress.progress = if (duration > 0) (pos * 1000 / duration).toInt().coerceIn(0, 1000) else 0
        }
        BlazeApp.graph(this).network.online.observe(this) { online -> offlineBanner.visible(!online) }
    }

    override fun onResume() {
        super.onResume()
        val prefs = BlazeApp.graph(this).prefs
        // Re-apply theme changes made in Settings without restarting the task.
        prefs.applyTheme()
        if (appearanceVersion != prefs.appearanceVersion) {
            appearanceVersion = prefs.appearanceVersion
            recreate()
        }
    }

    // ------------------------------------------------------------- navigation

    private fun showTab(tag: String) {
        val fm = supportFragmentManager
        val transaction = fm.beginTransaction()
        var target = fm.findFragmentByTag(tag)
        if (target == null) {
            target = when (tag) {
                TAG_SEARCH -> SearchFragment()
                TAG_LIBRARY -> LibraryFragment()
                else -> HomeFragment()
            }
            transaction.add(R.id.fragment_container, target, tag)
        }
        for (other in fm.fragments) {
            if (other !== target && other.tag in TABS) transaction.hide(other)
        }
        transaction.show(target)
        transaction.setReorderingAllowed(true)
        transaction.commitAllowingStateLoss()
    }

    private fun popDetails() {
        val fm = supportFragmentManager
        if (fm.backStackEntryCount > 0 && !fm.isStateSaved) fm.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
    }

    override fun openCollection(item: MediaItem) {
        val fm = supportFragmentManager
        if (fm.isStateSaved) return
        val reduced = BlazeApp.graph(this).prefs.reducedAnimations
        fm.beginTransaction().apply {
            if (!reduced) setCustomAnimations(R.anim.slide_up_short, R.anim.fade_out_short, R.anim.fade_in_short, R.anim.fade_out_short)
            add(R.id.fragment_container, DetailListFragment.newInstance(item), TAG_DETAIL)
            addToBackStack(TAG_DETAIL)
            commit()
        }
    }

    override fun openPlayer() {
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    fun selectTab(itemId: Int) {
        bottomNav.selectedItemId = itemId
    }

    fun currentTabFragment(): Fragment? = supportFragmentManager.fragments.firstOrNull { it.tag in TABS && it.isVisible }

    // ------------------------------------------------------------ mini player

    private fun renderMiniPlayer(state: PlayerState) {
        val item = state.current
        miniRoot.visible(item != null && BlazeApp.graph(this).prefs.miniPlayerEnabled)
        if (item == null) {
            lastArtworkId = null
            return
        }
        miniTitle.text = item.title
        miniArtist.text = "${item.artist} · ${item.source.label}"
        miniPlayPause.setImageResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        miniPlayPause.contentDescription = getString(if (state.isPlaying) R.string.action_pause else R.string.action_play)
        miniRoot.contentDescription = getString(R.string.cd_mini_player, item.title, item.artist)
        if (lastArtworkId != item.id) {
            lastArtworkId = item.id
            Artwork.load(miniArtwork, item, dp(44), dp(8))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && state.isPlaying) maybeRequestNotificationPermission()
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val prefs = BlazeApp.graph(this).prefs
        if (prefs.notificationPermissionAsked) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        prefs.notificationPermissionAsked = true
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
    }

    companion object {
        const val TAG_HOME = "home"
        const val TAG_SEARCH = "search"
        const val TAG_LIBRARY = "library"
        const val TAG_DETAIL = "detail"
        private val TABS = setOf(TAG_HOME, TAG_SEARCH, TAG_LIBRARY)
        private const val REQ_NOTIFICATIONS = 77
    }
}
