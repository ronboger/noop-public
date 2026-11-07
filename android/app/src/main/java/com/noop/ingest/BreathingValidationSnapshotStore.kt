package com.noop.ingest

import android.content.Context
import android.content.SharedPreferences
import com.noop.analytics.Whoop4BreathingDisturbances
import com.noop.analytics.Whoop4DisturbanceResearch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small, display-only snapshot of one already-generated validation bundle.
 *
 * Raw samples and detector traces stay in the ZIP. This store keeps only enough derived metadata for
 * the Sleep tab, avoiding a 100k-row analysis or ZIP parse whenever that screen opens.
 */
data class BreathingValidationSnapshot(
    val sessionStartTs: Long,
    val sessionEndTs: Long,
    val generatedAtMs: Long,
    val status: String,
    val algorithmVersion: String,
    val bundleVersion: Int,
    val appVersionCode: Int,
    val inputFingerprint: String,
    val primarySignal: String?,
    val scoredSleepHours: Double?,
    val broadScored: Tier?,
    val evidenceScored: Tier?,
    val autonomicScored: Tier?,
    val broadAllValid: Tier,
    val evidenceAllValid: Tier,
    val autonomicAllValid: Tier,
    val guardedFallbackScored: Int,
    val retainedSplitScored: Int,
    val suppressedSplitScored: Int,
    val independentModalityScored: Int,
    val recoveryMotionScored: Int,
    val nearRotationScored: Int,
    val largeRotations: Int,
    val primaryUsableWindows: Int?,
    val primaryRhythmicWindows: Int?,
    val primaryLongestGapSeconds: Int?,
    val events: List<EventTick>,
    /** Apple-shaped v8 labels derived from locked v6 events. Null on snapshots written before v8. */
    val bdAlgorithmVersion: String? = null,
    val bdCandidateEvents: Int? = null,
    val bdRatePerHour: Double? = null,
    val bdLabel: String? = null,
    /** accel-bd-v0 accelerometer-only research index. Null on snapshots written before it existed or when no motion. */
    val accelAlgorithmVersion: String? = null,
    val accelIndexPerHour: Double? = null,
    val accelCalibratedPerHour: Double? = null,
    val accelBurstsPerHour: Double? = null,
    val accelSleepEpochs: Int? = null,
) {
    data class Tier(
        val candidateEvents: Int,
        val denominatorHours: Double,
        val ratePerHour: Double?,
    )

    data class EventTick(
        val startTs: Long,
        val endTs: Long,
        val evidenceSupported: Boolean,
        val autonomicSupported: Boolean,
        val durationSeconds: Int = 0,
        val fallbackUsed: Boolean = false,
    )

    val identity: String
        get() = listOf(
            sessionStartTs,
            sessionEndTs,
            inputFingerprint,
            algorithmVersion,
            bundleVersion,
            appVersionCode,
        ).joinToString("|")

    val artifactKey: String
        get() = ValidationDriveUpload.artifactKey(
            inputFingerprintSha256 = inputFingerprint,
            algorithmVersion = algorithmVersion,
            bundleVersion = bundleVersion,
            appVersionCode = appVersionCode,
        )

    companion object {
        fun fromAnalysis(
            analysis: Whoop4DisturbanceResearch.Result,
            sessionStartTs: Long,
            sessionEndTs: Long,
            bundleVersion: Int,
            appVersionCode: Int,
            generatedAtMs: Long = System.currentTimeMillis(),
            accel: AccelBreathingDisturbanceExport.Output? = null,
        ): BreathingValidationSnapshot {
            fun tier(rate: Whoop4DisturbanceResearch.RateEstimate): Tier =
                Tier(rate.candidateEvents, rate.denominatorHours, rate.ratePerHour)

            fun optionalTier(rate: Whoop4DisturbanceResearch.RateEstimate?): Tier? =
                rate?.let(::tier)

            val scoredEvents = analysis.events.filter { it.inScoredSleep == true }
            val suppressedScored = analysis.suppressedSplitEvents.count {
                it.inScoredSleep == true
            }
            val primaryDiagnostic = analysis.channelDiagnostics.firstOrNull {
                it.label == analysis.primarySignal
            }
            val bd = Whoop4BreathingDisturbances.fromV6Events(
                sessionStartTs = sessionStartTs,
                sessionEndTs = sessionEndTs,
                scoredSleepHours = analysis.provenance.scoredSleepHours,
                events = analysis.events,
            )
            return BreathingValidationSnapshot(
                sessionStartTs = sessionStartTs,
                sessionEndTs = sessionEndTs,
                generatedAtMs = generatedAtMs,
                status = analysis.status.name,
                algorithmVersion = analysis.provenance.algorithmVersion,
                bundleVersion = bundleVersion,
                appVersionCode = appVersionCode,
                inputFingerprint = analysis.provenance.inputFingerprint,
                primarySignal = analysis.primarySignal,
                scoredSleepHours = analysis.provenance.scoredSleepHours,
                broadScored = optionalTier(analysis.scoredSleepRate),
                evidenceScored = optionalTier(analysis.highConfidenceScoredSleepRate),
                autonomicScored = optionalTier(analysis.autonomicScoredSleepRate),
                broadAllValid = tier(analysis.allValidRate),
                evidenceAllValid = tier(analysis.highConfidenceAllValidRate),
                autonomicAllValid = tier(analysis.autonomicAllValidRate),
                guardedFallbackScored = scoredEvents.count { it.fallbackUsed },
                retainedSplitScored = scoredEvents.count { it.splitFromOverlong },
                suppressedSplitScored = suppressedScored,
                independentModalityScored = scoredEvents.count {
                    it.independentModalitySupport > 0
                },
                recoveryMotionScored = scoredEvents.count { it.recoveryMotion },
                nearRotationScored = scoredEvents.count { it.nearLargeWristRotation },
                largeRotations = analysis.positionProxy.largeRotations,
                primaryUsableWindows = primaryDiagnostic?.usableWindows,
                primaryRhythmicWindows = primaryDiagnostic?.rhythmicWindows,
                primaryLongestGapSeconds = primaryDiagnostic?.longestSourceGapSeconds,
                events = scoredEvents.map {
                    EventTick(
                        startTs = it.startTs,
                        endTs = it.endTs,
                        evidenceSupported = it.highConfidence,
                        autonomicSupported = it.autonomicSupport,
                        durationSeconds = it.durationSeconds,
                        fallbackUsed = it.fallbackUsed,
                    )
                },
                bdAlgorithmVersion = bd.algorithmVersion,
                bdCandidateEvents = bd.candidateEvents,
                bdRatePerHour = bd.ratePerHour,
                bdLabel = bd.label.name,
                accelAlgorithmVersion = accel?.result?.algorithmVersion,
                accelIndexPerHour = accel?.result?.indexPerAsleepHour,
                accelCalibratedPerHour = accel?.result?.calibratedSahi3Like,
                accelBurstsPerHour = accel?.result?.burstsPerAsleepHour,
                accelSleepEpochs = accel?.result?.sleepEpochs,
            )
        }
    }
}

