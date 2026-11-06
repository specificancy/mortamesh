package com.mortalive.mortamesh

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** Shared visual tokens for the native Morta Mesh screens. */
internal object UiKit {
    val blue = 0xFF1877F2.toInt()
    val blueLight = 0xFFEAF2FF.toInt()
    val ink = 0xFF172033.toInt()
    val muted = 0xFF667085.toInt()
    val background = 0xFFF6F8FC.toInt()
    val border = 0xFFE6EAF1.toInt()
    val white = 0xFFFFFFFF.toInt()
}

/**
 * Small shared Android view kit. This deliberately contains presentation helpers only:
 * transport, storage, cryptography and screen state remain in their owning activities.
 */
abstract class UiKitActivity : Activity() {
    protected val blue = UiKit.blue
    protected val blueLight = UiKit.blueLight
    protected val ink = UiKit.ink
    protected val muted = UiKit.muted
    protected val background = UiKit.background
    protected val border = UiKit.border
    protected val white = UiKit.white

    protected open fun areUiActionsEnabled(): Boolean = true

    protected fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    protected fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }

    /** A shared 11sp minimum keeps small labels readable while respecting user font scaling. */
    protected fun label(
        text: String,
        size: Float,
        color: Int = ink,
        bold: Boolean = false
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = size.coerceAtLeast(11f)
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER_VERTICAL
        includeFontPadding = false
    }

    protected fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(white, 20, border)
        elevation = dp(1).toFloat()
    }

    /** All shared buttons provide at least a 48dp touch target. */
    protected fun primaryButton(text: String, onClick: () -> Unit): TextView =
        label(text, 14f, white, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            minimumHeight = dp(48)
            minWidth = dp(48)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            background = rounded(blue, 11)
            isClickable = true
            isFocusable = true
            setOnClickListener { if (areUiActionsEnabled()) onClick() }
        }

    protected fun secondaryButton(text: String, onClick: () -> Unit): TextView =
        label(text, 13f, blue, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            minimumHeight = dp(48)
            minWidth = dp(48)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            background = rounded(blueLight, 11)
            isClickable = true
            isFocusable = true
            setOnClickListener { if (areUiActionsEnabled()) onClick() }
        }
}
