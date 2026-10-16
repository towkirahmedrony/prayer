package io.github.towkirahmedrony.salah.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.towkirahmedrony.salah.StartupTrace
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleRequest
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleState
import io.github.towkirahmedrony.salah.core.domain.repository.CalendarRepository
import io.github.towkirahmedrony.salah.core.domain.repository.PrayerScheduleRepository
import io.github.towkirahmedrony.salah.core.domain.repository.ProhibitedPeriodsRepository
import io.github.towkirahmedrony.salah.core.domain.startup.PrayerSettingsState
import io.github.towkirahmedrony.salah.core.domain.startup.StartupSnapshot
import io.github.towkirahmedrony.salah.core.domain.startup.StoredLocationState
import io.github.towkirahmedrony.salah.core.domain.repository.UserPreferencesRepository
import io.github.towkirahmedrony.salah.core.domain.dashboard.DashboardConfig
import io.github.towkirahmedrony.salah.core.model.CalendarSnapshot
import io.github.towkirahmedrony.salah.core.model.DashboardCard
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.PrayerSchedule
import io.github.towkirahmedrony.salah.core.model.PrayerSettings
import io.github.towkirahmedrony.salah.core.model.ProhibitedPeriodsState
import io.github.towkirahmedrony.salah.core.model.RamadanWindow
import io.github.towkirahmedrony.salah.core.model.UnavailabilityReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Provides the Home screen with a single immutable state.
 *
 * Explicitly **not** present here: any astronomical or calendar computation. The view model only
 * assembles flows — the location and the prayer settings, the device clock, the schedule
 * repository and the prohibited-period repository.
 *
 * **Home-first, progressive loading.** Home is gated only by the data it actually needs to render
 * correct prayer information: the configured location, the prayer settings, the moment, and today's
 * schedule for that location. Everything else is folded in as it arrives and can never hold the
 * screen at [HomeUiState.Loading]:
 *
 *  - the **calendar snapshot** is a progressive input. The header shows a placeholder for the date
 *    until it lands instead of keeping the whole screen on the skeleton;
 *  - the **prohibited windows** are informational, so they are seeded with an honest "not available"
 *    and merged in later;
 *  - **tomorrow's schedule** is asked for only so Isha's period can be closed at the next day's Fajr;
 *    until it arrives that one period is reported as unresolved rather than ended at a guessed
 *    boundary, and nothing else on the screen waits for it.
 *
 * The schedule chain ([request] → [scheduleState]) is shared through a single [StateFlow], so the
 * repository is asked for one schedule per request rather than once per downstream consumer. That is
 * what keeps the clock tick from producing redundant calculations: the request is de-duplicated, and
 * a request that has not actually changed never reaches the repository again.
 *
 * Both countdowns — the remaining time in the current period and the time until the next prayer —
 * are recomputed from the same tick, so a single [moment] drives every clock reading on the screen
 * and the two can never disagree about what time it is.
 *
 * **Where the location and settings come from.** Both are read from the start-up
 * [StartupSnapshot], which has already read them into a [StateFlow] before Home is composed. That
 * matters because Home's essential state is assembled during composition: reading them from the
 * cold repositories here would make the prayer times wait on a dispatch onto a main thread that is
 * busy with the frame being composed. The snapshot is kept current, so a location or settings change
 * still recalculates exactly as before.
 *
 * The tick runs only while the screen is subscribed ([SharingStarted.WhileSubscribed]), so nothing
 * keeps ticking in the background once the user leaves Home. No WorkManager job is involved: a
 * countdown is not a deferrable task, and a periodic job would be the wrong mechanism for it. Prayer
 * *notifications* are a later phase, and their extension point is [PrayerScheduleRepository]: a
 * scheduler can depend on that interface directly and never needs the Home screen to be visible.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val startup: StartupSnapshot,
    private val prayerScheduleRepository: PrayerScheduleRepository,
    private val calendarRepository: CalendarRepository,
    private val prohibitedPeriodsRepository: ProhibitedPeriodsRepository,
    private val uiStateFactory: HomeUiStateFactory,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val clock: Clock,
) : ViewModel() {

    /**
     * One-shot guards for the startup trace.
     *
     * The tick re-emits [uiState] once a second, so without these the ready marks would be recorded
     * once a second for as long as Home is on screen and the first one — the measurement — could not
     * be picked out of the log. All of them are written from the main thread (the flows are collected
     * on `viewModelScope`), so a plain flag is sufficient.
     */
    private var homeLocationReadyMarked = false
    private var homeCriticalDataReadyMarked = false
    private var homeReadyMarked = false
    private var homeScheduleReadyMarked = false

    /**
     * The configured location, marked the first time Home has one.
     *
     * `home_location_ready` measures the transition the header depends on, not the composition of the
     * screen: the location arriving into Home is the point from which a correct place name can be
     * shown. Nothing is emitted while the snapshot is still reading, so Home can never see an
     * absence of location that is really an absence of an answer.
     */
    private val location: Flow<LocationInfo?> = startup.storedLocation
        .transform { state ->
            if (state is StoredLocationState.Loaded) emit(state.location)
        }
        .onEach { configured ->
            if (configured != null && !homeLocationReadyMarked) {
                homeLocationReadyMarked = true
                StartupTrace.mark("home_location_ready")
            }
        }

    private val settings: Flow<PrayerSettings> = startup.prayerSettings
        .transform { state ->
            if (state is PrayerSettingsState.Loaded) emit(state.settings)
        }

    /**
     * The current instant, re-read every second while the screen is visible.
     *
     * The zone is read fresh on every tick rather than taken from the injected [clock], because
     * [Clock] captures its zone when it is created: reading it here means a time-zone change on
     * the device takes effect immediately instead of after a restart.
     *
     * Shared, so exactly one timer runs no matter how many consumers observe it.
     */
    private val moment: StateFlow<ZonedDateTime> = flow {
        while (true) {
            emit(ZonedDateTime.ofInstant(clock.instant(), ZoneId.systemDefault()))
            delay(TICK_INTERVAL_MILLIS)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MILLIS),
        initialValue = ZonedDateTime.ofInstant(clock.instant(), ZoneId.systemDefault()),
    )

    /** `null` means no location is configured; the repository reports that state explicitly. */
    private val request: Flow<PrayerScheduleRequest?> = combine(location, settings, moment) { configured, currentSettings, at ->
        configured?.let { location ->
            PrayerScheduleRequest(
                // The day to calculate is the day it is *at the location*, not on the device.
                date = at.withZoneSameInstant(location.zoneId).toLocalDate(),
                location = location,
                profile = currentSettings.toProfile(),
            )
        }
    }.distinctUntilChanged()

    /**
     * Today's schedule, shared.
     *
     * A single [StateFlow] rather than a cold flow: the schedule is read by the schedule part and by
     * the prohibited windows, and a cold flow would ask the repository once per consumer — two
     * calculations for one request. [PrayerScheduleState.Loading] is only ever the initial value; the
     * repository always answers with a real state.
     */
    private val scheduleState: StateFlow<PrayerScheduleState> =
        request.flatMapLatest { current ->
            flow {
                // Brackets resolving the schedule for this request. A cache hit resolves immediately;
                // a cache miss includes the engine calculation, which the repository runs on
                // `Dispatchers.Default` so it never touches the main thread. Marked here rather than
                // inside the repository because the repository is unit-tested on the JVM, where the
                // Android-only trace cannot run.
                StartupTrace.mark("home_calculation_start")
                emitAll(prayerScheduleRepository.ensureSchedule(current))
                StartupTrace.mark("home_calculation_done")
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MILLIS),
            initialValue = PrayerScheduleState.Loading,
        )

    /**
     * Tomorrow's schedule, asked for through the same repository as today's.
     *
     * It exists for one reason: Isha's period closes at the next day's Fajr, and that boundary is
     * not on today's schedule. Reusing the repository means the same engine, the same location and
     * the same profile produce it — a separately calculated "next Fajr" would be a second prayer-time
     * engine, and it would eventually disagree with the timetable shown underneath it.
     *
     * The repository caches by the full request, so this adds one calculation per day, not one per
     * tick, and it is keyed the same way the notification reconciliation already keys the day it
     * pre-schedules.
     */
    private val nextDayRequest: Flow<PrayerScheduleRequest?> =
        combine(location, settings, moment) { configured, currentSettings, at ->
            configured?.let { location ->
                PrayerScheduleRequest(
                    date = at.withZoneSameInstant(location.zoneId).toLocalDate().plusDays(1),
                    location = location,
                    profile = currentSettings.toProfile(),
                )
            }
        }.distinctUntilChanged()

    private val nextDayScheduleState: StateFlow<PrayerScheduleState> =
        nextDayRequest.flatMapLatest { current -> prayerScheduleRepository.ensureSchedule(current) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MILLIS),
                // `Loading` carries no Fajr, which is the honest answer for the moment before
                // tomorrow has been calculated: the period is reported as unresolved, not ended at
                // an invented boundary.
                initialValue = PrayerScheduleState.Loading,
            )

    /**
     * Tomorrow's schedule itself, or `null` while it is not available.
     *
     * Passed on whole rather than reduced to its Fajr here: the same schedule closes Isha's period and
     * answers "what is the next prayer" once today's last prayer has begun, and
     * [io.github.towkirahmedrony.salah.core.domain.NextPrayerResolver] is the place that decides whether
     * the candidate really is tomorrow's before using it.
     */
    private val nextDaySchedule: Flow<PrayerSchedule?> = nextDayScheduleState
        .map { state -> (state as? PrayerScheduleState.Available)?.schedule }
        .distinctUntilChanged()

    private val prohibitedState: Flow<ProhibitedPeriodsState> =
        scheduleState.flatMapLatest { state ->
            prohibitedPeriodsRepository.observePeriods((state as? PrayerScheduleState.Available)?.schedule)
        }

    private val schedulePart: Flow<SchedulePart> =
        combine(settings, scheduleState) { currentSettings, state -> SchedulePart(currentSettings, state) }

    /**
     * The Home-critical inputs, together.
     *
     * Everything here is required to render correct prayer information, and nothing else is: the
     * location, the settings the times were calculated with, the current moment, and a schedule that
     * has actually been resolved. While the schedule is still [PrayerScheduleState.Loading] this does
     * not emit, so Home stays on its skeleton until real times exist rather than flashing a screen
     * with none.
     */
    private val criticalInputs: Flow<CriticalInputs> =
        combine(location, schedulePart, moment) { configuredLocation, part, currentMoment ->
            CriticalInputs(
                location = configuredLocation,
                settings = part.settings,
                scheduleState = part.state,
                now = currentMoment,
            )
        }.filter { it.scheduleState != PrayerScheduleState.Loading }
            .onEach {
                if (!homeCriticalDataReadyMarked) {
                    homeCriticalDataReadyMarked = true
                    StartupTrace.mark("home_critical_data_ready")
                }
            }

    /**
     * The calendar snapshot, or `null` until the repository has produced one.
     *
     * Seeded with `null` so a calendar that has not arrived cannot keep Home at
     * [HomeUiState.Loading]. The header then shows a placeholder for the date, which is the same
     * latency the skeleton would have had, without holding the prayer times behind it.
     */
    private val calendarFlow: Flow<CalendarSnapshot?> = flow {
        emit(null)
        emitAll(calendarRepository.observeToday())
    }

    /**
     * Prohibited windows, seeded with an honest "not available".
     *
     * The windows are derived from the schedule, which is on the critical path anyway, but they stay
     * off it: seeding a value keeps an informational section from ever gating the prayer content for
     * a frame.
     */
    private val prohibitedFlow: Flow<ProhibitedPeriodsState> =
        prohibitedState.onStart {
            emit(ProhibitedPeriodsState.NotAvailable(reason = UnavailabilityReason.UNKNOWN))
        }

    /**
     * The user's Home-dashboard layout.
     *
     * Read from the one preference store, so it arrives before the first frame and cannot make the
     * screen flicker: the very first [HomeUiState.Ready] already carries the saved arrangement. It
     * is deliberately independent of the prayer data — a card's own state changing never reorders
     * the dashboard — and hiding a card only changes this value, never the feature behind it.
     */
    private val dashboardConfig: Flow<DashboardConfig> = userPreferencesRepository.dashboardConfig

    /**
     * The estimated Ramadan window, from the same repository the Ramadan screen reads.
     *
     * On its own flow because it is a progressive input: the Ramadan card is context-aware and the
     * rest of Home must not wait for it. Seeded with `null` so a window that has not arrived yet
     * cannot hold the whole dashboard back — when it is late or unavailable the card is simply not
     * shown, which is the honest "not near Ramadan" state rather than a claim about the calendar.
     */
    private val ramadanWindow: Flow<RamadanWindow?> = calendarRepository.observeRamadanWindow()
        .onStart { emit(null) }

    /**
     * The inputs that are not on Home's critical path, gathered so the final combine stays within
     * the library's typed overloads: the calendar snapshot, the prohibited windows, tomorrow's
     * schedule and the Ramadan window.
     */
    private val contextInputs: Flow<ContextInputs> = combine(
        calendarFlow,
        prohibitedFlow,
        nextDaySchedule,
        ramadanWindow,
    ) { calendar, prohibited, tomorrowsSchedule, window ->
        ContextInputs(calendar, prohibited, tomorrowsSchedule, window)
    }

    val uiState: StateFlow<HomeUiState> = combine(
        criticalInputs,
        contextInputs,
        dashboardConfig,
    ) { critical, context, dashboard ->
        uiStateFactory.create(
            calendar = context.calendar,
            location = critical.location,
            settings = critical.settings,
            scheduleState = critical.scheduleState,
            prohibitedState = context.prohibited,
            now = critical.now,
            nextDaySchedule = context.nextDaySchedule,
            dashboard = dashboard,
            ramadanWindow = context.ramadanWindow,
        )
    }.onEach { state ->
        // The state becoming `Ready` is what makes Home renderable. The composable existing is not:
        // HomeScreen is only composed after the location gate has already opened, so a mark at
        // composition time would measure the gate, not Home.
        if (state is HomeUiState.Ready) {
            if (!homeReadyMarked) {
                homeReadyMarked = true
                StartupTrace.mark("home_ready")
            }
            // `timetableNote` is null exactly when a real schedule is being displayed. This is the
            // later and the stricter of the two: the first Home that carries prayer times, rather
            // than a note explaining why it has none.
            if (!homeScheduleReadyMarked && state.timetableNote == null) {
                homeScheduleReadyMarked = true
                StartupTrace.mark("home_schedule_ready")
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MILLIS),
        initialValue = HomeUiState.Loading,
    )

    /** Recalculates after a failure. The outcome reaches [uiState] as a value, never as a throw. */
    fun onRetry() {
        viewModelScope.launch {
            prayerScheduleRepository.refresh(request.first())
        }
    }

    /**
     * Shows or hides one dashboard card.
     *
     * Only the dashboard layout is written: the card's feature — its calculations, its reminder and
     * its background work — is untouched, and stays reachable through the app's navigation whether
     * or not the card is shown.
     */
    fun setCardEnabled(card: DashboardCard, enabled: Boolean) {
        updateDashboard { config -> config.withCardEnabled(card, enabled) }
    }

    /** Moves one card by [delta] positions within the dashboard, clamped to the ends. */
    fun moveCard(card: DashboardCard, delta: Int) {
        updateDashboard { config -> config.moved(card, delta) }
    }

    /** Restores the default dashboard layout. Resets no unrelated preference. */
    fun resetDashboard() {
        updateDashboard { config -> config.resetToDefault() }
    }

    /**
     * Writes a dashboard change, skipping the write when it changed nothing.
     *
     * The stored value is read first and compared against the normalised result, so a tap that
     * leaves the layout exactly as it was — a move at the top of the list, a toggle to the state it
     * is already in — does not touch the store. The read-modify-write itself happens inside the
     * repository's single transaction, so this pre-read only decides *whether* to write.
     */
    private fun updateDashboard(transform: (DashboardConfig) -> DashboardConfig) {
        viewModelScope.launch {
            val stored = userPreferencesRepository.dashboardConfig.first()
            val updated = transform(stored).normalized()
            if (updated != stored.normalized()) {
                userPreferencesRepository.updateDashboardConfig { updated }
            }
        }
    }

    private data class SchedulePart(
        val settings: PrayerSettings,
        val state: PrayerScheduleState,
    )

    /** The minimum inputs needed to render correct prayer information on Home. */
    private data class CriticalInputs(
        val location: LocationInfo?,
        val settings: PrayerSettings,
        val scheduleState: PrayerScheduleState,
        val now: ZonedDateTime,
    )

    /** The progressive, non-critical inputs, folded in as they arrive. */
    private data class ContextInputs(
        val calendar: CalendarSnapshot?,
        val prohibited: ProhibitedPeriodsState,
        val nextDaySchedule: PrayerSchedule?,
        val ramadanWindow: RamadanWindow?,
    )

    private companion object {
        const val SUBSCRIBE_TIMEOUT_MILLIS = 5_000L
        const val TICK_INTERVAL_MILLIS = 1_000L
    }
}
