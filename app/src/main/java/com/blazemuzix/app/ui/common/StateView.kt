package com.blazemuzix.app.ui.common

import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.blazemuzix.app.R
import com.blazemuzix.app.utils.toErrorCopy
import com.blazemuzix.app.utils.visible
import com.google.android.material.button.MaterialButton

/** Binds the shared `layout_state.xml` so every screen has consistent Loading/Empty/Error/Offline states. */
class StateView(private val root: View) {

    private val progress: ProgressBar = root.findViewById(R.id.state_progress)
    private val iconContainer: View = root.findViewById(R.id.state_icon_container)
    private val icon: ImageView = root.findViewById(R.id.state_icon)
    private val title: TextView = root.findViewById(R.id.state_title)
    private val message: TextView = root.findViewById(R.id.state_message)
    private val action: MaterialButton = root.findViewById(R.id.state_action)
    private val secondary: MaterialButton = root.findViewById(R.id.state_secondary)

    init {
        // Follow the chosen accent instead of the static green in bg_state_icon.
        val value = android.util.TypedValue()
        val theme = root.context.theme
        if (theme.resolveAttribute(R.attr.bmAccentContainer, value, true)) {
            val color = if (value.resourceId != 0) androidx.core.content.ContextCompat.getColor(root.context, value.resourceId) else value.data
            iconContainer.background?.let { bg ->
                val wrapped = androidx.core.graphics.drawable.DrawableCompat.wrap(bg.mutate())
                androidx.core.graphics.drawable.DrawableCompat.setTint(wrapped, color)
                iconContainer.background = wrapped
            }
        }
        if (theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, value, true)) {
            val color = if (value.resourceId != 0) androidx.core.content.ContextCompat.getColor(root.context, value.resourceId) else value.data
            androidx.core.widget.ImageViewCompat.setImageTintList(icon, android.content.res.ColorStateList.valueOf(color))
        }
    }

    fun hide() {
        root.visible(false)
    }

    fun loading(text: CharSequence? = root.context.getString(R.string.state_loading)) {
        root.visible(true)
        progress.visible(true)
        iconContainer.visible(false)
        title.visible(false)
        message.visible(text != null)
        message.text = text
        action.visible(false)
        secondary.visible(false)
    }

    fun show(
        iconRes: Int,
        titleText: CharSequence,
        messageText: CharSequence?,
        actionText: CharSequence? = null,
        onAction: (() -> Unit)? = null,
        secondaryText: CharSequence? = null,
        onSecondary: (() -> Unit)? = null
    ) {
        root.visible(true)
        progress.visible(false)
        iconContainer.visible(true)
        icon.setImageResource(iconRes)
        title.visible(true)
        title.text = titleText
        message.visible(!messageText.isNullOrEmpty())
        message.text = messageText
        action.visible(actionText != null)
        action.text = actionText
        action.setOnClickListener { onAction?.invoke() }
        secondary.visible(secondaryText != null)
        secondary.text = secondaryText
        secondary.setOnClickListener { onSecondary?.invoke() }
    }

    fun empty(titleText: CharSequence, messageText: CharSequence?, iconRes: Int = R.drawable.ic_music_note, actionText: CharSequence? = null, onAction: (() -> Unit)? = null) =
        show(iconRes, titleText, messageText, actionText, onAction)

    fun error(error: Throwable, onRetry: (() -> Unit)?) {
        val copy = error.toErrorCopy(root.context)
        show(copy.iconRes, copy.title, copy.message, if (onRetry != null) root.context.getString(R.string.action_retry) else null, onRetry)
    }

    fun offline(onRetry: (() -> Unit)?, messageText: CharSequence? = root.context.getString(R.string.state_offline_message)) {
        show(
            R.drawable.ic_wifi_off,
            root.context.getString(R.string.state_offline_title),
            messageText,
            if (onRetry != null) root.context.getString(R.string.action_retry) else null,
            onRetry
        )
    }
}
