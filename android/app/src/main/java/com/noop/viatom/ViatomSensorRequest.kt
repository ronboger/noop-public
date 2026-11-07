package com.noop.viatom

/** Single candidate telemetry request from issue #20; construction performs no BLE IO. */
object ViatomSensorRequest {
    @JvmStatic fun readSensors(): ByteArray {
        // Kotlin's existing CRC helper excludes the final checksum slot.
        val frame = byteArrayOf(0xAA.toByte(), 0x17, (0x17 xor 0xFF).toByte(), 0, 0, 0, 0, 0)
        frame[frame.lastIndex] = ViatomSensorFrame.crc8(frame).toByte()
        return frame
    }
}