object BreathingValidationSnapshotStore {
    private const val FILE = "breathing_validation_snapshots"
    private const val KEY = "snapshots"
    private const val MAX_SNAPSHOTS = 30
    private val lock = Any()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun all(context: Context): List<BreathingValidationSnapshot> =
        decodeList(prefs(context).getString(KEY, null))

    fun forSession(
        snapshots: List<BreathingValidationSnapshot>,
        sessionStartTs: Long,
        sessionEndTs: Long,
    ): BreathingValidationSnapshot? {
        val exact = snapshots.asSequence()
            .filter {
                it.sessionStartTs == sessionStartTs && it.sessionEndTs == sessionEndTs
            }
            .maxByOrNull { it.generatedAtMs }
        if (exact != null) return exact
        // A bed/wake edit or bridged-night group changes the displayed window by minutes, not
        // the identity of the night. Exact matching then hid last night's apnea candidates.
        // Require the overlap to cover most of BOTH windows so a nap snapshot cannot stand in
        // for the main night (or vice versa).
        return snapshots.asSequence()
            .mapNotNull { snapshot ->
                val overlap = overlapSeconds(
                    snapshot.sessionStartTs,
                    snapshot.sessionEndTs,
                    sessionStartTs,
                    sessionEndTs,
                ) ?: return@mapNotNull null
                val requested = sessionEndTs - sessionStartTs
                val stored = snapshot.sessionEndTs - snapshot.sessionStartTs
                if (requested <= 0L || stored <= 0L) return@mapNotNull null
                if (overlap < requested * OVERLAP_FRACTION) return@mapNotNull null
                if (overlap < stored * OVERLAP_FRACTION) return@mapNotNull null
                snapshot to overlap
            }
            .maxWithOrNull(compareBy({ it.second }, { it.first.generatedAtMs }))
            ?.first
    }

    internal fun overlapSeconds(
        startA: Long,
        endA: Long,
        startB: Long,
        endB: Long,
    ): Long? {
        val start = maxOf(startA, startB)
        val end = minOf(endA, endB)
        return if (end > start) end - start else null
    }

    private const val OVERLAP_FRACTION = 0.6

