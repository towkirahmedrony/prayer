package io.github.towkirahmedrony.salah.core.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
