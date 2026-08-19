package com.gearvrctl.app.input

import kotlin.math.sqrt

/**
 * Madgwick's gradient-descent AHRS filter — the standard, widely-published algorithm (Sebastian
 * Madgwick / x-io Technologies), ported here rather than pulled in as a dependency since it's
 * ~100 lines and has no Android-specific needs. Fuses gyro (drift-prone but responsive) with
 * accel+mag (noisy but drift-free) into a single orientation quaternion.
 *
 * [beta] trades responsiveness for noise: higher corrects toward accel/mag faster (less gyro
 * drift, more jitter from accel/mag noise), lower trusts the gyro more (smoother, drifts more).
 * A magnetometer reading of exactly (0,0,0) (not yet calibrated, or hardware unavailable) falls
 * back to gyro+accel only (yaw will still drift in that case — mag is what corrects yaw).
 */
class MadgwickAhrs(var beta: Double = 0.1) {

    private var q0 = 1.0
    private var q1 = 0.0
    private var q2 = 0.0
    private var q3 = 0.0

    val orientation: Quaternion get() = Quaternion(q0, q1, q2, q3)

    fun reset() {
        q0 = 1.0; q1 = 0.0; q2 = 0.0; q3 = 0.0
    }

    /** [gyro] in rad/s; [accel] and [mag] in any consistent units (normalized internally). */
    fun update(gyro: Triple<Double, Double, Double>, accel: Triple<Double, Double, Double>, mag: Triple<Double, Double, Double>, dtSeconds: Double): Quaternion {
        val (gx, gy, gz) = gyro
        var (ax, ay, az) = accel
        var (mx, my, mz) = mag

        if (mx == 0.0 && my == 0.0 && mz == 0.0) {
            return updateImu(gx, gy, gz, ax, ay, az, dtSeconds)
        }

        var qDot1 = 0.5 * (-q1 * gx - q2 * gy - q3 * gz)
        var qDot2 = 0.5 * (q0 * gx + q2 * gz - q3 * gy)
        var qDot3 = 0.5 * (q0 * gy - q1 * gz + q3 * gx)
        var qDot4 = 0.5 * (q0 * gz + q1 * gy - q2 * gx)

        if (!(ax == 0.0 && ay == 0.0 && az == 0.0)) {
            var norm = invNorm(ax, ay, az)
            ax *= norm; ay *= norm; az *= norm
            norm = invNorm(mx, my, mz)
            mx *= norm; my *= norm; mz *= norm

            val _2q0mx = 2.0 * q0 * mx
            val _2q0my = 2.0 * q0 * my
            val _2q0mz = 2.0 * q0 * mz
            val _2q1mx = 2.0 * q1 * mx
            val _2q0 = 2.0 * q0
            val _2q1 = 2.0 * q1
            val _2q2 = 2.0 * q2
            val _2q3 = 2.0 * q3
            val _2q0q2 = 2.0 * q0 * q2
            val _2q2q3 = 2.0 * q2 * q3
            val q0q0 = q0 * q0
            val q0q1 = q0 * q1
            val q0q2 = q0 * q2
            val q0q3 = q0 * q3
            val q1q1 = q1 * q1
            val q1q2 = q1 * q2
            val q1q3 = q1 * q3
            val q2q2 = q2 * q2
            val q2q3 = q2 * q3
            val q3q3 = q3 * q3

            val hx = mx * q0q0 - _2q0my * q3 + _2q0mz * q2 + mx * q1q1 + _2q1 * my * q2 + _2q1 * mz * q3 - mx * q2q2 - mx * q3q3
            val hy = _2q0mx * q3 + my * q0q0 - _2q0mz * q1 + _2q1mx * q2 - my * q1q1 + my * q2q2 + _2q2 * mz * q3 - my * q3q3
            val _2bx = sqrt(hx * hx + hy * hy)
            val _2bz = -_2q0mx * q2 + _2q0my * q1 + mz * q0q0 + _2q1mx * q3 - mz * q1q1 + _2q2 * my * q3 - mz * q2q2 + mz * q3q3
            val _4bx = 2.0 * _2bx
            val _4bz = 2.0 * _2bz

            var s0 = -_2q2 * (2.0 * q1q3 - _2q0q2 - ax) + _2q1 * (2.0 * q0q1 + _2q2q3 - ay) -
                _2bz * q2 * (_2bx * (0.5 - q2q2 - q3q3) + _2bz * (q1q3 - q0q2) - mx) +
                (-_2bx * q3 + _2bz * q1) * (_2bx * (q1q2 - q0q3) + _2bz * (q0q1 + q2q3) - my) +
                _2bx * q2 * (_2bx * (q0q2 + q1q3) + _2bz * (0.5 - q1q1 - q2q2) - mz)
            var s1 = _2q3 * (2.0 * q1q3 - _2q0q2 - ax) + _2q0 * (2.0 * q0q1 + _2q2q3 - ay) -
                4.0 * q1 * (1 - 2.0 * q1q1 - 2.0 * q2q2 - az) +
                _2bz * q3 * (_2bx * (0.5 - q2q2 - q3q3) + _2bz * (q1q3 - q0q2) - mx) +
                (_2bx * q2 + _2bz * q0) * (_2bx * (q1q2 - q0q3) + _2bz * (q0q1 + q2q3) - my) +
                (_2bx * q3 - _4bz * q1) * (_2bx * (q0q2 + q1q3) + _2bz * (0.5 - q1q1 - q2q2) - mz)
            var s2 = -_2q0 * (2.0 * q1q3 - _2q0q2 - ax) + _2q3 * (2.0 * q0q1 + _2q2q3 - ay) -
                4.0 * q2 * (1 - 2.0 * q1q1 - 2.0 * q2q2 - az) +
                (-_4bx * q2 - _2bz * q0) * (_2bx * (0.5 - q2q2 - q3q3) + _2bz * (q1q3 - q0q2) - mx) +
                (_2bx * q1 + _2bz * q3) * (_2bx * (q1q2 - q0q3) + _2bz * (q0q1 + q2q3) - my) +
                (_2bx * q0 - _4bz * q2) * (_2bx * (q0q2 + q1q3) + _2bz * (0.5 - q1q1 - q2q2) - mz)
            var s3 = _2q1 * (2.0 * q1q3 - _2q0q2 - ax) + _2q2 * (2.0 * q0q1 + _2q2q3 - ay) +
                (-_4bx * q3 + _2bz * q1) * (_2bx * (0.5 - q2q2 - q3q3) + _2bz * (q1q3 - q0q2) - mx) +
                (-_2bx * q0 + _2bz * q2) * (_2bx * (q1q2 - q0q3) + _2bz * (q0q1 + q2q3) - my) +
                _2bx * q1 * (_2bx * (q0q2 + q1q3) + _2bz * (0.5 - q1q1 - q2q2) - mz)

            val sNorm = invNorm4(s0, s1, s2, s3)
            s0 *= sNorm; s1 *= sNorm; s2 *= sNorm; s3 *= sNorm

            qDot1 -= beta * s0
            qDot2 -= beta * s1
            qDot3 -= beta * s2
            qDot4 -= beta * s3
        }

        q0 += qDot1 * dtSeconds
        q1 += qDot2 * dtSeconds
        q2 += qDot3 * dtSeconds
        q3 += qDot4 * dtSeconds
        normalizeState()
        return orientation
    }

