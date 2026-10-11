package io.github.towkirahmedrony.salah.core.model

import java.time.Instant

/**
 * Where a piece of information came from.
 *
 * This is what lets the UI state distinguish locally calculated data, remote data and local
 * caches, as required by the offline-first rules.
 */
enum class PrayerTimeSource {
    /** No provider is wired up, so no schedule exists. */
    UNCONFIGURED,

    /** Computed on the device. Works offline. */
    LOCAL_CALCULATION,

    /** Fetched from a remote provider and stored locally. */
    REMOTE_API,

    /** Imported or entered by the user from a verified local timetable. */
    USER_ENTERED_TIMETABLE,
}

/** Metadata describing the origin and freshness of a piece of data. */
data class DataSourceInfo(
    val source: PrayerTimeSource,
    val providerId: String? = null,
    val observedAt: Instant,
    val isAvailableOffline: Boolean,
)
