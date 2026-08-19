package com.gearvrctl.app.input

import kotlin.math.abs

/**
 * Spreads bursty input deltas across a fixed-rate render loop instead of applying them the
 * instant they arrive.
 *
 * Confirmed on real hardware: the controller delivers touchpad/gyro data in bursts of ~7-8
 * packets in a few ms, then goes quiet for ~75-85ms before the next burst — an effective ~12Hz
 * real update rate, not the ~65Hz notify rate assumed earlier. That's a firmware/BLE-scheduling
 * limit on the controller itself, not fixable in software. What IS fixable: instead of snapping
 * the cursor/scroll position the instant a burst's full delta arrives (which looks like a jump
 * followed by a freeze), accumulate incoming deltas and drain them gradually via [tick] called
 * from a steady ~60fps loop — converting each burst-jump into a smooth glide.
 */
class DeltaSmoother(private val smoothingFactor: Float = 0.35f) {

    private var pendingDx = 0f
    private var pendingDy = 0f

    fun addDelta(dx: Float, dy: Float) {
        pendingDx += dx
        pendingDy += dy
    }

    /** Call at a fixed tick rate. Returns the portion of the pending delta to apply this tick. */
    fun tick(): PointerDelta? {
        if (pendingDx == 0f && pendingDy == 0f) return null

        val outDx = pendingDx * smoothingFactor
        val outDy = pendingDy * smoothingFactor
        pendingDx -= outDx
        pendingDy -= outDy
        if (abs(pendingDx) < EPSILON) pendingDx = 0f
        if (abs(pendingDy) < EPSILON) pendingDy = 0f

        return PointerDelta(outDx, outDy)
    }

    fun reset() {
        pendingDx = 0f
        pendingDy = 0f
    }

    companion object {
        private const val EPSILON = 0.05f
    }
}
