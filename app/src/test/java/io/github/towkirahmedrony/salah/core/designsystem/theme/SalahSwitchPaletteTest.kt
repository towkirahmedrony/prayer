package io.github.towkirahmedrony.salah.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * The colours a reminder switch is drawn in.
 *
 * The reported defect was a *contrast* defect, so these assertions are contrast ratios rather than a
 * handful of hex values: an off track has to be visible against the card it sits on, and a thumb has to
 * be visible against its own track, in both themes. A test that only compared the palette to the values
 * written down today would pass just as happily if those values were changed to something invisible,
 * which is precisely the bug this palette exists to prevent.
 *
 * The ratios are WCAG's, computed here from the colour components rather than taken from a library, so
 * the test measures what it claims to and does not need the Compose runtime. 3:1 is the threshold WCAG
 * sets for the parts of a non-text control that identify it — its boundary against the background, and
 * one part of it against another.
 *
 * What is *not* asserted is a 3:1 ratio **between** the two track colours, and that is deliberate
 * rather than an omission. In the dark scheme the accent is a light mint and the card is nearly black,
 * so the only off-track that clears 3:1 against the card is another light tone: the two fills are then
 * separated by hue and saturation rather than by lightness. A switch's state is also carried by where
 * the thumb sits and by the state its assistive description announces, so lightness alone is not the
 * cue a user depends on. The palette still has to make the two states *different things*, and that is
 * what [off and on are different fills rather than two shades of one] checks.
 */
class SalahSwitchPaletteTest {

    private val light = SalahLightColorScheme
    private val dark = SalahDarkColorScheme
    private val themes = listOf("light" to light, "dark" to dark)

    @Test
    fun `an off switch is visible against the card it sits on in both themes`() {
        themes.forEach { (name, scheme) ->
            val palette = salahSwitchPalette(scheme)

            // Every switch in Salah sits on a `SalahSectionCard`, which is filled with the scheme's
            // surface, so that is the background the off track has to be distinguishable from. This is
            // the assertion that fails against Material's baseline colours, which matched this surface.
            assertAtLeast(
                minimum = 3.0f,
                what = "the off track of the $name switch",
                foreground = palette.uncheckedTrack,
                background = scheme.surface,
            )
            // And the knob is read against the track it slides on, not against the card.
            assertAtLeast(
                minimum = 3.0f,
                what = "the off thumb on its own track in the $name scheme",
                foreground = palette.uncheckedThumb,
                background = palette.uncheckedTrack,
            )
        }
    }

    @Test
    fun `an on switch is visible against the card and its thumb against its track`() {
        themes.forEach { (name, scheme) ->
            val palette = salahSwitchPalette(scheme)

            assertAtLeast(
                minimum = 3.0f,
                what = "the on track of the $name switch",
                foreground = palette.checkedTrack,
                background = scheme.surface,
            )
            assertAtLeast(
                minimum = 3.0f,
                what = "the on thumb on its own track in the $name scheme",
                foreground = palette.checkedThumb,
                background = palette.checkedTrack,
            )
        }
    }

    @Test
    fun `off and on are different fills rather than two shades of one`() {
        themes.forEach { (name, scheme) ->
            val palette = salahSwitchPalette(scheme)

            // The rule the palette encodes: the off state is the scheme's muted tone and the on state is
            // its accent, so the two can never be confused for one another however the theme changes.
            assertEquals(scheme.onSurfaceVariant, palette.uncheckedTrack)
            assertEquals(scheme.primary, palette.checkedTrack)

            assertNotEquals(palette.uncheckedTrack, palette.checkedTrack)
            assertNotEquals(palette.uncheckedBorder, palette.checkedBorder)
            // The *knob* is deliberately not asserted to differ between the two states. In the light scheme
            // it is white in both, because there `surface` and `onPrimary` are the same white; it is the
            // fill the knob slides on that carries the state, and the two assertions above pin that down.
            // The knob's own legibility on each of those fills is asserted separately.
        }
    }

    @Test
    fun `a disabled switch still reads as a switch rather than as the card`() {
        themes.forEach { (name, scheme) ->
            val palette = salahSwitchPalette(scheme)

            // A reminder switch is disabled whenever the master switch is off, and the requirement is
            // that the off state stays visible: a disabled track is dimmed, never the card's own colour,
            // and it keeps the enabled palette's distinction between off and on.
            assertNotEquals(scheme.surface, palette.disabledUncheckedTrack)
            assertNotEquals(scheme.surface, palette.disabledCheckedTrack)
            // A translucent colour is what a user actually sees *over* the card, so it is measured over
            // it rather than as a raw component: a disabled off-track that all but disappears into the
            // card is the same defect in a quieter place.
            assertAtLeast(
                minimum = 1.8f,
                what = "the disabled off track of the $name switch over the card",
                foreground = palette.disabledUncheckedTrack.compositedOver(scheme.surface),
                background = scheme.surface,
            )
            // Dimmed, not repainted: a disabled control is a quieter version of the same control.
            assertNotEquals(palette.disabledUncheckedTrack, palette.disabledCheckedTrack)
        }
    }

    @Test
    fun `the palette is drawn from the scheme it is given, not from fixed values`() {
        // The same function against two schemes has to produce two palettes; otherwise the light and
        // dark themes would be painting the same switch, and one of them would be wrong on its own
        // background.
        val lightPalette = salahSwitchPalette(light)
        val darkPalette = salahSwitchPalette(dark)

        assertNotEquals(lightPalette.uncheckedTrack, darkPalette.uncheckedTrack)
        assertNotEquals(lightPalette.checkedTrack, darkPalette.checkedTrack)
        assertNotEquals(lightPalette.uncheckedThumb, darkPalette.uncheckedThumb)
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

    /** [this] drawn over [background] at its own alpha, as the screen would show it. */
    private fun Color.compositedOver(background: Color): Color = Color(
        red = red * alpha + background.red * (1f - alpha),
        green = green * alpha + background.green * (1f - alpha),
        blue = blue * alpha + background.blue * (1f - alpha),
    )

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
