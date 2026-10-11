package io.github.towkirahmedrony.salah.core.model

/** How times are rendered on the Home screen. */
enum class TimeFormatPreference {
    /** Follow the device locale's short time format. */
    SYSTEM,

    /** Force a 12-hour clock with an AM/PM marker, for example `5:54 PM`. */
    TWELVE_HOUR,

    /** Force a 24-hour clock, for example `17:54`. */
    TWENTY_FOUR_HOUR,
}

/**
 * Everything the user can configure about prayer-time calculation, plus how those times are
 * displayed. This is one flat, immutable object on purpose: it is persisted as a unit, it is
 * compared as a unit to decide whether a cached schedule is still valid, and it maps directly
 * onto [PrayerCalculationProfile], which is what the engine consumes.
 *
 * Salah never changes these values on its own. Every field is written only from the prayer
 * settings screen, and the engine is never re-tuned behind the user's back.
 */
data class PrayerSettings(
    val method: PrayerCalculationMethod = PrayerCalculationMethod.DEFAULT,
    val asrMethod: AsrCalculationMethod = AsrCalculationMethod.HANAFI,
    /**
     * Bounds Fajr and Isha so they cannot run away on long summer days. At Bangladesh's
     * latitude this rule is never reached: it only clamps times that would otherwise fall
     * outside the night.
     */
    val highLatitudeRule: HighLatitudeRule = HighLatitudeRule.AUTOMATIC,
    /** Manual per-prayer corrections in minutes. Only non-zero values are kept. */
    val adjustmentsMinutes: Map<PrayerName, Int> = emptyMap(),
    val timeFormat: TimeFormatPreference = TimeFormatPreference.SYSTEM,
) {

    /** Manual correction applied to [prayer], or `0`. */
    fun adjustmentFor(prayer: PrayerName): Int = adjustmentsMinutes[prayer] ?: 0

    /** True when at least one prayer carries a manual correction. */
    val hasAdjustments: Boolean get() = adjustmentsMinutes.values.any { it != 0 }

    /**
     * Returns a copy with [prayer]'s correction set to [minutes], clamped to the supported
     * range. A clamped-to-zero correction removes the entry, so the stored map only ever holds
     * meaningful values.
     */
    fun withAdjustment(prayer: PrayerName, minutes: Int): PrayerSettings {
        val clamped = clampAdjustment(minutes)
        val next = adjustmentsMinutes.toMutableMap()
        if (clamped == 0) next.remove(prayer) else next[prayer] = clamped
        return copy(adjustmentsMinutes = next)
    }

    /** Removes every manual correction. */
    fun withoutAdjustments(): PrayerSettings = copy(adjustmentsMinutes = emptyMap())

    /** The calculation input this configuration produces. */
    fun toProfile(): PrayerCalculationProfile = PrayerCalculationProfile(
        method = method,
        asrMethod = asrMethod,
        highLatitudeRule = highLatitudeRule,
        adjustmentsMinutes = adjustmentsMinutes.filterValues { it != 0 },
    )

    companion object {
        /** Largest manual correction Salah accepts, in minutes, in either direction. */
        const val MAX_ADJUSTMENT_MINUTES: Int = 60

        /** Fixes an out-of-range or arbitrary stored value. */
        fun clampAdjustment(minutes: Int): Int =
            minutes.coerceIn(-MAX_ADJUSTMENT_MINUTES, MAX_ADJUSTMENT_MINUTES)

        /** The configuration a fresh install starts from. */
        val DEFAULT: PrayerSettings = PrayerSettings()
    }
}
