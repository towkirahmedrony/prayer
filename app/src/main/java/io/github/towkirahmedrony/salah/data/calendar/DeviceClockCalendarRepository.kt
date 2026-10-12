package io.github.towkirahmedrony.salah.data.calendar

import io.github.towkirahmedrony.salah.core.domain.calendar.BanglaCalendar
import io.github.towkirahmedrony.salah.core.domain.calendar.CalendarFormatting
import io.github.towkirahmedrony.salah.core.domain.calendar.GregorianCalendarGrid
import io.github.towkirahmedrony.salah.core.domain.calendar.HijriCalendar
import io.github.towkirahmedrony.salah.core.domain.calendar.RamadanCalendar
import io.github.towkirahmedrony.salah.core.domain.repository.CalendarRepository
import io.github.towkirahmedrony.salah.core.model.CalendarDateState
import io.github.towkirahmedrony.salah.core.model.CalendarDay
import io.github.towkirahmedrony.salah.core.model.CalendarMonth
import io.github.towkirahmedrony.salah.core.model.CalendarSettings
import io.github.towkirahmedrony.salah.core.model.CalendarSnapshot
import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import io.github.towkirahmedrony.salah.core.model.RamadanWindow
import io.github.towkirahmedrony.salah.core.model.UnavailabilityReason
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Calendar repository backed by the device clock and the local converters.
 *
 * Every date here is real. The Gregorian date comes from the device clock, the Bangla date from
 * [BanglaCalendar] under the verified Bangladesh rules, and the Hijri date from [HijriCalendar]'s
 * Umm al-Qura table. Nothing is a placeholder and nothing is fabricated: a date outside a converter's
 * supported range is reported as [UnavailabilityReason.OUT_OF_SUPPORTED_RANGE] rather than approximated.
 *
 * **Zone.** The day is taken in the device's current zone, read fresh on every pass rather than
 * captured when the clock was created. A time-zone change on the device therefore takes effect at the
 * next local midnight, and a snapshot is never rendered in a stale zone. The device zone — not the
 * prayer location's zone — is the right choice for a *calendar*: a calendar is a civil artifact of
 * where the user is, while prayer times are an astronomical property of where they pray.
 *
 * **Refresh.** Both flows re-emit when the device's calendar date actually rolls over, and whenever the
 * stored [CalendarSettings] change, rather than once per collection. The wait is computed to the next
 * local midnight instead of polled, and `atStartOfDay` is used rather than "plus 24 hours" because a
 * local day can be 23 or 25 hours long across a daylight-saving transition.
 */