    fun save(context: Context, snapshot: BreathingValidationSnapshot) {
        synchronized(lock) {
            val merged = (listOf(snapshot) + all(context).filterNot {
                it.identity == snapshot.identity
            })
                .sortedByDescending { it.generatedAtMs }
                .take(MAX_SNAPSHOTS)
            prefs(context).edit().putString(KEY, encodeList(merged)).apply()
        }
    }

    /**
     * Observe only this store's compact JSON. Returns an unregister callback for DisposableEffect.
     */
    fun observe(
        context: Context,
        onChanged: (List<BreathingValidationSnapshot>) -> Unit,
    ): () -> Unit {
        val preferences = prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY) onChanged(all(context))
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    internal fun encodeList(snapshots: List<BreathingValidationSnapshot>): String =
        JSONArray().apply {
            snapshots.forEach { put(it.toJson()) }
        }.toString()

    internal fun decodeList(raw: String?): List<BreathingValidationSnapshot> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    runCatching { array.getJSONObject(index).toSnapshot() }
                        .getOrNull()
                        ?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }
}

private fun BreathingValidationSnapshot.toJson(): JSONObject {
    fun tierJson(tier: BreathingValidationSnapshot.Tier): JSONObject =
        JSONObject()
            .put("candidate_events", tier.candidateEvents)
            .put("denominator_hours", tier.denominatorHours)
            .put("rate_per_hour", tier.ratePerHour ?: JSONObject.NULL)

    fun JSONObject.putTier(
        key: String,
        tier: BreathingValidationSnapshot.Tier?,
    ): JSONObject = put(key, tier?.let(::tierJson) ?: JSONObject.NULL)

    return JSONObject()
        .put("session_start_ts", sessionStartTs)
        .put("session_end_ts", sessionEndTs)
        .put("generated_at_ms", generatedAtMs)
        .put("status", status)
        .put("algorithm_version", algorithmVersion)
        .put("bundle_version", bundleVersion)
        .put("app_version_code", appVersionCode)
        .put("input_fingerprint", inputFingerprint)
        .put("primary_signal", primarySignal ?: JSONObject.NULL)
        .put("scored_sleep_hours", scoredSleepHours ?: JSONObject.NULL)
        .putTier("broad_scored", broadScored)
        .putTier("evidence_scored", evidenceScored)
        .putTier("autonomic_scored", autonomicScored)
        .putTier("broad_all_valid", broadAllValid)
        .putTier("evidence_all_valid", evidenceAllValid)
        .putTier("autonomic_all_valid", autonomicAllValid)
        .put("guarded_fallback_scored", guardedFallbackScored)
        .put("retained_split_scored", retainedSplitScored)
        .put("suppressed_split_scored", suppressedSplitScored)
        .put("independent_modality_scored", independentModalityScored)
        .put("recovery_motion_scored", recoveryMotionScored)
        .put("near_rotation_scored", nearRotationScored)
        .put("large_rotations", largeRotations)
        .put("primary_usable_windows", primaryUsableWindows ?: JSONObject.NULL)
        .put("primary_rhythmic_windows", primaryRhythmicWindows ?: JSONObject.NULL)
        .put("primary_longest_gap_seconds", primaryLongestGapSeconds ?: JSONObject.NULL)
        .put(
            "bd_algorithm_version", bdAlgorithmVersion ?: JSONObject.NULL,
        )
        .put("bd_candidate_events", bdCandidateEvents ?: JSONObject.NULL)
        .put("bd_rate_per_hour", bdRatePerHour ?: JSONObject.NULL)
        .put("bd_label", bdLabel ?: JSONObject.NULL)
        .put("accel_algorithm_version", accelAlgorithmVersion ?: JSONObject.NULL)
        .put("accel_index_per_hour", accelIndexPerHour ?: JSONObject.NULL)
        .put("accel_calibrated_per_hour", accelCalibratedPerHour ?: JSONObject.NULL)
        .put("accel_bursts_per_hour", accelBurstsPerHour ?: JSONObject.NULL)
        .put("accel_sleep_epochs", accelSleepEpochs ?: JSONObject.NULL)
        .put(
            "events",
            JSONArray().apply {
                events.forEach {
                    put(
                        JSONObject()
                            .put("start_ts", it.startTs)
                            .put("end_ts", it.endTs)
                            .put("evidence_supported", it.evidenceSupported)
                            .put("autonomic_supported", it.autonomicSupported)
                            .put("duration_seconds", it.durationSeconds)
                            .put("fallback_used", it.fallbackUsed),
                    )
                }
            },
        )
}

