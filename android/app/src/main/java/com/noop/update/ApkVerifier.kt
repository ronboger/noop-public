package com.noop.update

import java.io.File
import java.security.MessageDigest

/**
 * SHA-256 for a downloaded APK, checked against the value in the release manifest.
 *
 * This catches a TRUNCATED or corrupted download. It is not the security boundary: an APK not signed
 * by the permanent private key cannot install over the existing app whatever this code does.
 */
object ApkVerifier {

    /** Lowercase hex SHA-256 of [file], or null if it cannot be read. Streams in 64 KB blocks so a
     *  ~20 MB APK never lands in memory whole. */
    fun sha256(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** True iff [file] hashes to [expected]. Case-insensitive; false when the file is unreadable. */
    fun matches(file: File, expected: String): Boolean {
        val actual = sha256(file) ?: return false
        return actual.equals(expected.trim(), ignoreCase = true)
    }
}
