package com.noop.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.noop.BuildConfig
import java.util.concurrent.TimeUnit

/**
 * Schedules the daily update check. Mirrors [com.noop.ui.DebugExportScheduler]: one unique work name
 * so every (re)schedule and cancel addresses the same job, and KEEP so an existing schedule holds its
 * period anchor instead of resetting on every app start.
 *
 * The automatic update preference controls whether the worker is scheduled.
 */
object UpdateScheduler {

    private const val WORK_NAME = "noop_update_check_daily"

    fun reschedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx.applicationContext)
        if (!BuildConfig.ENABLE_SELF_UPDATE || !UpdateWatch.isEnabled(ctx)) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

}
