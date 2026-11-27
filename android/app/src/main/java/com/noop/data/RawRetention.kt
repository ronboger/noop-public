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

    /** The archive cutoff for [nowSec]: [RAW_WINDOW_DAYS] back, floored to a minute. */
    fun cutoff(nowSec: Long, windowDays: Int = RAW_WINDOW_DAYS): Long =
        floorMinute(nowSec - windowDays * DAY_SECONDS)

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
     * One bounded pass: for each eligible device, roll HR up to the cutoff, publish the boundary, then
     * delete the expired raw rows in short batches. Stops at [deadlineMillis] (wall clock, ms) with
     * `finished = false`; the next run resumes where this one stopped, because every step is idempotent.
     */
    suspend fun run(
        db: WhoopDatabase,
        nowSec: Long = System.currentTimeMillis() / 1000L,
        deadlineMillis: Long,
        windowDays: Int = RAW_WINDOW_DAYS,
    ): Report = withContext(Dispatchers.IO) {
        val dao = db.whoopDao()
        val cutoff = cutoff(nowSec, windowDays)
        val ids = dao.rawRetentionDeviceIds().distinct()
        var deleted = 0L
        var finished = true
        for (id in ids) {
            if (System.currentTimeMillis() >= deadlineMillis) { finished = false; break }
            // 1. Minute HR for everything below the cutoff, in short chunks from the oldest second.
            val oldest = dao.oldestHrTs(id)
            if (oldest != null && oldest < cutoff) {
                var from = floorMinute(oldest)
                while (from < cutoff) {
                    if (System.currentTimeMillis() >= deadlineMillis) { finished = false; break }
                    val to = minOf(from + ROLLUP_CHUNK_SECONDS, cutoff)
                    dao.rollupHrMinutes(id, from, to)
                    from = to
                    yield()
                }
                if (!finished) break
            }
            // 2. The boundary moves only after its minutes exist, so a reader never sees a gap.
            val previous = dao.rawArchivedBefore(id)
            if (previous == null || previous < cutoff) dao.upsertRawRetention(RawRetentionRow(id, cutoff))
            // 3. The deletes, oldest first, a short transaction per batch.
            val boundary = maxOf(previous ?: cutoff, cutoff)
            val result = deleteBelow(db.openHelper.writableDatabase, id, boundary, deadlineMillis)
            deleted += result.first
            if (!result.second) { finished = false; break }
        }
        if (deleted > 0) db.invalidationTracker.refreshVersionsAsync()
        Report(devices = ids.size, deleted = deleted, finished = finished)
    }

    /** Delete every pruned-table row of [deviceId] below [boundary]. Returns (rows, finished). */
    private suspend fun deleteBelow(
        sql: SupportSQLiteDatabase,
        deviceId: String,
        boundary: Long,
        deadlineMillis: Long,
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
                    if (System.currentTimeMillis() >= deadlineMillis) return total to false
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
