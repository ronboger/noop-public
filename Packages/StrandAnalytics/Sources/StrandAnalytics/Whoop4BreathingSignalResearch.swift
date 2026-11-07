import Foundation
import WhoopProtocol

/// Capability-first analysis for the private WHOOP 4 breathing-signal experiment.
///
/// This code asks only whether the reverse-engineered, one-row-per-second historical vectors contain
/// a stable rhythm in a plausible respiratory frequency band. It does not detect respiratory events,
/// estimate AHI/RDI, or infer sleep apnea. The historical fields are DSP outputs with unconfirmed
/// semantics, so every result remains a signal-quality observation.
public enum Whoop4BreathingSignalResearch {
    public enum Status: String, Sendable, Equatable {
        case unavailable
        case insufficientData
        case inconclusive
        case breathingLikeMotionObserved
    }

    public struct CapabilityReport: Sendable, Equatable {
        public let layoutVersions: [Int]
        public let sampleCount: Int
        public let spanHours: Double
        public let timeCoverage: Double
        public let primaryVectorCoverage: Double
        public let secondaryVectorCoverage: Double
        public let contactCoverage: Double
        public let signalQualityCoverage: Double
        /// Fraction of rows carrying both vectors whose six values are effectively identical.
        /// `nil` means there were no complete vector pairs to compare.
        public let duplicateVectorFraction: Double?
        public let hasOpticalContext: Bool
    }

    public struct Result: Sendable, Equatable {
        public let status: Status
        public let capability: CapabilityReport
        public let evaluatedWindows: Int
        public let usableWindows: Int
        public let rhythmicWindowFraction: Double
        /// Median share of non-DC spectral power in 0.10...0.40 Hz across usable windows.
        public let medianBandPowerFraction: Double?
        /// Median share of band power concentrated around its strongest frequency bin.
        public let medianRhythmConcentration: Double?
        /// Dominant motion cycles/minute, deliberately not labelled respiratory rate.
        public let medianDominantCyclesPerMinute: Double?
        /// Fraction of adjacent vector pairs changing by more than 0.03 candidate units per second.
        public let largeVectorChangeFraction: Double?
        public let reason: String
    }

    private static let windowSeconds = 300
    private static let minimumUsableWindows = 6 // 30 minutes; a feasibility gate, not a clinical gate.

    public static func analyze(_ input: [Whoop4ResearchSample]) -> Result {
        let rows = input.sorted { $0.ts < $1.ts }
        let capability = capabilityReport(rows)
        guard rows.count >= 2 else {
            return Result(status: .unavailable, capability: capability, evaluatedWindows: 0,
                          usableWindows: 0, rhythmicWindowFraction: 0,
                          medianBandPowerFraction: nil, medianRhythmConcentration: nil,
                          medianDominantCyclesPerMinute: nil, largeVectorChangeFraction: nil,
                          reason: "No WHOOP 4 historical research rows")
        }

        var evaluatedWindows = 0
        var windows: [WindowResult] = []
        var start = 0
        while start < rows.count {
            let bucketStart = (rows[start].ts / windowSeconds) * windowSeconds
            var end = start
            while end < rows.count && rows[end].ts < bucketStart + windowSeconds { end += 1 }
            evaluatedWindows += 1
            if let result = evaluateWindow(Array(rows[start..<end]), expectedStart: bucketStart) {
                windows.append(result)
            }
            start = end
        }

        let rhythmicFraction = windows.isEmpty ? 0
            : Double(windows.filter(\.isRhythmic).count) / Double(windows.count)
        let bandPower = medianOptional(windows.map(\.bandPowerFraction))
        let concentration = medianOptional(windows.map(\.rhythmConcentration))
        let cycles = medianOptional(windows.filter(\.isRhythmic).map(\.dominantCyclesPerMinute))
        let largeChange = weightedFraction(windows.map { ($0.largeChanges, $0.vectorComparisons) })

        guard capability.timeCoverage >= 0.70 else {
            return Result(status: .insufficientData, capability: capability,
                          evaluatedWindows: evaluatedWindows, usableWindows: windows.count,
                          rhythmicWindowFraction: rhythmicFraction,
                          medianBandPowerFraction: bandPower, medianRhythmConcentration: concentration,
                          medianDominantCyclesPerMinute: cycles, largeVectorChangeFraction: largeChange,
                          reason: "Less than 70% time coverage in the selected sleep window")
        }
        guard windows.count >= minimumUsableWindows else {
            return Result(status: .insufficientData, capability: capability,
                          evaluatedWindows: evaluatedWindows, usableWindows: windows.count,
                          rhythmicWindowFraction: rhythmicFraction,
                          medianBandPowerFraction: bandPower, medianRhythmConcentration: concentration,
                          medianDominantCyclesPerMinute: cycles, largeVectorChangeFraction: largeChange,
                          reason: "Fewer than 30 minutes passed strict on-wrist and continuity checks")
        }

        let observed = rhythmicFraction >= 0.50 && (concentration ?? 0) >= 0.20
        return Result(status: observed ? .breathingLikeMotionObserved : .inconclusive,
                      capability: capability, evaluatedWindows: evaluatedWindows,
                      usableWindows: windows.count, rhythmicWindowFraction: rhythmicFraction,
                      medianBandPowerFraction: bandPower, medianRhythmConcentration: concentration,
                      medianDominantCyclesPerMinute: cycles, largeVectorChangeFraction: largeChange,
                      reason: observed
                        ? "A repeatable rhythm exists in a plausible breathing-frequency band; its physiological source is unconfirmed"
                        : "The stored vectors do not yet show a sufficiently repeatable breathing-like rhythm")
    }

