package io.github.neisvestney.budssniffer.ui.gauge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.neisvestney.budssniffer.R
import io.github.neisvestney.budssniffer.buds.BudsBattery

@Composable
fun BudGaugePill(battery: BudsBattery?, live: Boolean, modifier: Modifier = Modifier) {
    val foreground = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, CircleShape)
            .padding(
                start = GaugeSpec.PillPaddingHorizontal,
                top = GaugeSpec.PillPaddingTop,
                end = GaugeSpec.PillPaddingHorizontal,
                bottom = GaugeSpec.PillPaddingBottom,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (state in gaugeStates(battery, live)) Gauge(state, foreground)
    }
}

@Composable
private fun RowScope.Gauge(state: GaugeState, foreground: Color) {
    val content = if (state.live) foreground else foreground.copy(alpha = GaugeSpec.DIMMED_ALPHA)
    val arc = if (state.charging) GaugeSpec.ChargingColor else content
    val track = foreground.copy(alpha = GaugeSpec.TRACK_ALPHA)
    Box(
        modifier = Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = state.description },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(GaugeSpec.Size, GaugeSpec.Height), contentAlignment = Alignment.TopCenter) {
            Canvas(Modifier.size(GaugeSpec.Size)) {
                drawIntoCanvas {
                    drawGaugeArc(it.nativeCanvas, size.minDimension, GaugeSpec.Stroke.toPx(), state.fraction, track.toArgb(), arc.toArgb())
                }
            }
            Box(Modifier.size(GaugeSpec.Size), contentAlignment = Alignment.Center) {
                Icon(painterResource(state.slot.icon), contentDescription = null, tint = content, modifier = Modifier.size(GaugeSpec.IconSize))
            }
            Number(state, content, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun Number(state: GaugeState, content: Color, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (state.charging) {
            Icon(
                painterResource(R.drawable.ic_widget_bolt),
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(GaugeSpec.BoltWidth, GaugeSpec.BoltHeight),
            )
            Spacer(Modifier.width(GaugeSpec.BoltGap))
        }
        Text(
            state.text,
            style = TextStyle(color = content, fontSize = GaugeSpec.NumberSize, fontWeight = FontWeight.Bold),
        )
    }
}
