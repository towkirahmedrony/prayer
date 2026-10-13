package io.github.towkirahmedrony.salah.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Per-prayer icons, so Asr, Maghrib, Isha and the Ramadan tile no longer share one glyph.
 * Drawn on the same 24-unit grid as [SalahGlyphs].
 */
@Composable
private fun PrayerGlyph(
    modifier: Modifier = Modifier,
    draw: DrawScope.() -> Unit,
) {
    Canvas(modifier = modifier.size(24.dp)) {
        val unit = size.minDimension / 24f
        scale(scaleX = unit, scaleY = unit, pivot = Offset.Zero) {
            draw()
        }
    }
}

/** A star with [points] points, centred on ([cx], [cy]). */
private fun starPath(cx: Float, cy: Float, outer: Float, inner: Float, points: Int): Path =
    Path().apply {
        val vertices = points * 2
        for (i in 0 until vertices) {
            val radius = if (i % 2 == 0) outer else inner
            val angle = (-PI / 2 + i * PI / points).toFloat()
            val x = cx + radius * cos(angle)
            val y = cy + radius * sin(angle)
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }

/** Asr: a clock face. */
@Composable
fun ClockGlyph(tint: Color, modifier: Modifier = Modifier) = PrayerGlyph(modifier) {
    drawCircle(tint, radius = 8.5f, center = Offset(12f, 12f), style = Stroke(width = 1.8f))
    drawLine(tint, Offset(12f, 12f), Offset(12f, 7.2f), strokeWidth = 1.8f, cap = StrokeCap.Round)
    drawLine(tint, Offset(12f, 12f), Offset(15.6f, 14.2f), strokeWidth = 1.8f, cap = StrokeCap.Round)
}

/** Maghrib: the sun going down onto the horizon, with a downward arrow. */
@Composable
fun SunsetGlyph(tint: Color, modifier: Modifier = Modifier) = PrayerGlyph(modifier) {
    drawArc(
        color = tint,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(6.5f, 13.5f),
        size = Size(11f, 11f),
        style = Stroke(width = 1.8f, cap = StrokeCap.Round),
    )
    drawLine(tint, Offset(3f, 19f), Offset(21f, 19f), strokeWidth = 1.8f, cap = StrokeCap.Round)
    drawLine(tint, Offset(12f, 3.5f), Offset(12f, 9f), strokeWidth = 1.8f, cap = StrokeCap.Round)
    drawLine(tint, Offset(9.6f, 6.8f), Offset(12f, 9.2f), strokeWidth = 1.8f, cap = StrokeCap.Round)
    drawLine(tint, Offset(14.4f, 6.8f), Offset(12f, 9.2f), strokeWidth = 1.8f, cap = StrokeCap.Round)
}

/** Isha: an outlined crescent moon with two small stars. */
@Composable
fun NightGlyph(tint: Color, modifier: Modifier = Modifier) = PrayerGlyph(modifier) {
    val full = Path().apply { addOval(Rect(center = Offset(10.5f, 13f), radius = 7.5f)) }
    val cut = Path().apply { addOval(Rect(center = Offset(14f, 10f), radius = 6.5f)) }
    val moon = Path.combine(PathOperation.Difference, full, cut)
    drawPath(moon, tint, style = Stroke(width = 1.7f, join = StrokeJoin.Round))
    drawPath(starPath(18.5f, 6f, 2.4f, 0.8f, 4), tint)
    drawPath(starPath(20.2f, 13.5f, 1.6f, 0.55f, 4), tint)
}

/** Ramadan: a solid crescent with a five-point star. */
@Composable
fun RamadanGlyph(tint: Color, modifier: Modifier = Modifier) = PrayerGlyph(modifier) {
    val crescent = Path().apply {
        fillType = PathFillType.EvenOdd
        addOval(Rect(center = Offset(10.5f, 12.5f), radius = 7.8f))
        addOval(Rect(center = Offset(14.2f, 10.2f), radius = 6.8f))
    }
    drawPath(crescent, tint)
    drawPath(starPath(18f, 9f, 3f, 1.25f, 5), tint)
}
