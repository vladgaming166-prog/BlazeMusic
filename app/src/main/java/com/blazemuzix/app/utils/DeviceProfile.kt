package com.blazemuzix.app.utils

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/** Heuristics used to switch on "old device mode" automatically. */
object DeviceProfile {

    @Volatile
    private var cached: Boolean? = null

    fun isLowEndDevice(context: Context): Boolean {
        cached?.let { return it }
        val result = try {
            val am = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val totalMb = memInfo.totalMem / (1024 * 1024)
            val lowRamFlag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && am.isLowRamDevice
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                lowRamFlag ||
                am.memoryClass <= 128 ||
                totalMb in 1..1536
        } catch (_: Exception) {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M
        }
        cached = result
        return result
    }

    /** Whether native inline WebView video is reasonable on this device. */
    fun supportsInlineVideo(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && !isLowEndDevice(context)
}
