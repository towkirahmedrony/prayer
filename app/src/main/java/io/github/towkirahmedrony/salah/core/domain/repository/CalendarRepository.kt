package io.github.towkirahmedrony.salah.core.domain.repository

import io.github.towkirahmedrony.salah.core.model.CalendarSnapshot
import kotlinx.coroutines.flow.Flow

/**
 * Read access to the calendar representations shown in Salah.
 *
 * The Gregorian date comes from the device clock. Bangla and Hijri conversion are separate
 * conversion engines; until they exist, those sections report
 * [io.github.towkirahmedrony.salah.core.model.UnavailabilityReason.CALENDAR_NOT_IMPLEMENTED].
 */
interface CalendarRepository {

    fun observeToday(): Flow<CalendarSnapshot>
}
