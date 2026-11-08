package io.github.towkirahmedrony.salah.data.notification

import android.content.Context
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.domain.notification.AzanAudioSource

/**
 * The Adhan recording bundled with Salah.
 *
 * The recording is `res/raw/azan.ogg` (Ogg Vorbis, 44.1 kHz stereo, 2 min 34 s, about 1.2 MB), so it
 * travels inside the APK: it plays with no network, no storage permission and no download step. It
 * is what backs [io.github.towkirahmedrony.salah.core.model.NotificationSound.AZAN], which is the
 * sound the notification channel plays — the channel, not a separate player, is the delivery
 * mechanism Android provides for a reminder's sound.
 *
 * **Licence.** The recording is CC0 1.0 Universal (public domain dedication), so redistribution is
 * expressly permitted and no attribution is legally required. It is credited anyway, both here and
 * in the settings screen, because the performer deserves it and because provenance should be
 * checkable:
 *
 *  - File: `Beautiful adhan.ogg`
 *  - Author: Adam-synagda
 *  - Source: Wikimedia Commons, https://commons.wikimedia.org/wiki/File:Beautiful_adhan.ogg
 *  - Licence: CC0 1.0 Universal (public domain dedication)
 *
 * See `docs/PRAYER_NOTIFICATIONS.md` §6.2 for the file's checksum and verification details.
 */
class BundledAzanAudioSource(
    private val context: Context,
) : AzanAudioSource {

    /** The bundled recording as an app-resource URI, readable without any storage permission. */
    override fun resolve(): String =
        "android.resource://${context.packageName}/${R.raw.azan}"

    /** The credit shown next to the Adhan option. */
    override fun attribution(): String =
        "\"Beautiful adhan\" by Adam-synagda (Wikimedia Commons), CC0 1.0 public domain"
}
