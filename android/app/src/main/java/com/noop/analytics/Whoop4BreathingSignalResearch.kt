package com.noop.analytics

import com.noop.data.Whoop4ResearchSampleEntity
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Capability-first analysis for the private WHOOP 4 breathing-signal experiment.
 *
 * This asks only whether the reverse-engineered, one-row-per-second historical vectors contain a
 * stable rhythm in a plausible respiratory frequency band. It does not detect respiratory events,
 * estimate AHI/RDI, or infer sleep apnea. The historical fields are DSP outputs with unconfirmed
 * semantics, so every result remains a signal-quality observation.
 */
object Whoop4BreathingSignalResearch {
    enum class Status {
        UNAVAILABLE,
        INSUFFICIENT_DATA,
        INCONCLUSIVE,
        BREATHING_LIKE_MOTION_OBSERVED,
    }

    data class CapabilityReport(
        val layoutVersions: List<Int>,
        val sampleCount: Int,
        val spanHours: Double,
        val timeCoverage: Double,
        val primaryVectorCoverage: Double,
        val secondaryVectorCoverage: Double,
        val contactCoverage: Double,
        val signalQualityCoverage: Double,
        /** Null when no rows contain both complete candidate vectors. */
        val duplicateVectorFraction: Double?,
        val hasOpticalContext: Boolean,
    )

    data class Result(
        val status: Status,
        val capability: CapabilityReport,
        val evaluatedWindows: Int,
        val usableWindows: Int,
        val rhythmicWindowFraction: Double,
        val medianBandPowerFraction: Double?,
        val medianRhythmConcentration: Double?,
        /** Dominant motion cycles/minute, deliberately not labelled respiratory rate. */
        val medianDominantCyclesPerMinute: Double?,
        val largeVectorChangeFraction: Double?,
        val reason: String,
    )

    private const val WINDOW_SECONDS = 300
    private const val MINIMUM_USABLE_WINDOWS = 6

    fun analyze(input: List<Whoop4ResearchSampleEntity>): Result {
        val rows = input.sortedBy { it.ts }
        val capability = capabilityReport(rows)
        if (rows.size < 2) return Result(
            Status.UNAVAILABLE, capability, 0, 0, 0.0,
            null, null, null, null,
            "No WHOOP 4 historical research rows",
        )

        var evaluatedWindows = 0
        val windows = ArrayList<WindowResult>()
        var start = 0
        while (start < rows.size) {
            val bucketStart = rows[start].ts / WINDOW_SECONDS * WINDOW_SECONDS
            var end = start
            while (end < rows.size && rows[end].ts < bucketStart + WINDOW_SECONDS) end++
            evaluatedWindows++
            evaluateWindow(rows.subList(start, end), bucketStart)?.let(windows::add)
            start = end
        }

        val rhythmicFraction = if (windows.isEmpty()) 0.0 else
            windows.count { it.isRhythmic }.toDouble() / windows.size
        val bandPower = medianOrNull(windows.map { it.bandPowerFraction })
        val concentration = medianOrNull(windows.map { it.rhythmConcentration })
        val cycles = medianOrNull(windows.filter { it.isRhythmic }.map { it.dominantCyclesPerMinute })
        val comparisons = windows.sumOf { it.vectorComparisons }
        val largeChange = if (comparisons == 0) null else
            windows.sumOf { it.largeChanges }.toDouble() / comparisons

        if (capability.timeCoverage < 0.70) return Result(
            Status.INSUFFICIENT_DATA, capability, evaluatedWindows, windows.size, rhythmicFraction,
            bandPower, concentration, cycles, largeChange,
            "Less than 70% time coverage in the selected sleep window",
        )
        if (windows.size < MINIMUM_USABLE_WINDOWS) return Result(
            Status.INSUFFICIENT_DATA, capability, evaluatedWindows, windows.size, rhythmicFraction,
            bandPower, concentration, cycles, largeChange,
            "Fewer than 30 minutes passed strict on-wrist and continuity checks",
        )

        val observed = rhythmicFraction >= 0.50 && (concentration ?: 0.0) >= 0.20
        return Result(
            if (observed) Status.BREATHING_LIKE_MOTION_OBSERVED else Status.INCONCLUSIVE,
            capability, evaluatedWindows, windows.size, rhythmicFraction,
            bandPower, concentration, cycles, largeChange,
            if (observed)
                "A repeatable rhythm exists in a plausible breathing-frequency band; its physiological source is unconfirmed"
            else
                "The stored vectors do not yet show a sufficiently repeatable breathing-like rhythm",
        )
    }

    fun capabilityReport(rows: List<Whoop4ResearchSampleEntity>): CapabilityReport {
        val sorted = rows.sortedBy { it.ts }
        val span = if (sorted.size < 2) 0L else max(1L, sorted.last().ts - sorted.first().ts + 1)
        val primary = sorted.count { completePrimary(it) != null }
        val secondary = sorted.count { completeSecondary(it) != null }
        val pairs = sorted.mapNotNull { row ->
            val first = completePrimary(row) ?: return@mapNotNull null
            val second = completeSecondary(row) ?: return@mapNotNull null
            first.indices.sumOf { (first[it] - second[it]) * (first[it] - second[it]) } <= 1e-12
        }
        return CapabilityReport(
            sorted.map { it.layoutVersion }.distinct().sorted(),
            sorted.size,
            span / 3600.0,
            if (span == 0L) 0.0 else min(1.0, sorted.map { it.ts }.distinct().size / span.toDouble()),
            if (sorted.isEmpty()) 0.0 else primary.toDouble() / sorted.size,
            if (sorted.isEmpty()) 0.0 else secondary.toDouble() / sorted.size,
            if (sorted.isEmpty()) 0.0 else sorted.count { it.skinContact != null }.toDouble() / sorted.size,
            if (sorted.isEmpty()) 0.0 else sorted.count { it.signalQuality != null }.toDouble() / sorted.size,
            if (pairs.isEmpty()) null else pairs.count { it }.toDouble() / pairs.size,
            sorted.any { it.ppgGreen != null || it.ppgRedIR != null || it.spo2Red != null || it.spo2IR != null },
        )
    }

