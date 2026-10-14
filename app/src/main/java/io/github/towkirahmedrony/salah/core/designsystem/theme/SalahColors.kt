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

/**
 * The boundary colour of a control that is not focused: an unfocused text field, an unselected chip.
 *
 * Deliberately *not* [OutlineMuted]. Material gives the palette two line roles — `outline` for the
 * boundary of a control and `outlineVariant` for a decorative rule — and Salah had mapped both to the
 * same cream-grey hairline. That hairline measures about **1.2:1** against the card, so every control
 * that outlines itself with `outline` (the calendar's view chips, the location form's unfocused text
 * fields) was drawing a boundary a user could not see, in either theme. WCAG asks for 3:1 on the parts
 * of a control that identify it, so `outline` is now a tone that clears that against both the canvas
 * and the card, while `outlineVariant` keeps the soft hairline for dividers, where a low-contrast rule
 * is the intent. The two roles are no longer the same value.
 */
val OutlineControl = Color(0xFF7E8E86)

/** [OutlineControl] for the dark scheme: the same job against a nearly-black canvas. */
val OutlineControlDark = Color(0xFF6E8078)

/**
 * The calendar's "observance" accent — `tertiary`.
 *
 * A warm terracotta rather than Material's baseline plum. The palette is green, gold and cream, and
 * `tertiary` is the one role Salah left undefined, so the year's observances were marked with the
 * baseline's pink (#7D5260 in light, #EFB8C8 in dark) — a colour that appears nowhere else in the app
 * and reads as a stray. Terracotta is distinct from the gold Ramadan marker it sits beside, and clears
 * 3:1 against the card in both themes, which a marker of this size has to.
 */
val Terracotta = Color(0xFFB4552D)
val OnTerracotta = Color(0xFFFFFFFF)
val TerracottaContainer = Color(0xFFF6DFD5)
val OnTerracottaContainer = Color(0xFF3B1507)
val TerracottaDark = Color(0xFFE29A78)
val OnTerracottaDark = Color(0xFF4C1B08)
val TerracottaContainerDark = Color(0xFF6B2A11)
val OnTerracottaContainerDark = Color(0xFFFFDBCB)

/**
 * Salah's light colour scheme.
 *
 * **Every role Material's components read is stated here.** A `lightColorScheme` call fills whatever
 * it is not given with Material's own baseline palette — lavender surfaces, a plum `tertiary`, a
 * violet `surfaceTint` — and those values are light but wrong for Salah: a dropdown menu painted
 * `surfaceContainer` came out #F3EDF7 (Material's lavender) rather than Salah's card, and the
 * calendar's observance marker came out pink. So the full set is declared, and the surface tiers are
 * derived from Salah's own paper tones: `surface` is the card white, the containers step down towards
 * the cream canvas, and `surfaceContainerHighest` is where Material puts an unselected control's fill.
 *
 * `on*` roles are stated with their container, never guessed, so a component that reads a pair gets a
 * readable pair. `SalahColorSchemeTest` asserts the contrast of each of them.
 */
internal val SalahLightColorScheme = lightColorScheme(
    primary = ForestGreenPrimary,
    onPrimary = Color.White,
    primaryContainer = MintContainer,
    onPrimaryContainer = ForestGreenDark,
    inversePrimary = Color(0xFF7EDBB6),
    secondary = GoldAccent,
    onSecondary = Color.White,
    secondaryContainer = MintLight,
    onSecondaryContainer = ForestGreenDark,
    tertiary = Terracotta,
    onTertiary = OnTerracotta,
    tertiaryContainer = TerracottaContainer,
    onTertiaryContainer = OnTerracottaContainer,
    background = WarmCanvas,
    onBackground = TextDark,
    surface = CardSurfaceWhite,
    onSurface = TextDark,
    surfaceVariant = MintLight,
    onSurfaceVariant = TextMuted,
    surfaceTint = ForestGreenPrimary,
    inverseSurface = Color(0xFF2C3631),
    inverseOnSurface = Color(0xFFEDF1EE),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = OutlineControl,
    outlineVariant = OutlineMuted,
    scrim = Color(0xFF000000),
    surfaceDim = Color(0xFFE3E0D7),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFDFBF7),
    surfaceContainerLow = Color(0xFFFAF8F2),
    surfaceContainer = Color(0xFFF6F4ED),
    surfaceContainerHigh = Color(0xFFF1EFE8),
    surfaceContainerHighest = Color(0xFFECE9E1),
)

/**
 * Salah's dark colour scheme.
 *
 * **A dark palette, not an inversion.** The greens are re-pitched for a dark canvas rather than
 * darkened: `primary` becomes a light mint that carries text and glyphs, with a deep green as its
 * `onPrimary`. Gold stays gold — Salah's secondary does not need to change value to be read on a dark
 * surface — and the surface tiers rise from a near-black canvas to the tone an unselected control sits
 * on, so a card, a menu and a text field are three distinguishable darks rather than one.
 *
 * Like the light scheme, the full role set is declared, so no component can fall back to Material's
 * baseline violet. See [SalahLightColorScheme] for why that matters.
 */
internal val SalahDarkColorScheme = darkColorScheme(
    primary = Color(0xFF7EDBB6),
    onPrimary = Color(0xFF003827),
    primaryContainer = Color(0xFF133E31),
    onPrimaryContainer = Color(0xFFAEF3D3),
    inversePrimary = ForestGreenPrimary,
    secondary = GoldAccent,
    onSecondary = Color(0xFF261D00),
    secondaryContainer = Color(0xFF332D1B),
    onSecondaryContainer = GoldAccent,
    tertiary = TerracottaDark,
    onTertiary = OnTerracottaDark,
    tertiaryContainer = TerracottaContainerDark,
    onTertiaryContainer = OnTerracottaContainerDark,
    background = Color(0xFF0F1714),
    onBackground = Color(0xFFE1E5E2),
    surface = Color(0xFF16221E),
    onSurface = Color(0xFFE1E5E2),
    surfaceVariant = Color(0xFF1F2F2A),
    onSurfaceVariant = Color(0xFFA1AEA7),
    surfaceTint = Color(0xFF7EDBB6),
    inverseSurface = Color(0xFFE1E5E2),
    inverseOnSurface = Color(0xFF16221E),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = OutlineControlDark,
    outlineVariant = Color(0xFF23322C),
    scrim = Color(0xFF000000),
    surfaceDim = Color(0xFF0F1714),
    surfaceBright = Color(0xFF35423D),
    surfaceContainerLowest = Color(0xFF0A110E),
    surfaceContainerLow = Color(0xFF16221E),
    surfaceContainer = Color(0xFF1B2823),
    surfaceContainerHigh = Color(0xFF26332E),
    surfaceContainerHighest = Color(0xFF313E39),
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
