package com.noop.ingest

import com.noop.analytics.Whoop4DisturbanceResearch
import kotlin.math.abs
import java.util.Locale
import kotlin.math.max

/**
 * The export-only contract for the v6 respiratory-burden bundle.
 *
 * The detector/model owner may evolve Result while this branch is developed. Keeping this mapper
 * here makes that hand-off one narrow seam: the mapper consumes the stable diagnostic fields and
 * refuses to label a v5 result as v6. WSRB is calculated from the returned frozen envelope and
 * onset baseline; the exporter never re-runs detection or changes event decisions.
 */
object ValidationBundleV6 {
    const val ALGORITHM_VERSION = "whoop4-respiratory-burden-v6"
    const val BUNDLE_VERSION = 6
    /** Historical freeze point for the v6 contract. Not an execution gate — exports stamp the running app. */
    const val APP_VERSION_NAME = "8.2.13"
    /** Historical freeze point for the v6 contract. Not an execution gate — exports stamp the running app. */
    const val APP_VERSION_CODE = 272
    const val WSRB_UNIT = "%·min/h"
    const val SAMPLE_DT_SECONDS = 1.0
    const val RIIV_RSA_BAND_HZ = "0.10-0.40"

    val PROVENANCE_GROUPS = listOf(
        "MOTION",
        "OPTICAL_INTENSITY",
        "CARDIAC_TIMING",
        "VENDOR_DSP_UNKNOWN",
    )

    data class Sample(
        val ts: Long,
        val envelope: Double?,
        val frozenOnsetBaseline: Double?,
        val amplitudeReduction: Double?,
        val sourceValid: Boolean,
        val rhythmicValid: Boolean,
        val frozenStageValid: Boolean,
        val wsrbContributionPctMinutes: Double,
    )

    data class Event(
        val startTs: Long,
        val endTs: Long,
        val durationSeconds: Int,
        val eventClass: String,
        val amplitudeReduction: Double,
        val recoveryMotionPresent: Boolean,
        val nominalHrOrRrResponsePresent: Boolean,
        val hrResponsePresent: Boolean,
        val rrResponsePresent: Boolean,
        val independentRespiratorySourcePresent: Boolean,
        val independentSourceSupport: Int,
        val supportFeatureCount: Int,
        val provenanceGroups: Set<String>,
        val sourceSignal: String,
        val inRhythmicWindow: Boolean,
        val inFrozenStage: Boolean?,
        val nearLargeWristRotation: Boolean,
        val fallbackUsed: Boolean,
        val wsrbContributionPctMinutes: Double,
    )

    data class Export(
        val samples: List<Sample>,
        val events: List<Event>,
        val wsrbNumeratorPctMinutes: Double,
        val atomicWsrbNumeratorPctMinutes: Double,
        val prolongedWsrbNumeratorPctMinutes: Double,
        val sourceValidHours: Double,
        val rhythmicValidHours: Double,
        val frozenStageValidHours: Double,
        val shadowCoverage: ShadowCoverage,
        val provenanceGroups: Map<String, String>,
    ) {
        val wsrb: Double?
            get() = wsrbNumeratorPctMinutes.takeIf { sourceValidHours > 0.0 }
                ?.let { it / sourceValidHours }
        val atomicWsrb: Double?
            get() = atomicWsrbNumeratorPctMinutes.takeIf { sourceValidHours > 0.0 }
                ?.let { it / sourceValidHours }
        val prolongedWsrb: Double?
            get() = prolongedWsrbNumeratorPctMinutes.takeIf { sourceValidHours > 0.0 }
                ?.let { it / sourceValidHours }
    }

    data class ShadowCoverage(
        val ppgGreenCoverage: Double,
        val rrCoverage: Double,
        val ppgGreenLongestGapSeconds: Int?,
        val rrLongestGapSeconds: Int?,
        val ppgGreenInterpolatedSeconds: Int,
        val rrInterpolatedSeconds: Int,
        val ppgGreenContinuityRejectedWindows: Int,
        val rrContinuityRejectedWindows: Int,
        val ppgGreenUsableWindows: Int,
        val rrUsableWindows: Int,
        val ppgGreenRhythmicWindows: Int,
        val rrRhythmicWindows: Int,
        val ppgGreenRhythmicWindowFraction: Double,
        val rrRhythmicWindowFraction: Double,
        val ppgGreenAliasingRiskWindows: Int,
        val rrAliasingRiskWindows: Int,
    )

