package com.noop.viatom

/**
 * Decrypted A5 status record (command 0x11, 20-byte plaintext). The field map is a CANDIDATE pinned
 * to the owner's verified 2026-10-13 session — all 90 decrypted status records — cross-read against
 * the vendor app's live readout at capture time (SpO2 95%, pulse 94 BPM, battery 100%):
 *
 *   [0..3]  u32le record index (each index repeats, ~2 Hz delivery)
 *   [4]     state byte — stayed 0x02 while measuring
 *   [5]     stayed 0
 *   [6]     SpO2 % — 0 during the unworn/invalid windows (paired zeros at [7] and [8])
 *   [7]     perfusion / signal-quality candidate (14..19 worn, 0 during the same invalid windows)
 *   [8]     pulse bpm — 0 while unworn
 *   [9..10] stayed 0
 *   [11]    movement-like value — stayed nonzero (30..70) through the invalid windows
 *   [12]    stayed 0
 *   [13]    battery % — matched the vendor app's 100%
 *   [14..19] stayed 0
 *
 * Single-capture evidence: treat the named fields as candidates, never clinical values. Timestamps
 * come from host reception; no device clock is established.
 */
data class ViatomStatusRecord(
    val index: Long,
    val state: Int,
    val rawSpO2: Int,
    val rawPerfusion: Int,
    val rawPulse: Int,
    val rawMotion: Int,
    val rawBattery: Int,
) {
    /** Present only when the ring reports a plausible reading (0 = unworn/calibrating). */
    val spo2Percent: Int? get() = rawSpO2.takeIf { it in 1..100 }
    val pulseBpm: Int? get() = rawPulse.takeIf { it in 1..254 }
    val batteryPercent: Int? get() = rawBattery.takeIf { it in 0..100 }

    /** True while the ring reports live measurements (false during the observed unworn windows). */
    val isMeasuring: Boolean get() = rawSpO2 != 0 || rawPulse != 0

    companion object {
        const val RECORD_LENGTH = 20

        fun decode(clear: ByteArray): ViatomStatusRecord? {
            if (clear.size != RECORD_LENGTH) return null
            fun u8(i: Int) = clear[i].toInt() and 0xff
            val index = (0..3).fold(0L) { acc, i -> acc or (u8(i).toLong() shl (i * 8)) }
            return ViatomStatusRecord(
                index = index,
                state = u8(4),
                rawSpO2 = u8(6),
                rawPerfusion = u8(7),
                rawPulse = u8(8),
                rawMotion = u8(11),
                rawBattery = u8(13),
            )
        }
    }
}
