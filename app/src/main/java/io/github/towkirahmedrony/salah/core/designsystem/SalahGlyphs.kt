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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
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

/**
 * The Tasbih counter's own mark: a ring of beads with a tassel.
 *
 * Separate from [TasbihGlyph] rather than shared with it. That glyph is the *Tools tab's* mark in the
 * bottom bar, and drawing it again on the Tools screen's Tasbih row would put the tab's own icon on one of
 * the tab's rows — a reader would reasonably conclude that the row goes back where they came from. This
 * one is the counter: the beads are drawn individually so the mark reads as something being counted rather
 * than as a closed loop, and the tassel is what makes it a misbaha at a glance and not a clock face.
 *
 * Same canvas language as every other glyph in this file — a 24-unit box, scaled to the size the caller
 * gives it, painted in the caller's tint — so it re-themes with the rest of the design system and adds no
 * icon dependency.
 */
@Composable
fun TasbihBeadsGlyph(tint: Color, modifier: Modifier = Modifier) = Glyph(tint, modifier) {
    val centre = Offset(12f, 11.5f)
    repeat(COUNTING_BEADS) { index ->
        // The first bead sits at the top, so the ring has a clear "start" and the tassel hangs opposite it.
        val angle = (2.0 * PI * index / COUNTING_BEADS) - (PI / 2.0)
        drawCircle(
            color = tint,
            radius = 1.8f,
            center = Offset(
                x = centre.x + (BEAD_RING_RADIUS * cos(angle)).toFloat(),
                y = centre.y + (BEAD_RING_RADIUS * sin(angle)).toFloat(),
            ),
        )
    }
    // The tassel, drawn clear of the lowest bead rather than through it.
    drawLine(tint, Offset(12f, 20.5f), Offset(12f, 22.5f), strokeWidth = 1.6f)
}

/** How many beads the counter's mark is drawn with — enough to read as a strand at 24dp. */
private const val COUNTING_BEADS = 8

/** Radius of the bead ring in the glyph's own 24-unit box. */
private const val BEAD_RING_RADIUS = 7.5f

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

@Composable
fun BellGlyph(tint: Color, modifier: Modifier = Modifier) = Glyph(tint, modifier) {
    val outline = Path().apply {
        moveTo(5f, 17.5f)
        cubicTo(7f, 15.8f, 7.5f, 14.2f, 7.5f, 11.5f)
        cubicTo(7.5f, 8.7f, 9.4f, 6.5f, 12f, 6.5f)
        cubicTo(14.6f, 6.5f, 16.5f, 8.7f, 16.5f, 11.5f)
        cubicTo(16.5f, 14.2f, 17f, 15.8f, 19f, 17.5f)
        close()
    }
    drawPath(outline, tint, style = Stroke(width = 1.8f))
    drawLine(tint, Offset(4.5f, 18.5f), Offset(19.5f, 18.5f), strokeWidth = 1.8f)
    drawArc(
        color = tint,
        startAngle = 0f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(10f, 18.5f),
        size = Size(4f, 4f),
        style = Stroke(width = 1.6f),
    )
}

@Composable
fun PersonGlyph(tint: Color, modifier: Modifier = Modifier) = Glyph(tint, modifier) {
    drawCircle(tint, radius = 10f, center = Offset(12f, 12f), style = Stroke(width = 1.7f))
    drawCircle(tint, radius = 3f, center = Offset(12f, 9f), style = Stroke(width = 1.7f))
    drawArc(
        color = tint,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(6.5f, 12f),
        size = Size(11f, 8f),
        style = Stroke(width = 1.7f),
    )
}

