package io.github.towkirahmedrony.salah.core.domain.repository

import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleRequest
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleState
import kotlinx.coroutines.flow.Flow

/**
 * Read access to prayer schedules.
 *
 * The contract is offline-first: [ensureSchedule] reads local state first and must never block
 * the caller on a network, because local calculation is the primary source. A schedule is only
 * produced when the cache has no entry that matches the full request — date, location **and**
 * calculation profile — so a schedule can never be shown for a different day, a different city,
 * or settings the user has since changed.
 */
interface PrayerScheduleRepository {

    /**
     * Emits the schedule for [request], calculating it when no matching local entry exists.
     *
     * A `null` [request] means no location is configured; the flow then emits
     * [PrayerScheduleState.Unavailable] with
     * [io.github.towkirahmedrony.salah.core.model.UnavailabilityReason.NO_LOCATION_CONFIGURED].
     *
     * Every failure is emitted as a value ([PrayerScheduleState.Failed] or
     * [PrayerScheduleState.Unavailable]); this flow does not throw for expected problems.
     */
    fun ensureSchedule(request: PrayerScheduleRequest?): Flow<PrayerScheduleState>

    /**
     * Recalculates [request] from the provider, bypassing the cache, and replaces the cache
     * entry when it succeeds. Used by the Home screen's retry action. Never throws.
     */
    suspend fun refresh(request: PrayerScheduleRequest?): PrayerScheduleState
}
