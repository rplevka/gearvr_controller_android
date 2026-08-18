package com.gearvrctl.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path

/**
 * Drives a live, continued touch-drag gesture so real content scrolls under the cursor, instead
 * of just moving the (invisible) overlay cursor. Built on `StrokeDescription.continueStroke`
 * (API 26+) — each call extends the same in-progress touch pointer rather than starting a new one.
 *
 * Touchpad samples arrive at ~65Hz; dispatching a gesture continuation on every single one is
 * likely too chatty, so deltas are batched and flushed every [FLUSH_EVERY_N_SAMPLES] samples.
 * Both that and [SEGMENT_DURATION_MS] are first-guess constants — expect on-device tuning.
 */
class ScrollGestureController(private val service: AccessibilityService) {

    private var active = false
    private var currentStroke: GestureDescription.StrokeDescription? = null
    private var lastX = 0f
    private var lastY = 0f
    private var pendingDx = 0f
    private var pendingDy = 0f
    private var sampleCounter = 0

    fun start(x: Float, y: Float) {
        active = true
        lastX = x
        lastY = y
        pendingDx = 0f
        pendingDy = 0f
        sampleCounter = 0

        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, SEGMENT_DURATION_MS, true)
        currentStroke = stroke
        dispatch(stroke)
    }

    fun extend(dx: Float, dy: Float) {
        if (!active) return
        pendingDx += dx
        pendingDy += dy
        sampleCounter++
        if (sampleCounter < FLUSH_EVERY_N_SAMPLES) return
        sampleCounter = 0

        val stroke = currentStroke ?: return
        val newX = lastX + pendingDx
        val newY = lastY + pendingDy
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

    private fun dispatch(stroke: GestureDescription.StrokeDescription) {
        service.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    companion object {
        private const val SEGMENT_DURATION_MS = 16L
        private const val FLUSH_EVERY_N_SAMPLES = 1
    }
}
