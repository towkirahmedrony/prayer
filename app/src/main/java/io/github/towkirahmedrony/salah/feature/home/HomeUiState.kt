package io.github.towkirahmedrony.salah.feature.home

import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import io.github.towkirahmedrony.salah.core.model.LocationOrigin
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerTimeSource
import io.github.towkirahmedrony.salah.core.model.SolarEvent
import io.github.towkirahmedrony.salah.core.model.UnavailabilityReason

/**
 * Immutable state of the Home screen.
 *
 * Everything the UI needs is already resolved here: the composables never read a repository,
 * never format a date and never compute a countdown. Sections that have no real value carry an
 * [UnavailabilityReason] so the screen can explain the gap instead of inventing data.
 */
sealed interface HomeUiState {

    /** At least one upstream flow has not emitted yet. */
    data object Loading : HomeUiState

    /** The device date could not be read, so nothing can be shown honestly. */
    data class Failed(val detail: String) : HomeUiState

    data class Ready(
        /** Localised Gregorian date for today, from the device clock. */
        val gregorianDate: String,
        /** Localised weekday name for today. */
        val weekday: String?,
        val gregorianSection: CalendarSection,
        val banglaSection: CalendarSection,
        val hijriSection: CalendarSection,
        val location: LocationSection,
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
    val isNext: Boolean,
)

data class SolarRow(
    val event: SolarEvent,
    val timeLabel: String?,
)

data class ProhibitedSection(
    /** Formatted "start – end" windows. Empty until a verified rule set exists. */
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