    public static func capabilityReport(_ rows: [Whoop4ResearchSample]) -> CapabilityReport {
        let sorted = rows.sorted { $0.ts < $1.ts }
        let span = sorted.count < 2 ? 0 : max(1, sorted.last!.ts - sorted.first!.ts + 1)
        let primary = sorted.filter { completePrimary($0) != nil }.count
        let secondary = sorted.filter { completeSecondary($0) != nil }.count
        let contact = sorted.filter { $0.skinContact != nil }.count
        let pairs = sorted.compactMap { row -> Bool? in
            guard let a = completePrimary(row), let b = completeSecondary(row) else { return nil }
            let squaredDifference = zip(a, b).reduce(0.0) { $0 + ($1.0 - $1.1) * ($1.0 - $1.1) }
            return squaredDifference <= 1e-12
        }
        return CapabilityReport(
            layoutVersions: Array(Set(sorted.map(\.layoutVersion))).sorted(),
            sampleCount: sorted.count,
            spanHours: Double(span) / 3600.0,
            timeCoverage: span == 0 ? 0 : min(1, Double(Set(sorted.map(\.ts)).count) / Double(span)),
            primaryVectorCoverage: sorted.isEmpty ? 0 : Double(primary) / Double(sorted.count),
            secondaryVectorCoverage: sorted.isEmpty ? 0 : Double(secondary) / Double(sorted.count),
            contactCoverage: sorted.isEmpty ? 0 : Double(contact) / Double(sorted.count),
            signalQualityCoverage: sorted.isEmpty ? 0
                : Double(sorted.filter { $0.signalQuality != nil }.count) / Double(sorted.count),
            duplicateVectorFraction: pairs.isEmpty ? nil
                : Double(pairs.filter { $0 }.count) / Double(pairs.count),
            hasOpticalContext: sorted.contains {
                $0.ppgGreen != nil || $0.ppgRedIR != nil || $0.spo2Red != nil || $0.spo2IR != nil
            })
    }

    private struct WindowResult {
        let bandPowerFraction: Double
        let rhythmConcentration: Double
        let dominantCyclesPerMinute: Double
        let isRhythmic: Bool
        let largeChanges: Int
        let vectorComparisons: Int
    }

    private struct SpectrumResult {
        let bandPowerFraction: Double
        let rhythmConcentration: Double
        let dominantCyclesPerMinute: Double
        let score: Double
    }

    private static func evaluateWindow(_ rows: [Whoop4ResearchSample], expectedStart: Int) -> WindowResult? {
        // A missing contact value is unknown, not evidence that the strap was worn. Require an explicit
        // nonzero value so layouts without a verified contact field cannot silently pass this gate.
        var bySecond: [Int: Whoop4ResearchSample] = [:]
        for row in rows where (row.skinContact ?? 0) > 0 { bySecond[row.ts] = row }
        guard bySecond.count >= 240 else { return nil }

        let getters: [(Whoop4ResearchSample) -> Double?] = [
            { $0.motion1X }, { $0.motion1Y }, { $0.motion1Z },
            { $0.motion2X }, { $0.motion2Y }, { $0.motion2Z },
        ]
        var bestSpectrum: SpectrumResult?
        for get in getters {
            let candidate = (0..<windowSeconds).map { bySecond[expectedStart + $0].flatMap(get) }
            guard let signal = interpolate(candidate, maximumGap: 2), let spectrum = spectrum(signal) else {
                continue
            }
            if spectrum.score > (bestSpectrum?.score ?? -1) { bestSpectrum = spectrum }
        }
        guard let bestSpectrum else { return nil }

        let ordered = bySecond.values.sorted { $0.ts < $1.ts }
        var largeChanges = 0
        var comparisons = 0
        for pair in zip(ordered, ordered.dropFirst()) where pair.1.ts - pair.0.ts == 1 {
            let left = completePrimary(pair.0) ?? completeSecondary(pair.0)
            let right = completePrimary(pair.1) ?? completeSecondary(pair.1)
            guard let left, let right else { continue }
            let delta = sqrt(zip(left, right).reduce(0.0) { $0 + ($1.1 - $1.0) * ($1.1 - $1.0) })
            comparisons += 1
            if delta > 0.03 { largeChanges += 1 }
        }

        let rhythmic = bestSpectrum.rhythmConcentration >= 0.20
            && bestSpectrum.bandPowerFraction >= 0.35
        return WindowResult(bandPowerFraction: bestSpectrum.bandPowerFraction,
                            rhythmConcentration: bestSpectrum.rhythmConcentration,
                            dominantCyclesPerMinute: bestSpectrum.dominantCyclesPerMinute,
                            isRhythmic: rhythmic, largeChanges: largeChanges,
                            vectorComparisons: comparisons)
    }

