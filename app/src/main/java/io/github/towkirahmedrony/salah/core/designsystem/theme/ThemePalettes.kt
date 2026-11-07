package io.github.towkirahmedrony.salah.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import io.github.towkirahmedrony.salah.core.model.ThemePreset

/**
 * The colour palettes behind the selectable theme presets.
 *
 * Every preset states its own core roles; the mechanical roles — surface container tiers, tinted
 * containers, inverse pairs and error colours — are derived here once, so eight themes do not mean eight
 * copies of a thirty-role scheme that could drift apart. [ThemePreset.DEFAULT] is deliberately not fed
 * through the generator: it returns the existing brand schemes unchanged, which is what keeps the
 * default appearance of the app identical.
 *
 * The palettes are designed against the same rules the existing scheme documents: a tinted paper canvas
 * with a raised card in light mode, a re-pitched palette rather than an inversion in dark mode, and
 * content colours chosen for the container they sit on. `ThemePresetsTest` measures every pair in every
 * preset and mode, so a palette that cannot be read fails the build instead of shipping.
 */

/** The roles a preset states directly. Everything else is derived in [toColorScheme]. */
@Immutable
private data class ThemeSeed(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val outlineVariant: Color,
)

/** The colour scheme of [preset] in the requested appearance. */
fun themeColorScheme(preset: ThemePreset, dark: Boolean): ColorScheme =
    if (preset == ThemePreset.DEFAULT) {
        if (dark) SalahDarkColorScheme else SalahLightColorScheme
    } else {
        preset.seed(dark).toColorScheme(dark)
    }

/**
 * The colours outside Material's scheme for [preset] in the requested appearance.
 *
 * The brand surface is fixed in both modes: it is the preset's own deep primary, with its own content
 * colour, exactly as the default preset's forest green is fixed. The gold and Ramadan accents come from
 * the active scheme, so they re-tone with the palette instead of being a leftover from another theme.
 */
fun themeAccents(preset: ThemePreset, dark: Boolean): SalahAccents {
    if (preset == ThemePreset.DEFAULT) return salahAccents(darkTheme = dark)

    val scheme = themeColorScheme(preset, dark)
    val brand = preset.lightSeed()
    return SalahAccents(
        ramadanToday = scheme.secondaryContainer,
        onRamadanToday = scheme.onSecondaryContainer,
        brandSurface = brand.primary,
        onBrandSurface = brand.onPrimary,
        goldText = scheme.secondary,
    )
}

private fun ThemePreset.lightSeed(): ThemeSeed = when (this) {
    ThemePreset.DEFAULT -> EmeraldLight
    ThemePreset.EMERALD -> EmeraldLight
    ThemePreset.OCEAN -> OceanLight
    ThemePreset.MIDNIGHT -> MidnightLight
    ThemePreset.ROSE -> RoseLight
    ThemePreset.LAVENDER -> LavenderLight
    ThemePreset.AMOLED -> AmoledLight
    ThemePreset.SAND -> SandLight
}

private fun ThemePreset.seed(dark: Boolean): ThemeSeed = when (this) {
    ThemePreset.DEFAULT, ThemePreset.EMERALD -> if (dark) EmeraldDark else EmeraldLight
    ThemePreset.OCEAN -> if (dark) OceanDark else OceanLight
    ThemePreset.MIDNIGHT -> if (dark) MidnightDark else MidnightLight
    ThemePreset.ROSE -> if (dark) RoseDark else RoseLight
    ThemePreset.LAVENDER -> if (dark) LavenderDark else LavenderLight
    ThemePreset.AMOLED -> if (dark) AmoledDark else AmoledLight
    ThemePreset.SAND -> if (dark) SandDark else SandLight
}

/**
 * Completes a seed into a full Material scheme.
 *
 * The surface ladder is generated rather than stated per preset: in light mode the tiers step from the
 * card towards a faint tint of the theme's own text colour, and in dark mode they step from the canvas
 * up through [ThemeSeed.surfaceVariant]. Both ladders are strictly ordered and the highest tier clears
 * the contrast a menu or an unselected control needs against the card. Containers are tints of the
 * palette's own accents over the card, so a secondary container is always in the theme's hue.
 */
