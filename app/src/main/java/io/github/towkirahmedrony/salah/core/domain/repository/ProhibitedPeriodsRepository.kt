package io.github.towkirahmedrony.salah.core.domain.repository

import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.ProhibitedPeriodsState
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * Read access to prohibited prayer-time windows.
 *
 * Implementations must only return windows derived from a reviewed rule set. Returning
 * [ProhibitedPeriodsState.NotAvailable] is the correct, expected state until such rules are
 * encoded and reviewed; invented intervals are never acceptable.
 */
interface ProhibitedPeriodsRepository {

    fun observePeriods(location: LocationInfo?, date: LocalDate): Flow<ProhibitedPeriodsState>
}
