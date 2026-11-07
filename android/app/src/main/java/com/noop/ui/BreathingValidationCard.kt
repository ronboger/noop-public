package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.noop.analytics.Whoop4BreathingDisturbances
import com.noop.ingest.BreathingValidationSnapshot
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Selected-night summary of the already-exported locked research detector.
 *
 * The card deliberately shows all three predeclared tiers, explicit valid-time denominators, and
 * non-clinical provenance. It never loads raw rows or reruns the detector.
 */
@Composable
internal fun BreathingValidationCard(
    snapshot: BreathingValidationSnapshot?,
    stageSegments: List<PersistedSegment>? = null,
    scoring: Boolean = false,
    recentSnapshots: List<BreathingValidationSnapshot> = emptyList(),
) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(
            title = "Breathing research",
            overline = "Selected night",
            trailing = "Experimental",
        )
        NoopCard(tint = Palette.restColor) {
            if (snapshot == null) {
                Text(
                    if (scoring) {
                        "Scoring this night's wrist-signal candidates…"
                    } else {
                        "No WHOOP 4 wrist-signal rows for this night yet. Wear the strap, let it " +
                            "sync, then reopen Sleep. A Test Centre export also writes this card."
                    },
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
                return@NoopCard
            }

            var expanded by remember(snapshot.identity) { mutableStateOf(false) }
            val status = breathingStatus(snapshot.status)
            val stageBreakdown = remember(snapshot.identity, stageSegments) {
                breathingStageBreakdown(
                    events = snapshot.events,
                    segments = stageSegments.orEmpty(),
                    windowStartTs = snapshot.sessionStartTs,
                    windowEndTs = snapshot.sessionEndTs,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space14)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Metrics.space4),
                    ) {
                        Text(
                            "NOOP wrist-signal candidates",
                            style = NoopType.headline,
                            color = Palette.textPrimary,
                        )
                        Text(
                            "Autonomic is the HR/RR-corroborated subset. Broad and evidence stay " +
                                "as locked v6 research tiers. Breathing disturbances copy Apple's " +
                                "events/hour + elevated/not-elevated shape only. Not AHI.",
                            style = NoopType.caption,
                            color = Palette.textTertiary,
                        )
                    }
                    Spacer(Modifier.width(Metrics.space12))
                    SourceBadge(text = status.label, tint = status.color)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Metrics.space8),
                ) {
                    BreathingTierCell(
                        label = "Autonomic",
                        tier = snapshot.autonomicScored,
                        modifier = Modifier.weight(1f),
                    )
                    BreathingTierCell(
                        label = "Evidence",
                        tier = snapshot.evidenceScored,
                        modifier = Modifier.weight(1f),
                    )
                    BreathingTierCell(
                        label = "Broad 30%",
                        tier = snapshot.broadScored,
                        modifier = Modifier.weight(1f),
                    )
                }

                BreathingDisturbancesBlock(snapshot, recentSnapshots)

                AccelerometerIndexBlock(snapshot)

                val validHours = snapshot.broadScored?.denominatorHours
                val scoredHours = snapshot.scoredSleepHours
                if (validHours != null && scoredHours != null && scoredHours > 0.0) {
                    val share = (validHours / scoredHours).coerceIn(0.0, 1.0)
                    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "Signal-valid scored sleep",
                                style = NoopType.footnote,
                                color = Palette.textSecondary,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                String.format(
                                    Locale.US,
                                    "%.2f of %.2f h · %d%%",
                                    validHours,
                                    scoredHours,
                                    (share * 100).roundToInt(),
                                ),
                                style = NoopType.footnote.copy(fontFeatureSettings = "tnum"),
                                color = Palette.textPrimary,
                            )
                        }
                        PipBar(
                            value = (share * 100).toFloat(),
                            tint = Palette.restColor,
                            height = 8.dp,
                        )
                    }
                }

                BreathingCandidateTimeline(snapshot, stageSegments.orEmpty(), stageBreakdown)
                BreathingCandidateStageBreakdown(stageBreakdown)

                Text(
                    "These are motion/physiology candidates—not SleepImage events, AHI, RDI, " +
                        "or a diagnosis.",
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )

                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        if (expanded) "Hide research details" else "Show research details",
                        color = Palette.accent,
                    )
                }

                if (expanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                        snapshot.primarySignal?.let {
                            BreathingDetailRow("Selected channel", it)
                        }
                        BreathingDetailRow(
                            "Algorithm",
                            "${snapshot.algorithmVersion} · bundle v${snapshot.bundleVersion} · " +
                                "app ${snapshot.appVersionCode}",
                        )
                        BreathingDetailRow(
                            "Input fingerprint",
                            snapshot.inputFingerprint.take(12),
                        )
                        BreathingDetailRow(
                            "Guarded fallback",
                            "${snapshot.guardedFallbackScored} scored candidates",
                        )
                        BreathingDetailRow(
                            "Split-overlong",
                            "${snapshot.retainedSplitScored} retained · " +
                                "${snapshot.suppressedSplitScored} suppressed",
                        )
                        BreathingDetailRow(
                            "Independent modality",
                            "${snapshot.independentModalityScored} scored candidates",
                        )
                        BreathingDetailRow(
                            "Recovery motion",
                            "${snapshot.recoveryMotionScored} scored candidates",
                        )
                        BreathingDetailRow(
                            "Motion / rotation flags",
                            "${snapshot.nearRotationScored} candidates near " +
                                "${snapshot.largeRotations} large rotations",
                        )
                        if (snapshot.primaryUsableWindows != null &&
                            snapshot.primaryRhythmicWindows != null
                        ) {
                            BreathingDetailRow(
                                "Rhythm continuity",
                                "${snapshot.primaryRhythmicWindows}/${snapshot.primaryUsableWindows} " +
                                    "rhythmic windows · longest gap " +
                                    "${snapshot.primaryLongestGapSeconds ?: 0}s",
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class BreathingStatus(val label: String, val color: Color)

@Composable
private fun breathingStatus(status: String): BreathingStatus = when (status) {
    "EXPLORATORY_ESTIMATE" -> BreathingStatus("Exploratory", Palette.restBright)
    "LOW_CONFIDENCE" -> BreathingStatus("Low confidence", Palette.statusWarning)
    "INSUFFICIENT_SIGNAL" -> BreathingStatus("Limited signal", Palette.statusWarning)
    else -> BreathingStatus("Unavailable", Palette.textTertiary)
}

@Composable
private fun BreathingTierCell(
    label: String,
    tier: BreathingValidationSnapshot.Tier?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(vertical = Metrics.space4),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Metrics.space4),
    ) {
        Text(
            label.uppercase(),
            style = NoopType.overline,
            color = Palette.textTertiary,
            textAlign = TextAlign.Center,
        )
        Text(
            tier?.ratePerHour?.let { String.format(Locale.US, "%.1f/h", it) } ?: "—",
            style = NoopType.number(24f, weight = FontWeight.Bold)
                .copy(fontFeatureSettings = "tnum"),
            color = Palette.textPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            tier?.let { "${it.candidateEvents} candidates" } ?: "No denominator",
            style = NoopType.caption.copy(fontFeatureSettings = "tnum"),
            color = Palette.textTertiary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun BreathingCandidateTimeline(
    snapshot: BreathingValidationSnapshot,
    stageSegments: List<PersistedSegment>,
    stageBreakdown: BreathingStageBreakdown?,
) {
    if (snapshot.events.isEmpty() || snapshot.sessionEndTs <= snapshot.sessionStartTs) return
    val byStage = stageBreakdown?.summaries
        ?.joinToString { "${it.label} ${it.candidateEvents}" }
        ?.let { " By stage: $it." }
        .orEmpty()
    val description =
        "${snapshot.events.size} broad wrist-signal candidates. " +
            "${snapshot.events.count { it.evidenceSupported }} evidence-supported. " +
            "${snapshot.events.count { it.autonomicSupported }} autonomic-confirmed.$byStage"
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
        Text("Candidate timing", style = NoopType.footnote, color = Palette.textSecondary)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .semantics { contentDescription = description },
        ) {
            val inset = 4.dp.toPx()
            val trackHeight = 8.dp.toPx()
            val trackTop = (size.height - trackHeight) / 2f
            drawRoundRect(
                color = Palette.surfaceInset,
                topLeft = Offset(inset, trackTop),
                size = Size(max(1f, size.width - inset * 2), trackHeight),
                cornerRadius = CornerRadius(trackHeight / 2f),
            )
            val span = (snapshot.sessionEndTs - snapshot.sessionStartTs).toFloat()
            stageSegments.forEach { segment ->
                val clippedStart = max(segment.start, snapshot.sessionStartTs)
                val clippedEnd = min(segment.end, snapshot.sessionEndTs)
                if (clippedEnd <= clippedStart) return@forEach
                val startFraction = (clippedStart - snapshot.sessionStartTs) / span
                val endFraction = (clippedEnd - snapshot.sessionStartTs) / span
                val left = inset + startFraction * (size.width - inset * 2)
                val right = inset + endFraction * (size.width - inset * 2)
                drawRoundRect(
                    color = breathingStageColor(segment.stage).copy(alpha = 0.72f),
                    topLeft = Offset(left, trackTop),
                    size = Size(max(1f, right - left), trackHeight),
                    cornerRadius = CornerRadius(1.dp.toPx()),
                )
            }
            snapshot.events.forEach { event ->
                val fraction = (
                    (event.startTs - snapshot.sessionStartTs) / span
                    ).coerceIn(0f, 1f)
                val x = inset + fraction * (size.width - inset * 2)
                val color = when {
                    event.autonomicSupported -> Palette.accent
                    event.evidenceSupported -> Palette.restBright
                    else -> Palette.textTertiary.copy(alpha = 0.58f)
                }
                val halfHeight = when {
                    event.autonomicSupported -> 14.dp.toPx()
                    event.evidenceSupported -> 11.dp.toPx()
                    else -> 8.dp.toPx()
                }
                drawLine(
                    color = color,
                    start = Offset(x, size.height / 2f - halfHeight),
                    end = Offset(x, size.height / 2f + halfHeight),
                    strokeWidth = if (
                        event.autonomicSupported || event.evidenceSupported
                    ) {
                        2.dp.toPx()
                    } else {
                        1.dp.toPx()
                    },
                    cap = StrokeCap.Round,
                )
            }
        }
        Text(
            if (stageBreakdown == null) {
                "Broad · evidence-supported · autonomic-confirmed"
            } else {
                "Stage-colored night · broad · evidence-supported · autonomic-confirmed"
            },
            style = NoopType.caption,
            color = Palette.textTertiary,
        )
    }
}

@Composable
private fun BreathingDisturbancesBlock(
    snapshot: BreathingValidationSnapshot,
    recentSnapshots: List<BreathingValidationSnapshot>,
) {
    val window = remember(snapshot.identity, recentSnapshots) {
        breathingDisturbancesWindow(recentSnapshots, snapshot.sessionEndTs)
    }
    val rate = snapshot.bdRatePerHour
    val label = snapshot.bdLabel ?: return
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
        Text(
            "Breathing disturbances (research)",
            style = NoopType.footnote,
            color = Palette.textSecondary,
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                if (rate != null) {
                    String.format(Locale.US, "%.1f / hour", rate)
                } else {
                    "—"
                },
                style = NoopType.captionNumber,
                color = Palette.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                breathingDisturbanceLabel(label),
                style = NoopType.footnote.copy(fontWeight = FontWeight.SemiBold),
                color = Palette.textPrimary,
            )
        }
        Text(
            when (window.label) {
                Whoop4BreathingDisturbances.WindowLabel.INSUFFICIENT ->
                    "Recent nights: ${window.eligibleNights} eligible " +
                        "(need ${window.minNights} in 30 days; store keeps 30 snapshots)."
                Whoop4BreathingDisturbances.WindowLabel.NO_CONSISTENT_ELEVATION ->
                    "Recent nights: ${window.elevatedNights} of ${window.eligibleNights} " +
                        "elevated. No consistent-elevation flag."
                Whoop4BreathingDisturbances.WindowLabel.CONSISTENT_ELEVATION ->
                    "Recent nights: ${window.elevatedNights} of ${window.eligibleNights} " +
                        "elevated. Consistent-elevation flag."
            },
            style = NoopType.caption,
            color = Palette.textTertiary,
        )
        Text(
            "Apple-shaped output on WHOOP 4 1 Hz DSP: events/hour of scored sleep, " +
                "elevated/not elevated, 30-day consistency. Not Apple's algorithm, not AHI, " +
                "not a diagnosis.",
            style = NoopType.caption,
            color = Palette.textTertiary,
        )
    }
}

