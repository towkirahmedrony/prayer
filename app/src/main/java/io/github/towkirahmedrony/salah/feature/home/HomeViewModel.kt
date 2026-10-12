package io.github.towkirahmedrony.salah.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.towkirahmedrony.salah.StartupTrace
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleRequest
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleState
import io.github.towkirahmedrony.salah.core.domain.repository.CalendarRepository
import io.github.towkirahmedrony.salah.core.domain.repository.LocationRepository
import io.github.towkirahmedrony.salah.core.domain.repository.PrayerScheduleRepository
import io.github.towkirahmedrony.salah.core.domain.repository.ProhibitedPeriodsRepository
import io.github.towkirahmedrony.salah.core.domain.repository.UserPreferencesRepository
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.PrayerSettings
import io.github.towkirahmedrony.salah.core.model.ProhibitedPeriodsState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
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
 * The request the schedule is calculated for is derived from the location, the settings and the
 * current moment, then de-duplicated. That single [distinctUntilChanged] is what prevents
 * recalculation loops: the clock ticks every second, but a request that has not actually changed
 * never reaches the repository again. Changing the location, the calculation method, the Asr
 * method, the high-latitude rule, a manual adjustment, the device time zone or the calendar date
 * all produce a genuinely new request, which the repository answers by recalculating.
 *
 * The tick runs only while the screen is subscribed ([SharingStarted.WhileSubscribed]), so
 * nothing keeps ticking in the background once the user leaves Home. No WorkManager job is
 * involved: a countdown is not a deferrable task, and a periodic job would be the wrong
 * mechanism for it. Prayer *notifications* are a later phase, and their extension point is
 * [PrayerScheduleRepository]: a scheduler can depend on that interface directly and never needs
 * the Home screen to be visible.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val locationRepository: LocationRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val prayerScheduleRepository: PrayerScheduleRepository,
    private val calendarRepository: CalendarRepository,
    private val prohibitedPeriodsRepository: ProhibitedPeriodsRepository,
    private val uiStateFactory: HomeUiStateFactory,
    private val clock: Clock,
) : ViewModel() {

    private val location: Flow<LocationInfo?> = locationRepository.location

    private val settings: Flow<PrayerSettings> = userPreferencesRepository.prayerSettings

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

    private val scheduleState: Flow<PrayerScheduleState> =
        request.flatMapLatest { current -> prayerScheduleRepository.ensureSchedule(current) }

    private val prohibitedState: Flow<ProhibitedPeriodsState> =
        scheduleState.flatMapLatest { state ->
            prohibitedPeriodsRepository.observePeriods((state as? PrayerScheduleState.Available)?.schedule)
        }

    private val schedulePart: Flow<SchedulePart> =
        combine(settings, scheduleState) { currentSettings, state -> SchedulePart(currentSettings, state) }

    /**
     * One-shot guards for the startup trace.
     *
     * The tick above re-emits [uiState] once a second, so without these the ready marks would be
     * recorded once a second for as long as Home is on screen and the first one — the measurement —
     * could not be picked out of the log.
     */
    private var homeReadyMarked = false
    private var homeScheduleReadyMarked = false

    val uiState: StateFlow<HomeUiState> = combine(
        calendarRepository.observeToday(),
        location,
        schedulePart,
        prohibitedState,
        moment,
    ) { calendar, configuredLocation, part, prohibited, currentMoment ->
        uiStateFactory.create(
            calendar = calendar,
            location = configuredLocation,
            settings = part.settings,
            scheduleState = part.state,
            prohibitedState = prohibited,
            now = currentMoment,
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

    private data class SchedulePart(
        val settings: PrayerSettings,
        val state: PrayerScheduleState,
    )

    private companion object {
        const val SUBSCRIBE_TIMEOUT_MILLIS = 5_000L
        const val TICK_INTERVAL_MILLIS = 1_000L
    }
}
