package io.github.neisvestney.budssniffer.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider as FixedColor
import io.github.neisvestney.budssniffer.MainActivity
import io.github.neisvestney.budssniffer.buds.BatteryRepository
import io.github.neisvestney.budssniffer.buds.BudLevel
import io.github.neisvestney.budssniffer.buds.LinkStatus
import kotlinx.coroutines.flow.first

private val Background = ColorProvider(day = Color(0xB8FFFFFF), night = Color(0xCC1C1C1E))
private val Primary = ColorProvider(day = Color(0xFF111111), night = Color(0xFFFAFAFA))
private val Secondary = ColorProvider(day = Color(0xFF6E6E73), night = Color(0xFFAEAEB2))
private val Dimmed = ColorProvider(day = Color(0xFF8E8E93), night = Color(0xFF8E8E93))
private val ChargingPill = FixedColor(Color(0xDAFFBE25))
private val NoPill = FixedColor(Color.Transparent)

class BudsWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val batteryFlow = BatteryRepository.battery(context)
        val initial = batteryFlow.first()
        provideContent {
            val battery by batteryFlow.collectAsState(initial = initial)
            val link by BatteryRepository.link.collectAsState()
            val live = link == LinkStatus.Connected
            Row(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .appWidgetBackground()
                    .background(Background)
                    .cornerRadius(android.R.dimen.system_app_widget_background_radius)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .clickable(actionStartActivity<MainActivity>()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cell("L", "Left", battery?.left, live)
                Cell("R", "Right", battery?.right, live)
                Cell("C", "Case", battery?.case, live)
            }
        }
    }

    @Composable
    private fun RowScope.Cell(label: String, name: String, level: BudLevel?, live: Boolean) {
        val charging = live && level?.charging == true
        val percent = level?.let { "${it.percent}%" }
        val description = buildString {
            append(name).append(' ').append(percent ?: "unknown")
            if (charging) append(", charging")
        }
        Column(
            modifier = GlanceModifier.defaultWeight().semantics { contentDescription = description },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = percent ?: "—",
                style = TextStyle(
                    color = if (live) Primary else Dimmed,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
            Spacer(GlanceModifier.height(2.dp))
            Text(
                text = label,
                // Always set a background: RemoteViews reapply doesn't clear one that was dropped from the modifier.
                modifier = GlanceModifier
                    .background(if (charging) ChargingPill else NoPill)
                    .cornerRadius(10.dp)
                    .padding(horizontal = 8.dp, vertical = 1.dp),
                style = TextStyle(
                    color = if (charging) Primary else Secondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
    }

    companion object {
        suspend fun refresh(context: Context) = BudsWidget().updateAll(context)
    }
}

class BudsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BudsWidget()
}
