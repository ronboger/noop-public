package com.noop.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Background-reliability helpers for the Android strap companion.
 *
 * OEM battery killers are the #1 reason a bonded strap "just stops syncing". The Test Centre
 * already surfaces the Doze exemption status as a diagnostic line; these helpers promote the
 * same signal into Today + Devices so a user never has to dig for it.
 */

/** True when the OS is allowed to kill NOOP in the background (not battery-opt exempt). */
fun isBatteryOptimised(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return !pm.isIgnoringBatteryOptimizations(context.packageName)
}

/**
 * Open the system battery-optimisation screen for this package. Prefer the direct
 * REQUEST_IGNORE intent (one-tap allow); fall back to the app-details battery page, then
 * the generic app-details screen. Never throws — a missing activity just no-ops.
 *
 * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is declared in the manifest. Sideloaded APKs are
 * allowed this path; the dialog still requires an explicit user tap.
 */
@SuppressLint("BatteryLife")
fun openBatteryOptimisationSettings(context: Context) {
    val pkg = context.packageName
    val candidates = buildList {
        add(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$pkg")
            },
        )
        add(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        )
        add(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$pkg")
            },
        )
    }
    for (intent in candidates) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launched = runCatching { context.startActivity(intent); true }.getOrDefault(false)
        if (launched) return
    }
}

/**
 * Re-check battery-optimisation status whenever the host lifecycle resumes (so returning
 * from the system dialog refreshes the card without a full restart).
 */
@Composable
fun rememberBatteryOptimised(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var optimised by remember { mutableStateOf(isBatteryOptimised(context)) }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                optimised = isBatteryOptimised(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return optimised
}

/**
 * Hours since [lastSyncAtSec] (unix seconds). Null when never synced. Pure helper so
 * call sites can decide "stale enough to warn" without re-deriving the arithmetic.
 */
fun hoursSinceSync(lastSyncAtSec: Long?, nowSec: Long = System.currentTimeMillis() / 1000L): Double? {
    if (lastSyncAtSec == null || lastSyncAtSec <= 0L) return null
    return ((nowSec - lastSyncAtSec).coerceAtLeast(0L)).toDouble() / 3600.0
}

/** Stale-sync threshold shown on Today: longer than this → surface a reconnect card. */
const val STALE_SYNC_HOURS = 3.0

/**
 * Which stale-sync warning to show, if any. Pure so the gating is unit-testable.
 *
 * Returns:
 *  - null            → no warning (fresh sync, or connected-and-never-synced — a first-connect
 *                      offload is normally seconds away, so warning there would flash a false alarm)
 *  - "disconnected"  → link down and last offload missing or ≥ [STALE_SYNC_HOURS] old
 *  - "connected"     → link up but the last completed offload is ≥ [STALE_SYNC_HOURS] old —
 *                      the sneaky failure mode where the BLE link looks healthy but data is stalled
 */
fun staleSyncKind(connected: Boolean, hoursSince: Double?): String? {
    if (hoursSince == null) return if (connected) null else "disconnected"
    if (hoursSince < STALE_SYNC_HOURS) return null
    return if (connected) "connected" else "disconnected"
}

/**
 * Compact reliability card used on Today (and reusable on Devices).
 *
 * [kind] picks the icon + title tone:
 *  - "battery" → Doze / battery-optimisation exemption missing
 *  - "stale"   → last successful offload is older than [STALE_SYNC_HOURS]
 */
@Composable
fun ReliabilityCard(
    kind: String,
    title: String,
    body: String,
    actionLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val icon = when (kind) {
        "battery" -> Icons.Filled.BatteryAlert
        else -> Icons.Filled.LinkOff
    }
    val tone = when (kind) {
        "battery" -> Palette.statusWarning
        else -> Palette.statusCritical
    }
    NoopCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { contentDescription = "$title. $body. $actionLabel" },
        padding = 14.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = tone, modifier = Modifier.size(22.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = NoopType.body, color = Palette.textPrimary)
                Text(body, style = NoopType.footnote, color = Palette.textSecondary)
                Text(actionLabel, style = NoopType.caption, color = tone)
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = Palette.textTertiary,
                modifier = Modifier.size(Metrics.iconSmall),
            )
        }
    }
}
