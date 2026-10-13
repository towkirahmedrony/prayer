package io.github.towkirahmedrony.salah.feature.ramadan

import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleState
import io.github.towkirahmedrony.salah.core.model.CalendarSettings
import io.github.towkirahmedrony.salah.core.model.DataSourceInfo
import io.github.towkirahmedrony.salah.core.model.HijriDateSource
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerNotificationSettings
import io.github.towkirahmedrony.salah.core.model.PrayerSettings
import io.github.towkirahmedrony.salah.core.model.PrayerTimeSource
import io.github.towkirahmedrony.salah.core.model.RamadanPhase
import io.github.towkirahmedrony.salah.core.model.RamadanWindow
import io.github.towkirahmedrony.salah.core.model.TimeFormatPreference
import io.github.towkirahmedrony.salah.core.model.UnavailabilityReason
import io.github.towkirahmedrony.salah.testing.testSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The Ramadan table.
 *
 * This is where the Sehri/Iftar rule becomes something a user reads, so the assertions are about the
 * rule itself: the Sehri column *is* the schedule's Fajr, the Iftar column *is* its Maghrib, a day with
 * no schedule says so instead of showing a blank that could be mistaken for a time, and the times are
 * rendered in the prayer location's zone with the user's own clock format.
 *
 * A 24-hour format is pinned so the expected strings are exact rather than locale-dependent.
 */
class RamadanUiStateFactoryTest {

    private val factory = RamadanUiStateFactory()
    private val english = java.util.Locale.ENGLISH
    private val zone = ZoneId.of("Asia/Dhaka")

    private val window = RamadanWindow(
        hijriYear = 1445,
        start = LocalDate.parse("2024-03-11"),
        end = LocalDate.parse("2024-04-09"),
        source = HijriDateSource.ESTIMATED_UMM_AL_QURA,
    )

    private val origin = DataSourceInfo(
        source = PrayerTimeSource.LOCAL_CALCULATION,
        providerId = null,
        observedAt = Instant.EPOCH,
        isAvailableOffline = true,
    )

    private fun settings(timeFormat: TimeFormatPreference = TimeFormatPreference.TWENTY_FOUR_HOUR) =
        PrayerSettings(timeFormat = timeFormat)

    private fun inputs(
        schedules: Map<LocalDate, PrayerScheduleState>,
        window: RamadanWindow? = this.window,
        today: LocalDate = LocalDate.parse("2024-03-13"),
        locationConfigured: Boolean = true,
        notifications: PrayerNotificationSettings = PrayerNotificationSettings.DEFAULT,
        calendar: CalendarSettings = CalendarSettings.DEFAULT,
    ) = RamadanUiStateInputs(
        today = today,
        calendarSettings = calendar,
        prayerSettings = settings(),
        notificationSettings = notifications,
        window = window,
        schedules = schedules,
        locationConfigured = locationConfigured,
    )

    private fun available(date: String, fajr: LocalTime = LocalTime.of(4, 47), maghrib: LocalTime = LocalTime.of(18, 12)) =
        LocalDate.parse(date) to PrayerScheduleState.Available(
            schedule = testSchedule(LocalDate.parse(date), zone = zone, fajr = fajr, maghrib = maghrib),
            origin = origin,
        )

    private fun ready(state: RamadanUiState): RamadanUiState.Ready = state as RamadanUiState.Ready

    @Test
    fun `one row per day of the window, numbered from one`() {
        val schedules = window.dates.associate { date -> available(date.toString()) }
        val state = ready(factory.create(inputs(schedules), english))

        assertEquals(30, state.rows.size)
        assertEquals((1..30).toList(), state.rows.map { it.ramadanDayNumber })
        assertEquals(window.start, state.rows.first().date)
        assertEquals(window.end, state.rows.last().date)
        assertEquals(0, state.daysWithoutSchedule)
        assertEquals(30, state.totalDays)
        assertEquals(1445, state.hijriYear)
        assertEquals("11 March 2024 – 9 April 2024", state.rangeLabel)
        assertEquals(HijriDateSource.ESTIMATED_UMM_AL_QURA, state.source)
        assertNull(state.message)
    }

