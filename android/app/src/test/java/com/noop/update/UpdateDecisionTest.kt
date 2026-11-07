package com.noop.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The load-bearing distinction: a MISSING manifest is Undetermined, not UpToDate. Reporting a failed
 * check as "up to date" would silently strand the user on an old build — the exact failure this
 * feature exists to prevent (spec §6).
 */
class UpdateDecisionTest {

    private fun manifest(code: Int) = UpdateManifest(code, "8.2.14", "a.apk", "abc", "")

    @Test
    fun newerVersionCodeIsAvailable() {
        val d = UpdateDecision.of(manifest(273), installedVersionCode = 272)
        assertTrue(d is UpdateDecision.Available)
        assertEquals(273, (d as UpdateDecision.Available).manifest.versionCode)
    }

    @Test
    fun equalVersionCodeIsUpToDate() {
        assertTrue(UpdateDecision.of(manifest(272), 272) is UpdateDecision.UpToDate)
    }

    @Test
    fun olderVersionCodeIsUpToDate() {
        // A downgrade is never offered: Android would refuse the install anyway.
        assertTrue(UpdateDecision.of(manifest(271), 272) is UpdateDecision.UpToDate)
    }

    @Test
    fun missingManifestIsUndetermined() {
        assertEquals(UpdateDecision.Undetermined, UpdateDecision.of(null, 272))
    }

    @Test
    fun upToDateReportsTheInstalledVersionName() {
        val d = UpdateDecision.of(manifest(272), 272) as UpdateDecision.UpToDate
        assertEquals("8.2.14", d.versionName)
    }
}
