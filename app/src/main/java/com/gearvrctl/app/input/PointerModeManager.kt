package com.gearvrctl.app.input

import com.gearvrctl.app.config.ActivePointerSource
import com.gearvrctl.app.config.GyroMode

/**
 * Picks which already-computed [PointerDelta] (touchpad, relative gyro, or absolute-orientation)
 * actually drives the cursor this sample. All sources are fed every sample regardless of which
 * is "active" (see GearVrAccessibilityService) so their internal state (touchpad's last-touch
 * position, gyro/AHRS calibration) stays warm for an instant switch.
 *
 * The absolute-orientation source reports an absolute screen target, not a delta — it's turned
 * into `target - cursor` here so it flows through the same delta-based pipeline (selection,
 * smoothing, clamping) as everything else, and "smoothing" naturally becomes a damped-aim ease
 * toward the target instead of an instant snap.
 */
object PointerModeManager {
    fun select(
        touching: Boolean,
        touchpadDelta: PointerDelta?,
        gyroDelta: PointerDelta?,
        absoluteTarget: Pair<Float, Float>?,
        cursorX: Float,
        cursorY: Float,
        gyroMode: GyroMode,
        activeSource: ActivePointerSource,
    ): PointerDelta? = when (gyroMode) {
        GyroMode.FALLBACK -> if (touching) touchpadDelta else gyroDelta
        GyroMode.EXPLICIT -> when (activeSource) {
            ActivePointerSource.TOUCHPAD -> touchpadDelta
            ActivePointerSource.GYRO -> gyroDelta
            ActivePointerSource.ABSOLUTE_ORIENTATION -> absoluteTarget?.let { (targetX, targetY) ->
                PointerDelta(targetX - cursorX, targetY - cursorY)
            }
        }
    }
}
