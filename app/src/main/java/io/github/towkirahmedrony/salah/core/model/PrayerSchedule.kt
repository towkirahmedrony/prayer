package io.github.towkirahmedrony.salah.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** A single prayer entry inside a [PrayerSchedule]. */
data class PrayerTimeEntry(
    val prayer: PrayerName,
    val time: ZonedDateTime,
    /** Manual adjustment in minutes that was applied to the engine output, if any. */
    val adjustmentMinutes: Int = 0,
)

/** A single solar event entry inside a [PrayerSchedule]. */
data class SolarEventEntry(
    val event: SolarEvent,
    val time: ZonedDateTime,
)

/**
 * One day of prayer and solar times for one location.
 *
 * A schedule is only produced by a provider that actually computed or fetched it. It carries
 * the profile, location and creation timestamp it was produced with, so a stored schedule can
 * never be silently reused for a different location, date or calculation profile.
 */
data class PrayerSchedule(
    val date: LocalDate,
    val location: LocationInfo,
    val profile: PrayerCalculationProfile,
    val prayers: List<PrayerTimeEntry>,
    val solarEvents: List<SolarEventEntry>,
    val generatedAt: Instant,
) {
    /** Zone the times in this schedule are expressed in. */
    val zoneId: ZoneId get() = location.zoneId

    /** True when all five obligatory prayers are present exactly once. */
    val isComplete: Boolean
        get() = prayers.size == PrayerName.entries.size &&
            prayers.map { it.prayer }.toSet().size == PrayerName.entries.size

    fun timeOf(prayer: PrayerName): ZonedDateTime? =
        prayers.firstOrNull { it.prayer == prayer }?.time

    fun timeOf(event: SolarEvent): ZonedDateTime? =
        solarEvents.firstOrNull { it.event == event }?.time
}
