package com.noop.viatom

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Offline candidate session decoder; no bundled keys, network calls or Bluetooth side effects.
 * See SLEEPIMAGE_ANDROID_PROTOCOL_EVIDENCE.md. CRC/padding are not a cryptographic MAC.
 */
class ViatomA5Session(secret: ByteArray, private val expectedEncryptionType: Int) {
    enum class Failure { INVALID_CREDENTIALS, INVALID_EXCHANGE, NO_SESSION, INVALID_RECORD, DECRYPTION_FAILED }
    class SessionException(val failure: Failure) : Exception(failure.name)
    private val secret = secret.copyOf()
    private var key: ByteArray? = null
    val hasSession: Boolean get() = key != null

    init {
        if (secret.size !in 16..4096 || expectedEncryptionType !in 0..255)
            throw SessionException(Failure.INVALID_CREDENTIALS)
    }

    /** Invalidate session on every disconnect, receive gap, malformed chunk or failed exchange. */
    fun reset() { key?.fill(0); key = null }

    /** Negotiated session key (copy), for encrypting outbound requests on a live connection. */
    fun sessionKey(): ByteArray = key?.copyOf() ?: throw SessionException(Failure.NO_SESSION)

    /** A new response always invalidates the previous session, including malformed responses.
     *  Mirrors the decompiled 2.3.1 exchange handler (l.java case 7): payload[0..3] are
     *  encryptType/keyLength/reserved and are stored, never validated; the key is payload[4..20). */
    fun acceptExchange(frame: ViatomA5Frame) {
        reset()
        if (frame.commandRaw != 255 || frame.header3Raw != 1 || frame.payload.size != 20)
            throw SessionException(Failure.INVALID_EXCHANGE)
        val clear = frame.payload.mapIndexed { i, b -> b xor (secret[i % secret.size].toInt() and 255) }
        key = clear.drop(4).map { it.toByte() }.toByteArray()
    }

    /** Preserve device bytes; don't invent units, sampling rate or device timestamps. */
    fun decrypt(frame: ViatomA5Frame): ByteArray {
        val sessionKey = key ?: throw SessionException(Failure.NO_SESSION)
        if (frame.commandRaw !in listOf(17, 18, 20) || frame.header3Raw != 0 ||
            frame.payload.isEmpty() || frame.payload.size % 16 != 0)
            throw SessionException(Failure.INVALID_RECORD)
        try {
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sessionKey, "AES"))
            return cipher.doFinal(frame.payload.map { it.toByte() }.toByteArray())
        } catch (_: java.security.GeneralSecurityException) {
            reset()
            throw SessionException(Failure.DECRYPTION_FAILED)
        }
    }
}

/** Candidate pleth bytes (0x12), not established raw ADC/PPG (0x13 was not captured). */
data class ViatomWaveform(val deviceIndex: Long, val samples: List<Int>) {
    companion object {
        fun decode(clear: ByteArray): ViatomWaveform? {
            fun u8(i: Int) = clear[i].toInt() and 255
            if (clear.size < 6) return null
            val count = u8(4) or (u8(5) shl 8)
            if (count == 0 || clear.size != 6 + count) return null
            val index = (0..3).fold(0L) { result, i -> result or (u8(i).toLong() shl (i * 8)) }
            return ViatomWaveform(index, (6 until clear.size).map(::u8))
        }
    }
}
