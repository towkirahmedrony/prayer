package io.github.towkirahmedrony.salah.core.domain.notification

import io.github.towkirahmedrony.salah.core.model.NotificationSound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Channel ids are an interface with Android: they are how the platform remembers a user's
 * customisations. They must be unique per (sound, vibration) pair, stable, and bounded — the whole
 * point of the design is that the number of channels does not grow with the number of prayers.
 */
class PrayerNotificationChannelIdTest {

    @Test
    fun `every sound and vibration pair has its own channel id`() {
        val ids = NotificationSound.entries.flatMap { sound ->
            listOf(true, false).map { vibration -> PrayerNotificationChannelId.of(sound, vibration) }
        }

        assertEquals(NotificationSound.entries.size * 2, ids.size)
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `the channel set is bounded and derived from sounds, not from prayers`() {
        assertEquals(
            NotificationSound.entries.size * 2,
            PrayerNotificationChannelId.prayerChannels.size,
        )
        assertEquals(
            PrayerNotificationChannelId.prayerChannels.size,
            PrayerNotificationChannelId.prayerChannels.toSet().size,
        )
    }

    @Test
    fun `ids are stable strings with a shared prefix`() {
        assertEquals("salah_prayer_salah_chime_vibrate", PrayerNotificationChannelId.of(NotificationSound.SALAH_CHIME, true))
        assertEquals("salah_prayer_silent_quiet", PrayerNotificationChannelId.of(NotificationSound.SILENT, false))
        PrayerNotificationChannelId.prayerChannels.forEach { id ->
            assertTrue(id.startsWith(PrayerNotificationChannelId.PREFIX))
        }
    }

    @Test
    fun `the status channel is separate from every prayer channel`() {
        assertTrue(PrayerNotificationChannelId.prayerChannels.none { it == PrayerNotificationChannelId.STATUS })
        assertTrue(PrayerNotificationChannelId.prayerChannels.none { it.startsWith("salah_status") })
    }
}