    @Test
    fun `the Sehri column is the schedule's Fajr and the Iftar column its Maghrib`() {
        val schedules = mapOf(
            available("2024-03-11", fajr = LocalTime.of(4, 47), maghrib = LocalTime.of(18, 12)),
            available("2024-03-12", fajr = LocalTime.of(4, 46), maghrib = LocalTime.of(18, 13)),
        )
        val state = ready(factory.create(inputs(schedules), english))

        val first = state.rows.first()
        assertEquals("04:47", first.sehriEndLabel)
        assertEquals("04:47", first.fajrLabel)
        assertEquals("18:12", first.iftarLabel)
        assertEquals("18:12", first.maghribLabel)
        val second = state.rows[1]
        assertEquals("04:46", second.sehriEndLabel)
        assertEquals("18:13", second.iftarLabel)
        assertTrue(first.hasSchedule)
    }

    @Test
    fun `a day with no Fajr shows no Sehri time rather than a plausible one`() {
        val schedule = testSchedule(LocalDate.parse("2024-03-11"), zone = zone)
            .let { it.copy(prayers = it.prayers.filterNot { entry -> entry.prayer == PrayerName.FAJR }) }
        val state = ready(
            factory.create(
                inputs(mapOf(LocalDate.parse("2024-03-11") to PrayerScheduleState.Available(schedule, origin))),
                english,
            ),
        )

        val row = state.rows.first()
        assertNull(row.sehriEndLabel)
        assertNull(row.fajrLabel)
        // Iftar is unaffected: a missing Fajr does not remove Maghrib.
        assertEquals("18:00", row.iftarLabel)
    }

    @Test
    fun `a day with no schedule says so once per row and once for the screen`() {
        val schedules = window.dates.associate { date ->
            if (date == LocalDate.parse("2024-03-13")) {
                date to PrayerScheduleState.Unavailable(
                    reason = UnavailabilityReason.NO_LOCATION_CONFIGURED,
                    detail = null,
                    origin = origin,
                )
            } else {
                available(date.toString())
            }
        }
        val state = ready(factory.create(inputs(schedules), english))

        assertEquals(30, state.rows.size)
        assertEquals(1, state.daysWithoutSchedule)
        val missing = state.rows.first { it.date == LocalDate.parse("2024-03-13") }
        assertFalse(missing.hasSchedule)
        assertEquals(UnavailabilityReason.NO_LOCATION_CONFIGURED, missing.unavailable)
        assertNull(missing.sehriEndLabel)
        assertNull(missing.iftarLabel)
        // A missing day does not remove the others.
        assertTrue(state.rows.first { it.date == LocalDate.parse("2024-03-12") }.hasSchedule)
    }

    @Test
    fun `a date with no schedule at all is a day without a timetable, not a day without a number`() {
        val state = ready(factory.create(inputs(emptyMap()), english))

        assertEquals(30, state.rows.size)
        assertEquals(30, state.daysWithoutSchedule)
        // The day numbers still come from the window, so the table can be read even with no times.
        assertEquals(1, state.rows.first().ramadanDayNumber)
        assertEquals(30, state.rows.last().ramadanDayNumber)
        assertTrue(state.rows.all { it.sehriEndLabel == null })
        assertTrue(state.rows.all { it.unavailable == null })
        assertNull(state.timeSource)
    }

    @Test
    fun `the provenance of the times is carried through from the schedule`() {
        val schedules = mapOf(available("2024-03-11"))
        val state = ready(factory.create(inputs(schedules), english))

        assertEquals(PrayerTimeSource.LOCAL_CALCULATION, state.timeSource)
        assertNull(state.timeSourceProviderId)
        assertTrue(state.locationConfigured)
    }

    @Test
    fun `no window means no rows and no dates rather than estimated ones`() {
        val state = ready(factory.create(inputs(emptyMap(), window = null), english))

        assertTrue(state.rows.isEmpty())
        assertNull(state.hijriYear)
        assertNull(state.rangeLabel)
        assertNull(state.source)
        assertNull(state.totalDays)
        assertNull(state.todayDayNumber)
        assertEquals(RamadanPhase.FAR_AWAY, state.phase)
    }

    @Test
    fun `each row carries the Gregorian, Bangla and Hijri dates of its own day`() {
        val state = ready(factory.create(inputs(mapOf(available("2024-03-11"))), english))

        val row = state.rows.first()
        assertEquals("11 March 2024", row.gregorianLabel)
        // 14 February is 1 Falgun 1430, so 11 March is its 27th day.
        assertEquals("27 Falgun 1430", row.banglaLabel)
        assertEquals("1 Ramadan 1445 AH", row.hijriLabel)
        assertTrue(row.isToday.not())
    }