@Composable
private fun AccelerometerIndexBlock(snapshot: BreathingValidationSnapshot) {
    val version = snapshot.accelAlgorithmVersion ?: return
    val index = snapshot.accelIndexPerHour
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
        Text(
            "Accelerometer index · $version (research)",
            style = NoopType.footnote,
            color = Palette.textSecondary,
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                if (index != null) {
                    String.format(Locale.US, "%.1f epochs / asleep hour", index)
                } else {
                    "No complete motion samples"
                },
                style = NoopType.captionNumber,
                color = Palette.textPrimary,
                modifier = Modifier.weight(1f),
            )
            snapshot.accelCalibratedPerHour?.let {
                Text(
                    String.format(Locale.US, "≈ %.1f / h comparator scale", it),
                    style = NoopType.footnote.copy(fontWeight = FontWeight.SemiBold),
                    color = Palette.textPrimary,
                )
            }
        }
        Text(
            buildString {
                append("Motion-only, independent of the v6 tiers above. ")
                snapshot.accelBurstsPerHour?.let {
                    append(String.format(Locale.US, "Untrained still→burst rate %.1f / h. ", it))
                }
                append(
                    "Frozen on seven SleepImage-paired nights of one person; the comparator-scale " +
                        "value is that fit, not AHI. Development stamp: not validated across people.",
                )
            },
            style = NoopType.caption,
            color = Palette.textTertiary,
        )
    }
}

