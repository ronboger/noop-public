import Foundation
import ViatomProtocol

// viatom-decode: replay captured Viatom PO2 sensor frames through the SAME ViatomProtocol
// decoder the app will use, from the command line on any platform (Linux included). This is the
// headless protocol-RE workflow's Swift half for the SleepImage groundwork: a future capture tool
// records raw 21-byte notifications off a ring into a capture JSON file, and this decodes them,
// so the RE path and the app can never drift (no second decoder to disagree). Mirrors
// oura-decode.
//
// The tool ONLY decodes. It never connects, writes, stores, or scores anything.
//
// Usage:
//   viatom-decode [FILE]
//   cat capture.json | viatom-decode
//   viatom-decode --hex 5500ff00000d006148000000000055000306010073
//
// Input (any one of):
//   FILE            a capture/fixture JSON file: an array of {"hex": ...} objects. A richer
//                   capture format ({"hex","ts_ms"}) is a superset and is read too.
//   (stdin)         the same JSON, piped in, when no FILE is given.
//   --hex HEX ...   one or more raw frame hex strings instead of a file.
//
// Options:
//   --json          emit a JSON array with one object per frame
//   -h, --help      show this help
//
// Every frame that fails any gate is reported with the failing gate (wrongLength / badHeader /
// errorStatus / badStatusComplement / wrongDeclaredLength / crcMismatch) — never as a
// measurement. Readouts (SPO2/PULSE) are printed only when worn, not calibrating, and in range.

// MARK: - Input model

/// One input record. `hex` is required; `tsMs` is provenance the decoder ignores.
struct CaptureRecord: Decodable {
    let hex: String
    let tsMs: Int?
    enum CodingKeys: String, CodingKey { case hex; case tsMs = "ts_ms" }
}

// MARK: - Arg parsing (dependency-free)

func die(_ msg: String) -> Never {
    FileHandle.standardError.write(Data((msg + "\n").utf8))
    exit(2)
}

let helpText = """
viatom-decode: decode captured Viatom PO2 sensor frames with the ViatomProtocol decoder.

USAGE:
  viatom-decode [--json] [FILE]
  cat capture.json | viatom-decode
  viatom-decode --hex 5500ff00000d006148000000000055000306010073

Reads a capture/fixture JSON array of {"hex": ...} from FILE or stdin, or raw
frames from --hex. Decode-only: no BLE, no writes, no storage. Frames that fail
any gate are reported with the failing gate, never as measurements.
"""

var jsonOut = false
var hexArgs: [String] = []
var filePath: String?

var args = Array(CommandLine.arguments.dropFirst())
var i = 0
while i < args.count {
    let a = args[i]
    switch a {
    case "-h", "--help":
        print(helpText); exit(0)
    case "--json": jsonOut = true
    case "--hex":
        i += 1
        while i < args.count && !args[i].hasPrefix("--") {
            hexArgs.append(args[i]); i += 1
        }
        continue
    default:
        if a.hasPrefix("-") { die("unknown option: \(a)") }
        filePath = a
    }
    i += 1
}

// MARK: - Gather input records

func loadJSON(_ data: Data) -> [CaptureRecord] {
    do {
        return try JSONDecoder().decode([CaptureRecord].self, from: data)
    } catch {
        die("could not parse capture JSON: \(error)")
    }
}

var records: [CaptureRecord] = []
if !hexArgs.isEmpty {
    records = hexArgs.map { CaptureRecord(hex: $0, tsMs: nil) }
} else if let path = filePath {
    guard let data = FileManager.default.contents(atPath: path) else { die("cannot read file: \(path)") }
    records = loadJSON(data)
} else {
    let data = FileHandle.standardInput.readDataToEndOfFile()
    guard !data.isEmpty else { die(helpText) }
    records = loadJSON(data)
}

// MARK: - Decode helpers

func bytes(fromHex hex: String) -> [UInt8]? {
    let s = hex.trimmingCharacters(in: .whitespacesAndNewlines)
    guard s.count % 2 == 0 else { return nil }
    var out = [UInt8](); out.reserveCapacity(s.count / 2)
    var idx = s.startIndex
    while idx < s.endIndex {
        let next = s.index(idx, offsetBy: 2)
        guard let b = UInt8(s[idx..<next], radix: 16) else { return nil }
        out.append(b); idx = next
    }
    return out
}

/// One line per decoded reading: raw fields always, safe readouts only when earned.
func describe(_ r: ViatomSensorReading) -> String {
    var line = "worn=\(r.isWorn ? 1 : 0) calibrating=\(r.isCalibrating ? 1 : 0) "
    line += "SPO2=\(r.spo2Percent.map { "\($0)%" } ?? "-") "
    line += "PULSE=\(r.pulseBpm.map { "\($0)bpm" } ?? "-") "
    line += "BATT=\(r.batteryPercent.map { "\($0)%" } ?? "-") "
    line += "raw(o2=\(r.rawSpO2) hr=\(r.rawPulse) batt=\(r.rawBattery) charge=\(r.chargingStatusRaw) motion=\(r.motionRaw) pi=\(r.perfusionRaw))"
    return line
}

// MARK: - Run

struct JSONLine: Encodable {
    let index: Int
    let tsMs: Int?
    let kind: String          // "reading" | "rejected" | "badHex"
    let gate: String?         // set when kind == "rejected"
    let detail: String

    enum CodingKeys: String, CodingKey {
        case index, kind, gate, detail
        case tsMs = "ts_ms"
    }
}

var jsonLines: [JSONLine] = []
var total = 0
var decodedCount = 0
var gateCounts: [String: Int] = [:]

for (n, rec) in records.enumerated() {
    let prefix = "[\(n)] " + (rec.tsMs.map { "ts_ms=\($0) " } ?? "")
    guard let raw = bytes(fromHex: rec.hex) else {
        FileHandle.standardError.write(Data("skipping bad hex at index \(n)\n".utf8))
        jsonLines.append(JSONLine(index: n, tsMs: rec.tsMs, kind: "badHex", gate: nil, detail: rec.hex))
        if !jsonOut { print(prefix + "BAD_HEX") }
        continue
    }
    total += 1
    switch ViatomSensorFrame.decodeDetailed(raw) {
    case let .reading(reading):
        decodedCount += 1
        let line = describe(reading)
        if jsonOut {
            jsonLines.append(JSONLine(index: n, tsMs: rec.tsMs, kind: "reading", gate: nil, detail: line))
        } else {
            print(prefix + line)
        }
    case let .rejected(gate):
        let name = String(describing: gate)
        gateCounts[name, default: 0] += 1
        if jsonOut {
            jsonLines.append(JSONLine(index: n, tsMs: rec.tsMs, kind: "rejected", gate: name, detail: "\((raw.count)) bytes"))
        } else {
            print(prefix + "REJECTED \(name) (\(raw.count) bytes)")
        }
    }
}

if jsonOut {
    let enc = JSONEncoder()
    enc.outputFormatting = [.prettyPrinted, .withoutEscapingSlashes]
    if let data = try? enc.encode(jsonLines), let s = String(data: data, encoding: .utf8) {
        print(s)
    }
} else {
    var summary = "\n\(decodedCount) readings from \(total) frames\n"
    for (g, c) in gateCounts.sorted(by: { $0.value > $1.value }) {
        summary += "  rejected \(c) \(g)\n"
    }
    FileHandle.standardError.write(Data(summary.utf8))
}
