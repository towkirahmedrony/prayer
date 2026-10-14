package io.github.towkirahmedrony.salah.core.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.towkirahmedrony.salah.core.designsystem.ChevronLeftGlyph
import io.github.towkirahmedrony.salah.core.designsystem.theme.salahSwitchPalette

/**
 * A bordered surface used for every Home section, so the screen reads as one system.
 *
 * Passing [onClick] makes the whole card a button. It goes through Material's own clickable `Card`
 * overload rather than a `clickable` modifier on the caller's side, so the ripple is clipped to the
 * card's corner radius and the card is announced to accessibility as a single button.
 */
@Composable
fun SalahSectionCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    val elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)

    if (onClick == null) {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            colors = colors,
            border = border,
            elevation = elevation,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                content()
            }
        }
    } else {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            colors = colors,
            border = border,
            elevation = elevation,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                content()
            }
        }
    }
}

/**
 * The header of a configuration subpage: a back action and the screen's title.
 *
 * A back control beside the title rather than a `TopAppBar`, because Salah has no app bar anywhere —
 * every screen states its own name in the content — and introducing one here would make the settings
 * subpages look like a different application. The system back gesture and button keep working
 * unchanged; this is the visible equivalent of them, wired to the same pop.
 *
 * The back action is a real `IconButton`, so its touch target is the platform's 48dp and TalkBack
 * announces it as a button with [backContentDescription] as its label. The glyph is a canvas drawing
 * and carries no text of its own, which is why the description is a required parameter: it is the
 * only thing a screen reader would otherwise have to go on.
 */
@Composable
fun SalahSubpageHeader(
    title: String,
    backContentDescription: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            ChevronLeftGlyph(
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { contentDescription = backContentDescription },
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/** Small status pill, used for provenance and implementation status. */
@Composable
fun SalahStatusPill(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(100.dp))
            .background(containerColor)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
        )
    }
}

/**
 * Explains why a section has no data.
 *
 * Used instead of inventing a value, which is the core honesty rule of the Home screen.
 */
@Composable
fun SalahUnavailableNote(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A shortcut tile that navigates to a planned section of the app. */
@Composable
fun SalahShortcutTile(
    title: String,
    subtitle: String,
    glyph: @Composable (Color) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            glyph(MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
            )
        }
    }
}

/**
 * A static placeholder for one piece of content that has not arrived yet.
 *
 * **Deliberately not animated.** The waits it covers are short, and a pulsing or shimmering block
 * draws more attention to the gap than the spinner it replaced — which is the opposite of the point.
 * It uses the same `surfaceVariant` as [SalahUnavailableNote], so a screen that is still loading
 * still reads as part of the same design system.
 *
 * Only the height, the corner radius and the colour are decided here. Callers supply the width and
 * the position, which is what lets one block stand in for a date line, a prayer chip or a whole
 * card, and lets each caller keep the geometry of the real content it is standing in for.
 */
@Composable
fun SalahSkeletonBlock(
    height: Dp,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
) {
    Box(
        modifier = modifier
            .height(height)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

/**
 * The switch Salah draws for a reminder.
 *
 * A thin wrapper over Material's `Switch`: it keeps every behaviour of the framework control — the
 * toggle gesture, the 48dp touch target, the switch role and the on/off state description a screen
 * reader announces — and replaces only the six colours it is painted with, because the baseline ones do
 * not survive Salah's palette. See [io.github.towkirahmedrony.salah.core.designsystem.theme.SalahSwitchPalette]
 * for exactly which colours and why: the off track is the scheme's muted tone rather than a default
 * container, so *off* is as visible as *on* on both the light and the dark card.
 *
 * It is a design-system component rather than a private composable per screen because there is more than
 * one place a Sehri or Iftar reminder is switched: the Ramadan page's reminder card and the full reminder
 * settings the card's gear opens. Both draw the same control, so both must be the same control.
 */
@Composable
fun SalahSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val palette = salahSwitchPalette(MaterialTheme.colorScheme)

    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = palette.checkedThumb,
            checkedTrackColor = palette.checkedTrack,
            checkedBorderColor = palette.checkedBorder,
            uncheckedThumbColor = palette.uncheckedThumb,
            uncheckedTrackColor = palette.uncheckedTrack,
            uncheckedBorderColor = palette.uncheckedBorder,
            disabledCheckedThumbColor = palette.disabledCheckedThumb,
            disabledCheckedTrackColor = palette.disabledCheckedTrack,
            disabledCheckedBorderColor = palette.disabledCheckedBorder,
            disabledUncheckedThumbColor = palette.disabledUncheckedThumb,
            disabledUncheckedTrackColor = palette.disabledUncheckedTrack,
            disabledUncheckedBorderColor = palette.disabledUncheckedBorder,
        ),
    )
}
