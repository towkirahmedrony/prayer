package io.github.towkirahmedrony.salah.feature.home

import io.github.towkirahmedrony.salah.core.model.AsrCalculationMethod
import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import io.github.towkirahmedrony.salah.core.model.HighLatitudeRule
import io.github.towkirahmedrony.salah.core.model.LocationOrigin
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationMethod
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.domain.prohibited.ProhibitedPeriodPhase
import io.github.towkirahmedrony.salah.core.domain.prohibited.ProhibitedWindowKind
import io.github.towkirahmedrony.salah.core.model.PrayerTimeSource
import io.github.towkirahmedrony.salah.core.model.ProhibitedConvention
import io.github.towkirahmedrony.salah.core.model.SolarEvent
import io.github.towkirahmedrony.salah.core.model.TimeFormatPreference
import io.github.towkirahmedrony.salah.core.model.UnavailabilityReason

/**
 * Immutable state of the Home screen.
 *
 * Everything the UI needs is already resolved here: the composables never read a repository,
 * never format a date and never compute a countdown. Sections that have no real value carry an
 * [UnavailabilityReason] so the screen can explain the gap instead of inventing data.
 *
 * Domain types travel as themselves (never as pre-rendered text), which is what lets
 * `feature/common/DomainLabels.kt` own localisation while this file stays Android-free.
 */
sealed interface HomeUiState {

    /** At least one upstream flow has not emitted yet. */
    data object Loading : HomeUiState

    /** The device date could not be read, so nothing can be shown honestly. */
    data class Failed(val detail: String) : HomeUiState

    data class Ready(
        /**
         * Localised Gregorian date for today, from the device clock, or `null` while the calendar
         * snapshot has not been produced yet.
         *
         * The calendar is a **progressive** input, not a Home-critical one: prayer times must not
         * wait for it. When it is late the header renders the placeholder for this field and the
         * date appears a moment later, instead of the whole screen staying on the skeleton.
         */
        val gregorianDate: String?,
        /** Localised weekday name for today. */
        val weekday: String?,
        val gregorianSection: CalendarSection,
        val banglaSection: CalendarSection,
        val hijriSection: CalendarSection,
        val location: LocationSection,
        /** How the displayed times were calculated, so the user can tell what they are looking at. */
        val calculation: CalculationSection,
        /** Why the timetable has no times, or `null` when a schedule exists. */
        val timetableNote: UnavailabilityReason?,
        /**
         * The prayer period that is open right now, or the explanation why none can be reported.
         *
         * This is the Home screen's primary prayer reading; [nextPrayer] stays as the secondary one.
         */
        val currentPrayer: CurrentPrayerSection,
        val nextPrayer: NextPrayerSection,
        val prayerRows: List<PrayerRow>,
        val solarRows: List<SolarRow>,
        val prohibited: ProhibitedSection,
        val provenance: ProvenanceSection,
        /** Non-null when the configured provider failed; shown with a retry action. */
        val failureDetail: String?,
    ) : HomeUiState
}

/** One calendar representation on the Home screen. */
data class CalendarSection(
    val system: CalendarSystem,
    /** Already localised and ready to render, or `null` when unavailable. */
    val value: String?,
    val unavailable: UnavailabilityReason?,
)

/** Resolved location state. Coordinates stay numeric so the UI formats them. */
data class LocationSection(
    val label: String?,
    val latitude: Double?,
    val longitude: Double?,
    val timeZoneId: String?,
    val origin: LocationOrigin?,
    val unavailable: UnavailabilityReason?,
)

/**
 * The calculation settings the displayed timetable was produced with.
 *
 * Shown on the Home screen so a time is never presented without the method that produced it:
 * two methods legitimately disagree, and the user needs to see which one is in force.
 */
data class CalculationSection(
    val method: PrayerCalculationMethod,
    val asrMethod: AsrCalculationMethod,
    val highLatitudeRule: HighLatitudeRule,
    /** True when at least one prayer carries a manual minute correction. */
    val hasAdjustments: Boolean,
    val timeFormat: TimeFormatPreference,
)

