package io.github.towkirahmedrony.salah.feature.qibla.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Qibla dial.
 *
 * **How it turns, and why.** A physical compass rose turns *underneath* a fixed pointer: the pointer
 * marks where the device is facing, and the rose is drawn rotated by the negative of the heading so
 * that the compass bearing under the pointer is the direction the user is looking. The Qibla marker
 * is drawn on the rose at the bearing itself, so after that one rotation it lands at
 * `bearing − heading` — the signed turn — which is why the display stays correct through 0°/360°
 * instead of winding almost all the way round.
 *
 * **Why the rotation is applied once, to the whole canvas.** Rotating each element separately is how
 * a dial ends up internally inconsistent: a needle rotated by one convention and a label by another
 * disagree by exactly the angle nobody notices until it matters. Everything that belongs to the rose
 * — ticks, cardinals, the marker — is drawn inside a single `rotate` block.
 *
 * **What it refuses to do.** When [isLive] is false the dial is drawn without rotation and the
 * marker is hidden, rather than animated from a heading that is not being measured. A dial that
 * moved on its own would be the most convincing kind of wrong.
 */
@Composable
fun QiblaCompassDial(
    headingDegrees: Double?,
    qiblaDegrees: Double?,
    isAligned: Boolean,
    isLive: Boolean,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()

    val dialColor = MaterialTheme.colorScheme.onSurfaceVariant
    val tickColor = MaterialTheme.colorScheme.outlineVariant
    val accent = MaterialTheme.colorScheme.primary
    val markerColor = MaterialTheme.colorScheme.tertiary
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(percent = 50)),
    ) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        val radius = minOf(size.width, size.height) / 2f
        val ringRadius = radius * RING_RADIUS_FRACTION

        drawCircle(color = surfaceColor, radius = ringRadius, center = centre, style = Stroke(width = radius * 0.02f))
        if (isAligned) {
            drawCircle(
                color = accent,
                radius = ringRadius,
                center = centre,
                style = Stroke(width = radius * 0.055f),
            )
        }

        // One rotation for the whole rose.
        rotate(degrees = if (isLive && headingDegrees != null) -headingDegrees.toFloat() else 0f, pivot = centre) {
            drawTicks(centre, ringRadius, radius, tickColor)
            drawCardinals(centre, ringRadius, radius, textMeasurer, dialColor)
            if (qiblaDegrees != null) {
                drawQiblaMarker(centre, ringRadius, radius, qiblaDegrees, markerColor)
            }
        }

        // The fixed pointer: the direction the device is facing. Outside the rotation on purpose.
        drawHeadingPointer(centre, ringRadius, radius, accent)
        drawCircle(color = dialColor, radius = radius * 0.03f, center = centre)
    }
}

/**
 * Tick marks every 15°, longer every 45°.
 *
 * Buried in the rotation block so the marks move with the rose, which is what makes the dial read as
 * a compass rather than as a decoration.
 */
private fun DrawScope.drawTicks(
    centre: Offset,
    ringRadius: Float,
    radius: Float,
    color: Color,
) {
    for (degrees in 0 until 360 step TICK_STEP_DEGREES) {
        val isMajor = degrees % 45 == 0
        val outer = ringRadius - radius * 0.02f
        val inner = outer - radius * (if (isMajor) 0.075f else 0.04f)
        val radians = Math.toRadians(degrees.toDouble() - 90.0)
        val dx = cos(radians).toFloat()
        val dy = sin(radians).toFloat()
        drawLine(
            color = color,
            start = Offset(centre.x + inner * dx, centre.y + inner * dy),
            end = Offset(centre.x + outer * dx, centre.y + outer * dy),
            strokeWidth = radius * (if (isMajor) 0.012f else 0.006f),
        )
    }
}

/** North, east, south and west, drawn inside the rotation so they track the rose. */
private fun DrawScope.drawCardinals(
    centre: Offset,
    ringRadius: Float,
    radius: Float,
    textMeasurer: TextMeasurer,
    color: Color,
) {
    val labelRadius = ringRadius - radius * 0.17f
    CARDINALS.forEach { (degrees, label) ->
        val layout = textMeasurer.measure(
            text = AnnotatedString(label),
            style = TextStyle(
                color = color,
                // DrawScope is a Density, so a pixel measurement converts to sp correctly here.
                fontSize = (radius * 0.11f).toSp(),
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
 * The Qibla marker: a filled wedge pointing outward at the bearing.
 *
 * Drawn at [degrees] within the (already rotated) rose, so it needs no knowledge of the heading — the
 * rotation is what places it relative to the pointer.
 */
private fun DrawScope.drawQiblaMarker(
    centre: Offset,
    ringRadius: Float,
    radius: Float,
    degrees: Double,
    color: Color,
) {
    val radians = Math.toRadians(degrees - 90.0)
    val dx = cos(radians).toFloat()
    val dy = sin(radians).toFloat()
    val perpendicular = Offset(-dy, dx)

    val tipDistance = ringRadius + radius * 0.02f
    val baseDistance = ringRadius - radius * 0.30f
    val halfWidth = radius * 0.09f

    val tip = Offset(centre.x + tipDistance * dx, centre.y + tipDistance * dy)
    val baseCentre = Offset(centre.x + baseDistance * dx, centre.y + baseDistance * dy)
    val left = Offset(baseCentre.x + halfWidth * perpendicular.x, baseCentre.y + halfWidth * perpendicular.y)
    val right = Offset(baseCentre.x - halfWidth * perpendicular.x, baseCentre.y - halfWidth * perpendicular.y)

    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(left.x, left.y)
        lineTo(right.x, right.y)
        close()
    }
    drawPath(path = path, color = color)
}

/** A fixed arrow at the top of the dial: the direction the top of the device points. */
private fun DrawScope.drawHeadingPointer(
    centre: Offset,
    ringRadius: Float,
    radius: Float,
    color: Color,
) {
    val tipY = centre.y - ringRadius - radius * 0.02f
    val baseY = centre.y - ringRadius + radius * 0.10f
    val halfWidth = radius * 0.06f

    val path = Path().apply {
        moveTo(centre.x, tipY)
        lineTo(centre.x - halfWidth, baseY)
        lineTo(centre.x + halfWidth, baseY)
        close()
    }
    drawPath(path = path, color = color)
}

/** Where the rose's outer ring sits, as a fraction of the available radius. */
private const val RING_RADIUS_FRACTION = 0.92f

/** Degrees between tick marks. */
private const val TICK_STEP_DEGREES = 15

/** The rose labels, as (degrees clockwise from north, text). */
private val CARDINALS = listOf(
    0 to "N",
    90 to "E",
    180 to "S",
    270 to "W",
)
