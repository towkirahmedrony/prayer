package io.github.towkirahmedrony.salah.data.tasbih

import io.github.towkirahmedrony.salah.core.model.TasbihSession

/**
 * Turns a [TasbihSession] into one string and back.
 *
 * ## Why a delimited line rather than JSON
 *
 * Salah has no JSON library on the classpath (its one hand-written parser exists for reading published
 * datasets, which are genuinely nested documents), and the Home dashboard already stores its layout as a
 * delimited string in the same DataStore. A session is six flat fields, so it uses the same shape for the
 * same reasons: the stored value is one preference, it round-trips without a parser, and nothing about
 * reading it depends on a library's behaviour on the JVM versus on Android.
 *
 * ## Why the delimiters are safe
 *
 * [FIELD_SEPARATOR] and [ENTRY_SEPARATOR] are the only two characters the format reserves. Identifiers
 * are generated as UUIDs, which contain neither, and every other field is a decimal integer. A payload
 * whose identifier *does* contain a delimiter could not have been written by this codec — it is
 * hand-edited or from a different writer — so [decode] rejects it rather than guessing where the fields
 * were meant to end. Rejecting is the safe direction: a session that cannot be read is reported as
 * absent, and the counter starts a fresh one, which is recoverable. A session read with the wrong count
 * would be indistinguishable from the truth.
 *
 * ## Why decoding cannot fail the app
 *
 * Both decoders are total: [decode] answers `null` for anything it cannot read, and [decodeHistory]
 * drops the entries it cannot read while keeping the ones it can. A store written by a different version
 * of Salah, or edited by hand, therefore costs the user at most the entries that were damaged — never the
 * screen, and never the app. Whatever is recovered is passed through
 * [TasbihSession.normalized], so a readable-but-out-of-range payload is corrected rather than trusted.
 *
 * ## Absolute times
 *
 * Both timestamps are epoch milliseconds, not wall-clock text: they are exact, they sort correctly
 * without a format, and the screen decides which zone and script to render them in. A stored local time
 * would be wrong the moment the device changed zone.
 */
object TasbihSessionCodec {

    /** Separates a session's fields. Never appears inside a field this codec writes. */
    const val FIELD_SEPARATOR: String = "|"

    /** Separates sessions within the stored history. Never appears inside a field this codec writes. */
    const val ENTRY_SEPARATOR: String = ";"

    /** How many fields a session has. A payload with any other number is rejected, not padded. */
    private const val FIELD_COUNT = 6

    /**
     * The one encoding of "this session has not reached its target", so absence round-trips exactly.
     *
     * A visible token rather than an empty field, on purpose: an empty final field would make the format's
     * field count depend on how a split treats a trailing separator, and a format whose arity rests on a
     * string-splitting subtlety is one refactor away from silently losing every incomplete session. `-` is
     * not a number, so it can never be confused with a completion time.
     */
    private const val NO_COMPLETION = "-"

    /**
     * [session] as one string.
     *
     * The value is normalised first, so this codec can only ever write a session the rest of the app can
     * represent — which is what keeps a corrupt in-memory value from reaching the disk through a write.
     */
    fun encode(session: TasbihSession): String {
        val safe = session.normalized()
        return listOf(
            safe.id,
            safe.count.toString(),
            safe.target.toString(),
            safe.startedAtMillis.toString(),
            safe.updatedAtMillis.toString(),
            safe.completedAtMillis?.toString() ?: NO_COMPLETION,
        ).joinToString(FIELD_SEPARATOR)
    }

    /** The session [encoded] holds, or `null` when it cannot be read as one. */
    fun decode(encoded: String?): TasbihSession? {
        val fields = encoded?.split(FIELD_SEPARATOR) ?: return null
        if (fields.size != FIELD_COUNT) return null

        val id = fields[0]
        // An identifier carrying a delimiter could not have been produced by this codec, and an empty one
        // would be an identifier two sessions could share.
        if (id.isBlank() || id.contains(ENTRY_SEPARATOR)) return null

        val count = fields[1].toIntOrNull() ?: return null
        val target = fields[2].toIntOrNull() ?: return null
        val startedAt = fields[3].toLongOrNull() ?: return null
        val updatedAt = fields[4].toLongOrNull() ?: return null
        val completedAt = if (fields[5] == NO_COMPLETION) null else fields[5].toLongOrNull() ?: return null

        return TasbihSession(
            id = id,
            count = count,
            target = target,
            startedAtMillis = startedAt,
            updatedAtMillis = updatedAt,
            completedAtMillis = completedAt,
        ).normalized()
    }

    /** [history] as one string. An empty history encodes to the empty string, which is not a session. */
    fun encodeHistory(history: List<TasbihSession>): String =
        history.joinToString(ENTRY_SEPARATOR) { session -> encode(session) }

    /**
     * The sessions [encoded] holds, newest first, with the unreadable ones dropped.
     *
     * Order is preserved rather than re-sorted: the stored order *is* newest-first, and a sort would
     * reorder entries whose timestamps tie.
     */
    fun decodeHistory(encoded: String?): List<TasbihSession> {
        if (encoded.isNullOrBlank()) return emptyList()
        return encoded
            .split(ENTRY_SEPARATOR)
            .mapNotNull { entry -> decode(entry) }
    }
}
