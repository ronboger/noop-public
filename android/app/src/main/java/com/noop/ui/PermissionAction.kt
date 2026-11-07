package com.noop.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * Next step after checking a runtime permission grant and whether the system will still
 * show a rationale / request dialog.
 *
 * Android's requestPermissions becomes a silent no-op once the user has permanently denied
 * (denied twice, or "Don't ask again"): the callback returns denied with
 * shouldShowRequestPermissionRationale == false. Mapping that case to [OpenSettings] is the
 * only recovery path.
 */
internal enum class PermissionAction {
    /** Permission is already granted — proceed (connect, enable feature, etc.). */
    Connect,
    /** Not granted, but the system dialog can still be shown — request again. */
    Request,
    /** Permanently denied — deep-link to the app's system Settings page. */
    OpenSettings,
}

/**
 * Pure decision for permission UX. Call with [granted] from checkSelfPermission and
 * [rationale] from [canStillRequest].
 *
 *  - (true, _)       → [PermissionAction.Connect]
 *  - (false, true)   → [PermissionAction.Request]
 *  - (false, false)  → [PermissionAction.OpenSettings]
 */
internal fun permissionAction(granted: Boolean, rationale: Boolean): PermissionAction = when {
    granted -> PermissionAction.Connect
    rationale -> PermissionAction.Request
    else -> PermissionAction.OpenSettings
}

/**
 * Whether the system request dialog is still worth launching.
 *
 * shouldShowRequestPermissionRationale returns false for BOTH "never asked" and "permanently
 * denied", so on its own it cannot tell a first-run user from a dead-ended one. Persisting
 * whether we have ever asked ([PermissionPrefs]) closes that gap: before the first ask we always
 * allow the request, and only a denial recorded afterwards routes the user to Settings.
 *
 * Without this, a permanently-denied user is re-armed to "Grant permission" on every fresh launch
 * and has to waste a tap on a button that does nothing before the UI corrects itself.
 */
internal fun canStillRequest(hasAsked: Boolean, showRationale: Boolean): Boolean =
    !hasAsked || showRationale

/**
 * Walk the ContextWrapper chain to the hosting Activity, or null (e.g. an application context).
 *
 * LocalContext.current is not guaranteed to be the Activity itself — a bare `as? Activity` cast
 * yields null whenever Compose hands back a ContextThemeWrapper, which would silently strand the
 * rationale check.
 */
internal fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/** Records whether a runtime permission has ever been requested, so permanent denial is detectable. */
internal object PermissionPrefs {
    private const val FILE = "noop_permission_prefs"
    const val NEARBY_DEVICES = "perm.asked.nearbyDevices"
    const val PHONE_STATE = "perm.asked.phoneState"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun hasAsked(ctx: Context, key: String): Boolean = prefs(ctx).getBoolean(key, false)

    fun markAsked(ctx: Context, key: String) = prefs(ctx).edit().putBoolean(key, true).apply()
}
