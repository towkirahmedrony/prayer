package io.github.towkirahmedrony.salah.core.domain.repository

import io.github.towkirahmedrony.salah.core.model.PrayerSchedule
import io.github.towkirahmedrony.salah.core.model.ProhibitedPeriodsState
import kotlinx.coroutines.flow.Flow

/**
 * Read access to prohibited prayer-time windows.
 *
 * Windows are derived from a prayer schedule, so the schedule is the input: the same solar
 * events that produced the timetable anchor the windows, and passing the schedule keeps the two
 * calculations from drifting apart.
 *
 * Implementations must only return windows derived from a *reviewed* rule set. Returning
 * [ProhibitedPeriodsState.NotAvailable] is the correct, expected state until such rules exist;
 * invented intervals are never acceptable.
 */
interface ProhibitedPeriodsRepository {

    /** Emits the prohibited windows for [schedule], or the reason none can be shown. */
    fun observePeriods(schedule: PrayerSchedule?): Flow<ProhibitedPeriodsState>
}
