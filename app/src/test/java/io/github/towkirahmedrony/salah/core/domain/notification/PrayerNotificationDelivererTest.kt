package io.github.towkirahmedrony.salah.core.domain.notification

import io.github.towkirahmedrony.salah.core.domain.SalahDiagnosticEvents
import io.github.towkirahmedrony.salah.core.model.NotificationSound
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerNotificationPreference
import io.github.towkirahmedrony.salah.core.model.PrayerNotificationSettings
import io.github.towkirahmedrony.salah.core.model.PrayerSettings
import io.github.towkirahmedrony.salah.core.model.TimeFormatPreference
import io.github.towkirahmedrony.salah.testing.FakeAudioPlaybackEngine
import io.github.towkirahmedrony.salah.testing.FakeAzanAudioSource
import io.github.towkirahmedrony.salah.testing.FakeLocationRepository
import io.github.towkirahmedrony.salah.testing.FakePermissionStatusProvider
import io.github.towkirahmedrony.salah.testing.FakePrayerNotificationPreferencesRepository
import io.github.towkirahmedrony.salah.testing.FakePrayerNotificationPresenter
import io.github.towkirahmedrony.salah.testing.FakeUserPreferencesRepository
import io.github.towkirahmedrony.salah.testing.MutableClock
import io.github.towkirahmedrony.salah.testing.RecordingLogger
import io.github.towkirahmedrony.salah.testing.testLocation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Delivery is the last gate before the notification shade, and it is where an alarm scheduled days
 * ago meets the settings the user has *now*. These tests hold the rule that the current settings
 * win: a reminder cannot appear for a prayer the user disabled, and a silent prayer still gets a
 * (silent) reminder.
 */
class PrayerNotificationDelivererTest {

    private val zone: ZoneId = ZoneId.of("Asia/Dhaka")
    private val date: LocalDate = LocalDate.of(2026, 5, 20)
    private val now: Instant = Instant.parse("2026-05-19T23:00:00Z") // 05:00 Dhaka, exactly Fajr

    private val logger = RecordingLogger()
    private val presenter = FakePrayerNotificationPresenter()
    private val permissions = FakePermissionStatusProvider()
    private val clock = MutableClock(now, zone)

    private val fajrSlot = PrayerAlarmSlot(date, ReminderTarget.FAJR, PrayerReminderKind.AT_TIME)

    private fun fajrRequest(triggerAt: Instant = now, advanceMinutes: Int = 0): PrayerNotificationRequest =
        PrayerNotificationRequest(
            slot = fajrSlot,
            triggerAtEpochMillis = triggerAt.toEpochMilli(),
            eventAtEpochMillis = ZonedDateTime.of(date, LocalTime.of(5, 0), zone).toInstant().toEpochMilli(),
            advanceMinutes = advanceMinutes,
        )

    private fun deliverer(
        preferences: PrayerNotificationSettings = PrayerNotificationSettings.DEFAULT,
        azanSource: String? = null,
        prayerSettings: PrayerSettings = PrayerSettings.DEFAULT,
    ): PrayerNotificationDeliverer = PrayerNotificationDeliverer(
        notificationSettings = FakePrayerNotificationPreferencesRepository(preferences),
        userPreferences = FakeUserPreferencesRepository(prayerSettings),
        locations = FakeLocationRepository(testLocation(zone)),
        permissions = permissions,
        presenter = presenter,
        azanPlayback = AzanPlaybackManager(
            audioSource = FakeAzanAudioSource(azanSource),
            engine = FakeAudioPlaybackEngine(),
            logger = logger,
        ),
        logger = logger,
        clock = clock,
    )

    // --- Happy path -----------------------------------------------------------

