package com.noop.analytics

import com.noop.data.HrSample
import com.noop.data.RrInterval
import com.noop.data.Whoop4ResearchSampleEntity
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import org.json.JSONArray

/**
 * Deterministic, research-only respiratory-disturbance feasibility analysis for WHOOP 4 history.
 *
 * WHOOP 4 type-47 history exposes one reverse-engineered DSP row per second, not Apple's direct
 * accelerometer time series. The output is therefore an instrumentation result, never AHI/RDI/REI.
 * Every denominator, threshold, event timestamp and input fingerprint is exposed for paired studies.
 */
object Whoop4DisturbanceResearch {
    const val ALGORITHM_VERSION = "whoop4-respiratory-burden-v6"
    const val VALIDATION_BUNDLE_VERSION = 6
    const val MOTION_SOURCE_HZ = 1
    const val HIGHER_RATE_MOTION_AVAILABLE = false

    private const val SPECTRUM_WINDOW_SECONDS = 300
    private const val DETREND_HALF_SECONDS = 15
    private const val ENVELOPE_HALF_SECONDS = 4
    private const val BASELINE_LOOKBACK_SECONDS = 300
    private const val BASELINE_GUARD_SECONDS = 5
    private const val MIN_EVENT_SECONDS = 10
    private const val MAX_PLAUSIBLE_EVENT_SECONDS = 180
    private const val RECOVERY_CONFIRM_SECONDS = 3
    // A centered 9-second RMS envelope deliberately smooths respiratory amplitude. Requiring a
    // return to 85–100% of the pre-event level can merge adjacent reductions; 75% is the explicit
    // recovery tolerance, followed by three confirming seconds.
    private const val RECOVERY_FACTOR = 0.75
    private const val PRIMARY_REDUCTION = 0.30
    private const val MOTION_DELTA_THRESHOLD = 0.03
    private const val EVENT_MAX_INTERPOLATED_GAP_SECONDS = 2
    private const val RESPIRATION_RHYTHM_MAX_INTERPOLATED_GAP_SECONDS = 5
    private const val MAX_ANALYSIS_SPAN_SECONDS = 24 * 3600
    private const val POSITION_EPOCH_SECONDS = 30
    private const val COS_45_DEGREES = 0.70710678
    private val spectrumBins = (1 until SPECTRUM_WINDOW_SECONDS / 2).filter { k ->
        k.toDouble() / SPECTRUM_WINDOW_SECONDS in 0.02..0.48
    }
    private val spectrumCosineBasis = Array(spectrumBins.size) { binIndex ->
        val k = spectrumBins[binIndex]
        DoubleArray(SPECTRUM_WINDOW_SECONDS) { index ->
            cos(2 * PI * k * index / SPECTRUM_WINDOW_SECONDS)
        }
    }
    private val spectrumSineBasis = Array(spectrumBins.size) { binIndex ->
        val k = spectrumBins[binIndex]
        DoubleArray(SPECTRUM_WINDOW_SECONDS) { index ->
            sin(2 * PI * k * index / SPECTRUM_WINDOW_SECONDS)
        }
    }

    enum class Status {
        UNAVAILABLE,
        INSUFFICIENT_SIGNAL,
        LOW_CONFIDENCE,
        EXPLORATORY_ESTIMATE,
    }

    enum class PositionStatus {
        UNAVAILABLE,
        ASSUMPTION_FAILED,
        LOW_CONFIDENCE,
        WRIST_ORIENTATION_AVAILABLE,
    }

    data class Coverage(
        val respirationRaw: Double,
        val ppgGreen: Double,
        val ppgRedIR: Double,
        val rawOximetryPair: Double,
        val heartRate: Double,
        val rrIntervals: Double,
        val motionVector: Double,
    )

    data class Provenance(
        val algorithmVersion: String,
        val inputFingerprint: String,
        val inputStartTs: Long?,
        val inputEndTs: Long?,
        val inputRows: Int,
        val sessionStartTs: Long?,
        val sessionEndTs: Long?,
        val sessionHours: Double,
        val scoredSleepHours: Double?,
        val sleepMaskSource: String,
        val motionSourceHz: Int,
        val higherRateMotionAvailable: Boolean,
    )

    data class ChannelDiagnostic(
        val label: String,
        val provenanceGroup: ProvenanceGroup = ProvenanceGroup.VENDOR_DSP_UNKNOWN,
        val sourceCoverage: Double,
        val longestSourceGapSeconds: Int,
        val continuityRejectedWindows: Int,
        val usableWindows: Int,
        val rhythmicWindows: Int,
        val rhythmicWindowFraction: Double,
        val medianScore: Double,
        val availability: String,
    )

    data class RateEstimate(
        val label: String,
        val candidateEvents: Int,
        val denominatorHours: Double,
        val ratePerHour: Double?,
    )

    data class SensitivityPoint(
        val amplitudeReduction: Double,
        val candidateEvents: Int,
        val ratePerAllValidHour: Double?,
    )

    enum class DurationClass { ORDINARY, PROLONGED }

    enum class ProvenanceGroup {
        MOTION,
        OPTICAL_INTENSITY,
        CARDIAC_TIMING,
        VENDOR_DSP_UNKNOWN,
    }

    data class BurdenEstimate(
        val label: String,
        val numeratorPercentMinutes: Double,
        val denominatorHours: Double,
        val burdenPercentMinutesPerHour: Double?,
    )

    data class DurationDistribution(
        val ordinaryEvents: Int,
        val prolongedEvents: Int,
        val ordinarySeconds: Int,
        val prolongedSeconds: Int,
    )

    /** Diagnostic-only shadow rhythm output. It never participates in event acceptance. */
    data class ShadowRhythmDiagnostic(
        val label: String,
        val provenanceGroup: ProvenanceGroup,
        val sourceCoverage: Double,
        val longestSourceGapSeconds: Int,
        val interpolatedSeconds: Int,
        val continuityRejectedWindows: Int,
        val usableWindows: Int,
        val rhythmicWindows: Int,
        val rhythmicWindowFraction: Double,
        val aliasingRiskWindows: Int,
        val availability: String,
        val shadowOnly: Boolean = true,
    ) {
        val aliasingRisk: Boolean get() = aliasingRiskWindows > 0
        val rhythmicValidSeconds: Int get() = rhythmicWindows * SPECTRUM_WINDOW_SECONDS
    }

    data class EventCandidate(
        val startTs: Long,
        val endTs: Long,
        val durationSeconds: Int,
        val amplitudeReduction: Double,
        val autonomicSupport: Boolean,
        val recoveryMotion: Boolean,
        val inRhythmicWindow: Boolean,
        val inScoredSleep: Boolean?,
        val nearLargeWristRotation: Boolean,
        val sourceSignal: String = "",
        val fallbackUsed: Boolean = false,
        val durationClass: DurationClass = DurationClass.ORDINARY,
        val frozenOnsetBaseline: Double? = null,
        val hrResponsePresent: Boolean = false,
        val rrResponsePresent: Boolean = false,
        val supportFeatureCount: Int = 0,
        val independentSourceSupport: Int = 0,
        val provenanceGroups: Set<ProvenanceGroup> = emptySet(),
        val splitFromOverlong: Boolean = false,
        /** Deprecated v5 export compatibility; never used to accept, reject, split, or rank. */
        val independentModalitySupport: Int = 0,
        /** Deprecated v5 export compatibility; v6 does not score duration or evidence. */
        val evidenceScore: Double = 0.0,
        /** Deprecated v5 export compatibility; this is diagnostic-only support presence. */
        val highConfidence: Boolean = false,
    ) {
        val isProlonged: Boolean get() = durationClass == DurationClass.PROLONGED
    }

    enum class EpisodeOutcome {
        ACCEPTED,
        PROLONGED_ACCEPTED,
        SPLIT_OVERLONG,
        TOO_SHORT,
        OVERLONG_UNSPLIT,
        EXCESS_MOTION,
        DISCONTINUITY,
        OVERLAPS_PREVIOUS,
    }

    data class DetectorEpisode(
        val startTs: Long,
        val endTs: Long,
        val durationSeconds: Int,
        val amplitudeReduction: Double?,
        val movingSeconds: Int,
        val outcome: EpisodeOutcome,
        val acceptedEvents: Int,
    )

    data class DetectorSample(
        val ts: Long,
        val envelope: Double?,
        val baseline: Double?,
        val amplitudeReduction: Double?,
        val motionState: String,
        val validForDetection: Boolean,
        val inRhythmicWindow: Boolean,
        val inScoredSleep: Boolean?,
    )

    data class PositionProxy(
        val status: PositionStatus,
        val stableCoverage: Double,
        val stableHours: Double,
        val orientationEpisodes: Int,
        val largeRotations: Int,
        val rotationsPerHour: Double?,
        val candidatesNearRotation: Int,
        val magnitudeP10: Double?,
        val magnitudeMedian: Double?,
        val magnitudeP90: Double?,
        val reason: String,
    )

