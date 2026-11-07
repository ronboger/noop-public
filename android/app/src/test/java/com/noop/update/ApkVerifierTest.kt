package com.noop.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A corruption check, not a security boundary — Android's signature enforcement is what actually
 * stops a foreign APK. This exists so a truncated download reads as "download was corrupt" instead
 * of a baffling installer failure.
 */
class ApkVerifierTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Known-answer test: SHA-256 of the empty string. */
    @Test
    fun hashesEmptyFileToTheKnownDigest() {
        val f = tmp.newFile("empty.bin")
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ApkVerifier.sha256(f),
        )
    }

    @Test
    fun matchesIdenticalContent() {
        val f = tmp.newFile("a.apk").apply { writeBytes(ByteArray(4096) { it.toByte() }) }
        val digest = ApkVerifier.sha256(f)!!
        assertTrue(ApkVerifier.matches(f, digest))
    }

    @Test
    fun matchIsCaseInsensitive() {
        val f = tmp.newFile("b.apk").apply { writeText("hello") }
        val digest = ApkVerifier.sha256(f)!!
        assertTrue(ApkVerifier.matches(f, digest.uppercase()))
    }

    @Test
    fun rejectsTruncatedContent() {
        val full = tmp.newFile("full.apk").apply { writeBytes(ByteArray(4096) { it.toByte() }) }
        val expected = ApkVerifier.sha256(full)!!
        val truncated = tmp.newFile("short.apk").apply { writeBytes(ByteArray(2048) { it.toByte() }) }
        assertFalse(ApkVerifier.matches(truncated, expected))
    }

    @Test
    fun missingFileHashesToNullAndNeverMatches() {
        val missing = java.io.File(tmp.root, "nope.apk")
        assertNull(ApkVerifier.sha256(missing))
        assertFalse(ApkVerifier.matches(missing, "abc"))
    }
}
