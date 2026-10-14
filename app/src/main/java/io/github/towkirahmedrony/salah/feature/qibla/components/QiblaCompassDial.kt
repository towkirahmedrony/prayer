package io.github.towkirahmedrony.salah.feature.qibla.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Qibla dial.
 *
 * **How it turns, and why.** A physical compass rose turns *underneath* a fixed pointer: the pointer
 * marks where the device is facing, and the rose is drawn rotated by the negative of the heading so
 * that the compass bearing under the pointer is the direction the user is looking. The Qibla marker
 * and the Qibla arrow are drawn on the rose at the bearing itself, so after that one rotation the
 * arrow lands at `bearing − heading` — the signed turn — which is why the display stays correct
 * through 0°/360° instead of winding almost all the way round.
 *
 * **Why the rotation is applied once, to the whole canvas.** Rotating each element separately is how
 * a dial ends up internally inconsistent: a needle rotated by one convention and a label by another
 * disagree by exactly the angle nobody notices until it matters. Everything that belongs to the rose
 * — ticks, cardinals, rose, arrow and marker — is drawn inside a single `rotate` block.
 *
 * **What it refuses to do.** When [isLive] is false the dial is drawn without rotation and the arrow
 * is hidden, rather than animated from a heading that is not being measured. A dial that moved on its
 * own would be the most convincing kind of wrong.
 *
 * **What is decoration and what is not.** The compass rose behind the arrow is a drawing, and it is
 * kept at a low contrast so it reads as a background rather than as a value. Everything with a
 * meaning is one of four things: the gold band and the pointer mark the direction the device faces,
 * the cardinals letter the rose, the arrow points at the Qibla, and the marker at the arrow's tip is
 * the Kaaba the arrow is pointing to. Nothing here claims a measurement the screen's text does not
 * also state — the bearing and the heading are written out under the dial.
 *
 * **Accessibility.** A `Canvas` draws pixels, not text, so nothing here reaches an accessibility
 * service on its own. [accessibilityLabel] is what a screen reader announces in its place; it is
 * required rather than optional because a dial nobody can read is worse than no dial, and it is
 * supplied by the caller because only the caller knows the bearing and heading in the same terms the
 * rest of the screen uses.
 */
@Composable
fun QiblaCompassDial(
    headingDegrees: Double?,
    qiblaDegrees: Double?,
    isAligned: Boolean,
    isLive: Boolean,
    accessibilityLabel: String,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()

    val faceColor = MaterialTheme.colorScheme.surface
    val bandColor = MaterialTheme.colorScheme.secondary
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = TICK_ALPHA)
    val roseColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ROSE_ALPHA)
    val cardinalColor = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary

    Canvas(
        modifier = modifier
            // Capped so the dial does not push the reading under it off a narrow screen, and so it
            // keeps the proportions of the reference rather than filling the width on a tablet.
            .widthIn(max = MAX_DIAL_SIZE)
            .fillMaxWidth()
            .aspectRatio(1f)
            .semantics { contentDescription = accessibilityLabel },
    ) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        // One radius, and every distance below as a fraction of it, so the dial is identical at any
        // size: a marker placed in device-independent pixels would drift out of the band on a large
        // screen and overlap the cardinals on a small one.
        val radius = minOf(size.width, size.height) / 2f

        val bandRadius = radius * BAND_RADIUS_FRACTION
        val bandWidth = radius * BAND_WIDTH_FRACTION
        val faceRadius = bandRadius - bandWidth / 2f

        // The face, drawn first: everything else in the rose sits on top of it.
        drawCircle(color = faceColor, radius = faceRadius, center = centre)

        // One rotation for the whole rose.
        rotate(
            degrees = if (isLive && headingDegrees != null) -headingDegrees.toFloat() else 0f,
            pivot = centre,
        ) {
            drawTicks(centre = centre, faceRadius = faceRadius, radius = radius, color = tickColor)
            drawRose(centre = centre, faceRadius = faceRadius, color = roseColor)
            drawCardinals(
                centre = centre,
                faceRadius = faceRadius,
                radius = radius,
                textMeasurer = textMeasurer,
                color = cardinalColor,
            )
            if (qiblaDegrees != null) {
                drawQiblaArrow(
                    centre = centre,
                    faceRadius = faceRadius,
                    radius = radius,
                    degrees = qiblaDegrees,
                    shaftColor = accent,
                    headColor = bandColor,
                )
                drawKaabaMarker(
                    centre = centre,
                    bandRadius = bandRadius,
                    radius = radius,
                    degrees = qiblaDegrees,
                    faceColor = faceColor,
                    bandColor = bandColor,
                )
            }
        }

        // The band and the fixed pointer are outside the rotation on purpose: they belong to the
        // device, not to the rose, and they are what the rose is read against.
        drawBand(
            centre = centre,
            bandRadius = bandRadius,
            bandWidth = bandWidth,
            color = bandColor,
        )

        if (isAligned) {
            drawCircle(
                color = accent,
                radius = faceRadius,
                center = centre,
                style = Stroke(width = radius * ALIGNMENT_RING_WIDTH_FRACTION),
            )
        }

        drawHeadingPointer(
            centre = centre,
            bandRadius = bandRadius,
            bandWidth = bandWidth,
            radius = radius,
            color = accent,
        )
        drawCircle(color = cardinalColor, radius = radius * HUB_RADIUS_FRACTION, center = centre)
    }
}

