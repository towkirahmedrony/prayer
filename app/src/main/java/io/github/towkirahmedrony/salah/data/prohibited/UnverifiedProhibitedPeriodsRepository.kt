package io.github.towkirahmedrony.salah.data.prohibited

import io.github.towkirahmedrony.salah.core.domain.repository.ProhibitedPeriodsRepository
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.ProhibitedPeriodsState
import io.github.towkirahmedrony.salah.core.model.UnavailabilityReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.time.LocalDate

/**
 * Prohibited-time provider for this phase.
 *
 * Prohibited prayer windows follow religious rulings whose exact boundaries depend on the
 * reviewed rule set. Those rules have not been encoded and reviewed, so this implementation
 * always reports [UnavailabilityReason.RULES_NOT_VERIFIED]. Salah therefore shows an explicit
 * explanation instead of hardcoded intervals, and never implies a ruling it cannot justify.
 */
class UnverifiedProhibitedPeriodsRepository : ProhibitedPeriodsRepository {

    override fun observePeriods(
        location: LocationInfo?,
        date: LocalDate,
    ): Flow<ProhibitedPeriodsState> = flowOf(
        ProhibitedPeriodsState.NotAvailable(
            reason = UnavailabilityReason.RULES_NOT_VERIFIED,
            detail = "Prohibited-time rules have not been reviewed and configured yet.",
        ),
    )
}
