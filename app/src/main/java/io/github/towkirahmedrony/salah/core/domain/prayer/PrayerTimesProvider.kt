package io.github.towkirahmedrony.salah.core.domain.prayer

import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationProfile
import io.github.towkirahmedrony.salah.core.model.PrayerSchedule
import io.github.towkirahmedrony.salah.core.model.PrayerTimeSource
import io.github.towkirahmedrony.salah.core.model.UnavailabilityReason
import java.time.LocalDate

/** Everything a provider needs to produce one day of times. */
data class PrayerScheduleRequest(
    val date: LocalDate,
    val location: LocationInfo,
    val profile: PrayerCalculationProfile,
)

/** Whether a provider can serve requests at all. */
enum class ProviderAvailability {
    CONFIGURED,
    NOT_CONFIGURED,
}

/** Result of a provider call. */
sealed interface PrayerTimesProviderResult {
    data class Success(val schedule: PrayerSchedule) : PrayerTimesProviderResult

    /** The provider is known but has nothing for this request (missing config, no data). */
    data class Unavailable(
        val reason: UnavailabilityReason,
        val detail: String? = null,
    ) : PrayerTimesProviderResult

    /** A provider was configured but the call failed. Never swallowed. */
    data class Failure(val detail: String) : PrayerTimesProviderResult
}

/**
 * A replaceable source of prayer schedules.
 *
 * Implementations live in the data layer and are the only place that may know about an
 * astronomical algorithm, an HTTP API, or an imported timetable. The Home screen depends on
 * this interface only, so a provider can be added or replaced without touching the UI.
 *
 * Planned implementations (not present in this phase):
 *  - a local astronomical engine (the Adhan Kotlin library is the candidate, pending a
 *    verified calculation profile);
 *  - a remote provider such as AlAdhan, validated before it is stored;
 *  - a user-imported timetable from a verified local source.
 */
interface PrayerTimesProvider {

    /** Stable identifier, used for diagnostics and for data-source metadata. */
    val id: String

    /** Which kind of source this is, reported to the UI as provenance. */
    val source: PrayerTimeSource

    /** Whether this provider can work without a network connection. */
    val isAvailableOffline: Boolean

    fun availability(): ProviderAvailability

    /** Produces a schedule for [request]. Must not throw for expected failures. */
    suspend fun schedule(request: PrayerScheduleRequest): PrayerTimesProviderResult
}
