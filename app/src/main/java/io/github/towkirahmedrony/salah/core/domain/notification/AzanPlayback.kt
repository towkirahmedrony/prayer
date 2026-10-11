package io.github.towkirahmedrony.salah.core.domain.notification

import io.github.towkirahmedrony.salah.core.domain.DiagnosticsLogger
import io.github.towkirahmedrony.salah.core.domain.SalahDiagnosticEvents

/**
 * Where a full-length Azan recording comes from.
 *
 * Separated from the playback machinery so the licensing question and the engineering question
 * stay apart. Salah ships **no** Azan recording: a proper one is a substantial piece of religious
 * audio, and redistributing a recording requires rights that this project does not have. Rather
 * than bundling something unlicensed, or synthesising a stand-in and calling it an Azan, the
 * bundled implementation reports that nothing is available.
 *
 * Adding a licensed recording is then a one-line change to
 * [io.github.towkirahmedrony.salah.data.notification.BundledAzanAudioSource]: return its location
 * and [AzanPlaybackManager] begins playing it, with no other code touched.
 */
interface AzanAudioSource {

    /**
     * The playable location of the Azan recording, or `null` when none is bundled.
     *
     * `null` is a normal, expected answer and is what Salah reports today.
     */
    fun resolve(): String?

    /** Human-readable attribution for the bundled recording, or `null` when none is bundled. */
    fun attribution(): String?
}

/**
 * The audio device Salah plays through.
 *
 * An interface so [AzanPlaybackManager]'s lifecycle rules — one playback at a time, stop on
 * request, always release — are unit-tested against a recording fake rather than a real
 * `MediaPlayer` on a real device.
 */
interface AudioPlaybackEngine {

    /** True while audio is actually playing. */
    fun isPlaying(): Boolean

    /**
     * Starts playing [source].
     *
     * @return `true` when playback started, `false` when the resource could not be played. Must
     *   not throw: a missing or corrupt resource is a `false`, because a prayer reminder must
     *   still be delivered when the audio fails.
     */
    fun start(source: String): Boolean

    /** Stops playback. Safe to call when nothing is playing. */
    fun stop()

    /** Releases platform resources. Safe to call more than once. */
    fun release()
}

/** Why a request to play the Azan did not produce audio. */
enum class AzanUnavailableReason {
    /** No licensed recording is bundled with this build. */
    NO_LICENSED_AUDIO_BUNDLED,

    /** Playback is already running; a second instance is never started. */
    ALREADY_PLAYING,
}

/** Outcome of a request to play the Azan. */
sealed interface AzanPlaybackResult {

    /** Playback is running. */
    data object Started : AzanPlaybackResult

    /** Playback was not started, for a reason that is not a failure. */
    data class Unavailable(
        val reason: AzanUnavailableReason,
        val detail: String,
    ) : AzanPlaybackResult

    /** Playback was attempted and the device refused it. */
    data class Failed(val detail: String) : AzanPlaybackResult
}

/**
 * Owns Azan playback: at most one instance, stopped on request, always released.
 *
 * The whole subsystem is implemented and tested even though no recording ships, so the extension
 * point is real rather than a comment. Its rules:
 *
 *  - **One playback at a time.** A second [play] while audio is running returns
 *    [AzanUnavailableReason.ALREADY_PLAYING] instead of stacking a second stream. Two Azans
 *    playing over each other is the failure this prevents.
 *  - **No silent restart.** [play] never resumes or restarts an interrupted stream; it either
 *    starts cleanly or reports why it did not.
 *  - **No infinite loop.** A recording plays once. Salah never sets `MediaPlayer` to loop, because
 *    an Azan repeated indefinitely is not something a user can be assumed to want.
 *  - **Missing audio is not a crash.** A `null` source is an [AzanPlaybackResult.Unavailable] and
 *    the prayer reminder itself is unaffected — the notification is delivered either way.
 *  - **Resources are always released.** [stop] and [release] are idempotent.
 *
 * Every outcome is logged against a stable event name so background behaviour is observable.
 */
class AzanPlaybackManager(
    private val audioSource: AzanAudioSource,
    private val engine: AudioPlaybackEngine,
    private val logger: DiagnosticsLogger,
) {

    /** True while Azan audio is playing. */
    val isPlaying: Boolean get() = engine.isPlaying()

    /** True when a recording is bundled and playback is therefore possible. */
    val isAvailable: Boolean get() = audioSource.resolve() != null

    /** Human-readable attribution of the bundled recording, if any. */
    fun attribution(): String? = audioSource.attribution()

    /**
     * Starts playback, if there is anything to play and nothing is playing already.
     *
     * Called from the delivery path for an at-time prayer reminder. The caller ignores an
     * [AzanPlaybackResult.Unavailable] result: the reminder itself has already been posted.
     */
    fun play(): AzanPlaybackResult {
        val source = audioSource.resolve()
        if (source == null) {
            val detail = "No licensed Azan recording is bundled with this build."
            logger.info(
                SalahDiagnosticEvents.AZAN_UNAVAILABLE,
                mapOf("reason" to AzanUnavailableReason.NO_LICENSED_AUDIO_BUNDLED.name),
            )
            return AzanPlaybackResult.Unavailable(
                reason = AzanUnavailableReason.NO_LICENSED_AUDIO_BUNDLED,
                detail = detail,
            )
        }

        if (engine.isPlaying()) {
            val detail = "Azan playback is already running; a second instance was not started."
            logger.warn(
                SalahDiagnosticEvents.AZAN_UNAVAILABLE,
                mapOf("reason" to AzanUnavailableReason.ALREADY_PLAYING.name),
            )
            return AzanPlaybackResult.Unavailable(
                reason = AzanUnavailableReason.ALREADY_PLAYING,
                detail = detail,
            )
        }

        return try {
            if (engine.start(source)) {
                logger.info(SalahDiagnosticEvents.AZAN_STARTED, emptyMap())
                AzanPlaybackResult.Started
            } else {
                val detail = "The bundled Azan recording could not be played."
                logger.error(SalahDiagnosticEvents.AZAN_FAILED, null, mapOf("reason" to "engine_refused"))
                AzanPlaybackResult.Failed(detail)
            }
        } catch (error: Exception) {
            // Playback must never take the notification path down with it.
            logger.error(SalahDiagnosticEvents.AZAN_FAILED, error, emptyMap())
            AzanPlaybackResult.Failed(error.message ?: error::class.simpleName.orEmpty())
        }
    }

    /**
     * Stops playback if it is running.
     *
     * @return `true` when playback was running and has been stopped.
     */
    fun stop(): Boolean {
        val wasPlaying = engine.isPlaying()
        engine.stop()
        if (wasPlaying) {
            logger.info(SalahDiagnosticEvents.AZAN_STOPPED, emptyMap())
        }
        return wasPlaying
    }

    /** Releases platform resources. Idempotent. */
    fun release() {
        engine.release()
    }
}
