package io.github.towkirahmedrony.salah.feature.home

import io.github.towkirahmedrony.salah.core.domain.CountdownFormatter
import io.github.towkirahmedrony.salah.core.domain.CurrentPrayerPeriodResolver
import io.github.towkirahmedrony.salah.core.domain.CurrentPrayerStatus
import io.github.towkirahmedrony.salah.core.domain.NextPrayerResolver
import io.github.towkirahmedrony.salah.core.domain.PeriodBoundary
import io.github.towkirahmedrony.salah.core.domain.location.LocationDisplayName
import io.github.towkirahmedrony.salah.core.domain.night.NightPeriodCalculator
import io.github.towkirahmedrony.salah.core.domain.solar.SolarDayResolver
import io.github.towkirahmedrony.salah.core.domain.dashboard.DashboardConfig
import io.github.towkirahmedrony.salah.core.domain.timeFormatterFor
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleState
import io.github.towkirahmedrony.salah.core.domain.prohibited.ProhibitedPeriodStatusResolver
import io.github.towkirahmedrony.salah.core.domain.calendar.RamadanCalendar
import io.github.towkirahmedrony.salah.core.domain.qibla.QiblaBearing
import io.github.towkirahmedrony.salah.core.domain.qibla.QiblaCalculator
import io.github.towkirahmedrony.salah.core.model.CalendarDateState
import io.github.towkirahmedrony.salah.core.model.CalendarSnapshot
import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerSchedule
import io.github.towkirahmedrony.salah.core.model.NightPeriodsState
import io.github.towkirahmedrony.salah.core.model.PrayerSettings
import io.github.towkirahmedrony.salah.core.model.PrayerTimeSource
import io.github.towkirahmedrony.salah.core.model.ProhibitedPeriodsState
import io.github.towkirahmedrony.salah.core.model.SolarDayState
import io.github.towkirahmedrony.salah.core.model.SolarEvent
import io.github.towkirahmedrony.salah.core.model.TimeFormatPreference
import io.github.towkirahmedrony.salah.core.model.UnavailabilityReason
import io.github.towkirahmedrony.salah.core.model.RamadanPhase
import io.github.towkirahmedrony.salah.core.model.RamadanWindow
import java.time.Duration
import java.time.ZoneId
import java.time.temporal.ChronoUnit
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
    private val solarDayResolver: SolarDayResolver = SolarDayResolver(),
    private val nightPeriodCalculator: NightPeriodCalculator = NightPeriodCalculator(),
    /**
     * The Qibla bearing, pure geography from the saved coordinates.
     *
     * Injected rather than re-implemented, so the Home card, the Qibla screen and their tests all
     * read the same formula; the bearing needs no permission, sensor or network, which is why it can
     * be resolved here rather than by the compass screen.
     */
    private val qiblaCalculator: QiblaCalculator = QiblaCalculator(),
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
        /** The user's saved dashboard layout, or the default when none has been stored. */
        dashboard: DashboardConfig = DashboardConfig.DEFAULT,
        /**
         * The estimated Ramadan window containing today, or the next one after it, from the same
         * `CalendarRepository` the Ramadan screen reads. `null` when the platform's Hijri table does
         * not cover one — the honest case in which no Ramadan card is shown.
         */
        ramadanWindow: RamadanWindow? = null,
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
            countdownCompact = nextPrayer?.let { CountdownFormatter.formatCompact(it.remaining) },
        )

        // The open period is resolved exactly once. The hero card and the current-prayer marker on the
        // prayer rows are both projections of this one result, so the Home and Prayer screens cannot
        // disagree about which prayer is current — the defect where the Prayer timetable highlighted
        // the *next* prayer (tomorrow's Fajr after Isha) is fixed by reading this value instead of
        // independently re-selecting from the list.
        val currentStatus = currentPrayerResolver.resolve(schedule, now, nextDayFajr)
        val currentPrayerName = (currentStatus as? CurrentPrayerStatus.Active)?.period?.prayer

        // The hero chart and the daylight span are the only geometry Home needs. Both are derived
        // from the same schedule everything else on the screen reads, so the chart cannot plot a day
        // the timetable disagrees with, and the span is measured, never assumed.
        val heroArc = buildHeroArc(
            schedule = schedule,
            now = now,
            currentPrayer = currentPrayerName,
            nextPrayer = nextPrayer?.prayer,
        )
        val daylight = daylightSpan(schedule)

        // The day's solar geometry and the night interval are derived from the same schedule (and,
        // for the night interval, the same following-day schedule) that everything else on the
        // screen reads. Neither performs astronomy: they only read instants the engine produced, and
        // they are a pure function of those instants and the displayed moment.
        val solarDayUi = solarDaySection(solarDayResolver.resolve(schedule), timeFormatter)
        val nightUi = nightSection(
            state = nightPeriodCalculator.calculate(schedule, nextDaySchedule),
            now = now,
            timeFormatter = timeFormatter,
        )

        return HomeUiState.Ready(
            gregorianDate = gregorian?.formattedDate,
            weekday = calendar?.weekdayLabel,
            gregorianSection = calendar?.gregorian?.toSection() ?: pendingSection(CalendarSystem.GREGORIAN),
            banglaSection = calendar?.bangla?.toSection() ?: pendingSection(CalendarSystem.BANGLA),
            hijriSection = calendar?.hijri?.toSection() ?: pendingSection(CalendarSystem.HIJRI),
            location = LocationSection(
                // Composed by the shared policy rather than read from the stored string, so Home, the
                // Qibla screen and the location screen describe one stored location the same way, and a
                // record written by an earlier build is shown under the current rule.
                label = location?.let { stored -> LocationDisplayName.of(stored) },
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
                    isPassed = entry != null && !entry.time.isAfter(now),
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
            solarDay = solarDayUi,
            night = nightUi,
            daylight = daylight,
            heroArc = heroArc,
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
            dashboard = dashboardState(dashboard),
            ramadan = ramadanSection(window = ramadanWindow, schedule = schedule, timeFormatter = timeFormatter),
            qibla = qiblaSection(location),
        )
    }

    /**
     * The arranged cards, resolved from the persisted layout.
     *
     * Every supported card is carried — visible or not — because the management list needs a switch
     * for each, while [DashboardState.enabled] is the subset the dashboard draws. The card is a
     * domain value, never a label, so the arrangement is independent of the interface language.
     */
    private fun dashboardState(config: DashboardConfig): DashboardState {
        val normalized = config.normalized()
        return DashboardState(
            cards = normalized.orderedCards().map { card ->
                DashboardCardState(card = card, enabled = normalized.isEnabled(card))
            },
        )
    }

    /**
     * The Ramadan reading for today.
     *
     * Context-aware by construction: the card is only visible while today is inside the estimated
     * window or within [RamadanWindow.UPCOMING_WINDOW_DAYS] of it, so Salah never claims it is
     * Ramadan when it is not. The Sehri and Iftar times are read from today's schedule through
     * [RamadanCalendar] — the same rule the Ramadan screen uses — never recomputed here.
     */
    private fun ramadanSection(
        window: RamadanWindow?,
        schedule: PrayerSchedule?,
        timeFormatter: DateTimeFormatter,
    ): RamadanSection {
        if (window == null || schedule == null) return RamadanSection.Hidden
        val today = schedule.date
        return when (window.phase(today)) {
            RamadanPhase.IN_RAMADAN -> RamadanSection(
                visible = true,
                inRamadan = true,
                dayNumber = window.dayNumber(today),
                totalDays = window.dayCount,
                sehriEndLabel = RamadanCalendar.sehriEndOf(schedule)?.let(timeFormatter::format),
                iftarLabel = RamadanCalendar.iftarOf(schedule)?.let(timeFormatter::format),
                daysUntilStart = null,
                unavailable = null,
            )

            RamadanPhase.UPCOMING -> RamadanSection(
                visible = true,
                inRamadan = false,
                dayNumber = null,
                totalDays = null,
                sehriEndLabel = null,
                iftarLabel = null,
                daysUntilStart = ChronoUnit.DAYS.between(today, window.start),
                unavailable = null,
            )

            RamadanPhase.FAR_AWAY -> RamadanSection.Hidden
        }
    }

    /**
     * The Qibla bearing from the saved location.
     *
     * A missing location, or coordinates the calculator refuses (invalid, on the Kaaba reference or
     * at its antipode), is reported as [QiblaSection.unavailable] rather than as a number — the card
     * shows the reason instead of a fabricated direction.
     */
    private fun qiblaSection(location: LocationInfo?): QiblaSection {
        if (location == null) return QiblaSection.Unavailable
        return when (val bearing = qiblaCalculator.bearingFrom(location)) {
            is QiblaBearing.Available -> QiblaSection(
                bearingDegrees = bearing.degrees,
                distanceKm = bearing.distanceMetres / METRES_PER_KILOMETRE,
                unavailable = null,
            )

            is QiblaBearing.Unavailable -> QiblaSection(
                bearingDegrees = null,
                distanceKm = null,
                unavailable = UnavailabilityReason.UNKNOWN,
            )
        }
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
                countdownCompact = CountdownFormatter.formatCompact(period.remaining),
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
            countdownCompact = CountdownFormatter.formatCompact(status.remaining),
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
            countdownCompact = null,
            next = next,
            unavailable = scheduleState.asTimetableNote() ?: status.reason,
        )
    }

    /**
     * Places every prayer and the displayed moment on the day's arc.
     *
     * The interval the arc spans is Fajr to Isha — the two ends of the day the timetable brackets — so
     * the first and last prayers sit on the arc's ends and the day's progress reads as a single
     * fraction. A moment outside that interval clamps to the nearer end rather than leaving the arc,
     * which is the honest reading for a pre-dawn or post-Isha view: the sun is at the day's boundary.
     *
     * Returns [HeroArcSection.Empty] instead of a partial chart when the schedule is missing or does
     * not span a positive interval, so no mark is ever invented.
     */
    private fun buildHeroArc(
        schedule: PrayerSchedule?,
        now: ZonedDateTime,
        currentPrayer: PrayerName?,
        nextPrayer: PrayerName?,
    ): HeroArcSection {
        if (schedule == null || !schedule.isComplete) return HeroArcSection.Empty
        val start = schedule.timeOf(PrayerName.FAJR) ?: return HeroArcSection.Empty
        val end = schedule.timeOf(PrayerName.ISHA) ?: return HeroArcSection.Empty
        val spanSeconds = Duration.between(start, end).seconds.toDouble()
        if (spanSeconds <= 0.0) return HeroArcSection.Empty

        fun fraction(time: ZonedDateTime): Float =
            (Duration.between(start, time).seconds.toDouble() / spanSeconds)
                .coerceIn(0.0, 1.0)
                .toFloat()

        val markers = PrayerName.entries.mapNotNull { prayer ->
            val time = schedule.timeOf(prayer) ?: return@mapNotNull null
            HeroArcMarker(
                prayer = prayer,
                position = fraction(time),
                isPassed = !time.isAfter(now),
                isCurrent = currentPrayer == prayer,
                isNext = nextPrayer == prayer,
            )
        }
        return HeroArcSection(progress = fraction(now), markers = markers)
    }

    /**
     * The sunlight span of the day: sunset minus sunrise, in the compact `11h 38m` form.
     *
     * `null` until both solar events exist, because a span with one end missing is not a span, and
     * Salah states no value it cannot measure.
     */
    private fun daylightSpan(schedule: PrayerSchedule?): String? {
        val sunrise = schedule?.timeOf(SolarEvent.SUNRISE) ?: return null
        val sunset = schedule?.timeOf(SolarEvent.SUNSET) ?: return null
        val span = Duration.between(sunrise, sunset)
        if (span.isNegative || span.isZero) return null
        return CountdownFormatter.formatCompact(span)
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

    /**
     * Maps the resolved solar geometry to its section.
     *
     * A field is left `null` when its event is genuinely absent; the section is only marked
     * unavailable when no schedule exists to derive anything from, so a polar day that still has a
     * transit and a measured span is shown as far as it honestly can be.
     */
    private fun solarDaySection(
        state: SolarDayState,
        timeFormatter: DateTimeFormatter,
    ): SolarDaySection = when (state) {
        is SolarDayState.Available -> SolarDaySection(
            solarMidnightLabel = state.day.solarMidnight?.let(timeFormatter::format),
            dayLengthLabel = state.day.dayLength?.let(CountdownFormatter::formatCompact),
            nightLengthLabel = state.day.nightLength?.let(CountdownFormatter::formatCompact),
            unavailable = null,
        )

        is SolarDayState.NotAvailable -> SolarDaySection(
            solarMidnightLabel = null,
            dayLengthLabel = null,
            nightLengthLabel = null,
            unavailable = state.reason,
        )
    }

    /**
     * Maps the resolved night interval to its section for the displayed moment.
     *
     * The phase, the upcoming boundary and the countdown all come from the same calculator, so the
     * countdown can only ever target a boundary the interval actually has. When the interval cannot
     * be built — today's Maghrib or the following Fajr is missing — every field is `null` and the
     * reason is carried, never a fabricated boundary.
     */
    private fun nightSection(
        state: NightPeriodsState,
        now: ZonedDateTime,
        timeFormatter: DateTimeFormatter,
    ): NightSection = when (state) {
        is NightPeriodsState.Available -> {
            val periods = state.periods
            val upcoming = nightPeriodCalculator.upcomingBoundary(periods, now)
            NightSection(
                phase = nightPeriodCalculator.phaseAt(periods, now),
                durationLabel = CountdownFormatter.formatCompact(periods.duration),
                islamicMidnightLabel = timeFormatter.format(periods.islamicMidnight),
                lastThirdStartsLabel = timeFormatter.format(periods.lastThirdStartsAt),
                tahajjudStartsLabel = timeFormatter.format(periods.tahajjudStartsAt),
                nextBoundary = upcoming?.boundary,
                countdown = upcoming?.let { CountdownFormatter.format(it.remaining) },
                convention = state.convention,
                unavailable = null,
            )
        }

        is NightPeriodsState.NotAvailable -> NightSection(
            phase = null,
            durationLabel = null,
            islamicMidnightLabel = null,
            lastThirdStartsLabel = null,
            tahajjudStartsLabel = null,
            nextBoundary = null,
            countdown = null,
            convention = null,
            unavailable = state.reason,
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

    private companion object {
        /** Metres in a kilometre, for the Qibla card's distance reading. */
        const val METRES_PER_KILOMETRE = 1_000.0
    }
}