    /** Map the current Result surface; replace only this function if the v6 model changes shape. */
    fun fromResult(
        analysis: Whoop4DisturbanceResearch.Result,
    ): Export {
        check(analysis.provenance.algorithmVersion == ALGORITHM_VERSION) {
            "v6 export requires $ALGORITHM_VERSION from the model; " +
                "received ${analysis.provenance.algorithmVersion}. " +
                "Wire the Android v6 model API before exporting a production bundle."
        }
        val primaryEvents = analysis.events.filterNot { it.fallbackUsed }
        val samples = analysis.detectorSamples.map { sample ->
            val event = primaryEvents.firstOrNull { sample.ts >= it.startTs && sample.ts < it.endTs }
            val sourceValid = sample.validForDetection &&
                sample.envelope != null &&
                sample.baseline != null &&
                sample.baseline > 0.0
            val frozenBaseline = event?.frozenOnsetBaseline?.takeIf { it > 0.0 }
            val depth = if (sourceValid && frozenBaseline != null && sample.envelope != null) {
                max(0.0, 1.0 - sample.envelope / frozenBaseline)
            } else {
                null
            }
            Sample(
                ts = sample.ts,
                envelope = sample.envelope,
                frozenOnsetBaseline = frozenBaseline,
                amplitudeReduction = depth ?: sample.amplitudeReduction,
                sourceValid = sourceValid,
                rhythmicValid = sourceValid && sample.inRhythmicWindow,
                frozenStageValid = sourceValid && sample.inScoredSleep == true,
                wsrbContributionPctMinutes = if (event != null && depth != null) {
                    100.0 * depth / 60.0
                } else 0.0,
            )
        }
        fun eventBurden(event: Whoop4DisturbanceResearch.EventCandidate): Double =
            samples.asSequence()
                .filter { it.ts >= event.startTs && it.ts < event.endTs }
                .sumOf { it.wsrbContributionPctMinutes }

        val events = analysis.events.map { event ->
            val independent = event.independentSourceSupport > 0
            Event(
                startTs = event.startTs,
                endTs = event.endTs,
                durationSeconds = event.durationSeconds,
                eventClass = if (event.durationSeconds > 180) "prolonged" else "atomic",
                amplitudeReduction = event.amplitudeReduction,
                recoveryMotionPresent = event.recoveryMotion,
                nominalHrOrRrResponsePresent = event.autonomicSupport,
                hrResponsePresent = event.hrResponsePresent,
                rrResponsePresent = event.rrResponsePresent,
                independentRespiratorySourcePresent = independent,
                independentSourceSupport = event.independentSourceSupport,
                supportFeatureCount = event.supportFeatureCount,
                provenanceGroups = event.provenanceGroups.map { it.name }.toSet(),
                sourceSignal = event.sourceSignal,
                inRhythmicWindow = event.inRhythmicWindow,
                inFrozenStage = event.inScoredSleep,
                nearLargeWristRotation = event.nearLargeWristRotation,
                fallbackUsed = event.fallbackUsed,
                wsrbContributionPctMinutes = eventBurden(event),
            )
        }
        val source = samples.count { it.sourceValid }.toDouble()
        val rhythmic = samples.count { it.rhythmicValid }.toDouble()
        val staged = samples.count { it.frozenStageValid }.toDouble()
        val numerator = samples.sumOf { it.wsrbContributionPctMinutes }
        check(abs(numerator - analysis.wsrb.numeratorPercentMinutes) <= 1e-6) {
            "v6 export WSRB numerator diverges from detector result: $numerator vs " +
                analysis.wsrb.numeratorPercentMinutes
        }
        return Export(
            samples = samples,
            events = events,
            wsrbNumeratorPctMinutes = numerator,
            atomicWsrbNumeratorPctMinutes = events
                .filter { it.eventClass == "atomic" }
                .sumOf { it.wsrbContributionPctMinutes },
            prolongedWsrbNumeratorPctMinutes = events
                .filter { it.eventClass == "prolonged" }
                .sumOf { it.wsrbContributionPctMinutes },
            sourceValidHours = source / 3600.0,
            rhythmicValidHours = rhythmic / 3600.0,
            frozenStageValidHours = staged / 3600.0,
            shadowCoverage = ShadowCoverage(
                ppgGreenCoverage = analysis.shadowRhythms.firstOrNull {
                    it.provenanceGroup == Whoop4DisturbanceResearch.ProvenanceGroup.OPTICAL_INTENSITY
                }?.sourceCoverage ?: analysis.coverage.ppgGreen,
                rrCoverage = analysis.shadowRhythms.firstOrNull {
                    it.provenanceGroup == Whoop4DisturbanceResearch.ProvenanceGroup.CARDIAC_TIMING
                }?.sourceCoverage ?: analysis.coverage.rrIntervals,
                ppgGreenLongestGapSeconds = analysis.shadowRhythms.firstOrNull {
                    it.provenanceGroup == Whoop4DisturbanceResearch.ProvenanceGroup.OPTICAL_INTENSITY
                }?.longestSourceGapSeconds,
                rrLongestGapSeconds = analysis.shadowRhythms.firstOrNull {
                    it.provenanceGroup == Whoop4DisturbanceResearch.ProvenanceGroup.CARDIAC_TIMING
                }?.longestSourceGapSeconds,
                ppgGreenInterpolatedSeconds = analysis.shadowRhythms.firstOrNull {
                    it.provenanceGroup == Whoop4DisturbanceResearch.ProvenanceGroup.OPTICAL_INTENSITY
                }?.interpolatedSeconds ?: 0,
                rrInterpolatedSeconds = analysis.shadowRhythms.firstOrNull {
                    it.provenanceGroup == Whoop4DisturbanceResearch.ProvenanceGroup.CARDIAC_TIMING
                }?.interpolatedSeconds ?: 0,
                ppgGreenContinuityRejectedWindows = analysis.shadowRhythms.firstOrNull {
                    it.provenanceGroup == Whoop4DisturbanceResearch.ProvenanceGroup.OPTICAL_INTENSITY
                }?.continuityRejectedWindows ?: 0,
                rrContinuityRejectedWindows = analysis.shadowRhythms.firstOrNull {
                    it.provenanceGroup == Whoop4DisturbanceResearch.ProvenanceGroup.CARDIAC_TIMING
                }?.continuityRejectedWindows ?: 0,
                ppgGreenUsableWindows = analysis.shadowRhythms.firstOrNull {
                    it.provenanceGroup == Whoop4DisturbanceResearch.ProvenanceGroup.OPTICAL_INTENSITY
                }?.usableWindows ?: 0,
                rrUsableWindows = analysis.shadowRhythms.firstOrNull {
                    it.provenanceGroup == Whoop4DisturbanceResearch.ProvenanceGroup.CARDIAC_TIMING
                }?.usableWindows ?: 0,
                ppgGreenRhythmicWindows = analysis.shadowRhythms.firstOrNull {
                    it.provenanceGroup == Whoop4DisturbanceResearch.ProvenanceGroup.OPTICAL_INTENSITY
                }?.rhythmicWindows ?: 0,
                rrRhythmicWindows = analysis.shadowRhythms.firstOrNull {
                    it.provenanceGroup == Whoop4DisturbanceResearch.ProvenanceGroup.CARDIAC_TIMING
                }?.rhythmicWindows ?: 0,
                ppgGreenRhythmicWindowFraction = analysis.shadowRhythms.firstOrNull {
                    it.provenanceGroup == Whoop4DisturbanceResearch.ProvenanceGroup.OPTICAL_INTENSITY
                }?.rhythmicWindowFraction ?: 0.0,
                rrRhythmicWindowFraction = analysis.shadowRhythms.firstOrNull {
                    it.provenanceGroup == Whoop4DisturbanceResearch.ProvenanceGroup.CARDIAC_TIMING
                }?.rhythmicWindowFraction ?: 0.0,
                ppgGreenAliasingRiskWindows = analysis.shadowRhythms.firstOrNull {
                    it.provenanceGroup == Whoop4DisturbanceResearch.ProvenanceGroup.OPTICAL_INTENSITY
                }?.aliasingRiskWindows ?: 0,
                rrAliasingRiskWindows = analysis.shadowRhythms.firstOrNull {
                    it.provenanceGroup == Whoop4DisturbanceResearch.ProvenanceGroup.CARDIAC_TIMING
                }?.aliasingRiskWindows ?: 0,
            ),
            provenanceGroups = mapOf(
                "motion" to "MOTION",
                "ppgGreen_riiv_like" to "OPTICAL_INTENSITY",
                "rr_rsa" to "CARDIAC_TIMING",
                "respiration_dsp" to "VENDOR_DSP_UNKNOWN",
            ),
        )
    }