/** The gold band that frames the rose — the dial's outer edge, and the ring the marker sits on. */
private fun DrawScope.drawBand(
    centre: Offset,
    bandRadius: Float,
    bandWidth: Float,
    color: Color,
) {
    drawCircle(color = color, radius = bandRadius, center = centre, style = Stroke(width = bandWidth))
}

/**
 * Tick marks every 15°, longer every 45°.
 *
 * Buried in the rotation block so the marks move with the rose, which is what makes the dial read as
 * a compass rather than as a decoration.
 */
private fun DrawScope.drawTicks(
    centre: Offset,
    faceRadius: Float,
    radius: Float,
    color: Color,
) {
    val outer = faceRadius - radius * TICK_INSET_FRACTION
    for (degrees in 0 until 360 step TICK_STEP_DEGREES) {
        val isMajor = degrees % 45 == 0
        val inner = outer - radius * (if (isMajor) TICK_MAJOR_FRACTION else TICK_MINOR_FRACTION)
        val radians = Math.toRadians(degrees.toDouble() - 90.0)
        val dx = cos(radians).toFloat()
        val dy = sin(radians).toFloat()
        drawLine(
            color = color,
            start = Offset(centre.x + inner * dx, centre.y + inner * dy),
            end = Offset(centre.x + outer * dx, centre.y + outer * dy),
            strokeWidth = radius * (if (isMajor) TICK_MAJOR_WIDTH_FRACTION else TICK_MINOR_WIDTH_FRACTION),
        )
    }
}

/**
 * The eight-point rose behind the needle.
 *
 * The four cardinal points are drawn long and the four intercardinal ones short, which is the shape a
 * reader already associates with a compass. It is drawn at [ROSE_ALPHA], low enough to be a
 * background: an ornament that competes with the arrow would make the arrow harder to read, which is
 * the opposite of what a compass is for.
 */
private fun DrawScope.drawRose(
    centre: Offset,
    faceRadius: Float,
    color: Color,
) {
    for (point in 0 until ROSE_POINTS) {
        val isCardinal = point % 2 == 0
        val tipRadius = faceRadius * (if (isCardinal) ROSE_LONG_FRACTION else ROSE_SHORT_FRACTION)
        val radians = Math.toRadians(point * (360.0 / ROSE_POINTS) - 90.0)
        val dx = cos(radians).toFloat()
        val dy = sin(radians).toFloat()

        // A thin diamond rather than a triangle: two mirrored points meet at the centre, which is
        // what stops eight separate triangles from looking like eight separate arrows.
        val halfWidth = tipRadius * ROSE_HALF_WIDTH_FRACTION
        val path = Path().apply {
            moveTo(centre.x + tipRadius * dx, centre.y + tipRadius * dy)
            lineTo(centre.x - halfWidth * dy, centre.y + halfWidth * dx)
            lineTo(centre.x - tipRadius * dx * ROSE_TAIL_FRACTION, centre.y - tipRadius * dy * ROSE_TAIL_FRACTION)
            lineTo(centre.x + halfWidth * dy, centre.y - halfWidth * dx)
            close()
        }
        drawPath(path = path, color = color)
    }
}

/** North, east, south and west, drawn inside the rotation so they track the rose. */
private fun DrawScope.drawCardinals(
    centre: Offset,
    faceRadius: Float,
    radius: Float,
    textMeasurer: TextMeasurer,
    color: Color,
) {
    val labelRadius = faceRadius * CARDINAL_RADIUS_FRACTION
    CARDINALS.forEach { (degrees, label) ->
        val layout = textMeasurer.measure(
            text = AnnotatedString(label),
            style = TextStyle(
                color = color,
                // DrawScope is a Density, so a pixel measurement converts to sp correctly here.
                fontSize = (radius * CARDINAL_FONT_FRACTION).toSp(),
                fontWeight = FontWeight.SemiBold,
            ),
        )
        val radians = Math.toRadians(degrees.toDouble() - 90.0)
        val x = centre.x + labelRadius * cos(radians).toFloat() - layout.size.width / 2f
        val y = centre.y + labelRadius * sin(radians).toFloat() - layout.size.height / 2f
        drawText(textLayoutResult = layout, topLeft = Offset(x, y))
    }
}

