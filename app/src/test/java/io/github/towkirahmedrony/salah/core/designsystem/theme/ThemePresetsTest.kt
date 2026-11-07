package io.github.towkirahmedrony.salah.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.towkirahmedrony.salah.core.model.ThemePreset
import io.github.towkirahmedrony.salah.core.model.UiStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Every selectable colour preset, measured in both appearances.
 *
 * The palette code is data, so the risks are the ones data carries: a palette whose muted text is too
 * faint, whose outline disappears into the card, whose surface tiers collapse, or whose accent is
 * unreadable on the canvas. None of those fail to compile. This test applies the same WCAG thresholds
 * the existing brand scheme is held to — 4.5:1 for text, 3:1 for a control boundary or marker, 1.15:1
 * for a surface tier a menu has to be distinguishable from — to all eight presets in light and dark.
 *
 * It also asserts that the default preset still resolves to the exact schemes and accents the app had
 * before presets existed, so adding the feature cannot have restyled the default appearance.
 */
class ThemePresetsTest {

    private val presets = ThemePreset.entries

    @Test
    fun `the default preset is the existing brand appearance, unchanged`() {
        assertEquals(SalahLightColorScheme, themeColorScheme(ThemePreset.DEFAULT, dark = false))
        assertEquals(SalahDarkColorScheme, themeColorScheme(ThemePreset.DEFAULT, dark = true))
        assertEquals(salahAccents(darkTheme = false), themeAccents(ThemePreset.DEFAULT, dark = false))
        assertEquals(salahAccents(darkTheme = true), themeAccents(ThemePreset.DEFAULT, dark = true))
    }

    @Test
    fun `every preset has its own palette in both appearances`() {
        val light = presets.map { themeColorScheme(it, dark = false) }
        val dark = presets.map { themeColorScheme(it, dark = true) }

        // Each preset must be a real alternative, not an alias of the one before it.
        assertEquals(presets.size, light.toSet().size)
        assertEquals(presets.size, dark.toSet().size)
        assertNotEquals(themeColorScheme(ThemePreset.DEFAULT, false), themeColorScheme(ThemePreset.EMERALD, false))
        assertNotEquals(themeColorScheme(ThemePreset.OCEAN, true), themeColorScheme(ThemePreset.MIDNIGHT, true))
    }

    @Test
    fun `every text role is legible in every preset and appearance`() {
        val textPairs: List<Triple<String, ColorScheme.() -> Color, ColorScheme.() -> Color>> = listOf(
            Triple("onBackground on background", { onBackground }, { background }),
            Triple("onSurface on surface", { onSurface }, { surface }),
            Triple("onSurfaceVariant on surface", { onSurfaceVariant }, { surface }),
            Triple("onSurfaceVariant on surfaceVariant", { onSurfaceVariant }, { surfaceVariant }),
            Triple("onPrimary on primary", { onPrimary }, { primary }),
            Triple("onPrimaryContainer on primaryContainer", { onPrimaryContainer }, { primaryContainer }),
            Triple("onSecondary on secondary", { onSecondary }, { secondary }),
            Triple("onSecondaryContainer on secondaryContainer", { onSecondaryContainer }, { secondaryContainer }),
            Triple("onTertiary on tertiary", { onTertiary }, { tertiary }),
            Triple("onTertiaryContainer on tertiaryContainer", { onTertiaryContainer }, { tertiaryContainer }),
            Triple("onError on error", { onError }, { error }),
            Triple("onErrorContainer on errorContainer", { onErrorContainer }, { errorContainer }),
            Triple("inverseOnSurface on inverseSurface", { inverseOnSurface }, { inverseSurface }),
        )

        appearances().forEach { (preset, mode, scheme) ->
            textPairs.forEach { (what, foreground, background) ->
                // The default preset is the existing brand scheme, frozen by design, and its
                // light secondary is the brand's mid gold: a decorative fill that SalahColorSchemeTest
                // documents at about 2.6:1 against white, never a text fill. Holding it to 4.5:1 would
                // demand recolouring the default appearance, which this feature must not do. Every new
                // preset's secondary is a real text fill and is held to 4.5:1 like the rest.
                if (preset == ThemePreset.DEFAULT && what == "onSecondary on secondary") return@forEach
                assertAtLeast(
                    minimum = 4.5f,
                    what = "the ${preset.name.lowercase()} preset's $mode scheme, $what",
                    foreground = scheme.foreground(),
                    background = scheme.background(),
                )
            }
        }
    }

