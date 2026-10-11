package io.github.towkirahmedrony.salah.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Salah's iconography, drawn directly with Compose.
 *
 * The glyphs are authored on a 24×24 unit grid and scaled to the requested size, so they stay
 * crisp at any density and add no third-party icon dependency to the build.
 */
@Composable
private fun Glyph(
    tint: Color,
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

/** A mosque dome with a base: the Home destination. */
@Composable
fun DomeGlyph(tint: Color, modifier: Modifier = Modifier) = Glyph(tint, modifier) {
    val dome = Path().apply {
        moveTo(6.5f, 19f)
        lineTo(6.5f, 12.5f)
        cubicTo(6.5f, 8.6f, 9.0f, 5.5f, 12f, 5.5f)
        cubicTo(15.0f, 5.5f, 17.5f, 8.6f, 17.5f, 12.5f)
        lineTo(17.5f, 19f)
        close()
    }
    drawPath(dome, tint)
    drawRoundRect(
        color = tint,
        topLeft = Offset(4f, 19.4f),
        size = Size(16f, 1.8f),
        cornerRadius = CornerRadius(0.9f, 0.9f),
    )
}

/** A crescent: the Prayer destination. */
@Composable
fun CrescentGlyph(tint: Color, modifier: Modifier = Modifier) = Glyph(tint, modifier) {
    val crescent = Path().apply {
        fillType = PathFillType.EvenOdd
        addOval(Rect(center = Offset(11.5f, 12f), radius = 8f))
        addOval(Rect(center = Offset(15.5f, 9.5f), radius = 8f))
    }
    drawPath(crescent, tint)
}

/** A calendar page: the Calendar destination. */
@Composable
fun CalendarGlyph(tint: Color, modifier: Modifier = Modifier) = Glyph(tint, modifier) {
    val stroke = Stroke(width = 1.7f)
    drawRoundRect(
        color = tint,
        topLeft = Offset(3.4f, 5.2f),
        size = Size(17.2f, 15.2f),
        cornerRadius = CornerRadius(2.6f, 2.6f),
        style = stroke,
    )
    drawLine(tint, Offset(3.4f, 9.8f), Offset(20.6f, 9.8f), strokeWidth = 1.7f)
    drawLine(tint, Offset(8.2f, 3f), Offset(8.2f, 6.6f), strokeWidth = 1.7f)
    drawLine(tint, Offset(15.8f, 3f), Offset(15.8f, 6.6f), strokeWidth = 1.7f)
    drawCircle(tint, radius = 1.1f, center = Offset(8.6f, 14f))
    drawCircle(tint, radius = 1.1f, center = Offset(12f, 14f))
}

/** A lantern: the Ramadan destination. */
@Composable
fun LanternGlyph(tint: Color, modifier: Modifier = Modifier) = Glyph(tint, modifier) {
    val stroke = Stroke(width = 1.7f)
    drawRoundRect(
        color = tint,
        topLeft = Offset(8.4f, 8.6f),
        size = Size(7.2f, 10.4f),
        cornerRadius = CornerRadius(2.6f, 2.6f),
        style = stroke,
    )
    val roof = Path().apply {
        moveTo(8.0f, 8.6f)
        lineTo(12f, 4.4f)
        lineTo(16f, 8.6f)
    }
    drawPath(roof, tint, style = stroke)
    drawLine(tint, Offset(12f, 19f), Offset(12f, 21f), strokeWidth = 1.7f)
    drawCircle(tint, radius = 1.2f, center = Offset(12f, 13.8f))
}

/** A compass needle: the Qibla shortcut. */
@Composable
fun QiblaGlyph(tint: Color, modifier: Modifier = Modifier) = Glyph(tint, modifier) {
    drawCircle(tint, radius = 8.6f, center = Offset(12f, 12f), style = Stroke(width = 1.6f))
    val needle = Path().apply {
        moveTo(12f, 4.6f)
        lineTo(15.2f, 12f)
        lineTo(12f, 19.4f)
        lineTo(8.8f, 12f)
        close()
    }
    drawPath(needle, tint)
}

/** A gear: the Settings destination. */
@Composable
fun GearGlyph(tint: Color, modifier: Modifier = Modifier) = Glyph(tint, modifier) {
    drawCircle(tint, radius = 8.2f, center = Offset(12f, 12f), style = Stroke(width = 1.7f))
    drawCircle(tint, radius = 3.1f, center = Offset(12f, 12f), style = Stroke(width = 1.7f))
    repeat(8) { index ->
        val radians = (index * 45.0) * PI / 180.0
        val innerX = 12f + (8.2f * cos(radians)).toFloat()
        val innerY = 12f + (8.2f * sin(radians)).toFloat()
        val outerX = 12f + (10.4f * cos(radians)).toFloat()
        val outerY = 12f + (10.4f * sin(radians)).toFloat()
        drawLine(tint, Offset(innerX, innerY), Offset(outerX, outerY), strokeWidth = 1.7f)
    }
}