internal fun breathingDisturbanceLabel(raw: String): String = when (raw) {
    "ELEVATED" -> "Elevated"
    "NOT_ELEVATED" -> "Not elevated"
    "INSUFFICIENT" -> "Insufficient sleep"
    else -> raw
}

internal fun breathingDisturbancesWindow(
    snapshots: List<BreathingValidationSnapshot>,
    windowEndTs: Long,
): Whoop4BreathingDisturbances.WindowAssessment {
    val newestPerSession = snapshots
        .groupBy { it.sessionStartTs to it.sessionEndTs }
        .mapNotNull { (_, group) -> group.maxByOrNull { it.generatedAtMs } }
    val nights = newestPerSession.mapNotNull { snapshot ->
        val raw = snapshot.bdLabel ?: return@mapNotNull null
        val label = runCatching {
            Whoop4BreathingDisturbances.NightLabel.valueOf(raw)
        }.getOrNull() ?: return@mapNotNull null
        Whoop4BreathingDisturbances.NightAssessment(
            algorithmVersion = snapshot.bdAlgorithmVersion
                ?: Whoop4BreathingDisturbances.ALGORITHM_VERSION,
            sessionStartTs = snapshot.sessionStartTs,
            sessionEndTs = snapshot.sessionEndTs,
            scoredSleepHours = snapshot.scoredSleepHours,
            candidateEvents = snapshot.bdCandidateEvents ?: 0,
            ratePerHour = snapshot.bdRatePerHour,
            label = label,
        )
    }
    return Whoop4BreathingDisturbances.assessWindow(nights, windowEndTs)
}