private fun ThemeSeed.toColorScheme(dark: Boolean): ColorScheme {
    val secondaryContainerColor = blend(surface, secondary, if (dark) 0.22f else 0.15f)
    val tertiaryContainerColor = blend(surface, tertiary, if (dark) 0.20f else 0.12f)
    val tiers = if (dark) darkSurfaceTiers() else lightSurfaceTiers()
    val inverseSurfaceColor = if (dark) onSurface else blend(background, Color.Black, 0.86f)
    val inverseOnSurfaceColor = if (dark) surface else surface
    val errorColors = if (dark) DarkError else LightError

    return if (dark) {
        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            inversePrimary = primary,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainerColor,
            onSecondaryContainer = onSurface,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainerColor,
            onTertiaryContainer = onSurface,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            surfaceTint = primary,
            inverseSurface = inverseSurfaceColor,
            inverseOnSurface = inverseOnSurfaceColor,
            error = errorColors.error,
            onError = errorColors.onError,
            errorContainer = errorColors.errorContainer,
            onErrorContainer = errorColors.onErrorContainer,
            outline = outline,
            outlineVariant = outlineVariant,
            scrim = Color.Black,
            surfaceDim = background,
            surfaceBright = blend(surfaceVariant, onSurface, 0.08f),
            surfaceContainerLowest = tiers[0],
            surfaceContainerLow = tiers[1],
            surfaceContainer = tiers[2],
            surfaceContainerHigh = tiers[3],
            surfaceContainerHighest = tiers[4],
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            inversePrimary = blend(primary, Color.White, 0.45f),
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainerColor,
            onSecondaryContainer = onSurface,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainerColor,
            onTertiaryContainer = onSurface,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            surfaceTint = primary,
            inverseSurface = inverseSurfaceColor,
            inverseOnSurface = inverseOnSurfaceColor,
            error = errorColors.error,
            onError = errorColors.onError,
            errorContainer = errorColors.errorContainer,
            onErrorContainer = errorColors.onErrorContainer,
            outline = outline,
            outlineVariant = outlineVariant,
            scrim = Color.Black,
            surfaceDim = background,
            surfaceBright = surface,
            surfaceContainerLowest = tiers[0],
            surfaceContainerLow = tiers[1],
            surfaceContainer = tiers[2],
            surfaceContainerHigh = tiers[3],
            surfaceContainerHighest = tiers[4],
        )
    }
}

/**
 * Light surface tiers: the card, then progressively fainter tints of the theme's text colour, so the
 * ladder is ordered and the highest tier is visibly distinct from the card a menu opens over.
 */
private fun ThemeSeed.lightSurfaceTiers(): List<Color> = listOf(
    blend(surface, onSurface, 0.015f),
    blend(surface, onSurface, 0.045f),
    blend(surface, onSurface, 0.075f),
    blend(surface, onSurface, 0.11f),
    blend(surface, onSurface, 0.15f),
)

/**
 * Dark surface tiers: from the canvas up to the theme's own raised variant tone, then a touch further
 * for the highest tier. AMOLED's true-black canvas is why the ladder starts at [background] rather than
 * at the card: black and black cannot form two distinguishable steps.
 */
private fun ThemeSeed.darkSurfaceTiers(): List<Color> = listOf(
    background,
    blend(background, surfaceVariant, 0.25f),
    blend(background, surfaceVariant, 0.50f),
    surfaceVariant,
    blend(surfaceVariant, onSurface, 0.07f),
)

/** Linear interpolation between two colours; the fraction is clamped to a real blend. */
private fun blend(base: Color, overlay: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(
        red = base.red + (overlay.red - base.red) * f,
        green = base.green + (overlay.green - base.green) * f,
        blue = base.blue + (overlay.blue - base.blue) * f,
        alpha = 1f,
    )
}

