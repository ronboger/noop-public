import Foundation
import ViatomProtocol
#if canImport(Darwin)
import Darwin
#else
import Glibc
#endif

// Offline only. Credentials are read from a private file, never command-line values or output.
// No network, vendor app, outbound command or Bluetooth dependency.
struct Credentials: Decodable { let secret_hex: String; let encryption_type: UInt8 }
struct Chunk: Decodable { let hex: String?; let ts_ms: Int64?; let gap: Bool? }
func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data((message + "\n").utf8)); exit(2)
}
func hex(_ string: String) -> [UInt8]? {
    let chars = Array(string.utf8)
    guard chars.count % 2 == 0 else { return nil }
    func nibble(_ c: UInt8) -> UInt8? {
        switch c { case 48...57: return c - 48; case 65...70: return c - 55; case 97...102: return c - 87; default: return nil }
    }
    var result: [UInt8] = []
    for i in stride(from: 0, to: chars.count, by: 2) {
        guard let a = nibble(chars[i]), let b = nibble(chars[i+1]) else { return nil }
        result.append(a << 4 | b)
    }
    return result
}
let args = Array(CommandLine.arguments.dropFirst())
if args == ["--help"] {
    print("""
    viatom-a5-decrypt credentials.json capture.json NEW-output.jsonl
    Credentials: {"secret_hex":"owner-supplied hex", "encryption_type":1}
    The encryption type must match the owner's exchange; 1 is an example, not a hardware claim.
    Capture: JSON array of {"hex":"...", "ts_ms":123} notification chunks;
    insert {"gap":true} at every disconnect/timeout. Must include the exchange response.
    Writes owner-only JSONL containing candidate decrypted device bytes and diagnostics.
    Requires Apple CommonCrypto. No supplied credentials are fetched or printed.
    Receipt times are host timestamps, not sample timestamps. Pleth units/rate unvalidated.
    Exit 0: at least one candidate waveform, no errors or partial tail. Exit 3: incomplete/unverified.
    """); exit(0)
}
guard args.count == 3 else { fail("usage: viatom-a5-decrypt credentials.json capture.json NEW-output.jsonl") }
#if !canImport(CommonCrypto)
fail("AES decryption requires Apple CommonCrypto on this build")
#endif
let credentials: Credentials
let chunks: [Chunk]
do {
    credentials = try JSONDecoder().decode(Credentials.self, from: Data(contentsOf: URL(fileURLWithPath: args[0])))
    chunks = try JSONDecoder().decode([Chunk].self, from: Data(contentsOf: URL(fileURLWithPath: args[1])))
} catch { fail("cannot read credentials/capture JSON; values have not been logged") }
guard let secret = hex(credentials.secret_hex) else { fail("invalid secret hex") }
let session: ViatomA5Session
do { session = try ViatomA5Session(secret: secret, expectedEncryptionType: credentials.encryption_type) }
catch { fail("invalid credential length") }
let fd = open(args[2], O_WRONLY | O_CREAT | O_EXCL, mode_t(0o600))
guard fd >= 0 else { fail("cannot create output (must be a new file)") }
let output = FileHandle(fileDescriptor: fd, closeOnDealloc: true)
func emit(_ row: [String: Any]) {
    do {
        var bytes = try JSONSerialization.data(withJSONObject: row, options: [.sortedKeys])
        bytes.append(10); try output.write(contentsOf: bytes)
    } catch { fail("output write failed") }
}
var stream = ViatomA5Stream()
var waveforms = 0
var samples = 0
var rejected = 0
var exchanges = 0
for (index, chunk) in chunks.enumerated() {
    var row: [String: Any] = ["chunk_index": index]
    if let timestamp = chunk.ts_ms { row["received_at_ms"] = timestamp }
    if chunk.gap == true {
        session.reset()
        row["kind"] = chunk.hex == nil ? "gap" : "invalid_gap"
        if chunk.hex != nil { rejected += 1 }
        row["discarded_bytes"] = stream.discardPartial(); emit(row); continue
    }
    guard let text = chunk.hex, let bytes = hex(text) else {
        session.reset(); rejected += 1; row["kind"] = "invalid_hex"
        row["discarded_bytes"] = stream.discardPartial(); emit(row); continue
    }
    let previousDropped = stream.droppedBytes
    let frames = stream.append(bytes)
    if stream.droppedBytes != previousDropped {
        session.reset(); rejected += 1
        emit(["kind": "framing_gap", "chunk_index": index])
        // The framing API returns frames, not ordered gap events. Reject this entire
        // chunk rather than accidentally accepting an exchange preceding an internal gap.
        stream.discardPartial()
        continue
    }
    for frame in frames {
        var record = row
        record["command_raw"] = Int(frame.commandRaw)
        do {
            if frame.commandRaw == 255 {
                try session.acceptExchange(frame); exchanges += 1
                record["kind"] = "candidate_session"; emit(record); continue
            }
            if frame.commandRaw == 16, session.hasSession, frame.header3Raw == 1,
               frame.header4Raw == 0, frame.payload.isEmpty {
                record["kind"] = "control_ack"; emit(record); continue
            }
            let clear = try session.decrypt(frame)
            record["sequence_raw"] = Int(frame.header4Raw)
            if frame.commandRaw == 18 {
                guard let wave = ViatomWaveform.decode(clear) else { throw ViatomA5Session.Failure.invalidRecord }
                record["kind"] = "candidate_pleth"
                record["device_index_raw"] = UInt64(wave.deviceIndex)
                record["samples_u8"] = wave.samples.map(Int.init)
                waveforms += 1; samples += wave.samples.count
            } else {
                record["kind"] = "decrypted_device_bytes"
                record["payload_hex"] = clear.map { String(format: "%02x", $0) }.joined()
            }
            emit(record)
        } catch {
            session.reset(); rejected += 1; record["kind"] = "rejected"
            record["reason"] = String(describing: error); emit(record)
        }
    }
}
let complete = waveforms > 0 && rejected == 0 && stream.pendingByteCount == 0
emit(["kind": "summary", "candidate_sessions": exchanges, "waveform_packets": waveforms,
      "waveform_bytes": samples, "rejected": rejected, "pending_bytes": stream.pendingByteCount,
      "complete_replay": complete, "hardware_validated": false])
do { try output.synchronize(); try output.close() } catch { fail("output flush failed") }
print("Candidate waveform packets: \(waveforms); samples: \(samples); rejected: \(rejected); pending bytes: \(stream.pendingByteCount). Hardware validation remains required.")
exit(complete ? 0 : 3)
