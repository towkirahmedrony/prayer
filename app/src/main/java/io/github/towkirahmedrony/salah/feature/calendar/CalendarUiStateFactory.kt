package io.github.towkirahmedrony.salah.feature.calendar

import io.github.towkirahmedrony.salah.core.domain.calendar.CalendarFormatting
import io.github.towkirahmedrony.salah.core.domain.calendar.GregorianCalendarGrid
import io.github.towkirahmedrony.salah.core.domain.location.LocationDisplayName
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleState
import io.github.towkirahmedrony.salah.core.domain.timeFormatterFor
import io.github.towkirahmedrony.salah.core.model.BanglaDate
import io.github.towkirahmedrony.salah.core.model.CalendarDay
import io.github.towkirahmedrony.salah.core.model.CalendarMonth
import io.github.towkirahmedrony.salah.core.model.CalendarSettings
import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import io.github.towkirahmedrony.salah.core.model.HijriDate
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerSettings
import io.github.towkirahmedrony.salah.core.model.RamadanPhase
import io.github.towkirahmedrony.salah.core.model.UnavailabilityReason
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Pure mapping from the repository's calendar month to [CalendarUiState].
 *
 * Android-free and clock-free, which is what lets the interesting rules be unit-tested directly:
 * which cell is today, which is selected, which are both, how the three date representations appear
 * side by side, and — the rule that matters most — that a date a converter could not produce appears
 * as a stated unavailability rather than as a plausible-looking value.
 *
 * **The grid follows the selected calendar system.** The repository hands in a [CalendarMonth] whose
 * days are the selected system's month (for example Ramadan 1445 when Hijri is selected), and this
 * class renders it as that system: the heading, the day-cell numbers and their numerals all come from
 * [CalendarSystem]. That is the fix for the mode selector having no visible effect — the month, the
 * day numbers and the year now change with the mode, not only which chip is highlighted.
 *
 * **Two locales, deliberately.** The grid and its heading use [CalendarFormatting.gridLocale], which
 * renders the Gregorian and Hijri grids in the interface language (or the stored date language) and
 * pins Bengali only for the Bangla mode — so the Hijri grid is `Ramadan 1445` in English and
 * `রমজান ১৪৪৫` in Bengali rather than always Arabic. The selected-day date *list* shows all three
 * calendars at once, so it uses one script chosen by the stored date language
 * ([CalendarFormatting.localeFor]). See docs/CALENDAR_IMPLEMENTATION.md.
 *
 * [systemLocale] is a parameter rather than a `Locale.getDefault()` call inside the method so a test
 * can pin it, and so the one place that decides between the stored date-language preference and the
 * interface's language is [CalendarFormatting.localeFor].
 *
 * **The interface language is [systemLocale].** The calendar view model passes the selected
 * `AppLanguage`'s locale here, so with Bengali selected and the stored date language left on "follow",
 * a Gregorian grid reads `অক্টোবর ২০২৬` and its weekday headers and day numbers are Bengali too —
 * while the Gregorian days themselves, the selection and the Ramadan window are untouched. Selecting
 * English passes `en` and the same month renders in Latin script. Nothing about the *data* changes: the
 * month is still built by the repository from the same Gregorian anchor.
 */
