package io.github.towkirahmedrony.salah.core.domain.repository

import io.github.towkirahmedrony.salah.core.model.CalendarMonth
import io.github.towkirahmedrony.salah.core.model.CalendarSnapshot
import io.github.towkirahmedrony.salah.core.model.RamadanWindow
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.YearMonth

/**
 * Read access to the calendar representations shown in Salah.
 *
 * Four calendars are presented over one Gregorian grid — Gregorian, Bangladesh Bangla and Hijri dates
 * on every day, plus the Ramadan day number — and every value is produced by a pure converter in
 * [io.github.towkirahmedrony.salah.core.domain.calendar]. The repository's job is only to decide
 * *which* Gregorian day is meant: the device clock supplies today, the configured zone places the
 * instant on a day, and the stored Hijri offset is applied before the Hijri value is read.
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
     * One Gregorian month, laid out as weeks, with each day's Bangla and Hijri dates already resolved.
     *
     * @param month the month to lay out. Any month is a valid request; a date outside a converter's
     *   supported range carries a `null` for that representation instead of a fabricated one, so the
     *   screen can explain the boundary rather than hide the month.
     */
    fun observeMonth(month: YearMonth): Flow<CalendarMonth>

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
