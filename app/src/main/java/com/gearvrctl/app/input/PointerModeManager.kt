package com.gearvrctl.app.input

import com.gearvrctl.app.config.ActivePointerSource
import com.gearvrctl.app.config.GyroMode

/**
 * Picks which already-computed [PointerDelta] (touchpad or gyro) actually drives the cursor this
 * sample. Both sources are fed every sample regardless of which is "active" (see
 * GearVrAccessibilityService) so their internal state (touchpad's last-touch position, gyro's
 * calibration) stays warm for an instant switch.
 */
object PointerModeManager {
    fun select(
        touching: Boolean,
        touchpadDelta: PointerDelta?,
        gyroDelta: PointerDelta?,
        gyroMode: GyroMode,
        activeSource: ActivePointerSource,
    ): PointerDelta? = when (gyroMode) {
        GyroMode.FALLBACK -> if (touching) touchpadDelta else gyroDelta
        GyroMode.EXPLICIT -> if (activeSource == ActivePointerSource.TOUCHPAD) touchpadDelta else gyroDelta
    }
}
