package com.blazemuzix.app.utils

import android.content.Context
import com.blazemuzix.app.BlazeApp
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.Registry
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.cache.DiskLruCacheFactory
import com.bumptech.glide.load.engine.cache.LruResourceCache
import com.bumptech.glide.load.engine.cache.MemorySizeCalculator
import com.bumptech.glide.module.GlideModule
import com.bumptech.glide.request.RequestOptions

/**
 * Configures Glide's caches from user settings (registered in the manifest so no
 * annotation processor is needed). Bounded disk cache, small memory cache on
 * low-end devices and RGB_565 decoding to halve bitmap memory there.
 */
@Suppress("DEPRECATION")
class BlazeGlideModule : GlideModule {

    override fun applyOptions(context: Context, builder: GlideBuilder) {
        val prefs = BlazeApp.graph(context).prefs
        val lowEnd = prefs.lowEndMode
        val diskBytes = prefs.cacheLimitMb.toLong() * 1024 * 1024
        builder.setDiskCache(DiskLruCacheFactory({ Artwork.diskCacheDir(context) }, diskBytes))

        val calculator = MemorySizeCalculator.Builder(context)
            .setMemoryCacheScreens(if (lowEnd) 1f else 2f)
            .setBitmapPoolScreens(if (lowEnd) 1f else 3f)
            .build()
        builder.setMemoryCache(LruResourceCache(calculator.memoryCacheSize.toLong()))

        val defaults = RequestOptions().format(if (lowEnd) DecodeFormat.PREFER_RGB_565 else DecodeFormat.PREFER_ARGB_8888)
        builder.setDefaultRequestOptions(defaults)
        builder.setLogLevel(android.util.Log.ERROR)
    }

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        // Default components are sufficient.
    }
}
