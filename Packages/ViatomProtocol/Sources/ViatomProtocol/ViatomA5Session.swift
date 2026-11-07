#if canImport(CommonCrypto)
import CommonCrypto
#endif

/// Offline candidate session decoder. Keys are supplied by the owner, never bundled or fetched.
/// Protocol provenance and unverified hardware assumptions: SLEEPIMAGE_ANDROID_PROTOCOL_EVIDENCE.md.
/// CRC and AES padding detect errors; this protocol has no cryptographic message authentication.
public final class ViatomA5Session {
    public enum Failure: Error, Equatable {
        case invalidCredentials, invalidExchange, noSession, invalidRecord, decryptionFailed, cryptoUnavailable
    }
    private var secret: [UInt8]
    private var key: [UInt8] = []
    private let expectedEncryptionType: UInt8
    public var hasSession: Bool { key.count == 16 }

    public init(secret: [UInt8], expectedEncryptionType: UInt8) throws {
        guard (16...4096).contains(secret.count) else { throw Failure.invalidCredentials }
        self.secret = secret
        self.expectedEncryptionType = expectedEncryptionType
    }

    /// Call on every disconnect, capture gap, malformed chunk, or failed exchange.
    public func reset() { key = [] }

    /// Accept only a complete, CRC-checked exchange response. A new exchange
    /// invalidates the old key even if the new response is malformed.
    /// Mirrors the decompiled 2.3.1 exchange handler (l.java case 7): payload[0..3]
    /// (encryptType/keyLength/reserved) are stored, never validated, and never
    /// reach the key, which is payload[4..20]; header4 is likewise unchecked.
    public func acceptExchange(_ frame: ViatomA5Frame) throws {
        reset()
        guard frame.commandRaw == 255, frame.header3Raw == 1,
              frame.payload.count == 20 else { throw Failure.invalidExchange }
        let clear = frame.payload.enumerated().map { $0.element ^ secret[$0.offset % secret.count] }
        key = Array(clear.dropFirst(4))
    }

    /// Negotiated session key (copy), for encrypting outbound requests on a live connection.
    public func sessionKey() throws -> [UInt8] {
        guard hasSession else { throw Failure.noSession }
        return key
    }

    /// Returns device bytes, without invented units, sample rate, or sample timestamps.
    /// Only the observed notification types are eligible; control/handshake bytes never escape.
    public func decrypt(_ frame: ViatomA5Frame) throws -> [UInt8] {
        guard hasSession else { throw Failure.noSession }
        guard [17, 18, 20].contains(frame.commandRaw), frame.header3Raw == 0,
              !frame.payload.isEmpty, frame.payload.count % 16 == 0 else { throw Failure.invalidRecord }
        do { return try Self.decryptAES(frame.payload, key: key) }
        catch { reset(); throw error }
    }

    /// Platform crypto adapter. Linux reports unavailable rather than using an unverified cipher.
    static func decryptAES(_ bytes: [UInt8], key: [UInt8]) throws -> [UInt8] {
        guard key.count == 16, !bytes.isEmpty, bytes.count % 16 == 0 else { throw Failure.decryptionFailed }
#if canImport(CommonCrypto)
        var output = [UInt8](repeating: 0, count: bytes.count)
        var moved = 0
        let capacity = output.count
        let status = CCCrypt(CCOperation(kCCDecrypt), CCAlgorithm(kCCAlgorithmAES),
                             CCOptions(kCCOptionECBMode),
                             key, key.count, nil, bytes, bytes.count, &output, capacity, &moved)
        guard status == kCCSuccess, moved == bytes.count, let padding = output.last,
              (1...16).contains(padding), output.suffix(Int(padding)).allSatisfy({ $0 == padding })
        else { throw Failure.decryptionFailed }
        return Array(output.dropLast(Int(padding)))
#else
        throw Failure.cryptoUnavailable
#endif
    }
}

/// Candidate 0x12 payload: uint32 LE device index, uint16 LE byte count, unsigned waveform bytes.
/// This is the device's pleth waveform, not established as raw optical ADC/PPG (0x13 was not observed).
public struct ViatomWaveform: Equatable, Sendable {
    public let deviceIndex: UInt32
    public let samples: [UInt8]

    public static func decode(_ clear: [UInt8]) -> ViatomWaveform? {
        guard clear.count >= 6 else { return nil }
        let count = Int(clear[4]) | Int(clear[5]) << 8
        guard count > 0, clear.count == 6 + count else { return nil }
        let index = (0..<4).reduce(UInt32(0)) { $0 | UInt32(clear[$1]) << ($1 * 8) }
        return ViatomWaveform(deviceIndex: index, samples: Array(clear.dropFirst(6)))
    }
}
