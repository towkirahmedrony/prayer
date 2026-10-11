package io.github.towkirahmedrony.salah.core.domain.notification

/**
 * What a fired alarm hands to the delivery path.
 *
 * The alarm `Intent` carries [slot] in its data URI (the stable identity) and this payload in a
 * single extra (the values that legitimately change when the schedule is recalculated). Encoding
 * and decoding live here, as pure string work, so the round trip is unit-tested without an
 * Android `Intent` — and so a malformed payload is a `null` rather than a crash in a receiver.
 *
 * The times are carried rather than recalculated because the reminder must report the time the
 * user was actually shown. Recalculating inside a receiver would risk announcing a time that
 * differs from the timetable the user is looking at.
 */
data class PrayerNotificationRequest(
    val slot: PrayerAlarmSlot,
    /** Instant the alarm was scheduled for, in epoch milliseconds. */
    val triggerAtEpochMillis: Long,
    /** The target's own time, in epoch milliseconds. */
    val eventAtEpochMillis: Long,
    /** Minutes before the event this reminder runs; `0` for an at-time reminder. */
    val advanceMinutes: Int,
) {

    /** The `Intent` data URI for this reminder's alarm. Stable per [slot]. */
    val uri: String get() = PrayerNotificationIds.uri(slot)

    /**
     * The extra payload, e.g. `ok=1;trigger=1750000000000;event=1749999000000;advance=10`.
     *
     * The leading `ok=1` is a version marker: a payload Salah does not recognise is rejected
     * instead of being read with the wrong field order.
     */
    fun encode(): String = buildString {
        append(PAYLOAD_MARKER)
        append(SEPARATOR).append("trigger=").append(triggerAtEpochMillis)
        append(SEPARATOR).append("event=").append(eventAtEpochMillis)
        append(SEPARATOR).append("advance=").append(advanceMinutes)
    }

    companion object {

        /** The marker that identifies a payload written by this version of Salah. */
        const val PAYLOAD_MARKER = "salah-prayer-notification-v1"

        /** Field separator. A character that cannot appear in a numeric field. */
        const val SEPARATOR = ';'

        /**
         * Rebuilds a request from a delivered alarm, or returns `null` when the inputs are not a
         * Salah alarm payload. Never throws.
         */
        fun decode(slotUri: String?, payload: String?): PrayerNotificationRequest? {
            val slot = PrayerNotificationIds.slotFromUri(slotUri) ?: return null
            val raw = payload?.trim().orEmpty()
            if (!raw.startsWith(PAYLOAD_MARKER)) return null

            val fields = raw.split(SEPARATOR).drop(1).mapNotNull { field ->
                val separator = field.indexOf('=')
                if (separator <= 0) null else field.take(separator) to field.substring(separator + 1)
            }.toMap()

            val trigger = fields[FIELD_TRIGGER]?.toLongOrNull() ?: return null
            val event = fields[FIELD_EVENT]?.toLongOrNull() ?: return null
            val advance = fields[FIELD_ADVANCE]?.toIntOrNull() ?: return null

            return PrayerNotificationRequest(
                slot = slot,
                triggerAtEpochMillis = trigger,
                eventAtEpochMillis = event,
                advanceMinutes = advance,
            )
        }

        /** Field names of the encoded payload. */
        const val FIELD_TRIGGER = "trigger"
        const val FIELD_EVENT = "event"
        const val FIELD_ADVANCE = "advance"
    }
}
