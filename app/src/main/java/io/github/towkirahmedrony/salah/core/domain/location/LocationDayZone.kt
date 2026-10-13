package io.github.towkirahmedrony.salah.core.domain.location

import io.github.towkirahmedrony.salah.core.domain.startup.StoredLocationState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.ZoneId

/**
 * The zone in which Salah decides a **location-local** civil day.
 *
 * **Why this exists.** Salah shows a calendar day in three representations and a prayer timetable for the
 * same day. Those must be the *same day*: a date above the times and times below the date are one
 * statement, and if they are derived in two different zones they can name two different civil days — which
 * is exactly what happened while the calendar took its day from the device's zone and the prayer schedule
 * took its day from the location's. This class is the single place that answers "which zone is that day
 * decided in", so no screen re-derives it
 * ([io.github.towkirahmedrony.salah.data.calendar.DeviceClockCalendarRepository] and
 * [io.github.towkirahmedrony.salah.feature.calendar.CalendarViewModel] both read this one instance).
 *
 * **What the answer is.** The configured location's own `zoneId` — the zone the prayer schedule is already
 * calculated and displayed in, and the zone the location policy attached to the coordinates. When no
 * location is configured, the device's own zone answers instead, so there is never a missing value and no
 * caller has to handle one.
 *
 * **Why it reads the start-up snapshot rather than the location repository.** A screen may need this
 * answer *synchronously* while it is being constructed — the Calendar screen picks the month to open on
 * before it can collect anything — and a cold DataStore flow cannot answer without a dispatch.
 * [io.github.towkirahmedrony.salah.core.domain.startup.StartupSnapshot] already solves exactly that
 * problem for this same value: it is read once during application start-up, published hot, and kept
 * current by one collector, and the location gate does not let a location-dependent screen exist until
 * the read has finished. Projecting it here means the first synchronous read is already the real answer,
 * and later changes still arrive through [zone].
 *
 * **Not a policy of its own.** It resolves nothing about geography, validation or the location model: it
 * reads the zone the stored [io.github.towkirahmedrony.salah.core.model.LocationInfo] already carries. A
 * location selected as a Bangladesh area or adopted from a device fix carries `Asia/Dhaka`; a manually
 * entered one carries the zone the user supplied.
 */
class LocationDayZone(
    /** The location as the app already publishes it: hot, and readable without suspending. */
    private val storedLocation: StateFlow<StoredLocationState>,
) {

    /**
     * The resolved zone, re-emitted whenever the stored location changes.
     *
     * A consumer that has to *react* to a zone change — the calendar repository restarts its wait for the
     * next local midnight on it — collects this. A consumer that only needs the current answer reads
     * [current]. A re-save of the same place does not move the zone and so does not re-emit.
     */
    val zone: Flow<ZoneId> = storedLocation
        .map { state -> state.locationZone() }
        .distinctUntilChanged()

    /**
     * The zone right now, without suspending.
     *
     * Answers with the device's zone while the stored location has not been read yet, which is the honest
     * "not known" answer rather than a claim about a place. In the running application that state is
     * unreachable from a screen: the location gate holds every location-dependent destination until the
     * read has completed.
     */
    fun current(): ZoneId = storedLocation.value.locationZone()

    private fun StoredLocationState.locationZone(): ZoneId =
        (this as? StoredLocationState.Loaded)?.location?.zoneId ?: ZoneId.systemDefault()
}
