package com.noop.ble

import com.noop.ble.WhoopBleClient.Companion.SLEEP_GATE_MAX_STALENESS_S
import com.noop.ble.WhoopBleClient.Companion.SLEEP_GATE_MIN_SAMPLES
import com.noop.ble.WhoopBleClient.Companion.SleepGate
import com.noop.ble.WhoopBleClient.Companion.overnightWindowContains
import com.noop.ble.WhoopBleClient.Companion.restingHrBaseline
import com.noop.ble.WhoopBleClient.Companion.sleepAwareCaptureWanted
import com.noop.ble.WhoopBleClient.Companion.sleepCeilingBpm
import com.noop.ble.WhoopBleClient.Companion.sleepGateFrom
import com.noop.ble.WhoopBleClient.Companion.sleepGateOuterWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sleep-aware overnight capture: the pure decisions behind following the wearer's sleep, not a clock. */
class SleepGateTest {

    private fun minutes(bpm: Int, n: Int = 1200) = List(n) { bpm }

    @Test
    fun ceilingIsRestingPlusTwentyPercentWithAnEightBpmFloor() {
        assertEquals(51.6, sleepCeilingBpm(43.0), 1e-9)
        assertEquals(38.0, sleepCeilingBpm(30.0), 1e-9) // 20% = 6 < 8, floor applies
    }

    @Test
    fun outerWindowWidensQuietHours() {
        assertEquals(20 * 60 to 11 * 60, sleepGateOuterWindow(22 * 60, 7 * 60))
        // Wraps correctly when the quiet start is just after midnight.
        assertEquals(23 * 60 to 13 * 60, sleepGateOuterWindow(60, 9 * 60))
    }

    @Test
    fun lowHrReadsAsleepAndHighHrAwake() {
        assertEquals(SleepGate.ASLEEP, sleepGateFrom(minutes(48), 60, 43.0, wasAsleep = false))
        assertEquals(SleepGate.AWAKE, sleepGateFrom(minutes(70), 60, 43.0, wasAsleep = false))
    }

    @Test
    fun wakeNeedsTheHysteresisMargin() {
        // 56 bpm: above the 51.6 ceiling (awake if not yet asleep) but under ceiling + 10 (stays asleep).
        assertEquals(SleepGate.AWAKE, sleepGateFrom(minutes(56), 60, 43.0, wasAsleep = false))
        assertEquals(SleepGate.ASLEEP, sleepGateFrom(minutes(56), 60, 43.0, wasAsleep = true))
        assertEquals(SleepGate.AWAKE, sleepGateFrom(minutes(62), 60, 43.0, wasAsleep = true))
    }

    @Test
    fun aBriefSurgeDoesNotFlipTheMedian() {
        val night = minutes(48, 1000) + minutes(95, 200) // a REM/turn-over spike, 1/6 of the window
        assertEquals(SleepGate.ASLEEP, sleepGateFrom(night, 60, 43.0, wasAsleep = true))
    }

    @Test
    fun unknownWithoutBaselineTooFewRowsOrStaleData() {
        assertEquals(SleepGate.UNKNOWN, sleepGateFrom(minutes(48), 60, null, false))
        assertEquals(SleepGate.UNKNOWN, sleepGateFrom(minutes(48, SLEEP_GATE_MIN_SAMPLES - 1), 60, 43.0, false))
        assertEquals(SleepGate.UNKNOWN, sleepGateFrom(minutes(48), SLEEP_GATE_MAX_STALENESS_S + 1, 43.0, false))
    }

    @Test
    fun implausibleRowsAreIgnored() {
        val rows = minutes(48, 700) + List(600) { 0 } // zeros (off-wrist) dropped; 700 valid remain
        assertEquals(SleepGate.ASLEEP, sleepGateFrom(rows, 60, 43.0, false))
    }

    @Test
    fun captureFollowsTheGate() {
        assertTrue(sleepAwareCaptureWanted(inOuter = true, inCore = false, gate = SleepGate.ASLEEP))
        assertFalse(sleepAwareCaptureWanted(inOuter = false, inCore = false, gate = SleepGate.ASLEEP))
        assertFalse(sleepAwareCaptureWanted(inOuter = true, inCore = true, gate = SleepGate.AWAKE))
        // Unknown = the old clock window, byte for byte.
        assertTrue(sleepAwareCaptureWanted(inOuter = true, inCore = true, gate = SleepGate.UNKNOWN))
        assertFalse(sleepAwareCaptureWanted(inOuter = true, inCore = false, gate = SleepGate.UNKNOWN))
    }

    @Test
    fun lateMorningSleepStillStreamsUntilTheOuterLimit() {
        val (s, e) = sleepGateOuterWindow(22 * 60, 7 * 60)
        val at1030 = overnightWindowContains(10 * 60 + 30, s, e)
        val at1130 = overnightWindowContains(11 * 60 + 30, s, e)
        assertTrue(sleepAwareCaptureWanted(at1030, inCore = false, gate = SleepGate.ASLEEP))
        assertFalse(sleepAwareCaptureWanted(at1130, inCore = false, gate = SleepGate.ASLEEP))
    }

    @Test
    fun restingBaselineIsTheMedianOfRecentNights() {
        assertEquals(43.5, restingHrBaseline(listOf(50, null, 42, 43, 44))!!, 1e-9)
        assertEquals(43.0, restingHrBaseline(listOf(50, null, 42, 43))!!, 1e-9)
        assertNull(restingHrBaseline(listOf(43, null, 44)))
        // Only the newest 14 count.
        assertEquals(40.0, restingHrBaseline(List(10) { 70 } + List(14) { 40 })!!, 1e-9)
    }
}