/** Countdown is only produced when a real schedule exists. */
data class NextPrayerSection(
    val prayer: PrayerName?,
    val time: String?,
    val countdown: String?,
)

/**
 * The active prayer period, resolved for the moment the screen is showing.
 *
 * Every field is already resolved: the composable formats nothing and decides nothing. A period
 * that is open always carries an end time and a countdown; the countdown is the time until the
 * next boundary in the timetable and says nothing about how long the prayer itself may be
 * performed.
 *
 * When no period is open — the middle of the morning, between sunrise and Dhuhr — [prayer] is
 * `null`, [next] carries the prayer that opens the next period, and [unavailable] stays `null`:
 * that reading is a fact about the timetable, not a gap in it.
 */
data class CurrentPrayerSection(
    /** The prayer whose period is open, or `null` when none is. */
    val prayer: PrayerName?,
    /** True only when a prayer period is open right now. */
    val isActive: Boolean,
    /**
     * Formatted start of the open period, or `null` when it began before the schedule in hand —
     * the Isha period that is still running in the small hours.
     */
    val startTime: String?,
    /** Formatted time the open period ends. */
    val endTime: String?,
    /** The prayer that ends the open period, when one does. */
    val endsWithPrayer: PrayerName?,
    /** True when that closing prayer falls on the following local date. */
    val endsNextDay: Boolean,
    /** The solar event that ends the open period, when a solar event does; never a prayer. */
    val endsWithSolarEvent: SolarEvent?,
    /** Countdown to the end of the open period, formatted. */
    val countdown: String?,
    /** The next prayer to begin, used when no period is open and shown as secondary detail. */
    val next: NextPrayerSection,
    /** Non-null when no period can be resolved, so the card explains the gap. */
    val unavailable: UnavailabilityReason?,
)

data class PrayerRow(
    val prayer: PrayerName,
    /** `null` renders as a placeholder dash in the UI, never as an invented time. */
    val timeLabel: String?,
    /** Manual correction already applied to [timeLabel]; `0` when the time is unadjusted. */
    val adjustmentMinutes: Int = 0,
    val isNext: Boolean,
    /**
     * Formatted time this prayer's period ends, or `null` when it cannot be resolved (for
     * example Isha, while tomorrow's Fajr has not been calculated yet). Never invented.
     */
    val endTimeLabel: String? = null,
    /** True when [endTimeLabel] falls on the following local date (Isha closes at next Fajr). */
    val endsNextDay: Boolean = false,
)

data class SolarRow(
    val event: SolarEvent,
    val timeLabel: String?,
)

/**
 * One prohibited interval, resolved for the moment the screen is showing.
 *
 * @param event the solar event the interval is anchored to. It is shown next to the interval so the
 *   astronomical event and the prohibited interval around it are never confused with each other.
 * @param phase where the interval stands: active, upcoming or passed.
 * @param countdown time until the interval opens (while upcoming) or until it closes (while active).
 */
data class ProhibitedWindowRow(
    val kind: ProhibitedWindowKind,
    val event: SolarEvent,
    val startsAt: String,
    val endsAt: String,
    /** Formatted time of [event] itself, when the schedule carries it. */
    val eventTime: String?,
    val phase: ProhibitedPeriodPhase,
    val countdown: String?,
    /** The configured boundaries of this interval, so the policy is readable on the screen. */
    val explanation: String,
)

data class ProhibitedSection(
    /** One row per configured interval; empty when nothing can be shown. */
    val windows: List<ProhibitedWindowRow>,
    /** The policy the rows were calculated under, so the screen can name it. */
    val convention: ProhibitedConvention?,
    val unavailable: UnavailabilityReason?,
)

/** Provenance of the data currently shown, so cached and calculated data stays visible. */
data class ProvenanceSection(
    val source: PrayerTimeSource,
    val providerId: String?,
    val observedAtLabel: String?,
    val offlineReady: Boolean,
)
