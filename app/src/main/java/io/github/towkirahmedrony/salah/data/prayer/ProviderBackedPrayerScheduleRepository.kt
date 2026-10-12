package io.github.towkirahmedrony.salah.data.prayer

import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleRequest
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleState
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerTimesProvider
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerTimesProviderResult
import io.github.towkirahmedrony.salah.core.domain.prayer.ProviderAvailability
import io.github.towkirahmedrony.salah.core.domain.repository.PrayerScheduleRepository
import io.github.towkirahmedrony.salah.core.model.DataSourceInfo
import io.github.towkirahmedrony.salah.core.model.PrayerSchedule
import io.github.towkirahmedrony.salah.core.model.UnavailabilityReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Clock
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

/**
 * Offline-first [PrayerScheduleRepository].
 *
 * Local state is the primary read source: [ensureSchedule] answers from an in-memory entry and
 * never performs I/O on its first emission, so the UI is never blocked. Only a cache miss — or
 * an explicit [refresh] — reaches the configured [PrayerTimesProvider], and every failure mode
 * is converted into a [PrayerScheduleState] value instead of an exception.
 *
 * **Cache key and invalidation.** An entry may be served only when its date, its location and
 * its calculation profile all equal the incoming request. Changing the location, the time zone,
 * the calculation method, the Asr method, the high-latitude rule, a manual adjustment or the
 * calendar date therefore invalidates the entry automatically, because `equals` on those value
 * types is what performs the comparison. There is no separate "invalidate" call to forget.
 *
 * **Bounded, per-request entries.** The cache holds a small map keyed by the full request rather
 * than a single day. Home and the notification reconciliation are two consumers of this repository
 * with different date windows — Home wants today, the reconciliation wants today and tomorrow — and
 * a single-entry cache would let them evict each other, recalculating the same day on every read.
 * The map is bounded so a long-running process cannot accumulate one entry per day or per location.
 *
 * **Why there is no database here.** A daily schedule is pure arithmetic over a date and a
 * coordinate pair: it is recomputed in microseconds, offline, with no input Salah does not
 * already store. Persisting derived schedules would add a schema, a migration path and a new
 * class of staleness bug in exchange for nothing. User preferences and the location are the
 * real durable state, and both live in DataStore. See `docs/ARCHITECTURE.md` §5.
 */
class ProviderBackedPrayerScheduleRepository(
    private val provider: PrayerTimesProvider,
    private val clock: Clock,
) : PrayerScheduleRepository {

    /** Guarded by [cacheLock]; read from the main thread and written from background calculations. */
    private val cacheLock = Any()

    /** Insertion-ordered, so the oldest entry is the one evicted when [MAX_CACHED_REQUESTS] is reached. */
    private val cache = LinkedHashMap<PrayerScheduleRequest, PrayerSchedule>()

    override fun ensureSchedule(request: PrayerScheduleRequest?): Flow<PrayerScheduleState> = flow {
        if (request == null) {
            emit(noLocationConfigured())
            return@flow
        }

        val cached = cachedFor(request)
        emit(if (cached == null) refresh(request) else available(cached))
    }

    override suspend fun refresh(request: PrayerScheduleRequest?): PrayerScheduleState {
        if (request == null) return noLocationConfigured()

        if (provider.availability() != ProviderAvailability.CONFIGURED) {
            return PrayerScheduleState.Unavailable(
                reason = UnavailabilityReason.CALCULATION_NOT_CONFIGURED,
                detail = NOT_CONFIGURED_DETAIL,
                origin = origin(),
            )
        }

        val result = try {
            provider.schedule(request)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            return PrayerScheduleState.Failed(
                detail = "Provider '${provider.id}' failed: ${error.message ?: error::class.simpleName}",
                origin = origin(),
            )
        }

        return when (result) {
            is PrayerTimesProviderResult.Success -> {
                val produced = result.schedule
                if (!produced.belongsTo(request)) {
                    // Times for another day or another city must never be shown as this
                    // request's answer, whatever the provider claims.
                    return PrayerScheduleState.Failed(
                        detail = "Provider '${provider.id}' answered with a schedule for " +
                            "${produced.date} at ${produced.location.label}, not for the requested " +
                            "${request.date} at ${request.location.label}.",
                        origin = origin(),
                    )
                }
                // Only ever store a schedule under the exact request that produced it, so a slow
                // response for an old request cannot overwrite a newer one.
                remember(produced, request)
                available(produced)
            }

            is PrayerTimesProviderResult.Unavailable -> PrayerScheduleState.Unavailable(
                reason = result.reason,
                detail = result.detail,
                origin = origin(),
            )

            is PrayerTimesProviderResult.Failure -> PrayerScheduleState.Failed(
                detail = result.detail,
                origin = origin(),
            )
        }
    }

    /** A schedule may be displayed only for the day and place it was produced for. */
    private fun PrayerSchedule.belongsTo(request: PrayerScheduleRequest): Boolean =
        date == request.date && location == request.location

    /** A schedule is cached, and re-served, only for the exact request it was calculated from. */
    private fun PrayerSchedule.matches(request: PrayerScheduleRequest): Boolean =
        belongsTo(request) && profile == request.profile

    /** The cached schedule for [request], or `null` when the entry is absent or no longer matches. */
    private fun cachedFor(request: PrayerScheduleRequest): PrayerSchedule? = synchronized(cacheLock) {
        cache[request]?.takeIf { it.matches(request) }
    }

    /** Stores [schedule] under [request], evicting the oldest entry once the cache is full. */
    private fun remember(schedule: PrayerSchedule, request: PrayerScheduleRequest) {
        synchronized(cacheLock) {
            if (!schedule.matches(request)) return
            cache[request] = schedule
            while (cache.size > MAX_CACHED_REQUESTS) {
                val oldest = cache.keys.firstOrNull() ?: break
                cache.remove(oldest)
            }
        }
    }

    private fun available(schedule: PrayerSchedule): PrayerScheduleState.Available =
        PrayerScheduleState.Available(
            schedule = schedule,
            origin = origin(observedAt = schedule.generatedAt),
        )

    private fun noLocationConfigured(): PrayerScheduleState.Unavailable =
        PrayerScheduleState.Unavailable(
            reason = UnavailabilityReason.NO_LOCATION_CONFIGURED,
            detail = NO_LOCATION_DETAIL,
            origin = origin(),
        )

    private fun origin(observedAt: Instant = clock.instant()): DataSourceInfo = DataSourceInfo(
        source = provider.source,
        providerId = provider.id,
        observedAt = observedAt,
        isAvailableOffline = provider.isAvailableOffline,
    )

    private companion object {
        /**
         * How many request-keyed schedules are kept.
         *
         * Small on purpose: today and tomorrow are all the notification reconciliation and Home ever
         * ask for together, and a location or settings change simply produces a new key. The bound is
         * only there so a process that runs for a long time cannot retain one entry per day.
         */
        const val MAX_CACHED_REQUESTS = 4

        const val NO_LOCATION_DETAIL =
            "No location has been configured yet, so no calculation input exists."

        const val NOT_CONFIGURED_DETAIL =
            "No prayer calculation engine is available, so Salah does not display prayer times."
    }
}