    data class Result(
        val status: Status,
        val primarySignal: String?,
        val coverage: Coverage,
        val provenance: Provenance,
        val channelDiagnostics: List<ChannelDiagnostic>,
        val positionProxy: PositionProxy,
        val allValidRate: RateEstimate,
        val rhythmicValidRate: RateEstimate,
        val scoredSleepRate: RateEstimate?,
        val rhythmicScoredSleepRate: RateEstimate?,
        val wsrb: BurdenEstimate,
        val rhythmicWsrb: BurdenEstimate,
        val frozenStageWsrb: BurdenEstimate?,
        val prolongedBurden: BurdenEstimate,
        val durationDistribution: DurationDistribution,
        val shadowRhythms: List<ShadowRhythmDiagnostic>,
        val highConfidenceAllValidRate: RateEstimate,
        val highConfidenceScoredSleepRate: RateEstimate?,
        val sensitivitySweep: List<SensitivityPoint>,
        val events: List<EventCandidate>,
        val autonomicSupportedEvents: Int,
        val highConfidenceEvents: Int,
        val guardedFallbackEvents: Int,
        val autonomicAllValidRate: RateEstimate,
        val autonomicScoredSleepRate: RateEstimate?,
        val suppressedSplitEvents: List<EventCandidate>,
        val detectorEpisodes: List<DetectorEpisode>,
        val detectorSamples: List<DetectorSample>,
        val reason: String,
    ) {
        val stageAgnosticWsrb: BurdenEstimate get() = wsrb
        val rhythmicValidWsrb: BurdenEstimate get() = rhythmicWsrb
        val frozenStageConditionedWsrb: BurdenEstimate? get() = frozenStageWsrb
        val prolongedWsrb: BurdenEstimate get() = prolongedBurden
        val sourceValidHours: Double get() = wsrb.denominatorHours
        val rhythmicSourceValidHours: Double get() = rhythmicWsrb.denominatorHours
        val frozenStageSourceValidHours: Double? get() = frozenStageWsrb?.denominatorHours
        val wsrbNumeratorPercentMinutes: Double get() = wsrb.numeratorPercentMinutes
        val wsrbPercentMinutesPerHour: Double? get() = wsrb.burdenPercentMinutesPerHour
        val rhythmicWsrbNumeratorPercentMinutes: Double get() = rhythmicWsrb.numeratorPercentMinutes
        val frozenStageWsrbNumeratorPercentMinutes: Double?
            get() = frozenStageWsrb?.numeratorPercentMinutes
    }

    private enum class ChannelModality { RESPIRATION, MOTION }

    private data class Channel(
        val label: String,
        val modality: ChannelModality,
        val value: ((Whoop4ResearchSampleEntity) -> Double?)? = null,
        val vector: ((Whoop4ResearchSampleEntity) -> List<Double>?)? = null,
    )

    private fun Channel.provenanceGroup(): ProvenanceGroup = when {
        modality == ChannelModality.MOTION -> ProvenanceGroup.MOTION
        label == "Respiration DSP field" -> ProvenanceGroup.VENDOR_DSP_UNKNOWN
        else -> ProvenanceGroup.VENDOR_DSP_UNKNOWN
    }

    private data class Spectrum(
        val bandPowerFraction: Double,
        val concentration: Double,
        val peakFrequencyHz: Double = 0.0,
        val aliasingRisk: Boolean = false,
    ) {
        val score: Double get() = bandPowerFraction * concentration
        val rhythmic: Boolean get() = bandPowerFraction >= 0.35 && concentration >= 0.20
    }

    private data class ChannelAssessment(
        val channel: Channel,
        val usableWindowStarts: Set<Long>,
        val rhythmicWindowStarts: Set<Long>,
        val medianScore: Double,
        val sourceCoverage: Double,
        val longestSourceGapSeconds: Int,
        val continuityRejectedWindows: Int,
        val availability: String,
    ) {
        val rhythmicFraction: Double
            get() = if (usableWindowStarts.isEmpty()) 0.0
            else rhythmicWindowStarts.size.toDouble() / usableWindowStarts.size
    }

    private enum class MotionState { UNKNOWN, STILL, MOVING }

    private data class Detection(
        val events: List<EventCandidate>,
        val episodes: List<DetectorEpisode>,
        val allValidSeconds: Int,
        val rhythmicValidSeconds: Int,
        val scoredSleepValidSeconds: Int?,
        val rhythmicScoredSleepValidSeconds: Int?,
    )

    private data class ChannelRun(
        val assessment: ChannelAssessment,
        val series: Series,
        val baseline: List<Double?>,
        val detection: Detection,
    )

    private data class OrientationEpoch(
        val startTs: Long,
        val vector: List<Double>,
        val observedSeconds: Int,
    )

