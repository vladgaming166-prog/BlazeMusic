package com.blazemuzix.app.utils

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.View
import androidx.annotation.AttrRes
import androidx.core.graphics.ColorUtils
import com.blazemuzix.app.BlazeApp
import com.blazemuzix.app.R
import com.blazemuzix.app.data.prefs.AppPreferences
import com.google.android.material.color.DynamicColors

/**
 * Applies the user's visual settings to an activity. Everything here is cheap:
 * theme overlays, a couple of window colours and one gradient drawable.
 * Every step degrades gracefully on Android 4.4 (no system-bar colours there).
 */
object Appearance {

    /** Call before `setContentView`. */
    fun apply(activity: Activity) {
        val prefs = BlazeApp.graph(activity).prefs
        if (prefs.dynamicAccent && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            DynamicColors.applyToActivityIfAvailable(activity)
        } else {
            accentOverlay(prefs.accent)?.let { activity.theme.applyStyle(it, true) }
        }
    }

    /** Call after `setContentView`; colours system bars and the root background. */
    fun decorate(activity: Activity, root: View?) {
        val prefs = BlazeApp.graph(activity).prefs
        val background = color(activity, android.R.attr.colorBackground)
        val accent = color(activity, androidx.appcompat.R.attr.colorPrimary)
        if (root != null) {
            if (prefs.backgroundStyle == AppPreferences.BACKGROUND_TINTED && !prefs.lowEndMode) {
                root.background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(ColorUtils.blendARGB(background, accent, 0.10f), background, background)
                )
            } else {
                root.setBackgroundColor(background)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val window = activity.window
            window.statusBarColor = when (prefs.statusBarStyle) {
                AppPreferences.BAR_ACCENT -> ColorUtils.blendARGB(background, accent, 0.18f)
                AppPreferences.BAR_BLACK -> Color.BLACK
                else -> background
            }
            window.navigationBarColor = when (prefs.navigationBarStyle) {
                AppPreferences.BAR_ACCENT -> ColorUtils.blendARGB(background, accent, 0.18f)
                AppPreferences.BAR_BLACK -> Color.BLACK
                else -> background
            }
        }
    }

    fun accentOverlay(accent: String): Int? = when (accent) {
        AppPreferences.ACCENT_TEAL -> R.style.ThemeOverlay_BlazeMuzix_Accent_Teal
        AppPreferences.ACCENT_BLUE -> R.style.ThemeOverlay_BlazeMuzix_Accent_Blue
        AppPreferences.ACCENT_PURPLE -> R.style.ThemeOverlay_BlazeMuzix_Accent_Purple
        AppPreferences.ACCENT_ORANGE -> R.style.ThemeOverlay_BlazeMuzix_Accent_Orange
        AppPreferences.ACCENT_RED -> R.style.ThemeOverlay_BlazeMuzix_Accent_Red
        else -> null
    }

    fun color(activity: Activity, @AttrRes attr: Int): Int {
        val value = TypedValue()
        return if (activity.theme.resolveAttribute(attr, value, true)) {
            if (value.resourceId != 0) androidx.core.content.ContextCompat.getColor(activity, value.resourceId) else value.data
        } else {
            Color.TRANSPARENT
        }
    }

    fun accentColor(activity: Activity): Int = color(activity, androidx.appcompat.R.attr.colorPrimary)
}
