package io.github.towkirahmedrony.salah.data.notification

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import androidx.core.net.toUri
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.domain.notification.NotificationSoundPreviewer
import io.github.towkirahmedrony.salah.core.model.NotificationSound

/**
 * Plays the actual sounds the user is choosing between.
 *
 * The preview plays the *same* asset the channel will use — the device's own notification sound for
 * [NotificationSound.SYSTEM_DEFAULT], the bundled chime for [NotificationSound.SALAH_CHIME] — rather
 * than a generic beep, so what the user hears is what they will get. Nothing is invented for the
 * preview, and a preview is clearly a preview: it plays once and never becomes a reminder.
 *
 * One preview at a time. Starting a new preview stops the previous one, so tapping along the list
 * cannot produce overlapping audio, and the resources are released as soon as playback finishes.
 */
class AndroidNotificationSoundPreviewer(
    private val context: Context,
) : NotificationSoundPreviewer {

    private var ringtone: Ringtone? = null
    private var player: MediaPlayer? = null

    override fun preview(sound: NotificationSound): Boolean {
        stopPreview()

        return try {
            when (sound) {
                // Nothing to hear: stopping is the whole preview, and that is a success, not a
                // failure. The UI says "silent", and silent is what the user gets.
                NotificationSound.SILENT -> true

                NotificationSound.SYSTEM_DEFAULT -> previewSystemDefault()

                NotificationSound.SALAH_CHIME -> previewChime()
            }
        } catch (error: Exception) {
            stopPreview()
            false
        }
    }

    override fun stopPreview() {
        ringtone?.let { current ->
            runCatching { if (current.isPlaying) current.stop() }
        }
        ringtone = null

        player?.let { current ->
            runCatching { if (current.isPlaying) current.stop() }
            runCatching { current.release() }
        }
        player = null
    }

    override fun release() = stopPreview()

    /** The device's current notification sound, so a change to the device default is reflected. */
    private fun previewSystemDefault(): Boolean {
        val uri = Settings.System.DEFAULT_NOTIFICATION_URI ?: return false
        val current = RingtoneManager.getRingtone(context, uri) ?: return false
        current.audioAttributes = previewAudioAttributes()
        current.play()
        ringtone = current
        return true
    }

    /** The bundled chime, played once. */
    private fun previewChime(): Boolean {
        val uri: Uri = "android.resource://${context.packageName}/${R.raw.salah_chime}".toUri()
        val current = MediaPlayer().apply {
            setAudioAttributes(previewAudioAttributes())
            setDataSource(context, uri)
            isLooping = false
            prepare()
        }
        current.setOnCompletionListener { stopPreview() }
        current.start()
        player = current
        return true
    }

    private fun previewAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .build()
}
