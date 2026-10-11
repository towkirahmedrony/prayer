package io.github.towkirahmedrony.salah.core.domain.prayer

import io.github.towkirahmedrony.salah.core.model.DataSourceInfo
import io.github.towkirahmedrony.salah.core.model.PrayerSchedule
import io.github.towkirahmedrony.salah.core.model.UnavailabilityReason

/**
 * State of the prayer schedule observed by the UI.
 *
 * Local data is the primary read source: [Available] may come from a cache and the UI shows it
 * immediately, while a refresh runs in the background.
 */
sealed interface PrayerScheduleState {

    data object Loading : PrayerScheduleState

    data class Available(
        val schedule: PrayerSchedule,
        val origin: DataSourceInfo,
    ) : PrayerScheduleState

    /** No schedule can be produced, with a reason the UI explains verbatim. */
    data class Unavailable(
        val reason: UnavailabilityReason,
        val detail: String? = null,
        val origin: DataSourceInfo,
    ) : PrayerScheduleState

    /** A provider was configured but failed. Reported, never silently swallowed. */
    data class Failed(
        val detail: String,
        val origin: DataSourceInfo,
    ) : PrayerScheduleState
}
