package io.github.towkirahmedrony.salah.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.towkirahmedrony.salah.core.domain.dashboard.DashboardConfig
import io.github.towkirahmedrony.salah.core.domain.repository.UserPreferencesRepository
import io.github.towkirahmedrony.salah.core.model.AppLanguage
import io.github.towkirahmedrony.salah.core.model.AsrCalculationMethod
import io.github.towkirahmedrony.salah.core.model.CalendarSettings
import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import io.github.towkirahmedrony.salah.core.model.DateLanguagePreference
import io.github.towkirahmedrony.salah.core.model.HighLatitudeRule
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationMethod
import io.github.towkirahmedrony.salah.core.model.PrayerConfigOverrides
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerSettings
import io.github.towkirahmedrony.salah.core.model.ThemeMode
import io.github.towkirahmedrony.salah.core.model.TimeFormatPreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Local, offline user preferences.
 *
 * This is the only class in Salah that writes user settings, and it writes all of them: the
 * theme, the prayer-calculation configuration and the time format share one DataStore file, so
 * prayer settings cannot end up in a second store that could disagree with the first.
 *
 * Unknown or out-of-range stored values fall back to a safe default instead of failing, so a
 * downgraded or corrupted store cannot break the app and cannot put the calculator into a state
 * the settings screen cannot represent.
 */
class DataStoreUserPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) : UserPreferencesRepository {

