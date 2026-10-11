package io.github.towkirahmedrony.salah.testing

import io.github.towkirahmedrony.salah.core.domain.DiagnosticsLogger
import io.github.towkirahmedrony.salah.core.domain.notification.AudioPlaybackEngine
import io.github.towkirahmedrony.salah.core.domain.notification.AzanAudioSource
import io.github.towkirahmedrony.salah.core.domain.notification.NotificationPermissionStatus
import io.github.towkirahmedrony.salah.core.domain.notification.NotificationPermissionStatusProvider
import io.github.towkirahmedrony.salah.core.domain.notification.NotificationSoundPreviewer
import io.github.towkirahmedrony.salah.core.domain.notification.PlannedPrayerNotification
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerAlarmSlot
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationPresenter
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationScheduler
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerReminderDelivery
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleRequest
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleState
import io.github.towkirahmedrony.salah.core.domain.repository.LocationRepository
import io.github.towkirahmedrony.salah.core.domain.repository.PrayerNotificationPreferencesRepository
import io.github.towkirahmedrony.salah.core.domain.repository.PrayerScheduleRepository
import io.github.towkirahmedrony.salah.core.domain.repository.UserPreferencesRepository
import io.github.towkirahmedrony.salah.core.model.DataSourceInfo
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.LocationOrigin
import io.github.towkirahmedrony.salah.core.model.NotificationSound
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationProfile
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerNotificationSettings
import io.github.towkirahmedrony.salah.core.model.PrayerSchedule
import io.github.towkirahmedrony.salah.core.model.PrayerSettings
import io.github.towkirahmedrony.salah.core.model.PrayerTimeEntry
import io.github.towkirahmedrony.salah.core.model.PrayerTimeSource
import io.github.towkirahmedrony.salah.core.model.SolarEvent
import io.github.towkirahmedrony.salah.core.model.SolarEventEntry
import io.github.towkirahmedrony.salah.core.model.ThemeMode
import io.github.towkirahmedrony.salah.core.model.UnavailabilityReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Test doubles for the prayer-notification subsystem.
 *
 * These exist so the scheduling and delivery rules can be exercised against *real* behaviour rather
 * than against mocked behaviour. A fake here models what the real collaborator does — an alarm
 * registry that can actually hold or not hold an alarm, a schedule source that can actually fail —
 * so an assertion like "no duplicate alarm was registered" is a statement about the code under test
 * and not about the stand-in.
 */

/** Records every diagnostic event so tests can assert what was reported, and at what level. */
class RecordingLogger : DiagnosticsLogger {

    enum class Level { INFO, WARN, ERROR }

    data class Entry(
        val level: Level,
        val event: String,
        val details: Map<String, Any?>,
        val throwable: Throwable?,
    )

    private val mutableEntries = mutableListOf<Entry>()

    val entries: List<Entry> get() = mutableEntries.toList()

    /** The throwable passed to the most recent [error] call, if any. */
    val lastError: Throwable? get() = mutableEntries.lastOrNull { it.level == Level.ERROR }?.throwable

    override fun info(event: String, details: Map<String, Any?>) {
        mutableEntries += Entry(Level.INFO, event, details, null)
    }

    override fun warn(event: String, details: Map<String, Any?>) {
        mutableEntries += Entry(Level.WARN, event, details, null)
    }

    override fun error(event: String, throwable: Throwable?, details: Map<String, Any?>) {
        mutableEntries += Entry(Level.ERROR, event, details, throwable)
    }

    fun events(): List<String> = mutableEntries.map { it.event }

    fun has(event: String): Boolean = mutableEntries.any { it.event == event }

    fun count(event: String): Int = mutableEntries.count { it.event == event }

    fun detailsOf(event: String): Map<String, Any?>? =
        mutableEntries.lastOrNull { it.event == event }?.details

    /** Every message rendered, for assertions that no coordinate or private value was logged. */
    fun renderAll(): String = mutableEntries.joinToString("\n") { entry ->
        entry.event + " " + entry.details.entries.joinToString(" ") { "${it.key}=${it.value}" }
    }
}

/**
 * An alarm registry that behaves like `AlarmManager` does for Salah's purposes.
 *
 * [registered] is keyed by slot, so registering the same slot twice replaces rather than adds —
 * exactly the platform behaviour `FLAG_UPDATE_CURRENT` provides. A test can therefore assert on the
 * *set* of live alarms, which is what "no duplicate alarms" actually means.
 */
class FakePrayerNotificationScheduler : PrayerNotificationScheduler {

    val registered = linkedMapOf<PrayerAlarmSlot, PlannedPrayerNotification>()

    var scheduleCalls: Int = 0
        private set
    var cancelCalls: Int = 0
        private set

    /** When set, the next [schedule] throws, to exercise the "one alarm failed" path. */
    var failNextSchedule: Boolean = false

    /** When true, every [schedule] and [cancel] call throws. */
    var failEverything: Boolean = false