private fun JSONObject.toSnapshot(): BreathingValidationSnapshot {
    fun tier(key: String): BreathingValidationSnapshot.Tier? {
        if (isNull(key)) return null
        val value = getJSONObject(key)
        return BreathingValidationSnapshot.Tier(
            candidateEvents = value.getInt("candidate_events"),
            denominatorHours = value.getDouble("denominator_hours"),
            ratePerHour = if (value.isNull("rate_per_hour")) {
                null
            } else {
                value.getDouble("rate_per_hour")
            },
        )
    }

    val eventArray = optJSONArray("events") ?: JSONArray()
    val events = buildList {
        for (index in 0 until eventArray.length()) {
            val event = eventArray.getJSONObject(index)
            add(
                BreathingValidationSnapshot.EventTick(
                    startTs = event.getLong("start_ts"),
                    // Snapshots written before stage correlation stored only the start. Treat
                    // those as point events so they remain usable and are classified by the stage
                    // containing that timestamp.
                    endTs = event.optLong("end_ts", event.getLong("start_ts")),
                    evidenceSupported = event.optBoolean("evidence_supported"),
                    autonomicSupported = event.optBoolean("autonomic_supported"),
                    durationSeconds = event.optInt("duration_seconds", 0),
                    fallbackUsed = event.optBoolean("fallback_used"),
                ),
            )
        }
    }
    return BreathingValidationSnapshot(
        sessionStartTs = getLong("session_start_ts"),
        sessionEndTs = getLong("session_end_ts"),
        generatedAtMs = getLong("generated_at_ms"),
        status = getString("status"),
        algorithmVersion = getString("algorithm_version"),
        bundleVersion = getInt("bundle_version"),
        appVersionCode = getInt("app_version_code"),
        inputFingerprint = getString("input_fingerprint"),
        primarySignal = if (isNull("primary_signal")) null else getString("primary_signal"),
        scoredSleepHours = if (isNull("scored_sleep_hours")) {
            null
        } else {
            getDouble("scored_sleep_hours")
        },
        broadScored = tier("broad_scored"),
        evidenceScored = tier("evidence_scored"),
        autonomicScored = tier("autonomic_scored"),
        broadAllValid = requireNotNull(tier("broad_all_valid")),
        evidenceAllValid = requireNotNull(tier("evidence_all_valid")),
        autonomicAllValid = requireNotNull(tier("autonomic_all_valid")),
        guardedFallbackScored = getInt("guarded_fallback_scored"),
        retainedSplitScored = getInt("retained_split_scored"),
        suppressedSplitScored = getInt("suppressed_split_scored"),
        independentModalityScored = getInt("independent_modality_scored"),
        recoveryMotionScored = getInt("recovery_motion_scored"),
        nearRotationScored = getInt("near_rotation_scored"),
        largeRotations = getInt("large_rotations"),
        primaryUsableWindows = if (isNull("primary_usable_windows")) {
            null
        } else {
            getInt("primary_usable_windows")
        },
        primaryRhythmicWindows = if (isNull("primary_rhythmic_windows")) {
            null
        } else {
            getInt("primary_rhythmic_windows")
        },
        primaryLongestGapSeconds = if (isNull("primary_longest_gap_seconds")) {
            null
        } else {
            getInt("primary_longest_gap_seconds")
        },
        events = events,
        bdAlgorithmVersion = if (isNull("bd_algorithm_version") || !has("bd_algorithm_version")) {
            null
        } else {
            optString("bd_algorithm_version").ifBlank { null }
        },
        bdCandidateEvents = if (isNull("bd_candidate_events") || !has("bd_candidate_events")) {
            null
        } else {
            getInt("bd_candidate_events")
        },
        bdRatePerHour = if (isNull("bd_rate_per_hour") || !has("bd_rate_per_hour")) {
            null
        } else {
            getDouble("bd_rate_per_hour")
        },
        bdLabel = if (isNull("bd_label") || !has("bd_label")) {
            null
        } else {
            optString("bd_label").ifBlank { null }
        },
        accelAlgorithmVersion = if (!has("accel_algorithm_version") || isNull("accel_algorithm_version")) null else getString("accel_algorithm_version"),
        accelIndexPerHour = if (!has("accel_index_per_hour") || isNull("accel_index_per_hour")) null else getDouble("accel_index_per_hour"),
        accelCalibratedPerHour = if (!has("accel_calibrated_per_hour") || isNull("accel_calibrated_per_hour")) null else getDouble("accel_calibrated_per_hour"),
        accelBurstsPerHour = if (!has("accel_bursts_per_hour") || isNull("accel_bursts_per_hour")) null else getDouble("accel_bursts_per_hour"),
        accelSleepEpochs = if (!has("accel_sleep_epochs") || isNull("accel_sleep_epochs")) null else getInt("accel_sleep_epochs"),
    )
}