    @Test
    fun `an enabled prayer is delivered on the channel its sound and vibration select`() = runTest {
        val preferences = PrayerNotificationSettings.DEFAULT.withPreference(
            PrayerName.FAJR,
            PrayerNotificationPreference(sound = NotificationSound.SALAH_CHIME, vibration = false),
        )

        val outcome = deliverer(preferences).deliver(fajrRequest())

        assertTrue(outcome is DeliveryOutcome.Posted)
        assertEquals(
            PrayerNotificationChannelId.of(NotificationSound.SALAH_CHIME, vibration = false),
            (outcome as DeliveryOutcome.Posted).channelId,
        )
        assertEquals(1, presenter.delivered.size)
        assertEquals(
            ZonedDateTime.of(date, LocalTime.of(5, 0), zone),
            presenter.delivered.single().eventAt,
        )
        assertTrue(logger.has(SalahDiagnosticEvents.DELIVERY_POSTED))
    }

    @Test
    fun `the reminder is rendered in the prayer location's zone and time format`() = runTest {
        val outcome = deliverer(
            prayerSettings = PrayerSettings.DEFAULT.copy(timeFormat = TimeFormatPreference.TWENTY_FOUR_HOUR),
        ).deliver(fajrRequest())

        assertTrue(outcome is DeliveryOutcome.Posted)
        val delivery = presenter.delivered.single()
        assertEquals(zone, delivery.eventAt.zone)
        assertEquals(TimeFormatPreference.TWENTY_FOUR_HOUR, delivery.timeFormat)
    }

    @Test
    fun `a silent choice still produces a reminder`() = runTest {
        val preferences = PrayerNotificationSettings.DEFAULT.withPreference(
            PrayerName.FAJR,
            PrayerNotificationPreference(sound = NotificationSound.SILENT, vibration = false),
        )

        val outcome = deliverer(preferences).deliver(fajrRequest())

        assertTrue(outcome is DeliveryOutcome.Posted)
        assertEquals(
            PrayerNotificationChannelId.of(NotificationSound.SILENT, vibration = false),
            (outcome as DeliveryOutcome.Posted).channelId,
        )
    }

    @Test
    fun `a sound changed after scheduling is honoured at delivery time`() = runTest {
        // The alarm was scheduled under the default sound; the user has since chosen silent.
        val preferences = PrayerNotificationSettings.DEFAULT.withPreference(
            PrayerName.FAJR,
            PrayerNotificationPreference(sound = NotificationSound.SILENT, vibration = true),
        )

        deliverer(preferences).deliver(fajrRequest())

        assertEquals(NotificationSound.SILENT, presenter.delivered.single().sound)
    }

    // --- Suppression ----------------------------------------------------------

    @Test
    fun `the master switch suppresses delivery`() = runTest {
        val outcome = deliverer(
            preferences = PrayerNotificationSettings.DEFAULT.copy(masterEnabled = false),
        ).deliver(fajrRequest())

        assertTrue(outcome is DeliveryOutcome.Suppressed)
        assertEquals(SuppressionReason.MASTER_DISABLED, (outcome as DeliveryOutcome.Suppressed).reason)
        assertTrue(presenter.delivered.isEmpty())
        assertTrue(logger.has(SalahDiagnosticEvents.DELIVERY_SUPPRESSED))
    }

    @Test
    fun `a disabled prayer is not delivered`() = runTest {
        val preferences = PrayerNotificationSettings.DEFAULT.withPreference(
            PrayerName.FAJR,
            PrayerNotificationPreference(enabled = false),
        )

        val outcome = deliverer(preferences).deliver(fajrRequest())

        assertEquals(SuppressionReason.TARGET_DISABLED, (outcome as DeliveryOutcome.Suppressed).reason)
        assertTrue(presenter.delivered.isEmpty())
    }

    @Test
    fun `a sunrise reminder is suppressed unless its own switch is on`() = runTest {
        val sunriseSlot = PrayerAlarmSlot(date, ReminderTarget.SUNRISE, PrayerReminderKind.AT_TIME)
        val request = PrayerNotificationRequest(sunriseSlot, now.toEpochMilli(), now.toEpochMilli(), 0)

        val off = deliverer().deliver(request)
        val on = deliverer(
            preferences = PrayerNotificationSettings.DEFAULT.copy(sunriseReminderEnabled = true),
        ).deliver(request)

        assertEquals(SuppressionReason.TARGET_DISABLED, (off as DeliveryOutcome.Suppressed).reason)
        assertTrue(on is DeliveryOutcome.Posted)
    }

