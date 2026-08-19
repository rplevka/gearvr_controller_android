package com.gearvrctl.app.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * A small visual-only crosshair drawn via a TYPE_ACCESSIBILITY_OVERLAY window — doesn't require
 * the SYSTEM_ALERT_WINDOW permission since it's created from within an AccessibilityService.
 * The overlay is purely cosmetic; it does not receive touches (FLAG_NOT_TOUCHABLE).
 *
 * The overlay window is a single fixed full-screen container, added once via
 * `WindowManager.addView` and never moved — movement is done by setting the small cursor child
 * view's `x`/`y` properties instead. Confirmed via real device logcat
 * (`WindowManager: Relayout Window{...} req=48x48` firing on every single sample, ~80-100ms
 * apart) that moving the window itself via `updateViewLayout` on every BLE sample (~65Hz) was
 * the actual cause of the cursor feeling low-FPS/laggy — each call is a full cross-process
 * layout pass. `View.x`/`y` is a cheap client-side render property with no such IPC.
 */
class CursorOverlayController(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var container: FrameLayout? = null
    private var cursorView: View? = null

    private val layoutParams = WindowManager.LayoutParams().apply {
        width = WindowManager.LayoutParams.MATCH_PARENT
        height = WindowManager.LayoutParams.MATCH_PARENT
        type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        format = PixelFormat.TRANSLUCENT
        gravity = Gravity.TOP or Gravity.START
    }

    fun show() {
        if (container != null) return
        val cursor = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(CURSOR_SIZE_PX, CURSOR_SIZE_PX)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(140, 255, 0, 0))
                setStroke(4, Color.WHITE)
            }
        }
        val frame = FrameLayout(context).apply { addView(cursor) }
        cursorView = cursor
        container = frame
        windowManager.addView(frame, layoutParams)
    }

    fun hide() {
        container?.let { windowManager.removeView(it) }
        container = null
        cursorView = null
    }

    fun moveTo(x: Float, y: Float) {
        val view = cursorView ?: return
        view.x = x - CURSOR_SIZE_PX / 2f
        view.y = y - CURSOR_SIZE_PX / 2f
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
