package io.github.towkirahmedrony.salah.feature.ramadan

import io.github.towkirahmedrony.salah.core.domain.calendar.BanglaCalendar
import io.github.towkirahmedrony.salah.core.domain.calendar.CalendarFormatting
import io.github.towkirahmedrony.salah.core.domain.calendar.HijriCalendar
import io.github.towkirahmedrony.salah.core.domain.calendar.RamadanCalendar
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleState
import io.github.towkirahmedrony.salah.core.domain.timeFormatterFor
import io.github.towkirahmedrony.salah.core.model.DataSourceInfo
import io.github.towkirahmedrony.salah.core.model.HijriDate
import io.github.towkirahmedrony.salah.core.model.HijriDateSource
import io.github.towkirahmedrony.salah.core.model.HijriMonth
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.RamadanPhase
import io.github.towkirahmedrony.salah.core.model.UnavailabilityReason
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Pure mapping from the Ramadan window and the existing prayer schedules to [RamadanUiState].
 *
 * This class is where the Sehri/Iftar rule becomes something the user reads, and it is deliberately
 * upstream of Android: no context, no resources, no clock and no repository. What that buys is a unit
 * test for each of the claims the screen makes — that Sehri is the Fajr time and Iftar is the Maghrib
 * time, that a missing Fajr produces no Sehri time, that a day without a schedule says so instead of
 * showing an empty cell, and that the times are rendered in the prayer location's zone with the user's
 * own clock format.
 *
 * The times are never re-derived here. They are read from the schedule the repository produced, which is
 * the same object the Home screen and the notification planner use, so the Ramadan table, the timetable
 * and the reminder cannot disagree. Sehri and Iftar go through
 * [RamadanCalendar.sehriEndOf] and [RamadanCalendar.iftarOf], so the rule itself has exactly one
 * definition.
 */
