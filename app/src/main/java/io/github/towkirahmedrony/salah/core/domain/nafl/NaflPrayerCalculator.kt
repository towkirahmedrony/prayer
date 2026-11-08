package io.github.towkirahmedrony.salah.core.domain.nafl

import io.github.towkirahmedrony.salah.core.domain.prohibited.ProhibitedWindowKind
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerSchedule
import io.github.towkirahmedrony.salah.core.model.ProhibitedPeriod
import io.github.towkirahmedrony.salah.core.model.SolarEvent
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

/** The voluntary (nafl) prayers whose time Salah states. None of them is one of the five. */
enum class NaflKind {
    /** The forenoon prayer, from the end of the sunrise prohibition to the start of the zenith one. */
    DUHA,

    /** The prayer after Maghrib, from Maghrib to Isha (a Hanafi tradition; see docs/NAFL_PRAYERS.md). */
    AWWABIN,

    /** The night prayer, from the start of the last third of the night to Fajr. */
    TAHAJJUD,
}

/** One nafl period, half-open `[startsAt, endsAt)` like every other period in Salah. */
data class NaflPeriod(
    val kind: NaflKind,
    val startsAt: ZonedDateTime,
    val endsAt: ZonedDateTime,
) {
    init {
        require(endsAt.isAfter(startsAt)) { "A nafl period must end after it starts." }
    }

    val duration: Duration get() = Duration.between(startsAt, endsAt)

    /** True when [moment] is inside the period; the exact start is inside, the exact end is not. */
    fun contains(moment: ZonedDateTime): Boolean = !moment.isBefore(startsAt) && moment.isBefore(endsAt)
}

/**
 * Today's nafl picture. A `null` period means it cannot be stated honestly (a missing anchor or a
 * period that would be empty), never that it was guessed.
 *
 * @param duhaPreferredFrom the start of the preferred part of Duha, a quarter of the day after
 *   sunrise. It is always inside [duha].
 * @param lastThirdStartsAt the start of the last third of the night, as the night calculator gave it.
 */
data class NaflDay(
    val duha: NaflPeriod?,
    val duhaPreferredFrom: ZonedDateTime?,
    val awwabin: NaflPeriod?,
    val tahajjud: NaflPeriod?,
    val lastThirdStartsAt: ZonedDateTime?,
) {
    companion object {
        val Empty = NaflDay(null, null, null, null, null)
    }
}

/**
 * What the hero ring shows when no fard period explains the moment, or when a prohibited window
 * overlaps one. Both carry the time left so the ring needs no arithmetic of its own.
 */
sealed interface NaflMoment {
    val remaining: Duration

    /** A prohibited window is open; [remaining] is the time until it closes. */
    data class Prohibited(
        val period: ProhibitedPeriod,
        override val remaining: Duration,
    ) : NaflMoment

    /** Duha is open; [remaining] is the time until it closes. */
    data class DuhaOpen(
        val period: NaflPeriod,
        override val remaining: Duration,
    ) : NaflMoment
}

/**
 * Derives the nafl periods from what Salah already calculated, and does no astronomy of its own.
 *
 *  - Duha opens exactly when the sunrise prohibition closes and closes exactly when the zenith
 *    prohibition opens (or at Dhuhr, if Dhuhr is earlier), so Duha and a prohibited window can never
 *    overlap;
 *  - Awwabin is the displayed Maghrib to the displayed Isha;
 *  - Tahajjud is the night calculator's start to the following Fajr, both handed in by the caller.
 *
 * Both entry points are pure functions of their arguments; neither reads a clock.
 */
class NaflPrayerCalculator {

    fun calculate(
        schedule: PrayerSchedule?,
        prohibited: List<ProhibitedPeriod>,
        tahajjudStartsAt: ZonedDateTime?,
        lastThirdStartsAt: ZonedDateTime?,
        fajrEndsAt: ZonedDateTime?,
    ): NaflDay {
        if (schedule == null) return NaflDay.Empty
        val zone = schedule.zoneId
        val duha = duhaOf(schedule, prohibited, zone)
        return NaflDay(
            duha = duha,
            duhaPreferredFrom = duha?.let { preferredStartOf(schedule, it, zone) },
            awwabin = awwabinOf(schedule, zone),
            tahajjud = if (tahajjudStartsAt != null && fajrEndsAt != null) {
                period(NaflKind.TAHAJJUD, tahajjudStartsAt, fajrEndsAt, zone)
            } else {
                null
            },
            lastThirdStartsAt = lastThirdStartsAt?.withZoneSameInstant(zone),
        )
    }

    /**
     * The non-fard reading of [now]: an open prohibited window first, otherwise open Duha, otherwise
     * `null`. A prohibited window wins over Duha even if a caller supplied windows that overlap it,
     * because showing "pray" during a prohibition is the worse mistake. Windows are half-open.
     */
    fun momentAt(day: NaflDay, prohibited: List<ProhibitedPeriod>, now: ZonedDateTime): NaflMoment? {
        val active = prohibited
            .filter { window -> !now.isBefore(window.startsAt) && now.isBefore(window.endsAt) }
            .maxByOrNull { window -> window.endsAt }
        if (active != null) {
            return NaflMoment.Prohibited(
                period = active,
                remaining = Duration.between(now, active.endsAt),
            )
        }
        val duha = day.duha
        if (duha != null && duha.contains(now)) {
            return NaflMoment.DuhaOpen(period = duha, remaining = Duration.between(now, duha.endsAt))
        }
        return null
    }

    private fun duhaOf(
        schedule: PrayerSchedule,
        prohibited: List<ProhibitedPeriod>,
        zone: ZoneId,
    ): NaflPeriod? {
        val sunrise = prohibited.firstOrNull { it.kind == ProhibitedWindowKind.SUNRISE_ASCENT } ?: return null
        val zenith = prohibited.firstOrNull { it.kind == ProhibitedWindowKind.ZENITH_DECLINE } ?: return null
        val dhuhr = schedule.timeOf(PrayerName.DHUHR)
        val end = if (dhuhr != null && dhuhr.isBefore(zenith.startsAt)) dhuhr else zenith.startsAt
        return period(NaflKind.DUHA, sunrise.endsAt, end, zone)
    }

    private fun preferredStartOf(schedule: PrayerSchedule, duha: NaflPeriod, zone: ZoneId): ZonedDateTime? {
        val sunrise = schedule.timeOf(SolarEvent.SUNRISE) ?: return null
        val sunset = schedule.timeOf(SolarEvent.SUNSET) ?: return null
        if (!sunset.isAfter(sunrise)) return null
        val preferred = sunrise.plus(Duration.between(sunrise, sunset).dividedBy(QUARTERS))
        return when {
            preferred.isBefore(duha.startsAt) -> duha.startsAt
            preferred.isBefore(duha.endsAt) -> preferred.withZoneSameInstant(zone)
            else -> null
        }
    }

    private fun awwabinOf(schedule: PrayerSchedule, zone: ZoneId): NaflPeriod? {
        val maghrib = schedule.timeOf(PrayerName.MAGHRIB) ?: return null
        val isha = schedule.timeOf(PrayerName.ISHA) ?: return null
        return period(NaflKind.AWWABIN, maghrib, isha, zone)
    }

    private fun period(kind: NaflKind, start: ZonedDateTime, end: ZonedDateTime, zone: ZoneId): NaflPeriod? =
        if (end.isAfter(start)) {
            NaflPeriod(kind, start.withZoneSameInstant(zone), end.withZoneSameInstant(zone))
        } else {
            null
        }

    private companion object {
        const val QUARTERS = 4L
    }
}
