package com.blazemuzix.app.utils

import android.content.Context
import android.os.Build
import android.util.Log
import com.blazemuzix.app.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Development crash diagnostics. Installed only in debug builds: every uncaught
 * exception is written to Logcat under one tag and to `files/crash/last-crash.txt`
 * (readable with `adb shell run-as com.blazemuzix.app.debug cat files/crash/last-crash.txt`),
 * then handed to the system handler so Android still shows the normal crash flow.
 * Nothing is sent anywhere; the file stays on the device.
 */
object CrashDiagnostics {

    const val TAG = "BlazeMuzixCrash"

    fun install(context: Context) {
        if (!BuildConfig.DEBUG) return
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(appContext, previous))
        lastCrash(appContext)?.let { Log.w(TAG, "Previous run crashed:\n$it") }
    }

    fun lastCrash(context: Context): String? =
        try {
            crashFile(context).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }

    private fun crashFile(context: Context): File = File(File(context.filesDir, "crash"), "last-crash.txt")

    private class Handler(
        private val context: Context,
        private val delegate: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {

        override fun uncaughtException(thread: Thread, error: Throwable) {
            try {
                val report = buildReport(thread, error)
                Log.e(TAG, report)
                crashFile(context).apply { parentFile?.mkdirs() }.writeText(report)
            } catch (_: Exception) {
                // Diagnostics must never mask the original crash.
            } finally {
                delegate?.uncaughtException(thread, error)
            }
        }

        private fun buildReport(thread: Thread, error: Throwable): String {
            val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            return buildString {
                appendLine("BlazeMuzix ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) crashed at $time")
                appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Thread: ${thread.name}")
                appendLine()
                append(trace)
            }
        }
    }
}