@Composable
fun LocationPinGlyph(tint: Color, modifier: Modifier = Modifier) = Glyph(tint, modifier) {
    val pin = Path().apply {
        moveTo(12f, 21f)
        cubicTo(10f, 18f, 5f, 13.7f, 5f, 10f)
        cubicTo(5f, 6.1f, 8f, 3f, 12f, 3f)
        cubicTo(16f, 3f, 19f, 6.1f, 19f, 10f)
        cubicTo(19f, 13.7f, 14f, 18f, 12f, 21f)
        close()
    }
    drawPath(pin, tint, style = Stroke(width = 1.8f))
    drawCircle(tint, radius = 2f, center = Offset(12f, 10f))
}

/**
 * The same pin, filled.
 *
 * The mark the location setup card uses for the manual option. The hole is punched with
 * [PathFillType.EvenOdd] rather than drawn as a white circle, so it stays the card's own colour in
 * either theme instead of a white dot on a dark card.
 */
@Composable
fun LocationPinFilledGlyph(tint: Color, modifier: Modifier = Modifier) = Glyph(tint, modifier) {
    val pin = Path().apply {
        fillType = PathFillType.EvenOdd
        moveTo(12f, 21f)
        cubicTo(10f, 18f, 5f, 13.7f, 5f, 10f)
        cubicTo(5f, 6.1f, 8f, 3f, 12f, 3f)
        cubicTo(16f, 3f, 19f, 6.1f, 19f, 10f)
        cubicTo(19f, 13.7f, 14f, 18f, 12f, 21f)
        close()
        addOval(Rect(center = Offset(12f, 10f), radius = 2.8f))
    }
    drawPath(pin, tint)
}