    fun analyze(
        input: List<Whoop4ResearchSampleEntity>,
        heartRate: List<HrSample> = emptyList(),
        rrIntervals: List<RrInterval> = emptyList(),
        scoredSleepIntervals: List<LongRange> = emptyList(),
        sessionStartTs: Long? = null,
        sessionEndTs: Long? = null,
        includeDiagnostics: Boolean = false,
    ): Result {
        val rows = input.sortedBy { it.ts }
        val spanSeconds = if (rows.size < 2) 0L else rows.last().ts - rows.first().ts + 1L
        val boundedSleepIntervals = boundedIntervals(
            scoredSleepIntervals,
            sessionStartTs ?: rows.firstOrNull()?.ts,
            sessionEndTs ?: rows.lastOrNull()?.ts?.plus(1L),
        )
        val coverage = coverage(rows, spanSeconds, heartRate, rrIntervals)
        val shadowRhythms = shadowRhythms(rows, rrIntervals)
        val provenance = provenance(
            rows, heartRate, rrIntervals, boundedSleepIntervals, sessionStartTs, sessionEndTs,
        )
        val emptyPosition = positionProxy(rows, emptyList(), sessionStartTs, sessionEndTs)
        if (rows.size < 2 || spanSeconds <= 0L || spanSeconds > MAX_ANALYSIS_SPAN_SECONDS) {
            return emptyResult(
                coverage = coverage,
                provenance = provenance,
                position = emptyPosition,
                shadowRhythms = shadowRhythms,
                reason = when {
                    spanSeconds > MAX_ANALYSIS_SPAN_SECONDS ->
                        "Selected interval exceeds the 24-hour single-session safety limit"
                    else -> "No WHOOP 4 historical research rows for the selected sleep session"
                },
            )
        }

        val channels = listOf(
            Channel(
                "Respiration DSP field",
                ChannelModality.RESPIRATION,
                value = { it.respirationRaw?.toDouble() },
            ),
            Channel("Motion candidate 1 · PCA", ChannelModality.MOTION, vector = ::primaryMotionVector),
            Channel("Motion candidate 2 · PCA", ChannelModality.MOTION, vector = ::secondaryMotionVector),
            Channel("Motion candidate 1 · X", ChannelModality.MOTION, value = { it.motion1X }),
            Channel("Motion candidate 1 · Y", ChannelModality.MOTION, value = { it.motion1Y }),
            Channel("Motion candidate 1 · Z", ChannelModality.MOTION, value = { it.motion1Z }),
            Channel("Motion candidate 2 · X", ChannelModality.MOTION, value = { it.motion2X }),
            Channel("Motion candidate 2 · Y", ChannelModality.MOTION, value = { it.motion2Y }),
            Channel("Motion candidate 2 · Z", ChannelModality.MOTION, value = { it.motion2Z }),
        )
        val assessments = channels.map { assessChannel(rows, it) }
        val diagnostics = assessments.map {
            ChannelDiagnostic(
                label = it.channel.label,
                provenanceGroup = it.channel.provenanceGroup(),
                sourceCoverage = it.sourceCoverage,
                longestSourceGapSeconds = it.longestSourceGapSeconds,
                continuityRejectedWindows = it.continuityRejectedWindows,
                usableWindows = it.usableWindowStarts.size,
                rhythmicWindows = it.rhythmicWindowStarts.size,
                rhythmicWindowFraction = it.rhythmicFraction,
                medianScore = it.medianScore,
                availability = it.availability,
            )
        }
        val maximumUsableWindows = assessments.maxOfOrNull { it.usableWindowStarts.size } ?: 0
        // A channel with one unusually clean window must not beat a channel that covers most of the
        // night. Require at least half the coverage of the best-covered source before comparing rhythm.
        val minimumComparableWindows = max(1, (maximumUsableWindows + 1) / 2)
        val eligible = assessments.filter { it.usableWindowStarts.size >= minimumComparableWindows }
        val strongestMotion = eligible
            .filter { it.channel.modality == ChannelModality.MOTION }
            .maxWithOrNull(
                compareBy<ChannelAssessment> { it.medianScore }.thenBy { it.rhythmicFraction },
            )
        val rotationInvariantMotion = eligible.firstOrNull {
            it.channel.label == "Motion candidate 1 · PCA"
        }
        val motionPrimary = if (
            rotationInvariantMotion != null &&
            rotationInvariantMotion.medianScore >= (strongestMotion?.medianScore ?: 0.0) * 0.60
        ) rotationInvariantMotion else strongestMotion
        val respiration = eligible.firstOrNull {
            it.channel.modality == ChannelModality.RESPIRATION
        }
        // Prefer the named respiration DSP field when it clears an absolute quality floor and is not
        // dramatically worse than the cleanest motion representation. Otherwise prefer rotation-invariant
        // PCA when it retains at least 60% of the strongest fixed-axis score.
        val primary = if (
            respiration != null &&
            respiration.rhythmicFraction >= 0.25 &&
            respiration.medianScore >= (strongestMotion?.medianScore ?: 0.0) * 0.60
        ) respiration else motionPrimary ?: respiration
        if (primary == null) {
            return emptyResult(
                coverage = coverage,
                provenance = provenance,
                position = emptyPosition,
                diagnostics = diagnostics,
                shadowRhythms = shadowRhythms,
                reason = "No channel passed continuity and explicit on-wrist checks",
            )
        }

        val primaryRun = runChannel(
            rows, primary, heartRate, rrIntervals, boundedSleepIntervals, PRIMARY_REDUCTION,
        )
        // Alternative channels are frozen before looking at any reference-device labels. Candidate 2 is
        // intentionally excluded because it is frequently byte-for-byte identical to candidate 1 and
        // therefore cannot count as independent corroboration.
        val alternateAssessments = eligible
            .filter { it != primary }
            .filter {
                it.channel.modality == ChannelModality.RESPIRATION ||
                    it.channel.label.startsWith("Motion candidate 1 ·")
            }
        val respirationAlternate = alternateAssessments
            .filter { it.channel.modality == ChannelModality.RESPIRATION }
            .maxByOrNull { it.medianScore }
        val motionAlternates = alternateAssessments
            .filter { it.channel.modality == ChannelModality.MOTION }
            .sortedWith(
                compareByDescending<ChannelAssessment> { it.medianScore }
                    .thenByDescending { it.rhythmicFraction },
            )
            .take(3)
        // Always retain the best available alternate respiration modality before adding correlated
        // motion representations. Otherwise three strong axes can crowd respiration out and make
        // "cross-channel" support look independent when it is not.
        val alternateRuns = (listOfNotNull(respirationAlternate) + motionAlternates)
            .map { runChannel(rows, it, heartRate, rrIntervals, boundedSleepIntervals, PRIMARY_REDUCTION) }

        val primaryEvents = primaryRun.detection.events.map { event ->
            annotateSupport(event, primary.channel.provenanceGroup(), alternateRuns)
        }
        // A fallback is allowed only for a primary-source continuity gap. Support features are
        // annotated after this source decision and never decide whether the candidate is retained.
        val fallbackEvents = alternateRuns.flatMap { run ->
            run.detection.events.filter { candidate ->
                primaryUnavailableFraction(candidate, primaryRun) >= 0.50 &&
                    primaryEvents.none { eventsNear(candidate, it) }
            }.map { candidate ->
                annotateSupport(
                    candidate.copy(fallbackUsed = true),
                    run.assessment.channel.provenanceGroup(),
                    listOf(primaryRun) + alternateRuns.filter { it !== run },
                )
            }
        }.sortedBy { it.startTs }.fold(ArrayList<EventCandidate>()) { kept, event ->
            if (kept.none { eventsNear(event, it) }) kept += event
            kept
        }
        val suppressedSplitEvents = emptyList<EventCandidate>()
        val combinedEvents = (primaryEvents + fallbackEvents).sortedBy { it.startTs }
        val position = positionProxy(rows, combinedEvents, sessionStartTs, sessionEndTs)
        val eventsWithPosition = markRotationProximity(combinedEvents, positionRotationTimes(rows))
        val detection = primaryRun.detection.copy(
            events = eventsWithPosition,
        )
        val allRate = rate("all valid monitoring", detection.events.size, detection.allValidSeconds)
        val rhythmicEvents = detection.events.count { it.inRhythmicWindow }
        val rhythmicRate = rate("rhythmic valid monitoring", rhythmicEvents, detection.rhythmicValidSeconds)
        val sleepRate = detection.scoredSleepValidSeconds?.let { seconds ->
            rate("scored sleep", detection.events.count { it.inScoredSleep == true }, seconds)
        }
        val rhythmicSleepRate = detection.rhythmicScoredSleepValidSeconds?.let { seconds ->
            rate(
                "rhythmic scored sleep",
                detection.events.count { it.inScoredSleep == true && it.inRhythmicWindow },
                seconds,
            )
        }
        val highConfidence = detection.events.filter { it.highConfidence }
        val highConfidenceAllRate = rate(
            "evidence-supported all valid monitoring",
            highConfidence.size,
            detection.allValidSeconds,
        )
        val highConfidenceSleepRate = detection.scoredSleepValidSeconds?.let { seconds ->
            rate(
                "evidence-supported scored sleep",
                highConfidence.count { it.inScoredSleep == true },
                seconds,
            )
        }
        val autonomicEvents = detection.events.filter { it.autonomicSupport }
        val autonomicAllRate = rate(
            "autonomic-confirmed all valid monitoring",
            autonomicEvents.size,
            detection.allValidSeconds,
        )
        val autonomicSleepRate = detection.scoredSleepValidSeconds?.let { seconds ->
            rate(
                "autonomic-confirmed scored sleep",
                autonomicEvents.count { it.inScoredSleep == true },
                seconds,
            )
        }

        val sweep = listOf(0.30, 0.40, 0.50, 0.60).map { reduction ->
            val d = if (reduction == PRIMARY_REDUCTION) {
                detection
            } else {
                val rawDetection = detectAtReduction(
                    primaryRun.series, primaryRun.baseline, reduction, primary.rhythmicWindowStarts,
                    heartRate, rrIntervals, boundedSleepIntervals, primary.channel.label,
                )
                rawDetection
            }
            SensitivityPoint(
                amplitudeReduction = reduction,
                candidateEvents = d.events.size,
                ratePerAllValidHour = perHour(d.events.size, d.allValidSeconds),
            )
        }

        val sourceValidSeconds = detection.allValidSeconds
        val rhythmicSourceValidSeconds = detection.rhythmicValidSeconds
        val frozenStageSourceValidSeconds = detection.scoredSleepValidSeconds
        // The primary WSRB denominator is the primary wrist-source-valid time. Alternate-source
        // fallback candidates remain diagnostic event records, but must not contribute burden from
        // a different source into that primary endpoint.
        val primaryBurdenEvents = detection.events.filterNot { it.fallbackUsed }
        val wsrb = burden(
            "stage-agnostic WSRB", primaryBurdenEvents, listOf(primaryRun), sourceValidSeconds,
        )
        val rhythmicWsrb = burden(
            "rhythmic-valid WSRB", primaryBurdenEvents.filter { it.inRhythmicWindow },
            listOf(primaryRun), rhythmicSourceValidSeconds,
        )
        val frozenStageWsrb = frozenStageSourceValidSeconds?.let { seconds ->
            burden(
                "frozen-stage-conditioned WSRB",
                primaryBurdenEvents.filter { it.inScoredSleep == true },
                listOf(primaryRun),
                seconds,
            )
        }
        val prolongedBurden = burden(
            "prolonged WSRB", primaryBurdenEvents.filter {
                it.durationClass == DurationClass.PROLONGED
            }, listOf(primaryRun), sourceValidSeconds,
        )
        val durationDistribution = DurationDistribution(
            ordinaryEvents = detection.events.count { it.durationClass == DurationClass.ORDINARY },
            prolongedEvents = detection.events.count { it.durationClass == DurationClass.PROLONGED },
            ordinarySeconds = detection.events.filter { it.durationClass == DurationClass.ORDINARY }
                .sumOf { it.durationSeconds },
            prolongedSeconds = detection.events.filter { it.durationClass == DurationClass.PROLONGED }
                .sumOf { it.durationSeconds },
        )

        val preferredHours = rhythmicSleepRate?.denominatorHours ?: rhythmicRate.denominatorHours
        val status = when {
            detection.allValidSeconds < 30 * 60 -> Status.INSUFFICIENT_SIGNAL
            preferredHours >= 3.0 && primary.rhythmicFraction >= 0.50 -> Status.EXPLORATORY_ESTIMATE
            else -> Status.LOW_CONFIDENCE
        }
        return Result(
            status = status,
            primarySignal = primary.channel.label,
            coverage = coverage,
            provenance = provenance,
            channelDiagnostics = diagnostics,
            positionProxy = position,
            allValidRate = allRate,
            rhythmicValidRate = rhythmicRate,
            scoredSleepRate = sleepRate,
            rhythmicScoredSleepRate = rhythmicSleepRate,
            wsrb = wsrb,
            rhythmicWsrb = rhythmicWsrb,
            frozenStageWsrb = frozenStageWsrb,
            prolongedBurden = prolongedBurden,
            durationDistribution = durationDistribution,
            shadowRhythms = shadowRhythms,
            highConfidenceAllValidRate = highConfidenceAllRate,
            highConfidenceScoredSleepRate = highConfidenceSleepRate,
            sensitivitySweep = sweep,
            events = detection.events,
            autonomicSupportedEvents = detection.events.count { it.autonomicSupport },
            highConfidenceEvents = highConfidence.size,
            guardedFallbackEvents = fallbackEvents.size,
            autonomicAllValidRate = autonomicAllRate,
            autonomicScoredSleepRate = autonomicSleepRate,
            suppressedSplitEvents = suppressedSplitEvents,
            detectorEpisodes = primaryRun.detection.episodes,
            detectorSamples = if (includeDiagnostics) detectorSamples(
                primaryRun, boundedSleepIntervals,
            ) else emptyList(),
            reason = when (status) {
                Status.EXPLORATORY_ESTIMATE ->
                    "Event-shaped candidates in a repeatable surrogate signal; still unvalidated and not AHI/RDI"
                Status.LOW_CONFIDENCE ->
                    "Candidates were counted, but signal repeatability or high-quality sleep time did not pass the reliability gate"
                Status.INSUFFICIENT_SIGNAL -> "Fewer than 30 minutes passed strict signal checks"
                Status.UNAVAILABLE -> "No analyzable data"
            },
        )
    }

