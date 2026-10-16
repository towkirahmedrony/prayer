package io.github.towkirahmedrony.salah.core.domain.solar

import io.github.towkirahmedrony.salah.core.model.PrayerSchedule
import io.github.towkirahmedrony.salah.core.model.SolarDay
import io.github.towkirahmedrony.salah.core.model.SolarDayState
import io.github.towkirahmedrony.salah.core.model.SolarEvent
import io.github.towkirahmedrony.salah.core.model.UnavailabilityReason
import java.time.Duration

/**
 * Derives the day's solar geometry from an already calculated [PrayerSchedule].
 *
 * The resolver performs **no astronomy of its own**. Every instant it reports is read from the
 * schedule's solar events, which the engine produced with zero prayer and method offsets, so it can
 * neither drift from the displayed times nor become a second calculation engine. It is pure over the
 * schedule and reads no clock, so a result is a function of its input alone.
 *
 * ## Definitions
 *
 * - **Solar noon / solar transit** is the engine's own transit for the coordinates and date. It is
 *   the instant the sun is highest, which is *not* clock 12:00 and *not* the displayed Dhuhr prayer
 *   time (Dhuhr may carry a method or manual offset). See `docs/SOLAR_AND_NIGHT_PERIODS.md`.
 * - **Solar midnight** is the anti-transit: the instant the sun is lowest, half a solar day from the
 *   transit. It is computed here as the transit shifted by exactly twelve hours. The sun's
 *   declination changes across the half-day, so this is an approximation of the true lower
 *   culmination, accurate to about a minute at Bangladesh's latitudes; Salah does not compute the
 *   true nadir independently, and says so rather than implying more precision than it has. It is
 *   astronomically meaningless only where the transit itself is undefined, and in that case it is
 *   `null` like every other derived field.
 * - **Day length** is the measured span from sunrise to sunset.
 * - **Night length** is the complement of the day length within a 24-hour day (`24h − dayLength`).
 *   It is deliberately the *astronomical* night — sunset to sunrise — and is **not** the
 *   Maghrib-to-Fajr interval the night-period calculator uses, which is longer because both of its
 *   ends are prayer times rather than the geometric horizon crossings.
 */
class SolarDayResolver {

    fun resolve(schedule: PrayerSchedule?): SolarDayState {
        if (schedule == null) {
            return SolarDayState.NotAvailable(
                reason = UnavailabilityReason.UNKNOWN,
                detail = "No schedule is available yet, so the day's solar geometry cannot be derived.",
            )
        }

        val sunrise = schedule.timeOf(SolarEvent.SUNRISE)
        val sunset = schedule.timeOf(SolarEvent.SUNSET)
        val solarNoon = schedule.timeOf(SolarEvent.SOLAR_NOON)

        val dayLength = if (sunrise != null && sunset != null && sunset.isAfter(sunrise)) {
            Duration.between(sunrise, sunset)
        } else {
            null
        }
        val nightLength = dayLength
            ?.let { Duration.ofDays(1).minus(it) }
            ?.takeIf { !it.isNegative && !it.isZero }

        return SolarDayState.Available(
            day = SolarDay(
                date = schedule.date,
                zoneId = schedule.zoneId,
                sunrise = sunrise,
                sunset = sunset,
                solarNoon = solarNoon,
                solarMidnight = solarNoon?.plus(SOLAR_MIDNIGHT_OFFSET),
                dayLength = dayLength,
                nightLength = nightLength,
            ),
        )
    }

    private companion object {
        /**
         * Half a solar day.
         *
         * The anti-transit is half a solar day from the transit. Fixed at twelve hours because that is
         * the definition of the nadir in solar time; the small residual from the equation of time is
         * what makes this an approximation, and it is documented rather than hidden behind a lookup.
         */
        val SOLAR_MIDNIGHT_OFFSET: Duration = Duration.ofHours(12)
    }
}
