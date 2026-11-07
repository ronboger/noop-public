/// Single legacy telemetry request documented in issue #20 and ecostech/viatom-ble.
/// Construction has no Bluetooth side effects. Only the separate, deliberate
/// hardware diagnostic may send this candidate until ring behavior is verified.
public enum ViatomSensorRequest {
    public static func readSensors() -> [UInt8] {
        let header: [UInt8] = [0xAA, 0x17, 0x17 ^ 0xFF, 0, 0, 0, 0]
        return header + [ViatomSensorFrame.crc8(header[...])]
    }
}
