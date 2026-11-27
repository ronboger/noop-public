package com.noop.data

import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * Raw-stream retention (v45).
 *
 * Every 1 Hz stream used to be kept forever. On a real install that was 6.6 GiB after 136 days, about
 * 52 MB a day, on a phone with 3% free storage, and the size alone made chart and scoring reads slow.
 *
 * The policy, in three levels:
 *  1. Summaries are kept forever: daily metrics, sleep sessions, hourly metrics, workouts, journal,
 *     metric series, live sessions. Nothing here touches them.
 *  2. Raw rows are kept for [RAW_WINDOW_DAYS]. That covers every reader that needs seconds: the 21-day
 *     rescore, the 30-day daytime stress baseline, the 32-day hourly bank and the 60-day Health Connect
 *     floor, with margin.
 *  3. Before a raw row leaves the window its HR is folded into [HrMinuteRow], kept forever, so HR charts
 *     of old days still work at one-minute resolution. The other streams (R-R, gravity, temperature,
 *     respiration, SpO2, steps, band state, research rows) have no generic per-minute form that keeps
 *     their meaning, and their results already live in the summaries.
 *
 * Kept on purpose: `event` and `battery` (sparse), `streamRevision` (one row per hour, scoring depends on
 * it), and every id outside [WhoopDao.rawRetentionDeviceIds] (for example imported data).
 */
object RawRetention {

    const val RAW_WINDOW_DAYS = 90
    private const val DAY_SECONDS = 86_400L

    /** The per-second tables that age out. Every one has `deviceId` and `ts` as the leading PK columns. */
    val PRUNED_TABLES: List<String> = listOf(
        // HR first, so its minute rollup can never see a second that another table already lost.
        "hrSample", "ppgHrSample",
        "rrInterval", "gravitySample", "skinTempSample", "spo2Sample", "stepSample", "respSample",
        "sleepStateSample", "whoop4ResearchSample", "whoop4RrPacket",
    )

    /** Days kept beyond the chosen window, so a reader with exactly that look-back (Health Connect's 60-day
     *  floor) never sees the edge being deleted under it. */
    const val MARGIN_DAYS = 2

    /** The archive cutoff for [nowSec]: [windowDays] plus [MARGIN_DAYS] back, floored to a minute. */
    fun cutoff(nowSec: Long, windowDays: Int = RAW_WINDOW_DAYS): Long =
        floorMinute(nowSec - (windowDays + MARGIN_DAYS) * DAY_SECONDS)

    fun floorMinute(ts: Long): Long = Math.floorDiv(ts, 60L) * 60L

    /** The archived resolution for a requested chart bucket: a whole number of minutes, at least one. */
    fun archivedBucketSeconds(bucketSeconds: Long): Long =
        if (bucketSeconds <= 60L) 60L else ((bucketSeconds + 59L) / 60L) * 60L

    /**
     * Clamp an analysis span so it never reads below [archivedBefore]. A scoring pass over a day whose raw
     * rows are gone would score it from nothing, and the window write replaces the stored scores for every
     * day in the span — so the archived days' real scores would be lost. Two spare days absorb the
     * look-back each night reads before its own midnight.
     */
    fun clampAnalysisDays(maxDays: Int, archivedBefore: Long?, nowSec: Long): Int {
        if (archivedBefore == null) return maxDays
        val available = ((nowSec - archivedBefore) / DAY_SECONDS).toInt() - 2
        return maxDays.coerceAtMost(available.coerceAtLeast(1))
    }

    /**
     * Join the archived and raw halves of one chart read. Only the bucket that straddles the boundary can
     * appear in both: its mean is the mean of the two halves (the bucket types carry no counts), its min
     * and max the true extremes.
     */
    fun joinBuckets(archived: List<HrBucket>, raw: List<HrBucket>): List<HrBucket> {
        if (archived.isEmpty()) return raw
        if (raw.isEmpty()) return archived
        val byStart = LinkedHashMap<Long, HrBucket>()
        for (b in archived) byStart[b.bucket] = b
        for (b in raw) {
            val a = byStart[b.bucket]
            byStart[b.bucket] = if (a == null) b else HrBucket(
                bucket = b.bucket,
                avgBpm = (a.avgBpm + b.avgBpm) / 2.0,
                minBpm = minOf(a.minBpm, b.minBpm),
                maxBpm = maxOf(a.maxBpm, b.maxBpm),
            )
        }
        return byStart.values.sortedBy { it.bucket }
    }

    /** Next batch size from the last one's latency: grow while fast, shrink when slow. */
    fun nextBatchSize(current: Int, lastMillis: Long): Int = when {
        lastMillis < 50L -> (current * 2).coerceAtMost(MAX_BATCH)
        lastMillis > 250L -> (current / 2).coerceAtLeast(MIN_BATCH)
        else -> current
    }

