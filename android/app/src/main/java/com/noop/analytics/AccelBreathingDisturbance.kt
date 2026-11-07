package com.noop.analytics

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * accel-bd-v0: accelerometer-only nightly "breathing disturbance" index (research, default-off).
 *
 * Mirrors `sleepimage/analysis/product-b-dev-2026-09-02/accel_bd_freeze.py` (spec
 * `accel_bd_v0_spec.json`). Input is the 1 Hz WHOOP 4 motion vector (motion candidate 1, g) and the
 * NOOP asleep mask. Per 30-s epoch it computes eight jerk features, scores them with a frozen
 * L2-logistic head trained on six SleepImage-paired nights (target: epoch overlaps a portal
 * respiratory mark), and reports positive sleep epochs per asleep hour plus a linear night
 * calibration to the comparator's sAHI3 scale. One person, seven nights: a research measurement,
 * not AHI, RDI, or a diagnosis. Byte-level parity with the Python reference is pinned by
 * [AccelBreathingDisturbanceTest] on a golden fixture.
 */
object AccelBreathingDisturbance {
    const val ALGORITHM_VERSION = "accel-bd-v0"
    const val EPOCH_SECONDS = 30
    const val SLEEP_EPOCH_MIN_FRACTION = 0.5
    const val STILL_THRESHOLD = 0.005
    const val MIN_FINITE_JERK_PER_EPOCH = 15
    private const val LOG_EPS = 1e-4
    private val JERK_COUNT_THRESHOLDS = doubleArrayOf(0.01, 0.02, 0.05, 0.10)

    val FEATURE_NAMES = listOf("log_jerk_mean", "log_jerk_max", "jerk_p90", "n_j01", "n_j02", "n_j05", "n_j10", "still_fraction")
    private val STANDARDIZE_MEAN = doubleArrayOf(-6.394219846039385, -5.58407267790073, 0.009622265972930838, 0.5248858943707889, 0.3079765268419909, 0.21299717452727668, 0.16366007389697892, 0.9391154346756246)
    private val STANDARDIZE_STD = doubleArrayOf(0.9084872825747913, 1.2796549872716108, 0.04508646209924935, 2.002899472113133, 1.398651604935893, 1.0960463981031736, 0.9046913355373366, 0.13941622518990401)
    private val LOGISTIC_WEIGHTS = doubleArrayOf(-0.05841503429507381, 0.6446089424264928, 0.03951268203613749, -0.012657413768825254, 0.068094166491966, -0.11523810267216085, -0.2402399291205239, 0.070723954506491)
    private const val LOGISTIC_BIAS = -2.216500642903652
    const val EPOCH_THRESHOLD = 0.13257006316380543
    const val CALIBRATION_SLOPE = 1.307234270348421
    const val CALIBRATION_INTERCEPT = -5.257098122533967
    const val BURST_JERK_THRESHOLD = 0.02
    const val BURST_QUIET_SECONDS = 20

    /** One 1-Hz motion sample; null axes mean the sample is incomplete. */
    data class MotionSample(val ts: Long, val x: Double?, val y: Double?, val z: Double?)

    data class EpochResult(
        val startOffsetSeconds: Int,
        val asleepFraction: Double,
        val features: DoubleArray?,
        val probability: Double?,
        val positive: Boolean,
    )

    data class NightResult(
        val algorithmVersion: String,
        val asleepHours: Double,
        val sleepEpochs: Int,
        val positiveEpochs: Int,
        val indexPerAsleepHour: Double?,
        val calibratedSahi3Like: Double?,
        val burstsPerAsleepHour: Double?,
        val epochs: List<EpochResult>,
    )

    data class Grid(val startTs: Long, val samples: List<MotionSample>, val asleep: BooleanArray,
                    val duplicateSeconds: Int, val missingSeconds: Int, val nonFiniteSamples: Int)

    private fun MotionSample.complete(): Boolean =
        x != null && y != null && z != null && x.isFinite() && y.isFinite() && z.isFinite()

    /**
     * Build a contiguous 1-Hz grid from arbitrary-order samples and asleep intervals [start, end).
     * Missing seconds get null axes; duplicate seconds keep the first occurrence; non-finite axes
     * are treated as missing. Returns null when there are no complete samples.
     */
    fun fromSamples(samples: List<MotionSample>, asleepIntervals: List<LongRange>): Grid? {
        if (samples.isEmpty()) return null
        val startTs = samples.minOf { it.ts }
        val endTs = samples.maxOf { it.ts }
        val n = (endTs - startTs + 1).toInt()
        val grid = arrayOfNulls<MotionSample>(n)
        var duplicates = 0
        var nonFinite = 0
        for (s in samples.sortedBy { it.ts }) {
            val i = (s.ts - startTs).toInt()
            if (grid[i] != null) { duplicates++; continue }
            val ok = s.complete()
            if (!ok && (s.x != null || s.y != null || s.z != null)) nonFinite++
            grid[i] = if (ok) s else MotionSample(s.ts, null, null, null)
        }
        var missing = 0
        val filled = List(n) { i -> grid[i] ?: MotionSample(startTs + i, null, null, null).also { missing++ } }
        if (filled.none { it.complete() }) return null
        val asleep = BooleanArray(n)
        for (r in asleepIntervals) {
            val lo = maxOf(0L, r.first - startTs).toInt()
            val hi = minOf(n.toLong(), r.last + 1 - startTs).toInt()
            for (t in lo until hi) asleep[t] = true
        }
        return Grid(startTs, filled, asleep, duplicates, missing, nonFinite)
    }