@Composable
private fun BreathingCandidateStageBreakdown(breakdown: BreathingStageBreakdown?) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
        Text(
            "Candidates by sleep stage",
            style = NoopType.footnote,
            color = Palette.textSecondary,
        )
        if (breakdown == null) {
            Text(
                "Stage timing is not available for this night, so candidates cannot be matched " +
                    "to REM, light, or deep sleep.",
                style = NoopType.caption,
                color = Palette.textTertiary,
            )
            return@Column
        }
        breakdown.summaries.forEach { summary ->
            BreathingStageRow(summary)
        }
        Text(
            "Observed/h uses all recorded time in each stage; signal gaps are not removed, so it " +
                "is a conservative density rather than the signal-valid rate above.",
            style = NoopType.caption,
            color = Palette.textTertiary,
        )
        if (breakdown.unassignedEvents > 0) {
            Text(
                "${breakdown.unassignedEvents} ${candidateWord(breakdown.unassignedEvents)} fell " +
                    "outside timestamped REM, light, or deep segments.",
                style = NoopType.caption,
                color = Palette.textTertiary,
            )
        }
    }
}

@Composable
private fun BreathingStageRow(summary: BreathingStageSummary) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space8),
    ) {
        Canvas(Modifier.width(10.dp).height(10.dp)) {
            drawCircle(color = breathingStageColor(summary.key))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(summary.label, style = NoopType.footnote, color = Palette.textPrimary)
            Text(
                "${formatStageDuration(summary.durationSeconds)} · " +
                    (summary.observedRatePerHour?.let {
                        String.format(Locale.US, "%.1f observed/h", it)
                    } ?: "— observed/h"),
                style = NoopType.caption.copy(fontFeatureSettings = "tnum"),
                color = Palette.textTertiary,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "${summary.candidateEvents} ${candidateWord(summary.candidateEvents)}",
                style = NoopType.footnote.copy(fontFeatureSettings = "tnum"),
                color = Palette.textPrimary,
            )
            Text(
                "${summary.evidenceEvents} evidence · ${summary.autonomicEvents} autonomic",
                style = NoopType.caption.copy(fontFeatureSettings = "tnum"),
                color = Palette.textTertiary,
            )
        }
    }
}

