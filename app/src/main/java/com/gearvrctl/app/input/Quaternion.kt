package com.gearvrctl.app.input

import kotlin.math.sqrt

/** w,x,y,z (Hamilton convention). Used to represent the controller's orientation. */
data class Quaternion(val w: Double, val x: Double, val y: Double, val z: Double) {

    fun normalized(): Quaternion {
        val norm = sqrt(w * w + x * x + y * y + z * z)
        if (norm == 0.0) return IDENTITY
        return Quaternion(w / norm, x / norm, y / norm, z / norm)
    }

    /** Rotates the vector (vx,vy,vz) by this quaternion (v' = q * v * q⁻¹, q assumed unit). */
    fun rotate(vx: Double, vy: Double, vz: Double): Triple<Double, Double, Double> {
        // Standard quaternion-vector rotation via the expanded (faster) formula.
        val uvx = 2.0 * (y * vz - z * vy)
        val uvy = 2.0 * (z * vx - x * vz)
        val uvz = 2.0 * (x * vy - y * vx)
        val uuvx = y * uvz - z * uvy
        val uuvy = z * uvx - x * uvz
        val uuvz = x * uvy - y * uvx
        return Triple(
            vx + w * uvx + uuvx,
            vy + w * uvy + uuvy,
            vz + w * uvz + uuvz,
        )
    }

    companion object {
        val IDENTITY = Quaternion(1.0, 0.0, 0.0, 0.0)
    }
}
