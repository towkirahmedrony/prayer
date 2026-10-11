package io.github.towkirahmedrony.salah.core.domain.notification

import io.github.towkirahmedrony.salah.core.model.NotificationSound

/**
 * Derives the notification channel a reminder belongs to from the sound and vibration the user
 * chose.
 *
 * **Why this exists at all.** Before Android 8.0 (API 26) an app set the sound and vibration on
 * each notification. From API 26 onwards the **channel** owns them, and a notification's own
 * `setSound`/`setVibrate` are ignored. Salah's `minSdk` is 26, so the channel is the only
 * mechanism available — and supporting a per-prayer sound and vibration preference therefore
 * requires one channel per (sound, vibration) combination.
 *
 * That is the concrete requirement the plan asked for, and it is why this is a small, bounded,
 * *derived* set rather than a channel per prayer: two prayers that share a sound and a vibration
 * preference share a channel, and the total is
 * `3 sounds × 2 vibration states = 6` regardless of how many prayers exist.
 *
 * Channel ids are stable strings, because Android remembers a channel's settings by id: renaming
 * one would silently reset the user's customisations. They are built from the enum names so that
 * adding a sound adds a channel without touching the existing ones.
 */
object PrayerNotificationChannelId {

    /** Prefix shared by every prayer-reminder channel. */
    const val PREFIX = "salah_prayer_"

    /** The channel used for non-urgent Salah status messages, not for prayer reminders. */
    const val STATUS = "salah_status"

    /** The channel id for a reminder announced with [sound] and [vibration]. */
    fun of(sound: NotificationSound, vibration: Boolean): String =
        PREFIX + sound.name.lowercase() + if (vibration) "_vibrate" else "_quiet"

    /** Every channel a prayer reminder can be routed to. */
    val prayerChannels: List<String> by lazy {
        NotificationSound.entries.flatMap { sound ->
            listOf(of(sound, vibration = true), of(sound, vibration = false))
        }
    }
}
