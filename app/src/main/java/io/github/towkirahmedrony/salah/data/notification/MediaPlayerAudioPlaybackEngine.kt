package io.github.towkirahmedrony.salah.data.notification

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.core.net.toUri
import androidx.core.content.ContextCompat
import io.github.towkirahmedrony.salah.core.domain.notification.AudioPlaybackEngine

/**
 * [AudioPlaybackEngine] over the framework `MediaPlayer`.
 *
 * No audio library is added: `MediaPlayer` is part of Android, it plays a bundled `res/raw` asset
 * or a local file without any storage permission, and it is what a single short-to-medium recording
 * needs. A streaming player would be the wrong tool — Salah's audio is local by design.
 *
 * The lifecycle rules the Azan subsystem depends on are implemented here:
 *
 *  - **One player, replaced not stacked.** [start] releases any previous player before creating
 *    the next, so a repeated call cannot leak a second `MediaPlayer` and play two streams at once.
 *  - **Audio focus is requested and abandoned.** Playback takes transient focus with ducking, so
 *    the user's music or podcast is lowered rather than silenced and then restored. Focus is
 *    abandoned on [stop] and in `onCompletion`.
 *  - **Never loops.** `isLooping` stays `false`. An Azan repeated forever is not a behaviour a user
 *    can be assumed to want, and it is a genuine battery and annoyance hazard.
 *  - **Never restarts itself.** `onError` releases the player and stops; it does not silently
 *    retry, so a corrupt asset fails once and visibly.
 *  - **Never throws.** A missing or corrupt resource makes [start] return `false`, which the
 *    subsystem reports as a failure. The prayer notification itself is unaffected — the reminder is
 *    delivered whether or not the audio works.
 *
 * **Not exercised at runtime today.** Because [BundledAzanAudioSource] resolves to `null`, this
 * engine is never asked to play anything in the shipped build, and its device behaviour has not
 * been verified on hardware. That limitation is stated in `docs/PRAYER_NOTIFICATIONS.md` §5
 * rather than being presented as tested.
 */
class MediaPlayerAudioPlaybackEngine(
    private val context: Context,
) : AudioPlaybackEngine {

    private val audioManager: AudioManager?
        get() = ContextCompat.getSystemService(context, AudioManager::class.java)

    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus: Boolean = false

    override fun isPlaying(): Boolean = player?.isPlaying == true

    override fun start(source: String): Boolean {
        // Replace rather than stack: a previous player is released first, so two Azans can never
        // overlap even if a caller misses the single-instance rule in AzanPlaybackManager.
        releasePlayer()

        return try {
            val created = MediaPlayer().apply {
                setAudioAttributes(azanAudioAttributes())
                setDataSource(context, source.toUri())
                isLooping = false
                prepare()
            }
            created.setOnCompletionListener { stop() }
            created.setOnErrorListener { _, _, _ ->
                // Release and stop; never retry silently.
                stop()
                true
            }
            requestFocusIfPossible()
            created.start()
            player = created
            true
        } catch (error: Exception) {
            // Includes IOException for a missing resource and IllegalStateException for a corrupt
            // one. A failed Azan must never take the notification path down with it.
            releasePlayer()
            false
        }
    }

    override fun stop() {
        releasePlayer()
        abandonFocusIfHeld()
    }

    override fun release() {
        stop()
    }

    private fun releasePlayer() {
        val current = player ?: return
        player = null
        runCatching { if (current.isPlaying) current.stop() }
        current.reset()
        current.release()
    }

    /**
     * Takes transient focus with ducking when the platform supports it.
     *
     * Focus is best-effort: if another app holds it, the Azan still plays, because a prayer alert
     * is exactly the kind of audio a user expects to interrupt. What this buys is that a podcast
     * the user is listening to is ducked rather than talked over at full volume.
     */
    private fun requestFocusIfPossible() {
        val manager = audioManager ?: return
        try {
            // The focus-request API is 26+ and `minSdk` is 26, so the deprecated
            // listener-based overload is never needed.
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(azanAudioAttributes())
                .build()
            focusRequest = request
            hasFocus = manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (error: Exception) {
            hasFocus = false
        }
    }

    private fun abandonFocusIfHeld() {
        if (!hasFocus) return
        hasFocus = false
        val manager = audioManager ?: return
        runCatching { focusRequest?.let(manager::abandonAudioFocusRequest) }
    }

    /** Sonification attributes: the Azan plays on the notification stream, not the media stream. */
    private fun azanAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
        .build()
}
