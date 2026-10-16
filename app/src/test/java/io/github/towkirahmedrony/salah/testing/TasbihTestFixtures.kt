package io.github.towkirahmedrony.salah.testing

import io.github.towkirahmedrony.salah.core.domain.repository.TasbihRepository
import io.github.towkirahmedrony.salah.core.domain.tasbih.TasbihCounter
import io.github.towkirahmedrony.salah.core.model.TasbihSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** The moment the fake applies a write at, so a test can assert a timestamp instead of a bound on one. */
const val FAKE_TASBIH_WRITE_MILLIS: Long = 1_000L

/**
 * A session built from literals, including combinations the store would never produce.
 *
 * Deliberately not normalised: the counter's and the codec's tests need to hand a function a session that
 * is out of range to prove it is repaired, and a builder that quietly fixed the value first would make
 * those tests vacuous. Pass [TasbihSession.normalized] explicitly when a valid session is what is wanted.
 */
fun tasbihSession(
    id: String = "session-1",
    count: Int = 0,
    target: Int = TasbihSession.DEFAULT_TARGET,
    startedAtMillis: Long = 0L,
    updatedAtMillis: Long = startedAtMillis,
    completedAtMillis: Long? = null,
): TasbihSession = TasbihSession(
    id = id,
    count = count,
    target = target,
    startedAtMillis = startedAtMillis,
    updatedAtMillis = updatedAtMillis,
    completedAtMillis = completedAtMillis,
)

/**
 * An in-memory [TasbihRepository] for the view model's tests.
 *
 * It holds one session and one history in state flows and applies the same policy the real store does —
 * the transforms come from [TasbihCounter] and nothing is applied to a session whose identifier does not
 * match — so a test of the view model exercises the view model's decisions rather than a second
 * implementation of the rules. `DataStoreTasbihRepositoryTest` is where the *real* store's behaviour,
 * including its transactions, is asserted.
 *
 * [writeTimeMillis] is fixed rather than taken from a clock, so a test can assert an exact timestamp. That
 * is only sound because the fake is never asked to do anything whose result depends on real elapsed time.
 */
class FakeTasbihRepository(
    initialSession: TasbihSession = TasbihSession.unstarted(nowMillis = 0L),
    initialHistory: List<TasbihSession> = emptyList(),
    initialHapticsEnabled: Boolean = true,
    /** The identifier [startNewSession] gives the new session, so a test can name what it expects. */
    private val newSessionId: String = "session-next",
    private val writeTimeMillis: Long = FAKE_TASBIH_WRITE_MILLIS,
) : TasbihRepository {

    private val sessionState = MutableStateFlow(initialSession.normalized())
    private val historyState = MutableStateFlow(initialHistory)
    private val hapticsState = MutableStateFlow(initialHapticsEnabled)

    /** Every session a write produced, in order, so a test can assert what the view model computed. */
    val writtenSessions: MutableList<TasbihSession> = mutableListOf()

    /** How many writes were attempted, whether or not they were applied. */
    var writeAttempts: Int = 0
        private set

    /**
     * When set, the next write throws this instead of storing anything, which models a store that cannot be
     * written. Clear it to let the following write succeed.
     */
    var writeFailure: Throwable? = null

    override val activeSession: Flow<TasbihSession> = sessionState

    override val history: Flow<List<TasbihSession>> = historyState

    override val hapticsEnabled: Flow<Boolean> = hapticsState

    /** The session as it now stands, for an assertion that does not have to go through a flow. */
    val session: TasbihSession get() = sessionState.value

    /** The history as it now stands. */
    val storedHistory: List<TasbihSession> get() = historyState.value

    override suspend fun updateActiveSession(
        expectedId: String,
        transform: (TasbihSession, Long) -> TasbihSession,
    ) {
        writeAttempts += 1
        writeFailure?.let { failure -> throw failure }

        val stored = sessionState.value
        // The real store's rule: an update computed for a session that is no longer active is dropped.
        if (stored.id != expectedId) return

        val updated = transform(stored, writeTimeMillis).normalized()
        writtenSessions += updated
        sessionState.value = updated
    }

    override suspend fun startNewSession() {
        writeAttempts += 1
        writeFailure?.let { failure -> throw failure }

        val current = sessionState.value
        TasbihCounter.archivable(current)?.let { archived ->
            historyState.value = TasbihCounter.retained(listOf(archived) + historyState.value)
        }
        sessionState.value = TasbihCounter.restarted(current, newSessionId, writeTimeMillis).normalized()
    }

    override suspend fun clearHistory() {
        writeAttempts += 1
        writeFailure?.let { failure -> throw failure }
        historyState.value = emptyList()
    }

    override suspend fun setHapticsEnabled(enabled: Boolean) {
        writeAttempts += 1
        writeFailure?.let { failure -> throw failure }
        hapticsState.value = enabled
    }
}
