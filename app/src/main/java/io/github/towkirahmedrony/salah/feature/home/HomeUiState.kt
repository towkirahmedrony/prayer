package io.github.towkirahmedrony.salah.feature.home

import io.github.towkirahmedrony.salah.core.model.AsrCalculationMethod
import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import io.github.towkirahmedrony.salah.core.model.HighLatitudeRule
import io.github.towkirahmedrony.salah.core.model.LocationOrigin
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationMethod
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerTimeSource
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

data class PrayerRow(
    val prayer: PrayerName,
    /** `null` renders as a placeholder dash in the UI, never as an invented time. */
    val timeLabel: String?,
    /** Manual correction already applied to [timeLabel]; `0` when the time is unadjusted. */
    val adjustmentMinutes: Int = 0,
    val isNext: Boolean,
)

data class SolarRow(
    val event: SolarEvent,
    val timeLabel: String?,
)

data class ProhibitedSection(
    /** Formatted "start – end" windows. Empty until a reviewed rule set exists. */
    val windows: List<String>,
    val unavailable: UnavailabilityReason?,
)

/** Provenance of the data currently shown, so cached and calculated data stays visible. */
data class ProvenanceSection(
    val source: PrayerTimeSource,
    val providerId: String?,
    val observedAtLabel: String?,
    val offlineReady: Boolean,
)
