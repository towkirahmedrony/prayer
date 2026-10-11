package io.github.towkirahmedrony.salah.feature.common

import androidx.annotation.StringRes
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.model.AsrCalculationMethod
import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import io.github.towkirahmedrony.salah.core.model.HighLatitudeRule
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationMethod
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.SolarEvent
import io.github.towkirahmedrony.salah.core.model.TimeFormatPreference
import io.github.towkirahmedrony.salah.core.model.UnavailabilityReason

/**
 * Maps domain types to string resources.
 *
 * Domain and data layers stay free of Android resources; this is the presentation-side mapping
 * layer, which keeps localisation out of the business logic.
 */
@StringRes
fun PrayerName.labelRes(): Int = when (this) {
    PrayerName.FAJR -> R.string.prayer_fajr
    PrayerName.DHUHR -> R.string.prayer_dhuhr
    PrayerName.ASR -> R.string.prayer_asr
    PrayerName.MAGHRIB -> R.string.prayer_maghrib
    PrayerName.ISHA -> R.string.prayer_isha
}

@StringRes
fun SolarEvent.labelRes(): Int = when (this) {
    SolarEvent.SUNRISE -> R.string.event_sunrise
    SolarEvent.SOLAR_NOON -> R.string.event_solar_noon
    SolarEvent.SUNSET -> R.string.event_sunset
}

@StringRes
fun CalendarSystem.sectionTitleRes(): Int = when (this) {
    CalendarSystem.GREGORIAN -> R.string.home_gregorian_label
    CalendarSystem.BANGLA -> R.string.home_bangla_date_title
    CalendarSystem.HIJRI -> R.string.home_hijri_date_title
}

/**
 * Calculation-method names.
 *
 * These are the conventional names of the methods the engine implements. They identify a
 * calculation convention; they are not a claim that Salah reproduces any authority's published
 * timetable, and no such wording may be added without a real comparison.
 */
@StringRes
fun PrayerCalculationMethod.labelRes(): Int = when (this) {
    PrayerCalculationMethod.KARACHI -> R.string.method_karachi
    PrayerCalculationMethod.MUSLIM_WORLD_LEAGUE -> R.string.method_muslim_world_league
    PrayerCalculationMethod.EGYPTIAN -> R.string.method_egyptian
    PrayerCalculationMethod.UMM_AL_QURA -> R.string.method_umm_al_qura
    PrayerCalculationMethod.DUBAI -> R.string.method_dubai
    PrayerCalculationMethod.MOON_SIGHTING_COMMITTEE -> R.string.method_moon_sighting_committee
    PrayerCalculationMethod.NORTH_AMERICA -> R.string.method_north_america
    PrayerCalculationMethod.KUWAIT -> R.string.method_kuwait
    PrayerCalculationMethod.QATAR -> R.string.method_qatar
    PrayerCalculationMethod.SINGAPORE -> R.string.method_singapore
    PrayerCalculationMethod.TURKEY -> R.string.method_turkey
}

/** Short hint naming the twilight angles, so the choice is not a bare name. */
@StringRes
fun PrayerCalculationMethod.hintRes(): Int = when (this) {
    PrayerCalculationMethod.KARACHI -> R.string.method_karachi_hint
    PrayerCalculationMethod.MUSLIM_WORLD_LEAGUE -> R.string.method_muslim_world_league_hint
    PrayerCalculationMethod.EGYPTIAN -> R.string.method_egyptian_hint
    PrayerCalculationMethod.UMM_AL_QURA -> R.string.method_umm_al_qura_hint
    PrayerCalculationMethod.DUBAI -> R.string.method_dubai_hint
    PrayerCalculationMethod.MOON_SIGHTING_COMMITTEE -> R.string.method_moon_sighting_committee_hint
    PrayerCalculationMethod.NORTH_AMERICA -> R.string.method_north_america_hint
    PrayerCalculationMethod.KUWAIT -> R.string.method_kuwait_hint
    PrayerCalculationMethod.QATAR -> R.string.method_qatar_hint
    PrayerCalculationMethod.SINGAPORE -> R.string.method_singapore_hint
    PrayerCalculationMethod.TURKEY -> R.string.method_turkey_hint
}

@StringRes
fun AsrCalculationMethod.labelRes(): Int = when (this) {
    AsrCalculationMethod.STANDARD -> R.string.asr_standard
    AsrCalculationMethod.HANAFI -> R.string.asr_hanafi
}

@StringRes
fun AsrCalculationMethod.hintRes(): Int = when (this) {
    AsrCalculationMethod.STANDARD -> R.string.asr_standard_hint
    AsrCalculationMethod.HANAFI -> R.string.asr_hanafi_hint
}

@StringRes
fun HighLatitudeRule.labelRes(): Int = when (this) {
    HighLatitudeRule.AUTOMATIC -> R.string.high_latitude_automatic
    HighLatitudeRule.MIDDLE_OF_THE_NIGHT -> R.string.high_latitude_middle_of_the_night
    HighLatitudeRule.SEVENTH_OF_THE_NIGHT -> R.string.high_latitude_seventh_of_the_night
    HighLatitudeRule.TWILIGHT_ANGLE -> R.string.high_latitude_twilight_angle
}

@StringRes
fun TimeFormatPreference.labelRes(): Int = when (this) {
    TimeFormatPreference.SYSTEM -> R.string.time_format_system
    TimeFormatPreference.TWELVE_HOUR -> R.string.time_format_twelve_hour
    TimeFormatPreference.TWENTY_FOUR_HOUR -> R.string.time_format_twenty_four_hour
}

@StringRes
fun UnavailabilityReason.messageRes(): Int = when (this) {
    UnavailabilityReason.NO_LOCATION_CONFIGURED -> R.string.reason_no_location
    UnavailabilityReason.CALCULATION_NOT_CONFIGURED -> R.string.reason_calculation_not_configured
    UnavailabilityReason.CALCULATION_NOT_POSSIBLE -> R.string.reason_calculation_not_possible
    UnavailabilityReason.RULES_NOT_VERIFIED -> R.string.reason_rules_not_verified
    UnavailabilityReason.CALENDAR_NOT_IMPLEMENTED -> R.string.reason_calendar_not_implemented
    UnavailabilityReason.NETWORK_UNAVAILABLE -> R.string.reason_network_unavailable
    UnavailabilityReason.UNKNOWN -> R.string.reason_unknown
}
