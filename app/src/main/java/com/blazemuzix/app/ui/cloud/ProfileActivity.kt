package com.blazemuzix.app.ui.cloud

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blazemuzix.app.BlazeApp
import com.blazemuzix.app.R
import com.blazemuzix.app.cloud.CloudProfile
import com.blazemuzix.app.data.models.SectionLayout
import com.blazemuzix.app.ui.common.ItemActions
import com.blazemuzix.app.ui.common.MediaAdapter
import com.blazemuzix.app.utils.Appearance
import com.blazemuzix.app.utils.Artwork
import com.blazemuzix.app.utils.applySystemBarInsets
import com.blazemuzix.app.utils.dp
import com.blazemuzix.app.utils.toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class ProfileActivity : AppCompatActivity(), MediaAdapter.Listener {

    private lateinit var adapter: MediaAdapter
    private var profile: CloudProfile? = null
    private var following = false

    private val photoPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            try {
                BlazeApp.graph(this@ProfileActivity).cloud.uploadAvatar(uri)
                load()
            } catch (e: Exception) {
                toast(e.message ?: getString(R.string.upload_failed))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Appearance.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)
        Appearance.decorate(this, findViewById(R.id.profile_root))
        findViewById<View>(R.id.profile_root).applySystemBarInsets(top = true, bottom = true)
        findViewById<View>(R.id.profile_back).setOnClickListener { finish() }
        adapter = MediaAdapter(SectionLayout.ROWS, this)
        val list = findViewById<RecyclerView>(R.id.profile_list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        findViewById<MaterialButton>(R.id.profile_follow).setOnClickListener { toggleFollow() }
        findViewById<MaterialButton>(R.id.profile_edit).setOnClickListener { edit() }
        lifecycleScope.launch { load() }
    }

    private suspend fun load() {
        val graph = BlazeApp.graph(this)
        val id = intent.getStringExtra(EXTRA_ID) ?: graph.cloud.myId()
        if (id.isNullOrBlank()) {
            toast(getString(R.string.auth_guest_message)); finish(); return
        }
        val loaded = try {
            graph.cloud.profile(id)
        } catch (e: Exception) {
            toast(e.message ?: getString(R.string.auth_cloud_not_configured)); finish(); return
        }
        profile = loaded
        if (loaded == null) {
            toast(getString(R.string.state_invalid_request_message)); finish(); return
        }
        Artwork.load(findViewById(R.id.profile_photo), loaded.avatar, com.blazemuzix.app.data.models.MediaType.USER, dp(88), dp(44))
        findViewById<TextView>(R.id.profile_name).text = loaded.username ?: loaded.email ?: getString(R.string.auth_signed_in_fallback)
        findViewById<TextView>(R.id.profile_bio).text = loaded.bio.orEmpty()
        val mine = graph.cloud.myId() == loaded.id
        findViewById<MaterialButton>(R.id.profile_edit).visibility = if (mine) View.VISIBLE else View.GONE
        findViewById<MaterialButton>(R.id.profile_follow).visibility = if (mine) View.GONE else View.VISIBLE
        following = if (!mine) runCatching { graph.cloud.isFollowing(loaded.id) }.getOrDefault(false) else false
        findViewById<MaterialButton>(R.id.profile_follow).setText(if (following) R.string.profile_following else R.string.profile_follow)
        val tracks = runCatching { graph.cloud.tracksByOwner(loaded.id) }.getOrDefault(emptyList()).map { it.toMediaItem() }
        val playlists = runCatching { graph.cloud.playlistsByOwner(loaded.id) }.getOrDefault(emptyList()).map { it.toMediaItem() }
        adapter.submitItems(tracks + playlists)
    }

    private fun toggleFollow() {
        val id = profile?.id ?: return
        lifecycleScope.launch {
            try {
                following = BlazeApp.graph(this@ProfileActivity).cloud.followUser(id)
                findViewById<MaterialButton>(R.id.profile_follow).setText(if (following) R.string.profile_following else R.string.profile_follow)
            } catch (e: Exception) {
                toast(e.message ?: getString(R.string.auth_guest_message))
            }
        }
    }

    private fun edit() {
        val view = layoutInflater.inflate(R.layout.dialog_text_input, null)
        val input = view.findViewById<EditText>(R.id.dialog_input)
        input.setText(profile?.username.orEmpty())
        input.hint = getString(R.string.auth_username)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.profile_edit)
            .setView(view)
            .setPositiveButton(R.string.action_save_profile) { _, _ ->
                lifecycleScope.launch {
                    try {
                        BlazeApp.graph(this@ProfileActivity).cloud.updateProfile(input.text?.toString(), profile?.bio, null)
                        load()
                        toast(R.string.profile_saved)
                    } catch (e: Exception) {
                        toast(e.message ?: getString(R.string.upload_failed))
                    }
                }
            }
            .setNeutralButton(R.string.auth_choose_photo) { _, _ -> photoPicker.launch("image/*") }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    override fun onItemClick(item: com.blazemuzix.app.data.models.MediaItem, position: Int) {
        ItemActions.primary(this, item)
    }

    override fun onItemMore(item: com.blazemuzix.app.data.models.MediaItem) {
        com.blazemuzix.app.ui.common.ItemActionsSheet.show(supportFragmentManager, item)
    }

    companion object {
        const val EXTRA_ID = "user_id"
    }
}