    /** Euclidean norm of the first difference; NaN where either sample is incomplete. Index 0 is NaN. */
    fun perSecondJerk(samples: List<MotionSample>): DoubleArray {
        val jerk = DoubleArray(samples.size) { Double.NaN }
        for (i in 1 until samples.size) {
            val a = samples[i - 1]
            val b = samples[i]
            if (!a.complete() || !b.complete()) continue
            val dx = b.x!! - a.x!!
            val dy = b.y!! - a.y!!
            val dz = b.z!! - a.z!!
            jerk[i] = sqrt(dx * dx + dy * dy + dz * dz)
        }
        return jerk
    }

    /** NumPy "linear" percentile on a sorted array. */
    internal fun percentileLinear(sorted: DoubleArray, percentile: Double): Double {
        if (sorted.size == 1) return sorted[0]
        val pos = (sorted.size - 1) * percentile / 100.0
        val lo = floor(pos).toInt()
        val hi = minOf(lo + 1, sorted.size - 1)
        val frac = pos - lo
        return sorted[lo] + (sorted[hi] - sorted[lo]) * frac
    }

    /** Eight jerk features for one epoch, or null when fewer than [MIN_FINITE_JERK_PER_EPOCH] finite values. */
    fun epochFeatures(jerk: DoubleArray, start: Int, end: Int): DoubleArray? {
        val finite = (start until minOf(end, jerk.size)).map { jerk[it] }.filter { !it.isNaN() }.toDoubleArray()
        if (finite.size < MIN_FINITE_JERK_PER_EPOCH) return null
        val sorted = finite.copyOf().also { it.sort() }
        val mean = finite.sum() / finite.size
        val max = sorted[sorted.size - 1]
        return doubleArrayOf(
            ln(mean + LOG_EPS),
            ln(max + LOG_EPS),
            percentileLinear(sorted, 90.0),
            finite.count { it >= JERK_COUNT_THRESHOLDS[0] }.toDouble(),
            finite.count { it >= JERK_COUNT_THRESHOLDS[1] }.toDouble(),
            finite.count { it >= JERK_COUNT_THRESHOLDS[2] }.toDouble(),
            finite.count { it >= JERK_COUNT_THRESHOLDS[3] }.toDouble(),
            finite.count { it < STILL_THRESHOLD }.toDouble() / finite.size,
        )
    }

    fun probability(features: DoubleArray): Double {
        var z = LOGISTIC_BIAS
        for (i in features.indices) {
            z += LOGISTIC_WEIGHTS[i] * ((features[i] - STANDARDIZE_MEAN[i]) / STANDARDIZE_STD[i])
        }
        return 1.0 / (1.0 + exp(-z))
    }

    /**
     * @param samples contiguous 1-Hz samples from the session start (missing seconds may be given with null axes)
     * @param asleep one flag per second, aligned with [samples]
     */
    fun analyze(grid: Grid): NightResult = analyze(grid.samples, grid.asleep)

    fun analyze(samples: List<MotionSample>, asleep: BooleanArray): NightResult {
        require(asleep.size == samples.size) { "asleep mask must align with samples" }
        for (i in 1 until samples.size) require(samples[i].ts == samples[i - 1].ts + 1) {
            "samples must be a contiguous 1-Hz grid; use fromSamples() to build one"
        }
        val jerk = perSecondJerk(samples)
        val epochs = ArrayList<EpochResult>()
        var sleepEpochs = 0
        var positives = 0
        var start = 0
        while (start + EPOCH_SECONDS <= samples.size) {
            val end = start + EPOCH_SECONDS
            var asleepCount = 0
            for (t in start until end) if (asleep[t]) asleepCount++
            val asleepFraction = asleepCount.toDouble() / EPOCH_SECONDS
            val features = epochFeatures(jerk, start, end)
            val p = features?.let { probability(it) }
            val isSleep = asleepFraction >= SLEEP_EPOCH_MIN_FRACTION && features != null
            val positive = isSleep && p != null && p >= EPOCH_THRESHOLD
            if (isSleep) sleepEpochs++
            if (positive) positives++
            epochs.add(EpochResult(start, asleepFraction, features, p, positive))
            start = end
        }
        val asleepSeconds = asleep.count { it }
        val asleepHours = asleepSeconds / 3600.0
        var bursts = 0
        var quiet = 0
        for (t in jerk.indices) {
            val v = jerk[t]
            if (v.isNaN()) continue
            if (v < STILL_THRESHOLD) {
                quiet++
            } else {
                if (quiet >= BURST_QUIET_SECONDS && v >= BURST_JERK_THRESHOLD && asleep[t]) bursts++
                quiet = 0
            }
        }
        val index = if (asleepHours > 0) positives / asleepHours else null
        return NightResult(
            algorithmVersion = ALGORITHM_VERSION,
            asleepHours = asleepHours,
            sleepEpochs = sleepEpochs,
            positiveEpochs = positives,
            indexPerAsleepHour = index,
            calibratedSahi3Like = index?.let { CALIBRATION_SLOPE * it + CALIBRATION_INTERCEPT },
            burstsPerAsleepHour = if (asleepHours > 0) bursts / asleepHours else null,
            epochs = epochs,
        )
    }
}
