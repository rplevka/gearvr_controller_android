package com.gearvrctl.app.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses a raw 60-byte Gear VR Controller BLE notification into a [GearVrSample].
 *
 * Layout confirmed empirically against a real controller (see capture-wizard dumps), not just
 * taken on faith from prior community reverse-engineering:
 *  - 3x 16-byte IMU sub-samples at offsets 0, 16, 32 (IMU samples faster than the BLE notify rate)
 *  - magnetometer int16 x/y/z at offsets 48, 50, 52
 *  - touchpad touch-state nibble + 10-bit X at offset 54, 10-bit Y split across 55/56
 *  - temperature (raw) at offset 57
 *  - button bitmask at offset 58 (bit6 is set at rest and clears whenever any other bit is set)
 *  - offset 59 was constant (0x64) throughout capture — reserved/unknown, ignored
 */
object GearVrPacketParser {

    const val PACKET_SIZE = 60
    private const val IMU_BLOCK_SIZE = 16
    private const val IMU_BLOCK_COUNT = 3

    private const val ACCEL_SCALE = 9.80665 / 2048.0
    private const val GYRO_SCALE = 1.0 / 14.285
    private const val MAG_SCALE = 0.06

    private const val TOUCHING_NIBBLE = 0x1

    fun parse(bytes: ByteArray): GearVrSample {
        require(bytes.size == PACKET_SIZE) { "Expected $PACKET_SIZE-byte packet, got ${bytes.size}" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val imuSamples = (0 until IMU_BLOCK_COUNT).map { i ->
            val offset = i * IMU_BLOCK_SIZE
            ImuSample(
                timestampUs = buffer.getInt(offset).toLong() and 0xFFFFFFFFL,
                accelMps2 = Vector3(
                    buffer.getShort(offset + 4) * ACCEL_SCALE,
                    buffer.getShort(offset + 6) * ACCEL_SCALE,
                    buffer.getShort(offset + 8) * ACCEL_SCALE,
                ),
                gyroDegPerSec = Vector3(
                    buffer.getShort(offset + 10) * GYRO_SCALE,
                    buffer.getShort(offset + 12) * GYRO_SCALE,
                    buffer.getShort(offset + 14) * GYRO_SCALE,
                ),
            )
        }

        val magnetometer = Vector3(
            buffer.getShort(48) * MAG_SCALE,
            buffer.getShort(50) * MAG_SCALE,
            buffer.getShort(52) * MAG_SCALE,
        )

        val touchByte = bytes[54].toInt() and 0xFF
        val touching = (touchByte ushr 4) == TOUCHING_NIBBLE
        val nextByte = bytes[55].toInt() and 0xFF
        val touchpadX = (((touchByte and 0xF) shl 6) + ((nextByte and 0xFC) ushr 2)) and 0x3FF
        val touchpadY = (((nextByte and 0x3) shl 8) + (bytes[56].toInt() and 0xFF)) and 0x3FF

        val temperatureRaw = bytes[57].toInt() and 0xFF

        val buttonMask = bytes[58].toInt() and 0xFF
        val buttons = ButtonState(
            trigger = buttonMask and 0x01 != 0,
            home = buttonMask and 0x02 != 0,
            back = buttonMask and 0x04 != 0,
            touchpadClick = buttonMask and 0x08 != 0,
            volumeUp = buttonMask and 0x10 != 0,
            volumeDown = buttonMask and 0x20 != 0,
        )

        return GearVrSample(
            imuSamples = imuSamples,
            magnetometerUt = magnetometer,
            touching = touching,
            touchpadX = touchpadX,
            touchpadY = touchpadY,
            buttons = buttons,
            temperatureRaw = temperatureRaw,
        )
    }
}
