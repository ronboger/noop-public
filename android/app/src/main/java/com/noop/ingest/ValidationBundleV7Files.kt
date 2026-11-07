package com.noop.ingest

import com.noop.analytics.Whoop4CrossDeviceCorrespondenceV7
import java.util.Locale

/** Strict, locale-independent, deterministic serializers for the v7 development export. */
object ValidationBundleV7Files {
    fun autonomicResponsesCsv(
        export: Whoop4CrossDeviceCorrespondenceV7.Export,
    ): String {
        ValidationBundleV7.requirePhaseIdentity(export)
        return buildString {
            appendLine(
                "start_unix_s,end_unix_s,duration_s,event_class,amplitude_reduction,source_signal," +
                    "baseline_hr_bpm,post_recovery_hr_peak_bpm,hr_rise_bpm,baseline_rr_ms," +
                    "post_recovery_rr_ms,rr_shortening_ms,baseline_hr_coverage,post_recovery_hr_coverage," +
                    "baseline_rr_coverage,post_recovery_rr_coverage,rr_availability,motion_confounder_fraction," +
                    "quality_coverage,confounder_penalty,autonomic_response_score,provenance_groups," +
                    "hr_rise_signed_bpm,rr_shortening_signed_ms",
            )
            export.autonomicResponses.forEach { response ->
                appendLine(listOf(
                    response.startTs,
                    response.endTs,
                    response.durationSeconds,
                    csv(response.eventClass),
                    number(response.amplitudeReduction),
                    csv(response.sourceSignal),
                    numberOrBlank(response.baselineHrBpm),
                    numberOrBlank(response.postRecoveryHrPeakBpm),
                    numberOrBlank(response.hrRiseBpm),
                    numberOrBlank(response.baselineRrMs),
                    numberOrBlank(response.postRecoveryRrMs),
                    numberOrBlank(response.rrShorteningMs),
                    number(response.baselineHrCoverage),
                    number(response.postRecoveryHrCoverage),
                    number(response.baselineRrCoverage),
                    number(response.postRecoveryRrCoverage),
                    response.rrAvailability.name,
                    numberOrBlank(response.motionConfounderFraction),
                    numberOrBlank(response.qualityCoverage),
                    number(response.confounderPenalty),
                    numberOrBlank(response.autonomicResponseScore),
                    csv(response.provenanceGroups.sortedBy { it.name }.joinToString("|")),
                    numberOrBlank(response.hrRiseSignedBpm),
                    numberOrBlank(response.rrShorteningSignedMs),
                ).joinToString(","))
            }
        }
    }

    fun fiveMinuteBurdenCsv(
        export: Whoop4CrossDeviceCorrespondenceV7.Export,
    ): String {
        ValidationBundleV7.requirePhaseIdentity(export)
        return buildString {
            appendLine(
                "bin_start_unix_s,bin_end_unix_s,wsrb_unweighted_numerator_pct_minutes," +
                    "wsrb_unweighted_denominator_seconds,cross_device_weighted_burden_numerator_pct_minutes," +
                    "cross_device_weighted_burden_denominator_seconds,hr_observed_seconds,rr_observed_seconds," +
                    "wsrb_unweighted_eligible,cross_device_weighted_burden_eligible",
            )
            export.fiveMinuteBins.sortedBy { it.startTs }.forEach { bin ->
                appendLine(listOf(
                    bin.startTs,
                    bin.endTs,
                    number(bin.wsrbUnweightedNumeratorPctMinutes),
                    bin.wsrbUnweightedDenominatorSeconds,
                    number(bin.crossDeviceWeightedBurdenNumeratorPctMinutes),
                    bin.crossDeviceWeightedBurdenDenominatorSeconds,
                    bin.hrObservedSeconds,
                    bin.rrObservedSeconds,
                    bin.wsrbUnweightedEligible,
                    bin.crossDeviceWeightedBurdenEligible,
                ).joinToString(","))
            }
        }
    }

    /** Canonical manifest with the phase identity and the two deliberately separate burden products. */
    fun manifestJson(
        export: Whoop4CrossDeviceCorrespondenceV7.Export,
    ): String {
        ValidationBundleV7.requirePhaseIdentity(export)
        val provenance = export.provenanceGroups.toSortedMap().entries.joinToString(",") { (key, value) ->
            "${json(key)}:${json(value.name)}"
        }
        return buildString {
            append("{")
            append("\"algorithm_version\":${json(ValidationBundleV7.ALGORITHM_VERSION)},")
            append("\"bundle_version\":${ValidationBundleV7.BUNDLE_VERSION},")
            append("\"input_algorithm_version\":${json(ValidationBundleV7.INPUT_ALGORITHM_VERSION)},")
            append("\"input_bundle_version\":${ValidationBundleV7.INPUT_BUNDLE_VERSION},")
            append("\"baseline_window\":${json(ValidationBundleV7.BASELINE_WINDOW)},")
            append("\"post_recovery_window\":${json(ValidationBundleV7.POST_RECOVERY_WINDOW)},")
            append("\"bin_seconds\":${ValidationBundleV7.FIVE_MINUTE_BIN_SECONDS},")
            append("\"minimum_bin_coverage\":${number(ValidationBundleV7.MIN_BIN_COVERAGE)},")
            append("\"wsrb_unweighted_numerator_pct_minutes\":${number(export.wsrbUnweightedNumeratorPctMinutes)},")
            append("\"wsrb_unweighted_denominator_seconds\":${export.wsrbUnweightedDenominatorSeconds},")
            append("\"cross_device_weighted_burden_numerator_pct_minutes\":${number(export.crossDeviceWeightedBurdenNumeratorPctMinutes)},")
            append("\"cross_device_weighted_burden_denominator_seconds\":${export.crossDeviceWeightedBurdenDenominatorSeconds},")
            append("\"provenance_groups\":{$provenance}")
            append("}")
        }
    }

    private fun number(value: Double): String = String.format(Locale.US, "%.6f", value)

    private fun numberOrBlank(value: Double?): String = value?.let(::number) ?: ""

    private fun csv(value: String): String {
        if (value.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return value
        return "\"${value.replace("\"", "\"\"")}\""
    }

    private fun json(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(Locale.US, char.code)) else append(char)
            }
        }
        append('"')
    }
}
