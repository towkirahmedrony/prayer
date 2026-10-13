package io.github.towkirahmedrony.salah.core.domain.repository

import io.github.towkirahmedrony.salah.core.model.CalendarMonth
import io.github.towkirahmedrony.salah.core.model.CalendarSnapshot
import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import io.github.towkirahmedrony.salah.core.model.RamadanWindow
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * Read access to the calendar representations shown in Salah.
 *
 * Three calendars are presented, and the grid the user reads is the month of whichever one is
 * selected: Gregorian, Bangladesh Bangla or Hijri. Every day also carries the other two
 * representations and the Ramadan day number, and every value is produced by a pure converter in
 * [io.github.towkirahmedrony.salah.core.domain.calendar]. The repository's job is only to decide
 * *which* Gregorian days are meant: the device clock supplies today, the configured zone places the
 * instant on a day, the stored Hijri offset is applied before the Hijri value is read, and
 * [io.github.towkirahmedrony.salah.core.domain.calendar.CalendarMonthResolver] decides which Gregorian
 * days the selected calendar system's month covers.
 *
 * **Which day "today" is.** The instant comes from the device clock, and the zone that places it on a
 * day is the *configured location's* zone — resolved once by
 * [io.github.towkirahmedrony.salah.core.domain.location.LocationDayZone] — falling back to the device's
 * own zone only when no location is configured. That is deliberate: the calendar day and the prayer
 * timetable are read together on one screen, and the timetable is calculated for the location, so
 * deriving the two in different zones would let them name different civil days. See
 * `docs/CALCULATION_CORRECTNESS_REVIEW.md` §3.
 *
 * Nothing here formats text for a layout and nothing here is a UI type; the values are domain types,
 * so the conversion stays independently testable.
 *
 * Neither method performs a network call. Both are answered from the device clock and local
 * conversion, so the calendar works offline and with no account.
 */
interface CalendarRepository {

    /**
     * Today's date in every supported representation.
     *
     * Re-emitted when the device's local date rolls over and whenever the stored calendar settings
     * change, so a screen left open overnight — or one where the user just moved the Hijri offset —
     * does not keep showing a stale value.
     */
    fun observeToday(): Flow<CalendarSnapshot>

    /**
     * One month of [system] that contains [anchor], laid out as weeks, with each day's Bangla and
     * Hijri dates already resolved.
     *
     * The month is the *selected calendar system's* month, not necessarily a Gregorian one: an anchor
     * of 20 March 2024 with [CalendarSystem.HIJRI] yields the Gregorian days of Ramadan 1445, and with
     * [CalendarSystem.BANGLA] the days of Chaitra 1430. That is what makes a calendar-mode switch
     * change the grid rather than only its heading.
     *
     * @param anchor any Gregorian day inside the month to lay out — usually today, the selected day, or
     *   the first day of the next/previous month the user navigated to.
     * @return a month whose `weeks` are the Gregorian days of the selected system's month. A day
     *   outside a converter's supported range carries a `null` for that representation instead of a
     *   fabricated one, so the screen can explain the boundary rather than hide the month; if the
     *   selected system's month cannot be established at all, the Gregorian month of [anchor] is laid
     *   out and the screen states the unavailability.
     */
    fun observeMonth(system: CalendarSystem, anchor: LocalDate): Flow<CalendarMonth>

    /**
     * Today as a date, re-emitted at each local midnight.
     *
     * The same value [observeToday] embeds, exposed on its own so a consumer that needs the date rather
     * than its renderings — the Ramadan screen, to know which day to mark — does not have to re-derive
     * "which day is it" from the device clock and duplicate the midnight logic.
     */
    fun observeTodayDate(): Flow<LocalDate>

    /**
     * The estimated Ramadan window containing today, or the next one after it.
     *
     * `null` when the platform's Hijri table does not cover either window, which is the honest "no
     * reliable data" case the Ramadan screen reports instead of inventing dates. Depends on the stored
     * Hijri offset, so moving the offset moves the window.
     */
    fun observeRamadanWindow(): Flow<RamadanWindow?>
}
