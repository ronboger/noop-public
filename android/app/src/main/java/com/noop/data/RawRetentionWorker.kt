package com.noop.data

import android.content.Context
import android.os.StatFs
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Runs [RawRetention] in the background, only while the phone charges, so the one-time catch-up on an
 * old store (hours of deletes) never competes with the wearer for battery. Each run stops well inside
 * WorkManager's 10-minute limit; an unfinished catch-up queues a follow-up run at once.
 *
 * After the deletes it reclaims space ([reclaim]). Deleted rows only free pages INSIDE the file: SQLite
 * reuses them, so the store stops growing, but the file does not shrink until it is vacuumed.
 */
class RawRetentionWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Opt-in: deleting raw rows cannot be undone, and a store this size is past the 2 GiB restore limit
        // of a backup, so a backup is not a usable copy of what would go. The wearer turns it on.
        if (!RawRetentionPrefs.enabled(applicationContext)) return Result.success()
        val db = WhoopDatabase.get(applicationContext)
        val deadline = System.currentTimeMillis() + RUN_BUDGET_MS
        val report = runCatching { RawRetention.run(db, deadlineMillis = deadline) }
            .onFailure { if (it is kotlin.coroutines.cancellation.CancellationException) throw it }
            .getOrElse { Log.w(TAG, "retention pass failed", it); return Result.success() }
        val reclaimNote = if (report.finished) runCatching { reclaim(applicationContext, db) }
            .onFailure { if (it is kotlin.coroutines.cancellation.CancellationException) throw it }
            .getOrElse { "reclaim failed: ${it.javaClass.simpleName}" } else "reclaim deferred"
        Log.i(TAG, "devices=${report.devices} deleted=${report.deleted} finished=${report.finished} $reclaimNote")
        if (!report.finished) enqueueCatchUp(applicationContext)
        return Result.success()
    }

    companion object {
        private const val TAG = "NoopRetention"
        private const val PERIODIC = "noop.rawRetention"
        private const val CATCH_UP = "noop.rawRetention.catchUp"
        private const val RUN_BUDGET_MS = 7L * 60_000L

        /** Pages handed back to the file system per run once incremental vacuum is on (4 KiB pages). */
        private const val INCREMENTAL_PAGES = 65_536

        private val CONSTRAINTS = Constraints.Builder()
            .setRequiresCharging(true)
            .setRequiresBatteryNotLow(true)
            .build()

        /** KEEP, for the reason StaleBatteryWorker documents: REPLACE on every launch restarts the period. */
        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<RawRetentionWorker>(6, TimeUnit.HOURS)
                .setConstraints(CONSTRAINTS)
                .build()
            runCatching {
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
            }
        }

        internal fun enqueueCatchUp(context: Context) {
            val request = OneTimeWorkRequestBuilder<RawRetentionWorker>()
                .setConstraints(CONSTRAINTS)
                .setInitialDelay(30, TimeUnit.SECONDS)
                .build()
            runCatching {
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniqueWork(CATCH_UP, ExistingWorkPolicy.REPLACE, request)
            }
        }

        /**
         * Give freed pages back to the file system, where that is safe.
         *
         * Deleted rows free pages INSIDE the file. SQLite reuses them, so the store stops growing, but the
         * file only shrinks with `auto_vacuum = INCREMENTAL`, which a store created before v45 does not
         * have. Switching mode needs a full VACUUM: a rewrite of the only copy of the data that needs about
         * twice the live size free (and may stage in RAM). That is deliberately NOT done here. With
         * incremental mode on, this hands back a bounded number of pages per run.
         */
        internal fun reclaim(context: Context, db: WhoopDatabase): String {
            val sql = db.openHelper.writableDatabase
            fun pragmaLong(q: String): Long = sql.query(q).use { if (it.moveToFirst()) it.getLong(0) else 0L }
            val pageSize = pragmaLong("PRAGMA page_size")
            val pageCount = pragmaLong("PRAGMA page_count")
            val freePages = pragmaLong("PRAGMA freelist_count")
            val mode = pragmaLong("PRAGMA auto_vacuum")
            val freeDisk = runCatching {
                StatFs(context.getDatabasePath(WhoopDatabase.DB_NAME).parent).availableBytes
            }.getOrDefault(0L)
            if (mode == 2L && freePages > 0) {
                sql.query("PRAGMA incremental_vacuum($INCREMENTAL_PAGES)").use { while (it.moveToNext()) Unit }
            }
            checkpoint(sql)
            return "pages=$pageCount free=$freePages pageSize=$pageSize autoVacuum=$mode disk=${freeDisk shr 20}MiB"
        }

        private fun checkpoint(sql: androidx.sqlite.db.SupportSQLiteDatabase) {
            runCatching { sql.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() } }
        }
    }
}

/** The wearer's opt-in for [RawRetention]. Off by default. */
object RawRetentionPrefs {
    private const val FILE = "noop_raw_retention"
    private const val KEY_ENABLED = "enabled"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    /** Turning it on starts the catch-up at the next charge instead of waiting up to six hours. */
    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
        if (on) RawRetentionWorker.enqueueCatchUp(context)
    }
}
