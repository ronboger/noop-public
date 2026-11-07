package com.noop.analytics

import com.noop.data.HrSample
import com.noop.data.RrInterval
import com.noop.data.Whoop4ResearchSampleEntity
import com.noop.ingest.ValidationBundleV6
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure, deterministic development core for the prospective WHOOP4 cross-device v7 phase.
 *
 * This layer consumes an already-produced v6 export and continuous cardiac observations. It
 * never opens portal/EDF data and never invokes the v6 detector. The v6 unweighted WSRB stream is
 * copied into a separate five-minute product; an optional score-weighted product is calculated
 * under a different name and can never replace it.
 */
object Whoop4CrossDeviceCorrespondenceV7 {
    const val ALGORITHM_VERSION = "whoop4-cross-device-correspondence-v7.0"
    const val INPUT_ALGORITHM_VERSION = ValidationBundleV6.ALGORITHM_VERSION
    const val INPUT_BUNDLE_VERSION = ValidationBundleV6.BUNDLE_VERSION
    const val BUNDLE_VERSION = 7
    const val BASELINE_START_SECONDS = -60
    const val BASELINE_END_SECONDS = -20
    const val POST_RECOVERY_SECONDS = 30
    const val FIVE_MINUTE_BIN_SECONDS = 300
    const val MIN_BIN_COVERAGE = 0.75
    const val WSRB_UNIT = ValidationBundleV6.WSRB_UNIT

    private const val MIN_WINDOW_COVERAGE = 0.50
    private const val HR_SCALE_FRACTION = 0.25
    private const val RR_SCALE_FRACTION = 0.20
    private const val MOTION_CONFOUNDER_THRESHOLD = 0.03

    enum class ProvenanceGroup {
        MOTION,
        OPTICAL_INTENSITY,
        CARDIAC_TIMING,
        VENDOR_DSP_UNKNOWN,
    }

    enum class RrAvailability {
        PRESENT,
        MISSING,
        INSUFFICIENT_WINDOW,
    }

    data class PhaseIdentity(
        val inputAlgorithmVersion: String = INPUT_ALGORITHM_VERSION,
        val inputBundleVersion: Int = INPUT_BUNDLE_VERSION,
        val outputAlgorithmVersion: String = ALGORITHM_VERSION,
        val outputBundleVersion: Int = BUNDLE_VERSION,
    )

    data class Input(
        val phaseIdentity: PhaseIdentity,
        val deviceId: String,
        val v6Export: ValidationBundleV6.Export,
        val heartRate: List<HrSample>,
        val rrIntervals: List<RrInterval>,
        val rawSamples: List<Whoop4ResearchSampleEntity> = emptyList(),
        val sessionStartTs: Long? = null,
        val sessionEndTs: Long? = null,
    )

    data class AutonomicResponse(
        val startTs: Long,
        val endTs: Long,
        val durationSeconds: Int,
        val eventClass: String,
        val amplitudeReduction: Double,
        val sourceSignal: String,
        val baselineHrBpm: Double?,
        val postRecoveryHrPeakBpm: Double?,
        val hrRiseBpm: Double?,
        val hrRiseSignedBpm: Double?,
        val baselineRrMs: Double?,
        val postRecoveryRrMs: Double?,
        val rrShorteningMs: Double?,
        val rrShorteningSignedMs: Double?,
        val baselineHrCoverage: Double,
        val postRecoveryHrCoverage: Double,
        val baselineRrCoverage: Double,
        val postRecoveryRrCoverage: Double,
        val rrAvailability: RrAvailability,
        val motionConfounderFraction: Double?,
        val qualityCoverage: Double?,
        val confounderPenalty: Double,
        val autonomicResponseScore: Double?,
        val provenanceGroups: Set<ProvenanceGroup>,
    ) {
        val isProlonged: Boolean get() = durationSeconds > 180
    }

