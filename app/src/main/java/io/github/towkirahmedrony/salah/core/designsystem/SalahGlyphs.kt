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

@Composable
fun CrescentGlyph(tint: Color, modifier: Modifier = Modifier) = Glyph(tint, modifier) {
    val crescent = Path().apply {
        fillType = PathFillType.EvenOdd
        addOval(Rect(center = Offset(11.5f, 12f), radius = 8f))
        addOval(Rect(center = Offset(15.5f, 9.5f), radius = 8f))
    }
    drawPath(crescent, tint)
}

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
}

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
}

@Composable
fun QiblaGlyph(tint: Color, modifier: Modifier = Modifier) = Glyph(tint, modifier) {
    drawCircle(tint, radius = 9f, center = Offset(12f, 12f), style = Stroke(width = 1.6f))
    val needle = Path().apply {
        moveTo(12f, 5.5f)
        lineTo(15.5f, 12f)
        lineTo(12f, 18.5f)
        lineTo(8.5f, 12f)
        close()
    }
    drawPath(needle, tint, style = Stroke(width = 1.5f))
}

@Composable
fun TasbihGlyph(tint: Color, modifier: Modifier = Modifier) = Glyph(tint, modifier) {
    drawCircle(tint, radius = 7.5f, center = Offset(12f, 11f), style = Stroke(width = 2.2f))
    drawLine(tint, Offset(12f, 18.5f), Offset(12f, 22f), strokeWidth = 2.2f)
}

@Composable
fun GearGlyph(tint: Color, modifier: Modifier = Modifier) = Glyph(tint, modifier) {
    drawCircle(tint, radius = 8f, center = Offset(12f, 12f), style = Stroke(width = 1.7f))
    drawCircle(tint, radius = 3.2f, center = Offset(12f, 12f), style = Stroke(width = 1.7f))
}

@Composable
fun SunrisePrayerGlyph(tint: Color, modifier: Modifier = Modifier) = Glyph(tint, modifier) {
    drawArc(tint, startAngle = 180f, sweepAngle = 180f, useCenter = false, topLeft = Offset(6f, 7f), size = Size(12f, 12f), style = Stroke(width = 1.8f))
    drawLine(tint, Offset(3f, 19f), Offset(21f, 19f), strokeWidth = 1.8f)
    drawLine(tint, Offset(12f, 3f), Offset(12f, 5.5f), strokeWidth = 1.8f)
}

@Composable
fun OctagramGlyph(tint: Color, modifier: Modifier = Modifier) = Glyph(tint, modifier) {
    drawRect(tint, topLeft = Offset(6f, 6f), size = Size(12f, 12f), style = Stroke(width = 1.8f))
    scale(scale = 1f, pivot = Offset(12f, 12f)) {
        // Rotated square form
        val path = Path().apply {
            moveTo(12f, 3.5f); lineTo(20.5f, 12f); lineTo(12f, 20.5f); lineTo(3.5f, 12f); close()
        }
        drawPath(path, tint, style = Stroke(width = 1.8f))
    }
}

@Composable
fun SunRaysArt(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val center = Offset(size.width, size.height * 0.5f)
        val radius = size.minDimension * 0.45f
        drawCircle(color = tint, radius = radius, center = center, style = Stroke(width = 1.8f))
        drawCircle(color = tint, radius = radius * 0.85f, center = center, style = Stroke(width = 1.2f))
        repeat(16) { i ->
            val angle = (i * 22.5 * PI / 180.0)
            val startR = radius + 6f
            val endR = radius + if (i % 2 == 0) 36f else 20f
            val p1 = Offset(center.x - (startR * cos(angle)).toFloat(), center.y + (startR * sin(angle)).toFloat())
            val p2 = Offset(center.x - (endR * cos(angle)).toFloat(), center.y + (endR * sin(angle)).toFloat())
            drawLine(color = tint, start = p1, end = p2, strokeWidth = 1.6f)
        }
    }
}