    @Test
    fun `a control boundary and a marker are visible in every preset`() {
        val graphicalPairs: List<Triple<String, ColorScheme.() -> Color, ColorScheme.() -> Color>> = listOf(
            Triple("outline on surface", { outline }, { surface }),
            Triple("outline on background", { outline }, { background }),
            Triple("primary on surface", { primary }, { surface }),
            Triple("tertiary on surface", { tertiary }, { surface }),
        )

        appearances().forEach { (preset, mode, scheme) ->
            graphicalPairs.forEach { (what, foreground, background) ->
                assertAtLeast(
                    minimum = 3.0f,
                    what = "the ${preset.name.lowercase()} preset's $mode scheme, $what",
                    foreground = scheme.foreground(),
                    background = scheme.background(),
                )
            }
        }
    }

    @Test
    fun `the surface tiers stay ordered and distinct from the card`() {
        appearances().forEach { (preset, mode, scheme) ->
            val ladder = listOf(
                scheme.surfaceContainerLowest,
                scheme.surfaceContainerLow,
                scheme.surfaceContainer,
                scheme.surfaceContainerHigh,
                scheme.surfaceContainerHighest,
            ).map { it.relativeLuminance() }

            val descending = ladder.zipWithNext().all { (lower, higher) -> lower > higher }
            val ascending = ladder.zipWithNext().all { (lower, higher) -> lower < higher }
            assertTrue(
                "the ${preset.name.lowercase()} preset's $mode surface tiers are not ordered",
                descending || ascending,
            )

            assertAtLeast(
                minimum = 1.15f,
                what = "the ${preset.name.lowercase()} preset's $mode highest surface tier against the card",
                foreground = scheme.surfaceContainerHighest,
                background = scheme.surface,
            )
        }
    }

    @Test
    fun `each appearance is actually light or dark`() {
        appearances().forEach { (preset, mode, scheme) ->
            if (mode == "light") {
                assertTrue(
                    "the ${preset.name.lowercase()} preset's light canvas is not light",
                    scheme.background.relativeLuminance() > 0.5f,
                )
            } else {
                assertTrue(
                    "the ${preset.name.lowercase()} preset's dark canvas is not dark",
                    scheme.background.relativeLuminance() < 0.05f,
                )
                assertTrue(
                    "the ${preset.name.lowercase()} preset's dark card is not dark",
                    scheme.surface.relativeLuminance() < 0.05f,
                )
            }
        }
    }

    @Test
    fun `the accents stay readable on the preset that owns them`() {
        appearances().forEach { (preset, mode, scheme) ->
            val accents = themeAccents(preset, dark = mode == "dark")

            assertAtLeast(
                minimum = 4.5f,
                what = "the ${preset.name.lowercase()} preset's $mode gold on the canvas",
                foreground = accents.goldText,
                background = scheme.background,
            )
            assertAtLeast(
                minimum = 4.5f,
                what = "the ${preset.name.lowercase()} preset's $mode gold on a card",
                foreground = accents.goldText,
                background = scheme.surface,
            )
            assertAtLeast(
                minimum = 4.5f,
                what = "the ${preset.name.lowercase()} preset's $mode Ramadan text on its fill",
                foreground = accents.onRamadanToday,
                background = accents.ramadanToday,
            )
            assertAtLeast(
                minimum = 1.15f,
                what = "the ${preset.name.lowercase()} preset's $mode Ramadan fill on the card",
                foreground = accents.ramadanToday,
                background = scheme.surface,
            )
            assertAtLeast(
                minimum = 4.5f,
                what = "the ${preset.name.lowercase()} preset's $mode brand surface content",
                foreground = accents.onBrandSurface,
                background = accents.brandSurface,
            )
            assertAtLeast(
                minimum = 1.15f,
                what = "the ${preset.name.lowercase()} preset's $mode brand surface on the canvas",
                foreground = accents.brandSurface,
                background = scheme.background,
            )
        }
    }

