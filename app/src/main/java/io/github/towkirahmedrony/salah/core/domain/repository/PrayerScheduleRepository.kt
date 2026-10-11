package io.github.towkirahmedrony.salah.core.domain.repository

import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleState
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * Read access to prayer schedules.
 *
 * The contract is offline-first: [observeSchedule] reads local state only and must never block
 * on the network. [refresh] may talk to a provider, and its result is what eventually reaches
 * [observeSchedule] observers.
 */
interface PrayerScheduleRepository {

    /**
     * Observes the schedule for [location] on [date].
     *
     * Emits [PrayerScheduleState.Unavailable] with [io.github.towkirahmedrony.salah.core.model.UnavailabilityReason.NO_LOCATION_CONFIGURED]
     * when [location] is `null`.
     */
    fun observeSchedule(location: LocationInfo?, date: LocalDate): Flow<PrayerScheduleState>

    /** Attempts to obtain a schedule from the configured provider. Never throws. */
    suspend fun refresh(location: LocationInfo?, date: LocalDate): PrayerScheduleState
}
