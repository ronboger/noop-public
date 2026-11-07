package com.noop.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import java.io.File

/**
 * Installs a downloaded release APK over the running app via the PackageInstaller session API.
 *
 * Chosen over the legacy ACTION_VIEW intent because it returns a real result: with ACTION_VIEW a
 * signature mismatch and a user-dismissed dialog are indistinguishable from success.
 *
 * Data survives the update because applicationId, signer and increasing versionCode all match — the
 * same three properties Tools/verify-android-upgrade.sh checks on the Mac path.
 */
object ApkInstaller {

    /** Has the user granted NOOP "install unknown apps"? Without it the session commit is refused. */
    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /** Intent to the system screen where that grant is made. The destination screen's wording varies
     *  by manufacturer, so the UI should describe the GOAL, not a vendor's menu path. */
    fun unknownSourcesIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))

    /**
     * Stage [apk] and commit it. True means Android accepted the session and will now prompt the
     * user; it does NOT mean the install finished.
     */
    fun install(ctx: Context, apk: File): Boolean = runCatching {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(ctx.packageName)
            runCatching { setSize(apk.length()) }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("noop.apk", 0, apk.length()).use { output ->
                apk.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
            val intent = Intent(ctx, InstallResultReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val pending = PendingIntent.getBroadcast(ctx, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
        true
    }.getOrDefault(false)
}
