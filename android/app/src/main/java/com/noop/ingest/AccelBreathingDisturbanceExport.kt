package com.noop.ingest

import com.noop.analytics.AccelBreathingDisturbance
import com.noop.data.Whoop4ResearchSampleEntity
import java.util.Locale
import org.json.JSONObject

/**
 * Validation-bundle serializer for the accel-bd-v0 research index. Sits beside the v6 export; it
 * never touches v6 results, scoring, or UI. Consumers: `sleepimage/analysis` paired-night tooling.
 */
object AccelBreathingDisturbanceExport {
    const val SUMMARY_KEY = "accel_bd_v0"
    const val JSON_ENTRY = "accel_bd_v0.json"
    const val EPOCHS_ENTRY = "accel_bd_v0_epochs.csv"

    data class Output(val grid: AccelBreathingDisturbance.Grid?, val result: AccelBreathingDisturbance.NightResult?)

    fun compute(rows: List<Whoop4ResearchSampleEntity>, scoredSleepIntervals: List<LongRange>): Output {
        val samples = rows.map { AccelBreathingDisturbance.MotionSample(it.ts, it.motion1X, it.motion1Y, it.motion1Z) }
        val grid = AccelBreathingDisturbance.fromSamples(samples, scoredSleepIntervals) ?: return Output(null, null)
        return Output(grid, AccelBreathingDisturbance.analyze(grid))
    }

    fun summaryJson(output: Output): JSONObject {
        val r = output.result
        val g = output.grid
        val json = JSONObject()
            .put("algorithm_version", AccelBreathingDisturbance.ALGORITHM_VERSION)
            .put("status", "DEVELOPMENT")
            .put(
                "interpretation",
                "Accelerometer-only nightly breathing-disturbance research index; not AHI, RDI, or a diagnosis. " +
                    "Frozen on seven paired nights of one person; not validated across people.",
            )
            .put("inputs", "1 Hz motion_candidate_1 x/y/z + current scored sleep intervals")
        if (r == null || g == null) return json.put("available", false).put("reason", "no complete motion samples")
        return json
            .put("available", true)
            .put("grid_start_unix_s", g.startTs)
            .put("grid_seconds", g.samples.size)
            .put("missing_seconds", g.missingSeconds)
            .put("duplicate_seconds", g.duplicateSeconds)
            .put("non_finite_samples", g.nonFiniteSamples)
            .put("asleep_hours", r.asleepHours)
            .put("sleep_epochs", r.sleepEpochs)
            .put("positive_epochs", r.positiveEpochs)
            .put("index_positive_epochs_per_asleep_hour", r.indexPerAsleepHour ?: JSONObject.NULL)
            .put("calibrated_sahi3_like_per_hour", r.calibratedSahi3Like ?: JSONObject.NULL)
            .put("untrained_bursts_per_asleep_hour", r.burstsPerAsleepHour ?: JSONObject.NULL)
            .put("epoch_threshold", AccelBreathingDisturbance.EPOCH_THRESHOLD)
            .put("calibration", JSONObject().put("slope", AccelBreathingDisturbance.CALIBRATION_SLOPE).put("intercept", AccelBreathingDisturbance.CALIBRATION_INTERCEPT).put("fitted_on", "7 nights, one person"))
    }

    fun epochsCsv(output: Output): String {
        val sb = StringBuilder("epoch_start_unix_s,asleep_fraction,is_sleep_epoch,probability,positive,")
        sb.append(AccelBreathingDisturbance.FEATURE_NAMES.joinToString(",")).append('\n')
        val r = output.result ?: return sb.toString()
        val start = output.grid?.startTs ?: return sb.toString()
        for (e in r.epochs) {
            val sleep = e.asleepFraction >= AccelBreathingDisturbance.SLEEP_EPOCH_MIN_FRACTION && e.features != null
            sb.append(start + e.startOffsetSeconds).append(',')
                .append(num(e.asleepFraction)).append(',')
                .append(sleep).append(',')
                .append(e.probability?.let { num(it) } ?: "").append(',')
                .append(e.positive).append(',')
                .append(e.features?.joinToString(",") { num(it) } ?: AccelBreathingDisturbance.FEATURE_NAMES.joinToString(",") { "" })
                .append('\n')
        }
        return sb.toString()
    }

    private fun num(v: Double): String = String.format(Locale.US, "%.9f", v)
}
