package io.github.towkirahmedrony.salah.core.designsystem.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import io.github.towkirahmedrony.salah.core.model.ThemeMode

private val SalahShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

private val SalahTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(letterSpacing = 0.6.sp),
    )
}

/**
 * The colours a Salah screen needs that Material's scheme does not carry.
 *
 * Material gives every role a container/content pair, but Salah has three things the scheme cannot
 * express, because they are *fixed* rather than themed or because they are a hue the scheme has no role
 * for:
 *
 *  - the **Ramadan row** that is today. Gold is already the app's secondary colour, and this keeps it
 *    out of the shared scheme so it cannot leak onto a control that should have stayed green. The
 *    content colour always sits on the container: a dark green in light mode and the muted gold the
 *    dark scheme already uses, so the highlighted row keeps a readable contrast in both.
 *  - the **brand green surface** — the next-prayer hero, the bottom bar, the brand header — with the
 *    white drawn on it. This pair is deliberately identical in both themes: it is the app's identity,
 *    not a themed surface, exactly like white type on a brand-coloured button. The two values live here
 *    together so the pairing is declared once and cannot drift apart across screens.
 *  - the **gold used as text**. One gold cannot serve both themes: the mid gold that reads on a dark
 *    canvas is unreadable on cream, and the dark gold that reads on cream is too dim on a dark canvas.
 *    So the accent carries the value that is legible on the theme it belongs to, and the screens ask for
 *    "the gold" rather than picking one of the two constants themselves.
 */
@Immutable
data class SalahAccents(
    /** Fill of the Ramadan table's "today" row. */
    val ramadanToday: Color,
    /** Text and times drawn on [ramadanToday]. */
    val onRamadanToday: Color,
    /** Fill of the brand's own green surface. Fixed in both themes; see the class documentation. */
    val brandSurface: Color,
    /** Text, icons and glyphs drawn on [brandSurface]. Fixed in both themes. */
    val onBrandSurface: Color,
    /** The gold used for text, small marks and brand type, legible on the active theme's canvas. */
    val goldText: Color,
)

/** The theme's [SalahAccents]. Provided by [SalahTheme]; the default is the light pairing. */
val LocalSalahAccents = staticCompositionLocalOf { salahAccents(darkTheme = false) }

/**
 * The accents of the given theme, as a plain value.
 *
 * A function of the boolean rather than a branch inside the composable so the pairings are a decision a
 * test can assert on: `SalahColorSchemeTest` measures both `goldText` values against their own canvas
 * and the brand pair against itself.
 */
internal fun salahAccents(darkTheme: Boolean): SalahAccents = if (darkTheme) {
    // The dark scheme's own secondary container is already a deep gold, so the Ramadan highlight reuses
    // it and only the text colour is the muted gold rather than the scheme's brighter one.
    SalahAccents(
        ramadanToday = SalahDarkColorScheme.secondaryContainer,
        onRamadanToday = GoldMuted,
        brandSurface = ForestGreenPrimary,
        onBrandSurface = Color.White,
        goldText = GoldMuted,
    )
} else {
    SalahAccents(
        ramadanToday = GoldMuted,
        onRamadanToday = ForestGreenDark,
        brandSurface = ForestGreenPrimary,
        onBrandSurface = Color.White,
        goldText = GoldText,
    )
}

/**
 * Whether [themeMode] means the dark scheme, given what the device currently reports.
 *
 * Split out from the composable because it is the *only* place the three modes are interpreted, and it
 * is the mapping the settings screen's choice passes through: `SYSTEM` follows the device, and the two
 * explicit modes override it in both directions, so choosing Dark on a light phone works and choosing
 * Light on a dark phone works. Reading `isSystemInDarkTheme()` anywhere else would be a second,
 * competing answer to this question.
 */
internal fun resolveDarkTheme(themeMode: ThemeMode, systemInDarkTheme: Boolean): Boolean =
    when (themeMode) {
        ThemeMode.SYSTEM -> systemInDarkTheme
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

/**
 * Applies the Salah design system.
 *
 * [ThemeMode.SYSTEM] follows the device setting; the explicit modes let the user override it,
 * which is why the system bar icon colours are updated here rather than in the platform theme.
 *
 * Bangla and Arabic script are rendered with the platform fonts (Android ships Bengali and
 * Arabic coverage in its system font stack). No font binaries are bundled in this phase.
 */
@Composable
fun SalahTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = resolveDarkTheme(themeMode, systemInDarkTheme = isSystemInDarkTheme())

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalSalahAccents provides salahAccents(darkTheme)) {
        MaterialTheme(
            colorScheme = if (darkTheme) SalahDarkColorScheme else SalahLightColorScheme,
            typography = SalahTypography,
            shapes = SalahShapes,
            content = content,
        )
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
