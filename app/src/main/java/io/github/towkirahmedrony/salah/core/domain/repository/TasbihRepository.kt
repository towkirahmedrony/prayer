package io.github.towkirahmedrony.salah.core.domain.repository

import io.github.towkirahmedrony.salah.core.model.TasbihSession
import kotlinx.coroutines.flow.Flow

/**
 * The Tasbih counter's local state: the session being counted, the history of finished ones, and the
 * counter's own haptics preference.
 *
 * ## One session, one key, one writer
 *
 * The store holds exactly one active session. That is the whole of the "one session cannot overwrite
 * another" guarantee at this level: there is a single slot for the session being counted, and a session
 * that is finished moves to the history rather than being overwritten where it stood. Archiving the old
 * session and installing the new one happen in **one** transaction ([startNewSession]), so the counter
 * can never be observed with a finished session missing from both places.
 *
 * ## Every write is a transform, never a value
 *
 * There is deliberately no `setActiveSession(session)`. A caller that read a session and wrote it back
 * would be able to write a value computed from an older state over a newer one — which is precisely the
 * failure a fast sequence of counts produces, because each tap reads, adds one, and writes. Instead
 * every mutation is handed a function and applies it to whatever is stored at the moment of the write,
 * inside the store's own transaction. Two counts that interleave therefore both land, in either order,
 * and no count is lost.
 *
 * ## [updateActiveSession] and the expected identifier
 *
 * The transform is only applied when the stored session is the one the caller believed it was
 * transforming. An update computed for a session that has since been replaced is dropped rather than
 * applied to its successor, so a count that was in flight while a new session started cannot leak into
 * it. The identity check is a parameter rather than a convention because a convention cannot be tested.
 *
 * ## Failures
 *
 * Writes are not swallowed: a store that cannot be written throws, and the view model turns that into
 * the screen's own "could not save" message. Reporting success for a count that was not stored is worse
 * than reporting the failure, because the user's next action would be built on a number that is not
 * there.
 *
 * ## Scope
 *
 * Local and offline, like every other store in Salah: no account, no network, no synchronisation. See
 * `docs/TASBIH_COUNTER.md` §5.
 */
interface TasbihRepository {

    /**
     * The session currently being counted.
     *
     * Always emits a value: a fresh install reads [TasbihSession.unstarted] rather than `null`, so the
     * screen never has to render a counter that does not exist. The emitted session is always
     * [TasbihSession.normalized], so no stored payload can reach the screen out of range.
     */
    val activeSession: Flow<TasbihSession>

    /**
     * The finished sessions, newest first, at most [TasbihSession.HISTORY_LIMIT] of them.
     *
     * Emits an empty list rather than `null` while nothing has been finished. Ordered by when each
     * session was archived, not re-sorted on read, so the list cannot reorder itself between two reads of
     * the same stored value.
     */
    val history: Flow<List<TasbihSession>>

    /**
     * Whether a count should give haptic feedback.
     *
     * A preference of the counter rather than of the app: it is stored beside the session in the same
     * single DataStore, under the counter's own key, so that turning it off cannot disturb the theme,
     * the calculation settings, the location or the reminder settings — and so that a restore of one
     * setting cannot carry another with it. Defaults to on; the screen offers it as a switch, and
     * counting works identically with it off.
     */
    val hapticsEnabled: Flow<Boolean>

    /**
     * Applies [transform] to the stored session, in one transaction with the read it is computed from.
     *
     * [expectedId] is the identifier of the session the caller was rendering. When the stored session
     * carries a different one, the write is dropped and nothing changes: the caller's count belonged to a
     * session that is no longer active.
     *
     * [transform]'s second argument is the moment the write is being applied, read from the store's own
     * clock inside the transaction. It is passed in rather than read by the caller because the policy in
     * [io.github.towkirahmedrony.salah.core.domain.tasbih.TasbihCounter] is a pure function of a session
     * and a moment, and the *actual* moment of the write is the one that belongs on the session's
     * "last updated" stamp.
     */
    suspend fun updateActiveSession(
        expectedId: String,
        transform: (TasbihSession, Long) -> TasbihSession,
    )

    /**
     * Archives the session being counted, when it has something to archive, and begins a new one.
     *
     * One transaction, so the history and the active session move together. The new session keeps the
     * previous target and takes a freshly generated identifier; a previous session with a count of zero is
     * not archived at all (see
     * [io.github.towkirahmedrony.salah.core.domain.tasbih.TasbihCounter.archivable]). The history is
     * trimmed to its retention limit as part of the same write.
     */
    suspend fun startNewSession()

    /**
     * Empties the history.
     *
     * **The active session is not touched**, and not because of ordering luck: the write names the
     * history key and no other. Clearing what has been counted before must never discard the count in
     * progress, which is the one thing the user would not expect to lose.
     */
    suspend fun clearHistory()

    /** Stores the haptics preference. Writes exactly one key. */
    suspend fun setHapticsEnabled(enabled: Boolean)
}
