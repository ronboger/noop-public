package com.noop.ingest

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import com.noop.BuildConfig
import com.noop.analytics.AnalyticsEngine
import com.noop.analytics.SleepStageTotals
import com.noop.analytics.SleepStager
import com.noop.analytics.SleepStagerV2
import com.noop.analytics.Whoop4CrossDeviceCorrespondenceV7
import com.noop.analytics.Whoop4DisturbanceResearch
import com.noop.data.HrSample
import com.noop.data.RrInterval
import com.noop.data.SleepSession
import com.noop.data.Whoop4ResearchSampleEntity
import com.noop.data.Whoop4RrPacketEntity
import com.noop.data.WhoopRepository
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * EXPERIMENTAL diagnostic: dump the decoded per-sample sensor streams NOOP already stores to ONE
 * combined long-format CSV (last 24 h) and share it. Lets power users / external devs prototype
 * sleep / activity / VBT algorithms on real data without a BLE stream (#308/#276/#322).
 *
 * Long format = one row per sample, with a `stream` discriminator and ONLY that stream's columns
 * filled (the rest blank). Streams: hr / rr / gravity / steps / ppghr / spo2 / skintemp / resp /
 * event. All rows are merged then sorted by ts ascending. Plain text only — never any BLE hex.
 *
 * The `hr` stream reads the RAW `hrSample` table (NOT WhoopDao.hrSamples, which COALESCE-unions in
 * the v26 PPG-derived HR); PPG HR is its own `ppghr` stream so a measured sensor HR is never
 * confused with a derived estimate. Columns and semantics MATCH the Swift exporter byte-for-byte:
 *   unix_s,iso_utc,stream,hr_bpm,rr_ms,grav_x,grav_y,grav_z,step_counter,ppg_bpm,ppg_conf,
 *   spo2_red,spo2_ir,skintemp_raw,resp_raw,event_kind,event_payload
 *
 * On-device only — the file is written to cache/logs (the existing FileProvider path) and shared via
 * the same ACTION_SEND mechanism as the strap-log export; nothing leaves the phone unless shared.
 */
object RawSensorExport {

    private val VALIDATION_NAME_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm", Locale.US)
            .withZone(java.time.ZoneOffset.UTC)
    private const val VALIDATION_BUNDLE_VERSION = ValidationBundleV6.BUNDLE_VERSION
    private val validationExportMutex = Mutex()

    /**
     * One fully-built validation ZIP plus the immutable phase identifiers needed by an off-device
     * uploader. The cache [file] is intentionally short-lived. Manual and background builds use
     * separate cache paths, and [validationExportMutex] prevents two builds from racing.
     */
    data class ValidationBundleArtifact(
        val file: File,
        val publicName: String,
        val inputFingerprintSha256: String,
        val algorithmVersion: String,
        val bundleVersion: Int,
        val appVersionCode: Int,
        /** Sleep session end (unix seconds). Stable across re-syncs that only mutate raw samples. */
        val sessionEndTs: Long,
    )

    /** 18 columns, in the contract order shared with the Swift exporter (band_sleep_state added, #175). */
    private const val HEADER =
        "unix_s,iso_utc,stream,hr_bpm,rr_ms,grav_x,grav_y,grav_z,step_counter,ppg_bpm,ppg_conf," +
            "spo2_red,spo2_ir,skintemp_raw,resp_raw,band_sleep_state,event_kind,event_payload"

    private val UTC_FMT: java.time.format.DateTimeFormatter =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .withZone(java.time.ZoneOffset.UTC)

    private fun iso(epochSeconds: Long): String =
        UTC_FMT.format(java.time.Instant.ofEpochSecond(epochSeconds))

    // Locale-proof Double (always '.'); reuse the exporter's csvField for the one free-text column.
    private fun n(v: Double): String = WhoopCsvExporter.num(v)
    private fun n(v: Int): String = v.toString()

    /**
     * Read each stream for [deviceId] over [from, to] (inclusive, unix seconds), merge by ts ascending,
     * and STREAM the combined long-format CSV body straight through [out] (CSV header row first). A high
     * per-stream [limit] caps a runaway 24 h window without truncating a normal day. Returns a per-stream
     * count map.
     *
     * Memory: we hold one short CSV-line String per sample, sort by ts, and write each through [out]'s
     * buffer. The previous version built a multi-MB StringBuilder of the whole file AND then a second
     * full copy via `header + csv` before writing — on a busy 24 h window that doubling tipped the export
     * into an OutOfMemoryError (#406). Keeping only the raw query rows + one line each, and never
     * materialising the whole file as a String, holds peak allocation roughly to the data itself.
     */
    internal suspend fun writeCsv(
        out: java.io.Writer,
        repo: WhoopRepository,
        deviceId: String,
        from: Long,
        to: Long,
        limit: Int = 200_000,
    ): Map<String, Int> {
        out.write(HEADER); out.write("\n")
        if (from > to) return emptyMap()
        // index: 0 hr_bpm,1 rr_ms,2 grav_x,3 grav_y,4 grav_z,5 step_counter,6 ppg_bpm,7 ppg_conf,
        //        8 spo2_red,9 spo2_ir,10 skintemp_raw,11 resp_raw,12 band_sleep_state,13 event_kind,14 event_payload
        val rows = ArrayList<LineRow>()
        val counts = LinkedHashMap<String, Int>()

        val hr = repo.rawHrSamples(deviceId, from, to, limit)
        counts["hr"] = hr.size
        for (s in hr) rows += LineRow(s.ts, line("hr", s.ts, 0 to n(s.bpm)))

        val rr = repo.rawRrIntervalsForDevice(deviceId, from, to, limit)
        counts["rr"] = rr.size
        for (s in rr) rows += LineRow(s.ts, line("rr", s.ts, 1 to n(s.rrMs)))

        val grav = repo.gravitySamplesForDevice(deviceId, from, to, limit)
        counts["gravity"] = grav.size
        for (s in grav) rows += LineRow(s.ts, line("gravity", s.ts, 2 to n(s.x), 3 to n(s.y), 4 to n(s.z)))

        val steps = repo.stepSamples(deviceId, from, to, limit)
        counts["steps"] = steps.size
        for (s in steps) rows += LineRow(s.ts, line("steps", s.ts, 5 to n(s.counter)))

        val ppg = repo.ppgHrSamples(deviceId, from, to, limit)
        counts["ppghr"] = ppg.size
        for (s in ppg) rows += LineRow(s.ts, line("ppghr", s.ts, 6 to n(s.bpm), 7 to n(s.conf)))

        val spo2 = repo.spo2Samples(deviceId, from, to, limit)
        counts["spo2"] = spo2.size
        for (s in spo2) rows += LineRow(s.ts, line("spo2", s.ts, 8 to n(s.red), 9 to n(s.ir)))

        val skin = repo.skinTempSamples(deviceId, from, to, limit)
        counts["skintemp"] = skin.size
        for (s in skin) rows += LineRow(s.ts, line("skintemp", s.ts, 10 to n(s.raw)))

        val resp = repo.respSamples(deviceId, from, to, limit)
        counts["resp"] = resp.size
        for (s in resp) rows += LineRow(s.ts, line("resp", s.ts, 11 to n(s.raw)))

        // Band sleep_state (#175): the strap's OWN @81 high-nibble state (0 wake/1 still/2 asleep/3 up),
        // carried verbatim. Column 12, before the event columns (matches the Swift exporter order).
        val sleepState = repo.sleepStateSamples(deviceId, from, to, limit)
        counts["band_sleep_state"] = sleepState.size
        for (s in sleepState) rows += LineRow(s.ts, line("band_sleep_state", s.ts, 12 to n(s.state)))

        val events = repo.events(deviceId, from, to, limit)
        counts["event"] = events.size
        for (s in events) rows += LineRow(
            s.ts,
            line("event", s.ts, 13 to WhoopCsvExporter.csvField(s.kind), 14 to WhoopCsvExporter.csvField(s.payloadJSON)),
        )

        // Stable sort by ts asc (a stream's intra-ts order is its query's secondary key).
        rows.sortBy { it.ts }
        for (r in rows) { out.write(r.line); out.write("\n") }
        return counts
    }

    /** ts + the fully-formatted CSV line for one sample (kept small so a whole day fits in memory). */
    private class LineRow(val ts: Long, val line: String)

    /** Build one CSV line: `unix_s,iso_utc,stream` + the 15 value cells (only [set] indices filled). */
    private fun line(stream: String, ts: Long, vararg set: Pair<Int, String>): String {
        val sb = StringBuilder(96)
        sb.append(ts).append(',').append(iso(ts)).append(',').append(stream)
        for (i in 0 until 15) {
            sb.append(',')
            for ((idx, v) in set) if (idx == i) { sb.append(v); break }
        }
        return sb.toString()
    }

    /**
     * Build the last-24 h CSV for the strap source and fire a share sheet (text/csv). Runs the DB read
     * off the main thread; toasts a per-stream summary so the user sees what was captured (and that the
     * deeper 5/MG streams are empty until they've been unlocked). On-device only.
     *
     * [deviceId] is REQUIRED, deliberately. It used to default to the canonical "my-whoop", and the one
     * caller took the default — so after a strap re-add this exported the legacy id's streams rather than
     * the strap the user is actually wearing, silently, in the CSV people attach to bug reports. That is
     * the #172/#175 defect exactly: a defaulted strap id that no caller overrides. Removing the default
     * makes the compiler ask, which is stronger than remembering. (Ported from tanarchytan/noop e4f508f.)
     */
    suspend fun export(context: Context, repo: WhoopRepository, deviceId: String) {
        runCatching {
            val now = System.currentTimeMillis() / 1000
            val dir = File(context.cacheDir, "logs").apply { mkdirs() }
            val file = File(dir, "noop-raw-sensors.csv")
            // Stream straight to disk through an 8 KB buffer — never hold the whole CSV as a String (#406).
            val counts = file.bufferedWriter().use { w ->
                w.append("# NOOP raw sensor export · last 24h · long-format CSV\n")
                w.append("# App: ${BuildConfig.VERSION_NAME} (${BuildConfig.TIER}) · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n")
                w.append("# One row per decoded sample; only the row's `stream` columns are filled. Times are UTC.\n")
                writeCsv(w, repo, deviceId, now - 86_400, now)
            }
            val publicName = "noop-raw-sensors-${VALIDATION_NAME_FMT.format(
                Instant.ofEpochSecond(now).atZone(ZoneId.systemDefault()),
            )}.csv"
            val adbCopy = runCatching {
                saveAdbCopy(context, file, "exports", publicName)
            }.onFailure {
                android.util.Log.w("RawSensorExport", "ADB raw-sensor copy failed", it)
            }.getOrNull()

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "NOOP raw sensor export")
                clipData = ClipData.newRawUri("NOOP raw sensor export", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "Export raw sensor data"))

            val total = counts.values.sum()
            val summary = if (total == 0) {
                "No samples in the last 24h - wear the strap and let it sync, then export again."
            } else {
                // Compact "hr 3204 · rr 812 · …" line, only non-empty streams.
                counts.filterValues { it > 0 }.entries.joinToString(" · ") { "${it.key} ${it.value}" }
            }
            Toast.makeText(
                context,
                "$summary · ${if (adbCopy != null) "USB copy saved" else "share copy only"}",
                Toast.LENGTH_LONG,
            ).show()
        }.onFailure {
            Toast.makeText(context, "Couldn't export sensor data: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Versioned validation bundle for the private WHOOP 4 breathing experiment. It contains the exact
     * raw session rows, event-level candidates, sensitivity sweep, denominators, provenance and input
     * fingerprint needed for a paired WatchPAT/SleepImage/PSG comparison. No multi-day fallback is used. */
    suspend fun exportWhoop4Research(
        context: Context,
        repo: WhoopRepository,
        deviceId: String = "my-whoop",
        interactive: Boolean = true,
    ): ValidationBundleArtifact? = validationExportMutex.withLock {
        runCatching {
            // Android system time is non-negative; the legacy branch below remains in source only
            // as a reference for preserving v5 ZIP contents while the v6 model is integrated.
            if (System.currentTimeMillis() >= 0L) {
                return@withLock exportWhoop4ResearchV6(context, repo, deviceId, interactive)
            }
            val now = System.currentTimeMillis() / 1000
            val sleeps = repo.sleepSessionsMerged(deviceId, now - 3 * 86_400, now + 86_400)
            val latest = checkNotNull(selectMainNightForValidation(sleeps)) {
                "No recent timestamped sleep session"
            }
            val from = latest.effectiveStartTs
            val to = latest.endTs
            val rows = repo.whoop4ResearchSamples(deviceId, from, to, 100_000)
            val hr = repo.hrSamplesForDevice(deviceId, from, to, 100_000)
            val rr = repo.rrIntervalsForDevice(deviceId, from, to, 100_000)
            val gravity = repo.gravitySamplesForDevice(deviceId, from, to, 100_000)
            val resp = repo.respSamples(deviceId, from, to, 100_000)
            val bandSleepState = repo.sleepStateSamples(deviceId, from, to, 100_000)
                .map { it.ts to it.state }
            val sleepIntervals = Whoop4DisturbanceResearch.scoredSleepIntervals(latest.stagesJSON)
            val analysis = Whoop4DisturbanceResearch.analyze(
                input = rows,
                heartRate = hr,
                rrIntervals = rr,
                scoredSleepIntervals = sleepIntervals,
                sessionStartTs = from,
                sessionEndTs = to,
                includeDiagnostics = true,
            )
            val dir = File(context.cacheDir, "logs").apply { mkdirs() }
            val file = if (interactive) {
                File(dir, "noop-whoop4-breathing-validation.zip")
            } else {
                File.createTempFile(
                    "noop-whoop4-breathing-validation-drive-",
                    ".zip",
                    dir,
                )
            }
            val publicName = "noop-validation-${VALIDATION_NAME_FMT.format(
                Instant.ofEpochSecond(to).atZone(ZoneId.systemDefault()),
            )}.zip"
            val v1Stages = SleepStager.stageSession(
                start = from, end = to, grav = gravity, hr = hr, rr = rr, resp = resp,
                bandSleepState = bandSleepState,
            )
            val v2Stages = SleepStagerV2.stageSession(
                start = from, end = to, grav = gravity, hr = hr, rr = rr, resp = resp,
                bandSleepState = bandSleepState,
            )
            val v1StagesJson = AnalyticsEngine.encodeStages(v1Stages) ?: "[]"
            val v2StagesJson = AnalyticsEngine.encodeStages(v2Stages) ?: "[]"
            val currentStagesJson = latest.stagesJSON ?: "[]"
            val rawCsv = buildString {
                appendLine("# NOOP private WHOOP 4 breathing research export")
                appendLine("# Raw/experimental signals only. Not AHI, RDI, SpO2 percentage, or a diagnosis.")
                appendLine("unix_s,layout_version,motion_candidate_1_x,motion_candidate_1_y,motion_candidate_1_z,motion_candidate_2_x,motion_candidate_2_y,motion_candidate_2_z,ppg_green,ppg_red_ir,spo2_red,spo2_ir,resp_raw,skin_contact,signal_quality,ambient,led_drive_1,led_drive_2")
                fun value(v: Any?): String = v?.toString() ?: ""
                rows.forEach { row ->
                    appendLine(listOf(
                        row.ts, row.layoutVersion, row.motion1X, row.motion1Y, row.motion1Z,
                        row.motion2X, row.motion2Y, row.motion2Z, row.ppgGreen, row.ppgRedIR,
                        row.spo2Red, row.spo2IR, row.respirationRaw, row.skinContact,
                        row.signalQuality, row.ambient, row.ledDrive1, row.ledDrive2,
                    ).joinToString(",") { value(it) })
                }
            }
            val eventsCsv = buildString {
                appendLine("start_unix_s,end_unix_s,duration_s,amplitude_reduction,autonomic_support,recovery_motion,in_rhythmic_window,in_scored_sleep,near_large_wrist_rotation,source_signal,guarded_fallback,split_from_overlong,independent_modality_support,evidence_score,evidence_supported")
                analysis.events.forEach { event ->
                    appendLine(listOf(
                        event.startTs, event.endTs, event.durationSeconds,
                        String.format(Locale.US, "%.6f", event.amplitudeReduction),
                        event.autonomicSupport, event.recoveryMotion, event.inRhythmicWindow,
                        event.inScoredSleep ?: "", event.nearLargeWristRotation,
                        WhoopCsvExporter.csvField(event.sourceSignal), event.fallbackUsed,
                        event.splitFromOverlong, event.independentModalitySupport,
                        String.format(Locale.US, "%.6f", event.evidenceScore),
                        event.highConfidence,
                    ).joinToString(","))
                }
            }
            val detectorSamplesCsv = buildString {
                appendLine("unix_s,envelope,baseline,amplitude_reduction,motion_state,valid_for_detection,in_rhythmic_window,in_scored_sleep")
                analysis.detectorSamples.forEach { sample ->
                    appendLine(listOf(
                        sample.ts,
                        sample.envelope?.let { String.format(Locale.US, "%.9f", it) } ?: "",
                        sample.baseline?.let { String.format(Locale.US, "%.9f", it) } ?: "",
                        sample.amplitudeReduction?.let {
                            String.format(Locale.US, "%.6f", it)
                        } ?: "",
                        sample.motionState, sample.validForDetection, sample.inRhythmicWindow,
                        sample.inScoredSleep ?: "",
                    ).joinToString(","))
                }
            }
            val rejectedEventsCsv = buildString {
                appendLine("start_unix_s,end_unix_s,duration_s,amplitude_reduction,moving_seconds,rejection_reason")
                analysis.detectorEpisodes.filter { it.acceptedEvents == 0 }.forEach { episode ->
                    appendLine(listOf(
                        episode.startTs, episode.endTs, episode.durationSeconds,
                        episode.amplitudeReduction?.let {
                            String.format(Locale.US, "%.6f", it)
                        } ?: "",
                        episode.movingSeconds, episode.outcome.name,
                    ).joinToString(","))
                }
                analysis.suppressedSplitEvents.forEach { event ->
                    appendLine(listOf(
                        event.startTs, event.endTs, event.durationSeconds,
                        String.format(Locale.US, "%.6f", event.amplitudeReduction),
                        "", "SPLIT_WITHOUT_REQUIRED_SUPPORT",
                    ).joinToString(","))
                }
            }
            val autonomicCsv = buildString {
                appendLine("unix_s,stream,value")
                hr.sortedBy { it.ts }.forEach { sample ->
                    appendLine("${sample.ts},heart_rate_bpm,${sample.bpm}")
                }
                rr.sortedBy { it.ts }.forEach { sample ->
                    appendLine("${sample.ts},rr_interval_ms,${sample.rrMs}")
                }
            }
            val stagingInputsCsv = buildString {
                appendLine("unix_s,stream,value_1,value_2,value_3")
                gravity.sortedBy { it.ts }.forEach { sample ->
                    appendLine("${sample.ts},gravity,${n(sample.x)},${n(sample.y)},${n(sample.z)}")
                }
                resp.sortedBy { it.ts }.forEach { sample ->
                    appendLine("${sample.ts},respiration_raw,${sample.raw},,")
                }
                bandSleepState.sortedBy { it.first }.forEach { sample ->
                    appendLine("${sample.first},band_sleep_state,${sample.second},,")
                }
            }
            val continuityGapsCsv = buildString {
                appendLine("stream,previous_unix_s,next_unix_s,missing_seconds")
                rows.asSequence().map { it.ts }.distinct().sorted().zipWithNext().forEach { (a, b) ->
                    if (b - a > 1L) appendLine("whoop4_research_rows,$a,$b,${b - a - 1L}")
                }
            }
            fun rateJson(rate: Whoop4DisturbanceResearch.RateEstimate?): Any =
                rate?.let {
                    JSONObject()
                        .put("label", it.label)
                        .put("candidate_events", it.candidateEvents)
                        .put("denominator_hours", it.denominatorHours)
                        .put("rate_per_hour", it.ratePerHour ?: JSONObject.NULL)
                } ?: JSONObject.NULL
            val provenance = analysis.provenance
            val position = analysis.positionProxy
            fun stageSummary(json: String): Any {
                val m = SleepStageTotals.minutes(json) ?: return JSONObject.NULL
                return JSONObject()
                    .put("in_bed_minutes", m.inBed)
                    .put("asleep_minutes", m.asleep)
                    .put("wake_minutes", m.awake)
                    .put("efficiency", if (m.inBed > 0.0) m.asleep / m.inBed else JSONObject.NULL)
            }
            val scoredSleepHours = provenance.scoredSleepHours
            val scoredCandidates = analysis.scoredSleepRate?.candidateEvents
            val observedFloor = if (scoredSleepHours != null && scoredSleepHours > 0.0 &&
                scoredCandidates != null
            ) scoredCandidates / scoredSleepHours else null
            val scoredValidFraction = if (scoredSleepHours != null && scoredSleepHours > 0.0) {
                analysis.scoredSleepRate?.denominatorHours?.div(scoredSleepHours)
            } else null
            val allValidFraction = if (provenance.sessionHours > 0.0) {
                analysis.allValidRate.denominatorHours / provenance.sessionHours
            } else null
            val bandMask = SleepStager.protectedBandWakeMask(
                start = from,
                end = to,
                nEpochs = kotlin.math.ceil((to - from) / 30.0).toInt(),
                bandSleepState = bandSleepState,
            )
            val summary = JSONObject()
                .put("warning", "Research-only; not AHI, RDI, ODI, calibrated SpO2, or a diagnosis")
                .put("app_version_name", BuildConfig.VERSION_NAME)
                .put("app_version_code", BuildConfig.VERSION_CODE)
                .put("app_tier", BuildConfig.TIER)
                .put("android_release", Build.VERSION.RELEASE)
                .put("android_sdk", Build.VERSION.SDK_INT)
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("status", analysis.status.name)
                .put("reason", analysis.reason)
                .put("primary_signal", analysis.primarySignal ?: JSONObject.NULL)
                .put("algorithm_version", provenance.algorithmVersion)
                .put("input_fingerprint_sha256", provenance.inputFingerprint)
                .put("input_start_unix_s", provenance.inputStartTs ?: JSONObject.NULL)
                .put("input_end_unix_s", provenance.inputEndTs ?: JSONObject.NULL)
                .put("input_rows", provenance.inputRows)
                .put("session_start_unix_s", provenance.sessionStartTs ?: JSONObject.NULL)
                .put("session_end_unix_s", provenance.sessionEndTs ?: JSONObject.NULL)
                .put("session_hours", provenance.sessionHours)
                .put("scored_sleep_hours", provenance.scoredSleepHours ?: JSONObject.NULL)
                .put("sleep_mask_source", provenance.sleepMaskSource)
                .put("validation_bundle_version", VALIDATION_BUNDLE_VERSION)
                .put("download_filename", publicName)
                .put("adb_relative_path",
                    "Android/data/${context.packageName}/files/validation/$publicName")
                .put("motion_source_hz", provenance.motionSourceHz)
                .put("higher_rate_motion_available", provenance.higherRateMotionAvailable)
                .put("sleep_staging", JSONObject()
                    .put("current_stored", stageSummary(currentStagesJson))
                    .put("v1_recomputed", stageSummary(v1StagesJson))
                    .put("v2_recomputed", stageSummary(v2StagesJson))
                    .put("v1_recipe_generation", SleepStager.v1RecipeGen)
                    .put("v2_recipe_generation", SleepStagerV2.v2RecipeGen)
                    .put("band_state_samples", bandSleepState.size)
                    .put("band_state_counts", JSONObject(
                        bandSleepState.groupingBy { it.second }.eachCount()
                            .mapKeys { it.key.toString() },
                    ))
                    .put("band_wake_promoted_minutes", bandMask.count { it } * 0.5))
                .put("denominator_audit", JSONObject()
                    .put("all_valid_fraction_of_session", allValidFraction ?: JSONObject.NULL)
                    .put("scored_valid_fraction_of_timestamped_sleep", scoredValidFraction ?: JSONObject.NULL)
                    .put("observed_candidates_per_timestamped_sleep_hour_floor",
                        observedFloor ?: JSONObject.NULL)
                    .put("note", "The floor divides observed candidates by all timestamped sleep; valid-hour rates normalize only analyzable seconds. Report both."))
                .put("method", JSONObject()
                    .put("spectrum_window_seconds", 300)
                    .put("detrend_window_seconds", 31)
                    .put("rms_envelope_seconds", 9)
                    .put("baseline_lookback_seconds", 300)
                    .put("baseline_percentile", 0.70)
                    .put("primary_amplitude_reduction", 0.30)
                    .put("sensitivity_amplitude_reductions", JSONArray(listOf(0.30, 0.40, 0.50, 0.60)))
                    .put("minimum_event_seconds", 10)
                    .put("maximum_event_seconds", 180)
                    .put("recovery_fraction_of_baseline", 0.75)
                    .put("partial_recovery_fraction_for_overlong_split", 0.50)
                    .put("partial_recovery_to_new_reduction_max_seconds", 15)
                    .put("recovery_confirm_seconds", 3)
                    .put("event_detection_maximum_interpolated_gap_seconds", 2)
                    .put("respiration_rhythm_maximum_interpolated_gap_seconds", 5)
                    .put("motion_delta_threshold", 0.03)
                    .put("large_rotation_degrees", 45)
                    .put("rotation_invariant_motion_channel", "five-minute three-axis PCA")
                    .put("minimum_pca_window_coverage", 0.80)
                    .put("minimum_channel_windows_vs_best_covered", 0.50)
                    .put("guarded_fallback_primary_unavailable_fraction", 0.50)
                    .put("guarded_fallback_requires", "recovery motion or autonomic support plus a corroborating representation from the same source modality")
                    .put("split_overlong_requires", "autonomic support, recovery motion, or a distinct physiological modality")
                    .put("valid_denominator_rule", "second is valid when the primary or an eligible guarded-fallback channel is valid")
                    .put("evidence_score_weights", JSONObject()
                        .put("duration", 0.25)
                        .put("depth", 0.20)
                        .put("recovery_motion", 0.25)
                        .put("autonomic_support", 0.20)
                        .put("independent_modality_support", 0.10))
                    .put("evidence_supported_threshold", 0.45)
                    .put("evidence_supported_requires", "recovery motion or autonomic support"))
                .put("all_valid_rate", rateJson(analysis.allValidRate))
                .put("rhythmic_valid_rate", rateJson(analysis.rhythmicValidRate))
                .put("scored_sleep_rate", rateJson(analysis.scoredSleepRate))
                .put("rhythmic_scored_sleep_rate", rateJson(analysis.rhythmicScoredSleepRate))
                .put("evidence_supported_all_valid_rate",
                    rateJson(analysis.highConfidenceAllValidRate))
                .put("evidence_supported_scored_sleep_rate",
                    rateJson(analysis.highConfidenceScoredSleepRate))
                .put("autonomic_confirmed_all_valid_rate",
                    rateJson(analysis.autonomicAllValidRate))
                .put("autonomic_confirmed_scored_sleep_rate",
                    rateJson(analysis.autonomicScoredSleepRate))
                .put("autonomic_supported_events", analysis.autonomicSupportedEvents)
                .put("evidence_supported_events", analysis.highConfidenceEvents)
                .put("guarded_fallback_events", analysis.guardedFallbackEvents)
                .put("split_candidates_suppressed", analysis.suppressedSplitEvents.size)
                .put("detector_episode_outcomes", JSONObject(
                    analysis.detectorEpisodes.groupingBy { it.outcome.name }.eachCount(),
                ))
                .put("coverage", JSONObject()
                    .put("respiration_raw", analysis.coverage.respirationRaw)
                    .put("ppg_green", analysis.coverage.ppgGreen)
                    .put("ppg_red_ir", analysis.coverage.ppgRedIR)
                    .put("raw_oximetry_pair", analysis.coverage.rawOximetryPair)
                    .put("heart_rate", analysis.coverage.heartRate)
                    .put("rr_intervals", analysis.coverage.rrIntervals)
                    .put("motion_vector", analysis.coverage.motionVector))
                .put("channel_diagnostics", JSONArray(analysis.channelDiagnostics.map {
                    JSONObject()
                        .put("label", it.label)
                        .put("source_coverage", it.sourceCoverage)
                        .put("longest_source_gap_seconds", it.longestSourceGapSeconds)
                        .put("continuity_rejected_windows", it.continuityRejectedWindows)
                        .put("availability", it.availability)
                        .put("usable_windows", it.usableWindows)
                        .put("rhythmic_windows", it.rhythmicWindows)
                        .put("rhythmic_window_fraction", it.rhythmicWindowFraction)
                        .put("median_score", it.medianScore)
                }))
                .put("sensitivity_sweep", JSONArray(analysis.sensitivitySweep.map {
                    JSONObject()
                        .put("required_amplitude_reduction", it.amplitudeReduction)
                        .put("candidate_events", it.candidateEvents)
                        .put("rate_per_all_valid_hour", it.ratePerAllValidHour ?: JSONObject.NULL)
                }))
                .put("position_proxy", JSONObject()
                    .put("status", position.status.name)
                    .put("reason", position.reason)
                    .put("stable_coverage", position.stableCoverage)
                    .put("stable_hours", position.stableHours)
                    .put("orientation_episodes", position.orientationEpisodes)
                    .put("large_rotations", position.largeRotations)
                    .put("rotations_per_hour", position.rotationsPerHour ?: JSONObject.NULL)
                    .put("candidates_near_rotation", position.candidatesNearRotation)
                    .put("magnitude_p10", position.magnitudeP10 ?: JSONObject.NULL)
                    .put("magnitude_median", position.magnitudeMedian ?: JSONObject.NULL)
                    .put("magnitude_p90", position.magnitudeP90 ?: JSONObject.NULL))
            ZipOutputStream(file.outputStream().buffered()).use { zip ->
                fun entry(name: String, contents: String) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(contents.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                entry("raw_samples.csv", rawCsv)
                entry("autonomic_samples.csv", autonomicCsv)
                entry("staging_inputs.csv", stagingInputsCsv)
                entry("candidate_events.csv", eventsCsv)
                entry("detector_samples.csv", detectorSamplesCsv)
                entry("rejected_events.csv", rejectedEventsCsv)
                entry("continuity_gaps.csv", continuityGapsCsv)
                entry("sleep_stages_current.json", currentStagesJson)
                entry("sleep_stages_v1.json", v1StagesJson)
                entry("sleep_stages_v2.json", v2StagesJson)
                entry("analysis_summary.json", summary.toString(2))
            }
            // The Sleep tab reads this tiny, derived snapshot instead of rerunning the 100k-row
            // detector or parsing a ZIP. Exact session bounds prevent a stale summary surviving an edit.
            // Dead legacy branch (gated off above); keep the same UI bridge seam as the live path.
            BreathingValidationSnapshotStore.save(
                context,
                BreathingValidationSnapshot.fromAnalysis(
                    analysis = analysis,
                    sessionStartTs = from,
                    sessionEndTs = to,
                    bundleVersion = VALIDATION_BUNDLE_VERSION,
                    appVersionCode = BuildConfig.VERSION_CODE,
                ),
            )
            val downloadsCopy = if (interactive) {
                runCatching {
                    saveValidationCopy(context, file, publicName)
                }.onFailure {
                    android.util.Log.w("RawSensorExport", "Downloads validation copy failed", it)
                }.getOrNull()
            } else {
                null
            }
            val adbCopy = if (interactive) {
                runCatching {
                    saveAdbCopy(context, file, "validation", publicName)
                }.onFailure {
                    android.util.Log.w("RawSensorExport", "ADB validation copy failed", it)
                }.getOrNull()
            } else {
                null
            }
            if (interactive) {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "NOOP WHOOP 4 breathing validation bundle")
                    clipData = ClipData.newRawUri("NOOP WHOOP 4 breathing validation bundle", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, "Export breathing validation bundle"))
                val savedLocations = buildList {
                    if (downloadsCopy != null) add("Downloads")
                    if (adbCopy != null) add("USB staging")
                }.joinToString(" + ")
                Toast.makeText(
                    context,
                    "${rows.size} rows · ${analysis.events.size} candidates · " +
                        if (savedLocations.isNotEmpty()) "saved $savedLocations" else "share copy only",
                    Toast.LENGTH_LONG,
                ).show()
            }
            ValidationBundleArtifact(
                file = file,
                publicName = publicName,
                inputFingerprintSha256 = provenance.inputFingerprint,
                algorithmVersion = provenance.algorithmVersion,
                bundleVersion = VALIDATION_BUNDLE_VERSION,
                appVersionCode = BuildConfig.VERSION_CODE,
                sessionEndTs = to,
            )
        }.onFailure {
            if (interactive) {
                Toast.makeText(
                    context,
                    "Couldn't export research data: ${it.message}",
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                android.util.Log.w("RawSensorExport", "Background validation build failed", it)
            }
        }.getOrNull()
    }

    private suspend fun exportWhoop4ResearchV6(
        context: Context,
        repo: WhoopRepository,
        deviceId: String,
        interactive: Boolean,
    ): ValidationBundleArtifact? = runCatching {
        val now = System.currentTimeMillis() / 1000
        val sleeps = repo.sleepSessionsMerged(deviceId, now - 3 * 86_400, now + 86_400)
        val latest = checkNotNull(selectMainNightForValidation(sleeps)) {
            "No recent timestamped sleep session"
        }
        val from = latest.effectiveStartTs
        val to = latest.endTs
        val rows = repo.whoop4ResearchSamples(deviceId, from, to, 100_000)
        val hr = repo.hrSamplesForDevice(deviceId, from, to, 100_000)
        val rr = repo.rrIntervalsForDevice(deviceId, from, to, 100_000)
        val gravity = repo.gravitySamplesForDevice(deviceId, from, to, 100_000)
        val resp = repo.respSamples(deviceId, from, to, 100_000)
        val bandSleepState = repo.sleepStateSamples(deviceId, from, to, 100_000)
            .map { it.ts to it.state }
        val sleepIntervals = Whoop4DisturbanceResearch.scoredSleepIntervals(latest.stagesJSON)
        val analysis = Whoop4DisturbanceResearch.analyze(
            input = rows,
            heartRate = hr,
            rrIntervals = rr,
            scoredSleepIntervals = sleepIntervals,
            sessionStartTs = from,
            sessionEndTs = to,
            includeDiagnostics = true,
        )
        val v6 = ValidationBundleV6.fromResult(analysis)
        // accel-bd-v0 research index (accelerometer-only) travels beside v6; it never alters v6.
        val accelBd = runCatching { AccelBreathingDisturbanceExport.compute(rows, sleepIntervals) }
            .getOrElse { AccelBreathingDisturbanceExport.Output(null, null) }
        // Publish the Sleep-card snapshot as soon as the primary analysis succeeds — before ZIP
        // assembly or optional v7 supplements. A later ZIP/serializer failure must not leave Sleep
        // without research results for this night.
        val primaryPhase = BreathingAlgorithmPhase.primary
        BreathingValidationUiBridge.publish(
            context = context,
            analysis = analysis,
            sessionStartTs = from,
            sessionEndTs = to,
            appVersionCode = BuildConfig.VERSION_CODE,
            phase = primaryPhase,
            accel = accelBd,
        )
        val v7 = computeV7CorrespondenceExport(
            deviceId = deviceId,
            v6 = v6,
            hr = hr,
            rr = rr,
            rows = rows,
            from = from,
            to = to,
        )
        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        val file = if (interactive) {
            File(dir, "noop-whoop4-respiratory-burden-v6-validation.zip")
        } else {
            File.createTempFile("noop-whoop4-respiratory-burden-v6-drive-", ".zip", dir)
        }
        val fingerprintPrefix = analysis.provenance.inputFingerprint.take(12)
        val publicName = "noop-validation-${VALIDATION_NAME_FMT.format(Instant.ofEpochSecond(to))}-v6-code${BuildConfig.VERSION_CODE}-$fingerprintPrefix.zip"
        val v1Stages = SleepStager.stageSession(
            start = from, end = to, grav = gravity, hr = hr, rr = rr, resp = resp,
            bandSleepState = bandSleepState,
        )
        val v2Stages = SleepStagerV2.stageSession(
            start = from, end = to, grav = gravity, hr = hr, rr = rr, resp = resp,
            bandSleepState = bandSleepState,
        )
        val v1StagesJson = AnalyticsEngine.encodeStages(v1Stages) ?: "[]"
        val v2StagesJson = AnalyticsEngine.encodeStages(v2Stages) ?: "[]"
        val currentStagesJson = latest.stagesJSON ?: "[]"
        val bandMask = SleepStager.protectedBandWakeMask(
            start = from,
            end = to,
            nEpochs = kotlin.math.ceil((to - from) / 30.0).toInt(),
            bandSleepState = bandSleepState,
        )
        val summary = JSONObject(
            ValidationBundleV6Files.summary(
                contextPackage = context.packageName,
                publicName = publicName,
                analysis = analysis,
                export = v6,
                from = from,
                to = to,
                currentStagesJson = currentStagesJson,
                v1StagesJson = v1StagesJson,
                v2StagesJson = v2StagesJson,
                bandSleepState = bandSleepState,
                bandMask = bandMask,
                appVersionName = BuildConfig.VERSION_NAME,
                appVersionCode = BuildConfig.VERSION_CODE,
            ),
        ).put(AccelBreathingDisturbanceExport.SUMMARY_KEY, AccelBreathingDisturbanceExport.summaryJson(accelBd)).toString(2)
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            fun entry(name: String, contents: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(contents.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry(AccelBreathingDisturbanceExport.JSON_ENTRY, AccelBreathingDisturbanceExport.summaryJson(accelBd).toString(2))
            entry(AccelBreathingDisturbanceExport.EPOCHS_ENTRY, AccelBreathingDisturbanceExport.epochsCsv(accelBd))
            // These entries preserve the existing leading columns and append optional prospective
            // R-R packet provenance; legacy readers can continue consuming their original columns.
            entry("raw_samples.csv", ValidationBundleV6Files.rawSamplesCsv(rows))
            // v20 packet-level R-R provenance; for captures that predate the packet table (v19 rows
            // carry the same fields on the research table), synthesize equivalent packet rows so the
            // export path is identical either way.
            val rrPackets = repo.whoop4RrPackets(deviceId, from, to).ifEmpty {
                rows.filter { it.rrIntervalsJson != null && it.captureOrdinal != null }.map { r ->
                    Whoop4RrPacketEntity(
                        deviceId = r.deviceId, ts = r.ts, captureOrdinal = r.captureOrdinal!!,
                        packetType = 47, rrIntervalsJson = r.rrIntervalsJson!!,
                        sourceUnixS = r.sourceUnixS, signalQuality = r.signalQuality,
                    )
                }
            }
            entry("autonomic_samples.csv", ValidationBundleV6Files.autonomicSamplesCsv(hr, rr, rrPackets))
            entry("staging_inputs.csv", ValidationBundleV6Files.stagingInputsCsv(gravity, resp, bandSleepState))
            entry("detector_samples.csv", ValidationBundleV6Files.detectorSamplesCsv(analysis))
            entry("continuity_gaps.csv", ValidationBundleV6Files.continuityGapsCsv(rows))
            entry("candidate_events.csv", ValidationBundleV6Files.candidateEventsCsv(v6))
            entry("wsrb_samples.csv", ValidationBundleV6Files.wsrbSamplesCsv(v6))
            entry("diagnostic_support.csv", ValidationBundleV6Files.diagnosticSupportCsv(v6))
            entry("rejected_events.csv", ValidationBundleV6Files.rejectedEventsCsv(analysis))
            entry("duration_distributions.json", ValidationBundleV6Files.durationDistributions(v6))
            entry("provenance_groups.json", ValidationBundleV6Files.provenanceGroups(v6))
            entry("sleep_stages_current.json", currentStagesJson)
            entry("sleep_stages_v1.json", v1StagesJson)
            entry("sleep_stages_v2.json", v2StagesJson)
            entry("analysis_summary.json", summary)
            // Fail-soft v7 ZIP supplements: analyze was already soft; serialize/write must be too so a
            // broken v7 path never rolls back the v6 ZIP or the UI snapshot published above.
            appendOptionalV7ZipEntries(write = ::entry, v7 = v7)
        }
        val downloadsCopy = if (interactive) {
            runCatching { saveValidationCopy(context, file, publicName) }
                .onFailure { android.util.Log.w("RawSensorExport", "Downloads validation copy failed", it) }
                .getOrNull()
        } else null
        val adbCopy = if (interactive) {
            runCatching { saveAdbCopy(context, file, "validation", publicName) }
                .onFailure { android.util.Log.w("RawSensorExport", "ADB validation copy failed", it) }
                .getOrNull()
        } else null
        if (interactive) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "NOOP WHOOP 4 respiratory-burden v6 validation bundle")
                clipData = ClipData.newRawUri("NOOP WHOOP 4 respiratory-burden v6 validation bundle", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "Export v6 validation bundle"))
            val savedLocations = buildList {
                if (downloadsCopy != null) add("Downloads")
                if (adbCopy != null) add("USB staging")
            }.joinToString(" + ")
            Toast.makeText(
                context,
                "${rows.size} rows · ${v6.events.size} candidates · " +
                    if (savedLocations.isNotEmpty()) "saved $savedLocations" else "share copy only",
                Toast.LENGTH_LONG,
            ).show()
        }
        ValidationBundleArtifact(
            file = file,
            publicName = publicName,
            inputFingerprintSha256 = analysis.provenance.inputFingerprint,
            // Drive identity follows the primary phase so a new algorithm version re-uploads.
            algorithmVersion = primaryPhase.algorithmVersion,
            bundleVersion = primaryPhase.bundleVersion,
            appVersionCode = BuildConfig.VERSION_CODE,
            sessionEndTs = to,
        )
    }.onFailure {
        if (interactive) {
            Toast.makeText(context, "Couldn't export v6 research data: ${it.message}", Toast.LENGTH_LONG).show()
        } else {
            android.util.Log.w("RawSensorExport", "Background v6 validation build failed", it)
        }
    }.getOrNull()

    /**
     * Fail-soft v7 correspondence export used by the v6 validation ZIP. A device-id mismatch or any
     * other analysis failure returns null so the caller can still ship a complete v6-only bundle.
     */
    internal fun computeV7CorrespondenceExport(
        deviceId: String,
        v6: ValidationBundleV6.Export,
        hr: List<HrSample>,
        rr: List<RrInterval>,
        rows: List<Whoop4ResearchSampleEntity>,
        from: Long,
        to: Long,
    ): Whoop4CrossDeviceCorrespondenceV7.Export? = runCatching {
        Whoop4CrossDeviceCorrespondenceV7.analyze(
            Whoop4CrossDeviceCorrespondenceV7.Input(
                phaseIdentity = Whoop4CrossDeviceCorrespondenceV7.PhaseIdentity(),
                deviceId = deviceId,
                v6Export = v6,
                heartRate = hr,
                rrIntervals = rr,
                rawSamples = rows,
                sessionStartTs = from,
                sessionEndTs = to,
            ),
        )
    }.onFailure {
        android.util.Log.w("RawSensorExport", "v7 correspondence export failed; bundle stays v6-only", it)
    }.getOrNull()

    /**
     * Write the three optional v7 ZIP entries.
     *
     * Serializer failures are fail-soft (log + skip that entry) so a broken v7 layer cannot blank
     * the v6 ZIP. Stream/IO failures from [write] are rethrown — continuing into a half-written
     * ZipOutputStream is unsafe. Returns how many entries were written (0..3).
     *
     * Pure seam for unit tests — the production ZipOutputStream path passes [write] that puts ZIP
     * entries; tests pass a capturing lambda.
     */
    internal fun appendOptionalV7ZipEntries(
        write: (name: String, contents: String) -> Unit,
        v7: Whoop4CrossDeviceCorrespondenceV7.Export?,
        onFailure: (Throwable) -> Unit = {
            android.util.Log.w("RawSensorExport", "v7 ZIP entry write failed; bundle stays v6-only", it)
        },
    ): Int {
        if (v7 == null) return 0
        val entries = listOf(
            "v7_autonomic_responses.csv" to { ValidationBundleV7Files.autonomicResponsesCsv(v7) },
            "v7_five_minute_burden.csv" to { ValidationBundleV7Files.fiveMinuteBurdenCsv(v7) },
            "v7_manifest.json" to { ValidationBundleV7Files.manifestJson(v7) },
        )
        var written = 0
        for ((name, body) in entries) {
            val contents = try {
                body()
            } catch (t: Throwable) {
                onFailure(t)
                continue
            }
            // Stream failures propagate: do not keep writing into a broken ZipOutputStream.
            write(name, contents)
            written += 1
        }
        return written
    }

    /**
     * Keep a timestamped copy in Downloads/NOOP so a USB-connected validation task can retrieve it with
     * adb. Android 9 and older retain the existing share-only path rather than requesting broad storage
     * permission. Failure is non-fatal: the cache copy is still shared by the caller.
     */
    private fun saveValidationCopy(context: Context, source: File, displayName: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/NOOP")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                ?: error("Couldn't open Downloads destination")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "Downloads/NOOP/$displayName"
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }

    /**
     * Keep a timestamped copy in app-scoped external storage. It needs no broad storage permission
     * and gives USB-connected research work a deterministic `adb pull` path even when
     * MediaStore/Downloads or the Android share sheet misbehaves. It is deleted with the app, so it
     * is an export staging area rather than a backup.
     */
    private fun saveAdbCopy(
        context: Context,
        source: File,
        directoryName: String,
        displayName: String,
    ): String {
        val directory = checkNotNull(context.getExternalFilesDir(directoryName)) {
            "App-scoped external storage unavailable"
        }.apply { mkdirs() }
        val destination = File(directory, displayName)
        source.inputStream().buffered().use { input ->
            destination.outputStream().buffered().use { output -> input.copyTo(output) }
        }
        return "USB: Android/data/${context.packageName}/files/$directoryName/$displayName"
    }

    /**
     * Same main-night pick the Sleep tab uses: the newest wake-day's longest overnight block, not
     * the latest-ending nap. A `maxBy(endTs)` export used to snapshot the afternoon nap, so the
     * Sleep card (keyed to the main night) showed no apnea rates.
     */
    internal fun selectMainNightForValidation(sleeps: List<SleepSession>): SleepSession? {
        if (sleeps.isEmpty()) return null
        val newestDay = sleeps.groupBy { localWakeDay(it.endTs) }
            .maxByOrNull { (_, blocks) -> blocks.maxOf { it.endTs } }
            ?.value
            .orEmpty()
        val offsetSec = (java.util.TimeZone.getDefault()
            .getOffset(System.currentTimeMillis()) / 1000).toLong()
        val idx = SleepStageTotals.mainNightIndex(
            newestDay.map { SleepStageTotals.NightBlock(it.effectiveStartTs, it.endTs) },
            offsetSec,
            null,
        )
        return idx?.let { newestDay[it] } ?: newestDay.maxByOrNull { it.endTs }
            ?: sleeps.maxByOrNull { it.endTs }
    }

    private fun localWakeDay(endTs: Long): String {
        val offsetSec = (java.util.TimeZone.getDefault().getOffset(endTs * 1000) / 1000).toLong()
        return AnalyticsEngine.dayString(endTs, offsetSec)
    }

    /**
     * Sleep opens this path when the displayed night has no compact snapshot yet.
     * A stored snapshot, or an inverted window, must not start another detector run.
     */
    internal fun shouldScoreDisplayedNight(
        sessionStartTs: Long,
        sessionEndTs: Long,
        hasSnapshot: Boolean,
    ): Boolean {
        if (hasSnapshot) return false
        return sessionEndTs > sessionStartTs
    }

    /**
     * Try the session's own strap first, then the active strap, then canonical `my-whoop`.
     * `-noop` computed ids store no research rows.
     */
    internal fun researchDeviceIdsForSession(
        sessionDeviceId: String?,
        activeStrapId: String,
    ): List<String> =
        listOfNotNull(sessionDeviceId, activeStrapId, "my-whoop")
            .map { it.removeSuffix("-noop") }
            .distinct()
            .filter { it.isNotBlank() }

    /**
     * Analyze one displayed night and publish the Sleep-card snapshot without building a ZIP.
     * Tries each [deviceIds] until research samples exist (active strap, then canonical).
     * Empty input must not publish: an UNAVAILABLE snapshot would hide a later sync of the
     * same window.
     */
    suspend fun publishUiSnapshotForSession(
        context: Context,
        repo: WhoopRepository,
        deviceIds: List<String>,
        sessionStartTs: Long,
        sessionEndTs: Long,
        stagesJSON: String?,
    ): Boolean {
        if (!shouldScoreDisplayedNight(sessionStartTs, sessionEndTs, hasSnapshot = false)) return false
        val ids = deviceIds.map { it.removeSuffix("-noop") }.distinct().filter { it.isNotBlank() }
        if (ids.isEmpty()) return false
        var rows = emptyList<Whoop4ResearchSampleEntity>()
        var usedId = ids.first()
        for (id in ids) {
            val found = repo.whoop4ResearchSamples(id, sessionStartTs, sessionEndTs, 100_000)
            if (found.isNotEmpty()) {
                rows = found
                usedId = id
                break
            }
        }
        if (rows.isEmpty()) return false
        val hr = repo.hrSamplesUnion(usedId, sessionStartTs, sessionEndTs, 100_000)
        val rr = repo.rrIntervalsUnion(usedId, sessionStartTs, sessionEndTs, 100_000)
        val sleepIntervals = Whoop4DisturbanceResearch.scoredSleepIntervals(stagesJSON)
        val analysis = Whoop4DisturbanceResearch.analyze(
            input = rows,
            heartRate = hr,
            rrIntervals = rr,
            scoredSleepIntervals = sleepIntervals,
            sessionStartTs = sessionStartTs,
            sessionEndTs = sessionEndTs,
            includeDiagnostics = false,
        )
        val accelBd = runCatching { AccelBreathingDisturbanceExport.compute(rows, sleepIntervals) }
            .getOrElse { AccelBreathingDisturbanceExport.Output(null, null) }
        BreathingValidationUiBridge.publish(
            context = context,
            analysis = analysis,
            sessionStartTs = sessionStartTs,
            sessionEndTs = sessionEndTs,
            appVersionCode = BuildConfig.VERSION_CODE,
            accel = accelBd,
        )
        return true
    }
}
