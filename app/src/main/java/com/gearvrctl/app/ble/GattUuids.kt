package com.gearvrctl.app.ble

import java.util.UUID

/**
 * Reverse-engineered by the community (jsyang/gearvr-controller-webbluetooth,
 * rdady/gear-vr-controller-linux) — not from any official Samsung spec.
 */
object GattUuids {
    val SERVICE: UUID = UUID.fromString("4f63756c-7573-2054-6872-65656d6f7465")
    val WRITE_CHARACTERISTIC: UUID = UUID.fromString("c8c51726-81bc-483b-a052-f7a14ea3d282")
    val NOTIFY_CHARACTERISTIC: UUID = UUID.fromString("c8c51726-81bc-483b-a052-f7a14ea3d281")

    /** Standard BLE client characteristic config descriptor, needed to enable notifications. */
    val CLIENT_CHARACTERISTIC_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    val ENABLE_SENSOR_MODE: ByteArray = byteArrayOf(0x01, 0x00)
    val ENABLE_VR_MODE: ByteArray = byteArrayOf(0x08, 0x00)
    val DISABLE: ByteArray = byteArrayOf(0x00, 0x00)
    val KEEP_ALIVE: ByteArray = byteArrayOf(0x04, 0x00)
}