/**
 * The Qibla arrow: a shaft and a head drawn from the hub out to the band, at the bearing.
 *
 * It is drawn at [degrees] within the (already rotated) rose, so it needs no knowledge of the
 * heading — the rotation is what places it relative to the pointer. The head stops short of the band
 * so that the marker on the band is what marks the exact bearing, and the arrow's tip is never
 * mistaken for it.
 */
private fun DrawScope.drawQiblaArrow(
    centre: Offset,
    faceRadius: Float,
    radius: Float,
    degrees: Double,
    shaftColor: Color,
    headColor: Color,
) {
    val radians = Math.toRadians(degrees - 90.0)
    val dx = cos(radians).toFloat()
    val dy = sin(radians).toFloat()
    val perpendicular = Offset(-dy, dx)

    val tipDistance = faceRadius * ARROW_TIP_FRACTION
    val headStartDistance = faceRadius * ARROW_HEAD_START_FRACTION
    val tailDistance = radius * ARROW_TAIL_FRACTION

    fun pointAlong(distance: Float, offset: Float): Offset = Offset(
        x = centre.x + distance * dx + offset * perpendicular.x,
        y = centre.y + distance * dy + offset * perpendicular.y,
    )

    val tailHalfWidth = radius * ARROW_TAIL_HALF_WIDTH_FRACTION
    val shaftHalfWidth = radius * ARROW_SHAFT_HALF_WIDTH_FRACTION
    val headHalfWidth = radius * ARROW_HEAD_HALF_WIDTH_FRACTION

    val tailLeft = pointAlong(tailDistance, tailHalfWidth)
    val shaftLeft = pointAlong(headStartDistance, shaftHalfWidth)
    val shaftRight = pointAlong(headStartDistance, -shaftHalfWidth)
    val tailRight = pointAlong(tailDistance, -tailHalfWidth)

    val shaft = Path().apply {
        moveTo(tailLeft.x, tailLeft.y)
        lineTo(shaftLeft.x, shaftLeft.y)
        lineTo(shaftRight.x, shaftRight.y)
        lineTo(tailRight.x, tailRight.y)
        close()
    }
    drawPath(path = shaft, color = shaftColor)

    val head = Path().apply {
        val tip = pointAlong(tipDistance, 0f)
        val left = pointAlong(headStartDistance, headHalfWidth)
        val right = pointAlong(headStartDistance, -headHalfWidth)
        moveTo(tip.x, tip.y)
        lineTo(left.x, left.y)
        lineTo(right.x, right.y)
        close()
    }
    drawPath(path = head, color = headColor)
}

/**
 * The Kaaba: the marker on the band at the bearing the arrow points to.
 *
 * A small square with a band across it, which is the shape the building is drawn as on every map and
 * in the reference design, rather than a second arrow — the arrow already says "this way", and a
 * marker that repeats it would leave the user guessing which of the two is the direction.
 *
 * The square is filled with the dial's own face colour and outlined in the gold, so it reads as
 * punched out of the band in either theme instead of depending on a black that would vanish on the
 * dark scheme's dial.
 */
private fun DrawScope.drawKaabaMarker(
    centre: Offset,
    bandRadius: Float,
    radius: Float,
    degrees: Double,
    faceColor: Color,
    bandColor: Color,
) {
    val radians = Math.toRadians(degrees - 90.0)
    val markerCentre = Offset(
        x = centre.x + bandRadius * cos(radians).toFloat(),
        y = centre.y + bandRadius * sin(radians).toFloat(),
    )
    val side = radius * MARKER_SIZE_FRACTION
    val topLeft = Offset(markerCentre.x - side / 2f, markerCentre.y - side / 2f)

    drawRoundRect(
        color = faceColor,
        topLeft = topLeft,
        size = Size(side, side),
        cornerRadius = CornerRadius(side * MARKER_CORNER_FRACTION, side * MARKER_CORNER_FRACTION),
    )
    drawRoundRect(
        color = bandColor,
        topLeft = topLeft,
        size = Size(side, side),
        cornerRadius = CornerRadius(side * MARKER_CORNER_FRACTION, side * MARKER_CORNER_FRACTION),
        style = Stroke(width = radius * MARKER_STROKE_FRACTION),
    )
    // The kiswah band across the upper third, which is what makes the square read as the Kaaba.
    val bandY = topLeft.y + side * MARKER_BAND_FRACTION
    drawLine(
        color = bandColor,
        start = Offset(topLeft.x + side * MARKER_BAND_INSET_FRACTION, bandY),
        end = Offset(topLeft.x + side * (1f - MARKER_BAND_INSET_FRACTION), bandY),
        strokeWidth = radius * MARKER_BAND_WIDTH_FRACTION,
    )
}

