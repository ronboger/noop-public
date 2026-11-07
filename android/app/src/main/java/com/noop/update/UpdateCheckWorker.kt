package com.noop.update

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.noop.BuildConfig
import com.noop.R

/** Notify once per newly-seen versionCode. Pure so the re-nag rule is unit-tested. */
object UpdateNotifyPolicy {
    fun shouldNotify(candidateVersionCode: Int, lastNotifiedVersionCode: Int): Boolean =
        candidateVersionCode > lastNotifiedVersionCode
}

/**
 * The daily check. Fetches ONLY the small release manifest — never the ~20 MB APK, which downloads
 * on an explicit tap. That keeps a background job on a local-first app honest about battery and
 * mobile data.
 */
class UpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val token = UpdateTokenStore.token(ctx) ?: return Result.success()

        val fetched = PrivateReleaseClient().fetchManifest(token)
        if (fetched !is FetchResult.Ok) return Result.success()

        val decision = UpdateDecision.of(fetched.manifest, BuildConfig.VERSION_CODE)
        if (decision !is UpdateDecision.Available) return Result.success()

        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastNotified = prefs.getInt(KEY_LAST_NOTIFIED, 0)
        if (!UpdateNotifyPolicy.shouldNotify(decision.manifest.versionCode, lastNotified)) {
            return Result.success()
        }

        notify(ctx, decision.manifest.versionName)
        prefs.edit().putInt(KEY_LAST_NOTIFIED, decision.manifest.versionCode).apply()
        return Result.success()
    }

    // POST_NOTIFICATIONS is already declared and requested at runtime in MainActivity; if it was
    // denied, areNotificationsEnabled() short-circuits and runCatching makes notify() a no-op.
    @SuppressLint("MissingPermission")
    private fun notify(ctx: Context, versionName: String) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return

        val manager = ctx.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Tells you when a new NOOP build is available." }
        )

        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = launch?.let {
            PendingIntent.getActivity(
                ctx, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setContentTitle("NOOP $versionName is available")
            .setContentText("Open Settings to download and install.")
            .setAutoCancel(true)
            .apply { pending?.let { setContentIntent(it) } }
            .build()

        runCatching { nm.notify(NOTIF_ID, notification) }
    }

    companion object {
        const val PREFS = "noop_update_prefs"
        const val KEY_LAST_NOTIFIED = "last_notified_version_code"
        private const val CHANNEL_ID = "noop_app_updates"
        private const val NOTIF_ID = 4207
    }
}