private fun candidateWord(count: Int): String = if (count == 1) "candidate" else "candidates"

private fun formatStageDuration(seconds: Long): String {
    val minutes = (seconds / 60.0).roundToInt()
    val hours = minutes / 60
    val remainder = minutes % 60
    return if (hours > 0) "${hours}h ${remainder}m" else "${minutes}m"
}

private fun breathingStageColor(stage: String): Color = when (stage.trim().lowercase()) {
    "deep" -> Palette.sleepDeep
    "rem" -> Palette.sleepREM
    "awake", "wake" -> Palette.sleepAwake
    else -> Palette.sleepLight
}

internal data class BreathingStageSummary(
    val key: String,
    val label: String,
    val durationSeconds: Long,
    val candidateEvents: Int,
    val evidenceEvents: Int,
    val autonomicEvents: Int,
) {
    val observedRatePerHour: Double?
        get() = if (durationSeconds > 0L) {
            candidateEvents / (durationSeconds / 3600.0)
        } else {
            null
        }
}

internal data class BreathingStageBreakdown(
    val summaries: List<BreathingStageSummary>,
    val unassignedEvents: Int,
)

/**
 * Correlate each stored candidate with the timestamped on-device sleep stages for the same window.
 *
 * New snapshots retain an event's full interval and assign it to the stage with the greatest
 * overlap. Older snapshots are point events and use the stage containing their start timestamp.
 * Awake and unknown segments are intentionally unassigned because this card reports scored-sleep
 * candidates only. Stage time is clipped to the exact validation window before rates are computed.
 */
internal fun breathingStageBreakdown(
    events: List<BreathingValidationSnapshot.EventTick>,
    segments: List<PersistedSegment>,
    windowStartTs: Long,
    windowEndTs: Long,
): BreathingStageBreakdown? {
    if (windowEndTs <= windowStartTs) return null
    val stageOrder = listOf("rem", "deep", "light")
    val labels = mapOf("rem" to "REM", "deep" to "Deep", "light" to "Light")
    val recognized = segments.mapNotNull { segment ->
        val key = segment.stage.trim().lowercase()
        if (key !in stageOrder) null else key to segment
    }
    if (recognized.isEmpty()) return null

    val durations = stageOrder.associateWith { 0L }.toMutableMap()
    recognized.forEach { (key, segment) ->
        val overlap = min(segment.end, windowEndTs) - max(segment.start, windowStartTs)
        if (overlap > 0L) durations[key] = durations.getValue(key) + overlap
    }
    if (durations.values.none { it > 0L }) return null

    data class Counts(var broad: Int = 0, var evidence: Int = 0, var autonomic: Int = 0)
    val counts = stageOrder.associateWith { Counts() }
    var unassigned = 0

    events.forEach { event ->
        val eventStart = max(event.startTs, windowStartTs)
        val eventEnd = min(max(event.startTs, event.endTs), windowEndTs)
        val key = if (eventEnd > eventStart) {
            recognized.asSequence()
                .map { (stage, segment) ->
                    stage to max(
                        0L,
                        min(eventEnd, segment.end) - max(eventStart, segment.start),
                    )
                }
                .filter { it.second > 0L }
                .maxByOrNull { it.second }
                ?.first
        } else if (event.startTs >= windowStartTs && event.startTs < windowEndTs) {
            recognized.firstOrNull { (_, segment) ->
                event.startTs >= segment.start && event.startTs < segment.end
            }?.first
        } else {
            null
        }
        if (key == null) {
            unassigned += 1
        } else {
            val stageCounts = counts.getValue(key)
            stageCounts.broad += 1
            if (event.evidenceSupported) stageCounts.evidence += 1
            if (event.autonomicSupported) stageCounts.autonomic += 1
        }
    }

    return BreathingStageBreakdown(
        summaries = stageOrder.map { key ->
            val stageCounts = counts.getValue(key)
            BreathingStageSummary(
                key = key,
                label = labels.getValue(key),
                durationSeconds = durations.getValue(key),
                candidateEvents = stageCounts.broad,
                evidenceEvents = stageCounts.evidence,
                autonomicEvents = stageCounts.autonomic,
            )
        },
        unassignedEvents = unassigned,
    )
}

@Composable
private fun BreathingDetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = NoopType.footnote,
            color = Palette.textSecondary,
            modifier = Modifier.weight(0.42f),
        )
        Text(
            value,
            style = NoopType.footnote.copy(fontFeatureSettings = "tnum"),
            color = Palette.textPrimary,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.58f),
        )
    }
}
