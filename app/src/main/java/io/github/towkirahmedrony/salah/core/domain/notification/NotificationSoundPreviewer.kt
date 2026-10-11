package io.github.towkirahmedrony.salah.core.domain.notification

import io.github.towkirahmedrony.salah.core.model.NotificationSound

/**
 * Plays a short preview of a reminder sound so the user can hear a choice before committing to it.
 *
 * A port, so the settings screen can be tested without an audio device and the Android
 * implementation stays a single, small adapter.
 *
 * A preview is never a prayer reminder: it is user-initiated, it plays once, it takes no alarm
 * and it is released as soon as it finishes. Nothing here can make a sound while the user is not
 * looking at the screen.
 */
interface NotificationSoundPreviewer {

    /**
     * Previews [sound].
     *
     * @return `true` when the preview was started or when there was deliberately nothing to play
     *   (a silent choice), `false` when the sound could not be played. Never throws.
     */
    fun preview(sound: NotificationSound): Boolean

    /** Stops any preview that is still playing. Safe to call when nothing is playing. */
    fun stopPreview()

    /** Releases preview resources. Idempotent. */
    fun release()
}
