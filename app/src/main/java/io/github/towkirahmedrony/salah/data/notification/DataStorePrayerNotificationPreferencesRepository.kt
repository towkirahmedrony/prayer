package io.github.towkirahmedrony.salah.data.notification

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.towkirahmedrony.salah.core.domain.repository.PrayerNotificationPreferencesRepository
import io.github.towkirahmedrony.salah.core.model.NotificationSound
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerNotificationPreference
import io.github.towkirahmedrony.salah.core.model.PrayerNotificationSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Local, offline notification settings, in the same DataStore file as every other preference.
 *
 * Two properties matter more than the mechanics:
 *
 * **It is sparse.** A target whose preference is exactly
 * [PrayerNotificationPreference.DEFAULT] has its keys removed rather than written out, so the store
 * grows only with decisions the user actually made. That mirrors how manual per-prayer adjustments
 * are stored, and it means a future change to the defaults is picked up by users who never touched
 * a control.
 *
 * **It never fails to read.** An unreadable store, an unknown enum name or an out-of-range advance
 * value all fall back to the documented default instead of throwing. A downgraded or hand-edited
 * store therefore cannot put the notifier into a state the settings screen cannot render — and
 * crucially, cannot make Salah forget that the master switch was on.
 */
class DataStorePrayerNotificationPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) : PrayerNotificationPreferencesRepository {

    override val settings: Flow<PrayerNotificationSettings> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences -> preferences.toNotificationSettings() }

    override suspend fun update(transform: (PrayerNotificationSettings) -> PrayerNotificationSettings) {
        dataStore.edit { preferences ->
            val updated = transform(preferences.toNotificationSettings())

            preferences[Keys.MASTER_ENABLED] = updated.masterEnabled
            preferences[Keys.SUNRISE_ENABLED] = updated.sunriseReminderEnabled

            // Ramadan reminders. Stored unconditionally, like the master and sunrise switches: they
            // are two flags and two lead times, and a sparse representation would buy nothing while
            // making "is Sehri on?" require a default to be looked up somewhere else.
            preferences[Keys.SEHRI_ENABLED] = updated.sehriReminderEnabled
            preferences[Keys.SEHRI_LEAD_MINUTES] = PrayerNotificationSettings.clampSehriLead(
                updated.sehriLeadMinutes,
            )
            preferences[Keys.IFTAR_ENABLED] = updated.iftarReminderEnabled
            preferences[Keys.IFTAR_LEAD_MINUTES] = PrayerNotificationSettings.clampIftarLead(
                updated.iftarLeadMinutes,
            )

            PrayerName.entries.forEach { prayer ->
                val preference = updated.preferenceFor(prayer).withBoundedAdvanceMinutes()
                if (preference.isDefault) {
                    // Writing the defaults back would make them indistinguishable from a decision
                    // the user made, and would freeze today's defaults forever.
                    preferences.remove(Keys.prayerEnabled(prayer))
                    preferences.remove(Keys.prayerSound(prayer))
                    preferences.remove(Keys.prayerVibration(prayer))
                    preferences.remove(Keys.prayerAdvance(prayer))
                } else {
                    preferences[Keys.prayerEnabled(prayer)] = preference.enabled
                    preferences[Keys.prayerSound(prayer)] = preference.sound.name
                    preferences[Keys.prayerVibration(prayer)] = preference.vibration
                    preferences[Keys.prayerAdvance(prayer)] = preference.advanceMinutes
                }
            }
        }
    }

    private fun Preferences.toNotificationSettings(): PrayerNotificationSettings =
        PrayerNotificationSettings(
            masterEnabled = this[Keys.MASTER_ENABLED]
                ?: PrayerNotificationSettings.DEFAULT.masterEnabled,
            sunriseReminderEnabled = this[Keys.SUNRISE_ENABLED]
                ?: PrayerNotificationSettings.DEFAULT.sunriseReminderEnabled,
            // A lead time read from the store is normalised to one of the offered choices, so a
            // hand-edited or downgraded value cannot put the screen into a state it cannot render.
            sehriReminderEnabled = this[Keys.SEHRI_ENABLED]
                ?: PrayerNotificationSettings.DEFAULT.sehriReminderEnabled,
            sehriLeadMinutes = PrayerNotificationSettings.clampSehriLead(
                this[Keys.SEHRI_LEAD_MINUTES] ?: PrayerNotificationSettings.DEFAULT.sehriLeadMinutes,
            ),
            iftarReminderEnabled = this[Keys.IFTAR_ENABLED]
                ?: PrayerNotificationSettings.DEFAULT.iftarReminderEnabled,
            iftarLeadMinutes = PrayerNotificationSettings.clampIftarLead(
                this[Keys.IFTAR_LEAD_MINUTES] ?: PrayerNotificationSettings.DEFAULT.iftarLeadMinutes,
            ),
            prayers = PrayerName.entries
                .mapNotNull { prayer ->
                    val preference = PrayerNotificationPreference(
                        enabled = this[Keys.prayerEnabled(prayer)]
                            ?: PrayerNotificationPreference.DEFAULT.enabled,
                        sound = NotificationSound.entries
                            .firstOrNull { it.name == this[Keys.prayerSound(prayer)] }
                            ?: PrayerNotificationPreference.DEFAULT.sound,
                        vibration = this[Keys.prayerVibration(prayer)]
                            ?: PrayerNotificationPreference.DEFAULT.vibration,
                        advanceMinutes = this[Keys.prayerAdvance(prayer)]
                            ?: PrayerNotificationPreference.DEFAULT.advanceMinutes,
                    ).withBoundedAdvanceMinutes()

                    if (preference.isDefault) null else prayer to preference
                }
                .toMap(),
        )

    private object Keys {
        val MASTER_ENABLED = booleanPreferencesKey("notification_master_enabled")
        val SUNRISE_ENABLED = booleanPreferencesKey("notification_sunrise_reminder_enabled")
        val SEHRI_ENABLED = booleanPreferencesKey("notification_sehri_reminder_enabled")
        val SEHRI_LEAD_MINUTES = intPreferencesKey("notification_sehri_reminder_lead_minutes")
        val IFTAR_ENABLED = booleanPreferencesKey("notification_iftar_reminder_enabled")
        val IFTAR_LEAD_MINUTES = intPreferencesKey("notification_iftar_reminder_lead_minutes")

        fun prayerEnabled(prayer: PrayerName) = booleanPreferencesKey("notification_${prayer.name}_enabled")
        fun prayerSound(prayer: PrayerName) = stringPreferencesKey("notification_${prayer.name}_sound")
        fun prayerVibration(prayer: PrayerName) = booleanPreferencesKey("notification_${prayer.name}_vibration")
        fun prayerAdvance(prayer: PrayerName) = intPreferencesKey("notification_${prayer.name}_advance_minutes")
    }
}

/** Clamps a stored or requested advance value into the supported range. */
internal fun PrayerNotificationPreference.withBoundedAdvanceMinutes(): PrayerNotificationPreference =
    if (advanceMinutes in 0..PrayerNotificationSettings.MAX_ADVANCE_MINUTES) {
        this
    } else {
        copy(
            advanceMinutes = advanceMinutes.coerceIn(
                minimumValue = 0,
                maximumValue = PrayerNotificationSettings.MAX_ADVANCE_MINUTES,
            ),
        )
    }
