package io.github.towkirahmedrony.salah.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings model is the only input the planner reads besides the schedule, so its defaults and
 * its clamping are behaviour, not trivia.
 */
class PrayerNotificationSettingsTest {

    @Test
    fun `a fresh install starts with reminders on and sunrise off`() {
        val settings = PrayerNotificationSettings.DEFAULT

        assertTrue(settings.masterEnabled)
        assertFalse(settings.sunriseReminderEnabled)
        assertTrue(settings.prayers.isEmpty())
    }

    @Test
    fun `a prayer with no stored preference reads as the documented default`() {
        val preference = PrayerNotificationSettings.DEFAULT.preferenceFor(PrayerName.ISHA)

        assertEquals(PrayerNotificationPreference.DEFAULT, preference)
        assertTrue(preference.enabled)
        assertEquals(NotificationSound.SYSTEM_DEFAULT, preference.sound)
        assertTrue(preference.vibration)
        assertEquals(0, preference.advanceMinutes)
    }

    @Test
    fun `a preference equal to the default is not stored`() {
        val settings = PrayerNotificationSettings.DEFAULT
            .withPreference(PrayerName.FAJR, PrayerNotificationPreference.DEFAULT)

        assertTrue(settings.prayers.isEmpty())
    }

    @Test
    fun `only the affected prayer's preference changes`() {
        val settings = PrayerNotificationSettings.DEFAULT
            .withPreference(PrayerName.FAJR, PrayerNotificationPreference(sound = NotificationSound.SILENT))

        assertEquals(NotificationSound.SILENT, settings.preferenceFor(PrayerName.FAJR).sound)
        assertEquals(
            PrayerNotificationPreference.DEFAULT.sound,
            settings.preferenceFor(PrayerName.DHUHR).sound,
        )
        assertEquals(1, settings.prayers.size)
    }

    @Test
    fun `setting a prayer back to the default removes the stored entry`() {
        val settings = PrayerNotificationSettings.DEFAULT
            .withPreference(PrayerName.ASR, PrayerNotificationPreference(enabled = false))
            .withPreference(PrayerName.ASR, PrayerNotificationPreference.DEFAULT)

        assertTrue(settings.prayers.isEmpty())
    }

    @Test
    fun `an out-of-range advance value is clamped rather than stored`() {
        val tooLarge = PrayerNotificationSettings.DEFAULT
            .withPreference(PrayerName.MAGHRIB, PrayerNotificationPreference(advanceMinutes = 900))
        val negative = PrayerNotificationSettings.DEFAULT
            .withPreference(PrayerName.MAGHRIB, PrayerNotificationPreference(advanceMinutes = -5))

        assertEquals(PrayerNotificationSettings.MAX_ADVANCE_MINUTES, tooLarge.preferenceFor(PrayerName.MAGHRIB).advanceMinutes)
        assertEquals(0, negative.preferenceFor(PrayerName.MAGHRIB).advanceMinutes)

        // A negatived advance clamps to the default, so nothing is stored for it at all.
        assertTrue(negative.prayers.isEmpty())
    }

    @Test
    fun `every offered advance choice is inside the supported range`() {
        PrayerNotificationSettings.ADVANCE_MINUTE_CHOICES.forEach { minutes ->
            assertTrue(
                "offered value $minutes must be within 0..MAX",
                minutes in 0..PrayerNotificationSettings.MAX_ADVANCE_MINUTES,
            )
        }
    }

    @Test
    fun `hasAnyEnabledTarget respects the master switch`() {
        val on = PrayerNotificationSettings.DEFAULT
        val off = PrayerNotificationSettings.DEFAULT.copy(masterEnabled = false)
        val allPrayersOff = PrayerName.entries.fold(PrayerNotificationSettings.DEFAULT) { acc, prayer ->
            acc.withPreference(prayer, PrayerNotificationPreference(enabled = false))
        }

        assertTrue(on.hasAnyEnabledTarget)
        assertFalse(off.hasAnyEnabledTarget)
        assertFalse(allPrayersOff.hasAnyEnabledPrayer)
    }

    @Test
    fun `the sunrise reminder alone counts as an enabled target`() {
        val settings = PrayerName.entries.fold(PrayerNotificationSettings.DEFAULT) { acc, prayer ->
            acc.withPreference(prayer, PrayerNotificationPreference(enabled = false))
        }.copy(sunriseReminderEnabled = true)

        assertFalse(settings.hasAnyEnabledPrayer)
        assertTrue(settings.hasAnyEnabledTarget)
    }
}