    private fun updateImu(gx: Double, gy: Double, gz: Double, ax0: Double, ay0: Double, az0: Double, dtSeconds: Double): Quaternion {
        var ax = ax0; var ay = ay0; var az = az0
        var qDot1 = 0.5 * (-q1 * gx - q2 * gy - q3 * gz)
        var qDot2 = 0.5 * (q0 * gx + q2 * gz - q3 * gy)
        var qDot3 = 0.5 * (q0 * gy - q1 * gz + q3 * gx)
        var qDot4 = 0.5 * (q0 * gz + q1 * gy - q2 * gx)

        if (!(ax == 0.0 && ay == 0.0 && az == 0.0)) {
            val norm = invNorm(ax, ay, az)
            ax *= norm; ay *= norm; az *= norm

            val _2q0 = 2.0 * q0
            val _2q1 = 2.0 * q1
            val _2q2 = 2.0 * q2
            val _2q3 = 2.0 * q3
            val _4q0 = 4.0 * q0
            val _4q1 = 4.0 * q1
            val _4q2 = 4.0 * q2
            val _8q1 = 8.0 * q1
            val _8q2 = 8.0 * q2
            val q0q0 = q0 * q0
            val q1q1 = q1 * q1
            val q2q2 = q2 * q2
            val q3q3 = q3 * q3

            var s0 = _4q0 * q2q2 + _2q2 * ax + _4q0 * q1q1 - _2q1 * ay
            var s1 = _4q1 * q3q3 - _2q3 * ax + 4.0 * q0q0 * q1 - _2q0 * ay - _4q1 + _8q1 * q1q1 + _8q1 * q2q2 + _4q1 * az
            var s2 = 4.0 * q0q0 * q2 + _2q0 * ax + _4q2 * q3q3 - _2q3 * ay - _4q2 + _8q2 * q1q1 + _8q2 * q2q2 + _4q2 * az
            var s3 = 4.0 * q1q1 * q3 - _2q1 * ax + 4.0 * q2q2 * q3 - _2q2 * ay

            val sNorm = invNorm4(s0, s1, s2, s3)
            s0 *= sNorm; s1 *= sNorm; s2 *= sNorm; s3 *= sNorm

            qDot1 -= beta * s0
            qDot2 -= beta * s1
            qDot3 -= beta * s2
            qDot4 -= beta * s3
        }

        q0 += qDot1 * dtSeconds
        q1 += qDot2 * dtSeconds
        q2 += qDot3 * dtSeconds
        q3 += qDot4 * dtSeconds
        normalizeState()
        return orientation
    }

    private fun normalizeState() {
        val norm = invNorm4(q0, q1, q2, q3)
        q0 *= norm; q1 *= norm; q2 *= norm; q3 *= norm
    }

    private fun invNorm(x: Double, y: Double, z: Double): Double {
        val n = sqrt(x * x + y * y + z * z)
        return if (n == 0.0) 0.0 else 1.0 / n
    }

    private fun invNorm4(w: Double, x: Double, y: Double, z: Double): Double {
        val n = sqrt(w * w + x * x + y * y + z * z)
        return if (n == 0.0) 0.0 else 1.0 / n
    }
}
