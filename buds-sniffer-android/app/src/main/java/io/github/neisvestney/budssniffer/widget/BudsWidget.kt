package io.github.neisvestney.budssniffer.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.Visibility
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.glance.visibility
import io.github.neisvestney.budssniffer.MainActivity
import io.github.neisvestney.budssniffer.R
import io.github.neisvestney.budssniffer.buds.BatteryRepository
import io.github.neisvestney.budssniffer.buds.BudLevel
import io.github.neisvestney.budssniffer.buds.LinkStatus
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

private val Background = ColorProvider(Color(0xE0141416))
private val Foreground = Color(0xFFFFFFFF)
private val Dimmed = Color(0x80FFFFFF)
private val Track = Color(0x33FFFFFF)
private val ChargingArc = Color(0xFF59C376)

private val GaugeSize = 52.dp
private val GaugeHeight = 59.dp
private val GaugeStroke = 7.5.dp
private val IconSize = 24.dp
// Matches the digits' cap height at 13sp bold.
private val BoltHeight = 9.5.dp
private val BoltWidth = 5.2.dp
// Oversized on purpose: the outline radius is clamped to half the height, giving a stadium.
private val PillRadius = 100.dp

// Gauge is open at the bottom, like One UI's battery widget.
private const val ARC_START = 160f
private const val ARC_SWEEP = 220f

class BudsWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val batteryFlow = BatteryRepository.battery(context)
        val initial = batteryFlow.first()
        provideContent {
            val battery by batteryFlow.collectAsState(initial = initial)
            val link by BatteryRepository.link.collectAsState()
            val live = link == LinkStatus.Connected
            // The pill wraps its content height instead of filling the cell, like One UI's widget.
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .appWidgetBackground()
                    .clickable(actionStartActivity<MainActivity>()),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .background(Background)
                        .cornerRadius(PillRadius)
                        // Extra bottom padding balances the arc's open bottom, which reads as empty space.
                        .padding(start = 12.dp, top = 7.dp, end = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Gauge(R.drawable.ic_widget_bud_left, "Left", battery?.left, live)
                    Gauge(R.drawable.ic_widget_bud_right, "Right", battery?.right, live)
                    Gauge(R.drawable.ic_widget_case, "Case", battery?.case, live)
                }
            }
        }
    }

    @Composable
    private fun RowScope.Gauge(@DrawableRes icon: Int, name: String, level: BudLevel?, live: Boolean) {
        val charging = live && level?.charging == true
        val description = buildString {
            append(name).append(' ').append(level?.let { "${it.percent}%" } ?: "unknown")
            if (charging) append(", charging")
        }
        val content = if (live) Foreground else Dimmed
        val arc = if (charging) ChargingArc else content
        val density = LocalContext.current.resources.displayMetrics.density
        val fraction = (level?.percent ?: 0).coerceIn(0, 100) / 100f
        val bitmap = remember(density, fraction, arc) {
            arcBitmap(GaugeSize.px(density), GaugeStroke.value * density, fraction, arc.toArgb())
        }
        Box(
            modifier = GlanceModifier.defaultWeight().semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            // The number sits inside the arc's bottom gap, so the gauge is only slightly taller than the arc.
            Box(modifier = GlanceModifier.width(GaugeSize).height(GaugeHeight), contentAlignment = Alignment.TopCenter) {
                Image(
                    provider = ImageProvider(bitmap),
                    contentDescription = null,
                    modifier = GlanceModifier.size(GaugeSize),
                )
                Box(modifier = GlanceModifier.size(GaugeSize), contentAlignment = Alignment.Center) {
                    Image(
                        provider = ImageProvider(icon),
                        contentDescription = null,
                        modifier = GlanceModifier.size(IconSize),
                        colorFilter = ColorFilter.tint(ColorProvider(content)),
                    )
                }
                Column(
                    modifier = GlanceModifier.fillMaxSize(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Number(level, charging, content)
                }
            }
        }
    }

    @Composable
    private fun Number(level: BudLevel?, charging: Boolean, content: Color) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Toggle visibility instead of omitting: RemoteViews reapply can keep stale children.
            Image(
                provider = ImageProvider(R.drawable.ic_widget_bolt),
                contentDescription = null,
                modifier = GlanceModifier
                    .width(BoltWidth)
                    .height(BoltHeight)
                    .visibility(if (charging) Visibility.Visible else Visibility.Gone),
                colorFilter = ColorFilter.tint(ColorProvider(content)),
            )
            Spacer(GlanceModifier.width(if (charging) 1.dp else 0.dp))
            Text(
                text = level?.percent?.toString() ?: "—",
                style = TextStyle(
                    color = ColorProvider(content),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
    }

    companion object {
        suspend fun refresh(context: Context) = BudsWidget().updateAll(context)
    }
}

private fun Dp.px(density: Float) = (value * density).roundToInt().coerceAtLeast(1)

private fun arcBitmap(sizePx: Int, strokePx: Float, fraction: Float, color: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val inset = strokePx / 2
    val bounds = RectF(inset, inset, sizePx - inset, sizePx - inset)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        strokeCap = Paint.Cap.ROUND
        this.color = Track.toArgb()
    }
    canvas.drawArc(bounds, ARC_START, ARC_SWEEP, false, paint)
    if (fraction > 0f) {
        paint.color = color
        canvas.drawArc(bounds, ARC_START, ARC_SWEEP * fraction, false, paint)
    }
    return bitmap
}

class BudsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BudsWidget()
}