    override fun schedule(notification: PlannedPrayerNotification) {
        scheduleCalls++
        if (failEverything) throw IllegalStateException("scheduler unavailable")
        if (failNextSchedule) {
            failNextSchedule = false
            throw IllegalStateException("alarm rejected")
        }
        registered[notification.slot] = notification
    }

    override fun cancel(slot: PrayerAlarmSlot) {
        cancelCalls++
        if (failEverything) throw IllegalStateException("scheduler unavailable")
        registered.remove(slot)
    }

    fun slots(): List<PrayerAlarmSlot> = registered.keys.toList()
}

/** Serves the schedules it was given, and reports a missing date instead of inventing one. */
class FakePrayerScheduleRepository(
    private val schedules: Map<LocalDate, PrayerSchedule> = emptyMap(),
) : PrayerScheduleRepository {

    var lastRequest: PrayerScheduleRequest? = null
        private set
    var calls: Int = 0
        private set

    /** When true, every request reports a provider failure rather than a missing schedule. */
    var failWithProviderError: Boolean = false

    override fun ensureSchedule(request: PrayerScheduleRequest?): Flow<PrayerScheduleState> = flow {
        calls++
        lastRequest = request
        if (request == null) {
            emit(
                PrayerScheduleState.Unavailable(
                    reason = UnavailabilityReason.NO_LOCATION_CONFIGURED,
                    detail = null,
                    origin = origin(),
                ),
            )
            return@flow
        }
        if (failWithProviderError) {
            emit(PrayerScheduleState.Failed(detail = "provider exploded", origin = origin()))
            return@flow
        }
        val schedule = schedules[request.date]
        if (schedule == null) {
            emit(
                PrayerScheduleState.Unavailable(
                    reason = UnavailabilityReason.CALCULATION_NOT_POSSIBLE,
                    detail = null,
                    origin = origin(),
                ),
            )
        } else {
            emit(PrayerScheduleState.Available(schedule = schedule, origin = origin()))
        }
    }

    override suspend fun refresh(request: PrayerScheduleRequest?): PrayerScheduleState =
        ensureSchedule(request).first()

    private fun origin(): DataSourceInfo = DataSourceInfo(
        source = PrayerTimeSource.LOCAL_CALCULATION,
        providerId = "test",
        observedAt = Instant.EPOCH,
        isAvailableOffline = true,
    )
}

/** A location that can be changed mid-test, to exercise a location change. */
class FakeLocationRepository(initial: LocationInfo? = null) : LocationRepository {

    private val state = MutableStateFlow(initial)

    override val location: Flow<LocationInfo?> = state

    fun set(value: LocationInfo?) {
        state.value = value
    }

    override suspend fun saveManualLocation(
        label: String,
        latitude: Double,
        longitude: Double,
        timeZoneId: String,
    ): Boolean {
        val created = LocationInfo.create(label, latitude, longitude, timeZoneId) ?: return false
        state.value = created
        return true
    }

    override suspend fun saveDeviceLocation(
        label: String,
        latitude: Double,
        longitude: Double,
        timeZoneId: String,
        accuracyMetres: Double?,
        acquiredAtEpochMillis: Long,
    ): Boolean {
        val created = LocationInfo.create(
            label = label,
            latitude = latitude,
            longitude = longitude,
            timeZoneId = timeZoneId,
            origin = LocationOrigin.DEVICE_GPS,
            accuracyMetres = accuracyMetres,
            acquiredAt = Instant.ofEpochMilli(acquiredAtEpochMillis),
        ) ?: return false
        state.value = created
        return true
    }

    override suspend fun clearLocation() {
        state.value = null
    }
}

/** In-memory notification preferences with the same transactional-update semantics as DataStore. */
class FakePrayerNotificationPreferencesRepository(
    initial: PrayerNotificationSettings = PrayerNotificationSettings.DEFAULT,
) : PrayerNotificationPreferencesRepository {

    private val state = MutableStateFlow(initial)

    override val settings: Flow<PrayerNotificationSettings> = state

    /** Every settings object the repository ended up holding, in order. */
    val updates: MutableList<PrayerNotificationSettings> = mutableListOf()

    fun set(value: PrayerNotificationSettings) {
        state.value = value
    }

    override suspend fun update(transform: (PrayerNotificationSettings) -> PrayerNotificationSettings) {
        val next = transform(state.value)
        updates += next
        state.value = next
    }
}

/** Calculation preferences; the notification path only reads the profile and the time format. */
class FakeUserPreferencesRepository(
    initialSettings: PrayerSettings = PrayerSettings.DEFAULT,
    initialTheme: ThemeMode = ThemeMode.SYSTEM,
) : UserPreferencesRepository {

    private val settingsState = MutableStateFlow(initialSettings)
    private val themeState = MutableStateFlow(initialTheme)

    override val themeMode: Flow<ThemeMode> = themeState

    override suspend fun setThemeMode(mode: ThemeMode) {
        themeState.value = mode
    }

    override val prayerSettings: Flow<PrayerSettings> = settingsState

    override suspend fun updatePrayerSettings(transform: (PrayerSettings) -> PrayerSettings) {
        settingsState.value = transform(settingsState.value)
    }
}

