package io.github.towkirahmedrony.salah.feature.tasbih

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.towkirahmedrony.salah.core.domain.repository.TasbihRepository
import io.github.towkirahmedrony.salah.core.domain.repository.UserPreferencesRepository
import io.github.towkirahmedrony.salah.core.domain.tasbih.TasbihCounter
import io.github.towkirahmedrony.salah.core.model.TasbihSession
import io.github.towkirahmedrony.salah.core.model.TimeFormatPreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.ZoneId
import java.util.Locale

/**
 * Drives the Tasbih screen.
 *
 * The view model is a *translator*, not a rule book. Every decision about what a count does to a session
 * lives in [TasbihCounter], every decision about what a session looks like lives in [TasbihUiStateFactory],
 * and every decision about what is stored lives in [TasbihRepository]. What is left here, and what this
 * class is responsible for, is the three things neither of those can do: assembling the state the screen
 * reads, turning a user's gesture into the one repository call it means, and reporting a store that could
 * not be written.
 *
 * ## Why the actions do not check before they write
 *
 * `count()` does not read the session, add one and write it back. It hands the repository a *function*,
 * which the repository applies to the session it finds inside its own transaction. The difference is the
 * whole concurrency story of this feature: ten taps arriving faster than the disk can answer are ten
 * functions applied to ten successive stored values, so every one of them lands. A read-then-write would
 * give ten writes of the same number — nine lost counts, and no way for the user to tell.
 *
 * Two consequences follow from that, and both are deliberate:
 *
 *  - the only thing each action reads from the current state is the **session identifier**, so an update
 *    can be attributed to the session it was computed for. It reads nothing else, because nothing else
 *    would be true by the time the write happens;
 *  - the state is *not* optimistically edited in memory. The screen shows what the store holds, one
 *    transaction later. DataStore's writes are serialised and fast, and a second source of truth for the
 *    count is exactly the thing that would eventually disagree with the first.
 *
 * Nothing here can double-count a single tap: each gesture makes exactly one call, and each call makes
 * exactly one increment. An increment cannot be applied twice by a replay, because there is no replay —
 * the function is invoked once, inside the transaction, against the value it is given.
 *
 * ## Failures
 *
 * A store that cannot be written is reported, not swallowed: the message reaches the screen as
 * [TasbihMessage.SAVE_FAILED] and the reading on screen is then known to be one the disk does not have.
 * The alternative — a count that silently evaporates — is the failure a user would only discover after a
 * restart, when the number they were relying on is gone.
 */
