package io.github.towkirahmedrony.salah.core.domain.dashboard

import io.github.towkirahmedrony.salah.core.model.DashboardCard

/**
 * The user's Home-dashboard layout: which cards are shown and in what order.
 *
 * A value object rather than many booleans, because the whole layout is written and read as one
 * preference group and is compared as a whole by the view model before anything is persisted. Its
 * only collaborators are [DashboardCard]'s stable ids, so it is pure Kotlin and is unit-tested
 * directly.
 *
 * **[normalized] is the recovery rule.** A layout written by an older build, edited by hand or
 * corrupted can reference a card that no longer exists, name the same card twice, omit a card that
 * has since been introduced or carry a version this build does not know. Normalising resolves all
 * of that without ever failing:
 *
 *  - identifiers are kept in their saved order, with duplicates removed and unknown identifiers
 *    dropped (a removed card simply disappears);
 *  - any supported card the saved order does not mention is appended in its declaration order, so a
 *    newly introduced card appears without moving the cards around it;
 *  - the hidden set is intersected with the supported cards, so a hidden identifier for a card that
 *    no longer exists cannot suppress anything;
 *  - the version is stamped to [CURRENT_VERSION].
 *
 * It is idempotent — normalising an already-normal layout returns `this` — which is what lets the
 * view model skip a write when a change produced no difference.
 *
 * **Independence.** The layout is its own preference group. Resetting it ([resetToDefault]) resets
 * nothing else: the prayer calculation settings, the selected location, the interface language and
 * the reminder settings live in different keys and are never read or written here.
 */
data class DashboardConfig(
    /**
     * The layout format version.
     *
     * Stored so a future change to the format can be recognised and migrated rather than guessed at.
     * A layout whose stored version or shape is not understood is normalised rather than rejected,
     * so no version here can make the dashboard unusable.
     */
    val version: Int = CURRENT_VERSION,
    /** Every supported card's id, in the order the user arranged them. */
    val order: List<String> = DashboardCard.entries.map { card -> card.id },
    /** The ids of the cards the user has hidden. */
    val hidden: Set<String> = DashboardCard.entries
        .filterNot { card -> card.defaultEnabled }
        .map { card -> card.id }
        .toSet(),
) {

    /** True when [card] is both present in the order and not hidden. */
    fun isEnabled(card: DashboardCard): Boolean = card.id in order && card.id !in hidden

    /**
     * Shows or hides one card, without changing its position.
     *
     * Enabling a card that the order somehow does not mention appends it rather than dropping it, so
     * toggling a card on can never produce a layout that still does not contain it.
     */
    fun withCardEnabled(card: DashboardCard, enabled: Boolean): DashboardConfig {
        val base = normalized()
        val id = card.id
        val nextOrder = if (enabled && id !in base.order) base.order + id else base.order
        val nextHidden = if (enabled) base.hidden - id else base.hidden + id
        return DashboardConfig(version = CURRENT_VERSION, order = nextOrder, hidden = nextHidden)
    }

    /**
     * Moves one card [delta] places within the order, clamped to the ends.
     *
     * A move past either end is a no-op rather than wrapping around, which is what makes the
     * management list's up/down controls behave the way a list is expected to. A card the order does
     * not mention, or a zero move, is left alone.
     */
    fun moved(card: DashboardCard, delta: Int): DashboardConfig {
        val base = normalized()
        val index = base.order.indexOf(card.id)
        if (index < 0 || delta == 0) return base
        val target = (index + delta).coerceIn(0, base.order.lastIndex)
        if (target == index) return base
        val reordered = base.order.toMutableList()
        reordered.removeAt(index)
        reordered.add(target, card.id)
        return DashboardConfig(version = CURRENT_VERSION, order = reordered, hidden = base.hidden)
    }

    /** The default layout. Persisted on its own; resetting it touches no other preference. */
    fun resetToDefault(): DashboardConfig = DEFAULT

    /** Every supported card, in the saved order. */
    fun orderedCards(): List<DashboardCard> =
        normalized().order.mapNotNull(DashboardCard::fromId)

    /** The cards the dashboard should actually render, in the saved order. */
    fun enabledCards(): List<DashboardCard> =
        orderedCards().filter { card -> isEnabled(card) }

    /**
     * A layout that references only supported cards, in a stable order, with a known version.
     *
     * Returns `this` when nothing needed correcting, so a caller can compare a change against the
     * stored value and skip a write that would store exactly what is already there.
     */
    fun normalized(): DashboardConfig {
        val supportedIds = DashboardCard.entries.map { card -> card.id }
        val supported = supportedIds.toSet()

        val seen = LinkedHashSet<String>(supportedIds.size)
        val ordered = ArrayList<String>(supportedIds.size)
        order.forEach { id ->
            if (id in supported && seen.add(id)) ordered.add(id)
        }
        supportedIds.forEach { id ->
            if (seen.add(id)) ordered.add(id)
        }

        val normalizedHidden = hidden.filterTo(LinkedHashSet()) { id -> id in supported }

        if (version == CURRENT_VERSION && ordered == order && normalizedHidden == hidden) return this
        return DashboardConfig(version = CURRENT_VERSION, order = ordered, hidden = normalizedHidden)
    }

    companion object {
        /** The layout format this build writes. */
        const val CURRENT_VERSION: Int = 1

        /** The default layout a fresh install starts on. */
        val DEFAULT: DashboardConfig = DashboardConfig()
    }
}
