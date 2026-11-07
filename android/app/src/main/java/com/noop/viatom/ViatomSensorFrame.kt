package com.noop.viatom

/**
 * Candidate Viatom PO2/PO3 read-sensors response decoder. NOT hardware-verified on a SleepImage ring.
 * Pure Kotlin: no BLE IO, vendor account, database write or medical scoring. Faithful twin of
 * Packages/ViatomProtocol/Sources/ViatomProtocol/ViatomSensorFrame.swift.
 *
 * Facts only from https://github.com/ecostech/viatom-ble (MIT, sensor offsets) and
 * https://github.com/MackeyStingray/o2r (GPLv3, frame format and CRC polynomial).
 * No GPL source code is included. Activate no BLE writes until the protocol is hardware-validated.
 */
object ViatomSensorFrame {
    const val SERVICE_UUID = "14839ac4-7d7e-415c-9a42-167340cf2339"
    const val NOTIFICATION_UUID = "0734594a-a8e7-4b1a-a6b1-cd5243059a57"
    const val WRITE_UUID = "8b00ace7-eb0b-49b0-bbe9-9aee0a26e1a3"
    const val SENSOR_FRAME_LENGTH = 21
    private const val SENSOR_PAYLOAD_LENGTH = 13

    /** Only a complete, successful 0x55 response with a valid CRC-8 is accepted. */
    fun decode(packet: ByteArray): ViatomSensorReading? {
        return when (val result = decodeDetailed(packet)) {
            is ViatomDecodeResult.Reading -> result.reading
            is ViatomDecodeResult.Rejected -> null
        }
    }

    /**
     * Decode with a diagnosis: either the reading, or the exact gate that rejected the packet.
     * The hardware-validation workflow needs to know *why* a real-device frame failed
     * (wrong length? error reply? CRC?) rather than a bare null.
     */
    fun decodeDetailed(packet: ByteArray): ViatomDecodeResult {
        fun u8(index: Int): Int = packet[index].toInt() and 0xff
        if (packet.size != SENSOR_FRAME_LENGTH) return ViatomDecodeResult.Rejected(ViatomFrameReject.WRONG_LENGTH)
        if (u8(0) != 0x55) return ViatomDecodeResult.Rejected(ViatomFrameReject.BAD_HEADER)
        if (u8(1) != 0x00) return ViatomDecodeResult.Rejected(ViatomFrameReject.ERROR_STATUS)
        if (u8(2) != (u8(1) xor 0xff)) return ViatomDecodeResult.Rejected(ViatomFrameReject.BAD_STATUS_COMPLEMENT)
        if ((u8(5) or (u8(6) shl 8)) != SENSOR_PAYLOAD_LENGTH) return ViatomDecodeResult.Rejected(ViatomFrameReject.WRONG_DECLARED_LENGTH)
        if (crc8(packet) != u8(packet.lastIndex)) return ViatomDecodeResult.Rejected(ViatomFrameReject.CRC_MISMATCH)
        return ViatomDecodeResult.Reading(
            ViatomSensorReading(
                rawSpO2 = u8(7), rawPulse = u8(8), rawBattery = u8(14),
                chargingStatusRaw = u8(15), motionRaw = u8(16),
                perfusionRaw = u8(17), isWorn = u8(18) != 0,
            )
        )
    }

    /** CRC-8/0x07 over all bytes except the trailing checksum, initial 0, no final xor. */
    internal fun crc8(bytes: ByteArray): Int {
        var crc = 0
        for (i in 0 until bytes.lastIndex) {
            crc = crc xor (bytes[i].toInt() and 0xff)
            repeat(8) {
                crc = if ((crc and 0x80) != 0) ((crc shl 1) xor 0x07) and 0xff
                      else (crc shl 1) and 0xff
            }
        }
        return crc
    }
}

/**
 * The specific gate a candidate frame failed. Mirrors the Swift `ViatomFrameReject`.
 * A different firmware response must get a separate decoder, not a weakened gate.
 */
enum class ViatomFrameReject {
    /** The packet is not exactly SENSOR_FRAME_LENGTH (21) bytes. */
    WRONG_LENGTH,
    /** The leading byte is not the 0x55 ring-response marker. */
    BAD_HEADER,
    /** The status byte is not 0x00: the ring answered an error reply, not a reading. */
    ERROR_STATUS,
    /** The complement byte is not the status byte XOR 0xFF (broken framing). */
    BAD_STATUS_COMPLEMENT,
    /** The 16-bit little-endian declared payload length is not 13. */
    WRONG_DECLARED_LENGTH,
    /** The trailing CRC-8 byte does not match. */
    CRC_MISMATCH,
}

/**
 * The result of a detailed decode: a reading, or the gate that rejected the packet.
 * Mirrors the Swift `ViatomDecodeResult`. Impossible to represent "accepted and rejected" at once.
 */
sealed class ViatomDecodeResult {
    data class Reading(val reading: ViatomSensorReading) : ViatomDecodeResult()
    data class Rejected(val rejected: ViatomFrameReject) : ViatomDecodeResult()
}

/** Raw bytes are not substituted with measurements when the ring is off-finger/calibrating. */
data class ViatomSensorReading(
    val rawSpO2: Int,
    val rawPulse: Int,
    val rawBattery: Int,
    val chargingStatusRaw: Int,
    val motionRaw: Int,
    val perfusionRaw: Int,
    val isWorn: Boolean,
) {
    val isCalibrating: Boolean get() = isWorn && rawSpO2 == 0 && rawPulse == 0
    val spo2Percent: Int? get() = rawSpO2.takeIf { isWorn && !isCalibrating && it in 1..100 }
    val pulseBpm: Int? get() = rawPulse.takeIf { isWorn && !isCalibrating && it in 1..254 }
    val batteryPercent: Int? get() = rawBattery.takeIf { it in 0..100 }
}