    data class FiveMinuteBin(
        val startTs: Long,
        val endTs: Long,
        val wsrbUnweightedNumeratorPctMinutes: Double,
        val wsrbUnweightedDenominatorSeconds: Int,
        val crossDeviceWeightedBurdenNumeratorPctMinutes: Double,
        val crossDeviceWeightedBurdenDenominatorSeconds: Int,
        val hrObservedSeconds: Int,
        val rrObservedSeconds: Int,
        val wsrbUnweightedEligible: Boolean,
        val crossDeviceWeightedBurdenEligible: Boolean,
    ) {
        val wsrbUnweightedPctMinutesPerHour: Double?
            get() = wsrbUnweightedNumeratorPctMinutes
                .takeIf { wsrbUnweightedDenominatorSeconds > 0 }
                ?.let { it / (wsrbUnweightedDenominatorSeconds / 3600.0) }

        val crossDeviceWeightedBurdenPctMinutesPerHour: Double?
            get() = crossDeviceWeightedBurdenNumeratorPctMinutes
                .takeIf { crossDeviceWeightedBurdenDenominatorSeconds > 0 }
                ?.let { it / (crossDeviceWeightedBurdenDenominatorSeconds / 3600.0) }
    }

    data class Export(
        val phaseIdentity: PhaseIdentity,
        val autonomicResponses: List<AutonomicResponse>,
        val fiveMinuteBins: List<FiveMinuteBin>,
        val wsrbUnweightedNumeratorPctMinutes: Double,
        val wsrbUnweightedDenominatorSeconds: Int,
        val crossDeviceWeightedBurdenNumeratorPctMinutes: Double,
        val crossDeviceWeightedBurdenDenominatorSeconds: Int,
        val provenanceGroups: Map<String, ProvenanceGroup>,
    )

    private data class SeriesPoint(val ts: Long, val value: Double)

    private data class WindowStats(
        val median: Double?,
        val upper: Double?,
        val coverage: Double,
    )

    /**
     * Analyze v6 proposals without changing their boundaries. In particular, a prolonged v6
     * proposal remains one prolonged record; this method never creates partial-recovery splits.
     */
    fun analyze(input: Input): Export {
        require(input.deviceId.isNotBlank()) { "v7 requires an explicit device identity" }
        require(input.heartRate.all { it.deviceId == input.deviceId }) { "heart-rate rows cross device identity" }
        require(input.rrIntervals.all { it.deviceId == input.deviceId }) { "R-R rows cross device identity" }
        require(input.rawSamples.all { it.deviceId == input.deviceId }) { "raw rows cross device identity" }
        require(input.phaseIdentity.inputAlgorithmVersion == INPUT_ALGORITHM_VERSION) {
            "v7 requires the v6 input algorithm identity"
        }
        require(input.phaseIdentity.inputBundleVersion == INPUT_BUNDLE_VERSION) {
            "v7 requires validation bundle v6 as its input"
        }
        require(input.phaseIdentity.outputAlgorithmVersion == ALGORITHM_VERSION) {
            "unexpected v7 output algorithm identity"
        }
        require(input.phaseIdentity.outputBundleVersion == BUNDLE_VERSION) {
            "unexpected v7 output bundle version"
        }

        val v6Samples = input.v6Export.samples.sortedBy { it.ts }
        val v6Events = input.v6Export.events.sortedWith(compareBy({ it.startTs }, { it.endTs }))
        val unweightedFromSamples = v6Samples.sumOf { it.wsrbContributionPctMinutes }
        require(abs(unweightedFromSamples - input.v6Export.wsrbNumeratorPctMinutes) <= 1e-6) {
            "v6 WSRB numerator does not match its exported sample stream"
        }

        val hr = collapseBySecond(input.heartRate.map { SeriesPoint(it.ts, it.bpm.toDouble()) })
        val rr = collapseBySecond(input.rrIntervals.map { SeriesPoint(it.ts, it.rrMs.toDouble()) })
        val raw = input.rawSamples.sortedBy { it.ts }
        val responses = v6Events.map { event ->
            responseFor(event, hr, rr, raw)
        }
        val bins = makeBins(
            samples = v6Samples,
            heartRate = hr,
            rrIntervals = rr,
            eventScores = responses,
            sessionStartTs = input.sessionStartTs,
            sessionEndTs = input.sessionEndTs,
        )
        val weightedNumerator = bins.sumOf { it.crossDeviceWeightedBurdenNumeratorPctMinutes }
        val weightedDenominator = bins.sumOf { it.crossDeviceWeightedBurdenDenominatorSeconds }

        return Export(
            phaseIdentity = input.phaseIdentity,
            autonomicResponses = responses,
            fiveMinuteBins = bins,
            wsrbUnweightedNumeratorPctMinutes = unweightedFromSamples,
            wsrbUnweightedDenominatorSeconds = v6Samples.count { it.sourceValid },
            crossDeviceWeightedBurdenNumeratorPctMinutes = weightedNumerator,
            crossDeviceWeightedBurdenDenominatorSeconds = weightedDenominator,
            provenanceGroups = mapOf(
                "hr_response" to ProvenanceGroup.CARDIAC_TIMING,
                "rr_response" to ProvenanceGroup.CARDIAC_TIMING,
                "motion_confounder" to ProvenanceGroup.MOTION,
                "quality_confounder" to ProvenanceGroup.OPTICAL_INTENSITY,
                "wsrb_unweighted" to ProvenanceGroup.VENDOR_DSP_UNKNOWN,
                "cross_device_weighted_burden" to ProvenanceGroup.CARDIAC_TIMING,
            ),
        )
    }

