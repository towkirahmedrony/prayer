package io.github.towkirahmedrony.salah.data.calendar

import io.github.towkirahmedrony.salah.core.domain.repository.CalendarRepository
import io.github.towkirahmedrony.salah.core.model.CalendarDateState
import io.github.towkirahmedrony.salah.core.model.CalendarSnapshot
import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import io.github.towkirahmedrony.salah.core.model.UnavailabilityReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Calendar repository for this phase.
 *
 * Only the Gregorian date is produced, and it comes from the device clock — a real value, not a
 * placeholder. The Bangladesh Bangla and Hijri sections report
 * [UnavailabilityReason.CALENDAR_NOT_IMPLEMENTED] because those conversions are not
 * implemented yet; Salah never fabricates a Bangla or Hijri date.
 *
 * Known limitation: the snapshot is emitted once per collection. Re-emitting at midnight
 * belongs with the prayer scheduler in a later phase.
 */
class DeviceClockCalendarRepository(
    private val clock: Clock,
) : CalendarRepository {

    override fun observeToday(): Flow<CalendarSnapshot> = flow {
        emit(snapshotNow())
    }

    private fun snapshotNow(): CalendarSnapshot {
        val locale = Locale.getDefault()
        val today = LocalDate.now(clock)
        return CalendarSnapshot(
            gregorian = CalendarDateState.Available(
                system = CalendarSystem.GREGORIAN,
                formattedDate = today.format(DateTimeFormatter.ofPattern("d MMMM yyyy", locale)),
                isVerifiedSource = true,
            ),
            bangla = CalendarDateState.NotAvailable(
                system = CalendarSystem.BANGLA,
                reason = UnavailabilityReason.CALENDAR_NOT_IMPLEMENTED,
                detail = "Bangladesh Bangla calendar conversion is not implemented yet.",
            ),
            hijri = CalendarDateState.NotAvailable(
                system = CalendarSystem.HIJRI,
                reason = UnavailabilityReason.CALENDAR_NOT_IMPLEMENTED,
                detail = "Hijri date conversion is not implemented yet. Salah does not estimate it.",
            ),
            weekdayLabel = today.format(DateTimeFormatter.ofPattern("EEEE", locale)),
        )
    }
}
