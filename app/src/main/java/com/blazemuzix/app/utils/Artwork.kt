package com.blazemuzix.app.utils

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.widget.ImageView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.blazemuzix.app.BlazeApp
import com.blazemuzix.app.R
import com.blazemuzix.app.data.models.MediaItem
import com.blazemuzix.app.data.models.MediaType
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Central artwork loader. Every image is downsampled to the size of its view
 * (never decoded at full resolution), cached on disk with a bounded size, and
 * loaded with a coherent placeholder so lists never look empty.
 */
object Artwork {

    private val placeholderCache = HashMap<Int, Drawable>()

    fun load(view: ImageView, item: MediaItem?, sizePx: Int, cornerRadiusPx: Int = 0) {
        load(view, item?.artworkUrl, item?.type ?: MediaType.SONG, sizePx, cornerRadiusPx)
    }

    fun load(view: ImageView, url: String?, type: MediaType, sizePx: Int, cornerRadiusPx: Int = 0) {
        val context = view.context
        val placeholder = placeholder(context, type, cornerRadiusPx, sizePx)
        if (url.isNullOrEmpty()) {
            Glide.with(view).clear(view)
            view.setImageDrawable(placeholder)
            return
        }
        val prefs = BlazeApp.graph(context).prefs
        val lowEnd = prefs.lowEndMode || prefs.dataSaver
        val target = if (lowEnd) (sizePx * 0.7f).toInt().coerceAtLeast(64) else sizePx
        var options = RequestOptions()
            .override(target, target)
            .centerCrop()
            .placeholder(placeholder)
            .error(placeholder)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
        if (lowEnd) options = options.format(DecodeFormat.PREFER_RGB_565)
        val request = Glide.with(view).load(if (url.startsWith("content://")) Uri.parse(url) else url).apply(options)
        if (prefs.reducedAnimations) request.dontAnimate()
        request.into(view)
    }

    fun clear(view: ImageView) {
        try {
            Glide.with(view).clear(view)
        } catch (_: Exception) {
        }
    }

    /** Loads a bitmap for the media notification / lock screen (small, bounded). */
    suspend fun loadBitmap(context: Context, url: String?, sizePx: Int): Bitmap? {
        if (url.isNullOrEmpty()) return null
        return withContext(Dispatchers.IO) {
            try {
                Glide.with(context.applicationContext)
                    .asBitmap()
                    .load(if (url.startsWith("content://")) Uri.parse(url) else url)
                    .apply(RequestOptions().override(sizePx, sizePx).centerCrop().format(DecodeFormat.PREFER_RGB_565))
                    .submit(sizePx, sizePx)
                    .get()
            } catch (_: Exception) {
                null
            }
        }
    }

    fun loadInto(context: Context, url: String?, sizePx: Int, onReady: (Bitmap?) -> Unit) {
        if (url.isNullOrEmpty()) {
            onReady(null)
            return
        }
        Glide.with(context.applicationContext)
            .asBitmap()
            .load(if (url.startsWith("content://")) Uri.parse(url) else url)
            .apply(RequestOptions().override(sizePx, sizePx).centerCrop())
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) = onReady(resource)
                override fun onLoadCleared(placeholder: Drawable?) {}
                override fun onLoadFailed(errorDrawable: Drawable?) = onReady(null)
            })
    }

    /** Rounded surface with a centred type icon; works on API 19 through AppCompat vector inflation. */
    fun placeholder(context: Context, type: MediaType, cornerRadiusPx: Int, sizePx: Int = 0): Drawable {
        val key = (type.ordinal * 100_000 + cornerRadiusPx + if (isNight(context)) 50_000 else 0) * 10_000 + (sizePx / 8)
        placeholderCache[key]?.let { return it.constantState?.newDrawable()?.mutate() ?: it }
        val background = GradientDrawable().apply {
            shape = if (type == MediaType.ARTIST) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx.toFloat()
            setColor(ContextCompat.getColor(context, R.color.bm_artwork_placeholder))
        }
        val iconRes = when (type) {
            MediaType.ALBUM -> R.drawable.ic_album
            MediaType.ARTIST -> R.drawable.ic_person
            MediaType.PLAYLIST -> R.drawable.ic_playlist_play
            MediaType.VIDEO, MediaType.SHORT -> R.drawable.ic_video
            MediaType.SONG -> R.drawable.ic_music_note
        }
        val icon = AppCompatResources.getDrawable(context, iconRes)?.mutate()?.also {
            DrawableCompat.setTint(DrawableCompat.wrap(it), ContextCompat.getColor(context, R.color.bm_artwork_placeholder_icon))
        }
        val layer = if (icon != null) {
            LayerDrawable(arrayOf(background, icon)).apply {
                // LayerDrawable gravity is API 23+, insets work everywhere: keep the icon at ~40% of the tile.
                val inset = if (sizePx > 0) (sizePx * 0.3f).toInt() else 30
                setLayerInset(1, inset, inset, inset, inset)
            }
        } else {
            background
        }
        placeholderCache[key] = layer
        return layer
    }

    private fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    fun trimMemory(context: Context, level: Int) {
        try {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
                Glide.get(context).clearMemory()
            } else {
                Glide.get(context).trimMemory(level)
            }
        } catch (_: Exception) {
        }
        placeholderCache.clear()
    }

    fun diskCacheDir(context: Context): File = File(context.cacheDir, "artwork")

    fun diskCacheSizeBytes(context: Context): Long = folderSize(diskCacheDir(context))

    suspend fun clearDiskCache(context: Context) = withContext(Dispatchers.IO) {
        try {
            Glide.get(context.applicationContext).clearDiskCache()
        } catch (_: Exception) {
        }
    }

    fun clearMemoryCache(context: Context) {
        try {
            Glide.get(context.applicationContext).clearMemory()
        } catch (_: Exception) {
        }
    }

    fun folderSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var size = 0L
        dir.listFiles()?.forEach { size += if (it.isDirectory) folderSize(it) else it.length() }
        return size
    }
}
