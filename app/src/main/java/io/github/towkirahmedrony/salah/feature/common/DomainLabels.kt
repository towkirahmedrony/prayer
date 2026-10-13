package io.github.towkirahmedrony.salah.feature.common

import androidx.annotation.StringRes
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.domain.prohibited.ProhibitedPeriodPhase
import io.github.towkirahmedrony.salah.core.domain.prohibited.ProhibitedWindowKind
import io.github.towkirahmedrony.salah.core.model.AppLanguage
import io.github.towkirahmedrony.salah.core.model.AsrCalculationMethod
import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import io.github.towkirahmedrony.salah.core.model.HighLatitudeRule
import io.github.towkirahmedrony.salah.core.model.HijriDateSource
import io.github.towkirahmedrony.salah.core.model.IslamicOccasion
import io.github.towkirahmedrony.salah.core.model.DateLanguagePreference
import io.github.towkirahmedrony.salah.core.model.NotificationSound
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

/**
 * Names of the prohibited intervals.
 *
 * Each name states which event the interval belongs to. They are labels for the interval, not for
 * the prayer period around it: the interval around sunrise is not the whole Fajr period, and the
 * wording must not suggest that it is.
 */
@StringRes
fun ProhibitedWindowKind.labelRes(): Int = when (this) {
    ProhibitedWindowKind.SUNRISE_ASCENT -> R.string.prohibited_kind_sunrise_ascent
    ProhibitedWindowKind.ZENITH_DECLINE -> R.string.prohibited_kind_zenith_decline
    ProhibitedWindowKind.SUNSET_SETTING -> R.string.prohibited_kind_sunset_setting
}

/** Where an interval stands: active, upcoming, or already past for this local date. */
@StringRes
fun ProhibitedPeriodPhase.labelRes(): Int = when (this) {
    ProhibitedPeriodPhase.ACTIVE -> R.string.prohibited_phase_active
    ProhibitedPeriodPhase.UPCOMING -> R.string.prohibited_phase_upcoming
    ProhibitedPeriodPhase.PASSED -> R.string.prohibited_phase_passed
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

/**
 * Reminder sound names.
 *
 * A sound choice is never a bare name: [hintRes] states what will actually be heard, so `Silent`
 * cannot be mistaken for "no reminder" and the bundled chime is not presented as something it is
 * not.
 */
@StringRes
fun NotificationSound.labelRes(): Int = when (this) {
    NotificationSound.SYSTEM_DEFAULT -> R.string.notification_sound_system_default
    NotificationSound.SALAH_CHIME -> R.string.notification_sound_salah_chime
    NotificationSound.SILENT -> R.string.notification_sound_silent
}

@StringRes
fun NotificationSound.hintRes(): Int = when (this) {
    NotificationSound.SYSTEM_DEFAULT -> R.string.notification_sound_system_default_hint
    NotificationSound.SALAH_CHIME -> R.string.notification_sound_salah_chime_hint
    NotificationSound.SILENT -> R.string.notification_sound_silent_hint
}

/**
 * Labels the provenance of a Hijri date.
 *
 * Both branches say "estimated". That is not hedging: Salah has no authority data source integrated,
 * so there is no state in which it could honestly present a Hijri date as confirmed, and the wording
 * is the same everywhere the date appears.
 */
@StringRes
fun HijriDateSource.labelRes(): Int = when (this) {
    HijriDateSource.ESTIMATED_UMM_AL_QURA -> R.string.calendar_hijri_source_calculated
    HijriDateSource.ESTIMATED_USER_ADJUSTED -> R.string.calendar_hijri_source_adjusted
}

/** Names of the observances Salah derives from the Hijri calendar. */
@StringRes
fun IslamicOccasion.labelRes(): Int = when (this) {
    IslamicOccasion.ISLAMIC_NEW_YEAR -> R.string.occasion_islamic_new_year
    IslamicOccasion.ASHURA -> R.string.occasion_ashura
    IslamicOccasion.MAWLID -> R.string.occasion_mawlid
    IslamicOccasion.RAMADAN_BEGINS -> R.string.occasion_ramadan_begins
    IslamicOccasion.ARAFAH -> R.string.occasion_arafah
    IslamicOccasion.EID_AL_ADHA -> R.string.occasion_eid_al_adha
    IslamicOccasion.EID_AL_FITR -> R.string.occasion_eid_al_fitr
}

/** Names of the calendar views and of the date-language choices. */
@StringRes
fun CalendarSystem.displayNameRes(): Int = when (this) {
    CalendarSystem.GREGORIAN -> R.string.calendar_view_gregorian
    CalendarSystem.BANGLA -> R.string.calendar_view_bangla
    CalendarSystem.HIJRI -> R.string.calendar_view_hijri
}

/** One-line statement of what each calendar view actually is. */
@StringRes
fun CalendarSystem.hintRes(): Int = when (this) {
    CalendarSystem.GREGORIAN -> R.string.calendar_view_gregorian_hint
    CalendarSystem.BANGLA -> R.string.calendar_view_bangla_hint
    CalendarSystem.HIJRI -> R.string.calendar_view_hijri_hint
}

@StringRes
fun DateLanguagePreference.labelRes(): Int = when (this) {
    DateLanguagePreference.SYSTEM -> R.string.calendar_language_system
    DateLanguagePreference.BANGLA -> R.string.calendar_language_bangla
    DateLanguagePreference.ENGLISH -> R.string.calendar_language_english
}

/**
 * The choices on the General screen's language selector.
 *
 * Deliberately separate resources from [DateLanguagePreference]'s labels even though two of them read
 * the same: the calendar's date language and the interface language are different preferences, and
 * sharing one resource would make a later change to either label silently change the wrong control.
 * `Follow the device` is named rather than shown as a blank, because it is a real choice — the one a
 * fresh install is on — and an empty radio row would look like a missing option.
 */
@StringRes
fun AppLanguage.labelRes(): Int = when (this) {
    AppLanguage.SYSTEM -> R.string.settings_language_system
    AppLanguage.ENGLISH -> R.string.settings_language_english
    AppLanguage.BANGLA -> R.string.settings_language_bangla
}

@StringRes
fun UnavailabilityReason.messageRes(): Int = when (this) {
    UnavailabilityReason.NO_LOCATION_CONFIGURED -> R.string.reason_no_location
    UnavailabilityReason.CALCULATION_NOT_CONFIGURED -> R.string.reason_calculation_not_configured
    UnavailabilityReason.CALCULATION_NOT_POSSIBLE -> R.string.reason_calculation_not_possible
    UnavailabilityReason.RULES_NOT_VERIFIED -> R.string.reason_rules_not_verified
    UnavailabilityReason.CALENDAR_NOT_IMPLEMENTED -> R.string.reason_calendar_not_implemented
    UnavailabilityReason.OUT_OF_SUPPORTED_RANGE -> R.string.reason_calendar_out_of_range
    UnavailabilityReason.NETWORK_UNAVAILABLE -> R.string.reason_network_unavailable
    UnavailabilityReason.UNKNOWN -> R.string.reason_unknown
}