    @Test
    fun `missing notification permission suppresses delivery and says so`() = runTest {
        permissions.current = NotificationPermissionStatus(notificationsAllowed = false, exactAlarmsAllowed = true)

        val outcome = deliverer().deliver(fajrRequest())

        assertEquals(
            SuppressionReason.NOTIFICATIONS_NOT_ALLOWED,
            (outcome as DeliveryOutcome.Suppressed).reason,
        )
        assertTrue(presenter.delivered.isEmpty())
    }

    @Test
    fun `a reminder that fires far too late is suppressed`() = runTest {
        // The alarm was due two hours before the device woke up.
        val staleTrigger = now.minusSeconds(2 * 60 * 60)

        val outcome = deliverer().deliver(fajrRequest(triggerAt = staleTrigger))

        assertEquals(SuppressionReason.STALE, (outcome as DeliveryOutcome.Suppressed).reason)
        assertTrue(presenter.delivered.isEmpty())
    }

    @Test
    fun `a reminder inside the staleness window is still delivered`() = runTest {
        val slightlyLate = now.minusSeconds(DeliveryOutcome.STALE_TOLERANCE_MILLIS / 1000 / 2)

        val outcome = deliverer().deliver(fajrRequest(triggerAt = slightlyLate))

        assertTrue(outcome is DeliveryOutcome.Posted)
    }

    // --- Azan -----------------------------------------------------------------

    @Test
    fun `azan is attempted for an at-time prayer reminder`() = runTest {
        val outcome = deliverer(azanSource = "file://azan.mp3").deliver(fajrRequest())

        outcome as DeliveryOutcome.Posted
        assertEquals(AzanPlaybackResult.Started, outcome.azan)
    }

    @Test
    fun `azan playback being unavailable never prevents the reminder`() = runTest {
        val outcome = deliverer(azanSource = null).deliver(fajrRequest())

        outcome as DeliveryOutcome.Posted
        assertNotNull(outcome.azan)
        assertTrue(outcome.azan is AzanPlaybackResult.Unavailable)
        assertEquals("the notification is the point, the audio is an extra", 1, presenter.delivered.size)
        assertTrue(logger.has(SalahDiagnosticEvents.DELIVERY_POSTED))
    }

    @Test
    fun `azan is not attempted for a lead-in reminder`() = runTest {
        val advanceSlot = PrayerAlarmSlot(date, ReminderTarget.FAJR, PrayerReminderKind.ADVANCE)
        val request = PrayerNotificationRequest(advanceSlot, now.toEpochMilli(), now.toEpochMilli(), 10)

        val outcome = deliverer(azanSource = "file://azan.mp3").deliver(request)

        outcome as DeliveryOutcome.Posted
        assertNull("a lead-in reminder is a notification, not a call to prayer", outcome.azan)
    }

    @Test
    fun `azan is not attempted for the solar target`() = runTest {
        val sunriseSlot = PrayerAlarmSlot(date, ReminderTarget.SUNRISE, PrayerReminderKind.AT_TIME)
        val request = PrayerNotificationRequest(sunriseSlot, now.toEpochMilli(), now.toEpochMilli(), 0)

        val outcome = deliverer(
            preferences = PrayerNotificationSettings.DEFAULT.copy(sunriseReminderEnabled = true),
            azanSource = "file://azan.mp3",
        ).deliver(request)

        outcome as DeliveryOutcome.Posted
        assertNull(outcome.azan)
    }

    // --- Failure handling -----------------------------------------------------

    @Test
    fun `a presenter that refuses is reported as a failure, not a success`() = runTest {
        presenter.accept = false

        val outcome = deliverer().deliver(fajrRequest())

        assertTrue(outcome is DeliveryOutcome.Failed)
        assertTrue(logger.has(SalahDiagnosticEvents.DELIVERY_FAILED))
    }

    @Test
    fun `a presenter that throws is reported instead of crashing the receiver`() = runTest {
        presenter.throwOnPresent = SecurityException("permission revoked mid-flight")

        val outcome = deliverer().deliver(fajrRequest())

        assertTrue(outcome is DeliveryOutcome.Failed)
        assertTrue(logger.lastError is SecurityException)
    }
}
