package io.github.towkirahmedrony.salah.core.model

/**
 * The layout and surface style of the app, persisted independently of the colour preset and mode.
 *
 * A style changes the design-system tokens every screen already reads — spacing scale, corner radii,
 * card padding, borders and elevation — rather than the palette. [CLASSIC] carries the values the app
 * had before this preference existed, so the default appearance is unchanged.
 */
enum class UiStyle {
    CLASSIC,
    MINIMAL,
    MODERN,
    COMPACT,
}
