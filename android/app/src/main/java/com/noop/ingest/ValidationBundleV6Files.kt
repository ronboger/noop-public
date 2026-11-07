package com.noop.ingest

import android.os.Build
import com.noop.BuildConfig
import com.noop.analytics.SleepStageTotals
import com.noop.analytics.SleepStager
import com.noop.analytics.SleepStagerV2
import com.noop.analytics.Whoop4DisturbanceResearch
import com.noop.data.GravitySample
import com.noop.data.HrSample
import com.noop.data.RespSample
import com.noop.data.RrInterval
import com.noop.data.SleepStateSampleEntity
import com.noop.data.Whoop4ResearchSampleEntity
import com.noop.data.Whoop4RrPacketEntity
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Pure, deterministic file/schema construction for the v6 ZIP. */
internal object ValidationBundleV6Files {
    fun rawSamplesCsv(rows: List<Whoop4ResearchSampleEntity>): String = buildString {
        appendLine("# NOOP private WHOOP 4 respiratory-burden research export")
        appendLine("# Raw/experimental signals only. Not AHI, RDI, SpO2 percentage, or a diagnosis.")
        appendLine("# capture_ordinal_within_chunk is decoder input order, not a global sequence or beat timestamp.")
        appendLine("unix_s,layout_version,motion_candidate_1_x,motion_candidate_1_y,motion_candidate_1_z,motion_candidate_2_x,motion_candidate_2_y,motion_candidate_2_z,ppg_green,ppg_red_ir,spo2_red,spo2_ir,resp_raw,skin_contact,signal_quality,ambient,led_drive_1,led_drive_2,source_unix_s,capture_ordinal_within_chunk,rr_intervals_json")
        rows.forEach { row ->
            val scalarValues = listOf(
                row.ts, row.layoutVersion, row.motion1X, row.motion1Y, row.motion1Z,
                row.motion2X, row.motion2Y, row.motion2Z, row.ppgGreen, row.ppgRedIR,
                row.spo2Red, row.spo2IR, row.respirationRaw, row.skinContact,
                row.signalQuality, row.ambient, row.ledDrive1, row.ledDrive2,
                row.sourceUnixS, row.captureOrdinal,
            ).joinToString(",") { it?.toString() ?: "" }
            appendLine(
                scalarValues + "," +
                    (row.rrIntervalsJson?.let(WhoopCsvExporter::csvField) ?: ""),
            )
        }
    }