class DeviceClockCalendarRepository(
    private val clock: Clock,
    private val settings: Flow<CalendarSettings> = flowOf(CalendarSettings.DEFAULT),
    private val banglaCalendar: BanglaCalendar = BanglaCalendar(),
    private val hijriCalendar: HijriCalendar = HijriCalendar(),
    private val ramadanCalendar: RamadanCalendar = RamadanCalendar(hijriCalendar),
    private val grid: GregorianCalendarGrid = GregorianCalendarGrid(),
    private val formatting: CalendarFormatting = CalendarFormatting(),
) : CalendarRepository {

    override fun observeToday(): Flow<CalendarSnapshot> =
        combine(midnightTicks(), settings) { instant, calendarSettings ->
            snapshotAt(instant, calendarSettings)
        }.distinctUntilChanged()

    override fun observeMonth(month: YearMonth): Flow<CalendarMonth> =
        combine(midnightTicks(), settings) { instant, calendarSettings ->
            monthAt(month, instant, calendarSettings)
        }.distinctUntilChanged()

    override fun observeTodayDate(): Flow<LocalDate> =
        combine(midnightTicks(), settings) { instant, _ -> todayAt(instant) }.distinctUntilChanged()

    override fun observeRamadanWindow(): Flow<RamadanWindow?> =
        combine(midnightTicks(), settings) { instant, calendarSettings ->
            ramadanCalendar.windowFor(todayAt(instant), calendarSettings.hijriAdjustmentDays)
        }.distinctUntilChanged()

    private fun snapshotAt(instant: Instant, calendarSettings: CalendarSettings): CalendarSnapshot {
        val today = todayAt(instant)
        val locale = formatting.localeFor(calendarSettings.dateLanguage)

        return CalendarSnapshot(
            gregorian = CalendarDateState.Available(
                system = CalendarSystem.GREGORIAN,
                formattedDate = formatting.gregorian(today, locale),
                isVerifiedSource = true,
            ),
            bangla = banglaDateState(today, locale),
            hijri = hijriDateState(today, calendarSettings, locale),
            weekdayLabel = formatting.weekday(today, locale),
        )
    }

    private fun monthAt(
        month: YearMonth,
        instant: Instant,
        calendarSettings: CalendarSettings,
    ): CalendarMonth {
        val today = todayAt(instant)
        val locale = formatting.localeFor(calendarSettings.dateLanguage)
        // One window for the whole month, so a day's Ramadan number can never disagree with the range
        // the screen shows beside the grid.
        val window = ramadanCalendar.windowFor(today, calendarSettings.hijriAdjustmentDays)

        return grid.layout(
            yearMonth = month,
            firstDayOfWeek = WeekFields.of(locale).firstDayOfWeek,
            today = today,
            ramadanWindow = window,
        ) { date -> dayOf(date, today, window, calendarSettings) }
    }

    private fun dayOf(
        date: LocalDate,
        today: LocalDate,
        window: RamadanWindow?,
        calendarSettings: CalendarSettings,
    ): CalendarDay {
        val hijri = hijriCalendar.dateOf(date, calendarSettings.hijriAdjustmentDays)
        return CalendarDay(
            date = date,
            isToday = date == today,
            bangla = banglaCalendar.convert(date),
            hijri = hijri,
            occasions = hijri?.let(hijriCalendar::occasionsOn).orEmpty(),
            ramadanDayNumber = window?.dayNumber(date),
        )
    }

    private fun banglaDateState(date: LocalDate, locale: Locale): CalendarDateState {
        val converted = banglaCalendar.convert(date) ?: return CalendarDateState.NotAvailable(
            system = CalendarSystem.BANGLA,
            reason = UnavailabilityReason.OUT_OF_SUPPORTED_RANGE,
            detail = BANGLA_RANGE_DETAIL,
        )
        return CalendarDateState.Available(
            system = CalendarSystem.BANGLA,
            formattedDate = formatting.bangla(converted, locale),
            // The Bangladesh rules are a published civil definition, not an estimate, so nothing here
            // is labelled as uncertain. The supported range is still reported when it is exceeded.
            isVerifiedSource = true,
        )
    }

    private fun hijriDateState(
        date: LocalDate,
        calendarSettings: CalendarSettings,
        locale: Locale,
    ): CalendarDateState {
        val converted = hijriCalendar.dateOf(date, calendarSettings.hijriAdjustmentDays)
            ?: return CalendarDateState.NotAvailable(
                system = CalendarSystem.HIJRI,
                reason = UnavailabilityReason.OUT_OF_SUPPORTED_RANGE,
                detail = HIJRI_RANGE_DETAIL,
            )
        return CalendarDateState.Available(
            system = CalendarSystem.HIJRI,
            formattedDate = formatting.hijriWithEra(converted, locale),
            // Never true: this is an Umm al-Qura estimate, not a moon-sighting announcement. See
            // docs/CALENDAR_SYSTEM.md §5.
            isVerifiedSource = false,
        )
    }

    private fun todayAt(instant: Instant): LocalDate =
        instant.atZone(ZoneId.systemDefault()).toLocalDate()

    /**
     * Emits the current instant, then re-emits at each subsequent local midnight.
     *
     * The zone is read on every pass so a time-zone change is picked up on the next boundary, and the
     * wait is at least 1 ms so a repository can never spin.
     */
    private fun midnightTicks(): Flow<Instant> = flow {
        while (true) {
            val instant = clock.instant()
            emit(instant)
            delay(millisUntilNextLocalMidnight(instant))
        }
    }

    /**
     * Whole milliseconds until the next local midnight, at least 1.
     *
     * `atStartOfDay` is used rather than adding a day to the current time because it is the only
     * form that is correct across a daylight-saving transition, where a local day can be 23 or
     * 25 hours long.
     */
    private fun millisUntilNextLocalMidnight(instant: Instant): Long {
        val zone = ZoneId.systemDefault()
        val midnight = instant.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
        return Duration.between(instant, midnight).toMillis().coerceAtLeast(1L)
    }

    private companion object {
        const val BANGLA_RANGE_DETAIL =
            "The Bangladesh Bangla calendar is implemented for dates from 14 April 2019 onwards, " +
                "the first New Year under the 2019 Bangla Academy rules."

        const val HIJRI_RANGE_DETAIL =
            "The Hijri calendar on this device covers roughly AH 1300 to AH 1600 " +
                "(about 1882 to 2174 CE), so no Hijri date is calculated outside it."
    }
}