    private static func spectrum(_ signal: [Double]) -> SpectrumResult? {
        guard signal.count == windowSeconds else { return nil }
        let detrended = signal.indices.map { i -> Double in
            let lo = max(0, i - 15), hi = min(signal.count - 1, i + 15)
            return signal[i] - signal[lo...hi].reduce(0, +) / Double(hi - lo + 1)
        }
        let energy = detrended.reduce(0) { $0 + $1 * $1 }
        guard energy > 1e-12 else { return nil }

        let n = detrended.count
        var powers: [Int: Double] = [:]
        for k in 1..<(n / 2) {
            let frequency = Double(k) / Double(n)
            guard frequency >= 0.02 && frequency <= 0.48 else { continue }
            var real = 0.0, imaginary = 0.0
            for (index, value) in detrended.enumerated() {
                let angle = 2 * Double.pi * Double(k * index) / Double(n)
                real += value * cos(angle)
                imaginary -= value * sin(angle)
            }
            powers[k] = real * real + imaginary * imaginary
        }
        let totalPower = powers.values.reduce(0, +)
        let band = powers.filter {
            let frequency = Double($0.key) / Double(n)
            return frequency >= 0.10 && frequency <= 0.40
        }
        let bandPower = band.values.reduce(0, +)
        guard totalPower > 0, bandPower > 0, let peak = band.max(by: { $0.value < $1.value }) else {
            return nil
        }
        let neighborhood = powers.filter { abs($0.key - peak.key) <= 3 }.values.reduce(0, +)
        let bandFraction = bandPower / totalPower
        let concentration = neighborhood / bandPower
        return SpectrumResult(bandPowerFraction: bandFraction,
                              rhythmConcentration: concentration,
                              dominantCyclesPerMinute: Double(peak.key) / Double(n) * 60,
                              score: bandFraction * concentration)
    }

    private static func completePrimary(_ row: Whoop4ResearchSample) -> [Double]? {
        guard let x = row.motion1X, let y = row.motion1Y, let z = row.motion1Z else { return nil }
        return [x, y, z]
    }

    private static func completeSecondary(_ row: Whoop4ResearchSample) -> [Double]? {
        guard let x = row.motion2X, let y = row.motion2Y, let z = row.motion2Z else { return nil }
        return [x, y, z]
    }

    private static func interpolate(_ values: [Double?], maximumGap: Int) -> [Double]? {
        var out = values
        var i = 0
        while i < out.count {
            if out[i] != nil { i += 1; continue }
            let start = i
            while i < out.count && out[i] == nil { i += 1 }
            let gap = i - start
            guard gap <= maximumGap, start > 0, i < out.count,
                  let left = out[start - 1], let right = out[i] else { return nil }
            for j in 0..<gap {
                out[start + j] = left + (right - left) * Double(j + 1) / Double(gap + 1)
            }
        }
        return out.compactMap { $0 }
    }

    private static func medianOptional(_ values: [Double]) -> Double? {
        guard !values.isEmpty else { return nil }
        let sorted = values.sorted(), middle = sorted.count / 2
        return sorted.count.isMultiple(of: 2)
            ? (sorted[middle - 1] + sorted[middle]) / 2
            : sorted[middle]
    }

    private static func weightedFraction(_ counts: [(Int, Int)]) -> Double? {
        let numerator = counts.reduce(0) { $0 + $1.0 }
        let denominator = counts.reduce(0) { $0 + $1.1 }
        return denominator == 0 ? nil : Double(numerator) / Double(denominator)
    }
}
