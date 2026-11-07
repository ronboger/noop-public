import Foundation
import ViatomProtocol

// Inspect local notification captures without authentication, decryption or BLE.
// Never emit opaque payload bytes: a handshake payload may contain key material.
struct Record: Decodable {
    let hex: String?
    let ts_ms: Int64?
    let gap: Bool?
}

func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data((message + "\n").utf8))
    exit(2)
}

func parseHex(_ text: String) -> [UInt8]? {
    let digits = Array(text.trimmingCharacters(in: .whitespacesAndNewlines).utf8)
    guard digits.count % 2 == 0 else { return nil }
    func nibble(_ c: UInt8) -> UInt8? {
        switch c {
        case 48...57: return c - 48
        case 65...70: return c - 55
        case 97...102: return c - 87
        default: return nil
        }
    }
    var bytes: [UInt8] = []
    for i in stride(from: 0, to: digits.count, by: 2) {
        guard let high = nibble(digits[i]), let low = nibble(digits[i + 1]) else { return nil }
        bytes.append(high << 4 | low)
    }
    return bytes
}

let arguments = Array(CommandLine.arguments.dropFirst())
if arguments == ["--help"] || arguments == ["-h"] {
    print("""
    viatom-a5-replay [capture.json]
    Reads a JSON array from FILE or stdin: {"hex":"...","ts_ms":123},
    or {"gap":true,"ts_ms":123} at a disconnect/receive timeout.
    Emits JSON envelope metadata and diagnostics. Payloads remain opaque and
    are never printed. ts_ms is the completing fragment's host receipt time,
    not device/sample time. No BLE, commands, authentication, or decryption.
    """)
    exit(0)
}
guard arguments.count <= 1, !arguments.contains(where: { $0.hasPrefix("-") }) else {
    fail("usage: viatom-a5-replay [capture.json]")
}
let data: Data
if let file = arguments.first {
    guard let contents = FileManager.default.contents(atPath: file) else { fail("cannot read capture") }
    data = contents
} else { data = FileHandle.standardInput.readDataToEndOfFile() }

let records: [Record]
do { records = try JSONDecoder().decode([Record].self, from: data) }
catch { fail("expected a JSON array of capture records") }

var stream = ViatomA5Stream()
var output: [[String: Any]] = []
var count = 0
var invalid = 0
for (index, record) in records.enumerated() {
    var provenance: [String: Any] = ["record_index": index]
    if let timestamp = record.ts_ms { provenance["ts_ms"] = timestamp }
    if record.gap == true {
        guard record.hex == nil else { fail("gap record \(index) must not contain hex") }
        provenance["kind"] = "gap"
        provenance["discarded_bytes"] = stream.discardPartial()
        output.append(provenance)
        continue
    }
    guard let hex = record.hex, let bytes = parseHex(hex) else {
        invalid += 1
        provenance["kind"] = "invalid_hex"
        provenance["discarded_bytes"] = stream.discardPartial()
        output.append(provenance)
        continue
    }
    for frame in stream.append(bytes) {
        var metadata = provenance
        metadata["kind"] = "envelope"
        metadata["command_raw"] = Int(frame.commandRaw)
        metadata["header3_raw"] = Int(frame.header3Raw)
        metadata["header4_raw"] = Int(frame.header4Raw)
        metadata["payload_length"] = frame.payload.count
        output.append(metadata)
        count += 1
    }
}
output.append([
    "kind": "summary", "envelopes": count, "pending_bytes": stream.pendingByteCount,
    "dropped_bytes": stream.droppedBytes, "crc_failures": stream.crcFailures,
    "oversized_headers": stream.oversizedHeaders, "invalid_chunks": invalid,
])
do {
    let result = try JSONSerialization.data(withJSONObject: output, options: [.prettyPrinted, .sortedKeys])
    FileHandle.standardOutput.write(result)
    print("")
} catch { fail("could not encode replay result") }
