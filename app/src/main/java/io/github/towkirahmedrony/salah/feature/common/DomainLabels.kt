package io.github.towkirahmedrony.salah.feature.common

import androidx.annotation.StringRes
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.SolarEvent
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

@StringRes
fun UnavailabilityReason.messageRes(): Int = when (this) {
    UnavailabilityReason.NO_LOCATION_CONFIGURED -> R.string.reason_no_location
    UnavailabilityReason.CALCULATION_NOT_CONFIGURED -> R.string.reason_calculation_not_configured
    UnavailabilityReason.RULES_NOT_VERIFIED -> R.string.reason_rules_not_verified
    UnavailabilityReason.CALENDAR_NOT_IMPLEMENTED -> R.string.reason_calendar_not_implemented
    UnavailabilityReason.NETWORK_UNAVAILABLE -> R.string.reason_network_unavailable
    UnavailabilityReason.UNKNOWN -> R.string.reason_unknown
}
