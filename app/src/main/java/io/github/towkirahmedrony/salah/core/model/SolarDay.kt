package io.github.towkirahmedrony.salah.core.model

import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The day's solar geometry, derived from the schedule's own solar events.
 *
 * Every value is derived from the offset-free solar events the engine produced (see
 * [io.github.towkirahmedrony.salah.core.domain.prayer.PrayerTimesProvider]), never from a prayer
 * time and never from a clock. A field is `null` when the event it needs is genuinely unavailable —
 * a polar schedule, for instance — rather than being filled with an approximation.
 *
 * @param date the local date the geometry describes, at the prayer location.
 * @param zoneId the zone every instant here is expressed in.
 * @param sunrise true sunrise, or `null` when it is not defined for this place and date.
 * @param sunset true sunset, or `null`.
 * @param solarNoon the true solar transit (local solar noon), or `null`.
 * @param solarMidnight the anti-transit, or `null` when [solarNoon] is.
 * @param dayLength the measured span from sunrise to sunset, or `null`.
 * @param nightLength the complement of [dayLength] within a 24-hour day, or `null`.
 */
data class SolarDay(
    val date: LocalDate,
    val zoneId: ZoneId,
    val sunrise: ZonedDateTime?,
    val sunset: ZonedDateTime?,
    val solarNoon: ZonedDateTime?,
    val solarMidnight: ZonedDateTime?,
    val dayLength: Duration?,
    val nightLength: Duration?,
)

/** State of the solar-day section. A missing schedule or event is reported, never invented. */
sealed interface SolarDayState {
    data class Available(val day: SolarDay) : SolarDayState

    data class NotAvailable(
        val reason: UnavailabilityReason,
        val detail: String? = null,
    ) : SolarDayState
}
