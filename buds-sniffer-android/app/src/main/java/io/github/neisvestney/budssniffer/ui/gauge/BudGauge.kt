package io.github.neisvestney.budssniffer.ui.gauge

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.neisvestney.budssniffer.R
import io.github.neisvestney.budssniffer.buds.BudLevel
import io.github.neisvestney.budssniffer.buds.BudsBattery

// Shared by the Glance widget and the in-app card so both render the same gauge.
object GaugeSpec {
    val Size = 52.dp
    val Height = 59.dp
    val Stroke = 7.5.dp
    val IconSize = 24.dp
    // Matches the digits' cap height at 13sp bold.
    val BoltHeight = 9.5.dp
    val BoltWidth = 5.2.dp
    val BoltGap = 1.dp
    val NumberSize = 13.sp
    // Asymmetric to offset the digits' font padding, so arc top and digit baseline sit equally from the edges.
    val PillPaddingHorizontal = 12.dp
    val PillPaddingTop = 11.dp
    val PillPaddingBottom = 8.dp
    val ChargingColor = Color(0xFF59C376)
    val WidgetBackground = Color(0xE0141416)
    val WidgetForeground = Color(0xFFFFFFFF)
    const val DIMMED_ALPHA = 0.5f
    const val TRACK_ALPHA = 0.2f

    // Gauge is open at the bottom, like One UI's battery widget.
    const val ARC_START = 160f
    const val ARC_SWEEP = 220f
}

enum class BudSlot(@param:DrawableRes val icon: Int, val label: String) {
    Left(R.drawable.ic_widget_bud_left, "Left"),
    Right(R.drawable.ic_widget_bud_right, "Right"),
    Case(R.drawable.ic_widget_case, "Case");

    fun levelOf(battery: BudsBattery?): BudLevel? = when (this) {
        Left -> battery?.left
        Right -> battery?.right
        Case -> battery?.case
    }
}

class GaugeState(val slot: BudSlot, level: BudLevel?, val live: Boolean) {
    // A stale level can't claim it is still charging.
    val charging = live && level?.charging == true
    val fraction = (level?.percent ?: 0).coerceIn(0, 100) / 100f
    val text = level?.percent?.toString() ?: "—"
    val description = buildString {
        append(slot.label).append(' ').append(level?.let { "${it.percent}%" } ?: "unknown")
        if (charging) append(", charging")
    }
}

fun gaugeStates(battery: BudsBattery?, live: Boolean) =
    BudSlot.entries.map { GaugeState(it, it.levelOf(battery), live) }

fun drawGaugeArc(canvas: Canvas, sizePx: Float, strokePx: Float, fraction: Float, track: Int, arc: Int) {
    val inset = strokePx / 2
    val bounds = RectF(inset, inset, sizePx - inset, sizePx - inset)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        strokeCap = Paint.Cap.ROUND
        color = track
    }
    canvas.drawArc(bounds, GaugeSpec.ARC_START, GaugeSpec.ARC_SWEEP, false, paint)
    if (fraction > 0f) {
        paint.color = arc
        canvas.drawArc(bounds, GaugeSpec.ARC_START, GaugeSpec.ARC_SWEEP * fraction, false, paint)
    }
}
