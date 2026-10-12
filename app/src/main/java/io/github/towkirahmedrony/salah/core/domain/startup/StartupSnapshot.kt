package io.github.towkirahmedrony.salah.core.domain.startup

import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.PrayerSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Whether the stored location has been read yet, and what it is.
 *
 * `null` cannot say "not read yet" for a value that is itself nullable, and a read that found
 * nothing configured must never be confused with a read that has not happened: the gate would have
 * to decide from an absence it cannot trust.
 */
sealed interface StoredLocationState {
    /** No read has finished yet, so nothing can be decided from it. */
    data object Reading : StoredLocationState

    /** The read finished; [location] is what is stored, or `null` when nothing is configured. */
    data class Loaded(val location: LocationInfo?) : StoredLocationState
}

/** Whether the prayer settings have been read yet, and what they are. */
sealed interface PrayerSettingsState {
    /** No read has finished yet. */
    data object Reading : PrayerSettingsState

    /** The read finished; [settings] are the values in force, including any defaults. */
    data class Loaded(val settings: PrayerSettings) : PrayerSettingsState
}

/**
 * The Home-critical values Salah reads at start-up, published as hot snapshots.
 *
 * **Why this exists.** Both values come from DataStore, and a cold DataStore flow can only deliver
 * its first value through a dispatch onto the main thread. The first composition owns that thread
 * while it composes, measures and draws the first frame, and a dispatch queued behind that frame
 * cannot run until the frame ends. That is what left the location gate on
 * [io.github.towkirahmedrony.salah.core.domain.location.LocationSetupState.Checking] — and Home on
 * its skeleton — long after the location had in fact been read. Publishing the reads into a
 * [StateFlow] makes them readable *synchronously* the moment the gate and Home first run, so the
 * first composition can already be the real destination carrying real prayer information instead of
 * a placeholder.
 *
 * The snapshot is read once, early, and then kept current by one collector per value, so consumers
 * still react when the user changes the location or the settings. Nothing here writes, and it does
 * not replace the repositories, which stay the source of truth: it is a projection of them, and the
 * cold repository flows remain available to consumers that want to observe changes themselves.
 *
 * The collections are started when this is constructed, which is why the application resolves it
 * during start-up rather than waiting for the first screen that needs it.
 */
class StartupSnapshot(
    storedLocationSource: Flow<LocationInfo?>,
    prayerSettingsSource: Flow<PrayerSettings>,
    scope: CoroutineScope,
) {

    private val storedLocationState = MutableStateFlow<StoredLocationState>(StoredLocationState.Reading)

    private val prayerSettingsState = MutableStateFlow<PrayerSettingsState>(PrayerSettingsState.Reading)

    /** The stored location, or [StoredLocationState.Reading] until it has been read once. */
    val storedLocation: StateFlow<StoredLocationState> = storedLocationState.asStateFlow()

    /** The prayer settings, or [PrayerSettingsState.Reading] until they have been read once. */
    val prayerSettings: StateFlow<PrayerSettingsState> = prayerSettingsState.asStateFlow()

    init {
        scope.launch {
            storedLocationSource.collect { stored ->
                // One assignment carrying both "read" and the value, so a consumer can never observe a
                // finished read paired with a stale value.
                storedLocationState.value = StoredLocationState.Loaded(stored)
            }
        }
        scope.launch {
            prayerSettingsSource.collect { settings ->
                prayerSettingsState.value = PrayerSettingsState.Loaded(settings)
            }
        }
    }

    /**
     * Suspends until the stored location has been read once, then returns it — `null` meaning no
     * location is configured, which is now a definite answer rather than an absence of one.
     */
    suspend fun awaitStoredLocation(): LocationInfo? =
        (storedLocation.first { it is StoredLocationState.Loaded } as StoredLocationState.Loaded).location

    /** Suspends until the prayer settings have been read once, then returns them. */
    suspend fun awaitPrayerSettings(): PrayerSettings =
        (prayerSettings.first { it is PrayerSettingsState.Loaded } as PrayerSettingsState.Loaded).settings
}
