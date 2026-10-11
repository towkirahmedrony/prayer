package io.github.towkirahmedrony.salah.core.model

/**
 * The five obligatory daily prayers.
 *
 * Sunrise and sunset are astronomical events, not prayers, and are modelled separately by
 * [SolarEvent]. These concepts must never be treated as interchangeable.
 *
 * Declaration order is the conventional mention order (Fajr → Isha), not the chronological
 * order of a given day. Prayer schedules always carry explicit timestamps.
 */
enum class PrayerName {
    FAJR,
    DHUHR,
    ASR,
    MAGHRIB,
    ISHA,
}

/**
 * Astronomical events that are relevant to the daily timetable.
 *
 * [SUNRISE], [SOLAR_NOON] and [SUNSET] are solar events. The religious rulings that may be
 * associated with them are modelled separately by
 * [io.github.towkirahmedrony.salah.core.model.ProhibitedPeriodsState] and are deliberately
 * not derived here.
 */
enum class SolarEvent {
    SUNRISE,
    SOLAR_NOON,
    SUNSET,
}
