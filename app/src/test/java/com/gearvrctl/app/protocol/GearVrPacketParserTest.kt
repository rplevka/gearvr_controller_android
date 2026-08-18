package com.gearvrctl.app.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures below are real 60-byte notification payloads captured from a physical Gear VR
 * Controller via the in-app Capture Wizard (one isolated action per fixture), not synthetic data.
 */
private fun hex(line: String): ByteArray =
    line.trim().split(" ").map { it.toInt(16).toByte() }.toByteArray()

class GearVrPacketParserTest {

    private val idle = hex(
        "A1 37 D5 01 37 00 CC 01 F2 07 05 00 E1 FF E9 FF 7A 4A D5 01 36 00 C9 01 F0 07 05 00 E1 FF " +
            "EA FF 53 5D D5 01 36 00 C8 01 EF 07 05 00 E2 FF EA FF DF 0C 55 E3 02 01 20 00 00 1A 40 64"
    )
    private val trigger = hex(
        "B4 96 3A 02 1E FF 3A 05 AF 05 E2 00 47 FF D4 FE 8D A9 3A 02 E0 FF 3A 06 E6 03 A6 00 E8 FE " +
            "BE FE 66 BC 3A 02 F4 FE EC 05 73 05 B1 00 92 00 4F FF 04 0D 16 E5 35 02 20 00 00 1A 01 64"
    )
    private val home = hex(
        "EA 16 9D 02 0E FE D8 03 12 08 D2 FC 10 00 2D FF C3 29 9D 02 0D FE FA 03 F1 07 F0 FC FF FF " +
            "17 FF 9C 3C 9D 02 DA FD D1 03 10 08 29 FD 05 00 08 FF 75 0D AD E3 F8 01 20 00 00 1A 02 64"
    )
    private val back = hex(
        "6B 83 FA 02 F6 FF 97 03 33 07 0F FF 9B FF 1D 00 44 96 FA 02 24 00 60 03 F7 06 EF FE B9 FF " +
            "30 00 1D A9 FA 02 D4 FF E4 03 6C 07 C8 FE EE FF 24 00 1D 0C E9 E2 AF 01 20 00 00 1A 04 64"
    )
    private val touchpadClick = hex(
        "F7 98 58 03 20 01 9D 04 90 06 AC FF D8 FF F4 FF D0 AB 58 03 FC 00 81 04 9F 06 EC FF 9E FF " +
            "FB FF A9 BE 58 03 39 01 E4 04 BE 06 97 FF C1 FF DB FF BC 0B 95 E3 6C 02 12 C4 AD 1A 08 64"
    )
    private val volumeUp = hex(
        "C5 4F D7 04 BE FE B7 00 B9 07 56 FF 8C FF BB FF 9E 62 D7 04 B6 FE 46 01 10 08 80 FF 8B FF " +
            "C7 FF 77 75 D7 04 C2 FE 5A 01 05 08 53 FF 4F FF C1 FF 32 0D 33 E3 E0 01 20 00 00 1A 10 64"
    )
    private val volumeDown = hex(
        "2F 66 33 05 F8 FF 8A 01 FD 07 76 00 98 FF D1 FF 08 79 33 05 DA FF 90 01 44 08 A3 00 9C FF " +
            "D9 FF E1 8B 33 05 0E 00 7E 01 A1 07 E7 00 CA FF E5 FF A4 0C DC E2 F4 01 20 00 00 1A 20 64"
    )
    private val touchTopLeft = hex(
        "5E 72 B8 03 84 01 BF FF 0F 08 CD FF AE FF E3 FF 37 85 B8 03 83 01 B8 FF 19 08 C5 FF C2 FF " +
            "E5 FF 10 98 B8 03 7F 01 B1 FF 32 08 BF FF D1 FF E7 FF 37 0C 40 E2 A4 01 10 D4 32 1A 40 64"
    )
    private val touchBottomRight = hex(
        "AF 60 0A 04 E4 00 FF FE BF 07 2E 00 23 00 8C FF 88 73 0A 04 FB 00 F4 FE DF 07 49 00 07 00 " +
            "78 FF 61 86 0A 04 04 01 10 FF E0 07 52 00 E6 FF 6B FF 4B 0C 0E E2 80 01 14 25 10 1A 40 64"
    )

    @Test
    fun `parses 3 IMU sub-samples with correct timestamp and scaled accel gyro`() {
        val sample = GearVrPacketParser.parse(idle)
        assertEquals(3, sample.imuSamples.size)

        val first = sample.imuSamples[0]
        assertEquals(30750625L, first.timestampUs)
        assertEquals(0.2634, first.accelMps2.x, 0.001)
        assertEquals(2.2027, first.accelMps2.y, 0.001)
        assertEquals(9.7396, first.accelMps2.z, 0.001) // ~1g, controller resting flat
        assertEquals(0.3500, first.gyroDegPerSec.x, 0.001)
        assertEquals(-2.1701, first.gyroDegPerSec.y, 0.001)
        assertEquals(-1.6101, first.gyroDegPerSec.z, 0.001)
    }

    @Test
    fun `parses magnetometer`() {
        val sample = GearVrPacketParser.parse(idle)
        assertEquals(197.70, sample.magnetometerUt.x, 0.01)
        assertEquals(-440.34, sample.magnetometerUt.y, 0.01)
        assertEquals(15.48, sample.magnetometerUt.z, 0.01)
    }

    @Test
    fun `idle packet reports not touching and no buttons pressed`() {
        val sample = GearVrPacketParser.parse(idle)
        assertFalse(sample.touching)
        val b = sample.buttons
        assertFalse(b.trigger); assertFalse(b.home); assertFalse(b.back)
        assertFalse(b.touchpadClick); assertFalse(b.volumeUp); assertFalse(b.volumeDown)
        assertEquals(26, sample.temperatureRaw)
    }

    @Test
    fun `each button bit fires only for its own button`() {
        assertTrue(GearVrPacketParser.parse(trigger).buttons.trigger)
        assertTrue(GearVrPacketParser.parse(home).buttons.home)
        assertTrue(GearVrPacketParser.parse(back).buttons.back)
        assertTrue(GearVrPacketParser.parse(touchpadClick).buttons.touchpadClick)
        assertTrue(GearVrPacketParser.parse(volumeUp).buttons.volumeUp)
        assertTrue(GearVrPacketParser.parse(volumeDown).buttons.volumeDown)
    }

    @Test
    fun `touchpad touch flag and position decode correctly`() {
        val click = GearVrPacketParser.parse(touchpadClick)
        assertTrue(click.touching)
        assertEquals(177, click.touchpadX)
        assertEquals(173, click.touchpadY)

        val topLeft = GearVrPacketParser.parse(touchTopLeft)
        assertTrue(topLeft.touching)
        assertEquals(53, topLeft.touchpadX)
        assertEquals(50, topLeft.touchpadY)

        val bottomRight = GearVrPacketParser.parse(touchBottomRight)
        assertTrue(bottomRight.touching)
        assertEquals(265, bottomRight.touchpadX)
        assertEquals(272, bottomRight.touchpadY)

        // top-left corner must read a smaller X/Y than bottom-right
        assertTrue(topLeft.touchpadX < bottomRight.touchpadX)
        assertTrue(topLeft.touchpadY < bottomRight.touchpadY)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects packets that are not 60 bytes`() {
        GearVrPacketParser.parse(ByteArray(10))
    }
}
