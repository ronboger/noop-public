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
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.noop.ui.MainActivity

/**
 * Wide 4×1 strip widget: Rest · Charge · Effort plus live HR and battery.
 * Same [WidgetSnapshotStore] as the main widget — no extra producers needed.
 */
class NoopStripWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snap = runCatching { WidgetSnapshotStore.load(context) }.getOrDefault(WidgetSnapshot())
        val dark = widgetIsDark(context)
        provideContent { StripContent(snap, dark) }
    }
}

class NoopStripWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NoopStripWidget()
}

@Composable
private fun StripContent(snap: WidgetSnapshot, dark: Boolean) {
    val surface = ColorProvider(if (dark) Color(0xFF0A1322) else Color(0xFFF4F1EA))
    val textPrimary = ColorProvider(if (dark) Color(0xFFF4F6F8) else Color(0xFF1A2230))
    val textSecondary = ColorProvider(if (dark) Color(0xFF8A94A4) else Color(0xFF7C8696))
    val effort = ColorProvider(if (dark) Color(0xFF4FB6A8) else Color(0xFF2E7D74))

    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(surface)
            .cornerRadius(16.dp)
            .clickable(actionStartActivity<MainActivity>())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StripScore(
            "REST",
            snap.restPct,
            snap.restPct?.let { ringBandColor(it, dark) } ?: textSecondary,
            modifier = GlanceModifier.defaultWeight(),
        )
        StripScore(
            "CHARGE",
            snap.recoveryPct,
            snap.recoveryPct?.let { ringBandColor(it, dark) } ?: textSecondary,
            modifier = GlanceModifier.defaultWeight(),
        )
        StripScore(
            "EFFORT",
            snap.effortPct,
            snap.effortPct?.let { effort } ?: textSecondary,
            modifier = GlanceModifier.defaultWeight(),
        )
        Spacer(modifier = GlanceModifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = snap.heartRate?.let { "♥ $it" } ?: "♥ —",
                style = TextStyle(color = textPrimary, fontSize = 12.sp),
            )
            Text(
                text = snap.batteryPct?.let { "⚡ $it%" } ?: "⚡ —",
                style = TextStyle(color = textSecondary, fontSize = 11.sp),
            )
        }
    }
}

@Composable
private fun StripScore(
    label: String,
    pct: Int?,
    color: ColorProvider,
    modifier: GlanceModifier = GlanceModifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = TextStyle(
                color = ColorProvider(Color(0xFF8A94A4)),
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
        Text(
            text = pct?.let { "$it%" } ?: "—",
            style = TextStyle(color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold),
        )
    }
}
