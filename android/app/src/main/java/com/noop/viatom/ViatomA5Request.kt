package com.noop.viatom

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Outbound wire-format builders for the CURRENT SleepImage A5 transport observed in the owner's
 * installed app — see docs/SLEEPIMAGE_ANDROID_PROTOCOL_EVIDENCE.md (2026-10-14 verified facts).
 * Construction performs no BLE IO: the caller's GATT owns writes. The only commands built here are
 * the two the captured vendor session sent — the auth exchange and the notification-control
 * request. No settings, reset, firmware or delete commands exist in this file.
 */
object ViatomA5Request {
    /** Current UART service observed on the owned ring and in the installed SleepImage 2.3.1 APK. */
    const val SERVICE_UUID = "e8fb0001-a14b-98f9-831b-4e2941d01248"
    const val WRITE_UUID = "e8fb0002-a14b-98f9-831b-4e2941d01248"
    const val NOTIFY_UUID = "e8fb0003-a14b-98f9-831b-4e2941d01248"

    const val COMMAND_AUTH_EXCHANGE = 0xff
    const val COMMAND_NOTIFY_CONTROL = 0x10

    /** The single control byte the captured session encrypted to open the notification stream. */
    private const val NOTIFY_CONTROL_BYTE = 0x0b

    /**
     * 16-byte exchange request: `access || unixSeconds-le32 || zeros`, each byte XORed with the
     * secret's first 16 bytes direct-indexed (lockstep indices, no cycling — the mask structure is
     * what let the credential scan pin the pair). Verified against the captured handshake.
     * [secret] must be >= 16 bytes, [access] 1..12 (the recovered pair is 12).
     */
    fun authExchange(secret: ByteArray, access: ByteArray, unixSeconds: Long): ByteArray {
        require(secret.size >= 16) { "secret must be at least 16 bytes" }
        require(access.size in 1..12) { "access must be 1..12 bytes" }
        val plain = ByteArray(16)
        access.copyInto(plain)
        for (i in 0..3) {
            plain[access.size + i] = ((unixSeconds ushr (8 * i)) and 0xffL).toByte()
        }
        return ByteArray(16) { i ->
            ((plain[i].toInt() and 0xff) xor (secret[i].toInt() and 0xff)).toByte()
        }
    }

    /**
     * The notification-control command the captured official session sent after the exchange
     * completed: AES-128/ECB/PKCS7 of the single byte 0x0b under that connection's negotiated
     * session key. The vendor AES-128 oracle reproduced this ciphertext exactly. Stream output ends
     * at disconnect, so its effect is inherently reversible.
     */
    fun notifyControl(sessionKey: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sessionKey, "AES"))
        return cipher.doFinal(byteArrayOf(NOTIFY_CONTROL_BYTE.toByte()))
    }

    /**
     * A5 envelope: `a5 | command | ~command | 0 | 0 | payloadLength-le16 | 0 | payload | crc8`.
     * header3/header4 are 0 on the outbound requests the vendor app sent.
     *
     * The CRC-8/0x07 covers every byte BEFORE the checksum slot — the same semantics the inbound
     * decoder validates (all 270 captured envelopes verify). Computing it over the frame with a
     * zeroed checksum byte feeds the CRC one extra 0x00 input byte and produces a different value
     * the ring's own CRC gate would reject.
     */
    fun wrap(command: Int, payload: ByteArray): ByteArray {
        require(command in 0..255 && payload.size <= 0xffff)
        val frame = ByteArray(payload.size + 8)
        frame[0] = 0xa5.toByte()
        frame[1] = command.toByte()
        frame[2] = (command xor 0xff).toByte()
        frame[5] = (payload.size and 0xff).toByte()
        frame[6] = ((payload.size ushr 8) and 0xff).toByte()
        payload.copyInto(frame, destinationOffset = 7)
        frame[frame.lastIndex] = ViatomSensorFrame.crc8(frame).toByte()
        return frame
    }
}
