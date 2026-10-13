package io.github.towkirahmedrony.salah.core.domain.location

import io.github.towkirahmedrony.salah.core.model.LocationInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * The zone in which Salah decides a **location-local** civil day.
 *
 * **Why this exists.** Salah shows a calendar day in three representations and a prayer timetable for
 * the same day. Those must be the *same day*: a date above the times and times below the date are one
 * statement, and if they are derived in two different zones they can name two different civil days —
 * which is exactly what happened while the calendar took its day from the device's zone and the prayer
 * schedule took its day from the location's. This class is the single place that answers "which zone is
 * that day decided in", so no screen re-derives it
 * ([io.github.towkirahmedrony.salah.data.calendar.DeviceClockCalendarRepository] and
 * [io.github.towkirahmedrony.salah.feature.calendar.CalendarViewModel] both read this one value).
 *
 * **What the answer is.** The configured location's own `zoneId`, because that is the zone the prayer
 * schedule is already calculated and displayed in and the zone the location policy attached to the
 * coordinates. When no location is configured — a state Salah's setup gate does not let a
 * location-dependent screen reach — the device's own zone answers instead, so the class is never
 * without a value and no caller has to handle a missing zone. That fallback is also what keeps the
 * device's zone genuinely *fresh* in that state: it is read on each [current] call rather than captured
 * when this object was built.
 *
 * **Not a policy of its own.** It resolves nothing about geography, validation or the location model:
 * it reads the zone the stored [LocationInfo] already carries. A location selected as a Bangladesh area
 * or adopted from a device fix carries `Asia/Dhaka`; a manually entered one carries the zone the user
 * supplied. Whatever validation applies to those is applied where they are written.
 *
 * **Where the ordering is decided.** [zone]'s *first* value is whatever [locationSource] emits first —
 * normally the stored location, because the repository reads DataStore once at start-up. It is not an
 * assumption about `null` meaning "no location": a `null` emission is "not configured" and is answered
 * with the device's zone, and a later non-null emission replaces it.
 *
 * Pure in the sense that matters for testing: it performs no platform call other than
 * `ZoneId.systemDefault()` in the fallback, and every consumer reads it as a flow or a value.
 */
class LocationDayZone(
    locationSource: Flow<LocationInfo?>,
    scope: CoroutineScope,
) {

    /**
     * The stored location's zone, or `null` while no location is configured.
     *
     * Held as the location's *zone* rather than as the location, because that is the only part of it
     * this class is about, and holding the zone (not the object) means a re-save of the same location
     * cannot re-emit a value that did not change.
     */
    private val configured: MutableStateFlow<ZoneId?> = MutableStateFlow(null)

    /**
     * The resolved zone, re-emitted whenever the stored location changes.
     *
     * A consumer that has to *react* to a zone change — the calendar repository restarts its
     * next-midnight wait on it — collects this. A consumer that only needs the current answer reads
     * [current].
     */
    val zone: Flow<ZoneId> = configured
        .map { stored -> stored ?: ZoneId.systemDefault() }
        .distinctUntilChanged()

    /**
     * The zone right now.
     *
     * Reads the device's zone on each call while no location is configured, so a device time-zone
     * change takes effect on the next pass instead of at the next location write.
     */
    fun current(): ZoneId = configured.value ?: ZoneId.systemDefault()

    init {
        scope.launch {
            locationSource.collect { location ->
                configured.value = location?.zoneId
            }
        }
    }
}