    const val MIN_BATCH = 250
    const val MAX_BATCH = 20_000
    private const val ROLLUP_CHUNK_SECONDS = 6 * 3_600L

    /** What one [run] did, for the strap log. */
    data class Report(
        val devices: Int = 0,
        val deleted: Long = 0,
        val finished: Boolean = true,
        val reclaimedBytes: Long = 0,
        val note: String = "",
    )

    /**
     * One bounded pass. For each eligible device it walks forward from the oldest raw second in
     * [ROLLUP_CHUNK_SECONDS] steps up to the cutoff, and for each step: rolls that step's HR into minutes,
     * moves the boundary to the step's end (so readers switch to the minutes exactly where they now exist),
     * deletes every pruned table's rows below it, and checkpoints. So every run frees space and makes
     * progress, and a run cut short resumes at the oldest row still left.
     *
     * Stops (with `finished = false`) at [deadlineMillis], as soon as [keepGoing] turns false (the wearer
     * switched retention off or changed the window), or when free disk falls under [minFreeDiskBytes]:
     * deletes write WAL and trigger rows before space comes back, so a nearly full phone stops early.
     */
    suspend fun run(
        db: WhoopDatabase,
        nowSec: Long = System.currentTimeMillis() / 1000L,
        deadlineMillis: Long,
        windowDays: Int = RAW_WINDOW_DAYS,
        keepGoing: () -> Boolean = { true },
        freeDiskBytes: () -> Long = { Long.MAX_VALUE },
        minFreeDiskBytes: Long = MIN_FREE_DISK_BYTES,
    ): Report = withContext(Dispatchers.IO) {
        val dao = db.whoopDao()
        val sql = db.openHelper.writableDatabase
        val cutoff = cutoff(nowSec, windowDays)
        val ids = dao.rawRetentionDeviceIds().distinct()
        var deleted = 0L
        val stop = { System.currentTimeMillis() >= deadlineMillis || !keepGoing() || freeDiskBytes() < minFreeDiskBytes }
        var finished = true
        devices@ for (id in ids) {
            val previous = dao.rawArchivedBefore(id) ?: Long.MIN_VALUE
            val oldestHr = dao.oldestHrTs(id)
            var from = if (oldestHr != null && oldestHr < cutoff) floorMinute(oldestHr) else cutoff
            // One last step to the cutoff itself covers the other tables' rows when there is no HR left.
            while (true) {
                if (stop()) { finished = false; break@devices }
                val to = minOf(from + ROLLUP_CHUNK_SECONDS, cutoff)
                if (from < to) dao.rollupHrMinutes(id, from, to)
                // Monotonic: a wider window later never moves the boundary back over deleted rows.
                if (previous < to && (dao.rawArchivedBefore(id) ?: Long.MIN_VALUE) < to) {
                    dao.upsertRawRetention(RawRetentionRow(id, to))
                }
                val result = deleteBelow(sql, id, to, stop)
                deleted += result.first
                if (!result.second) { finished = false; break@devices }
                runCatching { sql.query("PRAGMA wal_checkpoint(PASSIVE)").use { it.moveToFirst() } }
                yield()
                if (to >= cutoff) break
                from = to
            }
        }
        if (deleted > 0) db.invalidationTracker.refreshVersionsAsync()
        Report(devices = ids.size, deleted = deleted, finished = finished)
    }

    /** Below this much free disk a run stops: deletes need WAL and journal space before space returns. */
    const val MIN_FREE_DISK_BYTES = 300L shl 20

    /** Delete every pruned-table row of [deviceId] below [boundary]. Returns (rows, finished). */
    private suspend fun deleteBelow(
        sql: SupportSQLiteDatabase,
        deviceId: String,
        boundary: Long,
        stop: () -> Boolean,
    ): Pair<Long, Boolean> {
        var total = 0L
        var batch = 1_000
        for (table in PRUNED_TABLES) {
            // rowid, not the PK, so one statement shape serves every table. The inner SELECT walks the
            // (deviceId, ts, ...) PK index from the oldest row, so each batch touches only what it deletes.
            val stmt = sql.compileStatement(
                "DELETE FROM `$table` WHERE rowid IN (SELECT rowid FROM `$table` " +
                    "WHERE deviceId = ? AND ts < ? ORDER BY ts LIMIT ?)"
            )
            try {
                while (true) {
                    if (stop()) return total to false
                    val start = System.nanoTime()
                    stmt.clearBindings()
                    stmt.bindString(1, deviceId)
                    stmt.bindLong(2, boundary)
                    stmt.bindLong(3, batch.toLong())
                    val n = stmt.executeUpdateDelete()
                    total += n
                    batch = nextBatchSize(batch, (System.nanoTime() - start) / 1_000_000L)
                    yield()
                    if (n == 0) break
                }
            } finally {
                stmt.close()
            }
        }
        return total to true
    }
}
