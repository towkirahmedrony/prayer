package io.github.towkirahmedrony.salah.feature.ramadan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.towkirahmedrony.salah.core.domain.calendar.FastingCountdown
import io.github.towkirahmedrony.salah.core.domain.calendar.FastingCountdownResolver
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationCoordinator
import io.github.towkirahmedrony.salah.core.domain.notification.ReconcileOutcome
import io.github.towkirahmedrony.salah.core.domain.notification.ReconcileReason
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleRequest
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleState
import io.github.towkirahmedrony.salah.core.domain.repository.CalendarRepository
import io.github.towkirahmedrony.salah.core.domain.repository.LocationRepository
import io.github.towkirahmedrony.salah.core.domain.repository.PrayerNotificationPreferencesRepository
import io.github.towkirahmedrony.salah.core.domain.repository.PrayerScheduleRepository
import io.github.towkirahmedrony.salah.core.domain.repository.UserPreferencesRepository
import io.github.towkirahmedrony.salah.core.model.CalendarSettings
import io.github.towkirahmedrony.salah.core.model.PrayerNotificationSettings
import io.github.towkirahmedrony.salah.core.model.PrayerSettings
import io.github.towkirahmedrony.salah.core.model.RamadanWindow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

/**
 * Drives the Ramadan screen.
 *
 * Its one non-obvious responsibility is **which days to load**. The Ramadan window comes from
 * [CalendarRepository], and the screen asks the existing offline-first [PrayerScheduleRepository] for a
 * schedule for each of those dates — the same call `HomeViewModel` and the notification coordinator
 * make. There is no second prayer engine and no second cache, so a time shown in the Ramadan table is
 * literally the time the Home timetable and the reminder were built from.
 *
 * That also means the whole table works offline: the window is derived from the platform's Hijri table
 * and the schedules are local astronomical arithmetic, so nothing here needs a network.
 *
 * Sehri and Iftar reminders are written through [PrayerNotificationPreferencesRepository] and applied by
 * the same [PrayerNotificationCoordinator] every other reminder change goes through. That is deliberate:
 * a second scheduling path would be a second place for duplicate or stale alarms to appear, and the
 * coordinator's derived cancellation is what removes a Sehri alarm when Ramadan ends.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RamadanViewModel(
    private val calendarRepository: CalendarRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val locationRepository: LocationRepository,
    private val prayerScheduleRepository: PrayerScheduleRepository,
    private val notificationPreferences: PrayerNotificationPreferencesRepository,
    private val coordinator: PrayerNotificationCoordinator,
    private val uiStateFactory: RamadanUiStateFactory,
    private val countdownResolver: FastingCountdownResolver = FastingCountdownResolver(),
    private val clock: Clock = Clock.systemUTC(),
) : ViewModel() {

    private val message = MutableStateFlow<RamadanMessage?>(null)

    /** One schedule per date of the window, keyed by date so a row can find its own. */
    private val window: Flow<RamadanWindow?> = calendarRepository.observeRamadanWindow()

    private val schedules: Flow<Map<LocalDate, PrayerScheduleState>> = window.flatMapLatest { current ->
        if (current == null) flowOf(emptyMap()) else combineScheduleStates(current.dates)
    }

    private val settingsSnapshot: Flow<SettingsSnapshot> = combine(
        userPreferencesRepository.calendarSettings,
        userPreferencesRepository.prayerSettings,
        notificationPreferences.settings,
    ) { calendar, prayer, notifications -> SettingsSnapshot(calendar, prayer, notifications) }

    private val windowSnapshot: Flow<WindowSnapshot> = combine(
        window,
        schedules,
        locationRepository.location,
    ) { current, states, location -> WindowSnapshot(current, states, location != null) }

    val uiState: StateFlow<RamadanUiState> = combine(
        calendarRepository.observeTodayDate(),
        settingsSnapshot,
        windowSnapshot,
        message,
        userPreferencesRepository.appLanguage,
    ) { today, settings, windowState, currentMessage, language ->
        val state = uiStateFactory.create(
            RamadanUiStateInputs(
                today = today,
                calendarSettings = settings.calendar,
                prayerSettings = settings.prayer,
                notificationSettings = settings.notifications,
                window = windowState.window,
                schedules = windowState.schedules,
                locationConfigured = windowState.locationConfigured,
            ),
            // The interface language, so the Hijri rows, weekday names and heading follow the language
            // selector rather than the device locale. A change re-renders the same window and the same
            // times: this is an input to the formatting and to nothing else.
            systemLocale = language.locale() ?: Locale.getDefault(),
        )
        // The factory is pure and knows nothing about transient feedback, so it is attached here from a
        // value that was set by a real reconciliation result.
        (state as? RamadanUiState.Ready)?.copy(message = currentMessage) ?: state
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MILLIS),
        initialValue = RamadanUiState.Loading,
    )

    /**
     * The next fasting event and the time remaining, refreshed on a one-second tick.
     *
     * Kept as its own flow rather than folded into [uiState] on purpose: the countdown changes every
     * second and the table does not, so separating them means the one-second tick recomposes only the
     * countdown composable instead of the whole Ramadan list. The tick recomputes a pure subtraction
     * over already-calculated schedules — no prayer recalculation, no network, no calendar conversion —
     * and only runs while the screen is actually subscribed.
     *
     * It is `null` whenever there is no fasting event Salah can state: outside Ramadan, on a day with no
     * timetable, or after Iftar on the last day. The screen shows nothing rather than a misleading
     * countdown in those cases.
     */
    val countdown: StateFlow<FastingCountdown?> = combine(
        calendarRepository.observeTodayDate(),
        window,
        schedules,
        countdownTicks(),
    ) { today, currentWindow, states, instant ->
        countdownResolver.resolve(
            today = today,
            now = instant,
            window = currentWindow,
            todaySchedule = states.availableScheduleOn(today),
            tomorrowSchedule = states.availableScheduleOn(today.plusDays(1)),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MILLIS),
        initialValue = null,
    )

    /** Emits the current instant, then once a second while the countdown is observed. */
    private fun countdownTicks(): Flow<Instant> = flow {
        while (true) {
            emit(clock.instant())
            delay(COUNTDOWN_TICK_MILLIS)
        }
    }

    // --- Reminders ---------------------------------------------------------------

    /** Turns the Sehri reminder on or off, then reconciles. */
    fun setSehriReminderEnabled(enabled: Boolean) = updateReminders { current ->
        current.withSehriReminder(enabled)
    }

    /** Moves the Sehri lead-in to the next offered value, then reconciles. */
    fun shiftSehriLead(forward: Boolean) = updateReminders { current ->
        current.withSehriReminder(
            enabled = current.sehriReminderEnabled,
            leadMinutes = nextChoice(
                current = current.sehriLeadMinutes,
                choices = PrayerNotificationSettings.SEHRI_LEAD_MINUTE_CHOICES,
                forward = forward,
            ),
        )
    }

    /** Turns the Iftar reminder on or off, then reconciles. */
    fun setIftarReminderEnabled(enabled: Boolean) = updateReminders { current ->
        current.withIftarReminder(enabled)
    }

    /**
     * Turns the **precautionary Imsak** reminder on or off, then reconciles.
     *
     * A reminder is only ever planned when an Imsak interval is also configured, so switching this on
     * with no interval schedules nothing — the card only offers it once an interval exists.
     */
    fun setImsakReminderEnabled(enabled: Boolean) = updateReminders { current ->
        current.withImsakReminder(enabled)
    }

    /**
     * Turns the precautionary Imsak time on or off.
     *
     * Turning it on configures the documented default interval (it never leaves Imsak on with no rule);
     * turning it off clears the rule entirely, so no Imsak time is shown and none is scheduled. The
     * change is followed by a reconciliation because the Imsak reminder's time moves with the interval.
     */
    fun setImsakEnabled(enabled: Boolean) {
        message.value = null
        viewModelScope.launch {
            userPreferencesRepository.updateCalendarSettings { current ->
                if (enabled) {
                    current.copy(
                        imsakMinutesBeforeFajr = current.imsakMinutesBeforeFajr
                            ?: CalendarSettings.DEFAULT_IMSAK_MINUTES,
                    )
                } else {
                    current.withoutImsak()
                }
            }
            applyReconcile()
        }
    }

    /** Moves the configured Imsak interval to the next offered value. A no-op when none is configured. */
    fun shiftImsakMinutes(forward: Boolean) {
        message.value = null
        viewModelScope.launch {
            userPreferencesRepository.updateCalendarSettings { current ->
                val currentMinutes = current.imsakMinutesBeforeFajr ?: return@updateCalendarSettings current
                val choices = CalendarSettings.IMSAK_MINUTE_CHOICES
                val index = choices.indexOf(currentMinutes).let { if (it >= 0) it else 0 }
                val next = choices[(index + if (forward) 1 else -1).coerceIn(0, choices.lastIndex)]
                current.copy(imsakMinutesBeforeFajr = next)
            }
            applyReconcile()
        }
    }

    /** Moves the Iftar lead-in to the next offered value, then reconciles. */
    fun shiftIftarLead(forward: Boolean) = updateReminders { current ->
        current.withIftarReminder(
            enabled = current.iftarReminderEnabled,
            leadMinutes = nextChoice(
                current = current.iftarLeadMinutes,
                choices = PrayerNotificationSettings.IFTAR_LEAD_MINUTE_CHOICES,
                forward = forward,
            ),
        )
    }

    /** Clears the transient message once it has been shown. */
    fun dismissMessage() {
        message.value = null
    }

    private fun updateReminders(transform: (PrayerNotificationSettings) -> PrayerNotificationSettings) {
        message.value = null
        viewModelScope.launch {
            // Persist first, then reconcile: a reminder scheduled from stale settings for even a moment
            // is exactly the ghost the reconciliation exists to remove.
            notificationPreferences.update(transform)
            applyReconcile()
        }
    }

    /** Reconciles once and turns the outcome into the card's transient message. */
    private suspend fun applyReconcile() {
        when (val outcome = coordinator.reconcile(ReconcileReason.SETTINGS_CHANGED)) {
            is ReconcileOutcome.Applied -> {
                message.value = if (outcome.datesWithSchedule.isEmpty()) {
                    RamadanMessage.NoSchedule
                } else {
                    RamadanMessage.ScheduleUpdated
                }
            }

            is ReconcileOutcome.Disabled,
            is ReconcileOutcome.NoLocation,
            is ReconcileOutcome.Failed,
            -> message.value = RamadanMessage.NoSchedule
        }
    }

    /**
     * Combines one schedule flow per date into a single map.
     *
     * Written out with an explicit parameter type rather than inferred because the iterable form of
     * `combine` is reified over the element type, and leaving it implicit would make the flow's type
     * depend on inference succeeding rather than on what this function declares.
     */
    private fun combineScheduleStates(
        dates: List<LocalDate>,
    ): Flow<Map<LocalDate, PrayerScheduleState>> {
        val perDate: List<Flow<Pair<LocalDate, PrayerScheduleState>>> = dates.map { date ->
            scheduleStateFor(date).map { state -> date to state }
        }
        return combine(perDate) { states: Array<Pair<LocalDate, PrayerScheduleState>> -> states.toMap() }
    }

    private fun nextChoice(current: Int, choices: List<Int>, forward: Boolean): Int {
        val index = choices.indexOf(current).let { if (it >= 0) it else 0 }
        return choices[(index + if (forward) 1 else -1).coerceIn(0, choices.lastIndex)]
    }

    /**
     * The current schedule state for one date.
     *
     * A `null` request means no location is configured; the repository reports that state explicitly
     * rather than Salah inventing a coordinate. The request is keyed by the date, the location and the
     * persisted calculation profile, so changing any of them produces a genuinely new schedule instead
     * of a cached one from before the change.
     */
    private fun scheduleStateFor(date: LocalDate): Flow<PrayerScheduleState> = combine(
        locationRepository.location,
        userPreferencesRepository.prayerSettings,
    ) { location, settings ->
        location?.let { configured ->
            PrayerScheduleRequest(
                date = date,
                location = configured,
                profile = settings.toProfile(),
            )
        }
    }.distinctUntilChanged().flatMapLatest { request -> prayerScheduleRepository.ensureSchedule(request) }

    /** The schedule for [date] when one is available, or `null`. */
    private fun Map<LocalDate, PrayerScheduleState>.availableScheduleOn(date: LocalDate) =
        (this[date] as? PrayerScheduleState.Available)?.schedule

    private data class SettingsSnapshot(
        val calendar: CalendarSettings,
        val prayer: PrayerSettings,
        val notifications: PrayerNotificationSettings,
    )

    private data class WindowSnapshot(
        val window: RamadanWindow?,
        val schedules: Map<LocalDate, PrayerScheduleState>,
        val locationConfigured: Boolean,
    )

    private companion object {
        const val SUBSCRIBE_TIMEOUT_MILLIS = 5_000L

        /** How often the fasting countdown refreshes. One second, so an `MM:SS` reading stays honest. */
        const val COUNTDOWN_TICK_MILLIS = 1_000L
    }
}