    private fun responseFor(
        event: ValidationBundleV6.Event,
        hr: List<SeriesPoint>,
        rr: List<SeriesPoint>,
        raw: List<Whoop4ResearchSampleEntity>,
    ): AutonomicResponse {
        val baselineStart = event.startTs + BASELINE_START_SECONDS
        val baselineEnd = event.startTs + BASELINE_END_SECONDS
        val recoveryEnd = event.endTs + POST_RECOVERY_SECONDS
        val baselineHr = stats(hr, baselineStart, baselineEnd, 40)
        val postHr = stats(hr, event.endTs, recoveryEnd, POST_RECOVERY_SECONDS)
        val baselineRr = stats(rr, baselineStart, baselineEnd, 40)
        val postRr = stats(rr, event.endTs, recoveryEnd, POST_RECOVERY_SECONDS)
        val rrAvailability = when {
            rr.isEmpty() -> RrAvailability.MISSING
            baselineRr.median == null || postRr.median == null -> RrAvailability.INSUFFICIENT_WINDOW
            else -> RrAvailability.PRESENT
        }
        // hrRise uses p95(post) − p95(baseline): null-centered under stationary noise.
        val hrRiseSigned = if (baselineHr.upper != null && postHr.upper != null &&
            baselineHr.coverage >= MIN_WINDOW_COVERAGE && postHr.coverage >= MIN_WINDOW_COVERAGE
        ) {
            postHr.upper - baselineHr.upper
        } else null
        val hrRise = hrRiseSigned?.let { max(0.0, it) }
        val rrShorteningSigned = if (baselineRr.median != null && postRr.median != null &&
            baselineRr.coverage >= MIN_WINDOW_COVERAGE && postRr.coverage >= MIN_WINDOW_COVERAGE
        ) {
            baselineRr.median - postRr.median
        } else null
        val rrShortening = rrShorteningSigned?.let { max(0.0, it) }
        val quality = qualityStats(raw, baselineStart, recoveryEnd)
        val motionFraction = quality.first
        val qualityCoverage = quality.second
        val confounderPenalty = confounderPenalty(motionFraction, qualityCoverage)
        val score = responseScore(
            baselineHr = baselineHr.median,
            hrRise = hrRise,
            baselineRr = baselineRr.median,
            rrShortening = rrShortening,
            hrCoverage = min(baselineHr.coverage, postHr.coverage),
            rrCoverage = min(baselineRr.coverage, postRr.coverage),
            rrAvailable = rrAvailability == RrAvailability.PRESENT,
            confounderPenalty = confounderPenalty,
        )
        val provenance = linkedSetOf(ProvenanceGroup.CARDIAC_TIMING)
        if (motionFraction != null) provenance += ProvenanceGroup.MOTION
        if (qualityCoverage != null) provenance += ProvenanceGroup.OPTICAL_INTENSITY
        if (event.sourceSignal.isNotBlank()) provenance += ProvenanceGroup.VENDOR_DSP_UNKNOWN
        return AutonomicResponse(
            startTs = event.startTs,
            endTs = event.endTs,
            durationSeconds = event.durationSeconds,
            eventClass = event.eventClass,
            amplitudeReduction = event.amplitudeReduction,
            sourceSignal = event.sourceSignal,
            baselineHrBpm = baselineHr.median,
            postRecoveryHrPeakBpm = postHr.upper,
            hrRiseBpm = hrRise,
            hrRiseSignedBpm = hrRiseSigned,
            baselineRrMs = baselineRr.median,
            postRecoveryRrMs = postRr.median,
            rrShorteningMs = rrShortening,
            rrShorteningSignedMs = rrShorteningSigned,
            baselineHrCoverage = baselineHr.coverage,
            postRecoveryHrCoverage = postHr.coverage,
            baselineRrCoverage = baselineRr.coverage,
            postRecoveryRrCoverage = postRr.coverage,
            rrAvailability = rrAvailability,
            motionConfounderFraction = motionFraction,
            qualityCoverage = qualityCoverage,
            confounderPenalty = confounderPenalty,
            autonomicResponseScore = score,
            provenanceGroups = provenance,
        )
    }