    /**
     * Parse timestamped stage segments ([{start,end,stage}]) and return non-wake seconds. Minute-total
     * dictionaries have no timestamps and intentionally return empty rather than fabricating a sleep mask.
     */
    fun scoredSleepIntervals(stagesJSON: String?): List<LongRange> {
        if (stagesJSON.isNullOrBlank() || !stagesJSON.trimStart().startsWith("[")) return emptyList()
        val array = runCatching { JSONArray(stagesJSON) }.getOrNull() ?: return emptyList()
        val out = ArrayList<LongRange>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val stage = item.optString("stage").lowercase()
            if (stage == "wake" || stage == "awake" || stage.isBlank()) continue
            val start = item.optLong("start", Long.MIN_VALUE)
            val end = item.optLong("end", Long.MIN_VALUE)
            if (start != Long.MIN_VALUE && end > start) out += start..(end - 1)
        }
        return mergeIntervals(out)
    }

    private data class Series(
        val firstTs: Long,
        val raw: MutableList<Double?>,
        val envelope: MutableList<Double?>,
        val motion: Array<MotionState>,
    ) {
        val size: Int get() = raw.size
    }

    private fun buildSeries(rows: List<Whoop4ResearchSampleEntity>, channel: Channel): Series {
        val firstTs = rows.first().ts
        val size = (rows.last().ts - firstTs + 1L).toInt()
        val byTs = rows.associateBy { it.ts }
        val raw = MutableList<Double?>(size) { null }
        if (channel.vector != null) {
            var bucketStart = rhythmicWindowStart(firstTs)
            while (bucketStart <= rows.last().ts) {
                val vectors = (0 until SPECTRUM_WINDOW_SECONDS).map { offset ->
                    byTs[bucketStart + offset]
                        ?.takeIf(::hasUsableContactAndQuality)
                        ?.let(channel.vector)
                }
                pcaProjection(vectors, minimumComplete = 240)?.forEachIndexed { offset, value ->
                    val index = (bucketStart + offset - firstTs).toInt()
                    if (index in raw.indices) raw[index] = value
                }
                bucketStart += SPECTRUM_WINDOW_SECONDS
            }
        } else {
            val value = requireNotNull(channel.value)
            for (index in raw.indices) {
                raw[index] = byTs[firstTs + index]
                    ?.takeIf(::hasUsableContactAndQuality)
                    ?.let(value)
            }
        }
        interpolateInPlace(raw, maximumGap = EVENT_MAX_INTERPOLATED_GAP_SECONDS)

        val detrended = MutableList<Double?>(size) { null }
        for (i in raw.indices) {
            val value = raw[i] ?: continue
            var sum = 0.0
            var count = 0
            for (j in max(0, i - DETREND_HALF_SECONDS)..min(raw.lastIndex, i + DETREND_HALF_SECONDS)) {
                raw[j]?.let { sum += it; count++ }
            }
            if (count >= 24) detrended[i] = value - sum / count
        }
        val envelope = MutableList<Double?>(size) { null }
        for (i in detrended.indices) {
            var squareSum = 0.0
            var count = 0
            for (j in max(0, i - ENVELOPE_HALF_SECONDS)..min(detrended.lastIndex, i + ENVELOPE_HALF_SECONDS)) {
                detrended[j]?.let { squareSum += it * it; count++ }
            }
            if (count >= 7) envelope[i] = sqrt(squareSum / count)
        }

        val motion = Array(size) { MotionState.UNKNOWN }
        for (i in 1 until size) {
            val left = byTs[firstTs + i - 1]
            val right = byTs[firstTs + i]
            val a = left?.let(::motionVector) ?: continue
            val b = right?.let(::motionVector) ?: continue
            val delta = sqrt(a.indices.sumOf { (b[it] - a[it]) * (b[it] - a[it]) })
            motion[i] = if (delta > MOTION_DELTA_THRESHOLD) MotionState.MOVING else MotionState.STILL
        }
        return Series(firstTs, raw, envelope, motion)
    }

    private fun baseline(
        envelope: List<Double?>,
        motion: Array<MotionState>,
        excluded: BooleanArray,
    ): MutableList<Double?> {
        val out = MutableList<Double?>(envelope.size) { null }
        for (i in BASELINE_LOOKBACK_SECONDS until envelope.size) {
            val values = ArrayList<Double>(BASELINE_LOOKBACK_SECONDS)
            val lo = i - BASELINE_LOOKBACK_SECONDS
            val hi = i - BASELINE_GUARD_SECONDS
            for (j in lo until hi) {
                val value = envelope[j] ?: continue
                if (!excluded[j] && motion[j] == MotionState.STILL) values += value
            }
            if (values.size >= 120) out[i] = percentile(values, 0.70)?.takeIf { it > 1e-12 }
        }
        return out
    }

    private fun runChannel(
        rows: List<Whoop4ResearchSampleEntity>,
        assessment: ChannelAssessment,
        heartRate: List<HrSample>,
        rrIntervals: List<RrInterval>,
        scoredSleepIntervals: List<LongRange>,
        reduction: Double,
    ): ChannelRun {
        val series = buildSeries(rows, assessment.channel)
        val firstBaseline = baseline(series.envelope, series.motion, BooleanArray(series.size))
        val firstDetection = detectAtReduction(
            series, firstBaseline, reduction, assessment.rhythmicWindowStarts,
            heartRate, rrIntervals, scoredSleepIntervals, assessment.channel.label,
        )
        val excluded = BooleanArray(series.size)
        for (event in firstDetection.events) {
            val lo = max(0, (event.startTs - series.firstTs).toInt() - 5)
            val hi = min(series.size, (event.endTs - series.firstTs).toInt() + 20)
            for (index in lo until hi) excluded[index] = true
        }
        val refinedBaseline = baseline(series.envelope, series.motion, excluded)
        val detection = detectAtReduction(
            series, refinedBaseline, reduction, assessment.rhythmicWindowStarts,
            heartRate, rrIntervals, scoredSleepIntervals, assessment.channel.label,
        )
        return ChannelRun(assessment, series, refinedBaseline, detection)
    }

    private fun detectAtReduction(
        series: Series,
        baseline: List<Double?>,
        reduction: Double,
        rhythmicWindowStarts: Set<Long>,
        heartRate: List<HrSample>,
        rrIntervals: List<RrInterval>,
        scoredSleepIntervals: List<LongRange>,
        sourceSignal: String,
    ): Detection {
        val thresholdFactor = 1.0 - reduction
        val hrByTs = heartRate.groupBy { it.ts }.mapValues { (_, rows) -> median(rows.map { it.bpm.toDouble() }) }
        val rrByTs = rrIntervals.groupBy { it.ts }.mapValues { (_, rows) ->
            median(rows.map { it.rrMs.toDouble() }.filter { it in 300.0..2_000.0 })
        }
        val events = ArrayList<EventCandidate>()
        val episodes = ArrayList<DetectorEpisode>()
        var i = BASELINE_LOOKBACK_SECONDS
        while (i < series.size) {
            val base = baseline[i]
            val amp = series.envelope[i]
            if (
                base == null || amp == null || series.motion[i] != MotionState.STILL ||
                amp > base * thresholdFactor
            ) {
                i++
                continue
            }
            val eventBaseline = requireNotNull(base)
            val startingAmplitude = requireNotNull(amp)
            val start = i
            var cursor = i
            var consecutiveRecovery = 0
            var recoveryStart = -1
            var minimumAmplitude = startingAmplitude
            var discontinuity = false
            while (cursor < series.size) {
                val value = series.envelope[cursor]
                if (value == null) {
                    discontinuity = true
                    break
                }
                minimumAmplitude = min(minimumAmplitude, value)
                if (series.motion[cursor] == MotionState.UNKNOWN) {
                    discontinuity = true
                    break
                }
                if (value >= eventBaseline * RECOVERY_FACTOR) {
                    consecutiveRecovery++
                    if (consecutiveRecovery >= RECOVERY_CONFIRM_SECONDS) {
                        recoveryStart = cursor - RECOVERY_CONFIRM_SECONDS + 1
                        break
                    }
                } else {
                    consecutiveRecovery = 0
                }
                cursor++
            }
            if (recoveryStart < 0) {
                val end = max(start + 1, min(series.size, cursor))
                episodes += DetectorEpisode(
                    startTs = series.firstTs + start,
                    endTs = series.firstTs + end,
                    durationSeconds = end - start,
                    amplitudeReduction = (1.0 - minimumAmplitude / eventBaseline).coerceIn(0.0, 1.0),
                    movingSeconds = (start until end).count {
                        series.motion[it] == MotionState.MOVING
                    },
                    outcome = if (!discontinuity && end - start > MAX_PLAUSIBLE_EVENT_SECONDS) {
                        EpisodeOutcome.OVERLONG_UNSPLIT
                    } else {
                        EpisodeOutcome.DISCONTINUITY
                    },
                    acceptedEvents = 0,
                )
                i = max(i + 1, cursor + 1)
                continue
            }
            val duration = recoveryStart - start
            val movingSeconds = (start until recoveryStart)
                .count { series.motion[it] == MotionState.MOVING }
            val eventStartTs = series.firstTs + start
            val eventEndTs = series.firstTs + recoveryStart
            var accepted = 0
            var rejectedForMotion = false
            var rejectedForOverlap = false
            val eventDuration = duration
            if (eventDuration < MIN_EVENT_SECONDS) {
                // Keep the full-recovery episode in diagnostics, but do not accept a sub-10-second
                // reduction. Durations above 180 seconds are deliberately accepted as prolonged.
            } else {
                val segmentMoving = (start until recoveryStart)
                    .count { series.motion[it] == MotionState.MOVING }
                if (segmentMoving > max(2, eventDuration / 5)) {
                    rejectedForMotion = true
                } else {
                    val previous = events.lastOrNull()
                    val recoveredGap = previous == null ||
                        series.firstTs + start - previous.endTs >= RECOVERY_CONFIRM_SECONDS
                    if (recoveredGap) {
                        val segmentMinimum = (start until recoveryStart)
                            .mapNotNull { series.envelope[it] }
                            .minOrNull()
                        if (segmentMinimum != null) {
                            val hrAndRr = responseSupport(
                                series.firstTs + start, series.firstTs + recoveryStart, hrByTs, rrByTs,
                            )
                            events += EventCandidate(
                                startTs = series.firstTs + start,
                                endTs = series.firstTs + recoveryStart,
                                durationSeconds = eventDuration,
                                amplitudeReduction =
                                    (1.0 - segmentMinimum / eventBaseline).coerceIn(0.0, 1.0),
                                autonomicSupport = hrAndRr.first || hrAndRr.second,
                                recoveryMotion = (recoveryStart until min(series.size, recoveryStart + 20))
                                    .any { series.motion[it] == MotionState.MOVING },
                                inRhythmicWindow =
                                    rhythmicWindowStart(series.firstTs + start) in rhythmicWindowStarts,
                                inScoredSleep = if (scoredSleepIntervals.isEmpty()) null
                                else scoredSleepIntervals.any { series.firstTs + start in it },
                                nearLargeWristRotation = false,
                                sourceSignal = sourceSignal,
                                durationClass = if (eventDuration > MAX_PLAUSIBLE_EVENT_SECONDS) {
                                    DurationClass.PROLONGED
                                } else {
                                    DurationClass.ORDINARY
                                },
                                hrResponsePresent = hrAndRr.first,
                                rrResponsePresent = hrAndRr.second,
                                frozenOnsetBaseline = eventBaseline,
                            )
                            accepted++
                        }
                    } else {
                        rejectedForOverlap = true
                    }
                }
            }
            val outcome = when {
                accepted > 0 && duration > MAX_PLAUSIBLE_EVENT_SECONDS ->
                    EpisodeOutcome.PROLONGED_ACCEPTED
                accepted > 0 -> EpisodeOutcome.ACCEPTED
                duration < MIN_EVENT_SECONDS -> EpisodeOutcome.TOO_SHORT
                duration > MAX_PLAUSIBLE_EVENT_SECONDS -> EpisodeOutcome.OVERLONG_UNSPLIT
                rejectedForMotion -> EpisodeOutcome.EXCESS_MOTION
                rejectedForOverlap -> EpisodeOutcome.OVERLAPS_PREVIOUS
                else -> EpisodeOutcome.DISCONTINUITY
            }
            episodes += DetectorEpisode(
                startTs = eventStartTs,
                endTs = eventEndTs,
                durationSeconds = duration,
                amplitudeReduction = (1.0 - minimumAmplitude / eventBaseline).coerceIn(0.0, 1.0),
                movingSeconds = movingSeconds,
                outcome = outcome,
                acceptedEvents = accepted,
            )
            i = max(i + 1, recoveryStart + RECOVERY_CONFIRM_SECONDS)
        }

        var allValid = 0
        var rhythmicValid = 0
        var sleepValid = 0
        var rhythmicSleepValid = 0
        for (index in baseline.indices) {
            if (
                baseline[index] == null || series.envelope[index] == null ||
                series.motion[index] != MotionState.STILL
            ) continue
            allValid++
            val ts = series.firstTs + index
            val rhythmic = rhythmicWindowStart(ts) in rhythmicWindowStarts
            val sleeping = scoredSleepIntervals.any { ts in it }
            if (rhythmic) rhythmicValid++
            if (sleeping) sleepValid++
            if (rhythmic && sleeping) rhythmicSleepValid++
        }
        return Detection(
            events = events,
            episodes = episodes,
            allValidSeconds = allValid,
            rhythmicValidSeconds = rhythmicValid,
            scoredSleepValidSeconds = if (scoredSleepIntervals.isEmpty()) null else sleepValid,
            rhythmicScoredSleepValidSeconds =
                if (scoredSleepIntervals.isEmpty()) null else rhythmicSleepValid,
        )
    }

    private fun eventsNear(left: EventCandidate, right: EventCandidate): Boolean =
        left.startTs <= right.endTs + 30L && right.startTs <= left.endTs + 30L

    private fun independentProvenanceSupport(
        event: EventCandidate,
        sourceGroup: ProvenanceGroup,
        runs: List<ChannelRun>,
    ): Int = runs.asSequence()
        .filter { it.assessment.channel.provenanceGroup() != sourceGroup }
        .filter { run -> run.detection.events.any { eventsNear(event, it) } }
        .map { it.assessment.channel.provenanceGroup() }
        .distinct()
        .count()

    private fun annotateSupport(
        event: EventCandidate,
        sourceGroup: ProvenanceGroup,
        runs: List<ChannelRun>,
    ): EventCandidate {
        val independent = independentProvenanceSupport(event, sourceGroup, runs)
        val supportCount = (if (event.recoveryMotion) 1 else 0) +
            (if (event.autonomicSupport) 1 else 0) +
            (if (independent > 0) 1 else 0)
        val groups = buildSet {
            add(sourceGroup)
            if (independent > 0) {
                runs.filter { it.assessment.channel.provenanceGroup() != sourceGroup }
                    .filter { run -> run.detection.events.any { eventsNear(event, it) } }
                    .mapTo(this) { it.assessment.channel.provenanceGroup() }
            }
        }
        return event.copy(
            supportFeatureCount = supportCount,
            independentSourceSupport = independent,
            provenanceGroups = groups,
            // Compatibility projections only. Neither is consulted by the detector.
            independentModalitySupport = independent,
            evidenceScore = supportCount / 3.0,
            highConfidence = supportCount > 0,
        )
    }

    private fun primaryUnavailableFraction(event: EventCandidate, primary: ChannelRun): Double {
        val lo = max(0, (event.startTs - primary.series.firstTs).toInt())
        val hi = min(primary.series.size, (event.endTs - primary.series.firstTs).toInt())
        if (hi <= lo) return 1.0
        val unavailable = (lo until hi).count { index ->
            primary.series.envelope[index] == null || primary.baseline[index] == null ||
                primary.series.motion[index] == MotionState.UNKNOWN
        }
        return unavailable / (hi - lo).toDouble()
    }

    private fun detectorSamples(
        run: ChannelRun,
        scoredSleepIntervals: List<LongRange>,
    ): List<DetectorSample> = run.baseline.indices.map { index ->
        val baseline = run.baseline[index]
        val envelope = run.series.envelope[index]
        val ts = run.series.firstTs + index
        DetectorSample(
            ts = ts,
            envelope = envelope,
            baseline = baseline,
            amplitudeReduction = if (baseline != null && envelope != null && baseline > 0.0) {
                1.0 - envelope / baseline
            } else null,
            motionState = run.series.motion[index].name,
            validForDetection = baseline != null && envelope != null &&
                run.series.motion[index] == MotionState.STILL,
            inRhythmicWindow =
                rhythmicWindowStart(ts) in run.assessment.rhythmicWindowStarts,
            inScoredSleep = if (scoredSleepIntervals.isEmpty()) null
            else scoredSleepIntervals.any { ts in it },
        )
    }

    private fun responseSupport(
        eventStartTs: Long,
        eventEndTs: Long,
        hrByTs: Map<Long, Double?>,
        rrByTs: Map<Long, Double?>,
    ): Pair<Boolean, Boolean> {
        val beforeHr = (-30L until -5L).mapNotNull { hrByTs[eventStartTs + it] }
        val afterHr = (5L..25L).mapNotNull { hrByTs[eventEndTs + it] }
        val beforeHrMedian = median(beforeHr)
        val afterHrMedian = median(afterHr)
        val hrSupport = beforeHr.size >= 10 && afterHr.size >= 10 &&
            beforeHrMedian != null && afterHrMedian != null &&
            afterHrMedian - beforeHrMedian >= 5.0

        // Equal-length, even-sized windows avoid phase-dependent median shifts from ordinary
        // respiratory sinus arrhythmia when RR alternates around a stable centre.
        val beforeRr = robustRr((-30L until -6L).mapNotNull { rrByTs[eventStartTs + it] })
        val afterRr = robustRr((5L until 29L).mapNotNull { rrByTs[eventEndTs + it] })
        val beforeRrMedian = median(beforeRr)
        val afterRrMedian = median(afterRr)
        val rrSupport = beforeRr.size >= 5 && afterRr.size >= 5 &&
            beforeRrMedian != null && afterRrMedian != null &&
            afterRrMedian <= beforeRrMedian * 0.94
        return hrSupport to rrSupport
    }

    private fun robustRr(values: List<Double>): List<Double> {
        val plausible = values.filter { it in 300.0..2_000.0 }
        val centre = median(plausible) ?: return emptyList()
        return plausible.filter { abs(it - centre) <= centre * 0.20 }
    }

    private fun assessChannel(
        rows: List<Whoop4ResearchSampleEntity>,
        channel: Channel,
    ): ChannelAssessment {
        val usable = LinkedHashSet<Long>()
        val rhythmic = LinkedHashSet<Long>()
        val scores = ArrayList<Double>()
        val firstTs = rows.firstOrNull()?.ts ?: 0L
        val lastTs = rows.lastOrNull()?.ts ?: firstTs
        val sourceTimestamps = rows.asSequence()
            .filter(::hasUsableContactAndQuality)
            .filter { channelSourceAvailable(it, channel) }
            .map { it.ts }
            .toSet()
        val spanSeconds = max(1L, lastTs - firstTs + 1L)
        val sourceCoverage = sourceTimestamps.size / spanSeconds.toDouble()
        val longestGap = longestMissingRun(firstTs, lastTs, sourceTimestamps)
        var continuityRejected = 0
        var start = 0
        while (start < rows.size) {
            val bucketStart = rhythmicWindowStart(rows[start].ts)
            var end = start
            while (end < rows.size && rows[end].ts < bucketStart + SPECTRUM_WINDOW_SECONDS) end++
            val onWrist = rows.subList(start, end)
                .filter(::hasUsableContactAndQuality)
                .associateBy { it.ts }
            if (onWrist.size >= 240) {
                val values = channelValues(onWrist, bucketStart, channel)
                val maximumGap = if (channel.modality == ChannelModality.RESPIRATION) {
                    RESPIRATION_RHYTHM_MAX_INTERPOLATED_GAP_SECONDS
                } else {
                    EVENT_MAX_INTERPOLATED_GAP_SECONDS
                }
                val continuous = interpolate(values, maximumGap)
                if (continuous == null) {
                    continuityRejected++
                } else {
                    continuous.let(::spectrum)?.let { result ->
                        usable += bucketStart
                        scores += result.score
                        if (result.rhythmic) rhythmic += bucketStart
                    }
                }
            }
            start = end
        }
        val availability = when {
            sourceTimestamps.isEmpty() -> "absent"
            usable.isEmpty() && continuityRejected > 0 -> "present but discontinuous"
            usable.isEmpty() -> "insufficient complete windows"
            continuityRejected > 0 -> "usable with continuity loss"
            else -> "usable"
        }
        return ChannelAssessment(
            channel = channel,
            usableWindowStarts = usable,
            rhythmicWindowStarts = rhythmic,
            medianScore = median(scores) ?: 0.0,
            sourceCoverage = sourceCoverage,
            longestSourceGapSeconds = longestGap,
            continuityRejectedWindows = continuityRejected,
            availability = availability,
        )
    }

    private fun channelValues(
        rowsByTs: Map<Long, Whoop4ResearchSampleEntity>,
        bucketStart: Long,
        channel: Channel,
    ): List<Double?> {
        val scalar = channel.value
        if (scalar != null) {
            return (0 until SPECTRUM_WINDOW_SECONDS).map { offset ->
                rowsByTs[bucketStart + offset]?.let(scalar)
            }
        }
        val vector = requireNotNull(channel.vector)
        val vectors = (0 until SPECTRUM_WINDOW_SECONDS).map { offset ->
            rowsByTs[bucketStart + offset]?.let(vector)
        }
        return pcaProjection(vectors, minimumComplete = 240)
            ?: List(SPECTRUM_WINDOW_SECONDS) { null }
    }

    /**
     * Principal-axis projection of a three-axis window. PCA is invariant to a rigid rotation of the
     * coordinate system, so wrist orientation changes do not force one fixed X/Y/Z choice. Missing
     * samples remain missing and are handled by the same explicit two-second interpolation policy.
     */
    private fun pcaProjection(
        vectors: List<List<Double>?>,
        minimumComplete: Int,
    ): List<Double?>? {
        val complete = vectors.filterNotNull()
        if (complete.size < minimumComplete) return null
        val mean = List(3) { axis -> complete.map { it[axis] }.average() }
        val covariance = Array(3) { DoubleArray(3) }
        complete.forEach { vector ->
            val centered = List(3) { axis -> vector[axis] - mean[axis] }
            for (row in 0..2) {
                for (column in 0..2) covariance[row][column] += centered[row] * centered[column]
            }
        }
        // Try every basis vector. A single seed can be exactly orthogonal to the leading
        // eigenvector (for example, anti-correlated rotated axes) and converge to the wrong mode.
        val direction = (0..2).mapNotNull { seed ->
            var candidate = DoubleArray(3) { if (it == seed) 1.0 else 0.0 }
            repeat(20) {
                val next = DoubleArray(3) { row ->
                    (0..2).sumOf { column -> covariance[row][column] * candidate[column] }
                }
                val length = sqrt(next.sumOf { it * it })
                if (length <= 1e-12) return@mapNotNull null
                candidate = DoubleArray(3) { next[it] / length }
            }
            val transformed = DoubleArray(3) { row ->
                (0..2).sumOf { column -> covariance[row][column] * candidate[column] }
            }
            val rayleigh = (0..2).sumOf { candidate[it] * transformed[it] }
            candidate to rayleigh
        }.maxByOrNull { it.second }?.first ?: return null
        return vectors.map { vector ->
            vector?.let { (0..2).sumOf { axis -> (it[axis] - mean[axis]) * direction[axis] } }
        }
    }

    private fun channelSourceAvailable(row: Whoop4ResearchSampleEntity, channel: Channel): Boolean =
        channel.value?.invoke(row) != null || channel.vector?.invoke(row) != null

    private fun longestMissingRun(firstTs: Long, lastTs: Long, available: Set<Long>): Int {
        var longest = 0
        var current = 0
        for (ts in firstTs..lastTs) {
            if (ts in available) {
                current = 0
            } else {
                current++
                longest = max(longest, current)
            }
        }
        return longest
    }

    private fun spectrum(signal: List<Double>): Spectrum? {
        if (signal.size != SPECTRUM_WINDOW_SECONDS) return null
        val detrended = signal.indices.map { i ->
            var sum = 0.0
            var count = 0
            for (j in max(0, i - DETREND_HALF_SECONDS)..min(signal.lastIndex, i + DETREND_HALF_SECONDS)) {
                sum += signal[j]
                count++
            }
            signal[i] - sum / count
        }
        if (detrended.sumOf { it * it } <= 1e-12) return null
        val powers = HashMap<Int, Double>()
        spectrumBins.forEachIndexed { binIndex, k ->
            var real = 0.0
            var imaginary = 0.0
            for ((index, value) in detrended.withIndex()) {
                real += value * spectrumCosineBasis[binIndex][index]
                imaginary -= value * spectrumSineBasis[binIndex][index]
            }
            powers[k] = real * real + imaginary * imaginary
        }
        val total = powers.values.sum()
        val band = powers.filterKeys { it.toDouble() / signal.size in 0.10..0.40 }
        val bandTotal = band.values.sum()
        val peak = band.maxByOrNull { it.value } ?: return null
        if (total <= 0.0 || bandTotal <= 0.0) return null
        val neighborhood = powers.filterKeys { abs(it - peak.key) <= 3 }.values.sum()
        return Spectrum(
            bandPowerFraction = bandTotal / total,
            concentration = neighborhood / bandTotal,
            peakFrequencyHz = peak.key.toDouble() / signal.size,
            aliasingRisk = peak.key.toDouble() / signal.size >= 0.45,
        )
    }

    private fun burden(
        label: String,
        events: List<EventCandidate>,
        runs: List<ChannelRun>,
        denominatorSeconds: Int,
    ): BurdenEstimate {
        var numerator = 0.0
        for (event in events) {
            val run = runs.firstOrNull {
                it.assessment.channel.label == event.sourceSignal
            } ?: runs.firstOrNull() ?: continue
            val start = (event.startTs - run.series.firstTs).toInt()
            val end = (event.endTs - run.series.firstTs).toInt()
            val base = (event.frozenOnsetBaseline ?: run.baseline.getOrNull(start))
                ?.takeIf { it > 0.0 } ?: continue
            for (index in start until end.coerceAtMost(run.series.size)) {
                // Keep the WSRB numerator on the same primary-source-valid support as its
                // denominator and the exported per-second eligibility mask. Accepted events may
                // contain a limited number of MOVING seconds; those seconds remain event
                // diagnostics but cannot contribute source-valid burden.
                if (
                    run.baseline.getOrNull(index) == null ||
                    run.series.motion[index] != MotionState.STILL
                ) continue
                val envelope = run.series.envelope.getOrNull(index) ?: continue
                numerator += max(0.0, 1.0 - envelope / base) * 100.0 / 60.0
            }
        }
        val denominatorHours = denominatorSeconds / 3600.0
        return BurdenEstimate(
            label = label,
            numeratorPercentMinutes = numerator,
            denominatorHours = denominatorHours,
            burdenPercentMinutesPerHour = if (denominatorSeconds > 0) {
                numerator / denominatorHours
            } else {
                null
            },
        )
    }

    /** Exact v6 WSRB calculation for deterministic unit tests and downstream research tooling. */
    fun calculateWsrb(
        envelope: List<Double>,
        frozenOnsetBaseline: Double,
        sourceValidHours: Double,
        dtSeconds: Double = 1.0,
    ): BurdenEstimate {
        val numerator = envelope.sumOf {
            max(0.0, 1.0 - it / frozenOnsetBaseline) * dtSeconds / 60.0 * 100.0
        }
        return BurdenEstimate(
            label = "stage-agnostic WSRB",
            numeratorPercentMinutes = numerator,
            denominatorHours = sourceValidHours,
            burdenPercentMinutesPerHour = if (sourceValidHours > 0.0) {
                numerator / sourceValidHours
            } else {
                null
            },
        )
    }

    private data class ShadowSpectrum(
        val rhythmic: Boolean,
        val aliasingRisk: Boolean,
    )

    private fun shadowRhythms(
        rows: List<Whoop4ResearchSampleEntity>,
        rrIntervals: List<RrInterval>,
    ): List<ShadowRhythmDiagnostic> = listOf(
        shadowPpgGreen(rows),
        shadowRsa(rows, rrIntervals),
    )

    private fun shadowPpgGreen(rows: List<Whoop4ResearchSampleEntity>): ShadowRhythmDiagnostic {
        val firstTs = rows.firstOrNull()?.ts ?: 0L
        val lastTs = rows.lastOrNull()?.ts ?: firstTs
        val values = rows.associate { it.ts to it.ppgGreen?.toDouble() }
        return shadowDiagnostic(
            label = "ppgGreen RIIV-like shadow",
            provenanceGroup = ProvenanceGroup.OPTICAL_INTENSITY,
            firstTs = firstTs,
            lastTs = lastTs,
            values = values,
            sampleHz = 1,
        )
    }

    private fun shadowRsa(
        rows: List<Whoop4ResearchSampleEntity>,
        rrIntervals: List<RrInterval>,
    ): ShadowRhythmDiagnostic {
        val firstTs = rows.firstOrNull()?.ts ?: rrIntervals.minOfOrNull { it.ts } ?: 0L
        val lastTs = rows.lastOrNull()?.ts ?: rrIntervals.maxOfOrNull { it.ts } ?: firstTs
        val values = rrIntervals.groupBy { it.ts }.mapValues { (_, samples) ->
            median(samples.map { it.rrMs.toDouble().takeIf { rr -> rr in 300.0..2_000.0 } }
                .filterNotNull())
        }
        val centre = median(values.values.filterNotNull())
        val cleaned = values.mapValues { (_, value) ->
            value?.takeIf { centre == null || abs(it - centre) <= centre * 0.20 }
        }
        return shadowDiagnostic(
            label = "RR RSA shadow",
            provenanceGroup = ProvenanceGroup.CARDIAC_TIMING,
            firstTs = firstTs,
            lastTs = lastTs,
            values = cleaned,
            sampleHz = 4,
        )
    }

    private fun shadowDiagnostic(
        label: String,
        provenanceGroup: ProvenanceGroup,
        firstTs: Long,
        lastTs: Long,
        values: Map<Long, Double?>,
        sampleHz: Int,
    ): ShadowRhythmDiagnostic {
        val spanSeconds = max(0L, lastTs - firstTs + 1L)
        val available = values.filterValues { it != null }.keys
        val sourceCoverage = if (spanSeconds == 0L) 0.0
        else available.distinct().size / spanSeconds.toDouble()
        val longestGap = if (spanSeconds == 0L) 0 else {
            longestMissingRun(firstTs, lastTs, available)
        }
        var interpolatedSeconds = 0
        var rejected = 0
        var usable = 0
        var rhythmic = 0
        var aliasing = 0
        var windowStart = rhythmicWindowStart(firstTs)
        while (windowStart <= lastTs) {
            val seconds = (0 until SPECTRUM_WINDOW_SECONDS).map { offset ->
                values[windowStart + offset]
            }
            val nonNull = seconds.count { it != null }
            if (nonNull >= 240) {
                val copy = seconds.toMutableList()
                val before = copy.count { it == null }
                interpolateInPlace(copy, RESPIRATION_RHYTHM_MAX_INTERPOLATED_GAP_SECONDS)
                val after = copy.count { it == null }
                if (after == 0) {
                    interpolatedSeconds += before
                    val signal = copy.filterNotNull()
                    // RR is represented at 4 Hz after cleaning/interpolation; ppgGreen remains 1 Hz.
                    val resampled = if (sampleHz == 1) signal else resampleAtFourHz(signal)
                    val spectrum = shadowSpectrum(resampled, sampleHz)
                    usable++
                    if (spectrum?.rhythmic == true) rhythmic++
                    if (spectrum?.aliasingRisk == true) aliasing++
                } else {
                    rejected++
                }
            }
            windowStart += SPECTRUM_WINDOW_SECONDS
        }
        val fraction = if (usable == 0) 0.0 else rhythmic.toDouble() / usable
        val availability = when {
            available.isEmpty() -> "absent"
            usable == 0 && rejected > 0 -> "present but discontinuous"
            usable == 0 -> "insufficient complete windows"
            rejected > 0 -> "usable with continuity loss"
            else -> "usable"
        }
        return ShadowRhythmDiagnostic(
            label = label,
            provenanceGroup = provenanceGroup,
            sourceCoverage = sourceCoverage,
            longestSourceGapSeconds = longestGap,
            interpolatedSeconds = interpolatedSeconds,
            continuityRejectedWindows = rejected,
            usableWindows = usable,
            rhythmicWindows = rhythmic,
            rhythmicWindowFraction = fraction,
            aliasingRiskWindows = aliasing,
            availability = availability,
        )
    }

    private fun resampleAtFourHz(values: List<Double>): List<Double> {
        if (values.isEmpty()) return emptyList()
        val out = ArrayList<Double>(values.size * 4)
        values.forEachIndexed { index, value ->
            val next = values.getOrNull(index + 1) ?: value
            repeat(4) { quarter ->
                out += value + (next - value) * quarter / 4.0
            }
        }
        return out
    }

    private fun shadowSpectrum(signal: List<Double>, sampleHz: Int): ShadowSpectrum? {
        if (signal.size < SPECTRUM_WINDOW_SECONDS * sampleHz) return null
        val half = DETREND_HALF_SECONDS * sampleHz
        val detrended = signal.indices.map { i ->
            val local = signal.subList(max(0, i - half), min(signal.size, i + half + 1))
            signal[i] - (median(local) ?: 0.0)
        }
        val frequencies = generateSequence(0.02) { it + 0.02 }
            .takeWhile { it < sampleHz / 2.0 - 0.01 }
            .toList()
        if (frequencies.isEmpty()) return null
        val powers = frequencies.map { frequency ->
            var real = 0.0
            var imaginary = 0.0
            detrended.forEachIndexed { index, value ->
                val angle = 2.0 * PI * frequency * index / sampleHz
                real += value * cos(angle)
                imaginary -= value * sin(angle)
            }
            frequency to real * real + imaginary * imaginary
        }
        val total = powers.sumOf { it.second }
        val band = powers.filter { it.first in 0.10..0.40 }
        val bandTotal = band.sumOf { it.second }
        val peak = powers.maxByOrNull { it.second } ?: return null
        val bandPeak = band.maxByOrNull { it.second } ?: return null
        if (total <= 1e-12 || bandTotal <= 1e-12) return null
        val neighbourhood = band.filter { abs(it.first - bandPeak.first) <= 0.04 }
            .sumOf { it.second }
        return ShadowSpectrum(
            rhythmic = bandTotal / total >= 0.35 && neighbourhood / bandTotal >= 0.20,
            aliasingRisk = peak.first >= sampleHz / 2.0 - 0.10,
        )
    }

    private fun positionProxy(
        rows: List<Whoop4ResearchSampleEntity>,
        candidates: List<EventCandidate>,
        sessionStartTs: Long?,
        sessionEndTs: Long?,
    ): PositionProxy {
        val magnitudes = rows.mapNotNull(::motionVector).map(::magnitude)
        val p10 = percentile(magnitudes, 0.10)
        val medianMagnitude = percentile(magnitudes, 0.50)
        val p90 = percentile(magnitudes, 0.90)
        if (rows.size < 2 || magnitudes.isEmpty()) {
            return unavailablePosition("No complete motion vector rows", p10, medianMagnitude, p90)
        }
        if (medianMagnitude == null || medianMagnitude !in 0.50..1.50) {
            return unavailablePosition(
                "Observed vector magnitude violates the provisional unit-gravity assumption",
                p10, medianMagnitude, p90, PositionStatus.ASSUMPTION_FAILED,
            )
        }
        val epochs = orientationEpochs(rows)
        if (epochs.isEmpty()) {
            return unavailablePosition("No stable on-wrist orientation epochs", p10, medianMagnitude, p90)
        }
        val transitions = epochs.zipWithNext()
        val rotations = transitions.filter { (left, right) ->
            right.startTs - left.startTs <= POSITION_EPOCH_SECONDS * 2L &&
                dot(left.vector, right.vector) < COS_45_DEGREES
        }
        val rotationTimes = rotations.map { it.second.startTs }
        val span = when {
            sessionStartTs != null && sessionEndTs != null && sessionEndTs > sessionStartTs ->
                sessionEndTs - sessionStartTs
            else -> rows.last().ts - rows.first().ts + 1
        }
        val stableSeconds = epochs.sumOf { it.observedSeconds }
        val stableHours = stableSeconds / 3600.0
        val stableCoverage = min(1.0, stableSeconds / span.toDouble())
        val orientationEpisodes = 1 + transitions.count { (left, right) ->
            right.startTs - left.startTs > POSITION_EPOCH_SECONDS * 2L ||
                dot(left.vector, right.vector) < COS_45_DEGREES
        }
        val status = if (stableSeconds >= 30 * 60 && stableCoverage >= 0.50) {
            PositionStatus.WRIST_ORIENTATION_AVAILABLE
        } else {
            PositionStatus.LOW_CONFIDENCE
        }
        val nearRotations = candidates.count { event ->
            rotationTimes.any { abs(it - event.endTs) <= 30 }
        }
        return PositionProxy(
            status = status,
            stableCoverage = stableCoverage,
            stableHours = stableHours,
            orientationEpisodes = orientationEpisodes,
            largeRotations = rotations.size,
            rotationsPerHour = if (stableHours > 0.0) rotations.size / stableHours else null,
            candidatesNearRotation = nearRotations,
            magnitudeP10 = p10,
            magnitudeMedian = medianMagnitude,
            magnitudeP90 = p90,
            reason = if (status == PositionStatus.WRIST_ORIENTATION_AVAILABLE) {
                "Stable wrist-orientation episodes are available; anatomical position needs paired chest calibration"
            } else {
                "Too little stable wrist orientation for a reliable proxy"
            },
        )
    }

    private fun orientationEpochs(rows: List<Whoop4ResearchSampleEntity>): List<OrientationEpoch> {
        val grouped = rows.groupBy { it.ts / POSITION_EPOCH_SECONDS * POSITION_EPOCH_SECONDS }
        val out = ArrayList<OrientationEpoch>()
        for ((startTs, epochRows) in grouped.toSortedMap()) {
            val vectors = epochRows
                .filter(::hasUsableContactAndQuality)
                .mapNotNull(::motionVector)
                .mapNotNull(::normalizedOrientation)
            if (vectors.size < 24) continue
            val mean = listOf(
                vectors.map { it[0] }.average(),
                vectors.map { it[1] }.average(),
                vectors.map { it[2] }.average(),
            )
            if (magnitude(mean) < 0.96) continue
            normalized(mean)?.let { out += OrientationEpoch(startTs, it, vectors.size) }
        }
        return out
    }

    private fun positionRotationTimes(rows: List<Whoop4ResearchSampleEntity>): List<Long> =
        orientationEpochs(rows).zipWithNext().mapNotNull { (left, right) ->
            if (
                right.startTs - left.startTs <= POSITION_EPOCH_SECONDS * 2L &&
                dot(left.vector, right.vector) < COS_45_DEGREES
            ) right.startTs else null
        }

    private fun markRotationProximity(
        events: List<EventCandidate>,
        rotationTimes: List<Long>,
    ): List<EventCandidate> = events.map { event ->
        event.copy(nearLargeWristRotation = rotationTimes.any { abs(it - event.endTs) <= 30 })
    }

    private fun coverage(
        rows: List<Whoop4ResearchSampleEntity>,
        spanSeconds: Long,
        heartRate: List<HrSample>,
        rrIntervals: List<RrInterval>,
    ): Coverage {
        fun rowFraction(predicate: (Whoop4ResearchSampleEntity) -> Boolean): Double =
            if (rows.isEmpty()) 0.0 else rows.count(predicate).toDouble() / rows.size
        fun timeFraction(timestamps: Collection<Long>): Double =
            if (spanSeconds <= 0L) 0.0 else min(1.0, timestamps.distinct().size / spanSeconds.toDouble())
        return Coverage(
            respirationRaw = rowFraction { it.respirationRaw != null },
            ppgGreen = rowFraction { it.ppgGreen != null },
            ppgRedIR = rowFraction { it.ppgRedIR != null },
            rawOximetryPair = rowFraction { it.spo2Red != null && it.spo2IR != null },
            heartRate = timeFraction(heartRate.map { it.ts }),
            rrIntervals = timeFraction(rrIntervals.map { it.ts }),
            motionVector = rowFraction { motionVector(it) != null },
        )
    }

    private fun provenance(
        rows: List<Whoop4ResearchSampleEntity>,
        heartRate: List<HrSample>,
        rrIntervals: List<RrInterval>,
        sleep: List<LongRange>,
        sessionStartTs: Long?,
        sessionEndTs: Long?,
    ): Provenance {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: Any?) {
            digest.update((value?.toString() ?: "_").toByteArray(Charsets.UTF_8))
            digest.update('|'.code.toByte())
        }
        rows.forEach { row ->
            add(row.ts); add(row.layoutVersion)
            add(row.motion1X); add(row.motion1Y); add(row.motion1Z)
            add(row.motion2X); add(row.motion2Y); add(row.motion2Z)
            add(row.ppgGreen); add(row.ppgRedIR); add(row.spo2Red); add(row.spo2IR)
            add(row.respirationRaw); add(row.skinContact); add(row.signalQuality)
        }
        heartRate.sortedWith(compareBy<HrSample> { it.ts }.thenBy { it.bpm })
            .forEach { add(it.ts); add(it.bpm) }
        rrIntervals.sortedWith(compareBy<RrInterval> { it.ts }.thenBy { it.rrMs })
            .forEach { add(it.ts); add(it.rrMs) }
        sleep.sortedBy { it.first }.forEach { add(it.first); add(it.last) }
        add(sessionStartTs)
        add(sessionEndTs)
        val fingerprint = digest.digest().joinToString("") { "%02x".format(it) }
        val sessionSeconds = if (
            sessionStartTs != null && sessionEndTs != null && sessionEndTs > sessionStartTs
        ) sessionEndTs - sessionStartTs else 0L
        val sleepSeconds = if (sleep.isEmpty()) null else sleep.sumOf { it.last - it.first + 1L }
        return Provenance(
            algorithmVersion = ALGORITHM_VERSION,
            inputFingerprint = fingerprint,
            inputStartTs = rows.firstOrNull()?.ts,
            inputEndTs = rows.lastOrNull()?.ts,
            inputRows = rows.size,
            sessionStartTs = sessionStartTs,
            sessionEndTs = sessionEndTs,
            sessionHours = sessionSeconds / 3600.0,
            scoredSleepHours = sleepSeconds?.div(3600.0),
            sleepMaskSource = if (sleep.isEmpty()) "unavailable" else "timestamped stagesJSON",
            motionSourceHz = MOTION_SOURCE_HZ,
            higherRateMotionAvailable = HIGHER_RATE_MOTION_AVAILABLE,
        )
    }

    private fun emptyResult(
        coverage: Coverage,
        provenance: Provenance,
        position: PositionProxy,
        diagnostics: List<ChannelDiagnostic> = emptyList(),
        shadowRhythms: List<ShadowRhythmDiagnostic> = emptyList(),
        reason: String,
    ) = Result(
        status = Status.UNAVAILABLE,
        primarySignal = null,
        coverage = coverage,
        provenance = provenance,
        channelDiagnostics = diagnostics,
        positionProxy = position,
        allValidRate = rate("all valid monitoring", 0, 0),
        rhythmicValidRate = rate("rhythmic valid monitoring", 0, 0),
        scoredSleepRate = null,
        rhythmicScoredSleepRate = null,
        wsrb = burden("stage-agnostic WSRB", emptyList(), emptyList(), 0),
        rhythmicWsrb = burden("rhythmic-valid WSRB", emptyList(), emptyList(), 0),
        frozenStageWsrb = null,
        prolongedBurden = burden("prolonged WSRB", emptyList(), emptyList(), 0),
        durationDistribution = DurationDistribution(0, 0, 0, 0),
        shadowRhythms = shadowRhythms,
        highConfidenceAllValidRate = rate("evidence-supported all valid monitoring", 0, 0),
        highConfidenceScoredSleepRate = null,
        sensitivitySweep = emptyList(),
        events = emptyList(),
        autonomicSupportedEvents = 0,
        highConfidenceEvents = 0,
        guardedFallbackEvents = 0,
        autonomicAllValidRate = rate("autonomic-confirmed all valid monitoring", 0, 0),
        autonomicScoredSleepRate = null,
        suppressedSplitEvents = emptyList(),
        detectorEpisodes = emptyList(),
        detectorSamples = emptyList(),
        reason = reason,
    )

    private fun unavailablePosition(
        reason: String,
        p10: Double? = null,
        median: Double? = null,
        p90: Double? = null,
        status: PositionStatus = PositionStatus.UNAVAILABLE,
    ) = PositionProxy(
        status = status,
        stableCoverage = 0.0,
        stableHours = 0.0,
        orientationEpisodes = 0,
        largeRotations = 0,
        rotationsPerHour = null,
        candidatesNearRotation = 0,
        magnitudeP10 = p10,
        magnitudeMedian = median,
        magnitudeP90 = p90,
        reason = reason,
    )

    private fun rate(label: String, events: Int, seconds: Int): RateEstimate =
        RateEstimate(label, events, seconds / 3600.0, perHour(events, seconds))

    private fun perHour(events: Int, seconds: Int): Double? =
        if (seconds <= 0) null else events / (seconds / 3600.0)

    private fun rhythmicWindowStart(ts: Long): Long =
        ts / SPECTRUM_WINDOW_SECONDS * SPECTRUM_WINDOW_SECONDS

    private fun motionVector(row: Whoop4ResearchSampleEntity): List<Double>? {
        return primaryMotionVector(row) ?: secondaryMotionVector(row)
    }

    private fun primaryMotionVector(row: Whoop4ResearchSampleEntity): List<Double>? =
        completeVector(row.motion1X, row.motion1Y, row.motion1Z)

    private fun secondaryMotionVector(row: Whoop4ResearchSampleEntity): List<Double>? =
        completeVector(row.motion2X, row.motion2Y, row.motion2Z)

    private fun completeVector(x: Double?, y: Double?, z: Double?): List<Double>? =
        if (x != null && y != null && z != null) listOf(x, y, z) else null

    private fun hasUsableContactAndQuality(row: Whoop4ResearchSampleEntity): Boolean =
        (row.skinContact ?: 0) > 0 && (row.signalQuality == null || row.signalQuality > 0)

    private fun normalizedOrientation(vector: List<Double>): List<Double>? {
        val length = magnitude(vector)
        if (length !in 0.50..1.50) return null
        return vector.map { it / length }
    }

    private fun normalized(vector: List<Double>): List<Double>? {
        val length = magnitude(vector)
        if (length <= 1e-12) return null
        return vector.map { it / length }
    }

    private fun magnitude(vector: List<Double>): Double = sqrt(vector.sumOf { it * it })

    private fun dot(left: List<Double>, right: List<Double>): Double =
        left.indices.sumOf { left[it] * right[it] }

    private fun interpolate(values: List<Double?>, maximumGap: Int): List<Double>? {
        val out = values.toMutableList()
        interpolateInPlace(out, maximumGap)
        return if (out.any { it == null }) null else out.filterNotNull()
    }

    private fun interpolateInPlace(values: MutableList<Double?>, maximumGap: Int) {
        var i = 0
        while (i < values.size) {
            if (values[i] != null) {
                i++
                continue
            }
            val start = i
            while (i < values.size && values[i] == null) i++
            val gap = i - start
            if (gap > maximumGap || start == 0 || i >= values.size) continue
            val left = values[start - 1] ?: continue
            val right = values[i] ?: continue
            for (j in 0 until gap) {
                values[start + j] = left + (right - left) * (j + 1.0) / (gap + 1.0)
            }
        }
    }

    private fun percentile(values: List<Double>, percentile: Double): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val index = ((sorted.size - 1) * percentile).toInt().coerceIn(sorted.indices)
        return sorted[index]
    }

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0
        else sorted[middle]
    }

    private fun mergeIntervals(input: List<LongRange>): List<LongRange> {
        if (input.isEmpty()) return emptyList()
        val sorted = input.sortedBy { it.first }
        val out = ArrayList<LongRange>()
        var current = sorted.first()
        for (next in sorted.drop(1)) {
            if (next.first <= current.last + 1) {
                current = current.first..max(current.last, next.last)
            } else {
                out += current
                current = next
            }
        }
        out += current
        return out
    }

    private fun boundedIntervals(
        input: List<LongRange>,
        startTs: Long?,
        endTsExclusive: Long?,
    ): List<LongRange> {
        if (startTs == null || endTsExclusive == null || endTsExclusive <= startTs) {
            return mergeIntervals(input)
        }
        val endTs = endTsExclusive - 1L
        return mergeIntervals(input.mapNotNull { interval ->
            val start = max(interval.first, startTs)
            val end = min(interval.last, endTs)
            if (end >= start) start..end else null
        })
    }
}