@Immutable
private data class ErrorColors(
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
)

private val LightError = ErrorColors(
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkError = ErrorColors(
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

// --- Emerald: an elegant Islamic green on a pale green paper ------------------------

private val EmeraldLight = ThemeSeed(
    primary = Color(0xFF145C43),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD2EADD),
    onPrimaryContainer = Color(0xFF04301F),
    secondary = Color(0xFF806015),
    onSecondary = Color.White,
    tertiary = Color(0xFF2E6D6A),
    onTertiary = Color.White,
    background = Color(0xFFF3F7F4),
    onBackground = Color(0xFF10201A),
    surface = Color.White,
    onSurface = Color(0xFF10201A),
    surfaceVariant = Color(0xFFE2EDE6),
    onSurfaceVariant = Color(0xFF4E6159),
    outline = Color(0xFF6C8078),
    outlineVariant = Color(0xFFD5E2DA),
)

private val EmeraldDark = ThemeSeed(
    primary = Color(0xFF7EDBB6),
    onPrimary = Color(0xFF04382A),
    primaryContainer = Color(0xFF154D3A),
    onPrimaryContainer = Color(0xFFB7F2D8),
    secondary = Color(0xFFE0C588),
    onSecondary = Color(0xFF2B2205),
    tertiary = Color(0xFF7BCFC9),
    onTertiary = Color(0xFF003734),
    background = Color(0xFF0C1512),
    onBackground = Color(0xFFDDE8E2),
    surface = Color(0xFF121F1A),
    onSurface = Color(0xFFDDE8E2),
    surfaceVariant = Color(0xFF1C2E27),
    onSurfaceVariant = Color(0xFFA8BDB3),
    outline = Color(0xFF74897F),
    outlineVariant = Color(0xFF263A32),
)

// --- Ocean: deep blue and cyan -----------------------------------------------------

private val OceanLight = ThemeSeed(
    primary = Color(0xFF0A4C73),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE7F6),
    onPrimaryContainer = Color(0xFF022D46),
    secondary = Color(0xFF0C6E70),
    onSecondary = Color.White,
    tertiary = Color(0xFF8C5A16),
    onTertiary = Color.White,
    background = Color(0xFFF2F6FA),
    onBackground = Color(0xFF0E1B24),
    surface = Color.White,
    onSurface = Color(0xFF0E1B24),
    surfaceVariant = Color(0xFFDDEAF3),
    onSurfaceVariant = Color(0xFF4B616E),
    outline = Color(0xFF647C8B),
    outlineVariant = Color(0xFFD3E2EC),
)

private val OceanDark = ThemeSeed(
    primary = Color(0xFF7FCBEF),
    onPrimary = Color(0xFF00344D),
    primaryContainer = Color(0xFF0F405C),
    onPrimaryContainer = Color(0xFFC4E8FA),
    secondary = Color(0xFF63D6D2),
    onSecondary = Color(0xFF003735),
    tertiary = Color(0xFFF0BE7A),
    onTertiary = Color(0xFF422C00),
    background = Color(0xFF08131A),
    onBackground = Color(0xFFDCE8EF),
    surface = Color(0xFF0E1E28),
    onSurface = Color(0xFFDCE8EF),
    surfaceVariant = Color(0xFF172B37),
    onSurfaceVariant = Color(0xFFA3B8C4),
    outline = Color(0xFF6C8796),
    outlineVariant = Color(0xFF1F3745),
)

// --- Midnight: dark navy with quiet accents ----------------------------------------

private val MidnightLight = ThemeSeed(
    primary = Color(0xFF1F3A6E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E2F5),
    onPrimaryContainer = Color(0xFF0A1B3A),
    secondary = Color(0xFF5C4D8A),
    onSecondary = Color.White,
    tertiary = Color(0xFF2E6F7E),
    onTertiary = Color.White,
    background = Color(0xFFF4F6FA),
    onBackground = Color(0xFF131A28),
    surface = Color.White,
    onSurface = Color(0xFF131A28),
    surfaceVariant = Color(0xFFE1E6F0),
    onSurfaceVariant = Color(0xFF4D586B),
    outline = Color(0xFF6B7689),
    outlineVariant = Color(0xFFD7DEEA),
)

