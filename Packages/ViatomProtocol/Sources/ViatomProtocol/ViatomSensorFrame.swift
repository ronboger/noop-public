/// Candidate Viatom PO2/PO3 read-sensors response parser. It is NOT verified against
/// SleepImage-branded firmware. No Bluetooth transport, commands, or clinical scoring.
///
/// Wire facts, independently reimplemented from public observations:
/// - https://github.com/ecostech/viatom-ble (MIT; 19+ byte readings / sensor field offsets)
/// - https://github.com/MackeyStingray/o2r (GPLv3; *facts only* for 0x55 framing,
///   13-byte read-sensors payload and CRC-8/0x07; no source code copied)
/// Keep this decoder out of the live ingest path until on-device capture validation.
public enum ViatomSensorFrame {
    /// The GATT service observed on generic Viatom PO2 rings. Not a verified
    /// identifier for every SleepImage firmware variant.
    public static let serviceUUID = "14839ac4-7d7e-415c-9a42-167340cf2339"
    public static let notificationUUID = "0734594a-a8e7-4b1a-a6b1-cd5243059a57"
    public static let writeUUID = "8b00ace7-eb0b-49b0-bbe9-9aee0a26e1a3"

    /// A read-sensors response consists of a 7-byte header, 13-byte payload,
    /// and 1-byte CRC-8. This does not include segmented/other response types.
    public static let sensorFrameLength = 21
    private static let payloadLength = 13

    /// Decode ONLY a complete, successful candidate sensor response.
    /// Return nil on every framing, payload-size or CRC mismatch. In particular,
    /// arbitrary BLE notifications must never be interpreted as measurements.
    public static func decode(_ packet: [UInt8]) -> ViatomSensorReading? {
        switch decodeDetailed(packet) {
        case let .reading(reading): return reading
        case .rejected: return nil
        }
    }

    /// Decode with a diagnosis: either the reading, or the exact gate that
    /// rejected the packet. The hardware-validation workflow needs to know
    /// *why* a real-device frame failed (wrong length? error reply? CRC?)
    /// rather than a bare nil — and the `viatom-decode` replay tool reports
    /// this without re-implementing the gates.
    public static func decodeDetailed(_ packet: [UInt8]) -> ViatomDecodeResult {
        guard packet.count == sensorFrameLength else { return .rejected(.wrongLength) }
        guard packet[0] == 0x55 else { return .rejected(.badHeader) }
        guard packet[1] == 0x00 else { return .rejected(.errorStatus) } // not an error reply
        guard packet[2] == (packet[1] ^ 0xFF) else { return .rejected(.badStatusComplement) }
        guard Int(packet[5]) | (Int(packet[6]) << 8) == payloadLength else { return .rejected(.wrongDeclaredLength) }
        guard crc8(packet.dropLast()) == packet[sensorFrameLength - 1] else { return .rejected(.crcMismatch) }

        return .reading(ViatomSensorReading(
            rawSpO2: Int(packet[7]), rawPulse: Int(packet[8]),
            rawBattery: Int(packet[14]), chargingStatusRaw: Int(packet[15]),
            motionRaw: Int(packet[16]), perfusionRaw: Int(packet[17]),
            isWorn: packet[18] != 0))
    }

    /// CRC-8 with polynomial 0x07, initial value 0x00 and no final XOR.
    /// Applies to all frame bytes except the final checksum byte.
    static func crc8(_ bytes: ArraySlice<UInt8>) -> UInt8 {
        var crc: UInt8 = 0
        for byte in bytes {
            crc ^= byte
            for _ in 0..<8 {
                crc = (crc & 0x80) != 0 ? (crc &<< 1) ^ 0x07 : (crc &<< 1)
            }
        }
        return crc
    }
}

/// The result of a detailed decode: a reading, or the gate that rejected the
/// packet. Impossible to represent "accepted and rejected" at the same time.
public enum ViatomDecodeResult: Equatable, Sendable {
    case reading(ViatomSensorReading)
    case rejected(ViatomFrameReject)
}

/// The specific gate a candidate frame failed. Lets the hardware-verification
/// workflow and the `viatom-decode` replay tool report WHICH gate rejected a
/// captured frame instead of a bare nil — a different firmware response must
/// get a separate decoder, not a weakened gate.
public enum ViatomFrameReject: Equatable, Sendable, CaseIterable {
    /// The packet is not exactly `sensorFrameLength` (21) bytes.
    case wrongLength
    /// The leading byte is not the 0x55 ring-response marker.
    case badHeader
    /// The status byte is not 0x00: the ring answered an error reply, not a reading.
    case errorStatus
    /// The complement byte is not the status byte XOR 0xFF (broken framing).
    case badStatusComplement
    /// The 16-bit little-endian declared payload length is not 13.
    case wrongDeclaredLength
    /// The trailing CRC-8 byte does not match.
    case crcMismatch
}

/// Raw candidate values are retained even if the ring is unworn/calibrating.
/// Only safe, present measurements are exposed via the optional readouts.
/// Timestamps belong to the BLE receiver's host clock, not this packet.
public struct ViatomSensorReading: Sendable, Equatable {
    public let rawSpO2: Int
    public let rawPulse: Int
    public let rawBattery: Int
    public let chargingStatusRaw: Int
    public let motionRaw: Int
    public let perfusionRaw: Int
    public let isWorn: Bool

    public var isCalibrating: Bool { isWorn && rawSpO2 == 0 && rawPulse == 0 }

    /// Reject absent, calibration, and out-of-range samples; never turn 0 or
    /// off-finger sentinels into a valid oxygen saturation measurement.
    public var spo2Percent: Int? {
        guard isWorn, !isCalibrating, (1...100).contains(rawSpO2) else { return nil }
        return rawSpO2
    }

    public var pulseBpm: Int? {
        guard isWorn, !isCalibrating, (1...254).contains(rawPulse) else { return nil }
        return rawPulse
    }

    public var batteryPercent: Int? { (0...100).contains(rawBattery) ? rawBattery : nil }
}