    private data class WindowResult(
        val bandPowerFraction: Double,
        val rhythmConcentration: Double,
        val dominantCyclesPerMinute: Double,
        val isRhythmic: Boolean,
        val largeChanges: Int,
        val vectorComparisons: Int,
    )

    private data class SpectrumResult(
        val bandPowerFraction: Double,
        val rhythmConcentration: Double,
        val dominantCyclesPerMinute: Double,
        val score: Double,
    )

    private fun evaluateWindow(
        rows: List<Whoop4ResearchSampleEntity>,
        expectedStart: Long,
    ): WindowResult? {
        // Unknown contact is not proof of on-wrist wear. Require an explicit nonzero value.
        val bySecond = rows.filter { (it.skinContact ?: 0) > 0 }.associateBy { it.ts }
        if (bySecond.size < 240) return null

        val getters: List<(Whoop4ResearchSampleEntity) -> Double?> = listOf(
            { it.motion1X }, { it.motion1Y }, { it.motion1Z },
            { it.motion2X }, { it.motion2Y }, { it.motion2Z },
        )
        var best: SpectrumResult? = null
        for (get in getters) {
            val candidate = (0 until WINDOW_SECONDS).map { bySecond[expectedStart + it]?.let(get) }
            val signal = interpolate(candidate, 2) ?: continue
            val result = spectrum(signal) ?: continue
            if (result.score > (best?.score ?: -1.0)) best = result
        }
        val spectrum = best ?: return null

        var largeChanges = 0
        var comparisons = 0
        val ordered = bySecond.values.sortedBy { it.ts }
        for ((leftRow, rightRow) in ordered.zipWithNext()) {
            if (rightRow.ts - leftRow.ts != 1L) continue
            val left = completePrimary(leftRow) ?: completeSecondary(leftRow) ?: continue
            val right = completePrimary(rightRow) ?: completeSecondary(rightRow) ?: continue
            val delta = sqrt(left.indices.sumOf { (right[it] - left[it]) * (right[it] - left[it]) })
            comparisons++
            if (delta > 0.03) largeChanges++
        }
        val rhythmic = spectrum.rhythmConcentration >= 0.20 && spectrum.bandPowerFraction >= 0.35
        return WindowResult(
            spectrum.bandPowerFraction, spectrum.rhythmConcentration,
            spectrum.dominantCyclesPerMinute, rhythmic, largeChanges, comparisons,
        )
    }

    private fun spectrum(signal: List<Double>): SpectrumResult? {
        if (signal.size != WINDOW_SECONDS) return null
        val detrended = signal.indices.map { i ->
            val lo = max(0, i - 15)
            val hi = min(signal.lastIndex, i + 15)
            signal[i] - signal.subList(lo, hi + 1).average()
        }
        if (detrended.sumOf { it * it } <= 1e-12) return null

        val n = detrended.size
        val powers = HashMap<Int, Double>()
        for (k in 1 until n / 2) {
            val frequency = k.toDouble() / n
            if (frequency !in 0.02..0.48) continue
            var real = 0.0
            var imaginary = 0.0
            for ((index, value) in detrended.withIndex()) {
                val angle = 2 * PI * k * index / n
                real += value * cos(angle)
                imaginary -= value * sin(angle)
            }
            powers[k] = real * real + imaginary * imaginary
        }
        val totalPower = powers.values.sum()
        val band = powers.filterKeys { it.toDouble() / n in 0.10..0.40 }
        val bandPower = band.values.sum()
        val peak = band.maxByOrNull { it.value } ?: return null
        if (totalPower <= 0.0 || bandPower <= 0.0) return null
        val neighborhood = powers.filterKeys { abs(it - peak.key) <= 3 }.values.sum()
        val bandFraction = bandPower / totalPower
        val concentration = neighborhood / bandPower
        return SpectrumResult(
            bandFraction, concentration, peak.key.toDouble() / n * 60,
            bandFraction * concentration,
        )
    }

    private fun completePrimary(row: Whoop4ResearchSampleEntity): List<Double>? {
        val x = row.motion1X ?: return null
        val y = row.motion1Y ?: return null
        val z = row.motion1Z ?: return null
        return listOf(x, y, z)
    }

    private fun completeSecondary(row: Whoop4ResearchSampleEntity): List<Double>? {
        val x = row.motion2X ?: return null
        val y = row.motion2Y ?: return null
        val z = row.motion2Z ?: return null
        return listOf(x, y, z)
    }

    private fun interpolate(values: List<Double?>, maximumGap: Int): List<Double>? {
        val out = values.toMutableList()
        var i = 0
        while (i < out.size) {
            if (out[i] != null) { i++; continue }
            val start = i
            while (i < out.size && out[i] == null) i++
            val gap = i - start
            if (gap > maximumGap || start == 0 || i >= out.size) return null
            val left = out[start - 1] ?: return null
            val right = out[i] ?: return null
            for (j in 0 until gap) out[start + j] = left + (right - left) * (j + 1.0) / (gap + 1.0)
        }
        return out.filterNotNull()
    }

    private fun medianOrNull(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2 else sorted[middle]
    }
}