    internal fun wsrb(
        envelope: List<Double>,
        frozenBaseline: List<Double>,
        sourceValid: List<Boolean> = List(envelope.size) { true },
        sourceHours: Double,
    ): Double? {
        require(envelope.size == frozenBaseline.size && envelope.size == sourceValid.size)
        if (sourceHours <= 0.0) return null
        val numerator = envelope.indices.sumOf { index ->
            if (!sourceValid[index] || frozenBaseline[index] <= 0.0) 0.0
            else 100.0 * max(0.0, 1.0 - envelope[index] / frozenBaseline[index]) / 60.0
        }
        return numerator / sourceHours
    }

    internal fun durationDistribution(events: List<Event>): Map<String, Any?> {
        val durations = events.map { it.durationSeconds.toDouble() }
        if (durations.isEmpty()) return mapOf("count" to 0, "total_seconds" to 0.0)
        val ordered = durations.sorted()
        fun percentile(fraction: Double): Double = ordered[
            ((ordered.size - 1) * fraction).toInt().coerceIn(0, ordered.lastIndex)
        ]
        return mapOf(
            "count" to durations.size,
            "total_seconds" to durations.sum(),
            "minimum_seconds" to ordered.first(),
            "p25_seconds" to percentile(0.25),
            "median_seconds" to percentile(0.50),
            "p75_seconds" to percentile(0.75),
            "maximum_seconds" to ordered.last(),
        )
    }

    internal fun csvNumber(value: Double): String = String.format(Locale.US, "%.9f", value)
}
