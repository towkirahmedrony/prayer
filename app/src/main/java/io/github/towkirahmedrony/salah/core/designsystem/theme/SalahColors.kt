package io.github.towkirahmedrony.salah.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// Reference image color palette: Deep Islamic green, warm cream paper background, mint containers
val ForestGreenPrimary = Color(0xFF0D4734)
val ForestGreenDark = Color(0xFF072B20)
val MintContainer = Color(0xFFD4E8DC)
val MintLight = Color(0xFFE5F1EB)
val GoldAccent = Color(0xFFC49A45)
val GoldMuted = Color(0xFFE0C588)

/** Darker gold for small text on the cream canvas: GoldAccent alone is too faint to read there. */
val GoldText = Color(0xFF86601C)
val WarmCanvas = Color(0xFFF4F2EC)
val CardSurfaceWhite = Color(0xFFFFFFFF)
val TextDark = Color(0xFF14241D)
val TextMuted = Color(0xFF5E6D66)
val OutlineMuted = Color(0xFFE2E0D8)

internal val SalahLightColorScheme = lightColorScheme(
    primary = ForestGreenPrimary,
    onPrimary = Color.White,
    primaryContainer = MintContainer,
    onPrimaryContainer = ForestGreenDark,
    secondary = GoldAccent,
    onSecondary = Color.White,
    secondaryContainer = MintLight,
    onSecondaryContainer = ForestGreenDark,
    background = WarmCanvas,
    onBackground = TextDark,
    surface = CardSurfaceWhite,
    onSurface = TextDark,
    surfaceVariant = MintLight,
    onSurfaceVariant = TextMuted,
    outline = OutlineMuted,
    outlineVariant = OutlineMuted,
)

internal val SalahDarkColorScheme = darkColorScheme(
    primary = Color(0xFF7EDBB6),
    onPrimary = Color(0xFF003827),
    primaryContainer = Color(0xFF133E31),
    onPrimaryContainer = Color(0xFFAEF3D3),
    secondary = GoldAccent,
    onSecondary = Color(0xFF261D00),
    secondaryContainer = Color(0xFF332D1B),
    onSecondaryContainer = GoldAccent,
    background = Color(0xFF0F1714),
    onBackground = Color(0xFFE1E5E2),
    surface = Color(0xFF16221E),
    onSurface = Color(0xFFE1E5E2),
    surfaceVariant = Color(0xFF1F2F2A),
    onSurfaceVariant = Color(0xFFA1AEA7),
    outline = Color(0xFF2D3E37),
    outlineVariant = Color(0xFF23322C),
)

/**
 * The colours a Salah switch is drawn in, as a plain value.
 *
 * Salah's light and dark schemes are the ones that deviate from Material's baseline — a cream canvas, a
 * white card, a deep forest green — and neither of them defines the roles Material's **switch** reads on
 * its own. An *unchecked* switch paints its track in `surfaceContainerHighest` and its thumb and outline
 * in `outline`, so it inherited the baseline's lavender-greys and purples: `#E6E0E9` and `#79747E` in
 * light, `#36343B` and `#938F99` in dark. Against Salah's own cards those off-tracks measure about
 * **1.3:1** — the same as the card they are drawn on — while the "on" state was obvious in the primary
 * green. That is the whole of the reported visibility problem: a palette gap rather than a bug in
 * `Switch`, and it is why the fix belongs here and not in a coat of paint on one screen.
 *
 * The fix is to state the six colours the control actually uses, out of the existing Scheme, so both
 * themes read the same way:
 *
 *  - the **track** carries the state, with `primary` for on and `onSurfaceVariant` — the scheme's muted
 *    but clearly visible tone — for off. Both are ≥ 3:1 against the card they sit on, in both themes,
 *    which is what makes "off" visible without making it look like "on".
 *  - the **thumb** is the one that must be seen against the track rather than against the card, so it is
 *    the surface colour: a light knob on the dark off-track, a dark knob on the light on-track.
 *  - a **disabled** control keeps the distinction and only loses confidence: the same colours at a
 *    reduced alpha, never a colour that matches the card.
 *
 * This lives here, as a function of [ColorScheme] alone, rather than inside the composable for the same
 * reason every other formatting decision is out of the UI: it is a decision a test can assert on, and
 * `SalahSwitchPaletteTest` asserts exactly the contrasts above on both schemes.
 */
@Immutable
data class SalahSwitchPalette(
    val checkedThumb: Color,
    val checkedTrack: Color,
    val checkedBorder: Color,
    val uncheckedThumb: Color,
    val uncheckedTrack: Color,
    val uncheckedBorder: Color,
    val disabledCheckedThumb: Color,
    val disabledCheckedTrack: Color,
    val disabledCheckedBorder: Color,
    val disabledUncheckedThumb: Color,
    val disabledUncheckedTrack: Color,
    val disabledUncheckedBorder: Color,
)

/** The switch colours of [colorScheme]. See [SalahSwitchPalette]. */
fun salahSwitchPalette(colorScheme: ColorScheme): SalahSwitchPalette {
    // A disabled control is dimmed, not hidden: enough alpha that the off-track is still a shape a user
    // can see on the card, low enough that it does not read as an enabled control.
    val disabledAlpha = 0.6f

    return SalahSwitchPalette(
        checkedThumb = colorScheme.onPrimary,
        checkedTrack = colorScheme.primary,
        checkedBorder = colorScheme.primary,
        uncheckedThumb = colorScheme.surface,
        uncheckedTrack = colorScheme.onSurfaceVariant,
        uncheckedBorder = colorScheme.onSurfaceVariant,
        disabledCheckedThumb = colorScheme.surface,
        disabledCheckedTrack = colorScheme.primary.copy(alpha = disabledAlpha),
        disabledCheckedBorder = colorScheme.primary.copy(alpha = disabledAlpha),
        disabledUncheckedThumb = colorScheme.surface,
        disabledUncheckedTrack = colorScheme.onSurfaceVariant.copy(alpha = disabledAlpha),
        disabledUncheckedBorder = colorScheme.onSurfaceVariant.copy(alpha = disabledAlpha),
    )
}
