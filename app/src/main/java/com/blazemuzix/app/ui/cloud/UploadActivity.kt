package com.blazemuzix.app.ui.cloud

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.blazemuzix.app.BlazeApp
import com.blazemuzix.app.R
import com.blazemuzix.app.auth.SignInActivity
import com.blazemuzix.app.cloud.CloudRepository
import com.blazemuzix.app.network.ApiException
import com.blazemuzix.app.utils.Appearance
import com.blazemuzix.app.utils.Artwork
import com.blazemuzix.app.utils.Formatters
import com.blazemuzix.app.utils.applySystemBarInsets
import com.blazemuzix.app.utils.dp
import com.blazemuzix.app.utils.toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class UploadActivity : AppCompatActivity() {

    private var audioUri: Uri? = null
    private var coverUri: Uri? = null
    private var audioInfo: CloudRepository.LocalFileInfo? = null
    private var job: Job? = null

    private val audioPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        audioUri = uri
        val cloud = BlazeApp.graph(this).cloud
        try {
            val info = cloud.inspectAudio(uri)
            cloud.validateAudio(info)
            audioInfo = info
            findViewById<TextView>(R.id.upload_file_info).text = getString(
                R.string.upload_file_info,
                info.name,
                Formatters.bytes(info.sizeBytes),
                Formatters.duration(info.durationMs)
            )
            if (findViewById<TextInputEditText>(R.id.upload_title_input).text.isNullOrBlank()) {
                findViewById<TextInputEditText>(R.id.upload_title_input).setText(info.name.substringBeforeLast('.'))
            }
            showError(null)
        } catch (e: Exception) {
            audioUri = null
            showError(e.message ?: getString(R.string.upload_need_file))
        }
    }

    private val coverPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        coverUri = uri
        if (uri != null) Artwork.load(findViewById(R.id.upload_cover), uri.toString(), com.blazemuzix.app.data.models.MediaType.ALBUM, dp(120), dp(12))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Appearance.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_upload)
        Appearance.decorate(this, findViewById(R.id.upload_root))
        findViewById<View>(R.id.upload_root).applySystemBarInsets(top = true, bottom = true)
        findViewById<View>(R.id.upload_back).setOnClickListener { finish() }
        val graph = BlazeApp.graph(this)
        if (!graph.auth.hasCloudSession()) {
            startActivity(android.content.Intent(this, SignInActivity::class.java))
            toast(R.string.upload_need_sign_in)
            finish()
            return
        }
        findViewById<MaterialButton>(R.id.upload_pick_audio).setOnClickListener { audioPicker.launch("audio/*") }
        findViewById<MaterialButton>(R.id.upload_pick_cover).setOnClickListener { coverPicker.launch("image/*") }
        findViewById<MaterialButton>(R.id.upload_submit).setOnClickListener { startUpload() }
        findViewById<MaterialButton>(R.id.upload_cancel).setOnClickListener {
            job?.cancel()
            setUploading(false)
        }
    }

    private fun startUpload() {
        val audio = audioUri
        if (audio == null) {
            showError(getString(R.string.upload_need_file)); return
        }
        val title = findViewById<TextInputEditText>(R.id.upload_title_input).text?.toString().orEmpty()
        if (title.isBlank()) {
            showError(getString(R.string.upload_need_title)); return
        }
        showError(null)
        val graph = BlazeApp.graph(this)
        val draft = CloudRepository.UploadDraft(
            audioUri = audio,
            coverUri = coverUri,
            title = title,
            artist = findViewById<TextInputEditText>(R.id.upload_artist).text?.toString().orEmpty(),
            description = findViewById<TextInputEditText>(R.id.upload_description).text?.toString(),
            album = findViewById<TextInputEditText>(R.id.upload_album).text?.toString(),
            genre = findViewById<TextInputEditText>(R.id.upload_genre).text?.toString(),
            year = findViewById<TextInputEditText>(R.id.upload_year).text?.toString()?.toIntOrNull() ?: 0,
            visibility = if (findViewById<CheckBox>(R.id.upload_private).isChecked) "private" else "public"
        )
        setUploading(true)
        job = lifecycleScope.launch {
            try {
                val track = graph.cloud.uploadTrack(draft) { percent ->
                    runOnUiThread {
                        findViewById<ProgressBar>(R.id.upload_bar).progress = percent
                        findViewById<TextView>(R.id.upload_percent).text = getString(R.string.upload_progress, percent)
                    }
                }
                toast(getString(R.string.upload_done, track.title))
                startActivity(android.content.Intent(this@UploadActivity, TrackActivity::class.java).putExtra(TrackActivity.EXTRA_ID, track.id))
                finish()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(if (e is ApiException.Offline || e is ApiException.Network) getString(R.string.upload_failed) else (e.message ?: getString(R.string.upload_failed)))
                findViewById<MaterialButton>(R.id.upload_submit).setText(R.string.upload_retry)
            } finally {
                setUploading(false)
            }
        }
    }

    private fun setUploading(uploading: Boolean) {
        findViewById<ProgressBar>(R.id.upload_bar).visibility = if (uploading) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.upload_percent).visibility = if (uploading) View.VISIBLE else View.GONE
        findViewById<MaterialButton>(R.id.upload_cancel).visibility = if (uploading) View.VISIBLE else View.GONE
        findViewById<MaterialButton>(R.id.upload_submit).isEnabled = !uploading
    }

    private fun showError(message: String?) {
        val view = findViewById<TextView>(R.id.upload_error)
        view.visibility = if (message.isNullOrBlank()) View.GONE else View.VISIBLE
        view.text = message
    }
}