    /** One combined response head; absent RR is absent, never a zero-valued feature. */
    internal fun responseScore(
        baselineHr: Double?,
        hrRise: Double?,
        baselineRr: Double?,
        rrShortening: Double?,
        hrCoverage: Double,
        rrCoverage: Double,
        rrAvailable: Boolean,
        confounderPenalty: Double,
    ): Double? {
        val hrComponent = if (baselineHr != null && hrRise != null && baselineHr > 0.0) {
            clamp(hrRise / (baselineHr * HR_SCALE_FRACTION))
        } else null
        val rrComponent = if (rrAvailable && baselineRr != null && rrShortening != null && baselineRr > 0.0) {
            clamp(rrShortening / (baselineRr * RR_SCALE_FRACTION))
        } else null
        val components = listOfNotNull(
            hrComponent?.let { it to hrCoverage },
            rrComponent?.let { it to rrCoverage },
        )
        if (components.isEmpty()) return null
        // Coverage weights components relative to each other; coverage stays exported on the record.
        val weighted = components.sumOf { it.first * it.second } /
            components.sumOf { it.second }.coerceAtLeast(1e-12)
        return clamp(weighted * confounderPenalty)
    }

    private fun makeBins(
        samples: List<ValidationBundleV6.Sample>,
        heartRate: List<SeriesPoint>,
        rrIntervals: List<SeriesPoint>,
        eventScores: List<AutonomicResponse>,
        sessionStartTs: Long?,
        sessionEndTs: Long?,
    ): List<FiveMinuteBin> {
        if (samples.isEmpty()) return emptyList()
        val first = sessionStartTs ?: samples.minOf { it.ts }
        val lastExclusive = sessionEndTs ?: (samples.maxOf { it.ts } + 1L)
        if (lastExclusive <= first) return emptyList()
        val binSeconds = FIVE_MINUTE_BIN_SECONDS.toLong()
        val firstBin = Math.floorDiv(first + binSeconds - 1L, binSeconds) * binSeconds
        val lastBin = Math.floorDiv(lastExclusive - binSeconds, binSeconds) * binSeconds
        if (lastBin < firstBin) return emptyList()
        return (firstBin..lastBin step binSeconds).map { binStart ->
            val binEnd = binStart + binSeconds
            val inBin = samples.filter { it.ts >= binStart && it.ts < binEnd && it.ts >= first && it.ts < lastExclusive }
            val sourceValid = inBin.filter { it.sourceValid }
            val unweighted = sourceValid.sumOf { it.wsrbContributionPctMinutes }
            val eventFor = { ts: Long ->
                eventScores.firstOrNull { ts >= it.startTs && ts < it.endTs }
            }
            // Samples inside a null-score event are missing evidence: exclude from both weighted
            // products. Samples outside any event keep weight 0.0 and stay in the denominator.
            var weightedNumerator = 0.0
            var weightedDenominator = 0
            for (sample in sourceValid) {
                val event = eventFor(sample.ts)
                if (event != null && event.autonomicResponseScore == null) continue
                val score = event?.autonomicResponseScore ?: 0.0
                weightedNumerator += sample.wsrbContributionPctMinutes * score
                weightedDenominator += 1
            }
            val hrSeconds = heartRate.count { it.ts in binStart until binEnd && it.ts >= first && it.ts < lastExclusive }
            val rrSeconds = rrIntervals.count { it.ts in binStart until binEnd && it.ts >= first && it.ts < lastExclusive }
            val sourceEligible = sourceValid.size >= FIVE_MINUTE_BIN_SECONDS * MIN_BIN_COVERAGE
            val cardiacEligible = hrSeconds >= FIVE_MINUTE_BIN_SECONDS * MIN_BIN_COVERAGE
            FiveMinuteBin(
                startTs = binStart,
                endTs = binEnd,
                wsrbUnweightedNumeratorPctMinutes = unweighted,
                wsrbUnweightedDenominatorSeconds = sourceValid.size,
                crossDeviceWeightedBurdenNumeratorPctMinutes = weightedNumerator,
                crossDeviceWeightedBurdenDenominatorSeconds = weightedDenominator,
                hrObservedSeconds = hrSeconds,
                rrObservedSeconds = rrSeconds,
                wsrbUnweightedEligible = sourceEligible,
                crossDeviceWeightedBurdenEligible = sourceEligible && cardiacEligible,
            )
        }
    }