private val MidnightDark = ThemeSeed(
    primary = Color(0xFFA8C4F0),
    onPrimary = Color(0xFF12294F),
    primaryContainer = Color(0xFF22355C),
    onPrimaryContainer = Color(0xFFD7E4FA),
    secondary = Color(0xFFC9BCF0),
    onSecondary = Color(0xFF2A2145),
    tertiary = Color(0xFF7FD3DC),
    onTertiary = Color(0xFF00363C),
    background = Color(0xFF070B14),
    onBackground = Color(0xFFDCE3F0),
    surface = Color(0xFF0D1422),
    onSurface = Color(0xFFDCE3F0),
    surfaceVariant = Color(0xFF182136),
    onSurfaceVariant = Color(0xFFA4B1C6),
    outline = Color(0xFF6A7A94),
    outlineVariant = Color(0xFF202C44),
)

// --- Rose: soft rose and pink ------------------------------------------------------

private val RoseLight = ThemeSeed(
    primary = Color(0xFF8A2F4A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFADCE4),
    onPrimaryContainer = Color(0xFF3F0A1B),
    secondary = Color(0xFF8A5544),
    onSecondary = Color.White,
    tertiary = Color(0xFF5B5D8A),
    onTertiary = Color.White,
    background = Color(0xFFFBF4F5),
    onBackground = Color(0xFF241419),
    surface = Color.White,
    onSurface = Color(0xFF241419),
    surfaceVariant = Color(0xFFF3E3E7),
    onSurfaceVariant = Color(0xFF6B4E57),
    outline = Color(0xFF8A6A74),
    outlineVariant = Color(0xFFEBD8DD),
)

private val RoseDark = ThemeSeed(
    primary = Color(0xFFF3B4C4),
    onPrimary = Color(0xFF531C2E),
    primaryContainer = Color(0xFF5C2436),
    onPrimaryContainer = Color(0xFFFFD9E1),
    secondary = Color(0xFFE6B9A8),
    onSecondary = Color(0xFF3B1E14),
    tertiary = Color(0xFFC0C2EF),
    onTertiary = Color(0xFF1E214D),
    background = Color(0xFF150D10),
    onBackground = Color(0xFFF0DFE4),
    surface = Color(0xFF1E1318),
    onSurface = Color(0xFFF0DFE4),
    surfaceVariant = Color(0xFF2E1E25),
    onSurfaceVariant = Color(0xFFC3A9B1),
    outline = Color(0xFF927780),
    outlineVariant = Color(0xFF38252C),
)

// --- Lavender: elegant purple ------------------------------------------------------

private val LavenderLight = ThemeSeed(
    primary = Color(0xFF5B3E8A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8DDF7),
    onPrimaryContainer = Color(0xFF2A1550),
    secondary = Color(0xFF7A5A18),
    onSecondary = Color.White,
    tertiary = Color(0xFF2F6B5E),
    onTertiary = Color.White,
    background = Color(0xFFF8F5FB),
    onBackground = Color(0xFF1D1729),
    surface = Color.White,
    onSurface = Color(0xFF1D1729),
    surfaceVariant = Color(0xFFEBE4F3),
    onSurfaceVariant = Color(0xFF5D526C),
    outline = Color(0xFF867B94),
    outlineVariant = Color(0xFFE0D8EA),
)

