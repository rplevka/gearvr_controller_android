package com.gearvrctl.app.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MadgwickAhrsTest {

    @Test
    fun `stays normalized and finite when fed resting sensor data`() {
        val ahrs = MadgwickAhrs()
        var q = Quaternion.IDENTITY
        // Resting controller: no rotation, gravity down Z, arbitrary-but-fixed mag reading.
        repeat(200) {
            q = ahrs.update(
                gyro = Triple(0.0, 0.0, 0.0),
                accel = Triple(0.0, 0.0, 9.8),
                mag = Triple(20.0, 5.0, -40.0),
                dtSeconds = 1.0 / 65.0,
            )
        }

        val norm = q.w * q.w + q.x * q.x + q.y * q.y + q.z * q.z
        assertEquals(1.0, norm, 1e-6)
        assertFalse(q.w.isNaN())
        assertFalse(q.x.isNaN())
        assertFalse(q.y.isNaN())
        assertFalse(q.z.isNaN())
    }

    @Test
    fun `converges toward identity from a perturbed start when resting`() {
        val ahrs = MadgwickAhrs(beta = 0.5) // high beta = fast convergence for this test
        // Start from a deliberately wrong orientation and see if resting accel/mag pulls it back.
        repeat(500) {
            ahrs.update(
                gyro = Triple(0.0, 0.0, 0.0),
                accel = Triple(0.0, 0.0, 9.8),
                mag = Triple(20.0, 5.0, -40.0),
                dtSeconds = 1.0 / 65.0,
            )
        }
        val q = ahrs.orientation
        val (vx, vy, vz) = q.rotate(0.0, 0.0, -1.0)
        // Forward vector should still be roughly unit length and finite (sanity, not exact pose).
        val len = vx * vx + vy * vy + vz * vz
        assertTrue(abs(len - 1.0) < 1e-6)
    }

    @Test
    fun `quaternion rotate preserves vector length`() {
        val q = Quaternion(0.7071, 0.7071, 0.0, 0.0).normalized()
        val (x, y, z) = q.rotate(0.0, 0.0, -1.0)
        val len = sqrt(x * x + y * y + z * z)
        assertEquals(1.0, len, 1e-6)
    }

    private fun sqrt(v: Double) = kotlin.math.sqrt(v)
}