class TasbihViewModel(
    private val repository: TasbihRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val uiStateFactory: TasbihUiStateFactory,
    /**
     * The clock the history is rendered against — nothing else reads it. Every timestamp the *store* writes
     * is taken from the repository's own clock inside its transaction, so this one cannot drift from the
     * values it is describing.
     */
    private val clock: Clock = Clock.systemUTC(),
    /**
     * The zone a session's start is rendered in: the device's own, because a counting session belongs to
     * the person holding the phone rather than to the configured location. See [TasbihUiStateFactory].
     */
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private val message = MutableStateFlow<TasbihMessage?>(null)

    /** The stored counter, grouped so the three values reach [uiState] as one reading. */
    private val stored: Flow<StoredCounter> = combine(
        repository.activeSession,
        repository.history,
        repository.hapticsEnabled,
    ) { session, history, haptics -> StoredCounter(session, history, haptics) }

    /**
     * The two preferences that decide how the state is *written* rather than what it contains.
     *
     * Grouped separately because they change for reasons unrelated to counting — switching the interface
     * language, or the timetable's clock format — and a change to either must re-render the same session
     * rather than re-read it.
     */
    private val display: Flow<DisplaySettings> = combine(
        userPreferencesRepository.appLanguage,
        userPreferencesRepository.prayerSettings,
    ) { language, prayerSettings ->
        DisplaySettings(
            // `null` is the "follow the device" choice, which is what the pre-selector behaviour was and
            // what an unset preference must still mean.
            locale = language.locale() ?: Locale.getDefault(),
            timeFormat = prayerSettings.timeFormat,
        )
    }

    /**
     * What the screen draws.
     *
     * Starts at [TasbihUiState.Loading] and stays there until the store's first emission arrives. That is
     * not a cosmetic state: the screen does not draw the count surface until it clears, which is what stops
     * a tap landing against a session whose identifier is not yet known.
     *
     * `WhileSubscribed` rather than `Eagerly`: the flows are read while the screen is on screen and for a
     * short grace period after, which is enough to survive a configuration change without re-reading the
     * store, and no longer. `stateIn` keeps the last value, so returning to the screen inside that window
     * shows the count immediately rather than flashing the skeleton again.
     */
    val uiState: StateFlow<TasbihUiState> = combine(stored, display, message) { counter, settings, current ->
        uiStateFactory.create(
            session = counter.session,
            history = counter.history,
            hapticsEnabled = counter.hapticsEnabled,
            locale = settings.locale,
            timeFormat = settings.timeFormat,
            zone = zone,
            message = current,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MILLIS),
        initialValue = TasbihUiState.Loading,
    )

    // --- Counting ---------------------------------------------------------------

    /**
     * Records one count.
     *
     * The increment is the gesture's whole meaning, so this does exactly one thing: it hands
     * [TasbihCounter.counted] to the store. Reaching the target is not an event here — it is a property of
     * the session the screen reads, so the notice it draws cannot go missing if a write is slow.
     */
    fun count() = updateSession { session, nowMillis -> TasbihCounter.counted(session, nowMillis) }

    /** Takes back the last count. A no-op on a session with nothing counted. */
    fun undo() = updateSession { session, nowMillis -> TasbihCounter.undone(session, nowMillis) }

    /**
     * Clears the count of the session in progress, keeping its identity and its target.
     *
     * The confirmation that must precede this is the *screen's*, because it is the screen that knows what
     * the user can see and what they are about to lose; by the time this is called, the decision is made.
     */
    fun reset() = updateSession { session, nowMillis -> TasbihCounter.reset(session, nowMillis) }

    /**
     * Points the session at [target].
     *
     * The count is untouched: which target a number is measured against is a separate fact from the number.
     * The screen offers the targets; this does not check that [target] is one of them, because the
     * counter's own range check is the boundary and a target from anywhere else is still a target.
     */
    fun setTarget(target: Int) =
        updateSession { session, nowMillis -> TasbihCounter.retargeted(session, target, nowMillis) }

    /**
     * Archives the session in progress and begins a new one.
     *
     * Does not go through [updateSession] on purpose. This is not a change to the session being counted —
     * it is the counter moving to a different session — and the archive and the new session are written in
     * one transaction by the repository, so there is no transition in which both are half-done. The
     * confirmation in front of this is the screen's; see `TasbihScreen`.
     */
    fun startNewSession() {
        message.value = null
        write { repository.startNewSession() }
    }

    /** Empties the history. The session in progress is not touched — see [TasbihRepository.clearHistory]. */
    fun clearHistory() {
        message.value = null
        write { repository.clearHistory() }
    }

    /**
     * Turns the counter's haptic feedback on or off.
     *
     * A preference rather than a gesture veto, so it is written like any other setting and the count that
     * follows it is unaffected either way: the counter counts identically with haptics off, and a device
     * with no vibrator behaves the same as one with haptics switched off.
     */
    fun setHapticsEnabled(enabled: Boolean) {
        message.value = null
        write { repository.setHapticsEnabled(enabled) }
    }

    // --- Internals --------------------------------------------------------------

    /**
     * Applies [transform] to the session the screen is currently showing.
     *
     * The identifier comes from the current state and nothing else does, which is the point: it is the one
     * value that must be *this* session's, and it is checked by the repository before the transform runs.
     * While the store has not been read yet there is no session to attribute a count to, so the call is
     * dropped rather than guessed at — and the screen cannot produce one in that window anyway, because it
     * does not draw the controls until the state is ready.
     */
    private fun updateSession(transform: (TasbihSession, Long) -> TasbihSession) {
        val sessionId = (uiState.value as? TasbihUiState.Ready)?.sessionId ?: return
        message.value = null
        write { repository.updateActiveSession(sessionId, transform) }
    }

    /** Runs one write, turning a failure into the screen's own notice. */
    private fun write(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (cancellation: CancellationException) {
                // The screen went away mid-write. That is not a failure to report — there is no screen left
                // to report it to — and swallowing it would break structured cancellation.
                throw cancellation
            } catch (error: Exception) {
                message.value = TasbihMessage.SAVE_FAILED
            }
        }
    }

    private data class StoredCounter(
        val session: TasbihSession,
        val history: List<TasbihSession>,
        val hapticsEnabled: Boolean,
    )

    private data class DisplaySettings(
        val locale: Locale,
        val timeFormat: TimeFormatPreference,
    )

    private companion object {
        /** How long the state stays live after the screen stops observing it. */
        const val SUBSCRIBE_TIMEOUT_MILLIS = 5_000L
    }
}