/** A permission state a test controls directly. */
class FakePermissionStatusProvider(
    var current: NotificationPermissionStatus = NotificationPermissionStatus(
        notificationsAllowed = true,
        exactAlarmsAllowed = true,
    ),
) : NotificationPermissionStatusProvider {
    override fun status(): NotificationPermissionStatus = current
}

/** Captures what would have been shown, instead of showing it. */
class FakePrayerNotificationPresenter(
    var accept: Boolean = true,
) : PrayerNotificationPresenter {

    val delivered: MutableList<PrayerReminderDelivery> = mutableListOf()

    var throwOnPresent: Throwable? = null

    override fun present(delivery: PrayerReminderDelivery): Boolean {
        throwOnPresent?.let { throw it }
        delivered += delivery
        return accept
    }
}

/** An Azan source that can be made available or unavailable, to exercise both branches. */
class FakeAzanAudioSource(private val source: String? = null) : AzanAudioSource {
    override fun resolve(): String? = source
    override fun attribution(): String? = if (source == null) null else "test recording"
}

/** An audio engine whose every state transition is observable. */
class FakeAudioPlaybackEngine(
    var startSucceeds: Boolean = true,
) : AudioPlaybackEngine {

    var playing: Boolean = false
        private set
    var startCalls: Int = 0
        private set
    var stopCalls: Int = 0
        private set
    var releaseCalls: Int = 0
        private set

    /** Sources passed to [start], in order. */
    val startedSources: MutableList<String> = mutableListOf()

    var throwOnStart: Throwable? = null

    override fun isPlaying(): Boolean = playing

    override fun start(source: String): Boolean {
        startCalls++
        startedSources += source
        throwOnStart?.let { throw it }
        playing = startSucceeds
        return startSucceeds
    }

    override fun stop() {
        stopCalls++
        playing = false
    }

    override fun release() {
        releaseCalls++
        playing = false
    }
}

/** Records preview requests without making a sound. */
class FakeNotificationSoundPreviewer(
    var result: Boolean = true,
) : NotificationSoundPreviewer {

    val previewed: MutableList<NotificationSound> = mutableListOf()
    var stopCalls: Int = 0

    override fun preview(sound: NotificationSound): Boolean {
        previewed += sound
        return result
    }

    override fun stopPreview() {
        stopCalls++
    }

    override fun release() = stopPreview()
}

/**
 * Builds a complete schedule for [date] at [zone].
 *
 * Values are explicit and obviously synthetic (01:00, 02:00, …) rather than plausible prayer times,
 * so a failing assertion can never be confused with an assertion about real astronomical output.
 */
fun testSchedule(
    date: LocalDate,
    zone: ZoneId = ZoneId.of("Asia/Dhaka"),
    fajr: LocalTime = LocalTime.of(5, 0),
    dhuhr: LocalTime = LocalTime.of(12, 0),
    asr: LocalTime = LocalTime.of(15, 0),
    maghrib: LocalTime = LocalTime.of(18, 0),
    isha: LocalTime = LocalTime.of(19, 0),
    sunrise: LocalTime = LocalTime.of(6, 0),
    profile: PrayerCalculationProfile = PrayerCalculationProfile(),
): PrayerSchedule {
    fun at(time: LocalTime): ZonedDateTime = ZonedDateTime.of(date, time, zone)
    return PrayerSchedule(
        date = date,
        location = testLocation(zone),
        profile = profile,
        prayers = listOf(
            PrayerTimeEntry(PrayerName.FAJR, at(fajr)),
            PrayerTimeEntry(PrayerName.DHUHR, at(dhuhr)),
            PrayerTimeEntry(PrayerName.ASR, at(asr)),
            PrayerTimeEntry(PrayerName.MAGHRIB, at(maghrib)),
            PrayerTimeEntry(PrayerName.ISHA, at(isha)),
        ),
        solarEvents = listOf(
            SolarEventEntry(SolarEvent.SUNRISE, at(sunrise)),
            SolarEventEntry(SolarEvent.SOLAR_NOON, at(dhuhr)),
            SolarEventEntry(SolarEvent.SUNSET, at(maghrib)),
        ),
        generatedAt = Instant.EPOCH,
    )
}

/** A location in [zone], so schedule times and the observance zone agree. */
fun testLocation(zone: ZoneId = ZoneId.of("Asia/Dhaka")): LocationInfo = LocationInfo(
    label = "Test location",
    latitude = 23.8103,
    longitude = 90.4125,
    timeZoneId = zone.id,
    origin = LocationOrigin.MANUAL_ENTRY,
)

/**
 * A clock a test can move.
 *
 * Deterministic time is what lets the scheduling rules be tested without waiting for anything: a
 * "time change" or "the day rolled over" scenario is a single assignment here rather than a real
 * delay or a real prayer time.
 */
class MutableClock(
    var current: Instant,
    private val zone: ZoneId = ZoneId.of("UTC"),
) : Clock() {

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)

    override fun instant(): Instant = current

    /** Advances the clock, for scenarios such as a prayer time passing. */
    fun advanceTo(instant: Instant) {
        current = instant
    }
}
