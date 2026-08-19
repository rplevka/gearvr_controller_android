package com.gearvrctl.app.input

import com.gearvrctl.app.protocol.Vector3

/**
 * Records raw magnetometer samples while the user rotates the controller through all
 * orientations, then derives a hard-iron bias as the per-axis midpoint of the observed range —
 * the standard simple hard-iron calibration technique (no soft-iron/ellipsoid correction, same
 * limitation the reference implementation this was ported from admits to).
 */
class MagnetometerCalibrator {

    private var minX = Double.MAX_VALUE
    private var minY = Double.MAX_VALUE
    private var minZ = Double.MAX_VALUE
    private var maxX = -Double.MAX_VALUE
    private var maxY = -Double.MAX_VALUE
    private var maxZ = -Double.MAX_VALUE

    fun start() {
        minX = Double.MAX_VALUE; minY = Double.MAX_VALUE; minZ = Double.MAX_VALUE
        maxX = -Double.MAX_VALUE; maxY = -Double.MAX_VALUE; maxZ = -Double.MAX_VALUE
    }

    fun sample(mag: Vector3) {
        if (mag.x < minX) minX = mag.x
        if (mag.x > maxX) maxX = mag.x
        if (mag.y < minY) minY = mag.y
        if (mag.y > maxY) maxY = mag.y
        if (mag.z < minZ) minZ = mag.z
        if (mag.z > maxZ) maxZ = mag.z
    }

    fun finish(): Vector3 = Vector3((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2)
}