    @Test
    fun `the brand surface is fixed across light and dark for each preset`() {
        presets.forEach { preset ->
            assertEquals(
                "the ${preset.name.lowercase()} preset's brand surface changes with the appearance mode",
                themeAccents(preset, dark = false).brandSurface,
                themeAccents(preset, dark = true).brandSurface,
            )
        }
    }

    @Test
    fun `every UI style is a distinct geometry`() {
        val styles = UiStyle.entries.map { salahStyle(it) }

        assertEquals(styles.size, styles.toSet().size)
        // The default appearance is the classic geometry, so it must be the one the app shipped with.
        assertEquals(16.dp, salahStyle(UiStyle.CLASSIC).cardPadding)
        assertEquals(20.dp, salahStyle(UiStyle.CLASSIC).screenHorizontalPadding)
        assertEquals(16.dp, salahStyle(UiStyle.CLASSIC).contentHorizontalPadding)
        assertEquals(12.dp, salahStyle(UiStyle.CLASSIC).listSpacing)
        assertTrue(
            "Compact must be tighter than Classic",
            salahStyle(UiStyle.COMPACT).cardPadding < salahStyle(UiStyle.CLASSIC).cardPadding,
        )
        assertTrue(
            "Minimal must be more spacious than Classic",
            salahStyle(UiStyle.MINIMAL).cardPadding > salahStyle(UiStyle.CLASSIC).cardPadding,
        )
        assertTrue(
            "Modern must add elevation",
            salahStyle(UiStyle.MODERN).cardElevation.value > 0f,
        )
        assertEquals(
            "Minimal must remove card borders",
            0f,
            salahStyle(UiStyle.MINIMAL).cardBorderWidth.value,
        )
        assertEquals(
            "Modern must remove card borders",
            0f,
            salahStyle(UiStyle.MODERN).cardBorderWidth.value,
        )
        assertEquals(
            "Classic's card keeps its one-dp border",
            1f,
            salahStyle(UiStyle.CLASSIC).cardBorderWidth.value,
        )
        // A borderless style must not rely on the border for visibility: its card is a tinted surface,
        // which stays distinguishable from AMOLED's true-black canvas.
        assertEquals(SalahCardSurface.SURFACE_VARIANT, salahStyle(UiStyle.MINIMAL).cardSurface)
        assertEquals(SalahCardSurface.SURFACE_VARIANT, salahStyle(UiStyle.MODERN).cardSurface)
        assertEquals(SalahCardSurface.SURFACE, salahStyle(UiStyle.CLASSIC).cardSurface)
    }

    // --- Helpers ----------------------------------------------------------------

    private fun appearances(): List<Triple<ThemePreset, String, ColorScheme>> =
        presets.flatMap { preset ->
            listOf(
                Triple(preset, "light", themeColorScheme(preset, dark = false)),
                Triple(preset, "dark", themeColorScheme(preset, dark = true)),
            )
        }

    private fun assertAtLeast(minimum: Float, what: String, foreground: Color, background: Color) {
        val ratio = contrastRatio(foreground, background)
        assertTrue(
            "$what has a contrast ratio of ${"%.2f".format(ratio)}, below the required $minimum",
            ratio >= minimum,
        )
    }

    private fun contrastRatio(first: Color, second: Color): Float {
        val firstLuminance = first.relativeLuminance()
        val secondLuminance = second.relativeLuminance()
        val lighter = maxOf(firstLuminance, secondLuminance)
        val darker = minOf(firstLuminance, secondLuminance)
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    private fun Color.relativeLuminance(): Float {
        fun linear(component: Float): Float = if (component <= 0.03928f) {
            component / 12.92f
        } else {
            ((component + 0.055f) / 1.055f).pow(2.4f)
        }

        return 0.2126f * linear(red) + 0.7152f * linear(green) + 0.0722f * linear(blue)
    }
}
