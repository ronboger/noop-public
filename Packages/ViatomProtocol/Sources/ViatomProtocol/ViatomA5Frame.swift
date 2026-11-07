/// Candidate envelope from the current SleepImage Android client's UART path.
/// See docs/SLEEPIMAGE_ANDROID_PROTOCOL_EVIDENCE.md for inspected APK provenance.
/// This verifies framing only. Payloads remain opaque, potentially encrypted;
/// CRC success is neither authentication nor proof of a physiological sample.
public struct ViatomA5Frame: Equatable, Sendable {
    public let commandRaw: UInt8
    public let header3Raw: UInt8
    public let header4Raw: UInt8
    public let payload: [UInt8]

    public static func decode(_ bytes: [UInt8]) -> ViatomA5Frame? {
        guard bytes.count >= 8, bytes[0] == 0xA5,
              bytes[2] == (bytes[1] ^ 0xFF) else { return nil }
        let payloadLength = Int(bytes[5]) | Int(bytes[6]) << 8
        guard bytes.count == payloadLength + 8,
              ViatomSensorFrame.crc8(bytes.dropLast()) == bytes.last else { return nil }
        return ViatomA5Frame(commandRaw: bytes[1], header3Raw: bytes[3],
                            header4Raw: bytes[4], payload: Array(bytes[7..<bytes.count - 1]))
    }
}

/// Bounded reconstruction of complete A5 envelopes from fragmented notifications.
/// A caller must discard partial bytes on disconnect or timeout: an in-range
/// corrupted length is indistinguishable from a frame still awaiting fragments.
/// No resynchronization may turn these opaque envelopes into trusted measurements.
public struct ViatomA5Stream: Sendable {
    public let maxPayloadLength: Int
    private var pending: [UInt8] = []
    public private(set) var droppedBytes = 0
    public private(set) var crcFailures = 0
    public private(set) var oversizedHeaders = 0
    public var pendingByteCount: Int { pending.count }

    /// This is a local memory bound, not a claim about the ring's maximum frame.
    public init(maxPayloadLength: Int = 4096) {
        precondition((0...65535).contains(maxPayloadLength))
        self.maxPayloadLength = maxPayloadLength
    }

    public mutating func append(_ fragment: [UInt8]) -> [ViatomA5Frame] {
        var frames: [ViatomA5Frame] = []
        for byte in fragment {
            pending.append(byte)
            while !pending.isEmpty {
                guard let start = pending.firstIndex(of: 0xA5) else {
                    droppedBytes += pending.count
                    pending.removeAll(keepingCapacity: true)
                    break
                }
                if start > 0 { drop(start) }
                if pending.count < 3 { break }
                if pending[2] != (pending[1] ^ 0xFF) { drop(1); continue }
                if pending.count < 7 { break }
                let payloadLength = Int(pending[5]) | Int(pending[6]) << 8
                if payloadLength > maxPayloadLength {
                    oversizedHeaders += 1
                    drop(1)
                    continue
                }
                let frameLength = payloadLength + 8
                if pending.count < frameLength { break }
                if let frame = ViatomA5Frame.decode(Array(pending.prefix(frameLength))) {
                    frames.append(frame)
                    pending.removeFirst(frameLength)
                } else {
                    crcFailures += 1
                    drop(1)
                }
            }
        }
        return frames
    }

    /// Call at every transport discontinuity; never join two BLE connections.
    @discardableResult public mutating func discardPartial() -> Int {
        let count = pending.count
        drop(count)
        return count
    }

    private mutating func drop(_ count: Int) {
        droppedBytes += count
        pending.removeFirst(count)
    }
}
