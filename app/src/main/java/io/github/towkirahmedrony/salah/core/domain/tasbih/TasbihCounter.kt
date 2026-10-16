package io.github.towkirahmedrony.salah.core.domain.tasbih

import io.github.towkirahmedrony.salah.core.model.TasbihSession

/**
 * Every operation the Tasbih counter performs on a session.
 *
 * Pure, total and free of Android: each function takes a session and a moment and returns a new
 * session, so the whole counting policy is decided here and nowhere else. The view model calls these
 * functions, the data layer stores their results, and the tests assert them directly — which means the
 * rules the feature promises (a count never goes negative, counting past the target is allowed,
 * resetting is not the same as starting a new session) are one set of functions rather than a set of
 * behaviours spread across a screen, a view model and a serializer.
 *
 * ## Why the moment is a parameter
 *
 * Nothing here reads a clock. `startedAtMillis` and `updatedAtMillis` are decided by the caller, which
 * is what makes a session's timestamps assertable in a test instead of merely plausible. Production
 * passes the time the write is actually made — the moment the count is applied inside the store's own
 * transaction — rather than the moment the tap was seen, so "last updated" cannot claim a moment
 * before the value it describes was written.
 *
 * ## What is deliberately *not* random here
 *
 * A new session's identifier is supplied by the caller. The data layer generates it (the only place a
 * random value enters the feature), so an archived session and the session that replaced it are two
 * different rows in the history without this class ever having to invent an id.
 */
object TasbihCounter {

    /**
     * [session] with one more count recorded.
     *
     * The whole of "counting". Three rules live in this function:
     *
     *  - the count is capped at [TasbihSession.MAX_COUNT], and the cap is checked *before* the addition
     *    rather than applied after it, so the addition can never wrap. A count that is already at the
     *    cap is returned unchanged rather than overflowing to a negative number;
     *  - the target's completion time is stamped the *first* time the count reaches the target and is
     *    left alone afterwards, so counting on past the target does not rewrite when it was met;
     *  - counting on past the target is allowed. Nothing here stops, blocks or resets the count once
     *    [TasbihSession.isComplete] is true — reaching a target is a milestone the screen announces, not
     *    a limit it imposes.
     */
    fun counted(session: TasbihSession, nowMillis: Long): TasbihSession {
        // Returned unchanged rather than merely uncounted: the count did not move, so "last updated"
        // must not move either, and the caller can skip the write entirely.
        if (!session.canCount) return session
        val counted = session.copy(
            count = session.count + 1,
            updatedAtMillis = later(session.updatedAtMillis, nowMillis),
        )
        return if (counted.isComplete && counted.completedAtMillis == null) {
            counted.copy(completedAtMillis = counted.updatedAtMillis)
        } else {
            counted
        }
    }

    /**
     * [session] with one count removed, for a tap the user did not mean.
     *
     * Never goes below zero: a session with nothing counted is returned unchanged. Dropping back below
     * the target clears [TasbihSession.completedAtMillis], because the session is then no longer a
     * completed one — and leaving a completion time on an incomplete session is the kind of stale value
     * that would make the history lie.
     *
     * The increment is its inverse for every value except the completion stamp: counting up and then
     * back down returns the same count, the same target and the same start time, and leaves the session
     * incomplete again, which is the pair of facts a user who mis-tapped expects.
     */
    fun undone(session: TasbihSession, nowMillis: Long): TasbihSession {
        if (session.count <= 0) return session
        val reduced = session.copy(
            count = session.count - 1,
            updatedAtMillis = later(session.updatedAtMillis, nowMillis),
        )
        return if (reduced.isComplete) reduced else reduced.copy(completedAtMillis = null)
    }

    /**
     * [session] with its count back to zero, keeping its target and its identity.
     *
     * **A reset is not a new session.** The session keeps its id and its target and begins again from
     * the moment of the reset, so nothing is archived and the history is untouched — which is what the
     * confirmation in front of the action is protecting: a count the user did mean, not a record they
     * had kept.
     */
    fun reset(session: TasbihSession, nowMillis: Long): TasbihSession = session.copy(
        count = 0,
        startedAtMillis = nowMillis,
        updatedAtMillis = nowMillis,
        completedAtMillis = null,
    )

    /**
     * [session] pointed at [target] instead.
     *
     * The count is kept exactly as it was, because changing the target changes what the count is
     * measured against, not what has been counted. The target is brought into range through
     * [TasbihSession.clampTarget], so no caller can store a target the picker cannot represent.
     *
     * Completion is then re-derived rather than copied: lowering the target below the current count
     * completes the session (and stamps the moment it did so), and raising it above the current count
     * un-completes it.
     */
    fun retargeted(session: TasbihSession, target: Int, nowMillis: Long): TasbihSession {
        val updated = session.copy(
            target = TasbihSession.clampTarget(target),
            updatedAtMillis = later(session.updatedAtMillis, nowMillis),
        )
        return when {
            updated.isComplete && updated.completedAtMillis == null ->
                updated.copy(completedAtMillis = updated.updatedAtMillis)

            !updated.isComplete -> updated.copy(completedAtMillis = null)

            else -> updated
        }
    }

    /**
     * The session that replaces [previous]: a new identifier, no count, and [previous]'s target.
     *
     * The target is carried over deliberately. It is the one setting a user changes once and expects to
     * persist across sessions; making them re-pick it after every reset would be a small tax on the one
     * action this feature exists for. The shipped default is used only when the previous session has
     * none to carry, which [normalized][TasbihSession.normalized] makes impossible — so in practice the
     * user's own last choice always wins.
     */
    fun restarted(previous: TasbihSession, id: String, nowMillis: Long): TasbihSession = TasbihSession(
        id = id,
        count = 0,
        target = previous.target,
        startedAtMillis = nowMillis,
        updatedAtMillis = nowMillis,
        completedAtMillis = null,
    )

    /**
     * The copy of [session] that belongs in the history, or `null` when nothing should be archived.
     *
     * A session with a count of zero is not history: it records that the user opened the screen and
     * started a session without counting, which would pad the list with rows that say nothing. Every
     * session with a count — complete or not — is archived, because an unfinished count is something the
     * user did, and dropping it would be the accidental deletion the new-session action exists to avoid.
     */
    fun archivable(session: TasbihSession): TasbihSession? = if (session.count > 0) session else null

    /**
     * [history] with the retention policy applied: newest first, at most
     * [TasbihSession.HISTORY_LIMIT] entries.
     *
     * The order is the stored order — newest first — rather than a sort, so a session whose timestamps
     * tie with another keeps the position it was archived in, and the list a user sees cannot reorder
     * itself between two reads of the same stored value. `sortedByDescending` would reorder ties on
     * every read; taking a prefix does not.
     */
    fun retained(history: List<TasbihSession>): List<TasbihSession> =
        history.take(TasbihSession.HISTORY_LIMIT)

    /**
     * The later of [current] and [nowMillis].
     *
     * A monotonic guard, not a formatting nicety. The clock a device reports can step backwards — a
     * time-zone change, a manual correction, a network-synchronised clock — and without this an update
     * would record a "last updated" moment before the session started, so the history's timestamps would
     * read as nonsense and `updatedAtMillis >= startedAtMillis` would stop holding. Taking the later of
     * the two keeps every timestamp ordered even when the clock is not.
     */
    private fun later(current: Long, nowMillis: Long): Long = if (nowMillis > current) nowMillis else current
}
