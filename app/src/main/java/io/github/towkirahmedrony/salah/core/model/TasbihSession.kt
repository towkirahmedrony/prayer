package io.github.towkirahmedrony.salah.core.model

/**
 * One counting session of the Tasbih counter.
 *
 * A session is the unit the counter works in, not the count: it carries its own identity, its own
 * target and its own two timestamps, so a session can be archived into the history and a new one
 * started without the two ever sharing a value. Nothing here is derived from the clock or from a
 * random source — deciding *when* a session started and *which* identifier it got is the caller's
 * job — which is what keeps the type constructible in a test with literal values.
 *
 * ## The invariants, and where they are enforced
 *
 * [normalized] is the single place the type's invariants are restored, and every way in produces a
 * normalised value:
 *
 *  - `count` is between 0 and [MAX_COUNT]. It can never be negative, because the storage layer reads
 *    the value through [clampCount] and the counter never decrements below zero — and the upper bound
 *    is what makes integer overflow unrepresentable rather than merely unlikely.
 *  - `target` is between [MIN_TARGET] and [MAX_TARGET], through [clampTarget].
 *  - `completedAtMillis` is present exactly when [isComplete]. A store that says a session was
 *    completed but carries no time (a hand-edited file, or a build that predates the field) is given
 *    the session's own last update as the moment it was completed, rather than being reported as
 *    incomplete when the count says otherwise.
 *  - `updatedAtMillis` is never before `startedAtMillis`, so "last updated" cannot read as earlier
 *    than the session it belongs to.
 *
 * ## What a session does *not* hold
 *
 * No dhikr text, no count of sets and no user identity. Salah does not carry religious content for
 * this feature (see `docs/TASBIH_COUNTER.md` §1), and the counter is a counter: it records how many
 * times the user tapped, not what they said.
 */
