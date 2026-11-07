package com.noop.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A DAILY worker must not produce a daily notification about the same release. Notify once per
 * newly-seen versionCode; stay silent on every repeat.
 */
class UpdateNotifyPolicyTest {

    @Test
    fun notifiesForANewVersion() {
        assertTrue(UpdateNotifyPolicy.shouldNotify(candidateVersionCode = 273, lastNotifiedVersionCode = 272))
    }

    @Test
    fun notifiesWhenNothingHasEverBeenNotified() {
        assertTrue(UpdateNotifyPolicy.shouldNotify(273, 0))
    }

    @Test
    fun staysSilentOnARepeatOfTheSameVersion() {
        assertFalse(UpdateNotifyPolicy.shouldNotify(273, 273))
    }

    @Test
    fun staysSilentForAnOlderCandidateThanAlreadyNotified() {
        // Can happen if a release is deleted and an earlier one becomes "latest" again.
        assertFalse(UpdateNotifyPolicy.shouldNotify(272, 273))
    }
}
