package io.github.towkirahmedrony.salah.feature.tasbih

/**
 * What the Tasbih screen renders.
 *
 * **Only numbers, fractions and timestamps cross this boundary.** Every sentence the screen shows is
 * resolved by the composable from a string resource, and every value that depends on the interface
 * language — the numerals of the count, the digits of a target, a session's date and time — is formatted
 * here, in a class that needs no Android context. That split is the same one Home and Ramadan use, and it
 * is what lets the whole state be asserted in a unit test with no device: the test reads the strings the
 * screen would read, and the screen adds the words.
 *
 * There are exactly two states, because the screen has exactly two things to say. [Loading] is the moment
 * between the composition and the store's first emission — short, but real, and the reason the counter is
 * not drawn until it passes: a count taken against a session the screen has not read yet would be a count
 * taken against the wrong session.
 */
sealed interface TasbihUiState {

    /**
     * The stored session has not been read yet.
     *
     * Not a spinner: the screen draws the shape of the counter with a skeleton in place of the number, so
     * the first real reading arrives into a layout that already exists. See
     * [io.github.towkirahmedrony.salah.core.designsystem.components.SalahSkeletonBlock].
     */
    data object Loading : TasbihUiState

    /** The session being counted, ready to draw. */
    data class Ready(
        /**
         * The identifier of the session this state was built from.
         *
         * Carried into the view model's writes so an update computed here is only applied to the session it
         * was computed for. See
         * [io.github.towkirahmedrony.salah.core.domain.repository.TasbihRepository.updateActiveSession].
         */
        val sessionId: String,
        /** The current count, in the numerals of the interface language. */
        val countDigits: String,
        /** The target, in the numerals of the interface language. */
        val targetDigits: String,
        /** How far the session has moved towards its target: 0..1, and 1 once it has passed it. */
        val progress: Float,
        /** True once the count has reached the target. Counting on past it keeps this true. */
        val isComplete: Boolean,
        /**
         * True once more has been counted than the target.
         *
         * One bit more than [isComplete], and the reason the screen can say something useful about counting
         * on: "the target was reached" is a fact about a session that stopped the moment it got there, and
         * the user who kept going is owed the sentence that tells them they may.
         */
        val hasCountedPastTarget: Boolean,
        /** Counts still needed to reach the target, in the interface language's numerals. `null` when complete. */
        val remainingDigits: String?,
        /** False only at the counter's ceiling; the increment control is disabled when it is. */
        val canCount: Boolean,
        /** True while there is a count to take back; the decrement control is disabled when it is not. */
        val canUndo: Boolean,
        /** The selected target as a number, for comparing against [targetChoices]. */
        val target: Int,
        /** The targets offered, with the current one marked. Never empty. */
        val targetChoices: List<TasbihTargetChoice>,
        /** Finished sessions, newest first. Empty until one is finished. */
        val history: List<TasbihHistoryRow>,
        /**
         * How many finished sessions the history keeps, in the interface language's numerals, so the screen
         * can state the retention rule in the same script as the counts it is talking about.
         */
        val historyLimitDigits: String,
        /** Whether a count should give haptic feedback. */
        val hapticsEnabled: Boolean,
        /** A transient notice about the last action, or `null`. */
        val message: TasbihMessage?,
    ) : TasbihUiState
}

/**
 * One offered target.
 *
 * [label] is the target in the interface language's numerals, formatted here rather than in the
 * composable, so the chip and the reading above it are written by the same rule.
 */
data class TasbihTargetChoice(
    val target: Int,
    val label: String,
    val selected: Boolean,
)

/**
 * One finished session in the history.
 *
 * [id] is the session's own identifier, used as the row's composition key so a row keeps its identity when
 * the list changes at its head — the case this list is in every time a session is finished.
 */
data class TasbihHistoryRow(
    val id: String,
    /** The count this session finished on, in the interface language's numerals. */
    val countDigits: String,
    /** The target it was counting towards, in the interface language's numerals. */
    val targetDigits: String,
    /** True when the session reached its target before it was finished. */
    val isComplete: Boolean,
    /** The session's start, as a date and a time in the device's own zone. */
    val timestampLabel: String,
)

/**
 * A notice about the last action, or about a failure.
 *
 * Deliberately not a sentence: the screen resolves each value to its own string resource, so the notice
 * is localised with the rest of the screen rather than in the view model.
 */
enum class TasbihMessage {
    /**
     * The store could not be written.
     *
     * Shown instead of silently keeping the count on screen: a reading the disk does not have is a reading
     * that will not be there after a restart, and the user is entitled to know which of the two they are
     * looking at.
     */
    SAVE_FAILED,
}
