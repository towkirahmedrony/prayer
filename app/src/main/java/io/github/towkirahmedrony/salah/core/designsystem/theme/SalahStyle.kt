package io.github.towkirahmedrony.salah.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.towkirahmedrony.salah.core.model.UiStyle

/** Which surface role a section card is painted with. */
enum class SalahCardSurface {
    /** The raised card colour. The outlined look; visible through its border. */
    SURFACE,

    /** A tinted container. Used by the borderless styles, which still need a visible card in every
     * palette — including AMOLED, where the card colour is the same true black as the canvas. */
    SURFACE_VARIANT,
}

/**
 * The measurable part of Salah's design system: how much room a screen, a card and a control get, and
 * what their corners and edges look like.
 *
 * Colours come from Material's scheme and the [SalahAccents]; everything geometric comes from here.
 * Screens read these tokens instead of writing their own `16.dp`, which is what lets one setting
 * restyle every screen consistently: there is no per-screen density variant to keep in step.
 *
 * [CLASSIC] is exactly the geometry the app shipped with — the same paddings, the same radii and the
 * same flat bordered cards — so the default style changes nothing. The other three are real layout
 * variations rather than a repaint:
 *
 *  - **Minimal** opens the spacing up, removes the card borders and rounds the surfaces more gently,
 *    so the page reads as one calm column instead of a stack of outlined boxes.
 *  - **Modern** keeps the app's density but gives cards a larger radius and a subtle elevation, with no
 *    border — a raised surface rather than an outlined one.
 *  - **Compact** tightens the screen margins, section gaps and card padding so more of a timetable or
 *    settings page fits on one screen.
 */
@Immutable
data class SalahStyle(
    val uiStyle: UiStyle,
    /** Corner radii for every Material shape role. */
    val shapes: Shapes,
    /** Horizontal margin of a settings-style page. */
    val screenHorizontalPadding: Dp,
    /** Horizontal margin of a content-dense page such as Home or the calendar. */
    val contentHorizontalPadding: Dp,
    /** Gap between independent sections or cards of a form. */
    val sectionSpacing: Dp,
    /** Gap between rows of one list, where the rows are already separated by their own shape. */
    val listSpacing: Dp,
    /** Inner padding of every [io.github.towkirahmedrony.salah.core.designsystem.components.SalahSectionCard]. */
    val cardPadding: Dp,
    /** Resting elevation of a section card. Zero is the flat, outlined look. */
    val cardElevation: Dp,
    /** Width of a section card's border. Zero removes the border entirely. */
    val cardBorderWidth: Dp,
    /** Which surface role a card is painted with. See [SalahCardSurface]. */
    val cardSurface: SalahCardSurface,
    /** Vertical padding of one row in a settings option group. */
    val optionRowSpacing: Dp,
)

/** The style currently applied by [SalahTheme]. The default is the app's original geometry. */
val LocalSalahStyle = staticCompositionLocalOf { salahStyle(UiStyle.CLASSIC) }

/**
 * The tokens of [style], as a plain value.
 *
 * A function rather than a branch inside the composable so the variations are a decision a test can
 * assert on, and so the Appearance screen can preview a style it has not selected yet.
 */
fun salahStyle(style: UiStyle): SalahStyle = when (style) {
    UiStyle.CLASSIC -> SalahStyle(
        uiStyle = style,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(30.dp),
        ),
        screenHorizontalPadding = 20.dp,
        contentHorizontalPadding = 16.dp,
        sectionSpacing = 16.dp,
        listSpacing = 12.dp,
        cardPadding = 16.dp,
        cardElevation = 0.dp,
        cardBorderWidth = 1.dp,
        cardSurface = SalahCardSurface.SURFACE,
        optionRowSpacing = 3.dp,
    )

    // Airy and quiet: wider margins, more space between cards, no outline. The shape stays generous
    // rather than becoming pill-shaped, so the app still reads as Salah.
    UiStyle.MINIMAL -> SalahStyle(
        uiStyle = style,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(6.dp),
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(22.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
        screenHorizontalPadding = 24.dp,
        contentHorizontalPadding = 20.dp,
        sectionSpacing = 24.dp,
        listSpacing = 20.dp,
        cardPadding = 20.dp,
        cardElevation = 0.dp,
        cardBorderWidth = 0.dp,
        cardSurface = SalahCardSurface.SURFACE_VARIANT,
        optionRowSpacing = 8.dp,
    )

    // Contemporary surfaces: a larger radius and a soft lift instead of a hairline border.
    UiStyle.MODERN -> SalahStyle(
        uiStyle = style,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(10.dp),
            small = RoundedCornerShape(16.dp),
            medium = RoundedCornerShape(22.dp),
            large = RoundedCornerShape(28.dp),
            extraLarge = RoundedCornerShape(34.dp),
        ),
        screenHorizontalPadding = 20.dp,
        contentHorizontalPadding = 16.dp,
        sectionSpacing = 18.dp,
        listSpacing = 14.dp,
        cardPadding = 20.dp,
        cardElevation = 2.dp,
        cardBorderWidth = 0.dp,
        cardSurface = SalahCardSurface.SURFACE_VARIANT,
        optionRowSpacing = 5.dp,
    )

    // Information-dense: narrower margins, tighter gaps and smaller inner padding, while keeping the
    // border that identifies a card without adding elevation.
    UiStyle.COMPACT -> SalahStyle(
        uiStyle = style,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(6.dp),
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(14.dp),
            large = RoundedCornerShape(18.dp),
            extraLarge = RoundedCornerShape(22.dp),
        ),
        screenHorizontalPadding = 16.dp,
        contentHorizontalPadding = 12.dp,
        sectionSpacing = 12.dp,
        listSpacing = 8.dp,
        cardPadding = 12.dp,
        cardElevation = 0.dp,
        cardBorderWidth = 1.dp,
        cardSurface = SalahCardSurface.SURFACE,
        optionRowSpacing = 1.dp,
    )
}
