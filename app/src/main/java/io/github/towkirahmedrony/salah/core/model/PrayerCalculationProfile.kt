package io.github.towkirahmedrony.salah.core.model

/** Juristic method used for the Asr prayer time. */
enum class AsrCalculationMethod {
    /** Majority position (Shafi'i, Maliki, Hanbali and others). */
    STANDARD,

    /** Hanafi position. */
    HANAFI,
}

/** How twilight is handled at high latitudes where true twilight may not occur. */
enum class HighLatitudeRule {
    /** No adjustment. Only valid where the sun reaches the required depression angles. */
    NONE,

    MIDDLE_OF_THE_NIGHT,

    SEVENTH_OF_THE_NIGHT,

    TWILIGHT_ANGLE,
}

/**
 * The inputs a prayer-time provider needs in addition to a location and a date.
 *
 * This profile is a *configuration object*: Salah does not present a profile as a verified
 * religious authority. The engine that would consume it is not integrated yet.
 */
data class PrayerCalculationProfile(
    /** Identifier of the calculation method, for example `KARACHI` or `MWL`. */
    val methodId: String,
    val asrMethod: AsrCalculationMethod,
    val highLatitudeRule: HighLatitudeRule,
    /** Per-prayer manual adjustments in minutes, applied by the engine that produces the schedule. */
    val adjustmentsMinutes: Map<PrayerName, Int> = emptyMap(),
) {
    companion object {
        /**
         * A commonly used starting configuration for Bangladesh (Karachi method, Hanafi Asr).
         *
         * This is only a configurable default. It is **not** a verified Islamic Foundation
         * Bangladesh timetable and is never labelled as an official source.
         */
        val DEFAULT_KARACHI_HANAFI = PrayerCalculationProfile(
            methodId = "KARACHI",
            asrMethod = AsrCalculationMethod.HANAFI,
            highLatitudeRule = HighLatitudeRule.NONE,
        )
    }
}
