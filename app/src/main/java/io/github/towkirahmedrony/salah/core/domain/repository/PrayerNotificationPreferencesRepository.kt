package io.github.towkirahmedrony.salah.core.domain.repository

import io.github.towkirahmedrony.salah.core.model.PrayerNotificationSettings
import kotlinx.coroutines.flow.Flow

/**
 * Local persistence of the prayer-notification settings.
 *
 * Kept separate from [UserPreferencesRepository] because these settings are read on a completely
 * different path — by a broadcast receiver, on a device that may never open the app that day — and
 * that path should depend on the notification contract and nothing wider.
 *
 * The data, however, is **not** kept in a separate file: the implementation writes to the same
 * DataStore as every other Salah preference, so there is one store, one source of truth, and no
 * possibility of two stores disagreeing about whether notifications are on.
 *
 * Offline by construction. There is no account, no network call and no server-side copy: these
 * values are read straight from disk, which is what makes reboot recovery work with the radio off.
 */
interface PrayerNotificationPreferencesRepository {

    /**
     * Emits the stored notification settings.
     *
     * Always emits a value: a fresh install gets [PrayerNotificationSettings.DEFAULT] rather than
     * `null`, so no caller has to guess whether notifications are configured.
     */
    val settings: Flow<PrayerNotificationSettings>

    /**
     * Atomically rewrites the settings through [transform].
     *
     * Read and write happen inside one DataStore transaction, so two quick taps cannot lose one
     * another's change and no cached copy can be written back over a newer value — the same
     * guarantee [UserPreferencesRepository.updatePrayerSettings] provides for calculation settings.
     */
    suspend fun update(transform: (PrayerNotificationSettings) -> PrayerNotificationSettings)
}
