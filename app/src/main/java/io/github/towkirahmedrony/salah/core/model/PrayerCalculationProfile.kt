package io.github.towkirahmedrony.salah.core.model

/** Juristic method used for the Asr prayer time. */
enum class AsrCalculationMethod {
    /** Majority position (Shafi'i, Maliki, Hanbali and others): an object's own shadow. */
    STANDARD,

    /** Hanafi position: twice an object's shadow. Produces a later Asr. */
    HANAFI,
}

/**
 * How twilight is bounded at high latitudes, where the sun may not reach the Fajr or Isha
 * depression angle at all.
 *
 * These values mirror the rules the engine implements. There is no "off" switch: the engine
 * always applies *some* bound, because without one a summer Fajr or Isha is undefined.
 */
enum class HighLatitudeRule {
    /**
     * Let the engine pick the rule recommended for the location's latitude. This is what Salah
     * uses by default and it is the only value that adapts to the location.
     */
    AUTOMATIC,

    /** Fajr is never earlier, and Isha never later, than the middle of the night. */
    MIDDLE_OF_THE_NIGHT,

    /** Fajr is bounded by the last seventh of the night, Isha by the first seventh. */
    SEVENTH_OF_THE_NIGHT,

    /** As [SEVENTH_OF_THE_NIGHT], but the fraction is derived from the twilight angles. */
    TWILIGHT_ANGLE,
}

/**
 * The inputs a prayer-time provider needs in addition to a location and a date.
 *
 * This is a *configuration object*, not a religious authority. It is produced from
 * [PrayerSettings], travels with every [PrayerSchedule], and is part of what decides whether a
 * cached schedule may still be shown: a schedule calculated under one profile is never served
 * for a request made under another.
 */
data class PrayerCalculationProfile(
    val method: PrayerCalculationMethod = PrayerCalculationMethod.DEFAULT,
    val asrMethod: AsrCalculationMethod = AsrCalculationMethod.HANAFI,
    val highLatitudeRule: HighLatitudeRule = HighLatitudeRule.AUTOMATIC,
    /** Per-prayer manual adjustments in minutes, applied by the engine that produces the schedule. */
    val adjustmentsMinutes: Map<PrayerName, Int> = emptyMap(),
) {
    /** Manual correction applied to [prayer], or `0`. */
    fun adjustmentFor(prayer: PrayerName): Int = adjustmentsMinutes[prayer] ?: 0

    companion object {
        /**
         * A commonly used starting configuration for Bangladesh: Karachi angles with Hanafi Asr.
         *
         * This is only a configurable default. It is **not** a verified Islamic Foundation
         * Bangladesh timetable and is never labelled as an official source. See
         * `docs/PRAYER_CALCULATION.md` for what would be required to claim such a match.
         */
        val DEFAULT_KARACHI_HANAFI: PrayerCalculationProfile = PrayerSettings.DEFAULT.toProfile()
    }
}
