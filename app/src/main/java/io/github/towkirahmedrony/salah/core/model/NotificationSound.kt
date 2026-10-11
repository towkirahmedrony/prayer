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
 *    `docs/PRAYER_NOTIFICATIONS.md` §5. It is what makes a prayer alert recognisable without
 *    requiring network access or a system sound the user may have replaced.
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

    /** No sound. The reminder still appears in the notification shade. */
    SILENT,
    ;

    /** True when this choice produces no audible alert. */
    val isSilent: Boolean get() = this == SILENT
}
