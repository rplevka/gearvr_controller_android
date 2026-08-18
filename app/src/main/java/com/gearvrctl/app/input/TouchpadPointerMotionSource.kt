package com.gearvrctl.app.input

import com.gearvrctl.app.protocol.GearVrSample
import kotlin.math.sqrt

data class PointerDelta(val dx: Float, val dy: Float)

/**
 * Turns touchpad samples into relative pointer motion, like a laptop trackpad — the touchpad's
 * own X/Y range (0-315) isn't screen space, so only the delta between consecutive touches matters.
 *
 * [sensitivity] is a flat multiplier; [acceleration] adds an extra multiplier that scales with
 * per-sample swipe speed, so a fast flick covers more screen than a slow drag at the same base
 * sensitivity — the same idea as PC mouse pointer acceleration curves. Both are `var`s (not
 * constructor defaults) so Settings changes apply immediately without recreating this instance.
 */
class TouchpadPointerMotionSource(
    var sensitivity: Float = 6.0f,
    var acceleration: Float = 0.0f,
) : PointerMotionSource {

    private var lastX: Int? = null
    private var lastY: Int? = null

    override fun onSample(sample: GearVrSample): PointerDelta? {
        if (!sample.touching) {
            lastX = null
            lastY = null
            return null
        }

        val prevX = lastX
        val prevY = lastY
        lastX = sample.touchpadX
        lastY = sample.touchpadY

        if (prevX == null || prevY == null) return null // just started touching, no delta yet

        val rawDx = (sample.touchpadX - prevX).toFloat()
        val rawDy = (sample.touchpadY - prevY).toFloat()
        val magnitude = sqrt(rawDx * rawDx + rawDy * rawDy)
        val multiplier = sensitivity * (1f + acceleration * magnitude)
        return PointerDelta(rawDx * multiplier, rawDy * multiplier)
    }
}
