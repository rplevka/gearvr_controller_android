package com.gearvrctl.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path

/**
 * Drives a live, continued touch-drag gesture so real content scrolls under the cursor, instead
 * of just moving the (invisible) overlay cursor. Built on `StrokeDescription.continueStroke`
 * (API 26+) — each call extends the same in-progress touch pointer rather than starting a new one.
 *
 * Touchpad samples arrive at ~65Hz. `dispatchGesture` is async and rejects a new call while one
 * is still in flight — firing on a fixed sample-count schedule regardless of completion means
 * most continuations get silently dropped. Flushing is driven by the dispatch's own completion
 * callback instead: accumulate deltas while a dispatch is in flight, and send the next one the
 * instant the previous completes, so it tracks as fast as the system can actually keep up.
 *
 * [lastX]/[lastY] must stay within [0, screenWidth/Height] — `Path`/`StrokeDescription` throw
 * `IllegalArgumentException: Path bounds must not be negative` if a coordinate goes negative
 * (confirmed via a real device crash log), which a long enough scroll drag past the screen edge
 * will do if left unclamped, unlike the overlay cursor position which already clamps.
 */
class ScrollGestureController(
    private val service: AccessibilityService,
    private val screenWidth: Int,
    private val screenHeight: Int,
) {

    private var active = false
    private var currentStroke: GestureDescription.StrokeDescription? = null
    private var lastX = 0f
    private var lastY = 0f
    private var pendingDx = 0f
    private var pendingDy = 0f
    private var dispatching = false

    private val resultCallback = object : AccessibilityService.GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            dispatching = false
            flushPending()
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            dispatching = false
            flushPending()
        }
    }

    fun start(x: Float, y: Float) {
        active = true
        lastX = clampX(x)
        lastY = clampY(y)
        pendingDx = 0f
        pendingDy = 0f
        dispatching = false

        val path = Path().apply { moveTo(lastX, lastY) }
        val stroke = GestureDescription.StrokeDescription(path, 0, SEGMENT_DURATION_MS, true)
        currentStroke = stroke
        dispatch(stroke)
    }

    fun extend(dx: Float, dy: Float) {
        if (!active) return
        pendingDx += dx
        pendingDy += dy
        flushPending()
    }

    private fun flushPending() {
        if (!active || dispatching) return
        if (pendingDx == 0f && pendingDy == 0f) return
        val stroke = currentStroke ?: return

        val newX = clampX(lastX + pendingDx)
        val newY = clampY(lastY + pendingDy)
        val path = Path().apply {
            moveTo(lastX, lastY)
            lineTo(newX, newY)
        }
        lastX = newX
        lastY = newY
        pendingDx = 0f
        pendingDy = 0f

        val next = stroke.continueStroke(path, 0, SEGMENT_DURATION_MS, true)
        currentStroke = next
        dispatch(next)
    }

    fun end() {
        if (!active) return
        active = false
        val stroke = currentStroke ?: return
        val path = Path().apply { moveTo(lastX, lastY) }
        dispatch(stroke.continueStroke(path, 0, SEGMENT_DURATION_MS, false))
        currentStroke = null
    }

    private fun clampX(x: Float) = x.coerceIn(0f, screenWidth.toFloat())
    private fun clampY(y: Float) = y.coerceIn(0f, screenHeight.toFloat())

    private fun dispatch(stroke: GestureDescription.StrokeDescription) {
        dispatching = true
        val dispatched = service.dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            resultCallback,
            null,
        )
        if (!dispatched) dispatching = false
    }

    companion object {
        private const val SEGMENT_DURATION_MS = 16L
    }
}
