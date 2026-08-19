package com.gearvrctl.app.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.roundToInt

/**
 * A small visual-only crosshair drawn via a TYPE_ACCESSIBILITY_OVERLAY window — doesn't require
 * the SYSTEM_ALERT_WINDOW permission since it's created from within an AccessibilityService.
 * The overlay is purely cosmetic; it does not receive touches (FLAG_NOT_TOUCHABLE).
 */
class CursorOverlayController(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var cursorView: View? = null
    private val layoutParams = WindowManager.LayoutParams().apply {
        width = CURSOR_SIZE_PX
        height = CURSOR_SIZE_PX
        type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        format = PixelFormat.TRANSLUCENT
        gravity = Gravity.TOP or Gravity.START
    }

    fun show() {
        if (cursorView != null) return
        val view = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(140, 255, 0, 0))
                setStroke(4, Color.WHITE)
            }
        }
        cursorView = view
        windowManager.addView(view, layoutParams)
    }

    fun hide() {
        cursorView?.let { windowManager.removeView(it) }
        cursorView = null
    }

    fun moveTo(x: Float, y: Float) {
        val view = cursorView ?: return
        layoutParams.x = (x - CURSOR_SIZE_PX / 2f).roundToInt()
        layoutParams.y = (y - CURSOR_SIZE_PX / 2f).roundToInt()
        windowManager.updateViewLayout(view, layoutParams)
    }

    fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            @Suppress("DEPRECATION")
            val point = android.graphics.Point().also { display.getRealSize(it) }
            point.x to point.y
        }
    }

    companion object {
        private const val CURSOR_SIZE_PX = 48
    }
}
