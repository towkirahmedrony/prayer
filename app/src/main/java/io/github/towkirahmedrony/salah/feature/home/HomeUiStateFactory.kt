package io.github.towkirahmedrony.salah.feature.home

import io.github.towkirahmedrony.salah.core.domain.CountdownFormatter
import io.github.towkirahmedrony.salah.core.domain.CurrentPrayerPeriodResolver
import io.github.towkirahmedrony.salah.core.domain.CurrentPrayerStatus
import io.github.towkirahmedrony.salah.core.domain.NextPrayerResolver
import io.github.towkirahmedrony.salah.core.domain.PeriodBoundary
import io.github.towkirahmedrony.salah.core.domain.timeFormatterFor
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleState
import io.github.towkirahmedrony.salah.core.domain.prohibited.ProhibitedPeriodStatusResolver
import io.github.towkirahmedrony.salah.core.model.CalendarDateState
import io.github.towkirahmedrony.salah.core.model.CalendarSnapshot
import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerSchedule
import io.github.towkirahmedrony.salah.core.model.PrayerSettings
import io.github.towkirahmedrony.salah.core.model.PrayerTimeSource
import io.github.towkirahmedrony.salah.core.model.ProhibitedPeriodsState
import io.github.towkirahmedrony.salah.core.model.SolarEvent
import io.github.towkirahmedrony.salah.core.model.TimeFormatPreference
import io.github.towkirahmedrony.salah.core.model.UnavailabilityReason
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Pure mapping from domain state to [HomeUiState].
 *
 * This class holds the only "logic" the Home screen needs, without Android or coroutine
 * dependencies, which is what makes the anti-fabrication rules unit-testable:
 *
 *  - a countdown appears only when a complete schedule for *today* exists;
 *  - the current prayer period is resolved from that same schedule, never from a second engine;
 *  - missing Bangla/Hijri conversion is reported as unavailable, never invented;
 *  - sunrise, solar noon and sunset are rendered as solar events, never as prayers;
 *  - a provider failure is surfaced as [HomeUiState.Ready.failureDetail], not swallowed;
 *  - the method that produced a timetable is always reported next to it.
 *
 * The moment being displayed is passed in, so the whole screen — including both countdowns — is a
 * function of the schedule and the clock, and can be replayed in a test at any instant.
 *
 * Times are rendered in the prayer location's zone, using the user's time-format preference.
 * The device's own zone is used only as a last-resort fallback when neither a schedule nor a
 * configured location exists to supply one.
 */