    private fun stats(
        series: List<SeriesPoint>,
        startTs: Long,
        endTs: Long,
        expectedSeconds: Int,
    ): WindowStats {
        val values = series.asSequence()
            .filter { it.ts >= startTs && it.ts < endTs }
            .map { it.value }
            .toList()
        return WindowStats(
            median = values.medianOrNull(),
            upper = values.percentileOrNull(0.95),
            coverage = (values.size.toDouble() / expectedSeconds.coerceAtLeast(1)).coerceIn(0.0, 1.0),
        )
    }

    private fun qualityStats(
        raw: List<Whoop4ResearchSampleEntity>,
        startTs: Long,
        endTs: Long,
    ): Pair<Double?, Double?> {
        val rows = raw.filter { it.ts >= startTs && it.ts < endTs }
        if (rows.isEmpty()) return null to null
        val motionRows = rows.count { row ->
            val axes = listOf(row.motion1X, row.motion1Y, row.motion1Z, row.motion2X, row.motion2Y, row.motion2Z)
                .filterNotNull()
            axes.isNotEmpty() && axes.sumOf { abs(it) } > MOTION_CONFOUNDER_THRESHOLD
        }
        val qualityRows = rows.count { row ->
            (row.signalQuality == null || row.signalQuality > 0) &&
                (row.skinContact == null || row.skinContact > 0)
        }
        val hasMotion = rows.any { it.motion1X != null || it.motion2X != null }
        val hasQuality = rows.any { it.signalQuality != null || it.skinContact != null }
        return (if (hasMotion) motionRows.toDouble() / rows.size else null) to
            (if (hasQuality) qualityRows.toDouble() / rows.size else null)
    }

    private fun confounderPenalty(motionFraction: Double?, qualityCoverage: Double?): Double {
        val motionPenalty = 1.0 - 0.5 * (motionFraction ?: 0.0).coerceIn(0.0, 1.0)
        val qualityPenalty = if (qualityCoverage == null) 1.0
        else 0.75 + 0.25 * qualityCoverage.coerceIn(0.0, 1.0)
        return (motionPenalty * qualityPenalty).coerceIn(0.0, 1.0)
    }

    private fun collapseBySecond(points: List<SeriesPoint>): List<SeriesPoint> = points
        .groupBy { it.ts }
        .entries
        .sortedBy { it.key }
        .map { (ts, values) -> SeriesPoint(ts, values.map { it.value }.average()) }

    private fun List<Double>.medianOrNull(): Double? = percentileOrNull(0.5)

    /**
     * Linear-interpolation percentile; parity with numpy method='linear'
     * (`pos = (n-1)*fraction`, interpolate between floor/ceil ranks).
     */
    private fun List<Double>.percentileOrNull(fraction: Double): Double? {
        if (isEmpty()) return null
        val ordered = sorted()
        if (ordered.size == 1) return ordered[0]
        val pos = (ordered.size - 1) * fraction
        val lowerIndex = kotlin.math.floor(pos).toInt().coerceIn(0, ordered.lastIndex)
        val upperIndex = kotlin.math.ceil(pos).toInt().coerceIn(0, ordered.lastIndex)
        if (lowerIndex == upperIndex) return ordered[lowerIndex]
        val weight = pos - lowerIndex
        return ordered[lowerIndex] + weight * (ordered[upperIndex] - ordered[lowerIndex])
    }

    private fun clamp(value: Double): Double = value.coerceIn(0.0, 1.0)

    /** Stable number formatting shared by the v7 serializers. */
    internal fun number(value: Double): String = String.format(Locale.US, "%.6f", value)
}
