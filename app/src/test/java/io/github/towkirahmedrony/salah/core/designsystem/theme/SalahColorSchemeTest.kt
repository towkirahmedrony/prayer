package io.github.towkirahmedrony.salah.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import io.github.towkirahmedrony.salah.core.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * The colour schemes, and the accents that sit outside them.
 *
 * Two kinds of assertion, both aimed at the defects the audit found rather than at the values written
 * down today:
 *
 *  - **Coverage.** A scheme that leaves a role out does not get a neutral default: it gets Material's
 *    *baseline* one, so a dropdown menu painted `surfaceContainer` came out the baseline's lavender and
 *    the calendar's observance marker came out the baseline's plum. The tests below pin the roles to
 *    Salah's own palette and give the surface tiers a shape (an ordered ladder that steps away from the
 *    card), so a role silently falling back to Material is a failure rather than a colour nobody notices.
 *  - **Contrast.** WCAG's ratios, computed here from the colour components so the test measures what it
 *    claims to and needs no runtime. Text pairs are held to 4.5:1, the parts of a control that identify it
 *    to 3:1. This is the half that would have caught the reported problems: `outline` and `outlineVariant`
 *    were the same cream hairline, so every control that outlines itself with `outline` — the calendar's
 *    view chips, the location form's unfocused fields — drew a boundary at about 1.2:1, and the dark
 *    scheme's marker and hero colours were never measured against anything at all.
 *
 * What is deliberately *not* asserted: the ratio of `secondary` (the brand's mid gold) against the light
 * card. It is about 2.6:1 there, it is used as a large decorative fill — the Qibla dial's band — and the
 * gold that carries meaning as text or as a small mark is the accent's [SalahAccents.goldText], which is
 * held to 4.5:1 below. Naming the mid gold's own ratio as a target would be a claim the brand palette
 * does not make.
 */
class SalahColorSchemeTest {

    private val light = SalahLightColorScheme
    private val dark = SalahDarkColorScheme
    private val themes = listOf("light" to light, "dark" to dark)
    private val accents = mapOf("light" to salahAccents(darkTheme = false), "dark" to salahAccents(darkTheme = true))

    // --- Theme mode mapping -----------------------------------------------------

    @Test
    fun `system follows the device and the two explicit modes override it`() {
        // The mapping the Appearance screen's choice passes through. A mode that only worked in one
        // direction would leave a user who picked Light on a dark phone stuck in the dark scheme.
        assertTrue(resolveDarkTheme(ThemeMode.SYSTEM, systemInDarkTheme = true))
        assertTrue(!resolveDarkTheme(ThemeMode.SYSTEM, systemInDarkTheme = false))
        assertTrue(!resolveDarkTheme(ThemeMode.LIGHT, systemInDarkTheme = true))
        assertTrue(!resolveDarkTheme(ThemeMode.LIGHT, systemInDarkTheme = false))
        assertTrue(resolveDarkTheme(ThemeMode.DARK, systemInDarkTheme = false))
        assertTrue(resolveDarkTheme(ThemeMode.DARK, systemInDarkTheme = true))
    }

    // --- Role coverage ----------------------------------------------------------

    @Test
    fun `the roles Salah re-tints are Salah's, not Material's baseline`() {
        // The roles this project states explicitly. Each of these was undefined before the audit and so
        // rendered as Material's own palette — lavender surfaces, a plum `tertiary`, a violet
        // `surfaceTint` — inside a cream or forest-green app. Comparing against the baseline scheme is the
        // assertion that a future Material upgrade cannot quietly reintroduce them.
        val stated: List<Pair<String, ColorScheme.() -> Color>> = listOf(
            "primary" to { primary },
            "primaryContainer" to { primaryContainer },
            "secondary" to { secondary },
            "secondaryContainer" to { secondaryContainer },
            "tertiary" to { tertiary },
            "tertiaryContainer" to { tertiaryContainer },
            "background" to { background },
            "surface" to { surface },
            "surfaceVariant" to { surfaceVariant },
            "onSurfaceVariant" to { onSurfaceVariant },
            "surfaceTint" to { surfaceTint },
            "inverseSurface" to { inverseSurface },
            "inverseOnSurface" to { inverseOnSurface },
            "inversePrimary" to { inversePrimary },
            "outline" to { outline },
            "outlineVariant" to { outlineVariant },
            "surfaceDim" to { surfaceDim },
            "surfaceBright" to { surfaceBright },
            "surfaceContainerLowest" to { surfaceContainerLowest },
            "surfaceContainerLow" to { surfaceContainerLow },
            "surfaceContainer" to { surfaceContainer },
            "surfaceContainerHigh" to { surfaceContainerHigh },
            "surfaceContainerHighest" to { surfaceContainerHighest },
        )

        themes.forEach { (name, scheme) ->
            val baseline = if (name == "light") lightColorScheme() else darkColorScheme()
            stated.forEach { (role, read) ->
                assertNotEquals(
                    "the $role of the $name scheme is still Material's baseline colour",
                    baseline.read(),
                    scheme.read(),
                )
            }
        }
    }

    @Test
    fun `tertiary is Salah's own accent rather than the baseline plum`() {
        // The concrete instance of the coverage rule, named because it is the one that was visible: the
        // calendar marked the year's observances in Material's #7D5260 pink in light mode and #EFB8C8 in
        // dark, colours that appear nowhere else in the app.
        assertEquals(Terracotta, light.tertiary)
        assertEquals(TerracottaDark, dark.tertiary)
        assertNotEquals(lightColorScheme().tertiary, light.tertiary)
        assertNotEquals(darkColorScheme().tertiary, dark.tertiary)
    }

    @Test
    fun `the surface tiers form a ladder that steps away from the card`() {
        // Material paints an unselected control's fill, a menu's container and a card from three different
        // tiers, so the tiers have to be ordered and distinguishable: a ladder that collapses onto the card
        // is how a dropdown menu ends up the same colour as the surface it opens over.
        themes.forEach { (name, scheme) ->
            val ladder = listOf(
                scheme.surfaceContainerLowest,
                scheme.surfaceContainerLow,
                scheme.surfaceContainer,
                scheme.surfaceContainerHigh,
                scheme.surfaceContainerHighest,
            ).map { it.relativeLuminance() }

            val descending = ladder.zipWithNext().all { (lower, higher) -> lower > higher }
            val ascending = ladder.zipWithNext().all { (lower, higher) -> lower < higher }
            assertTrue("the surface tiers of the $name scheme are not ordered", descending || ascending)

            assertAtLeast(
                minimum = 1.15f,
                what = "the highest $name surface tier against the card it sits on",
                foreground = scheme.surfaceContainerHighest,
                background = scheme.surface,
            )
        }
    }

    @Test
    fun `the dark scheme is dark and the light scheme is light`() {
        // A guard against the schemes being swapped or an edit landing in the wrong one: everything else
        // here measures a pair, and a pair swapped wholesale would still measure well.
        assertTrue("the dark background is not dark", dark.background.relativeLuminance() < 0.05f)
        assertTrue("the dark surface is not dark", dark.surface.relativeLuminance() < 0.05f)
        assertTrue("the light background is not light", light.background.relativeLuminance() > 0.5f)
        assertTrue("the light surface is not light", light.surface.relativeLuminance() > 0.5f)
    }

    // --- Contrast ---------------------------------------------------------------

    @Test
    fun `every text role is legible on the surface it is drawn on`() {
        val textPairs: List<Triple<String, ColorScheme.() -> Color, ColorScheme.() -> Color>> = listOf(
            Triple("onBackground on background", { onBackground }, { background }),
            Triple("onSurface on surface", { onSurface }, { surface }),
            Triple("onSurfaceVariant on surface", { onSurfaceVariant }, { surface }),
            Triple("onSurfaceVariant on surfaceVariant", { onSurfaceVariant }, { surfaceVariant }),
            Triple("onPrimary on primary", { onPrimary }, { primary }),
            Triple("onPrimaryContainer on primaryContainer", { onPrimaryContainer }, { primaryContainer }),
            Triple("onSecondaryContainer on secondaryContainer", { onSecondaryContainer }, { secondaryContainer }),
            Triple("onTertiary on tertiary", { onTertiary }, { tertiary }),
            Triple("onTertiaryContainer on tertiaryContainer", { onTertiaryContainer }, { tertiaryContainer }),
            Triple("onError on error", { onError }, { error }),
            Triple("onErrorContainer on errorContainer", { onErrorContainer }, { errorContainer }),
            Triple("inverseOnSurface on inverseSurface", { inverseOnSurface }, { inverseSurface }),
        )

        themes.forEach { (name, scheme) ->
            textPairs.forEach { (what, foreground, background) ->
                assertAtLeast(
                    minimum = 4.5f,
                    what = "the $name scheme's $what",
                    foreground = scheme.foreground(),
                    background = scheme.background(),
                )
            }
        }
    }

    @Test
    fun `a control boundary and a marker clear the graphical threshold`() {
        // 3:1 is the ratio WCAG asks of the parts of a control that identify it: the outline of a text
        // field, the border of an unselected chip, and a marker that carries a date's meaning.
        val graphicalPairs: List<Triple<String, ColorScheme.() -> Color, ColorScheme.() -> Color>> = listOf(
            Triple("outline on surface", { outline }, { surface }),
            Triple("outline on background", { outline }, { background }),
            Triple("primary on surface", { primary }, { surface }),
            Triple("tertiary on surface", { tertiary }, { surface }),
        )

        themes.forEach { (name, scheme) ->
            graphicalPairs.forEach { (what, foreground, background) ->
                assertAtLeast(
                    minimum = 3.0f,
                    what = "the $name scheme's $what",
                    foreground = scheme.foreground(),
                    background = scheme.background(),
                )
            }
        }
    }

    @Test
    fun `outline is the boundary of a control and outlineVariant the decorative rule`() {
        // The mapping error the audit found: both roles were the same cream hairline, so an unfocused text
        // field and an unselected chip outlined themselves in a colour about 1.2:1 against the card. They
        // are two different jobs — one identifies a control, the other draws a rule — so they are two
        // different tones, and the divider stays quieter than the boundary.
        themes.forEach { (name, scheme) ->
            assertNotEquals("the two line roles are the same tone in the $name scheme", scheme.outline, scheme.outlineVariant)

            val boundary = contrastRatio(scheme.outline, scheme.surface)
            val rule = contrastRatio(scheme.outlineVariant, scheme.surface)
            assertTrue(
                "in the $name scheme the divider ($rule) is not quieter than the control boundary ($boundary)",
                rule < boundary,
            )
            // A rule still has to be a visible line rather than a whisper, even at the low end.
            assertAtLeast(
                minimum = 1.15f,
                what = "the $name scheme's outlineVariant against the card",
                foreground = scheme.outlineVariant,
                background = scheme.surface,
            )
        }
    }

    // --- Accents ----------------------------------------------------------------

    @Test
    fun `the gold accent is legible on the theme it belongs to`() {
        // One gold cannot serve both themes: the brand's mid gold reads on the dark canvas and is too
        // faint on cream, and the dark gold that reads on cream is too dim on a dark canvas. The accent
        // carries the value that belongs to its own theme, and this is the assertion that it does.
        themes.forEach { (name, scheme) ->
            val gold = accents.getValue(name).goldText
            assertAtLeast(
                minimum = 4.5f,
                what = "the $name accent's gold on the canvas",
                foreground = gold,
                background = scheme.background,
            )
            assertAtLeast(
                minimum = 4.5f,
                what = "the $name accent's gold on a card",
                foreground = gold,
                background = scheme.surface,
            )
        }

        // Two themes, two golds: the same value in both is exactly the defect this accent replaces.
        assertNotEquals(accents.getValue("light").goldText, accents.getValue("dark").goldText)
    }

    @Test
    fun `the Ramadan highlight keeps its content readable in both themes`() {
        themes.forEach { (name, scheme) ->
            val accent = accents.getValue(name)
            assertAtLeast(
                minimum = 4.5f,
                what = "the $name Ramadan row's text on its own fill",
                foreground = accent.onRamadanToday,
                background = accent.ramadanToday,
            )
            // The fill also has to be one the row's own card can be told apart from, or the highlight is
            // invisible even though its text is readable.
            assertAtLeast(
                minimum = 1.15f,
                what = "the $name Ramadan row's fill on the card",
                foreground = accent.ramadanToday,
                background = scheme.surface,
            )
        }
    }

    @Test
    fun `the brand surface keeps white type readable in both themes`() {
        // The next-prayer hero and the bottom bar are one pairing, declared once. White on the brand green
        // is a deliberate constant — the app's identity rather than a themed surface, like white type on a
        // brand-coloured button — so what is asserted is that the pairing is the *same* in both themes and
        // that it clears the text threshold, not that it changes with the theme.
        themes.forEach { (name, scheme) ->
            val accent = accents.getValue(name)
            assertAtLeast(
                minimum = 4.5f,
                what = "the $name brand surface's own content",
                foreground = accent.onBrandSurface,
                background = accent.brandSurface,
            )
            // It is still a surface on the page: a hero or a bar that matches the canvas is a hero or a bar
            // nobody can see.
            assertAtLeast(
                minimum = 1.15f,
                what = "the $name brand surface against the canvas",
                foreground = accent.brandSurface,
                background = scheme.background,
            )
        }

        assertEquals(accents.getValue("light").brandSurface, accents.getValue("dark").brandSurface)
        assertEquals(accents.getValue("light").onBrandSurface, accents.getValue("dark").onBrandSurface)
        assertEquals(ForestGreenPrimary, accents.getValue("dark").brandSurface)
    }

    // --- Helpers ----------------------------------------------------------------

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

    /** WCAG relative luminance, from the colour's own components. */
    private fun Color.relativeLuminance(): Float {
        fun linear(component: Float): Float = if (component <= 0.03928f) {
            component / 12.92f
        } else {
            ((component + 0.055f) / 1.055f).pow(2.4f)
        }

        return 0.2126f * linear(red) + 0.7152f * linear(green) + 0.0722f * linear(blue)
    }
}
