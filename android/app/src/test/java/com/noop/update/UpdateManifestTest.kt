package com.noop.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The manifest is the only machine-readable contract between a published release and the phone.
 * A malformed one must resolve to null — never a partially-populated object, and never a throw —
 * so the caller can say "couldn't determine the latest version" rather than acting on garbage.
 */
class UpdateManifestTest {

    private val valid = """
        {"versionCode":273,"versionName":"8.2.14","apk":"NOOP-v8.2.14-private.apk",
         "sha256":"abc123","notes":"Fixes things."}
    """.trimIndent()

    @Test
    fun parsesWellFormed() {
        val m = UpdateManifest.parse(valid)!!
        assertEquals(273, m.versionCode)
        assertEquals("8.2.14", m.versionName)
        assertEquals("NOOP-v8.2.14-private.apk", m.apk)
        assertEquals("abc123", m.sha256)
        assertEquals("Fixes things.", m.notes)
    }

    @Test
    fun notesAreOptional() {
        val m = UpdateManifest.parse(
            """{"versionCode":273,"versionName":"8.2.14","apk":"a.apk","sha256":"abc"}"""
        )!!
        assertEquals("", m.notes)
    }

    @Test
    fun rejectsMissingVersionCode() {
        assertNull(UpdateManifest.parse("""{"versionName":"8.2.14","apk":"a.apk","sha256":"abc"}"""))
    }

    @Test
    fun rejectsMissingApk() {
        assertNull(UpdateManifest.parse("""{"versionCode":273,"versionName":"8.2.14","sha256":"abc"}"""))
    }

    @Test
    fun rejectsMissingSha() {
        assertNull(UpdateManifest.parse("""{"versionCode":273,"versionName":"8.2.14","apk":"a.apk"}"""))
    }

    @Test
    fun rejectsNonNumericVersionCode() {
        assertNull(
            UpdateManifest.parse("""{"versionCode":"nope","versionName":"8","apk":"a.apk","sha256":"abc"}""")
        )
    }

    @Test
    fun rejectsBlankApkName() {
        assertNull(
            UpdateManifest.parse("""{"versionCode":273,"versionName":"8","apk":"  ","sha256":"abc"}""")
        )
    }

    @Test
    fun rejectsGarbage() {
        assertNull(UpdateManifest.parse("not json at all"))
        assertNull(UpdateManifest.parse(""))
        assertNull(UpdateManifest.parse("[]"))
    }

    // Audit F3 / CWE-22: remote-controlled manifest.apk must reduce to a safe basename.

    @Test
    fun safeApkFileNameAcceptsPlainApk() {
        assertEquals("release.apk", UpdateManifest.safeApkFileName("release.apk"))
    }

    @Test
    fun safeApkFileNameStripsPathTraversal() {
        assertEquals("evil.apk", UpdateManifest.safeApkFileName("../evil.apk"))
    }

    @Test
    fun safeApkFileNameStripsAbsolutePath() {
        assertEquals("path.apk", UpdateManifest.safeApkFileName("/abs/path.apk"))
    }

    @Test
    fun safeApkFileNameRejectsNonApk() {
        assertNull(UpdateManifest.safeApkFileName("notapk.txt"))
    }

    @Test
    fun safeApkFileNameRejectsBareSuffixAndControlChars() {
        assertNull(UpdateManifest.safeApkFileName(".apk"))
        assertNull(UpdateManifest.safeApkFileName("bad\u0007name.apk"))
    }

    @Test
    fun safeApkFileNameAcceptsUpperCaseSuffix() {
        assertEquals("release.APK", UpdateManifest.safeApkFileName("release.APK"))
    }

    /**
     * This literal is real output of the manifest block in
     * Tools/publish-private-android-release.sh; if that script's JSON shape
     * changes, THIS TEST MUST BE UPDATED IN THE SAME COMMIT, because the phone
     * cannot read a manifest it does not recognise.
     */
    @Test
    fun parsesRealPublishScriptOutput() {
        // Verbatim capture from Tools/publish-private-android-release.sh — do not reformat.
        val scriptOutput = """
{
  "versionCode": 272,
  "versionName": "8.2.13",
  "apk": "NOOP-v8.2.13-private.apk",
  "sha256": "ed50d5414e3b54c225ecf77445fd4b40f414edc700dbcd80210c03b6798afb94",
  "notes": "Private Android maintenance release 8.2.13."
}
""".trimIndent()

        val m = UpdateManifest.parse(scriptOutput)
        assertNotNull(m)
        // versionCode == 272 as Int: if the script ever quotes it, parse fails or this equals fails.
        assertEquals(272, m!!.versionCode)
        assertEquals("8.2.13", m.versionName)
        assertEquals("NOOP-v8.2.13-private.apk", m.apk)
        assertEquals(
            "ed50d5414e3b54c225ecf77445fd4b40f414edc700dbcd80210c03b6798afb94",
            m.sha256
        )
        assertEquals("Private Android maintenance release 8.2.13.", m.notes)
        assertTrue("apk must end with .apk (script picks via basename)", m.apk.endsWith(".apk"))
    }
}
