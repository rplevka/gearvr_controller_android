package com.gearvrctl.app.protocol

/** Phase-1 ground-truth helper: turns a raw notify payload into a readable hex line. */
object RawPacketLogger {
    fun toHex(bytes: ByteArray): String =
        bytes.joinToString(separator = " ") { "%02X".format(it) }

    fun summaryLine(index: Int, bytes: ByteArray): String =
        "#$index (${bytes.size}B) ${toHex(bytes)}"
}
