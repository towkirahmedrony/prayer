package io.github.towkirahmedrony.salah.core.domain.notification

import io.github.towkirahmedrony.salah.core.domain.SalahDiagnosticEvents
import io.github.towkirahmedrony.salah.testing.FakeAudioPlaybackEngine
import io.github.towkirahmedrony.salah.testing.FakeAzanAudioSource
import io.github.towkirahmedrony.salah.testing.RecordingLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Azan playback is implemented and tested even though no recording ships. These tests hold the
 * lifecycle rules that will matter the moment a licensed recording is added: play at most once at a
 * time, stop on request, always release, and never crash when the audio is missing.
 */
class AzanPlaybackManagerTest {

    private val logger = RecordingLogger()

    private fun manager(
        source: String?,
        engine: FakeAudioPlaybackEngine = FakeAudioPlaybackEngine(),
    ): Pair<AzanPlaybackManager, FakeAudioPlaybackEngine> =
        AzanPlaybackManager(FakeAzanAudioSource(source), engine, logger) to engine

    @Test
    fun `playback reports unavailable when no licensed recording is bundled`() {
        val (azan, engine) = manager(source = null)

        val result = azan.play()

        assertTrue(result is AzanPlaybackResult.Unavailable)
        assertEquals(
            AzanUnavailableReason.NO_LICENSED_AUDIO_BUNDLED,
            (result as AzanPlaybackResult.Unavailable).reason,
        )
        // Nothing was started, and nothing crashed: a missing resource is not a failure path.
        assertEquals(0, engine.startCalls)
        assertTrue(logger.has(SalahDiagnosticEvents.AZAN_UNAVAILABLE))
        assertFalse(azan.isAvailable)
        assertNull(azan.attribution())
    }

    @Test
    fun `playback starts exactly once when a recording is available`() {
        val (azan, engine) = manager(source = "android.resource://test/raw/azan")

        val result = azan.play()

        assertEquals(AzanPlaybackResult.Started, result)
        assertEquals(1, engine.startCalls)
        assertEquals(listOf("android.resource://test/raw/azan"), engine.startedSources)
        assertTrue(azan.isPlaying)
        assertTrue(logger.has(SalahDiagnosticEvents.AZAN_STARTED))
    }

    @Test
    fun `a second play request never stacks a second stream`() {
        val (azan, engine) = manager(source = "file://azan.mp3")

        azan.play()
        val second = azan.play()

        assertTrue(second is AzanPlaybackResult.Unavailable)
        assertEquals(
            AzanUnavailableReason.ALREADY_PLAYING,
            (second as AzanPlaybackResult.Unavailable).reason,
        )
        assertEquals("engine.start must not be called twice", 1, engine.startCalls)
    }

    @Test
    fun `stop releases playback and is safe to repeat`() {
        val (azan, engine) = manager(source = "file://azan.mp3")
        azan.play()

        assertTrue(azan.stop())
        assertFalse(azan.isPlaying)
        assertFalse("stopping again does nothing", azan.stop())

        assertEquals(2, engine.stopCalls)
        assertTrue(logger.has(SalahDiagnosticEvents.AZAN_STOPPED))
    }

    @Test
    fun `release is forwarded to the engine`() {
        val (azan, engine) = manager(source = null)

        azan.release()

        assertEquals(1, engine.releaseCalls)
    }

    @Test
    fun `an engine that refuses to start is reported as a failure, not a crash`() {
        val engine = FakeAudioPlaybackEngine(startSucceeds = false)
        val (azan, _) = manager(source = "file://azan.mp3", engine = engine)

        val result = azan.play()

        assertTrue(result is AzanPlaybackResult.Failed)
        assertTrue(logger.has(SalahDiagnosticEvents.AZAN_FAILED))
    }

    @Test
    fun `an engine that throws is reported as a failure with the cause logged`() {
        val engine = FakeAudioPlaybackEngine().apply { throwOnStart = IllegalStateException("decoder died") }
        val (azan, _) = manager(source = "file://azan.mp3", engine = engine)

        val result = azan.play()

        assertTrue(result is AzanPlaybackResult.Failed)
        assertEquals("decoder died", (result as AzanPlaybackResult.Failed).detail)
        assertEquals(1, logger.count(SalahDiagnosticEvents.AZAN_FAILED))
        assertTrue(logger.lastError is IllegalStateException)
    }

    @Test
    fun `availability and attribution follow the audio source`() {
        val (unavailable, _) = manager(source = null)
        val (available, _) = manager(source = "file://azan.mp3")

        assertFalse(unavailable.isAvailable)
        assertNull(unavailable.attribution())
        assertTrue(available.isAvailable)
        assertEquals("test recording", available.attribution())
    }
}