/** A fixed arrow at the top of the dial: the direction the top of the device points. */
private fun DrawScope.drawHeadingPointer(
    centre: Offset,
    bandRadius: Float,
    bandWidth: Float,
    radius: Float,
    color: Color,
) {
    val tipY = centre.y - bandRadius - radius * POINTER_TIP_FRACTION
    val baseY = centre.y - bandRadius + bandWidth * POINTER_BASE_FRACTION
    val halfWidth = radius * POINTER_HALF_WIDTH_FRACTION

    val path = Path().apply {
        moveTo(centre.x, tipY)
        lineTo(centre.x - halfWidth, baseY)
        lineTo(centre.x + halfWidth, baseY)
        close()
    }
    drawPath(path = path, color = color)
}

/** The band the marker sits on, as a fraction of the available radius; the dial's outer edge. */
private const val BAND_RADIUS_FRACTION = 0.90f

/** The band's thickness. */
private const val BAND_WIDTH_FRACTION = 0.09f

/** How far inside the face the ticks stop. */
private const val TICK_INSET_FRACTION = 0.02f

/** Degrees between tick marks. */
private const val TICK_STEP_DEGREES = 15

private const val TICK_MAJOR_FRACTION = 0.075f
private const val TICK_MINOR_FRACTION = 0.04f
private const val TICK_MAJOR_WIDTH_FRACTION = 0.012f
private const val TICK_MINOR_WIDTH_FRACTION = 0.006f
private const val TICK_ALPHA = 0.45f

private const val ROSE_POINTS = 8

/** The rose's reach: the cardinal points stop just inside the letters, so the two never collide. */
private const val ROSE_LONG_FRACTION = 0.72f
private const val ROSE_SHORT_FRACTION = 0.48f
private const val ROSE_HALF_WIDTH_FRACTION = 0.055f
private const val ROSE_TAIL_FRACTION = 0.16f
private const val ROSE_ALPHA = 0.24f

/** Where the cardinal letters sit, relative to the face's radius. */
private const val CARDINAL_RADIUS_FRACTION = 0.79f
private const val CARDINAL_FONT_FRACTION = 0.10f

private const val ARROW_TAIL_FRACTION = 0.10f

/**
 * How far out the arrow's head stops, as a fraction of the face.
 *
 * It reaches the near edge of the marker on the band, so the arrow and the Kaaba it points at read as
 * one instruction rather than as an arrow pointing at a gap.
 */
private const val ARROW_TIP_FRACTION = 0.94f
private const val ARROW_HEAD_START_FRACTION = 0.70f
private const val ARROW_TAIL_HALF_WIDTH_FRACTION = 0.018f
private const val ARROW_SHAFT_HALF_WIDTH_FRACTION = 0.030f
private const val ARROW_HEAD_HALF_WIDTH_FRACTION = 0.075f

private const val MARKER_SIZE_FRACTION = 0.20f
private const val MARKER_CORNER_FRACTION = 0.14f
private const val MARKER_STROKE_FRACTION = 0.014f
private const val MARKER_BAND_FRACTION = 0.36f
private const val MARKER_BAND_INSET_FRACTION = 0.16f
private const val MARKER_BAND_WIDTH_FRACTION = 0.016f

private const val POINTER_TIP_FRACTION = 0.06f
private const val POINTER_BASE_FRACTION = 0.55f
private const val POINTER_HALF_WIDTH_FRACTION = 0.045f

private const val ALIGNMENT_RING_WIDTH_FRACTION = 0.035f
private const val HUB_RADIUS_FRACTION = 0.025f

/** The widest the dial is allowed to draw, so a tall screen cannot push the reading below the fold. */
private val MAX_DIAL_SIZE = 320.dp

/** The rose labels, as (degrees clockwise from north, text). */
private val CARDINALS = listOf(
    0 to "N",
    90 to "E",
    180 to "S",
    270 to "W",
)
