package io.github.towkirahmedrony.salah.core.domain.location

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Formats the numbers the location screens show, in `Locale.ROOT`.
 *
 * **Why `Locale.ROOT`.** A latitude and a longitude are identifiers, not prose. Rendered in a locale
 * whose decimal separator is a comma, `23.810300, 90.412500` becomes ambiguous the moment it is copied
 * into another field, and an Arabic-Indic-digit locale would turn a coordinate into something no map
 * accepts. The same reasoning already applies to countdowns and zone offsets elsewhere in Salah.
 *
 * **Why coordinates are shown at all.** They are not on screen by default — every screen shows the
 * area and the source instead — but an advanced details section and the GPS confirmation do show them,
 * because a user who believes the fix is wrong needs to be able to see the number that will be saved.
 * When they are shown, they are shown exactly: six decimal places, which is about 11 cm and more than
 * enough to reproduce the position.
 */
object LocationFormatting {

    /** Decimal places used for a displayed coordinate. Six is ~0.11 m; more would be noise. */
    const val COORDINATE_DECIMALS: Int = 6

    /** `23.810300, 90.412500` — latitude first, exactly as every model in the app orders them. */
    fun coordinates(point: GeoPoint): String =
        "${fixed(point.latitude)}, ${fixed(point.longitude)}"

    /** `±12 m`, `±1.2 km`, or `null` when the provider reported no accuracy. */
    fun accuracy(metres: Double?): String? {
        if (metres == null || !metres.isFinite() || metres <= 0.0) return null
        return "±${distance(metres)}"
    }

    /** `450 m`, `3.4 km`, `37 km` — a distance short enough to read at a glance. */
    fun distance(metres: Double): String = when {
        !metres.isFinite() -> "—"
        abs(metres) < 1_000.0 -> "${metres.roundToInt()} m"
        abs(metres) < 10_000.0 -> "${oneDecimal(metres / 1_000.0)} km"
        else -> "${(metres / 1_000.0).roundToInt()} km"
    }

    private fun fixed(value: Double): String = String.format(Locale.ROOT, "%.${COORDINATE_DECIMALS}f", value)

    private fun oneDecimal(value: Double): String = String.format(Locale.ROOT, "%.1f", value)
}