    fun autonomicSamplesCsv(
        hr: List<HrSample>,
        rr: List<RrInterval>,
        packets: List<Whoop4RrPacketEntity> = emptyList(),
    ): String = buildString {
        appendLine("unix_s,stream,value,source_unix_s,packet_ordinal_within_capture,rr_index_within_packet,signal_quality,representation")
        hr.sortedBy { it.ts }.forEach {
            appendLine("${it.ts},heart_rate_bpm,${it.bpm},,,,,one_value_per_second")
        }

        // Packet-level R-R stream (v20+). The packet table keys by (deviceId, ts, captureOrdinal,
        // packetType), so same-second duplicates survive here that the (deviceId, ts)-keyed research
        // table dropped. For pre-v20 captures with no packet rows, the caller synthesizes equivalent
        // rows from the research table (see RawSensorExport), so this one code path serves both.
        // Every packet row carries rrIntervalsJson (possibly "[]"), so a genuinely-empty packet is
        // distinguishable from one that was lost.
        val packetRows = packets.sortedWith(compareBy({ it.ts }, { it.captureOrdinal }))
        val packetIntervalsPerSecond = HashMap<Long, Int>()
        packetRows.forEach { packet ->
            val intervals = parseRrIntervalsJson(packet.rrIntervalsJson, packet.ts)
            packetIntervalsPerSecond.merge(packet.ts, intervals.size, Int::plus)
            // source_unix_s is the strap/device timestamp BEFORE gross-clock correction: real-unix for
            // type-47 HISTORICAL packets, device-epoch for type-43 REALTIME_RAW_DATA fallbacks — the
            // representation tag labels the clock domain so it is never read as wall-clock blindly.
            val representation = if (packet.packetType == 43)
                "packet_order_preserved_source_ts_device_epoch"
            else
                "packet_order_preserved_no_subsecond_timestamps"
            intervals.forEachIndexed { intervalIndex, rrMs ->
                appendLine(
                    listOf(
                        packet.ts,
                        "rr_interval_ms",
                        rrMs,
                        packet.sourceUnixS?.toString() ?: "",
                        packet.captureOrdinal,
                        intervalIndex,
                        packet.signalQuality ?: "",
                        representation,
                    ).joinToString(","),
                )
            }
        }
        // Suppress a legacy rrInterval row ONLY when the retained packets demonstrably cover its
        // second: packet interval count >= legacy row count for that second. A second whose packets
        // are all empty ("[]") or partially lost keeps its legacy rows, explicitly labelled — never
        // silently dropped in favour of an empty packet (the v18 export bug).
        val legacyCountPerSecond = rr.groupingBy { it.ts }.eachCount()
        rr.filter { (packetIntervalsPerSecond[it.ts] ?: 0) < (legacyCountPerSecond[it.ts] ?: 0) }
            .sortedWith(compareBy<RrInterval> { it.ts }.thenBy { it.rrMs })
            .forEach {
                val representation = if (packetIntervalsPerSecond.containsKey(it.ts))
                    "legacy_partially_packet_covered_order_unknown"
                else
                    "legacy_second_bucket_order_unknown"
                appendLine("${it.ts},rr_interval_ms,${it.rrMs},,,,,$representation")
            }
    }

    fun stagingInputsCsv(
        gravity: List<GravitySample>,
        resp: List<RespSample>,
        bandSleepState: List<Pair<Long, Int>>,
    ): String = buildString {
        appendLine("unix_s,stream,value_1,value_2,value_3")
        gravity.sortedBy { it.ts }.forEach {
            appendLine("${it.ts},gravity,${n(it.x)},${n(it.y)},${n(it.z)}")
        }
        resp.sortedBy { it.ts }.forEach { appendLine("${it.ts},respiration_raw,${it.raw},,") }
        bandSleepState.sortedBy { it.first }.forEach { appendLine("${it.first},band_sleep_state,${it.second},,") }
    }

    fun continuityGapsCsv(rows: List<Whoop4ResearchSampleEntity>): String = buildString {
        appendLine("stream,previous_unix_s,next_unix_s,missing_seconds")
        rows.asSequence().map { it.ts }.distinct().sorted().zipWithNext().forEach { (a, b) ->
            if (b - a > 1L) appendLine("whoop4_research_rows,$a,$b,${b - a - 1L}")
        }
    }

    fun detectorSamplesCsv(analysis: Whoop4DisturbanceResearch.Result): String = buildString {
        appendLine("unix_s,envelope,baseline,amplitude_reduction,motion_state,valid_for_detection,in_rhythmic_window,in_scored_sleep")
        analysis.detectorSamples.forEach { sample ->
            appendLine(listOf(
                sample.ts,
                sample.envelope?.let(::n) ?: "",
                sample.baseline?.let(::n) ?: "",
                sample.amplitudeReduction?.let { String.format(Locale.US, "%.6f", it) } ?: "",
                sample.motionState,
                sample.validForDetection,
                sample.inRhythmicWindow,
                sample.inScoredSleep ?: "",
            ).joinToString(","))
        }
    }

