package com.gearvrctl.app.input

import android.util.Log
import com.gearvrctl.app.protocol.GearVrSample
import com.gearvrctl.app.protocol.Vector3
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2

/**
 * "Laser pointer" absolute-aim source: fuses gyro+accel+mag via [MadgwickAhrs] into an
 * orientation quaternion, then maps that orientation straight to an absolute screen position
 * (yaw/pitch through a configurable field-of-view), rather than integrating relative deltas like
 * [GyroPointerMotionSource]. Ported from a working reference implementation of the same
 * controller (mijuu/GearVR-Controller-Bridge).
 *
 * The controller-frame → screen-frame axis mapping (which axis is yaw vs pitch, and sign) below
 * is a first guess — genuinely unknown until tested against the real controller in hand, same as
 * every other axis-mapping decision in this project.
 */
class AbsoluteOrientationSource(private val ahrs: MadgwickAhrs = MadgwickAhrs()) {

    var magHardIronBias: Vector3 = Vector3(0.0, 0.0, 0.0)
    var fovDegrees: Float = 90f

    private var lastTimestampUs: Long? = null
    private var calibrationSampleCount = 0
    private var gyroBiasX = 0.0
    private var gyroBiasY = 0.0
    private var gyroBiasZ = 0.0
    private var gyroCalibrated = false

    fun onSample(sample: GearVrSample) {
        val first = sample.imuSamples.firstOrNull() ?: return
        val avgGx = sample.imuSamples.map { it.gyroDegPerSec.x }.average()
        val avgGy = sample.imuSamples.map { it.gyroDegPerSec.y }.average()
        val avgGz = sample.imuSamples.map { it.gyroDegPerSec.z }.average()
        val avgAx = sample.imuSamples.map { it.accelMps2.x }.average()
        val avgAy = sample.imuSamples.map { it.accelMps2.y }.average()
        val avgAz = sample.imuSamples.map { it.accelMps2.z }.average()

        val lastTs = lastTimestampUs
        lastTimestampUs = first.timestampUs

        if (!gyroCalibrated) {
            gyroBiasX += avgGx
            gyroBiasY += avgGy
            gyroBiasZ += avgGz
            calibrationSampleCount++
            if (calibrationSampleCount >= CALIBRATION_SAMPLES) {
                gyroBiasX /= calibrationSampleCount
                gyroBiasY /= calibrationSampleCount
                gyroBiasZ /= calibrationSampleCount
                gyroCalibrated = true
            }
            return
        }

        if (lastTs == null) return // first sample after calibration, no dt yet
        val dtSeconds = (first.timestampUs - lastTs) / 1_000_000.0
        if (dtSeconds <= 0 || dtSeconds > MAX_PLAUSIBLE_DT_SECONDS) return // clock wrap/gap

        val gyroRad = Triple(
            Math.toRadians(avgGx - gyroBiasX),
            Math.toRadians(avgGy - gyroBiasY),
            Math.toRadians(avgGz - gyroBiasZ),
        )
        val accel = Triple(avgAx, avgAy, avgAz)
        val mag = sample.magnetometerUt
        val magBiased = Triple(
            mag.x - magHardIronBias.x,
            mag.y - magHardIronBias.y,
            mag.z - magHardIronBias.z,
        )

        ahrs.update(gyroRad, accel, magBiased, dtSeconds)
    }

    /** Absolute screen-space target, or null until gyro bias calibration has completed. */
    fun currentTarget(screenWidth: Int, screenHeight: Int): Pair<Float, Float>? {
        if (!gyroCalibrated) return null

        // Confirmed on real hardware (isolated roll/pitch/yaw test): the controller's true
        // forward/pointing axis is body Y, not Z — rolling (rotation about the true forward axis)
        // was leaking into the old (0,0,-1)-referenced yaw/pitch because that reference vector
        // wasn't aligned with the axis roll actually rotates around. Body Y matches the already-
        // confirmed relative-mode convention (gyro Y drives roll, gyro X drives pitch).
        val (vx, vy, vz) = ahrs.orientation.rotate(0.0, -1.0, 0.0)
        val yawDeg = Math.toDegrees(atan2(vx, -vy))
        val pitchDeg = Math.toDegrees(asin(vz.coerceIn(-1.0, 1.0)))

        debugLog(vx, vy, vz, yawDeg, pitchDeg)

        val fov = fovDegrees.toDouble()
        val xRatio = yawDeg / fov + 0.5
        val yRatio = -pitchDeg / fov + 0.5
        return (xRatio * screenWidth).toFloat() to (yRatio * screenHeight).toFloat()
    }

    // Temporary diagnostic to nail down the real controller-frame → yaw/pitch mapping empirically
    // instead of guessing again — batched (not per-sample) to avoid the Log.d overhead pitfall
    // already hit once with the BLE timing probe.
    private val debugLines = StringBuilder()
    private var debugCount = 0

    private fun debugLog(vx: Double, vy: Double, vz: Double, yawDeg: Double, pitchDeg: Double) {
        debugCount++
        debugLines.append(
            String.format(
                Locale.US,
                "%.2f,%.2f,%.2f,yaw=%.1f,pitch=%.1f\n",
                vx, vy, vz, yawDeg, pitchDeg,
            )
        )
        if (debugCount >= 20) {
            Log.d("GearVrOrientation", debugLines.toString())
            debugLines.clear()
            debugCount = 0
        }
    }

    companion object {
        private const val CALIBRATION_SAMPLES = 30 // ~0.5s at the ~65Hz notify rate
        private const val MAX_PLAUSIBLE_DT_SECONDS = 0.5
    }
}
