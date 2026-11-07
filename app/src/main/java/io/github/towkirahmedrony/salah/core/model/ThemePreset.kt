package io.github.towkirahmedrony.salah.core.model

/**
 * The colour theme of the app, persisted locally beside the appearance mode.
 *
 * The preset chooses the colour scheme only. It is deliberately independent of [ThemeMode] (light,
 * dark or the device) and of [UiStyle] (spacing and surface treatment), so changing one of the three
 * can never reset the other two. [DEFAULT] is the existing Salah brand palette and is the value a fresh
 * install reads, so an upgrade changes nothing until the user chooses a theme.
 */
enum class ThemePreset {
    DEFAULT,
    EMERALD,
    OCEAN,
    MIDNIGHT,
    ROSE,
    LAVENDER,
    AMOLED,
    SAND,
}