/** A downward chevron, for a control that opens a list. */
@Composable
fun ChevronDownGlyph(tint: Color, modifier: Modifier = Modifier) = Glyph(tint, modifier) {
    val stroke = Stroke(width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val chevron = Path().apply {
        moveTo(6.5f, 9.5f)
        lineTo(12f, 15f)
        lineTo(17.5f, 9.5f)
    }
    drawPath(chevron, tint, style = stroke)
}

/** A right-pointing chevron, for a row that opens a screen of its own. */
@Composable
fun ChevronRightGlyph(tint: Color, modifier: Modifier = Modifier) = Glyph(tint, modifier) {
    val stroke = Stroke(width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val chevron = Path().apply {
        moveTo(9.5f, 6.5f)
        lineTo(15f, 12f)
        lineTo(9.5f, 17.5f)
    }
    drawPath(chevron, tint, style = stroke)
}

/** The same chevron pointing back: the header's back action on a configuration subpage. */
@Composable
fun ChevronLeftGlyph(tint: Color, modifier: Modifier = Modifier) = Glyph(tint, modifier) {
    val stroke = Stroke(width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val chevron = Path().apply {
        moveTo(14.5f, 6.5f)
        lineTo(9f, 12f)
        lineTo(14.5f, 17.5f)
    }
    drawPath(chevron, tint, style = stroke)
}

/** A crescent moon: the appearance category, whose subject is light and dark. */
@Composable
fun MoonGlyph(tint: Color, modifier: Modifier = Modifier) = Glyph(tint, modifier) {
    // The bite is punched with [PathFillType.EvenOdd] rather than drawn as a second colour, so the
    // mark is one shape and sits on any background in either theme.
    val moon = Path().apply {
        fillType = PathFillType.EvenOdd
        addOval(Rect(center = Offset(12f, 12f), radius = 8.6f))
        addOval(Rect(center = Offset(17f, 8.5f), radius = 8f))
    }
    drawPath(moon, tint)
}

/** Three sliders: the general-preferences category, whose subject is adjustment. */
@Composable
fun SlidersGlyph(tint: Color, modifier: Modifier = Modifier) = Glyph(tint, modifier) {
    // The knob is drawn after its own rail, so the rail reads as passing behind a solid handle rather
    // than as two dashes meeting in the middle.
    listOf(7.5f to 9.5f, 12f to 15f, 16.5f to 8.5f).forEach { (y, knobX) ->
        drawLine(tint, Offset(4.5f, y), Offset(19.5f, y), strokeWidth = 1.7f)
        drawCircle(tint, radius = 2.5f, center = Offset(knobX, y))
    }
}

/**
 * A ruler: a scale with tick marks, for a distance.
 *
 * Drawn on the diagonal because a horizontal ruler reads as a progress bar at this size, and the
 * diagonal is what makes it recognisable next to the compass needle it sits beside on the Qibla
 * screen.
 */
@Composable
fun RulerGlyph(tint: Color, modifier: Modifier = Modifier) = Glyph(tint, modifier) {
    val stroke = Stroke(width = 1.6f, cap = StrokeCap.Round)
    rotate(degrees = DIAGONAL_DEGREES, pivot = Offset(12f, 12f)) {
        drawRoundRect(
            color = tint,
            topLeft = Offset(3.6f, 8.6f),
            size = Size(16.8f, 6.8f),
            cornerRadius = CornerRadius(1.6f, 1.6f),
            style = stroke,
        )
        listOf(7.0f, 10.2f, 13.4f, 16.6f).forEach { x ->
            drawLine(tint, Offset(x, 8.6f), Offset(x, 12f), strokeWidth = 1.3f)
        }
    }
}

/**
 * A check mark: an affirmative answer, used where a reading is confirmed good rather than merely
 * present.
 */
@Composable
fun CheckGlyph(tint: Color, modifier: Modifier = Modifier) = Glyph(tint, modifier) {
    val check = Path().apply {
        moveTo(4.6f, 12.6f)
        lineTo(9.8f, 17.8f)
        lineTo(19.4f, 6.4f)
    }
    drawPath(check, tint, style = Stroke(width = 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/**
 * A magnifier: the manual location search field's leading mark.
 *
 * A circle and a handle, drawn as strokes rather than as a filled lens, so at 16–20 dp it reads as an
 * outline beside the field's text instead of a dark blob.
 */
@Composable
fun SearchGlyph(tint: Color, modifier: Modifier = Modifier) = Glyph(tint, modifier) {
    drawCircle(
        color = tint,
        radius = 6.4f,
        center = Offset(10.6f, 10.6f),
        style = Stroke(width = 2f),
    )
    drawLine(
        color = tint,
        start = Offset(15.4f, 15.4f),
        end = Offset(19.4f, 19.4f),
        strokeWidth = 2f,
        cap = StrokeCap.Round,
    )
}

/** An X: the clear-search affordance, paired with its localized content description at the call site. */
@Composable
fun CloseGlyph(tint: Color, modifier: Modifier = Modifier) = Glyph(tint, modifier) {
    drawLine(
        color = tint,
        start = Offset(6.8f, 6.8f),
        end = Offset(17.2f, 17.2f),
        strokeWidth = 2.2f,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = tint,
        start = Offset(17.2f, 6.8f),
        end = Offset(6.8f, 17.2f),
        strokeWidth = 2.2f,
        cap = StrokeCap.Round,
    )
}

/**
 * A broadcast mark: a dot with two waves leaving it on each side.
 *
 * Used where a screen offers to ask the device for its own position, so the two ways of giving Salah
 * a location are told apart by their icons as well as by their titles.
 */
@Composable
fun SignalGlyph(tint: Color, modifier: Modifier = Modifier) = Glyph(tint, modifier) {
    drawCircle(tint, radius = 1.7f, center = Offset(12f, 12f))
    listOf(4.6f, 7.6f).forEach { radius ->
        listOf(135f, -45f).forEach { startAngle ->
            drawArc(
                color = tint,
                startAngle = startAngle,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(12f - radius, 12f - radius),
                size = Size(radius * 2f, radius * 2f),
                style = Stroke(width = 1.7f),
            )
        }
    }
}

/** The angle the ruler is drawn on, so its own length leaves the 24-unit box on the diagonal. */
private const val DIAGONAL_DEGREES = -40f
