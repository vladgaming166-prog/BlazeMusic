package com.blazemuzix.app.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.blazemuzix.app.R
import com.blazemuzix.app.data.models.MediaItem
import com.blazemuzix.app.data.models.MediaType
import com.blazemuzix.app.data.models.Source
import com.blazemuzix.app.network.ApiException
import java.util.Locale
import java.util.concurrent.TimeUnit

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
fun View.dp(value: Int): Int = context.dp(value)

fun View.visible(show: Boolean) {
    visibility = if (show) View.VISIBLE else View.GONE
}

fun Context.toast(text: CharSequence) {
    Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}

fun Context.toast(resId: Int) {
    Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
}

object Formatters {
    fun duration(ms: Long): String {
        if (ms <= 0) return "--:--"
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms)
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun position(ms: Long): String = if (ms < 0) "0:00" else duration(ms).let { if (it == "--:--") "0:00" else it }

    fun bytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }
}

/** Human-readable title/message for every failure type. */
data class ErrorCopy(val title: String, val message: String, val iconRes: Int, val offline: Boolean = false)

fun Throwable.toErrorCopy(context: Context): ErrorCopy = when (this) {
    is ApiException.Offline -> ErrorCopy(
        context.getString(R.string.state_offline_title), context.getString(R.string.state_offline_message), R.drawable.ic_wifi_off, offline = true
    )
    is ApiException.Timeout -> ErrorCopy(context.getString(R.string.state_error_title), context.getString(R.string.state_timeout_message), R.drawable.ic_error_outline)
    is ApiException.RateLimited -> ErrorCopy(context.getString(R.string.state_rate_limited_title), context.getString(R.string.state_rate_limited_message), R.drawable.ic_error_outline)
    is ApiException.Unauthorized -> ErrorCopy(context.getString(R.string.state_unauthorized_title), context.getString(R.string.state_unauthorized_message), R.drawable.ic_lock)
    is ApiException.InvalidRequest -> ErrorCopy(context.getString(R.string.state_invalid_request_title), context.getString(R.string.state_invalid_request_message), R.drawable.ic_error_outline)
    is ApiException.ServerError, is ApiException.Network, is ApiException.Parse -> ErrorCopy(
        context.getString(R.string.state_provider_unavailable_title), context.getString(R.string.state_provider_unavailable_message), R.drawable.ic_cloud_off
    )
    is ApiException.NotConfigured -> ErrorCopy(
        context.getString(R.string.provider_not_configured, provider), context.getString(R.string.home_no_providers_message), R.drawable.ic_info
    )
    else -> ErrorCopy(context.getString(R.string.state_error_title), context.getString(R.string.state_error_message), R.drawable.ic_error_outline)
}

fun MediaType.label(context: Context): String = when (this) {
    MediaType.SONG -> context.getString(R.string.type_song)
    MediaType.ALBUM -> context.getString(R.string.type_album)
    MediaType.ARTIST -> context.getString(R.string.type_artist)
    MediaType.PLAYLIST -> context.getString(R.string.type_playlist)
    MediaType.VIDEO -> context.getString(R.string.type_video)
    MediaType.SHORT -> context.getString(R.string.type_short)
}

fun Source.color(context: Context): Int = androidx.core.content.ContextCompat.getColor(
    context,
    when (this) {
        Source.YOUTUBE, Source.YOUTUBE_MUSIC -> R.color.source_youtube
        Source.SPOTIFY -> R.color.source_spotify
        Source.LOCAL -> R.color.source_local
    }
)

/** Opens an item in its official app, falling back to the browser. Never bypasses anything. */
object ExternalActions {

    fun openExternal(context: Context, item: MediaItem): Boolean {
        val url = item.externalUrl ?: return false
        val appIntent = when (item.source) {
            Source.SPOTIFY -> Intent(Intent.ACTION_VIEW, Uri.parse("spotify:${spotifyType(item.type)}:${item.providerId}")).setPackage("com.spotify.music")
            Source.YOUTUBE, Source.YOUTUBE_MUSIC -> Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.google.android.youtube")
            Source.LOCAL -> null
        }
        if (appIntent != null && canResolve(context, appIntent)) {
            return start(context, appIntent)
        }
        return openUrl(context, url)
    }

    fun openInYouTubeMusic(context: Context, item: MediaItem): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/watch?v=${item.providerId}"))
            .setPackage("com.google.android.apps.youtube.music")
        return if (canResolve(context, intent)) start(context, intent) else openUrl(context, "https://music.youtube.com/watch?v=${item.providerId}")
    }

    fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: Exception) {
        false
    }

    fun openUrl(context: Context, url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        return start(context, intent)
    }

    fun share(context: Context, item: MediaItem) {
        val link = item.externalUrl ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.share_subject))
            putExtra(Intent.EXTRA_TEXT, context.getString(R.string.share_text, item.title, item.artist, link))
        }
        start(context, Intent.createChooser(intent, context.getString(R.string.action_share)))
    }

    private fun spotifyType(type: MediaType) = when (type) {
        MediaType.ALBUM -> "album"
        MediaType.ARTIST -> "artist"
        MediaType.PLAYLIST -> "playlist"
        else -> "track"
    }

    private fun canResolve(context: Context, intent: Intent): Boolean = try {
        context.packageManager.resolveActivity(intent, 0) != null
    } catch (_: Exception) {
        false
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }
}

/** Adds system-bar insets as padding (API 21+); older versions are unaffected. */
fun View.applySystemBarInsets(top: Boolean = false, bottom: Boolean = false) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
    val startTop = paddingTop
    val startBottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.updatePadding(
            top = if (top) startTop + bars.top else v.paddingTop,
            bottom = if (bottom) startBottom + bars.bottom else v.paddingBottom
        )
        insets
    }
    if (isAttachedToWindow) ViewCompat.requestApplyInsets(this)
}

fun ViewGroup.inflateChild(layoutRes: Int): View =
    android.view.LayoutInflater.from(context).inflate(layoutRes, this, false)