    @Test
    fun `the Hijri and Bangla labels follow the interface language instead of always using Arabic`() {
        // The same day as the English assertions above, on the same screen, rendered for a Bengali
        // interface. The Ramadan table reuses the shared Hijri formatter, so it follows the language
        // selector like the Calendar screen rather than the device's own locale.
        val bengali = java.util.Locale.forLanguageTag("bn")
        val state = ready(factory.create(inputs(mapOf(available("2024-03-11"))), bengali))

        val row = state.rows.first()
        assertEquals("১ রমজান ১৪৪৫ AH", row.hijriLabel)
        assertEquals("২৭ ফাল্গুন ১৪৩০", row.banglaLabel)
        assertTrue(
            "the Bengali Gregorian label carries no Bengali script: ${row.gregorianLabel}",
            row.gregorianLabel.any { character -> character.code in 0x0980..0x09FF },
        )

        // Nothing on the row, and nothing in the window heading, is left in Arabic script: the English
        // and Bengali interfaces must never show an Arabic month name or Arabic-Indic numerals.
        listOfNotNull(row.gregorianLabel, row.banglaLabel, row.hijriLabel, state.rangeLabel).forEach { label ->
            assertTrue(
                "Arabic script survived in a Bengali Ramadan row: $label",
                label.none { character -> character.code in 0x0600..0x06FF },
            )
        }
    }

    @Test
    fun `the Hijri adjustment moves the Hijri labels in the table`() {
        val state = ready(
            factory.create(
                inputs(
                    mapOf(available("2024-03-11")),
                    calendar = CalendarSettings(hijriAdjustmentDays = 1),
                ),
                english,
            ),
        )

        assertEquals("2 Ramadan 1445 AH", state.rows.first().hijriLabel)
        assertEquals(1, state.hijriAdjustmentDays)
    }

    @Test
    fun `today is marked by its own date, not by the first row`() {
        val state = ready(
            factory.create(
                inputs(
                    mapOf(available("2024-03-20")),
                    today = LocalDate.parse("2024-03-20"),
                ),
                english,
            ),
        )

        assertEquals(listOf(LocalDate.parse("2024-03-20")), state.rows.filter { it.isToday }.map { it.date })
        // 20 March is the tenth day of a Ramadan that began on 11 March, and the first row is not the
        // one marked as today.
        assertEquals(10, state.todayDayNumber)
        assertFalse(state.rows.first().isToday)
        assertEquals(RamadanPhase.IN_RAMADAN, state.phase)
    }

    @Test
    fun `the reminder settings are reported so the screen can show them`() {
        val notifications = PrayerNotificationSettings.DEFAULT
            .withSehriReminder(enabled = true, leadMinutes = 15)
            .withIftarReminder(enabled = true, leadMinutes = 10)
        val state = ready(factory.create(inputs(emptyMap(), notifications = notifications), english))

        assertTrue(state.masterEnabled)
        assertTrue(state.sehriReminderEnabled)
        assertEquals(15, state.sehriLeadMinutes)
        assertTrue(state.iftarReminderEnabled)
        assertEquals(10, state.iftarLeadMinutes)
    }

    @Test
    fun `a time is rendered in the prayer location's zone, not the device's`() {
        // The schedule is in Dhaka; the expected string is Dhaka local time.
        val otherZone = testSchedule(
            LocalDate.parse("2024-03-11"),
            zone = zone,
            fajr = LocalTime.of(4, 47),
        )
        val state = ready(
            factory.create(
                RamadanUiStateInputs(
                    today = LocalDate.parse("2024-03-11"),
                    calendarSettings = CalendarSettings.DEFAULT,
                    prayerSettings = settings(),
                    notificationSettings = PrayerNotificationSettings.DEFAULT,
                    window = RamadanWindow(
                        hijriYear = 1445,
                        start = LocalDate.parse("2024-03-11"),
                        end = LocalDate.parse("2024-03-11"),
                        source = HijriDateSource.ESTIMATED_UMM_AL_QURA,
                    ),
                    schedules = mapOf(LocalDate.parse("2024-03-11") to PrayerScheduleState.Available(otherZone, origin)),
                    locationConfigured = true,
                ),
                english,
            ),
        )

        assertEquals("04:47", state.rows.single().sehriEndLabel)
        assertEquals("18:00", state.rows.single().iftarLabel)
    }
}
