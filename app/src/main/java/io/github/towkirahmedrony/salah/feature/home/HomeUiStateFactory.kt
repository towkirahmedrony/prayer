package io.github.towkirahmedrony.salah.feature.home

import io.github.towkirahmedrony.salah.core.domain.CountdownFormatter
import io.github.towkirahmedrony.salah.core.domain.NextPrayerResolver
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleState
import io.github.towkirahmedrony.salah.core.model.CalendarDateState
import io.github.towkirahmedrony.salah.core.model.CalendarSnapshot
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.PrayerName
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
 *  - missing Bangla/Hijri conversion is reported as unavailable, never invented;
 *  - sunrise, solar noon and sunset are rendered as solar events, never as prayers;
 *  - a provider failure is surfaced as [HomeUiState.Ready.failureDetail], not swallowed;
 *  - the method that produced a timetable is always reported next to it.
 *
 * Times are rendered in the prayer location's zone, using the user's time-format preference.
 * The device's own zone is used only as a last-resort fallback when neither a schedule nor a
 * configured location exists to supply one.
 */
class HomeUiStateFactory(
    private val nextPrayerResolver: NextPrayerResolver,
) {

    fun create(
        calendar: CalendarSnapshot?,
        location: LocationInfo?,
        settings: PrayerSettings,
        scheduleState: PrayerScheduleState,
        prohibitedState: ProhibitedPeriodsState,
        now: ZonedDateTime,
    ): HomeUiState {
        if (calendar == null) {
            return HomeUiState.Failed(detail = "The device date could not be read.")
        }

        val gregorian = calendar.gregorian as? CalendarDateState.Available
            ?: return HomeUiState.Failed(detail = "The Gregorian date is unavailable.")

        val schedule = (scheduleState as? PrayerScheduleState.Available)?.schedule
        val zone = schedule?.zoneId ?: location?.zoneId ?: ZoneId.systemDefault()
        val locale = Locale.getDefault()

        val timeFormatter = timeFormatter(zone, locale, settings.timeFormat)
        val stampFormatter = DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withLocale(locale)
            .withZone(zone)

        // The resolver returns null rather than guessing when the schedule is for another day,
        // incomplete, or already finished, so the countdown can never be fabricated.
        val nextPrayer = nextPrayerResolver.resolve(schedule, now)

        return HomeUiState.Ready(
            gregorianDate = gregorian.formattedDate,
            weekday = calendar.weekdayLabel,
            gregorianSection = calendar.gregorian.toSection(),
            banglaSection = calendar.bangla.toSection(),
            hijriSection = calendar.hijri.toSection(),
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
            nextPrayer = NextPrayerSection(
                prayer = nextPrayer?.prayer,
                time = nextPrayer?.let { timeFormatter.format(it.time) },
                countdown = nextPrayer?.let { CountdownFormatter.format(it.remaining) },
            ),
            prayerRows = PrayerName.entries.map { prayer ->
                val entry = schedule?.prayers?.firstOrNull { it.prayer == prayer }
                PrayerRow(
                    prayer = prayer,
                    timeLabel = entry?.let { timeFormatter.format(it.time) },
                    adjustmentMinutes = entry?.adjustmentMinutes ?: 0,
                    isNext = nextPrayer?.prayer == prayer,
                )
            },
            solarRows = SolarEvent.entries.map { event ->
                SolarRow(
                    event = event,
                    timeLabel = schedule?.timeOf(event)?.let(timeFormatter::format),
                )
            },
            prohibited = prohibitedState.toSection(timeFormatter),
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
     * Builds the formatter for the user's preference.
     *
     * `SYSTEM` follows the device locale, which is the only option that respects a locale whose
     * conventional clock is neither 12- nor 24-hour in the way the fixed patterns assume.
     */
    private fun timeFormatter(
        zone: ZoneId,
        locale: Locale,
        preference: TimeFormatPreference,
    ): DateTimeFormatter = when (preference) {
        TimeFormatPreference.SYSTEM -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        TimeFormatPreference.TWELVE_HOUR -> DateTimeFormatter.ofPattern("h:mm a", locale)
        TimeFormatPreference.TWENTY_FOUR_HOUR -> DateTimeFormatter.ofPattern("HH:mm", locale)
    }.withLocale(locale).withZone(zone)

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

    private fun ProhibitedPeriodsState.toSection(timeFormatter: DateTimeFormatter): ProhibitedSection =
        when (this) {
            is ProhibitedPeriodsState.Available -> ProhibitedSection(
                windows = periods.map { period ->
                    "${timeFormatter.format(period.startsAt)} – ${timeFormatter.format(period.endsAt)}"
                },
                unavailable = null,
            )

            is ProhibitedPeriodsState.NotAvailable -> ProhibitedSection(
                windows = emptyList(),
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
