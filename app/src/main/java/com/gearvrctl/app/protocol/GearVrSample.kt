package com.gearvrctl.app.protocol

data class Vector3(val x: Double, val y: Double, val z: Double)

data class ImuSample(val timestampUs: Long, val accelMps2: Vector3, val gyroDegPerSec: Vector3)

data class ButtonState(
    val trigger: Boolean,
    val home: Boolean,
    val back: Boolean,
    val touchpadClick: Boolean,
    val volumeUp: Boolean,
    val volumeDown: Boolean,
)

data class GearVrSample(
    /** 3 IMU sub-samples packed into every notification (higher IMU rate than notify rate). */
    val imuSamples: List<ImuSample>,
    val magnetometerUt: Vector3,
    val touching: Boolean,
    /** 0-315, only meaningful while [touching]. */
    val touchpadX: Int,
    val touchpadY: Int,
    val buttons: ButtonState,
    val temperatureRaw: Int,
)