    fun wsrbSamplesCsv(export: ValidationBundleV6.Export): String = buildString {
        appendLine("unix_s,envelope,frozen_onset_baseline,amplitude_reduction,source_valid,rhythmic_valid,frozen_stage_valid,wsrb_contribution_pct_minutes")
        export.samples.forEach { sample ->
            appendLine(listOf(
                sample.ts,
                sample.envelope?.let(::n) ?: "",
                sample.frozenOnsetBaseline?.let(::n) ?: "",
                sample.amplitudeReduction?.let { String.format(Locale.US, "%.6f", it) } ?: "",
                sample.sourceValid,
                sample.rhythmicValid,
                sample.frozenStageValid,
                n(sample.wsrbContributionPctMinutes),
            ).joinToString(","))
        }
    }

    fun candidateEventsCsv(export: ValidationBundleV6.Export): String = buildString {
        appendLine("start_unix_s,end_unix_s,duration_s,event_class,amplitude_reduction,recovery_motion_present,nominal_hr_or_rr_response_present,hr_response_present,rr_response_present,independent_respiratory_source_present,independent_source_support,support_feature_count,provenance_groups,source_signal,in_rhythmic_window,in_frozen_stage,near_large_wrist_rotation,guarded_fallback,wsrb_contribution_pct_minutes")
        export.events.forEach { event ->
            appendLine(listOf(
                event.startTs, event.endTs, event.durationSeconds, event.eventClass,
                String.format(Locale.US, "%.6f", event.amplitudeReduction),
                event.recoveryMotionPresent, event.nominalHrOrRrResponsePresent,
                event.hrResponsePresent, event.rrResponsePresent,
                event.independentRespiratorySourcePresent, event.independentSourceSupport,
                event.supportFeatureCount,
                WhoopCsvExporter.csvField(event.provenanceGroups.sorted().joinToString("|")),
                WhoopCsvExporter.csvField(event.sourceSignal), event.inRhythmicWindow,
                event.inFrozenStage ?: "", event.nearLargeWristRotation, event.fallbackUsed,
                n(event.wsrbContributionPctMinutes),
            ).joinToString(","))
        }
    }

    fun diagnosticSupportCsv(export: ValidationBundleV6.Export): String = buildString {
        appendLine("start_unix_s,end_unix_s,recovery_motion_present,nominal_hr_or_rr_response_present,hr_response_present,rr_response_present,independent_respiratory_source_present,independent_source_support,support_feature_count,provenance_groups,diagnostic_only")
        export.events.forEach { event ->
            appendLine(listOf(
                event.startTs, event.endTs, event.recoveryMotionPresent,
                event.nominalHrOrRrResponsePresent, event.hrResponsePresent,
                event.rrResponsePresent, event.independentRespiratorySourcePresent,
                event.independentSourceSupport, event.supportFeatureCount,
                WhoopCsvExporter.csvField(event.provenanceGroups.sorted().joinToString("|")), true,
            ).joinToString(","))
        }
    }

    fun rejectedEventsCsv(analysis: Whoop4DisturbanceResearch.Result): String = buildString {
        appendLine("start_unix_s,end_unix_s,duration_s,amplitude_reduction,moving_seconds,rejection_reason")
        analysis.detectorEpisodes.filter { it.acceptedEvents == 0 }.forEach { episode ->
            appendLine(listOf(
                episode.startTs, episode.endTs, episode.durationSeconds,
                episode.amplitudeReduction?.let { String.format(Locale.US, "%.6f", it) } ?: "",
                episode.movingSeconds, episode.outcome.name,
            ).joinToString(","))
        }
    }

    fun durationDistributions(export: ValidationBundleV6.Export): String {
        val atomic = export.events.filter { it.eventClass == "atomic" }
        val prolonged = export.events.filter { it.eventClass == "prolonged" }
        return JSONObject()
            .put("all_events", JSONObject(ValidationBundleV6.durationDistribution(export.events)))
            .put("atomic_events", JSONObject(ValidationBundleV6.durationDistribution(atomic)))
            .put("prolonged_events", JSONObject(ValidationBundleV6.durationDistribution(prolonged)))
            .put("burden_components", JSONObject()
                .put("atomic_numerator_pct_minutes", export.atomicWsrbNumeratorPctMinutes)
                .put("prolonged_numerator_pct_minutes", export.prolongedWsrbNumeratorPctMinutes)
                .put("source_valid_hours", export.sourceValidHours)
                .put("atomic_wsrb_pct_minutes_per_hour", export.atomicWsrb ?: JSONObject.NULL)
                .put("prolonged_wsrb_pct_minutes_per_hour", export.prolongedWsrb ?: JSONObject.NULL))
            .toString(2)
    }

