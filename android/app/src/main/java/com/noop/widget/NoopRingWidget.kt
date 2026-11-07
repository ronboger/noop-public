package com.noop.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.noop.ui.MainActivity

/**
 * Compact 2×1 home-screen widget: today's Charge % at a glance, plus a one-word
 * connection status. Same snapshot store as the main 2×2 widget — no extra BLE/DB.
 */
class NoopRingWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snap = runCatching { WidgetSnapshotStore.load(context) }.getOrDefault(WidgetSnapshot())
        val dark = widgetIsDark(context)
        provideContent { RingContent(snap, dark) }
    }
}

class NoopRingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NoopRingWidget()
}

@Composable
private fun RingContent(snap: WidgetSnapshot, dark: Boolean) {
    val surface = ColorProvider(if (dark) Color(0xFF0A1322) else Color(0xFFF4F1EA))
    val textPrimary = ColorProvider(if (dark) Color(0xFFF4F6F8) else Color(0xFF1A2230))
    val textSecondary = ColorProvider(if (dark) Color(0xFF8A94A4) else Color(0xFF7C8696))
    val charge = snap.recoveryPct
    val chargeColor = charge?.let { ringBandColor(it, dark) } ?: textSecondary

    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(surface)
            .cornerRadius(16.dp)
            .clickable(actionStartActivity<MainActivity>())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = GlanceModifier.defaultWeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "CHARGE",
                style = TextStyle(color = textSecondary, fontSize = 10.sp, fontWeight = FontWeight.Medium),
            )
            Text(
                text = charge?.let { "$it%" } ?: "—",
                style = TextStyle(color = chargeColor, fontSize = 28.sp, fontWeight = FontWeight.Bold),
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = when {
                    snap.connected -> "Connected"
                    snap.updatedAtMs > 0L -> "Open to sync"
                    else -> "Not linked"
                },
                style = TextStyle(color = textSecondary, fontSize = 11.sp),
            )
            snap.heartRate?.let { hr ->
                Text(
                    text = "♥ $hr",
                    style = TextStyle(color = textPrimary, fontSize = 12.sp),
                )
            }
        }
    }
}

internal fun widgetIsDark(context: Context): Boolean = runCatching {
    when (
        context.getSharedPreferences("noop_prefs", Context.MODE_PRIVATE)
            .getString("theme.appearance", "system")
    ) {
        "light" -> false
        "dark" -> true
        else -> (context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
}.getOrDefault(true)

/** Recovery-band colour (67 / 34 cuts). Shared by the compact widgets. */
internal fun ringBandColor(recovery: Int, dark: Boolean): ColorProvider = ColorProvider(
    when {
        recovery >= 67 -> if (dark) Color(0xFFE8B84B) else Color(0xFFB07D17)
        recovery >= 34 -> if (dark) Color(0xFFD98A3D) else Color(0xFFC2792E)
        else -> if (dark) Color(0xFFE0662F) else Color(0xFFC84E1E)
    },
)