    override val themeMode: Flow<ThemeMode> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { preferences ->
            val stored = preferences[Keys.THEME_MODE]
            ThemeMode.entries.firstOrNull { it.name == stored } ?: ThemeMode.SYSTEM
        }

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { preferences -> preferences[Keys.THEME_MODE] = mode.name }
    }

    // --- Interface language -----------------------------------------------------

    override val appLanguage: Flow<AppLanguage> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { preferences ->
            AppLanguage.fromIdentifier(preferences[Keys.APP_LANGUAGE])
        }

    override suspend fun setAppLanguage(language: AppLanguage) {
        // One key, so selecting a language cannot rewrite the theme, the calculation settings or the
        // calendar settings that share this store. The edit itself throws on a store that cannot be
        // written, which is what lets the settings screen avoid claiming a change it did not make.
        dataStore.edit { preferences -> preferences[Keys.APP_LANGUAGE] = language.name }
    }

    override val prayerSettings: Flow<PrayerSettings> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { preferences -> preferences.toPrayerSettings() }

    override suspend fun updatePrayerSettings(transform: (PrayerSettings) -> PrayerSettings) {
        // Read-modify-write inside one transaction: concurrent updates cannot lose each other,
        // and no cached copy of the settings can be written back over a newer one.
        dataStore.edit { preferences ->
            // Freeze the inferred overrides on the first write, before the settings change.
            //
            // A store written before this feature existed has no override key, so the owned fields are
            // inferred from the settings. If they were only inferred on read, a remote value written into
            // an unowned field could later be mis-read as a user's own choice (it would differ from the
            // default). Materialising the inference here, from the state before this write, keeps the
            // boundary between "the user chose this" and "a remote default supplied it" fixed.
            if (!preferences.contains(Keys.PRAYER_CONFIG_OVERRIDES)) {
                preferences[Keys.PRAYER_CONFIG_OVERRIDES] =
                    PrayerConfigOverrides.deriving(preferences.toPrayerSettings()).encode()
            }
            val updated = transform(preferences.toPrayerSettings())
            preferences[Keys.CALCULATION_METHOD] = updated.method.name
            preferences[Keys.ASR_METHOD] = updated.asrMethod.name
            preferences[Keys.HIGH_LATITUDE_RULE] = updated.highLatitudeRule.name
            preferences[Keys.TIME_FORMAT] = updated.timeFormat.name
            PrayerName.entries.forEach { prayer ->
                val minutes = updated.adjustmentFor(prayer)
                if (minutes == 0) {
                    preferences.remove(Keys.adjustment(prayer))
                } else {
                    preferences[Keys.adjustment(prayer)] = minutes
                }
            }
        }
    }

    // --- Prayer-configuration overrides -----------------------------------------

    /**
     * Which calculation fields the user owns.
     *
     * The key is read only when it is present; when it is absent — a store written before remote
     * configuration existed — the owned fields are inferred from the stored settings. That inference
     * preserves any value the user had changed while still allowing a remote default to supply a field that
     * is still at the bundled default. See [PrayerConfigOverrides.deriving].
     */
    override val prayerConfigOverrides: Flow<PrayerConfigOverrides> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { preferences -> preferences.toPrayerConfigOverrides() }

    override suspend fun updatePrayerConfigOverrides(
        transform: (PrayerConfigOverrides) -> PrayerConfigOverrides,
    ) {
        dataStore.edit { preferences ->
            val updated = transform(preferences.toPrayerConfigOverrides())
            preferences[Keys.PRAYER_CONFIG_OVERRIDES] = updated.encode()
        }
    }

    /**
     * Reads the owned calculation fields, inferring them for a store that predates the key.
     *
     * `contains` distinguishes "absent" (infer) from "present but empty" (the user has reset everything),
     * which is the difference between adopting a remote default and honouring an explicit reset.
     */
    private fun Preferences.toPrayerConfigOverrides(): PrayerConfigOverrides =
        if (contains(Keys.PRAYER_CONFIG_OVERRIDES)) {
            PrayerConfigOverrides.decode(this[Keys.PRAYER_CONFIG_OVERRIDES])
        } else {
            PrayerConfigOverrides.deriving(toPrayerSettings())
        }

    // --- Calendar ---------------------------------------------------------------

    override val calendarSettings: Flow<CalendarSettings> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { preferences -> preferences.toCalendarSettings() }

    override suspend fun updateCalendarSettings(transform: (CalendarSettings) -> CalendarSettings) {
        dataStore.edit { preferences ->
            val updated = transform(preferences.toCalendarSettings())
            preferences[Keys.PREFERRED_CALENDAR] = updated.preferredSystem.name
            preferences[Keys.DATE_LANGUAGE] = updated.dateLanguage.name
            // The offset is clamped on write as well as on read, so no caller can store a value the
            // calendar screen cannot represent.
            preferences[Keys.HIJRI_ADJUSTMENT_DAYS] = CalendarSettings.clampHijriAdjustment(
                updated.hijriAdjustmentDays,
            )
            // The precautionary Imsak interval: a configured interval is clamped to an offered value
            // and stored, and a cleared one removes the key so "no Imsak rule" is representable and
            // distinguishable from "a rule of zero minutes".
            val imsak = updated.imsakMinutesBeforeFajr
            if (imsak == null) {
                preferences.remove(Keys.IMSAK_MINUTES_BEFORE_FAJR)
            } else {
                preferences[Keys.IMSAK_MINUTES_BEFORE_FAJR] = CalendarSettings.clampImsak(imsak)
            }
        }
    }

    /**
     * Reads the calendar preferences, falling back to the documented defaults.
     *
     * An unknown calendar system or language name, or an out-of-range offset, is replaced by the
     * default instead of throwing: a store written by another version of Salah must never make the
     * calendar screens unusable.
     */
    private fun Preferences.toCalendarSettings(): CalendarSettings = CalendarSettings(
        preferredSystem = CalendarSystem.entries
            .firstOrNull { it.name == this[Keys.PREFERRED_CALENDAR] }
            ?: CalendarSettings.DEFAULT.preferredSystem,
        hijriAdjustmentDays = CalendarSettings.clampHijriAdjustment(
            this[Keys.HIJRI_ADJUSTMENT_DAYS] ?: CalendarSettings.DEFAULT.hijriAdjustmentDays,
        ),
        dateLanguage = DateLanguagePreference.entries
            .firstOrNull { it.name == this[Keys.DATE_LANGUAGE] }
            ?: CalendarSettings.DEFAULT.dateLanguage,
        // Absent means "no Imsak rule configured"; a present value is normalised to an offered choice.
        imsakMinutesBeforeFajr = this[Keys.IMSAK_MINUTES_BEFORE_FAJR]
            ?.let(CalendarSettings::clampImsak),
    )

    private fun Preferences.toPrayerSettings(): PrayerSettings = PrayerSettings(
        method = PrayerCalculationMethod.entries
            .firstOrNull { it.name == this[Keys.CALCULATION_METHOD] }
            ?: PrayerCalculationMethod.DEFAULT,
        asrMethod = AsrCalculationMethod.entries
            .firstOrNull { it.name == this[Keys.ASR_METHOD] }
            ?: PrayerSettings.DEFAULT.asrMethod,
        highLatitudeRule = HighLatitudeRule.entries
            .firstOrNull { it.name == this[Keys.HIGH_LATITUDE_RULE] }
            ?: PrayerSettings.DEFAULT.highLatitudeRule,
        timeFormat = TimeFormatPreference.entries
            .firstOrNull { it.name == this[Keys.TIME_FORMAT] }
            ?: PrayerSettings.DEFAULT.timeFormat,
        adjustmentsMinutes = PrayerName.entries
            .mapNotNull { prayer ->
                // Clamped on read as well as on write, so a hand-edited or corrupted store can
                // never move a prayer time by an unsupported amount.
                val stored = this[Keys.adjustment(prayer)] ?: return@mapNotNull null
                val clamped = PrayerSettings.clampAdjustment(stored)
                if (clamped == 0) null else prayer to clamped
            }
            .toMap(),
    )

    // --- Home dashboard layout --------------------------------------------------

    /**
     * The Home dashboard layout, normalised on read.
     *
     * Nothing here is allowed to fail on a store this build does not understand: an unknown card id
     * is dropped, a duplicate is removed, a card introduced since the layout was written is appended,
     * and an absent layout reads as the documented default. An absent order reads as the default
     * order and an absent hidden set as the default hidden set, so a fresh install opens on the
     * documented arrangement rather than on an empty dashboard.
     */
    override val dashboardConfig: Flow<DashboardConfig> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { preferences -> preferences.toDashboardConfig() }

    override suspend fun updateDashboardConfig(transform: (DashboardConfig) -> DashboardConfig) {
        dataStore.edit { preferences ->
            val updated = transform(preferences.toDashboardConfig()).normalized()
            preferences[Keys.DASHBOARD_CONFIG_VERSION] = updated.version
            preferences[Keys.DASHBOARD_CARD_ORDER] = updated.order.joinToString(CARD_ID_SEPARATOR)
            // Sorted so the stored value is stable: the same layout always serialises to the same
            // bytes, which keeps an unchanged layout from producing a difference later.
            preferences[Keys.DASHBOARD_HIDDEN_CARDS] =
                updated.hidden.sorted().joinToString(CARD_ID_SEPARATOR)
        }
    }

    private fun Preferences.toDashboardConfig(): DashboardConfig = DashboardConfig(
        version = this[Keys.DASHBOARD_CONFIG_VERSION] ?: DashboardConfig.CURRENT_VERSION,
        order = this[Keys.DASHBOARD_CARD_ORDER]?.toCardIds() ?: DashboardConfig.DEFAULT.order,
        hidden = this[Keys.DASHBOARD_HIDDEN_CARDS]?.toCardIds()?.toSet() ?: DashboardConfig.DEFAULT.hidden,
    ).normalized()

    private fun String.toCardIds(): List<String> =
        split(CARD_ID_SEPARATOR).filter { id -> id.isNotBlank() }

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val CALCULATION_METHOD = stringPreferencesKey("prayer_calculation_method")
        val ASR_METHOD = stringPreferencesKey("prayer_asr_method")
        val HIGH_LATITUDE_RULE = stringPreferencesKey("prayer_high_latitude_rule")
        val TIME_FORMAT = stringPreferencesKey("time_format")
        val PRAYER_CONFIG_OVERRIDES = stringPreferencesKey("prayer_config_overrides")
        val PREFERRED_CALENDAR = stringPreferencesKey("calendar_preferred_system")
        val HIJRI_ADJUSTMENT_DAYS = intPreferencesKey("calendar_hijri_adjustment_days")
        val DATE_LANGUAGE = stringPreferencesKey("calendar_date_language")
        val IMSAK_MINUTES_BEFORE_FAJR = intPreferencesKey("calendar_imsak_minutes_before_fajr")
        val DASHBOARD_CONFIG_VERSION = intPreferencesKey("dashboard_config_version")
        val DASHBOARD_CARD_ORDER = stringPreferencesKey("dashboard_card_order")
        val DASHBOARD_HIDDEN_CARDS = stringPreferencesKey("dashboard_hidden_cards")

        fun adjustment(prayer: PrayerName) = intPreferencesKey("prayer_adjustment_${prayer.name}")
    }

    private companion object {
        /** Separates card ids within the one stored string. Never appears inside an id. */
        const val CARD_ID_SEPARATOR = ","
    }
}