class HomeUiStateFactory(
    private val nextPrayerResolver: NextPrayerResolver,
    private val currentPrayerResolver: CurrentPrayerPeriodResolver,
    private val prohibitedStatusResolver: ProhibitedPeriodStatusResolver,
) {

    fun create(
        calendar: CalendarSnapshot?,
        location: LocationInfo?,
        settings: PrayerSettings,
        scheduleState: PrayerScheduleState,
        prohibitedState: ProhibitedPeriodsState,
        now: ZonedDateTime,
        /**
         * The following local date's **schedule**, when the caller has it.
         *
         * It answers two questions that today's timetable cannot: the boundary that closes Isha's
         * period, and — once today's last prayer has begun — what the next prayer actually is, since
         * that is the following day's Fajr. The whole schedule is taken rather than a bare instant so
         * both answers come from one calculation the same engine produced for the same place, and so
         * the resolver can check that it really is tomorrow's before using it.
         */
        nextDaySchedule: PrayerSchedule? = null,
    ): HomeUiState {
        // `null` means the calendar snapshot has not been produced yet, which is a *pending* state,
        // not a failure: Home renders the prayer content and a placeholder date, and the calendar
        // arrives a moment later. A snapshot that exists but carries no usable Gregorian date is a
        // genuine failure, because the device date could not be read.
        val gregorian = calendar?.gregorian as? CalendarDateState.Available
        if (calendar != null && gregorian == null) {
            // A reason, not a sentence: the screen renders it in whichever language is selected.
            return HomeUiState.Failed(reason = UnavailabilityReason.DEVICE_DATE_UNAVAILABLE)
        }

        val schedule = (scheduleState as? PrayerScheduleState.Available)?.schedule
        val zone = schedule?.zoneId ?: location?.zoneId ?: ZoneId.systemDefault()
        val locale = Locale.getDefault()

        val timeFormatter = timeFormatter(zone, locale, settings.timeFormat)
        val stampFormatter = DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withLocale(locale)
            .withZone(zone)

        // Tomorrow's Fajr, derived here from tomorrow's own schedule rather than accepted as a bare
        // instant, so Isha's closing boundary and the next-prayer countdown are the same calculation.
        // `null` when tomorrow has not been produced — the honest answer, not an invented boundary.
        val nextDayFajr = nextDaySchedule?.timeOf(PrayerName.FAJR)

        // The resolver returns null rather than guessing when the schedule is for another day or
        // incomplete, so the countdown can never be fabricated. It is given tomorrow's schedule so that
        // after today's last prayer it can name tomorrow's Fajr instead of reporting nothing.
        val nextPrayer = nextPrayerResolver.resolve(schedule, now, nextDaySchedule)
        val nextPrayerSection = NextPrayerSection(
            prayer = nextPrayer?.prayer,
            time = nextPrayer?.let { timeFormatter.format(it.time) },
            countdown = nextPrayer?.let { CountdownFormatter.format(it.remaining) },
        )

        // The open period is resolved exactly once. The hero card and the current-prayer marker on the
        // prayer rows are both projections of this one result, so the Home and Prayer screens cannot
        // disagree about which prayer is current — the defect where the Prayer timetable highlighted
        // the *next* prayer (tomorrow's Fajr after Isha) is fixed by reading this value instead of
        // independently re-selecting from the list.
        val currentStatus = currentPrayerResolver.resolve(schedule, now, nextDayFajr)
        val currentPrayerName = (currentStatus as? CurrentPrayerStatus.Active)?.period?.prayer

        return HomeUiState.Ready(
            gregorianDate = gregorian?.formattedDate,
            weekday = calendar?.weekdayLabel,
            gregorianSection = calendar?.gregorian?.toSection() ?: pendingSection(CalendarSystem.GREGORIAN),
            banglaSection = calendar?.bangla?.toSection() ?: pendingSection(CalendarSystem.BANGLA),
            hijriSection = calendar?.hijri?.toSection() ?: pendingSection(CalendarSystem.HIJRI),
            location = LocationSection(
                label = location?.label,
                latitude = location?.latitude,
                longitude = location?.longitude,
                timeZoneId = location?.timeZoneId,
                origin = location?.origin,
                unavailable = if (location == null) {
                    UnavailabilityReason.NO_LOCATION_CONFIGURED
                } else {
                    null
                },
            ),
            calculation = CalculationSection(
                method = settings.method,
                asrMethod = settings.asrMethod,
                highLatitudeRule = settings.highLatitudeRule,
                hasAdjustments = settings.hasAdjustments,
                timeFormat = settings.timeFormat,
            ),
            timetableNote = scheduleState.asTimetableNote(),
            currentPrayer = currentPrayerSection(
                status = currentStatus,
                next = nextPrayerSection,
                scheduleState = scheduleState,
                timeFormatter = timeFormatter,
            ),
            nextPrayer = nextPrayerSection,
            prayerRows = PrayerName.entries.map { prayer ->
                val entry = schedule?.prayers?.firstOrNull { it.prayer == prayer }
                val end = periodEnd(schedule, prayer, nextDayFajr)
                PrayerRow(
                    prayer = prayer,
                    timeLabel = entry?.let { timeFormatter.format(it.time) },
                    adjustmentMinutes = entry?.adjustmentMinutes ?: 0,
                    isNext = nextPrayer?.prayer == prayer,
                    isCurrent = currentPrayerName == prayer,
                    endTimeLabel = end?.let { timeFormatter.format(it.time) },
                    endsNextDay = end?.isNextDay == true,
                )
            },
            solarRows = SolarEvent.entries.map { event ->
                SolarRow(
                    event = event,
                    timeLabel = schedule?.timeOf(event)?.let(timeFormatter::format),
                )
            },
            prohibited = prohibitedState.toSection(
                schedule = schedule,
                now = now,
                timeFormatter = timeFormatter,
            ),
            provenance = ProvenanceSection(
                source = scheduleState.originSource(),
                providerId = scheduleState.providerId(),
                observedAtLabel = scheduleState.observedAt()?.let(stampFormatter::format),
                offlineReady = scheduleState.isAvailableOffline(),
            ),
            failureDetail = (scheduleState as? PrayerScheduleState.Failed)?.detail,
        )
    }

    /**
     * Resolves the period that is open now.
     *
     * The reason reported when nothing can be resolved prefers the schedule's own explanation —
     * "no location configured" is more useful to a reader than a generic failure — and falls back to
     * the resolver's reason when the schedule itself is fine but cannot describe this moment.
     */
    private fun currentPrayerSection(
        status: CurrentPrayerStatus,
        next: NextPrayerSection,
        scheduleState: PrayerScheduleState,
        timeFormatter: DateTimeFormatter,
    ): CurrentPrayerSection = when (status) {
        is CurrentPrayerStatus.Active -> {
            val period = status.period
            val boundary = period.endsWith
            CurrentPrayerSection(
                prayer = period.prayer,
                isActive = true,
                // A null start is the Isha period that began before today's schedule; the card says
                // so rather than showing a start time from another day's timetable.
                startTime = period.startsAt?.let(timeFormatter::format),
                endTime = timeFormatter.format(period.endsAt),
                endsWithPrayer = (boundary as? PeriodBoundary.Prayer)?.prayer,
                endsNextDay = (boundary as? PeriodBoundary.Prayer)?.isNextDay == true,
                endsWithSolarEvent = (boundary as? PeriodBoundary.Solar)?.event,
                countdown = CountdownFormatter.format(period.remaining),
                next = next,
                unavailable = null,
            )
        }

        is CurrentPrayerStatus.Between -> CurrentPrayerSection(
            prayer = null,
            isActive = false,
            startTime = null,
            endTime = timeFormatter.format(status.nextPrayerTime),
            endsWithPrayer = status.nextPrayer,
            endsNextDay = false,
            endsWithSolarEvent = null,
            countdown = CountdownFormatter.format(status.remaining),
            next = next,
            unavailable = null,
        )

        is CurrentPrayerStatus.Unavailable -> CurrentPrayerSection(
            prayer = null,
            isActive = false,
            startTime = null,
            endTime = null,
            endsWithPrayer = null,
            endsNextDay = false,
            endsWithSolarEvent = null,
            countdown = null,
            next = next,
            unavailable = scheduleState.asTimetableNote() ?: status.reason,
        )
    }

    /** The moment a prayer's period closes, and whether that moment is on the next local date. */
    private data class PeriodEnd(val time: ZonedDateTime, val isNextDay: Boolean)

    /**
     * Resolves where a prayer's period ends, using the same convention as
     * [CurrentPrayerPeriodResolver]: Fajr closes at sunrise, Dhuhr at Asr, Asr at Maghrib,
     * Maghrib at Isha and Isha at the next day's Fajr.
     *
     * Returns `null` instead of guessing when the schedule is incomplete or the closing boundary
     * is not available, so a row can only ever show an end time that really exists.
     */
    private fun periodEnd(
        schedule: PrayerSchedule?,
        prayer: PrayerName,
        nextDayFajr: ZonedDateTime?,
    ): PeriodEnd? {
        if (schedule == null || !schedule.isComplete) return null
        return when (prayer) {
            PrayerName.FAJR -> schedule.timeOf(SolarEvent.SUNRISE)?.let { PeriodEnd(it, false) }
            PrayerName.DHUHR -> schedule.timeOf(PrayerName.ASR)?.let { PeriodEnd(it, false) }
            PrayerName.ASR -> schedule.timeOf(PrayerName.MAGHRIB)?.let { PeriodEnd(it, false) }
            PrayerName.MAGHRIB -> schedule.timeOf(PrayerName.ISHA)?.let { PeriodEnd(it, false) }
            PrayerName.ISHA -> {
                val isha = schedule.timeOf(PrayerName.ISHA)
                val closing = nextDayFajr?.withZoneSameInstant(schedule.zoneId)
                if (isha != null && closing != null && closing.isAfter(isha)) {
                    PeriodEnd(closing, true)
                } else {
                    null
                }
            }
        }
    }

    /**
     * Builds the formatter for the user's preference.
     *
     * Delegates to [timeFormatterFor] so prayer reminders format times by exactly the same rule as
     * the Home screen, instead of the two drifting apart.
     */
    private fun timeFormatter(
        zone: ZoneId,
        locale: Locale,
        preference: TimeFormatPreference,
    ): DateTimeFormatter = timeFormatterFor(zone = zone, locale = locale, preference = preference)

    /**
     * A calendar section that is still being produced.
     *
     * Reported as unavailable with [UnavailabilityReason.UNKNOWN] rather than left blank, so the
     * section explains itself for the moment before the real date lands. No date is invented.
     */
    private fun pendingSection(system: CalendarSystem): CalendarSection = CalendarSection(
        system = system,
        value = null,
        unavailable = UnavailabilityReason.UNKNOWN,
    )

    private fun CalendarDateState.toSection(): CalendarSection = when (this) {
        is CalendarDateState.Available -> CalendarSection(
            system = system,
            value = formattedDate,
            unavailable = null,
        )

        is CalendarDateState.NotAvailable -> CalendarSection(
            system = system,
            value = null,
            unavailable = reason,
        )
    }

    /**
     * Classifies each prohibited window against the displayed moment and formats it for the card.
     *
     * The anchor solar event is looked up from the same schedule the windows were derived from, so
     * the event time shown beside an interval is the very timestamp the interval was calculated
     * from — including for the zenith window, whose anchor is the solar transit and not the
     * displayed Dhuhr prayer time.
     */
    private fun ProhibitedPeriodsState.toSection(
        schedule: PrayerSchedule?,
        now: ZonedDateTime,
        timeFormatter: DateTimeFormatter,
    ): ProhibitedSection = when (this) {
        is ProhibitedPeriodsState.Available -> ProhibitedSection(
            windows = prohibitedStatusResolver.classify(periods, now).map { status ->
                val period = status.period
                ProhibitedWindowRow(
                    kind = period.kind,
                    event = period.kind.anchorEvent,
                    startsAt = timeFormatter.format(period.startsAt),
                    endsAt = timeFormatter.format(period.endsAt),
                    eventTime = schedule?.timeOf(period.kind.anchorEvent)?.let(timeFormatter::format),
                    phase = status.phase,
                    // While a window is upcoming the useful reading is how long until it opens;
                    // while it is active it is how long until it closes.
                    countdown = (status.startsIn ?: status.endsIn)?.let(CountdownFormatter::format),
                    explanation = period.explanation,
                )
            },
            convention = convention,
            unavailable = null,
        )

        is ProhibitedPeriodsState.NotAvailable -> ProhibitedSection(
            windows = emptyList(),
            convention = null,
            // The reason travels with its detail so the screen can explain the exact gap
            // instead of falling back to a generic sentence about verified rules.
            unavailable = reason,
        )
    }

    private fun PrayerScheduleState.asTimetableNote(): UnavailabilityReason? = when (this) {
        is PrayerScheduleState.Available -> null
        is PrayerScheduleState.Unavailable -> reason
        is PrayerScheduleState.Failed -> UnavailabilityReason.UNKNOWN
        PrayerScheduleState.Loading -> UnavailabilityReason.UNKNOWN
    }

    private fun PrayerScheduleState.originSource(): PrayerTimeSource = when (this) {
        is PrayerScheduleState.Available -> origin.source
        is PrayerScheduleState.Unavailable -> origin.source
        is PrayerScheduleState.Failed -> origin.source
        PrayerScheduleState.Loading -> PrayerTimeSource.UNCONFIGURED
    }

    private fun PrayerScheduleState.providerId(): String? = when (this) {
        is PrayerScheduleState.Available -> origin.providerId
        is PrayerScheduleState.Unavailable -> origin.providerId
        is PrayerScheduleState.Failed -> origin.providerId
        PrayerScheduleState.Loading -> null
    }

    private fun PrayerScheduleState.observedAt() = when (this) {
        is PrayerScheduleState.Available -> origin.observedAt
        is PrayerScheduleState.Unavailable -> origin.observedAt
        is PrayerScheduleState.Failed -> origin.observedAt
        PrayerScheduleState.Loading -> null
    }

    private fun PrayerScheduleState.isAvailableOffline(): Boolean = when (this) {
        is PrayerScheduleState.Available -> origin.isAvailableOffline
        is PrayerScheduleState.Unavailable -> origin.isAvailableOffline
        is PrayerScheduleState.Failed -> origin.isAvailableOffline
        PrayerScheduleState.Loading -> false
    }
}
