package io.github.towkirahmedrony.salah.data.calendar

import io.github.towkirahmedrony.salah.core.domain.calendar.BanglaCalendar
import io.github.towkirahmedrony.salah.core.domain.calendar.CalendarFormatting
import io.github.towkirahmedrony.salah.core.domain.calendar.CalendarMonthResolver
import io.github.towkirahmedrony.salah.core.domain.calendar.GregorianCalendarGrid
import io.github.towkirahmedrony.salah.core.domain.calendar.HijriCalendar
import io.github.towkirahmedrony.salah.core.domain.calendar.RamadanCalendar
import io.github.towkirahmedrony.salah.core.domain.location.LocationDayZone
import io.github.towkirahmedrony.salah.core.domain.repository.CalendarRepository
import io.github.towkirahmedrony.salah.core.model.AppLanguage
import io.github.towkirahmedrony.salah.core.model.CalendarDateState
import io.github.towkirahmedrony.salah.core.model.CalendarDay
import io.github.towkirahmedrony.salah.core.model.CalendarMonth
import io.github.towkirahmedrony.salah.core.model.CalendarSettings
import io.github.towkirahmedrony.salah.core.model.CalendarSnapshot
import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import io.github.towkirahmedrony.salah.core.model.RamadanWindow
import io.github.towkirahmedrony.salah.core.model.UnavailabilityReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
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
 * **Zone.** Every date here is a *location-local* day. It is taken in the zone the configured location
 * carries, resolved once by [LocationDayZone] — the same zone the prayer timetable is calculated and
 * displayed in — so the date line above the times and the times themselves cannot name two different
 * civil days. With no location configured the device's own zone answers instead, and in that state the
 * zone is read fresh on every pass rather than captured when the clock was created, so a device
 * time-zone change still takes effect at the next local midnight. Nothing here captures the zone: a
 * location change re-emits through [LocationDayZone.zone] and restarts the wait for the next midnight,
 * so a snapshot is never rendered in a stale zone either.
 *
 * **Refresh.** Both flows re-emit when the device's calendar date actually rolls over, and whenever the
 * stored [CalendarSettings] change, rather than once per collection. The wait is computed to the next
 * local midnight instead of polled, and `atStartOfDay` is used rather than "plus 24 hours" because a
 * local day can be 23 or 25 hours long across a daylight-saving transition.
 *
 * **Which language the snapshot's own text is written in.** [observeToday]'s date lines and weekday
 * name are the only text this class produces, and it is produced in the *interface* language: the
 * stored date-language preference still decides the script (an explicit `BANGLA` or `ENGLISH` wins,
 * because that setting is a statement about the dates themselves), but its `SYSTEM` branch means
 * "follow the interface", not "follow the phone". [interfaceLanguage] is what makes that true, and
 * leaving it at its default keeps the pre-selector behaviour — the device locale — so the domain-level
 * tests pin exactly what they pinned before.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceClockCalendarRepository(
    private val clock: Clock,
    private val settings: Flow<CalendarSettings> = flowOf(CalendarSettings.DEFAULT),
    /**
     * The language Salah's interface is written in, read as a flow so a change to the selector
     * re-renders today's date lines instead of waiting for the next local midnight.
     */
    private val interfaceLanguage: Flow<AppLanguage> = flowOf(AppLanguage.SYSTEM),
    /**
     * The zone a location-local civil day is decided in, or `null` to follow the device's own zone.
     *
     * `null` is the "no location zone supplier" case and is the default so a construction that has no
     * location — which is what the domain-level calendar tests are — keeps the behaviour it pinned
     * before this parameter existed. The application passes the real one (see `SalahModules`); nothing
     * else in the app constructs this class.
     */
    private val dayZone: LocationDayZone? = null,
    private val banglaCalendar: BanglaCalendar = BanglaCalendar(),
    private val hijriCalendar: HijriCalendar = HijriCalendar(),
    private val ramadanCalendar: RamadanCalendar = RamadanCalendar(hijriCalendar),
    private val grid: GregorianCalendarGrid = GregorianCalendarGrid(),
    private val formatting: CalendarFormatting = CalendarFormatting(),
    private val monthResolver: CalendarMonthResolver = CalendarMonthResolver(banglaCalendar, hijriCalendar),
) : CalendarRepository {

    override fun observeToday(): Flow<CalendarSnapshot> =
        combine(midnightTicks(), settings, interfaceLanguage) { instant, calendarSettings, language ->
            snapshotAt(instant, calendarSettings, language)
        }.distinctUntilChanged()

    override fun observeMonth(system: CalendarSystem, anchor: LocalDate): Flow<CalendarMonth> =
        combine(midnightTicks(), settings) { instant, calendarSettings ->
            monthAt(system, anchor, instant, calendarSettings)
        }.distinctUntilChanged()

    override fun observeTodayDate(): Flow<LocalDate> =
        combine(midnightTicks(), settings) { instant, _ -> todayAt(instant) }.distinctUntilChanged()

    override fun observeRamadanWindow(): Flow<RamadanWindow?> =
        combine(midnightTicks(), settings) { instant, calendarSettings ->
            ramadanCalendar.windowFor(todayAt(instant), calendarSettings.hijriAdjustmentDays)
        }.distinctUntilChanged()

    private fun snapshotAt(
        instant: Instant,
        calendarSettings: CalendarSettings,
        language: AppLanguage,
    ): CalendarSnapshot {
        val today = todayAt(instant)
        val locale = formatting.localeFor(calendarSettings.dateLanguage, systemLocale(language))

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
        system: CalendarSystem,
        anchor: LocalDate,
        instant: Instant,
        calendarSettings: CalendarSettings,
    ): CalendarMonth {
        val today = todayAt(instant)
        val locale = formatting.localeFor(calendarSettings.dateLanguage)
        // One window for the whole month, so a day's Ramadan number can never disagree with the range
        // the screen shows beside the grid.
        val window = ramadanCalendar.windowFor(today, calendarSettings.hijriAdjustmentDays)
        // The selected calendar system's month, expressed as the Gregorian days it covers. When the
        // system's month cannot be established (a date outside the verified Bangla range or the
        // platform's Hijri table) the Gregorian month is laid out instead and the screen states the
        // unavailability, so the grid is never empty and no date is invented.
        val span = monthResolver.monthContaining(system, anchor, calendarSettings.hijriAdjustmentDays)
            ?: monthResolver.monthContaining(CalendarSystem.GREGORIAN, anchor)
            ?: return grid.layout(
                yearMonth = YearMonth.from(anchor),
                firstDayOfWeek = WeekFields.of(locale).firstDayOfWeek,
                today = today,
                ramadanWindow = window,
            ) { date -> dayOf(date, today, window, calendarSettings) }

        return grid.layout(
            dates = span.dates,
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
        instant.atZone(zoneNow()).toLocalDate()

    /**
     * The zone the day is decided in right now.
     *
     * Delegates to [LocationDayZone] when one is supplied, so the calendar and the prayer timetable
     * answer this question from one place; otherwise the device's own zone, read fresh here rather than
     * captured, which is the behaviour this class had before a location zone was available to it.
     */
    private fun zoneNow(): ZoneId = dayZone?.current() ?: ZoneId.systemDefault()

    /**
     * The locale the interface is written in, or the device's own when the user is following it.
     *
     * `SYSTEM` resolving to `Locale.getDefault()` rather than to a pinned locale is what keeps "follow
     * the device" meaning what it meant before the selector existed: a phone set to Bengali still gets
     * Bengali dates without the user choosing anything.
     */
    private fun systemLocale(language: AppLanguage): Locale =
        language.locale() ?: Locale.getDefault()

    /**
     * Emits the current instant, then re-emits at each subsequent local midnight.
     *
     * The wait is recomputed in the zone the day is currently decided in, and the ticker is *restarted*
     * when that zone changes (a location change while the screen is open), so a new zone never
     * inherits the previous one's wait. The wait is at least 1 ms so a repository can never spin.
     */
    private fun midnightTicks(): Flow<Instant> = zoneChanges().flatMapLatest {
        flow {
            while (true) {
                val instant = clock.instant()
                emit(instant)
                delay(millisUntilNextLocalMidnight(instant))
            }
        }
    }

    /**
     * The zone the day is decided in now, then every zone it changes to.
     *
     * `onStart` supplies the first emission so the ticker begins immediately, whether or not the
     * location has been read yet; `distinctUntilChanged` collapses the seed and the zone source's own
     * first value when they agree, and drops a location change that does not move the zone.
     */
    private fun zoneChanges(): Flow<ZoneId> =
        (dayZone?.zone ?: emptyFlow<ZoneId>())
            .onStart { emit(zoneNow()) }
            .distinctUntilChanged()

    /**
     * Whole milliseconds until the next local midnight in the current day zone, at least 1.
     *
     * `atStartOfDay` is used rather than adding a day to the current time because it is the only
     * form that is correct across a daylight-saving transition, where a local day can be 23 or
     * 25 hours long.
     */
    private fun millisUntilNextLocalMidnight(instant: Instant): Long {
        val zone = zoneNow()
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