class RamadanUiStateFactory(
    private val formatting: CalendarFormatting = CalendarFormatting(),
    private val banglaCalendar: BanglaCalendar = BanglaCalendar(),
    private val hijriCalendar: HijriCalendar = HijriCalendar(),
) {

    fun create(
        inputs: RamadanUiStateInputs,
        systemLocale: Locale = Locale.getDefault(),
    ): RamadanUiState {
        val locale = formatting.localeFor(inputs.calendarSettings.dateLanguage, systemLocale)
        val window = inputs.window
        val origin = inputs.schedules.values.firstNotNullOfOrNull { it.origin() }
        val anySchedule = inputs.schedules.values.filterIsInstance<PrayerScheduleState.Available>()
            .firstOrNull()
            ?.schedule

        val rows = window?.dates.orEmpty().map { date ->
            rowOf(date = date, inputs = inputs, locale = locale, fallbackZone = anySchedule?.zoneId)
        }

        return RamadanUiState.Ready(
            phase = window?.phase(inputs.today) ?: RamadanPhase.FAR_AWAY,
            hijriYear = window?.hijriYear,
            titleLabel = window?.let { range -> hijriYearLabel(range.hijriYear, range.source, locale) },
            rangeLabel = window?.let { range ->
                "${formatting.gregorian(range.start, locale)} – ${formatting.gregorian(range.end, locale)}"
            },
            source = window?.source,
            totalDays = window?.dayCount,
            todayDayNumber = window?.dayNumber(inputs.today),
            rows = rows,
            daysWithoutSchedule = rows.count { !it.hasSchedule },
            timeSource = origin?.source,
            timeSourceProviderId = origin?.providerId,
            locationConfigured = inputs.locationConfigured,
            masterEnabled = inputs.notificationSettings.masterEnabled,
            sehriReminderEnabled = inputs.notificationSettings.sehriReminderEnabled,
            sehriLeadMinutes = inputs.notificationSettings.sehriLeadMinutes,
            iftarReminderEnabled = inputs.notificationSettings.iftarReminderEnabled,
            iftarLeadMinutes = inputs.notificationSettings.iftarLeadMinutes,
            hijriAdjustmentDays = inputs.calendarSettings.hijriAdjustmentDays,
            message = null,
        )
    }

    /**
     * The heading's Hijri year, for example `Ramadan 1448 AH` or `রমজান ১৪৪৮ AH`.
     *
     * Built from the shared Hijri formatter so the year is written in the interface language's own
     * numerals (Bengali on a Bengali interface) exactly as the table's dates are. The era marker is
     * the same `AH` the rows use, so the heading and the table cannot disagree about what calendar
     * they are in.
     */
    private fun hijriYearLabel(year: Int, source: HijriDateSource, locale: Locale): String {
        val monthAndYear = formatting.hijriMonth(
            HijriDate(year = year, month = HijriMonth.RAMADAN, day = 1, source = source),
            locale,
        )
        return "$monthAndYear ${CalendarFormatting.HIJRI_ERA}"
    }

    private fun rowOf(
        date: LocalDate,
        inputs: RamadanUiStateInputs,
        locale: Locale,
        fallbackZone: ZoneId?,
    ): RamadanDayRow {
        val state = inputs.schedules[date]
        val schedule = (state as? PrayerScheduleState.Available)?.schedule
        val zone = schedule?.zoneId ?: fallbackZone ?: ZoneId.systemDefault()

        val timeFormatter = timeFormatterFor(
            zone = zone,
            locale = locale,
            preference = inputs.prayerSettings.timeFormat,
        )

        // Sehri is Fajr and Iftar is Maghrib, taken from the schedule rather than recalculated.
        val sehriEnd = schedule?.let(RamadanCalendar::sehriEndOf)
        val iftar = schedule?.let(RamadanCalendar::iftarOf)

        return RamadanDayRow(
            date = date,
            ramadanDayNumber = inputs.window?.dayNumber(date) ?: 0,
            gregorianLabel = formatting.gregorian(date, locale),
            banglaLabel = banglaCalendar.convert(date)?.let { formatting.bangla(it, locale) },
            hijriLabel = hijriLabelOf(date, inputs, locale),
            sehriEndLabel = sehriEnd?.let(timeFormatter::format),
            fajrLabel = schedule?.timeOf(PrayerName.FAJR)?.let(timeFormatter::format),
            iftarLabel = iftar?.let(timeFormatter::format),
            maghribLabel = schedule?.timeOf(PrayerName.MAGHRIB)?.let(timeFormatter::format),
            isToday = date == inputs.today,
            hasSchedule = schedule != null,
            unavailable = state?.unavailabilityReason(),
        )
    }

    /**
     * The Hijri date of [date], or `null` outside the platform's table.
     *
     * Nullable rather than defaulted to the Gregorian date: a Hijri label that silently repeated the
     * Gregorian value would be exactly the kind of fabricated date this feature must not produce.
     *
     * The era marker is included so the row is unmistakable: in the table the three dates are stacked
     * with no label column beside them, and `1445 AH` cannot be read as a Gregorian year.
     */
    private fun hijriLabelOf(
        date: LocalDate,
        inputs: RamadanUiStateInputs,
        locale: Locale,
    ): String? = hijriCalendar.dateOf(date, inputs.calendarSettings.hijriAdjustmentDays)
        ?.let { formatting.hijriWithEra(it, locale) }

    /** Provenance of a schedule state, so the screen can name where the times came from. */
    private fun PrayerScheduleState.origin(): DataSourceInfo? = when (this) {
        is PrayerScheduleState.Available -> origin
        is PrayerScheduleState.Unavailable -> origin
        is PrayerScheduleState.Failed -> origin
        PrayerScheduleState.Loading -> null
    }

    private fun PrayerScheduleState.unavailabilityReason(): UnavailabilityReason? = when (this) {
        is PrayerScheduleState.Available -> null
        is PrayerScheduleState.Unavailable -> reason
        is PrayerScheduleState.Failed -> UnavailabilityReason.UNKNOWN
        PrayerScheduleState.Loading -> UnavailabilityReason.UNKNOWN
    }
}
