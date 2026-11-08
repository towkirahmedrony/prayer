package io.github.towkirahmedrony.salah.core.model

/**
 * The sound a Salah prayer reminder may use.
 *
 * Deliberately a small, closed set: every value here is backed by something Salah can actually
 * play, and nothing is offered that the app cannot deliver.
 *
 *  - [SYSTEM_DEFAULT] uses the device's own notification sound. It is always available, works
 *    offline, and cannot break if a bundled asset is missing.
 *  - [SALAH_CHIME] is a short alert tone that ships inside the APK (`res/raw/salah_chime.wav`).
 *    It was synthesised for this project and carries no third-party licence — see
 *    `docs/PRAYER_NOTIFICATIONS.md` §6. It is what makes a prayer alert recognisable without
 *    requiring network access or a system sound the user may have replaced.
 *  - [AZAN] is the Adhan (call to prayer) itself, bundled as `res/raw/azan.ogg` and played by the
 *    notification channel, so it needs no network, no storage permission and no separate player.
 *    Choosing it is the app's "Ajan notification" option: whichever prayers the user sets to
 *    [AZAN] announce themselves with the recording. It is CC0 1.0 (public domain) — see
 *    `docs/PRAYER_NOTIFICATIONS.md` §6.2 for the source and attribution.
 *  - [SILENT] posts a reminder with no sound at all. This is a "silent reminder", not an absence
 *    of a reminder: the notification still appears in the shade.
 *
 * On Android 8.0 (API 26) and later the **notification channel** owns the sound, so Salah routes
 * each reminder to the channel that matches the sound and vibration the user chose. See
 * `docs/PRAYER_NOTIFICATIONS.md` §4. That is also why a sound change here cannot override a
 * channel the user has already customised in system settings — Android preserves the user's
 * choice, and Salah says so in the UI instead of pretending otherwise.
 */
enum class NotificationSound {

    /** The device's own notification sound. */
    SYSTEM_DEFAULT,

    /** A short alert tone bundled with Salah. */
    SALAH_CHIME,

    /**
     * The Adhan (call to prayer), bundled with Salah and played by the notification channel.
     *
     * The recording ships in `res/raw/azan.ogg` and is played locally, so it works offline and
     * needs no storage permission. Because a channel's sound is fixed when the channel is created,
     * this value has **its own** channel ids (`salah_prayer_azan_vibrate` and
     * `salah_prayer_azan_quiet`): Android creates them fresh with this sound rather than inheriting
     * a channel that was created for a different sound. See `docs/PRAYER_NOTIFICATIONS.md` §4.3.
     */
    AZAN,

    /** No sound. The reminder still appears in the notification shade. */
    SILENT,
    ;

    /** True when this choice produces no audible alert. */
    val isSilent: Boolean get() = this == SILENT
}