data class TasbihSession(
    /** Stable identifier of this session. Unique within the stored history. */
    val id: String,
    /** How many times the user has counted in this session. Never negative. */
    val count: Int,
    /** The count this session is counting towards. */
    val target: Int,
    /** When this session was started, in epoch milliseconds. */
    val startedAtMillis: Long,
    /** When the count last changed, in epoch milliseconds. */
    val updatedAtMillis: Long,
    /**
     * When the count first reached [target], or `null` while it has not.
     *
     * Kept as the *first* time the target was reached: counting on past the target does not move it,
     * because the moment the target was met is a fact about the session that later taps cannot change.
     * It becomes `null` again only if the count falls back below the target or the session is reset.
     */
    val completedAtMillis: Long?,
) {

    /** True once the count has reached the target. Counting on past it keeps this true. */
    val isComplete: Boolean get() = count >= target

    /**
     * How far the session has moved towards its target, as a fraction between 0 and 1.
     *
     * Clamped, so a count past the target reads as a full bar rather than as an over-full one, and a
     * target of zero cannot divide by zero. The bar is the same shape whether or not the user keeps
     * counting, which is the point: passing the target changes the *message*, not the geometry.
     */
    val progress: Float
        get() = if (target <= 0) 0f else (count.toFloat() / target.toFloat()).coerceIn(0f, 1f)

    /** Counts still needed to reach the target, or 0 once it has been reached. */
    val remainingToTarget: Int get() = (target - count).coerceAtLeast(0)

    /** True while another count can still be recorded — false only at [MAX_COUNT]. */
    val canCount: Boolean get() = count < MAX_COUNT

    /**
     * This session with every invariant restored.
     *
     * Idempotent, so applying it to an already-valid session returns an equal value, and total, so no
     * stored payload can produce a session the rest of the app cannot represent. A blank identifier is
     * replaced by [DEFAULT_SESSION_ID] rather than left empty, because an empty id is one that two
     * sessions could share — and two sessions sharing an id is exactly what the history must not
     * contain.
     */
    fun normalized(): TasbihSession {
        val safeStarted = startedAtMillis
        val safeCount = clampCount(count)
        val safeTarget = clampTarget(target)
        val safeUpdated = if (updatedAtMillis < safeStarted) safeStarted else updatedAtMillis
        val complete = safeCount >= safeTarget
        return copy(
            id = id.ifBlank { DEFAULT_SESSION_ID },
            count = safeCount,
            target = safeTarget,
            startedAtMillis = safeStarted,
            updatedAtMillis = safeUpdated,
            // Present exactly when the count says the target was reached: a stored session that
            // contradicts its own count is repaired towards the count, which is the value the screen
            // shows and the value the user produced. A stored completion *before* the session started is
            // not a moment this session can have had, so it is replaced rather than believed.
            completedAtMillis = when {
                !complete -> null
                completedAtMillis == null || completedAtMillis < safeStarted -> safeUpdated
                else -> completedAtMillis
            },
        )
    }

    companion object {

        /**
         * The target a session is given when Salah has no reason to use another one.
         *
         * It is Salah's shipped default and nothing more: the counter makes no claim about how many
         * times anything should be said, and the user can change it to any value in
         * [TARGET_CHOICES] at any time. It is deliberately the first entry of that list, so "the
         * default" and "the first choice offered" cannot drift apart.
         */
        const val DEFAULT_TARGET: Int = 33

        /** The smallest target the counter accepts. A target of zero would make progress undefined. */
        const val MIN_TARGET: Int = 1

        /**
         * The largest target the counter accepts.
         *
         * A bound rather than an absence of one, so a target is always a value the target picker can
         * represent and the progress fraction can divide by without losing meaning.
         */
        const val MAX_TARGET: Int = 100_000

        /**
         * The largest count the counter will store.
         *
         * Reaching it is not a realistic outcome of tapping a phone — it is the bound that makes
         * integer overflow unrepresentable. `count + 1` is only ever taken from a value below this
         * constant, so the addition cannot wrap, and the reading stays within six digits, which is what
         * lets it be drawn on one line at the largest supported font scale (see `TasbihScreen`).
         */
        const val MAX_COUNT: Int = 999_999

        /**
         * The identifier of the session that has not been written yet.
         *
         * A constant rather than a generated value on purpose. The store's read side and its write side
         * both stand in this identifier for "no session is stored", so the first count of a fresh
         * install is written to the session the screen was actually showing instead of being discarded
         * as an update for a session that is not there. See
         * [io.github.towkirahmedrony.salah.data.tasbih.DataStoreTasbihRepository].
         */
        const val DEFAULT_SESSION_ID: String = "unstarted"

        /**
         * How many finished sessions the history keeps.
         *
         * A documented retention policy rather than an unbounded list: the store holds the [HISTORY_LIMIT]
         * most recently finished sessions and drops the oldest beyond that. It bounds both the size of
         * the one stored string and the length of the list the screen draws, and it is
         * [io.github.towkirahmedrony.salah.core.domain.tasbih.TasbihCounter.retained] that applies it —
         * nothing else trims the history, so the policy has exactly one implementation.
         */
        const val HISTORY_LIMIT: Int = 30

        /**
         * The targets the counter offers, in the order it offers them.
         *
         * The shipped set. A session whose stored target is not in this list — a value written by an
         * older build, or restored from a backup — is still shown and still counted towards; the picker
         * adds it as its own choice rather than silently rewriting the user's number. See
         * [io.github.towkirahmedrony.salah.feature.tasbih.TasbihUiStateFactory].
         */
        val TARGET_CHOICES: List<Int> = listOf(33, 99, 100, 500, 1000)

        /** The session a fresh install begins in, before the user has counted anything. */
        fun unstarted(nowMillis: Long, id: String = DEFAULT_SESSION_ID): TasbihSession = TasbihSession(
            id = id,
            count = 0,
            target = DEFAULT_TARGET,
            startedAtMillis = nowMillis,
            updatedAtMillis = nowMillis,
            completedAtMillis = null,
        )

        /** [value] brought into the range a target may hold. */
        fun clampTarget(value: Int): Int = value.coerceIn(MIN_TARGET, MAX_TARGET)

        /** [value] brought into the range a count may hold. */
        fun clampCount(value: Int): Int = value.coerceIn(0, MAX_COUNT)
    }
}