private val LavenderDark = ThemeSeed(
    primary = Color(0xFFCDB6F5),
    onPrimary = Color(0xFF38215E),
    primaryContainer = Color(0xFF452E6E),
    onPrimaryContainer = Color(0xFFE8DDFA),
    secondary = Color(0xFFE2C485),
    onSecondary = Color(0xFF3E2E00),
    tertiary = Color(0xFF82D3C3),
    onTertiary = Color(0xFF00382F),
    background = Color(0xFF0F0B16),
    onBackground = Color(0xFFE7E0F0),
    surface = Color(0xFF171120),
    onSurface = Color(0xFFE7E0F0),
    surfaceVariant = Color(0xFF241B31),
    onSurfaceVariant = Color(0xFFB8ABC7),
    outline = Color(0xFF857697),
    outlineVariant = Color(0xFF2E243D),
)

// --- AMOLED: true black with optimised contrast ------------------------------------

/**
 * AMOLED's light appearance is not a second black theme: "AMOLED" describes the dark surface, and its
 * light counterpart is a pure, high-contrast monochrome paper theme. Choosing Dark turns it into true
 * black with a near-black card.
 */
private val AmoledLight = ThemeSeed(
    // 0x1A rather than pure black: this value is also AMOLED's fixed brand surface, and a black hero on
    // a black OLED canvas would be invisible. #1A1A1A is the darkest tone that still separates itself
    // from the true-black background.
    primary = Color(0xFF1A1A1A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E6E6),
    onPrimaryContainer = Color(0xFF111111),
    secondary = Color(0xFF4A4A4A),
    onSecondary = Color.White,
    tertiary = Color(0xFF333333),
    onTertiary = Color.White,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFF0F0F0),
    onSurfaceVariant = Color(0xFF4A4A4A),
    outline = Color(0xFF6A6A6A),
    outlineVariant = Color(0xFFDEDEDE),
)

private val AmoledDark = ThemeSeed(
    primary = Color(0xFF9FE8C8),
    onPrimary = Color(0xFF003827),
    primaryContainer = Color(0xFF113A2C),
    onPrimaryContainer = Color(0xFFC7F5E1),
    secondary = Color(0xFFE0C588),
    onSecondary = Color(0xFF2C2300),
    tertiary = Color(0xFFC9B8FF),
    onTertiary = Color(0xFF25165B),
    background = Color.Black,
    onBackground = Color(0xFFEDEDED),
    surface = Color.Black,
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF1C1C1C),
    onSurfaceVariant = Color(0xFFB0B0B0),
    outline = Color(0xFF7D7D7D),
    outlineVariant = Color(0xFF2A2A2A),
)

// --- Sand: warm beige and earth ----------------------------------------------------

private val SandLight = ThemeSeed(
    primary = Color(0xFF6E4F2A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF0E2CC),
    onPrimaryContainer = Color(0xFF33200A),
    secondary = Color(0xFF7A5A18),
    onSecondary = Color.White,
    tertiary = Color(0xFF4F6B57),
    onTertiary = Color.White,
    background = Color(0xFFF6F0E4),
    onBackground = Color(0xFF241D12),
    surface = Color(0xFFFFFDF8),
    onSurface = Color(0xFF241D12),
    surfaceVariant = Color(0xFFEDE3D2),
    onSurfaceVariant = Color(0xFF645845),
    outline = Color(0xFF8A7A63),
    outlineVariant = Color(0xFFE1D5C1),
)

private val SandDark = ThemeSeed(
    primary = Color(0xFFE4C08D),
    onPrimary = Color(0xFF402C0B),
    primaryContainer = Color(0xFF4A361A),
    onPrimaryContainer = Color(0xFFF7DFB9),
    secondary = Color(0xFFD9B77A),
    onSecondary = Color(0xFF3A2A00),
    tertiary = Color(0xFFA9C7AE),
    onTertiary = Color(0xFF12351D),
    background = Color(0xFF15120C),
    onBackground = Color(0xFFEBE3D5),
    surface = Color(0xFF1E1A13),
    onSurface = Color(0xFFEBE3D5),
    surfaceVariant = Color(0xFF2C261C),
    onSurfaceVariant = Color(0xFFC5B9A5),
    outline = Color(0xFF8E8069),
    outlineVariant = Color(0xFF382F23),
)
