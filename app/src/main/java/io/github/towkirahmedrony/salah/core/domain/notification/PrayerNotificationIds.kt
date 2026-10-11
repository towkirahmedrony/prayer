package io.github.towkirahmedrony.salah.core.domain.notification

import java.time.LocalDate

/**
 * Deterministic identities for every scheduled prayer reminder.
 *
 * There are three identities per slot, and all three are derived from the same
 * ([date][PrayerAlarmSlot.date], [target][PrayerAlarmSlot.target], [kind][PrayerAlarmSlot.kind])
 * triple by pure functions. Nothing random, nothing time-dependent, nothing stored:
 *
 *  1. [requestCode] — the `PendingIntent` request code used to schedule and cancel the alarm.
 *  2. [notificationId] — the `NotificationManager` id, applied together with [tag] so a
 *     re-delivery of the same slot can never produce a second visible notification.
 *  3. [uri] — the alarm `Intent`'s data, which carries the slot.
 *
 * **Why a URI as well as a request code.** `PendingIntent` equality ignores extras and compares
 * the `Intent`'s action, data, type, class and categories. Two alarms that differ only in extras
 * would therefore be treated as the same `PendingIntent` and the second would silently overwrite
 * the first. Giving every slot a unique data URI makes the intents genuinely distinct.
 *
 * **Why the URI holds the slot and not the times.** The data URI is part of the `PendingIntent`
 * identity, so it must stay *stable* for a slot. If it also carried the planned times, then
 * rescheduling the same slot for a moved prayer time would create a second, different
 * `PendingIntent`, orphaning the first alarm instead of replacing it — a duplicate-wakeup bug.
 * The times therefore travel in the intent's **extras** (see [PrayerNotificationRequest]), which
 * `FLAG_UPDATE_CURRENT` refreshes in place, and the receiver still recovers the slot from the
 * delivered intent alone.
 *
 * **Why the request code is collision-free.** The code is
 * `floorMod(epochDay, 1_000_000) * 16 + target.ordinal * 2 + kind.ordinal`. Within any window of
 * `1_000_000` days (about 2 700 years) every `(target, kind)` pair gets a distinct 4-bit block,
 * and the planner only ever plans a window of a few days. `ReminderTarget` has 6 constants and
 * `PrayerReminderKind` has 2, so the block size of 16 leaves headroom for future targets without
 * changing the scheme. The whole value stays far below `Int.MAX_VALUE`.
 */
object PrayerNotificationIds {

    /** Number of `(target, kind)` combinations reserved per day. Must stay a power of two. */
    private const val BLOCK_SIZE = 16

    /** Days after which a request code would repeat — about 2 700 years. */
    private const val EPOCH_MODULUS = 1_000_000

    /** The `PendingIntent` request code for [slot]. */
    fun requestCode(slot: PrayerAlarmSlot): Int =
        dayBlock(slot.date) + slot.target.ordinal * PrayerReminderKind.entries.size + slot.kind.ordinal

    /** The `NotificationManager` id for [slot]. See [tag] for the other half of the identity. */
    fun notificationId(slot: PrayerAlarmSlot): Int = requestCode(slot)

    /**
     * The `NotificationManager` tag for [slot].
     *
     * Posting under a stable tag *and* a stable id means Android replaces the existing
     * notification instead of adding a second one, which is what makes duplicate alarms harmless
     * even if one were ever created.
     */
    fun tag(slot: PrayerAlarmSlot): String = slot.identity

    /** The unique data URI that identifies [slot] as an alarm intent. */
    fun uri(slot: PrayerAlarmSlot): String = "${SCHEME}://$AUTHORITY/${uriPath(slot)}"

    /** The path part of [uri], used to build and parse the alarm identity. */
    internal fun uriPath(slot: PrayerAlarmSlot): String =
        "${slot.date.toEpochDay()}/${slot.target.name}/${slot.kind.name}"

    /**
     * Rebuilds the slot from an alarm URI, or returns `null` when [uri] is not one of ours.
     *
     * Parsing is total and total-on-failure: an unrecognised, truncated or hand-crafted URI
     * yields `null` rather than an exception, so a malformed broadcast can never crash the
     * receiver. Nothing here is guessed — a target or kind that is not a known constant is a
     * rejection, not a fallback.
     */
    fun slotFromUri(uri: String?): PrayerAlarmSlot? {
        val value = uri?.trim().orEmpty()
        val prefix = "$SCHEME://$AUTHORITY/"
        if (!value.startsWith(prefix)) return null

        val parts = value.removePrefix(prefix).split('/')
        if (parts.size != URI_PATH_SEGMENTS) return null

        val epochDay = parts[0].toLongOrNull() ?: return null
        val target = ReminderTarget.entries.firstOrNull { it.name == parts[1] } ?: return null
        val kind = PrayerReminderKind.entries.firstOrNull { it.name == parts[2] } ?: return null

        return PrayerAlarmSlot(date = LocalDate.ofEpochDay(epochDay), target = target, kind = kind)
    }

    /** `epochDay/target/kind` — three segments, no more, no less. */
    private const val URI_PATH_SEGMENTS = 3

    private fun dayBlock(date: LocalDate): Int =
        (date.toEpochDay().mod(EPOCH_MODULUS.toLong()).toInt()) * BLOCK_SIZE

    /** Scheme of the alarm data URI. */
    const val SCHEME = "salah"

    /** Authority of the alarm data URI. */
    const val AUTHORITY = "prayer-notification"
}
