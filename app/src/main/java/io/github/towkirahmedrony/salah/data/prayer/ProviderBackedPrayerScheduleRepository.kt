package io.github.towkirahmedrony.salah.data.prayer

import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleRequest
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleState
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerTimesProvider
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerTimesProviderResult
import io.github.towkirahmedrony.salah.core.domain.prayer.ProviderAvailability
import io.github.towkirahmedrony.salah.core.domain.repository.PrayerScheduleRepository
import io.github.towkirahmedrony.salah.core.model.DataSourceInfo
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationProfile
import io.github.towkirahmedrony.salah.core.model.PrayerSchedule
import io.github.towkirahmedrony.salah.core.model.UnavailabilityReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlin.coroutines.cancellation.CancellationException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/**
 * Offline-first [PrayerScheduleRepository].
 *
 * Local state is the primary read source: [observeSchedule] answers from the local cache and
 * never performs I/O, so the UI is never blocked by the network. Only [refresh] talks to the
 * configured [PrayerTimesProvider], and every failure mode is converted into a
 * [PrayerScheduleState] value instead of an exception.
 *
 * The cache is intentionally in-memory for this phase. It becomes a Room-backed cache in the
 * same change that introduces the first persisted schedule, which is why no database is added
 * here (see docs/ARCHITECTURE.md).
 */
class ProviderBackedPrayerScheduleRepository(
    private val provider: PrayerTimesProvider,
    private val clock: Clock,
    private val profile: PrayerCalculationProfile,
) : PrayerScheduleRepository {

    private val cachedSchedule = MutableStateFlow<PrayerSchedule?>(null)
    private val lastFailure = MutableStateFlow<String?>(null)

    override fun observeSchedule(location: LocationInfo?, date: LocalDate): Flow<PrayerScheduleState> {
        if (location == null) {
            return flowOf(
                PrayerScheduleState.Unavailable(
                    reason = UnavailabilityReason.NO_LOCATION_CONFIGURED,
                    detail = NO_LOCATION_DETAIL,
                    origin = origin(),
                ),
            )
        }
        return combine(cachedSchedule, lastFailure) { cached, failure ->
            val usable = cached?.takeIf { it.date == date && it.location == location }
            when {
                usable != null -> PrayerScheduleState.Available(
                    schedule = usable,
                    origin = origin(observedAt = usable.generatedAt),
                )

                failure != null -> PrayerScheduleState.Failed(
                    detail = failure,
                    origin = origin(),
                )

                provider.availability() == ProviderAvailability.CONFIGURED -> PrayerScheduleState.Unavailable(
                    reason = UnavailabilityReason.UNKNOWN,
                    detail = "The provider is configured but has not produced a schedule yet.",
                    origin = origin(),
                )

                else -> PrayerScheduleState.Unavailable(
                    reason = UnavailabilityReason.CALCULATION_NOT_CONFIGURED,
                    detail = CALCULATION_NOT_CONFIGURED_DETAIL,
                    origin = origin(),
                )
            }
        }
    }

    override suspend fun refresh(location: LocationInfo?, date: LocalDate): PrayerScheduleState {
        if (location == null) {
            return PrayerScheduleState.Unavailable(
                reason = UnavailabilityReason.NO_LOCATION_CONFIGURED,
                detail = NO_LOCATION_DETAIL,
                origin = origin(),
            )
        }

        val request = PrayerScheduleRequest(date = date, location = location, profile = profile)
        val result = try {
            provider.schedule(request)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            val detail = "Provider '${provider.id}' failed: ${error.message ?: error::class.simpleName}"
            lastFailure.value = detail
            return PrayerScheduleState.Failed(detail = detail, origin = origin())
        }

        return when (result) {
            is PrayerTimesProviderResult.Success -> {
                lastFailure.value = null
                cachedSchedule.value = result.schedule
                PrayerScheduleState.Available(
                    schedule = result.schedule,
                    origin = origin(observedAt = result.schedule.generatedAt),
                )
            }

            is PrayerTimesProviderResult.Unavailable -> {
                lastFailure.value = null
                PrayerScheduleState.Unavailable(
                    reason = result.reason,
                    detail = result.detail,
                    origin = origin(),
                )
            }

            is PrayerTimesProviderResult.Failure -> {
                lastFailure.value = result.detail
                PrayerScheduleState.Failed(detail = result.detail, origin = origin())
            }
        }
    }

    private fun origin(observedAt: Instant = clock.instant()): DataSourceInfo = DataSourceInfo(
        source = provider.source,
        providerId = provider.id,
        observedAt = observedAt,
        isAvailableOffline = provider.isAvailableOffline,
    )

    private companion object {
        const val NO_LOCATION_DETAIL =
            "No location has been configured yet, so no calculation input exists."
        const val CALCULATION_NOT_CONFIGURED_DETAIL =
            "No prayer calculation engine is integrated yet, so Salah does not display prayer times."
    }
}
