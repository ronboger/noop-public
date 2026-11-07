package com.noop.viatom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.noop.R
import com.noop.ui.appLaunchIntent

/**
 * Foreground-service anchor for [ViatomRingRecorder]. The recorder's partial wake lock keeps the CPU
 * up across the A5 notification stream, but a wake lock does NOT keep the process alive — Android
 * can still reap a backgrounded app and take the GATT link with it. While a recording runs this
 * service holds a `connectedDevice` foreground slot and an ongoing notification, the same contract
 * [com.noop.ble.WhoopConnectionService] uses for the strap. It owns no BLE logic of its own; it
 * exists so the ring's overnight capture does not depend on WHOOP also being connected.
 */
class ViatomRingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The notification's "Stop recording" action routes back here as a self-intent.
        if (intent?.action == ACTION_STOP) {
            runCatching { ViatomRingRecorder.shared(this).stop() }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        ensureChannel()
        // Must call startForeground promptly after startForegroundService(). If the platform refuses
        // (e.g. API 34's connectedDevice type without BLUETOOTH_CONNECT) we stop cleanly rather than
        // crash — the recorder's own wake lock still covers the same night, just less strongly.
        val promoted = runCatching {
            val type =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else {
                    0
                }
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
        }.isSuccess
        if (!promoted) stopSelf()
        // NOT_STICKY on purpose: a restarted service would show "recording" while the in-memory
        // recorder is actually gone — a readout that lies. A dead process needs a fresh start().
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            appLaunchIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopAction = PendingIntent.getService(
            this,
            1,
            Intent(this, ViatomRingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setContentTitle(getString(R.string.viatom_ring_title))
            .setContentText(getString(R.string.viatom_ring_fgs_detail))
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.viatom_ring_stop), stopAction)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun ensureChannel() {
        runCatching {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.viatom_ring_title),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            mgr.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "noop_viatom_ring"
        private const val NOTIF_ID = 4207
        const val ACTION_STOP = "com.noop.viatom.action.STOP_RING_RECORDING"

        /**
         * Promote the process to the foreground for the length of a recording. MUST be called from a
         * foreground context (the recorder's start() runs on a user button press) to satisfy Android
         * 12+'s background-start rule. Defensive: any failure is swallowed so it can never break the
         * recording itself.
         */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, ViatomRingService::class.java),
                )
            }
        }

        /** Drop the foreground promotion. The BLE teardown is the recorder's own job. */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ViatomRingService::class.java)) }
        }
    }
}
