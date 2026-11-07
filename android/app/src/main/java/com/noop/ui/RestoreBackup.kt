package com.noop.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.noop.data.DataBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Schedule the restart from the IO operation itself: navigation can cancel the screen's
 * continuation after the database was replaced. Existing repositories now hold closed DAOs. */
internal suspend fun restoreBackup(context: Context, uri: Uri, allowOversize: Boolean = false) =
    withContext(Dispatchers.IO) {
        val app = context.applicationContext
        DataBackup.importFrom(app, uri, allowOversize).also { result ->
            if (result == DataBackup.ImportResult.NeedsRestart) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(app, "Restarting NOOP to finish restoring your backup.", Toast.LENGTH_LONG).show()
                    val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)
                    try {
                        launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            ?.let(app::startActivity)
                    } finally {
                        Runtime.getRuntime().exit(0)
                    }
                }
            }
        }
    }
