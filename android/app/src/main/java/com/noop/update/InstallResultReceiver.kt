package com.noop.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/**
 * Receives the outcome of an in-app update session.
 *
 * The important case is [PackageInstaller.STATUS_PENDING_USER_ACTION]: Android will not install
 * anything until the user confirms, and it hands back the confirmation Intent here for us to launch.
 * That dialog is not an obstacle to route around — it is the design (spec §7).
 *
 * There is deliberately no success branch. A successful self-update REPLACES this process, so any
 * success handler here is unreachable in practice; the app confirms the new build by its own
 * versionCode on next launch.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
            }
            else -> {
                // Failures surface through the Settings UI on the next check; nothing to do here.
            }
        }
    }
}
