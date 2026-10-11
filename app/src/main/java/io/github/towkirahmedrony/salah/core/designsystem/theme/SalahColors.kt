package io.github.towkirahmedrony.salah.core.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Brand palette: deep emerald, warm ivory, brass accent, slate for solar information.
private val EmeraldDeep = Color(0xFF0B6B4F)
private val EmeraldInk = Color(0xFF04261A)
private val EmeraldMist = Color(0xFFC7EFDC)
private val Brass = Color(0xFF8A6A32)
private val BrassSoft = Color(0xFFF4E4C4)
private val Slate = Color(0xFF3B5E7A)
private val Ivory = Color(0xFFFBF8F1)
private val IvoryElevated = Color(0xFFFFFDF8)
private val SandLine = Color(0xFFE4DCCB)
private val InkStrong = Color(0xFF1B1C18)
private val InkMuted = Color(0xFF4C4639)

private val EmeraldLight = Color(0xFF8AD7B6)
private val EmeraldContainerDark = Color(0xFF0A4C39)
private val BrassLight = Color(0xFFDCC08A)
private val SlateLight = Color(0xFFA8C6E0)
private val NightBase = Color(0xFF101512)
private val NightElevated = Color(0xFF131A16)
private val NightMuted = Color(0xFFC4C0B4)
private val NightLine = Color(0xFF3B4038)

/** Light scheme: warm ivory paper with deep emerald ink. */
internal val SalahLightColorScheme = lightColorScheme(
    primary = EmeraldDeep,
    onPrimary = Color.White,
    primaryContainer = EmeraldMist,
    onPrimaryContainer = EmeraldInk,
    secondary = Brass,
    onSecondary = Color.White,
    secondaryContainer = BrassSoft,
    onSecondaryContainer = Color(0xFF2A1F06),
    tertiary = Slate,
    onTertiary = Color.White,
    background = Ivory,
    onBackground = InkStrong,
    surface = IvoryElevated,
    onSurface = InkStrong,
    surfaceVariant = SandLine,
    onSurfaceVariant = InkMuted,
    outline = Color(0xFFB9AF9A),
    outlineVariant = SandLine,
    error = Color(0xFFB3261E),
    onError = Color.White,
)

/** Dark scheme: deep green-black with a soft emerald light. */
internal val SalahDarkColorScheme = darkColorScheme(
    primary = EmeraldLight,
    onPrimary = Color(0xFF00382A),
    primaryContainer = EmeraldContainerDark,
    onPrimaryContainer = EmeraldMist,
    secondary = BrassLight,
    onSecondary = Color(0xFF3B2E10),
    secondaryContainer = Color(0xFF56431C),
    onSecondaryContainer = BrassSoft,
    tertiary = SlateLight,
    onTertiary = Color(0xFF102A3C),
    background = NightBase,
    onBackground = Color(0xFFE6E2D8),
    surface = NightElevated,
    onSurface = Color(0xFFE6E2D8),
    surfaceVariant = NightLine,
    onSurfaceVariant = NightMuted,
    outline = Color(0xFF8C9186),
    outlineVariant = NightLine,
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
)
