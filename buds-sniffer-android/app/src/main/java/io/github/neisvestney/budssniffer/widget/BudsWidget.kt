package io.github.neisvestney.budssniffer.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import io.github.neisvestney.budssniffer.buds.LinkStatus
import io.github.neisvestney.budssniffer.ui.gauge.GaugeSpec
import io.github.neisvestney.budssniffer.ui.gauge.GaugeState
import io.github.neisvestney.budssniffer.ui.gauge.drawGaugeArc
import io.github.neisvestney.budssniffer.ui.gauge.gaugeStates
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

private val Background = ColorProvider(GaugeSpec.WidgetBackground)
private val Foreground = GaugeSpec.WidgetForeground
private val Dimmed = Foreground.copy(alpha = GaugeSpec.DIMMED_ALPHA)
private val Track = Foreground.copy(alpha = GaugeSpec.TRACK_ALPHA)

// Oversized on purpose: the outline radius is clamped to half the height, giving a stadium.
private val PillRadius = 100.dp

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
                        .padding(
                            start = GaugeSpec.PillPaddingHorizontal,
                            top = GaugeSpec.PillPaddingTop,
                            end = GaugeSpec.PillPaddingHorizontal,
                            bottom = GaugeSpec.PillPaddingBottom,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (state in gaugeStates(battery, live)) Gauge(state)
                }
            }
        }
    }

    @Composable
    private fun RowScope.Gauge(state: GaugeState) {
        val content = if (state.live) Foreground else Dimmed
        val arc = if (state.charging) GaugeSpec.ChargingColor else content
        val density = LocalContext.current.resources.displayMetrics.density
        val bitmap = remember(density, state.fraction, arc) {
            arcBitmap(GaugeSpec.Size.px(density), GaugeSpec.Stroke.value * density, state.fraction, arc.toArgb())
        }
        Box(
            modifier = GlanceModifier.defaultWeight().semantics { contentDescription = state.description },
            contentAlignment = Alignment.Center,
        ) {
            // The number sits inside the arc's bottom gap, so the gauge is only slightly taller than the arc.
            Box(modifier = GlanceModifier.width(GaugeSpec.Size).height(GaugeSpec.Height), contentAlignment = Alignment.TopCenter) {
                Image(
                    provider = ImageProvider(bitmap),
                    contentDescription = null,
                    modifier = GlanceModifier.size(GaugeSpec.Size),
                )
                Box(modifier = GlanceModifier.size(GaugeSpec.Size), contentAlignment = Alignment.Center) {
                    Image(
                        provider = ImageProvider(state.slot.icon),
                        contentDescription = null,
                        modifier = GlanceModifier.size(GaugeSpec.IconSize),
                        colorFilter = ColorFilter.tint(ColorProvider(content)),
                    )
                }
                Column(
                    modifier = GlanceModifier.fillMaxSize(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Number(state, content)
                }
            }
        }
    }

    @Composable
    private fun Number(state: GaugeState, content: Color) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Toggle visibility instead of omitting: RemoteViews reapply can keep stale children.
            Image(
                provider = ImageProvider(R.drawable.ic_widget_bolt),
                contentDescription = null,
                modifier = GlanceModifier
                    .width(GaugeSpec.BoltWidth)
                    .height(GaugeSpec.BoltHeight)
                    .visibility(if (state.charging) Visibility.Visible else Visibility.Gone),
                colorFilter = ColorFilter.tint(ColorProvider(content)),
            )
            Spacer(GlanceModifier.width(if (state.charging) GaugeSpec.BoltGap else 0.dp))
            Text(
                text = state.text,
                style = TextStyle(
                    color = ColorProvider(content),
                    fontSize = GaugeSpec.NumberSize,
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
    drawGaugeArc(Canvas(bitmap), sizePx.toFloat(), strokePx, fraction, Track.toArgb(), color)
    return bitmap
}

class BudsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BudsWidget()
}
