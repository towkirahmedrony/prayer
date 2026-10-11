package io.github.towkirahmedrony.salah.data.notification

import io.github.towkirahmedrony.salah.core.domain.notification.AzanAudioSource

/**
 * Salah's Azan recording source, which currently has **no recording to offer**.
 *
 * This is the honest state of the feature, not an unfinished stub. A full Azan recording is a
 * substantial piece of religious audio, and bundling one means redistributing it, which requires
 * rights this project does not have. The alternatives were both worse:
 *
 *  - bundling a recording of unknown provenance would ship a licensing problem inside the APK, and
 *    `README.md` states that no licence has been established for this repository;
 *  - synthesising a stand-in and labelling it "Azan" would misrepresent a generated tone as
 *    recited scripture — precisely the kind of fabrication the rest of Salah refuses to do.
 *
 * So [resolve] returns `null`, and the notification settings screen tells the user plainly that
 * Azan playback is unavailable in this build and why, instead of showing a control that cannot
 * work. `docs/PRAYER_NOTIFICATIONS.md` §5 records the decision.
 *
 * **Adding a licensed recording is a one-line change**: return its location here (a bundled
 * `res/raw` resource, or a file the user supplies) and [AzanPlaybackManager] starts playing it.
 * The playback lifecycle, audio focus, single-instance rule and release behaviour are already
 * implemented and unit-tested; only the asset is missing.
 */
class BundledAzanAudioSource : AzanAudioSource {

    override fun resolve(): String? = null

    override fun attribution(): String? = null
}
