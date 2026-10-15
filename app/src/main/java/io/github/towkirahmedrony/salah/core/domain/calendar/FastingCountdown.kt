package io.github.towkirahmedrony.salah.core.domain.calendar

import io.github.towkirahmedrony.salah.core.model.PrayerSchedule
import io.github.towkirahmedrony.salah.core.model.RamadanWindow
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * Which fasting event a countdown is counting to.
 *
 * There are exactly two, and both are the same values Sehri and Iftar are: [SEHRI_END] is the calculated
 * Fajr and [IFTAR] is the calculated Maghrib. The precautionary Imsak time is deliberately **not** an
 * event here — it is not a deadline, and counting down to it as though it ended the fast would present a
 * precaution as the rule.
 */
enum class FastingEventKind {
    /** Sehri ends — the calculated Fajr of the day. */
    SEHRI_END,

    /** Iftar — the calculated Maghrib of the day. */
    IFTAR,
}

/**
 * The next fasting event and how long remains until it.
 *
 * [eventAt] is in the prayer location's zone, so the countdown is measured against the same instants the
 * timetable shows. [remaining] is never negative: the resolver only ever returns an event still ahead of
 * the [Instant] it was given.
 */
data class FastingCountdown(
    val kind: FastingEventKind,
    val eventAt: ZonedDateTime,
    val remaining: Duration,
)

/**
 * The single answer to "what is the next fasting event, and how long until it?".
 *
 * It is a pure function of the device's current instant, the estimated Ramadan window and the day's
 * already-calculated prayer schedules — no clock of its own, no network and no repository. That is what
 * makes it directly unit-testable with a fixed instant, and what lets the screen recompute it cheaply on
 * a one-second tick without touching the prayer engine or the calendar conversion.
 *
 * ## The rules it encodes
 *
 *  - **Inside Ramadan only.** Outside the window (or when no window could be derived) it returns `null`.
 *    It never shows a fasting countdown for a day the calendar does not place inside Ramadan, so no
 *    countdown is ever presented as though the user were currently fasting when they are not.
 *  - **Before Sehri ends:** the remaining time until the calculated **Fajr**.
 *  - **After Sehri and before Iftar:** the remaining time until the calculated **Maghrib**.
 *  - **After Iftar:** the next day's **Fajr**, but only when the next day is still inside the window. On
 *    the final day of Ramadan the next day is outside it (Shawwal), so it returns `null` rather than a
 *    countdown to a fast that is not there.
 *  - **No schedule, no countdown.** A day with no prayer schedule — or one missing the Fajr or Maghrib
 *    the answer would need — yields `null`, exactly as the timetable shows no time. A plausible-looking
 *    countdown is never invented.
 */
class FastingCountdownResolver {

    /**
     * The next fasting event relative to [now], or `null` when there is none Salah can state.
     *
     * @param today the current local date at the prayer location.
     * @param now the device's current instant.
     * @param window the estimated Ramadan window, or `null` when none is available.
     * @param todaySchedule the calculated schedule for [today], or `null` when there is none.
     * @param tomorrowSchedule the calculated schedule for the day after [today], used only for the
     *   after-Iftar case; `null` simply means that case yields no countdown.
     */
    fun resolve(
        today: LocalDate,
        now: Instant,
        window: RamadanWindow?,
        todaySchedule: PrayerSchedule?,
        tomorrowSchedule: PrayerSchedule?,
    ): FastingCountdown? {
        if (window == null || !window.contains(today)) return null
        if (todaySchedule == null) return null

        val fajr = RamadanCalendar.sehriEndOf(todaySchedule)
        val maghrib = RamadanCalendar.iftarOf(todaySchedule)

        if (fajr != null && now.isBefore(fajr.toInstant())) {
            return countdown(FastingEventKind.SEHRI_END, fajr, now)
        }

        if (maghrib != null && now.isBefore(maghrib.toInstant())) {
            return countdown(FastingEventKind.IFTAR, maghrib, now)
        }

        // Past Iftar, but only when we actually know Iftar happened. If Maghrib is missing, "past Iftar"
        // cannot be established, so no next-day countdown is offered either.
        if (maghrib == null || now.isBefore(maghrib.toInstant())) return null

        val tomorrow = today.plusDays(1)
        if (!window.contains(tomorrow)) return null

        val nextFajr = tomorrowSchedule?.let(RamadanCalendar::sehriEndOf) ?: return null
        if (!now.isBefore(nextFajr.toInstant())) return null

        return countdown(FastingEventKind.SEHRI_END, nextFajr, now)
    }

    private fun countdown(
        kind: FastingEventKind,
        eventAt: ZonedDateTime,
        now: Instant,
    ): FastingCountdown = FastingCountdown(
        kind = kind,
        eventAt = eventAt,
        remaining = Duration.between(now, eventAt.toInstant()),
    )
}