    fun provenanceGroups(export: ValidationBundleV6.Export): String =
        JSONObject(export.provenanceGroups)
            .put("independence_rule", "Correlated axes within one group never count independently.")
            .put("vendor_dsp_independence", "Unknown until input provenance is documented.")
            .toString(2)

    fun summary(
        contextPackage: String,
        publicName: String,
        analysis: Whoop4DisturbanceResearch.Result,
        export: ValidationBundleV6.Export,
        from: Long,
        to: Long,
        currentStagesJson: String,
        v1StagesJson: String,
        v2StagesJson: String,
        bandSleepState: List<Pair<Long, Int>>,
        bandMask: BooleanArray,
        appVersionName: String,
        appVersionCode: Int,
    ): String {
        val provenance = analysis.provenance
        fun stageSummary(json: String): Any {
            val minutes = SleepStageTotals.minutes(json) ?: return JSONObject.NULL
            return JSONObject()
                .put("in_bed_minutes", minutes.inBed)
                .put("asleep_minutes", minutes.asleep)
                .put("wake_minutes", minutes.awake)
                .put("efficiency", if (minutes.inBed > 0.0) minutes.asleep / minutes.inBed else JSONObject.NULL)
        }
        val counts = export.events.groupingBy { it.eventClass }.eachCount()
        val summary = JSONObject()
            .put("schema_version", "validation-bundle-v6")
            .put("warning", "Research-only; not ventilation, AHI, RDI, ODI, calibrated SpO2, or a diagnosis")
            .put("app_version_name", appVersionName)
            .put("app_version_code", appVersionCode)
            // Stamped with the running app — not the historical freeze constants on ValidationBundleV6.
            .put("required_app_version_name", appVersionName)
            .put("required_app_version_code", appVersionCode)
            .put("app_tier", BuildConfig.TIER)
            .put("android_release", Build.VERSION.RELEASE)
            .put("android_sdk", Build.VERSION.SDK_INT)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("status", analysis.status.name)
            .put("reason", analysis.reason)
            .put("algorithm_version", ValidationBundleV6.ALGORITHM_VERSION)
            .put("input_fingerprint_sha256", provenance.inputFingerprint)
            .put("input_start_unix_s", provenance.inputStartTs ?: JSONObject.NULL)
            .put("input_end_unix_s", provenance.inputEndTs ?: JSONObject.NULL)
            .put("input_rows", provenance.inputRows)
            .put("session_start_unix_s", provenance.sessionStartTs ?: from)
            .put("session_end_unix_s", provenance.sessionEndTs ?: to)
            .put("session_hours", provenance.sessionHours)
            .put("validation_bundle_version", ValidationBundleV6.BUNDLE_VERSION)
            .put("download_filename", publicName)
            .put("adb_relative_path", "Android/data/$contextPackage/files/validation/$publicName")
            .put("snapshot_metadata", JSONObject()
                .put("algorithm_version", ValidationBundleV6.ALGORITHM_VERSION)
                .put("bundle_version", ValidationBundleV6.BUNDLE_VERSION)
                .put("app_version_code", appVersionCode)
                .put("input_fingerprint_sha256", provenance.inputFingerprint)
                .put("session_start_unix_s", from)
                .put("session_end_unix_s", to)
                .put("deterministic_identity", listOf(
                    provenance.inputFingerprint,
                    ValidationBundleV6.ALGORITHM_VERSION,
                    ValidationBundleV6.BUNDLE_VERSION,
                    appVersionCode,
                    from,
                    to,
                ).joinToString("|")))
            .put("primary_endpoint", JSONObject()
                .put("name", "wrist_source_reduction_burden")
                .put("value", export.wsrb ?: JSONObject.NULL)
                .put("units", ValidationBundleV6.WSRB_UNIT)
                .put("numerator_pct_minutes", export.wsrbNumeratorPctMinutes)
                .put("denominator_source_valid_hours", export.sourceValidHours)
                .put("definition", "100 * sum(max(0, 1 - envelope / frozen_onset_baseline) * dt / 60) / H_source")
                .put("dt_seconds", ValidationBundleV6.SAMPLE_DT_SECONDS)
                .put("not_a_clinical_endpoint", true))
            .put("burden_components", JSONObject()
                .put("atomic_numerator_pct_minutes", export.atomicWsrbNumeratorPctMinutes)
                .put("prolonged_numerator_pct_minutes", export.prolongedWsrbNumeratorPctMinutes)
                .put("source_valid_hours", export.sourceValidHours)
                .put("atomic_wsrb_pct_minutes_per_hour", export.atomicWsrb ?: JSONObject.NULL)
                .put("prolonged_wsrb_pct_minutes_per_hour", export.prolongedWsrb ?: JSONObject.NULL)
                .put("prolonged_endpoint_is_descriptive", true))
            .put("denominators", JSONObject()
                .put("source_valid_hours", export.sourceValidHours)
                .put("rhythmic_valid_hours", export.rhythmicValidHours)
                .put("frozen_stage_conditioned_source_valid_hours", export.frozenStageValidHours)
                .put("session_hours", provenance.sessionHours)
                .put("rule", "Each burden/rate endpoint retains its own eligibility denominator."))
            .put("counts", JSONObject()
                .put("all_events", export.events.size)
                .put("atomic_events", counts["atomic"] ?: 0)
                .put("prolonged_events", counts["prolonged"] ?: 0)
                .put("rejected_episodes", analysis.detectorEpisodes.count { it.acceptedEvents == 0 }))
            .put("duration_distributions", JSONObject()
                .put("all_events", JSONObject(ValidationBundleV6.durationDistribution(export.events)))
                .put("atomic_events", JSONObject(ValidationBundleV6.durationDistribution(export.events.filter { it.eventClass == "atomic" })))
                .put("prolonged_events", JSONObject(ValidationBundleV6.durationDistribution(export.events.filter { it.eventClass == "prolonged" }))))
            .put("diagnostic_support", JSONObject()
                .put("support_feature_count", "recovery_motion + nominal_HR_or_RR_response + independent_respiratory_source")
                .put("diagnostic_only", true)
                .put("does_not_accept_reject_split_or_rank", true)
                .put("event_count_with_support", export.events.count { it.supportFeatureCount > 0 }))
            .put("shadow_respiratory_estimators", JSONObject()
                .put("riiv_like_ppg_green", JSONObject()
                    .put("provenance_group", "OPTICAL_INTENSITY")
                    .put("coverage", export.shadowCoverage.ppgGreenCoverage)
                    .put("longest_gap_seconds", export.shadowCoverage.ppgGreenLongestGapSeconds ?: JSONObject.NULL)
                    .put("interpolated_seconds", export.shadowCoverage.ppgGreenInterpolatedSeconds)
                    .put("continuity_rejected_windows", export.shadowCoverage.ppgGreenContinuityRejectedWindows)
                    .put("usable_windows", export.shadowCoverage.ppgGreenUsableWindows)
                    .put("rhythmic_windows", export.shadowCoverage.ppgGreenRhythmicWindows)
                    .put("rhythmic_window_fraction", export.shadowCoverage.ppgGreenRhythmicWindowFraction)
                    .put("aliasing_risk_windows", export.shadowCoverage.ppgGreenAliasingRiskWindows)
                    .put("band_hz", ValidationBundleV6.RIIV_RSA_BAND_HZ)
                    .put("one_value_per_second_aliasing_guard", "Report as RIIV-like rhythm only; no beat-level pulse amplitude."))
                .put("rsa_cleaned_rr", JSONObject()
                    .put("provenance_group", "CARDIAC_TIMING")
                    .put("coverage", export.shadowCoverage.rrCoverage)
                    .put("longest_gap_seconds", export.shadowCoverage.rrLongestGapSeconds ?: JSONObject.NULL)
                    .put("interpolated_seconds", export.shadowCoverage.rrInterpolatedSeconds)
                    .put("continuity_rejected_windows", export.shadowCoverage.rrContinuityRejectedWindows)
                    .put("usable_windows", export.shadowCoverage.rrUsableWindows)
                    .put("rhythmic_windows", export.shadowCoverage.rrRhythmicWindows)
                    .put("rhythmic_window_fraction", export.shadowCoverage.rrRhythmicWindowFraction)
                    .put("aliasing_risk_windows", export.shadowCoverage.rrAliasingRiskWindows)
                    .put("band_hz", ValidationBundleV6.RIIV_RSA_BAND_HZ))
                .put("independence_rule", "RIIV/RSA remain shadow-only and cannot accept/reject v6 events."))
            .put("provenance_groups", JSONObject(export.provenanceGroups))
            .put("coverage", JSONObject()
                .put("respiration_raw", analysis.coverage.respirationRaw)
                .put("ppg_green", analysis.coverage.ppgGreen)
                .put("ppg_red_ir", analysis.coverage.ppgRedIR)
                .put("raw_oximetry_pair", analysis.coverage.rawOximetryPair)
                .put("heart_rate", analysis.coverage.heartRate)
                .put("rr_intervals", analysis.coverage.rrIntervals)
                .put("motion_vector", analysis.coverage.motionVector))
            .put("sleep_staging", JSONObject()
                .put("current_stored", stageSummary(currentStagesJson))
                .put("v1_recomputed", stageSummary(v1StagesJson))
                .put("v2_recomputed", stageSummary(v2StagesJson))
                .put("v1_recipe_generation", SleepStager.v1RecipeGen)
                .put("v2_recipe_generation", SleepStagerV2.v2RecipeGen)
                .put("band_state_samples", bandSleepState.size)
                .put("band_wake_promoted_minutes", bandMask.count { it } * 0.5))
            .put("method", JSONObject()
                .put("primary_onset_fraction", 0.30)
                .put("ordinary_duration_seconds", "10-180 inclusive")
                .put("prolonged_duration_seconds", ">180")
                .put("partial_recovery_splitting", false)
                .put("evidence_score_decision_variable", false)
                .put("high_confidence_decision_variable", false)
                .put("source_selection_and_continuity", "Owned by the v6 model; exporter consumes frozen output only."))
            .put("integration_assumptions", JSONArray(listOf(
                "The v6 model preserves Result.detectorSamples envelope/baseline and Result.events timing fields.",
                "HR and RR responses are descriptive event fields; supportFeatureCount remains diagnostic-only.",
                "RIIV/RSA diagnostics are exported from the model shadow outputs and remain non-operative.",
            )))
        return summary.toString(2)
    }

    private fun n(value: Double): String = String.format(Locale.US, "%.9f", value)

    private fun parseRrIntervalsJson(json: String, ts: Long): List<Int> {
        val array = runCatching { JSONArray(json) }.getOrElse {
            error("Invalid rrIntervalsJson at unix_s=$ts")
        }
        return (0 until array.length()).map { index ->
            val value = array.opt(index)
            check(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toInt().toDouble()) {
                "rrIntervalsJson must contain only finite integers at unix_s=$ts index=$index"
            }
            value.toInt()
        }
    }
}
