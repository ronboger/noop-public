package com.noop.ble

import com.noop.ble.WhoopBleClient.Companion.PHONE_PROTECT_PAUSE_PCT
import com.noop.ble.WhoopBleClient.Companion.PHONE_PROTECT_RESUME_PCT
import com.noop.ble.WhoopBleClient.Companion.phoneLowLatched
import com.noop.ble.WhoopBleClient.Companion.phoneProtectActive
import com.noop.ble.WhoopBleClient.Companion.standardHrSilenced
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phone-battery protect: the pure decisions behind pausing the background stream and plain HR while the
 * PHONE (not the strap) is unplugged and in Battery Saver or low.
 */
class PhoneBatteryProtectTest {

    @Test
    fun thresholdsHaveADeadband() {
        assertEquals(20, PHONE_PROTECT_PAUSE_PCT)
        assertEquals(25, PHONE_PROTECT_RESUME_PCT)
        assertTrue(PHONE_PROTECT_RESUME_PCT > PHONE_PROTECT_PAUSE_PCT)
    }

    @Test
    fun latchEngagesAtOrBelowPauseThreshold() {
        assertTrue(phoneLowLatched(20, wasLatched = false))
        assertTrue(phoneLowLatched(3, wasLatched = false))
        assertFalse(phoneLowLatched(21, wasLatched = false))
    }

    @Test
    fun latchHoldsInsideTheDeadband() {
        for (pct in 21..24) {
            assertTrue("held at $pct", phoneLowLatched(pct, wasLatched = true))
            assertFalse("stays clear at $pct", phoneLowLatched(pct, wasLatched = false))
        }
    }

    @Test
    fun latchClearsAtOrAboveResumeThreshold() {
        assertFalse(phoneLowLatched(25, wasLatched = true))
        assertFalse(phoneLowLatched(80, wasLatched = true))
    }

    @Test
    fun unknownReadingHoldsTheLatch() {
        assertTrue(phoneLowLatched(-1, wasLatched = true))
        assertFalse(phoneLowLatched(-1, wasLatched = false))
    }

    @Test
    fun gaugeDitherAtTheBoundaryDoesNotFlap() {
        // 21 → 20 → 21 → 20 → 22 → 24 → 25: one engage, one release.
        var latched = false
        val edges = mutableListOf<Boolean>()
        for (pct in listOf(21, 20, 21, 20, 22, 24, 25)) {
            val next = phoneLowLatched(pct, latched)
            if (next != latched) edges += next
            latched = next
        }
        assertEquals(listOf(true, false), edges)
    }

    @Test
    fun activeOnBatterySaverOrLowWhileUnplugged() {
        assertTrue(phoneProtectActive(enabled = true, powered = false, powerSaveMode = true, lowLatched = false))
        assertTrue(phoneProtectActive(enabled = true, powered = false, powerSaveMode = false, lowLatched = true))
        assertFalse(phoneProtectActive(enabled = true, powered = false, powerSaveMode = false, lowLatched = false))
    }

    @Test
    fun powerAlwaysWins() {
        // Battery Saver stays on while charging up to ~90%: a phone on the charger must still capture.
        assertFalse(phoneProtectActive(enabled = true, powered = true, powerSaveMode = true, lowLatched = true))
    }

    @Test
    fun disabledNeverEngages() {
        assertFalse(phoneProtectActive(enabled = false, powered = false, powerSaveMode = true, lowLatched = true))
    }

    @Test
    fun lastNightScenarioEngagesBeforeTheBatteryDies() {
        // Unplugged at 76%, Battery Saver came on later; the phone died at 08:37. Protect must be engaged
        // from 20% down, with or without Battery Saver.
        var latched = false
        val engagedAt = mutableListOf<Int>()
        for (pct in 76 downTo 0) {
            latched = phoneLowLatched(pct, latched)
            if (phoneProtectActive(true, powered = false, powerSaveMode = false, lowLatched = latched)) engagedAt += pct
        }
        assertEquals(20, engagedAt.first())
        assertEquals(0, engagedAt.last())
    }

    private fun quiet(
        screen: Boolean = false, continuous: Boolean = false, protect: Boolean = false,
        alarm: Boolean = false, historyEmpty: Boolean = false,
    ) = WhoopBleClient.quietStandardHr(screen, continuous, protect, alarm, historyEmpty)

    @Test
    fun plainHrIsOffByDefaultInTheBackground() {
        assertTrue(quiet())
    }

    @Test
    fun realConsumersKeepPlainHrOn() {
        assertFalse(quiet(screen = true))
        assertFalse(quiet(continuous = true))
        assertFalse(quiet(alarm = true))
    }

    @Test
    fun emptyHistoryOffloadKeepsPlainHrOnBecauseItIsTheOnlySource() {
        assertFalse(quiet(historyEmpty = true))
        assertFalse(quiet(historyEmpty = true, protect = true))
    }

    @Test
    fun phoneProtectOverridesContinuousCaptureButNotAVisibleScreen() {
        assertTrue(quiet(continuous = true, protect = true))
        assertTrue(quiet(alarm = true, protect = true))
        assertFalse(quiet(screen = true, protect = true))
    }

    @Test
    fun smartAlarmWindowIncludesTheLeadTime() {
        val deadline = 10_000_000_000L
        val window = 30
        val opens = deadline - window * 60_000L - WhoopBleClient.SMART_ALARM_HR_LEAD_MS
        val f = { now: Long -> WhoopBleClient.smartAlarmNeedsHr(true, deadline, window, now) }
        assertFalse(f(opens - 1))
        assertTrue(f(opens))
        assertTrue(f(deadline))
        assertFalse(f(deadline + 1))
        assertFalse(WhoopBleClient.smartAlarmNeedsHr(false, deadline, window, deadline))
        assertFalse(WhoopBleClient.smartAlarmNeedsHr(true, 0L, window, 0L))
    }

    @Test
    fun liveScreenKeepsPlainHrOn() {
        assertTrue(standardHrSilenced(protectActive = true, screenWantsRealtime = false))
        assertFalse(standardHrSilenced(protectActive = true, screenWantsRealtime = true))
        assertFalse(standardHrSilenced(protectActive = false, screenWantsRealtime = false))
    }
}