class CalendarUiStateFactory(
    private val formatting: CalendarFormatting = CalendarFormatting(),
) {

    fun create(
        month: CalendarMonth,
        settings: CalendarSettings,
        selected: LocalDate,
        systemLocale: Locale = Locale.getDefault(),
        /**
         * The prayer schedule for [selected] at the configured location, or its absence.
         *
         * Defaulted to [PrayerScheduleState.Loading] so a caller that has no prayer source — the
         * factory's own unit tests — gets a screen with no times rather than a fabricated timetable.
         */
        prayerSchedule: PrayerScheduleState = PrayerScheduleState.Loading,
        /** The prayer settings the times were calculated with, for the clock-format preference. */
        prayerSettings: PrayerSettings = PrayerSettings.DEFAULT,
        /** The saved location the prayer times belong to, or `null` when none is configured. */
        location: LocationInfo? = null,
    ): CalendarUiState {
        val days = month.days
        if (days.isEmpty()) {
            return CalendarUiState.Failed(detail = "This month has no days to lay out.")
        }

        val system = settings.preferredSystem
        val locale = formatting.localeFor(settings.dateLanguage, systemLocale)
        val gridLocale = formatting.gridLocale(system, settings.dateLanguage, systemLocale)
        val today = month.today
        // The view model clamps the selection into the displayed month, so this fallback is unreachable
        // in the app; it exists so a mismatch degrades to showing a real day rather than crashing.
        val selectedDay = days.firstOrNull { it.date == selected } ?: days.first()
        val todayDay = days.firstOrNull { it.date == today } ?: selectedDay

        val spans = month.hijriAndBanglaSpans(locale)

        return CalendarUiState.Ready(
            system = system,
            month = month.yearMonth,
            monthLabel = monthLabel(month, system, gridLocale, locale),
            hijriSpanLabel = spans.first,
            banglaSpanLabel = spans.second,
            weekdayLabels = weekdayLabels(month, gridLocale),
            weeks = month.weeks.map { week ->
                week.map { cell -> cellOf(cell, selectedDay.date, month, system, gridLocale) }
            },
            selected = selectedDay.toSelectedDay(locale),
            today = todayDay.toSelectedDay(locale),
            selectedIsToday = selectedDay.date == today,
            selectedOutOfRange = selectedDay.outOfRangeFor(system),
            ramadan = month.toRamadanSection(today, locale),
            prayerTimes = prayerSection(prayerSchedule, prayerSettings, location, locale),
            hijriAdjustmentDays = settings.hijriAdjustmentDays,
            dateLanguage = settings.dateLanguage,
            monthStart = days.first().date,
            monthEnd = days.last().date,
        )
    }

    /**
     * The month heading in the selected calendar system — `October 2026`, `আশ্বিন ১৪৩১` or the Hijri
     * month (`Ramadan 1445` / `রমজান ১৪৪৫`) — rendered in [gridLocale], which is the interface language
     * unless the stored date language overrides it.
     *
     * Derived from the month's own first convertible day, so the heading is the system's month and
     * year, not the Gregorian month the days happen to fall in. When the system's month is not
     * available (a date outside the verified range) it falls back to the Gregorian month label; the
     * selected-day card then carries the out-of-range note.
     */
    private fun monthLabel(
        month: CalendarMonth,
        system: CalendarSystem,
        gridLocale: Locale,
        fallbackLocale: Locale,
    ): String {
        val gregorian = formatting.gregorianMonth(month.yearMonth.atDay(1), fallbackLocale)
        return when (system) {
            CalendarSystem.GREGORIAN -> formatting.gregorianMonth(month.yearMonth.atDay(1), gridLocale)
            CalendarSystem.BANGLA -> month.days.firstNotNullOfOrNull { it.bangla }
                ?.let { formatting.banglaMonth(it, gridLocale) }
                ?: gregorian

            CalendarSystem.HIJRI -> month.days.firstNotNullOfOrNull { it.hijri }
                ?.let { formatting.hijriMonth(it, gridLocale) }
                ?: gregorian
        }
    }

    /**
     * The column headers, in the order the grid lays its columns out.
     *
     * Built from [CalendarMonth.firstDayOfWeek] — the same value the grid used for its leading
     * padding — so a header can never sit above the wrong column. Deriving the header from the first
     * *real* day instead would shift every label by however many blank cells precede it.
     */
    private fun weekdayLabels(month: CalendarMonth, locale: Locale): List<String> =
        (0 until GregorianCalendarGrid.DAYS_PER_WEEK).map { offset ->
            formatting.weekdayShortLabel(month.firstDayOfWeek.plus(offset.toLong()), locale)
        }

    /**
     * Builds the state's single Ramadan summary.
     *
     * The range is shown with its source wording, so an estimated range can never be read as an
     * announced one; and when no range could be derived the label is `null` and the screen shows
     * nothing, rather than a guessed date.
     */
    private fun CalendarMonth.toRamadanSection(today: LocalDate, locale: Locale): RamadanSection {
        val window = ramadanWindow
        if (window == null) {
            return RamadanSection(
                phase = RamadanPhase.FAR_AWAY,
                todayDayNumber = null,
                rangeLabel = null,
                hijriYear = null,
                source = null,
            )
        }
        return RamadanSection(
            phase = window.phase(today),
            todayDayNumber = window.dayNumber(today),
            rangeLabel = "${formatting.gregorian(window.start, locale)} – " +
                formatting.gregorian(window.end, locale),
            hijriYear = window.hijriYear,
            source = window.source,
        )
    }

    private fun CalendarDay.toSelectedDay(locale: Locale): SelectedDay = SelectedDay(
        date = date,
        // Jumu'ah is the week's own name for a Friday, not a calendar observance Salah derives.
        isJumuah = date.dayOfWeek == DayOfWeek.FRIDAY,
        weekday = formatting.weekday(date, locale),
        lines = listOf(
            CalendarDateLine(
                system = CalendarSystem.GREGORIAN,
                value = formatting.gregorian(date, locale),
                unavailable = null,
                source = null,
            ),
            CalendarDateLine(
                system = CalendarSystem.BANGLA,
                value = bangla?.let { formatting.bangla(it, locale) },
                unavailable = if (bangla == null) UnavailabilityReason.OUT_OF_SUPPORTED_RANGE else null,
                source = null,
            ),
            CalendarDateLine(
                system = CalendarSystem.HIJRI,
                value = hijri?.let { formatting.hijriWithEra(it, locale) },
                unavailable = if (hijri == null) UnavailabilityReason.OUT_OF_SUPPORTED_RANGE else null,
                source = hijri?.source,
            ),
        ),
        occasions = occasions,
        ramadanDayNumber = ramadanDayNumber,
    )

    /** The reason the selected day cannot be shown in [system], or `null` when it can. */
    private fun CalendarDay.outOfRangeFor(system: CalendarSystem): UnavailabilityReason? = when (system) {
        CalendarSystem.GREGORIAN -> null
        CalendarSystem.BANGLA -> if (bangla == null) UnavailabilityReason.OUT_OF_SUPPORTED_RANGE else null
        CalendarSystem.HIJRI -> if (hijri == null) UnavailabilityReason.OUT_OF_SUPPORTED_RANGE else null
    }

    private fun cellOf(
        cell: CalendarDay?,
        selected: LocalDate,
        month: CalendarMonth,
        system: CalendarSystem,
        gridLocale: Locale,
    ): CalendarCell {
        if (cell == null) {
            return CalendarCell(
                date = null,
                dayOfMonth = null,
                dayLabel = null,
                stage = CalendarCellStage.PADDING,
                isRamadanDay = false,
                occasion = null,
            )
        }
        val isToday = cell.date == month.today
        val isSelected = cell.date == selected
        // The day number is the one the *selected system* uses, written in the interface language's
        // numerals: under the Hijri mode that is the Hijri day of month, so the same cell reads `14` in
        // English and `১৪` in Bengali. A day the system cannot convert falls back to its Gregorian day
        // number, which keeps the grid complete while the card states why the conversion is missing.
        val dayNumber = dayNumberIn(cell, system) ?: cell.date.dayOfMonth
        return CalendarCell(
            date = cell.date,
            dayOfMonth = dayNumber,
            dayLabel = formatting.numerals(dayNumber, gridLocale),
            stage = when {
                isToday && isSelected -> CalendarCellStage.TODAY_SELECTED
                isSelected -> CalendarCellStage.SELECTED
                isToday -> CalendarCellStage.TODAY
                else -> CalendarCellStage.REGULAR
            },
            isRamadanDay = cell.ramadanDayNumber != null,
            occasion = cell.occasions.firstOrNull(),
            isHijriMonthStart = cell.hijri?.day == 1,
        )
    }

    /**
     * The Hijri and Bangla months the displayed month covers, as two ready-to-render labels.
     *
     * A Gregorian or Bangla month rarely lines up with the others, so each span is taken from the
     * month's own first and last convertible days: when they fall in the same month the label names it
     * once with its year, and when the month turns over inside the displayed span both months are named
     * — with a year each only when the turn-over also crosses a year boundary.
     *
     * The Bangla half is always written in Bengali script and Bengali numerals, which is how the
     * Bangladeshi civil calendar is named; the Hijri half follows the interface language, like every
     * other Hijri label. `null` means the platform's tables do not cover the month, so nothing is shown
     * rather than a guessed span.
     */
    private fun CalendarMonth.hijriAndBanglaSpans(locale: Locale): Pair<String?, String?> {
        val monthDays = days
        val hijriDays = monthDays.mapNotNull { it.hijri }
        val banglaDays = monthDays.mapNotNull { it.bangla }
        val hijriSpan = hijriDays.lastOrNull()?.let { last ->
            hijriSpanLabel(hijriDays.first(), last, locale)
        }
        val banglaSpan = banglaDays.lastOrNull()?.let { last ->
            banglaSpanLabel(banglaDays.first(), last)
        }
        return hijriSpan to banglaSpan
    }

    private fun hijriSpanLabel(first: HijriDate, last: HijriDate, locale: Locale): String =
        spanLabel(
            firstName = formatting.hijriMonthName(first.month, locale),
            firstYear = first.year,
            lastName = formatting.hijriMonthName(last.month, locale),
            lastYear = last.year,
            number = { year -> formatting.numerals(year, locale) },
        )

    private fun banglaSpanLabel(first: BanglaDate, last: BanglaDate): String {
        val bengali = Locale(CalendarFormatting.BANGLA_LANGUAGE)
        return spanLabel(
            firstName = formatting.banglaMonthName(first.month, bengali),
            firstYear = first.year,
            lastName = formatting.banglaMonthName(last.month, bengali),
            lastYear = last.year,
            number = { year -> formatting.numerals(year, bengali) },
        )
    }

    /** `Jumada al-Awwal – Jumada al-Thani 1448`, or the two ended forms when a year turns over too. */
    private fun spanLabel(
        firstName: String,
        firstYear: Int,
        lastName: String,
        lastYear: Int,
        number: (Int) -> String,
    ): String = when {
        firstName == lastName && firstYear == lastYear -> "$firstName ${number(firstYear)}"
        firstYear == lastYear -> "$firstName \u2013 $lastName ${number(firstYear)}"
        else -> "$firstName ${number(firstYear)} \u2013 $lastName ${number(lastYear)}"
    }

    /**
     * The five prayers for the selected day, in the location's zone and the user's clock format.
     *
     * Derived from the same [PrayerScheduleState] the Home screen and the reminders use, so the calendar
     * can never show a different time from the timetable it sits beside. A schedule that is absent or
     * failed carries a reason into the section, so the card explains the gap instead of leaving five
     * dashes.
     */
    private fun prayerSection(
        scheduleState: PrayerScheduleState,
        settings: PrayerSettings,
        location: LocationInfo?,
        locale: Locale,
    ): PrayerTimesSection {
        val schedule = (scheduleState as? PrayerScheduleState.Available)?.schedule
        val zone = schedule?.zoneId ?: location?.zoneId ?: ZoneId.systemDefault()
        val formatter = timeFormatterFor(zone = zone, locale = locale, preference = settings.timeFormat)
        return PrayerTimesSection(
            locationLabel = location?.let { stored -> LocationDisplayName.of(stored) },
            rows = PrayerName.entries.map { prayer ->
                PrayerTimeRow(prayer = prayer, timeLabel = schedule?.timeOf(prayer)?.let(formatter::format))
            },
            unavailable = when (scheduleState) {
                is PrayerScheduleState.Unavailable -> scheduleState.reason
                is PrayerScheduleState.Failed -> UnavailabilityReason.UNKNOWN
                else -> null
            },
        )
    }

    /** The day's number in [system], or `null` when that system could not convert the date. */
    private fun dayNumberIn(cell: CalendarDay, system: CalendarSystem): Int? = when (system) {
        CalendarSystem.GREGORIAN -> cell.date.dayOfMonth
        CalendarSystem.BANGLA -> cell.bangla?.day
        CalendarSystem.HIJRI -> cell.hijri?.day
    }
}
