package io.github.towkirahmedrony.salah.data.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationChannelId
import io.github.towkirahmedrony.salah.core.model.NotificationSound

/**
 * Creates the notification channels Salah posts through.
 *
 * **Why there is more than one.** On Android 8.0 (API 26) and later the channel — not the
 * notification — owns the sound and the vibration. Salah's `minSdk` is 26, so per-prayer sound and
 * vibration preferences can only be honoured by routing each reminder to a channel that already
 * carries that pair. The set is therefore the closed cross-product of
 * `NotificationSound × vibration` — six prayer channels — and *not* one channel per prayer: two
 * prayers that share a preference share a channel.
 *
 * **Channel settings are created once and then belong to the user.** After creation Android keeps
 * the user's edits (sound changed, notifications silenced, importance lowered) and an app's
 * `createNotificationChannel` call cannot override them. Salah therefore only ever creates
 * channels, never rewrites them, and the settings screen says so plainly instead of implying that
 * choosing a sound in Salah overrides a choice made in system settings.
 *
 * A seventh channel, [PrayerNotificationChannelId.STATUS], is used for non-urgent Salah messages.
 * It is deliberately low importance and unrelated to prayer reminders, so silencing status
 * messages cannot accidentally silence prayer reminders.
 */
class AndroidNotificationChannels(
    private val context: Context,
) {

    private val notificationManager: NotificationManager?
        get() = ContextCompat.getSystemService(context, NotificationManager::class.java)

    /**
     * Creates every channel. Idempotent, and safe to call on every process start.
     *
     * Salah's own `createNotificationChannel` on an existing channel updates the channel's name
     * and description only; sound, vibration, importance and the user's other choices are left
     * exactly as Android holds them.
     */
    fun ensureCreated() {
        // `minSdk` is 26, so channels always exist: there is no pre-O branch to take.
        val manager = notificationManager ?: return

        NotificationSound.entries.forEach { sound ->
            listOf(true, false).forEach { vibration ->
                manager.createNotificationChannel(prayerChannel(sound, vibration))
            }
        }
        manager.createNotificationChannel(statusChannel())
    }

    private fun prayerChannel(sound: NotificationSound, vibration: Boolean): NotificationChannel {
        val id = PrayerNotificationChannelId.of(sound, vibration)
        // High importance only where there is an audible alert: a reminder the user asked to hear
        // should be able to reach them, while a silent one must not be given a heads-up popup.
        val importance = if (sound.isSilent) {
            NotificationManager.IMPORTANCE_DEFAULT
        } else {
            NotificationManager.IMPORTANCE_HIGH
        }

        return NotificationChannel(id, channelName(sound, vibration), importance).apply {
            description = context.getString(R.string.notification_channel_prayer_description)
            enableVibration(vibration)

            when (sound) {
                NotificationSound.SYSTEM_DEFAULT -> {
                    // The device's own notification sound, resolved live so a change to the
                    // device-wide default is reflected.
                    setSound(
                        Settings.System.DEFAULT_NOTIFICATION_URI,
                        reminderAudioAttributes(),
                    )
                }

                NotificationSound.SALAH_CHIME -> {
                    setSound(chimeUri(), reminderAudioAttributes())
                }

                NotificationSound.SILENT -> {
                    // Explicitly silent. `enableVibration` above still controls the haptic.
                    setSound(null, null)
                }
            }

            // No full-screen intent and no Do Not Disturb bypass: Salah never presents itself over
            // what the user is doing, and never overrides a Do Not Disturb the user configured.
            enableLights(false)
            setShowBadge(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }
    }

    private fun statusChannel(): NotificationChannel {
        val channel = NotificationChannel(
            PrayerNotificationChannelId.STATUS,
            context.getString(R.string.notification_channel_status_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = context.getString(R.string.notification_channel_status_description)
        channel.setSound(null, null)
        channel.enableVibration(false)
        channel.lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        return channel
    }

    /**
     * Human-visible channel name, so the system settings list explains itself.
     *
     * The name states the sound and the vibration the channel carries, which is what lets a user
     * map a channel back to the prayers Salah routes through it.
     */
    private fun channelName(sound: NotificationSound, vibration: Boolean): String {
        val soundName = context.getString(
            when (sound) {
                NotificationSound.SYSTEM_DEFAULT -> R.string.notification_sound_system_default
                NotificationSound.SALAH_CHIME -> R.string.notification_sound_salah_chime
                NotificationSound.SILENT -> R.string.notification_sound_silent
            },
        )
        val vibrationName = context.getString(
            if (vibration) {
                R.string.notification_channel_vibration_on
            } else {
                R.string.notification_channel_vibration_off
            },
        )
        return context.getString(R.string.notification_channel_prayer_name, soundName, vibrationName)
    }

    /** The bundled chime, addressed as an app resource so it needs no storage permission. */
    private fun chimeUri(): Uri = "android.resource://${context.packageName}/${R.raw.salah_chime}".toUri()

    /** Sonification attributes, so the sound respects the notification stream and its volume. */
    private fun reminderAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .build()
}
