package com.noop.analytics

/**
 * Apple-shaped breathing-disturbance *product* on locked v6 events.
 *
 * Apple Watch SANF (K240929) reports a nightly Breathing Disturbances value in events/hour of
 * tracked sleep, labels the night Elevated or Not Elevated, and looks across ~30 days for
 * consistent elevation (publicly: accelerometer during sleep; notify after enough nights).
 * That algorithm, cutoff, and clearance are Apple's. This object copies the **output shape
 * only** onto WHOOP 4's one-hertz DSP candidates. It does not use Apple's classifier, does
 * not match Apple's unpublished nightly cutoff, and is not AHI, RDI, REI, ODI, or a diagnosis.
 *
 * Input modality is not Apple-equivalent: WHOOP 4 history is one reverse-engineered DSP row
 * per second, not a direct high-rate accelerometer stream. Event times come from locked
 * [Whoop4DisturbanceResearch] (v6). This layer only selects transient scored-sleep primary
 * events and applies frozen night/window labels. v6 thresholds are not retuned here.
 */
object Whoop4BreathingDisturbances {
    const val ALGORITHM_VERSION = "whoop4-breathing-disturbances-v8"
    const val BUNDLE_VERSION = 8
    const val MIN_EVENT_SECONDS = 10
    const val MAX_TRANSIENT_SECONDS = 120
    const val MIN_SCORED_SLEEP_HOURS = 2.0
    /**
     * Nightly Elevated stand-in. Apple's nightly cutoff is unpublished. The SANF *notification*
     * is aimed at signs of moderate-to-severe apnea (FDA summary). 15 events/hour is the
     * conventional moderate AHI boundary used as a **named research stand-in**, not Apple's
     * cutoff and not a fit to any WatchPAT night.
     */
    const val ELEVATED_PER_HOUR = 15.0
    const val EVALUATION_DAYS = 30
    const val MIN_NIGHTS_FOR_WINDOW = 10
    const val CONSISTENT_ELEVATED_FRACTION = 0.5

    enum class NightLabel {
        INSUFFICIENT,
        NOT_ELEVATED,
        ELEVATED,
    }

    enum class WindowLabel {
        INSUFFICIENT,
        NO_CONSISTENT_ELEVATION,
        CONSISTENT_ELEVATION,
    }

    data class Candidate(
        val startTs: Long,
        val endTs: Long,
        val durationSeconds: Int,
        val inScoredSleep: Boolean,
        val fallbackUsed: Boolean,
    )

    data class NightAssessment(
        val algorithmVersion: String = ALGORITHM_VERSION,
        val sessionStartTs: Long,
        val sessionEndTs: Long,
        val scoredSleepHours: Double?,
        val candidateEvents: Int,
        val ratePerHour: Double?,
        val label: NightLabel,
        val elevatedThresholdPerHour: Double = ELEVATED_PER_HOUR,
    )

    data class WindowAssessment(
        val algorithmVersion: String = ALGORITHM_VERSION,
        val windowDays: Int = EVALUATION_DAYS,
        val eligibleNights: Int,
        val elevatedNights: Int,
        val label: WindowLabel,
        val minNights: Int = MIN_NIGHTS_FOR_WINDOW,
        val consistentFraction: Double = CONSISTENT_ELEVATED_FRACTION,
    )

    fun isTransientPrimary(candidate: Candidate): Boolean {
        if (!candidate.inScoredSleep) return false
        if (candidate.fallbackUsed) return false
        return candidate.durationSeconds in MIN_EVENT_SECONDS..MAX_TRANSIENT_SECONDS
    }

    fun assessNight(
        sessionStartTs: Long,
        sessionEndTs: Long,
        scoredSleepHours: Double?,
        candidates: List<Candidate>,
    ): NightAssessment {
        val kept = candidates.filter(::isTransientPrimary)
        val hours = scoredSleepHours
        if (hours == null || hours < MIN_SCORED_SLEEP_HOURS) {
            return NightAssessment(
                sessionStartTs = sessionStartTs,
                sessionEndTs = sessionEndTs,
                scoredSleepHours = hours,
                candidateEvents = kept.size,
                ratePerHour = null,
                label = NightLabel.INSUFFICIENT,
            )
        }
        val rate = kept.size / hours
        val label = if (rate >= ELEVATED_PER_HOUR) NightLabel.ELEVATED else NightLabel.NOT_ELEVATED
        return NightAssessment(
            sessionStartTs = sessionStartTs,
            sessionEndTs = sessionEndTs,
            scoredSleepHours = hours,
            candidateEvents = kept.size,
            ratePerHour = rate,
            label = label,
        )
    }

    fun assessWindow(
        nights: List<NightAssessment>,
        windowEndTs: Long,
    ): WindowAssessment {
        val windowStartTs = windowEndTs - EVALUATION_DAYS * 86_400L
        val inWindow = nights.filter { it.sessionEndTs in (windowStartTs + 1)..windowEndTs }
        val eligible = inWindow.filter { it.label != NightLabel.INSUFFICIENT }
        val elevated = eligible.count { it.label == NightLabel.ELEVATED }
        val label = when {
            eligible.size < MIN_NIGHTS_FOR_WINDOW -> WindowLabel.INSUFFICIENT
            elevated >= CONSISTENT_ELEVATED_FRACTION * eligible.size ->
                WindowLabel.CONSISTENT_ELEVATION
            else -> WindowLabel.NO_CONSISTENT_ELEVATION
        }
        return WindowAssessment(
            eligibleNights = eligible.size,
            elevatedNights = elevated,
            label = label,
        )
    }

    fun fromV6Events(
        sessionStartTs: Long,
        sessionEndTs: Long,
        scoredSleepHours: Double?,
        events: List<Whoop4DisturbanceResearch.EventCandidate>,
    ): NightAssessment = assessNight(
        sessionStartTs = sessionStartTs,
        sessionEndTs = sessionEndTs,
        scoredSleepHours = scoredSleepHours,
        candidates = events.map {
            Candidate(
                startTs = it.startTs,
                endTs = it.endTs,
                durationSeconds = it.durationSeconds,
                // Unknown sleep state (null) is not scored sleep. Conservative, matches the
                // snapshot's scoredEvents filter; do not treat null as in-sleep.
                inScoredSleep = it.inScoredSleep == true,
                fallbackUsed = it.fallbackUsed,
            )
        },
    )
}
