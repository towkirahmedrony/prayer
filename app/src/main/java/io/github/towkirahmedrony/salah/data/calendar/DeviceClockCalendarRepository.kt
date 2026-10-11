package io.github.towkirahmedrony.salah.data.calendar

import io.github.towkirahmedrony.salah.core.domain.repository.CalendarRepository
import io.github.towkirahmedrony.salah.core.model.CalendarDateState
import io.github.towkirahmedrony.salah.core.model.CalendarSnapshot
import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import io.github.towkirahmedrony.salah.core.model.UnavailabilityReason
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Calendar repository backed by the device clock.
 *
 * Only the Gregorian date is produced, and it comes from the device clock — a real value, not a
 * placeholder. The Bangladesh Bangla and Hijri sections report
 * [UnavailabilityReason.CALENDAR_NOT_IMPLEMENTED] because those conversions are not implemented
 * yet; Salah never fabricates a Bangla or Hijri date.
 *
 * The snapshot is re-emitted when the device's calendar date actually rolls over, rather than
 * once per collection, so a screen left open overnight stops showing yesterday. The wait is
 * computed to the next local midnight instead of polling, and it is re-derived every iteration:
 * [ZoneId.systemDefault] is read on each pass, so a time-zone change on the device is picked up
 * on the next midnight boundary and never yields a snapshot rendered in a stale zone.
 */
class DeviceClockCalendarRepository(
    private val clock: Clock,
) : CalendarRepository {

    override fun observeToday(): Flow<CalendarSnapshot> = flow {
        while (true) {
            val instant = clock.instant()
            emit(snapshotAt(instant))
            delay(millisUntilNextLocalMidnight(instant))
        }
    }.distinctUntilChanged()

    private fun snapshotAt(instant: Instant): CalendarSnapshot {
        val locale = Locale.getDefault()
        val today = instant.atZone(ZoneId.systemDefault()).toLocalDate()
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


    /**
     * Whole milliseconds until the next local midnight, at least 1.
     *
     * `atStartOfDay` is used rather than adding a day to the current time because it is the only
     * form that is correct across a daylight-saving transition, where a local day can be 23 or
     * 25 hours long.
     */
    private fun millisUntilNextLocalMidnight(instant: Instant): Long {
        val zone = ZoneId.systemDefault()
        val midnight = instant.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
        return Duration.between(instant, midnight).toMillis().coerceAtLeast(1L)
    }
}
