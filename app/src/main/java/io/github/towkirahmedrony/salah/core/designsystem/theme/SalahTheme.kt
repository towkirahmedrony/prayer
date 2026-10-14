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
 * The accent colours Material's scheme does not carry.
 *
 * Material gives every role a container/content pair, but Salah needs gold for exactly one thing:
 * the row of the Ramadan table that is today. Gold is already the app's secondary colour, and this
 * keeps it out of the shared scheme so it cannot leak onto a control that should have stayed green.
 * The content colour always sits on the container: a dark green in light mode and the muted gold the
 * dark scheme already uses, so the highlighted row keeps a readable contrast in both.
 */
@Immutable
data class SalahAccents(
    /** Fill of the Ramadan table's "today" row. */
    val ramadanToday: Color,
    /** Text and times drawn on [ramadanToday]. */
    val onRamadanToday: Color,
)

/** The theme's [SalahAccents]. Provided by [SalahTheme]; the default is the light pairing. */
val LocalSalahAccents = staticCompositionLocalOf {
    SalahAccents(ramadanToday = GoldMuted, onRamadanToday = ForestGreenDark)
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
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

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

    val accents = if (darkTheme) {
        // The dark scheme's own secondary container is already a deep gold, so the highlight reuses
        // it and only the text colour is the muted gold rather than the scheme's brighter one.
        SalahAccents(
            ramadanToday = SalahDarkColorScheme.secondaryContainer,
            onRamadanToday = GoldMuted,
        )
    } else {
        SalahAccents(ramadanToday = GoldMuted, onRamadanToday = ForestGreenDark)
    }

    CompositionLocalProvider(LocalSalahAccents provides accents) {
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
