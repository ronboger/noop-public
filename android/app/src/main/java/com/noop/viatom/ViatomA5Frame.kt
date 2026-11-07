package com.noop.viatom

/**
 * Candidate current SleepImage envelope; see docs/SLEEPIMAGE_ANDROID_PROTOCOL_EVIDENCE.md.
 * Pure framing only: payloads stay opaque, possibly encrypted. CRC is not authentication.
 * List<Int> exposes a read-only payload with value equality, matching the Swift twin.
 */
data class ViatomA5Frame private constructor(
    val commandRaw: Int,
    val header3Raw: Int,
    val header4Raw: Int,
    val payload: List<Int>,
) {
    companion object {
        fun decode(bytes: ByteArray): ViatomA5Frame? {
            fun u8(index: Int) = bytes[index].toInt() and 0xff
            if (bytes.size < 8 || u8(0) != 0xa5 || u8(2) != (u8(1) xor 0xff)) return null
            val payloadLength = u8(5) or (u8(6) shl 8)
            if (bytes.size != payloadLength + 8 || ViatomSensorFrame.crc8(bytes) != u8(bytes.lastIndex)) return null
            return ViatomA5Frame(u8(1), u8(3), u8(4), (7 until bytes.lastIndex).map(::u8))
        }
    }
}

/**
 * Bounded reassembly of fragmented A5 notifications. Discard partial data on disconnect/timeout:
 * a corrupt in-range length is indistinguishable from a frame waiting for more fragments.
 * The memory limit is local policy, not a hardware maximum. Mirrors Swift ViatomA5Stream.
 */
class ViatomA5Stream(val maxPayloadLength: Int = 4096) {
    init { require(maxPayloadLength in 0..65535) }
    private val pending = ArrayList<Byte>()
    var droppedBytes = 0
        private set
    var crcFailures = 0
        private set
    var oversizedHeaders = 0
        private set
    val pendingByteCount: Int get() = pending.size

    fun append(fragment: ByteArray): List<ViatomA5Frame> {
        val frames = mutableListOf<ViatomA5Frame>()
        for (byte in fragment) {
            pending.add(byte)
            while (pending.isNotEmpty()) {
                val start = pending.indexOf(0xa5.toByte())
                if (start < 0) { drop(pending.size); break }
                if (start > 0) drop(start)
                if (pending.size < 3) break
                if (u8(2) != (u8(1) xor 0xff)) { drop(1); continue }
                if (pending.size < 7) break
                val payloadLength = u8(5) or (u8(6) shl 8)
                if (payloadLength > maxPayloadLength) {
                    oversizedHeaders++
                    drop(1)
                    continue
                }
                val frameLength = payloadLength + 8
                if (pending.size < frameLength) break
                val frame = ViatomA5Frame.decode(pending.take(frameLength).toByteArray())
                if (frame != null) {
                    frames.add(frame)
                    pending.subList(0, frameLength).clear()
                } else {
                    crcFailures++
                    drop(1)
                }
            }
        }
        return frames
    }

    /** Call on each transport discontinuity; never join different BLE connections. */
    fun discardPartial(): Int = pending.size.also { drop(it) }

    private fun u8(index: Int) = pending[index].toInt() and 0xff
    private fun drop(count: Int) { droppedBytes += count; pending.subList(0, count).clear() }
}
