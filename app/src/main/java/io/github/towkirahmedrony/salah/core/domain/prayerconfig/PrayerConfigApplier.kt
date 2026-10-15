package io.github.towkirahmedrony.salah.core.domain.prayerconfig

import io.github.towkirahmedrony.salah.core.domain.DiagnosticsLogger
import io.github.towkirahmedrony.salah.core.domain.SalahDiagnosticEvents
import io.github.towkirahmedrony.salah.core.domain.repository.LocationRepository
import io.github.towkirahmedrony.salah.core.domain.repository.UserPreferencesRepository
import io.github.towkirahmedrony.salah.core.model.PrayerConfigOverrides
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * Turns the active remote configuration and the user's explicit choices into the calculation settings the
 * rest of the app already reads.
 *
 * **Why this exists rather than a second settings object.** Every consumer of prayer settings — the Home
 * screen, the notification coordinator, the startup warm-up — reads
 * [io.github.towkirahmedrony.salah.core.model.PrayerSettings] from the one DataStore. Rather than teach
 * each of them about remote configuration, this class resolves the effective profile once (through
 * [PrayerConfigResolver]) and writes the non-overridden fields back into that same settings object. The
 * existing read sites then need no change, and the existing engine consumes exactly the
 * [io.github.towkirahmedrony.salah.core.model.PrayerCalculationProfile] it always did.
 *
 * **It never overwrites a field the user owns.** A field recorded in [PrayerConfigOverrides] keeps the
 * user's value; only the unowned fields take the base value from the applicable default. The time format is
 * not a calculation input and is never touched, and neither are the theme, the calendar settings or the
 * stored location — this class writes exactly one preference group.
 *
 * [active] is published hot so the settings screen can show where the effective configuration came from
 * without reading the store on every recomposition.
 */
class PrayerConfigApplier(
    private val resolver: PrayerConfigResolver,
    private val userPreferences: UserPreferencesRepository,
    private val store: ActivePrayerConfigStore,
    private val locations: LocationRepository,
    private val logger: DiagnosticsLogger,
) {

    private val _active = MutableStateFlow<ActivePrayerConfig?>(null)

    /** The active remote configuration, or `null` when none is stored. Updated on every [apply] and [refresh]. */
    val active: StateFlow<ActivePrayerConfig?> = _active.asStateFlow()

    /**
     * Where the effective calculation profile currently comes from, recomputed whenever any input moves.
     *
     * Combines the stored settings, the owned fields, the saved area and the active configuration, so the
     * settings screen can say "published district configuration", "user override" or "bundled default"
     * without reading the store on every recomposition and without ever touching raw configuration content.
     */
    val origin: Flow<PrayerConfigOrigin> = combine(
        userPreferences.prayerSettings,
        userPreferences.prayerConfigOverrides,
        locations.location,
        _active,
    ) { settings, overrides, location, active ->
        resolver.resolve(active?.snapshot, settings, overrides, location?.area).origin
    }

    /** Loads the stored configuration into [active] without touching any preference. */
    suspend fun refresh() {
        _active.value = runCatching { store.active() }.getOrNull()
    }

    /**
     * Recomputes the effective calculation settings and writes the non-overridden fields back.
     *
     * Reads the stored configuration, the saved area and the user's overrides, resolves once, and applies
     * the result inside the repository's own read-modify-write transaction so a concurrent change cannot be
     * lost. Adopting a remote revision, resetting an override and the app's start-up path all flow through
     * here, so the notification coordinator's later reconciliation plans against the value that was written.
     */
    suspend fun apply() {
        val active = runCatching { store.active() }.getOrNull()
        _active.value = active
        val area = runCatching { locations.location.first() }.getOrNull()?.area
        val overrides = runCatching { userPreferences.prayerConfigOverrides.first() }
            .getOrDefault(PrayerConfigOverrides.NONE)

        var origin = PrayerConfigOrigin.BUNDLED
        userPreferences.updatePrayerSettings { current ->
            val resolved = resolver.resolve(active?.snapshot, current, overrides, area)
            origin = resolved.origin
            current.copy(
                method = resolved.profile.method,
                asrMethod = resolved.profile.asrMethod,
                highLatitudeRule = resolved.profile.highLatitudeRule,
                adjustmentsMinutes = resolved.profile.adjustmentsMinutes,
            )
        }

        logger.info(
            SalahDiagnosticEvents.PRAYER_CONFIG_EFFECTIVE_APPLIED,
            mapOf(
                "origin" to origin.kind.name.lowercase(),
                "hasRemote" to (active != null),
                "overrides" to overrides.fields.size,
            ),
        )
    }

    /** Releases [field] back to the applicable default and reapplies. */
    suspend fun resetField(field: PrayerConfigOverrides.Field) {
        userPreferences.updatePrayerConfigOverrides { it.without(field) }
        logger.info(SalahDiagnosticEvents.PRAYER_CONFIG_OVERRIDE_RESET, mapOf("field" to field.name))
        apply()
    }

    /** Releases every calculation field back to the applicable default and reapplies. */
    suspend fun resetAll() {
        userPreferences.updatePrayerConfigOverrides { it.cleared() }
        logger.info(SalahDiagnosticEvents.PRAYER_CONFIG_OVERRIDE_RESET, mapOf("field" to "ALL"))
        apply()
    }
}
