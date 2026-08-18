package com.gearvrctl.app.input

import com.gearvrctl.app.protocol.GearVrSample
import kotlin.math.abs

/**
 * Aims the cursor via dead-reckoning integration of gyro rate — no full sensor fusion (accel/mag
 * aren't used for drift correction), so it will drift slowly over time. Good enough for a rough
 * "point the controller" pointer, not a stable VR-grade orientation tracker.
 *
 * Axis mapping: roll (gyro Y) drives horizontal cursor movement, pitch (gyro X) drives vertical —
 * confirmed on real hardware (rolling left/right moved the cursor vertically and tilting
 * front/back moved it horizontally with the original X→dx/Y→dy mapping, i.e. swapped from this).
 * Sign is still a first guess.
 *
 * Runs a one-time bias calibration over the first [CALIBRATION_SAMPLES] packets after
 * construction (averages the resting gyro noise/offset and subtracts it from then on), and
 * ignores anything under [deadzoneDegPerSec] afterward so a stationary controller doesn't drift
 * the cursor. Calibration runs continuously in the background regardless of whether gyro output
 * is actually being used for the cursor at any given moment (see PointerModeManager), so it's
 * already warmed up by the time FALLBACK mode needs it.
 */
class GyroPointerMotionSource(
    var sensitivity: Float = 15.0f,
    var deadzoneDegPerSec: Float = 1.5f,
) : PointerMotionSource {

    private var lastTimestampUs: Long? = null
    private var calibrationSampleCount = 0
    private var biasX = 0f
    private var biasY = 0f
    private var calibrated = false

    override fun onSample(sample: GearVrSample): PointerDelta? {
        val first = sample.imuSamples.firstOrNull() ?: return null
        val avgX = sample.imuSamples.map { it.gyroDegPerSec.x }.average().toFloat()
        val avgY = sample.imuSamples.map { it.gyroDegPerSec.y }.average().toFloat()

        val lastTs = lastTimestampUs
        lastTimestampUs = first.timestampUs

        if (!calibrated) {
            biasX += avgX
            biasY += avgY
            calibrationSampleCount++
            if (calibrationSampleCount >= CALIBRATION_SAMPLES) {
                biasX /= calibrationSampleCount
                biasY /= calibrationSampleCount
                calibrated = true
            }
            return null
        }

        if (lastTs == null) return null // first sample after calibration, no dt yet
        val dtSeconds = (first.timestampUs - lastTs) / 1_000_000.0
        if (dtSeconds <= 0 || dtSeconds > MAX_PLAUSIBLE_DT_SECONDS) return null // clock wrap/gap

        val rateX = applyDeadzone(avgX - biasX)
        val rateY = applyDeadzone(avgY - biasY)
        if (rateX == 0f && rateY == 0f) return null

        return PointerDelta(
            dx = (rateY * dtSeconds * sensitivity).toFloat(),
            dy = (rateX * dtSeconds * sensitivity).toFloat(),
        )
    }

    private fun applyDeadzone(rate: Float): Float = if (abs(rate) < deadzoneDegPerSec) 0f else rate

    companion object {
        private const val CALIBRATION_SAMPLES = 30 // ~0.5s at the ~65Hz notify rate
        private const val MAX_PLAUSIBLE_DT_SECONDS = 0.5
    }
}
