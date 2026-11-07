package com.noop.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import com.noop.R

/**
 * Publishes the four long-press launcher shortcuts (Live HR, Workout, Breathe, Devices).
 *
 * Dynamic (via [ShortcutManager]) rather than static XML so the host package is always the
 * running applicationId — full / debug / demo flavors all get working shortcuts without
 * per-flavor `shortcuts.xml` copies. Idempotent: calling again replaces the set.
 */
object LauncherShortcuts {

    /** Route ids published as shortcuts. Every entry must resolve via [routeIsKnown] against the
     *  [Destination] allowlist — pinned by ShortcutRoutesTest so publisher and consumer can't drift. */
    val ROUTES = listOf("live", "workouts", "breathe", "devices")

    /**
     * Pure twin of AppRoot's consume-time allowlist check: [route] is known when it (or its first
     * path segment) matches a destination route (or its first segment). Extracted for unit tests.
     */
    fun routeIsKnown(route: String, knownRoutes: Collection<String>): Boolean =
        knownRoutes.any { it == route || it.substringBefore('/') == route.substringBefore('/') }

    fun publish(context: Context) {
        val sm = context.getSystemService(ShortcutManager::class.java) ?: return
        val app = context.applicationContext

        fun intentFor(route: String): Intent =
            Intent(app, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(MainActivity.EXTRA_SHORTCUT, route)
                // FLAG_ACTIVITY_CLEAR_TOP so a warm app still delivers onNewIntent.
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

        fun shortcut(
            id: String,
            shortRes: Int,
            longRes: Int,
            iconRes: Int,
            route: String,
        ): ShortcutInfo =
            ShortcutInfo.Builder(app, id)
                .setShortLabel(app.getString(shortRes))
                .setLongLabel(app.getString(longRes))
                .setIcon(Icon.createWithResource(app, iconRes))
                .setIntent(intentFor(route))
                .build()

        val (liveRoute, workoutsRoute, breatheRoute, devicesRoute) = ROUTES
        val shortcuts = listOf(
            shortcut(
                id = "live_hr",
                shortRes = R.string.shortcut_live_hr_short,
                longRes = R.string.shortcut_live_hr_long,
                iconRes = R.drawable.ic_stat_heart,
                route = liveRoute,
            ),
            shortcut(
                id = "start_workout",
                shortRes = R.string.shortcut_workout_short,
                longRes = R.string.shortcut_workout_long,
                iconRes = R.mipmap.ic_launcher,
                route = workoutsRoute,
            ),
            shortcut(
                id = "breathe",
                shortRes = R.string.shortcut_breathe_short,
                longRes = R.string.shortcut_breathe_long,
                iconRes = R.mipmap.ic_launcher,
                route = breatheRoute,
            ),
            shortcut(
                id = "devices",
                shortRes = R.string.shortcut_devices_short,
                longRes = R.string.shortcut_devices_long,
                iconRes = R.mipmap.ic_launcher,
                route = devicesRoute,
            ),
        )
        runCatching { sm.dynamicShortcuts = shortcuts }
    }
}
