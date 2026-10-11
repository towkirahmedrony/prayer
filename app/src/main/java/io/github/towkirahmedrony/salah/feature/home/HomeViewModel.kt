package io.github.towkirahmedrony.salah.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleState
import io.github.towkirahmedrony.salah.core.domain.repository.CalendarRepository
import io.github.towkirahmedrony.salah.core.domain.repository.LocationRepository
import io.github.towkirahmedrony.salah.core.domain.repository.PrayerScheduleRepository
import io.github.towkirahmedrony.salah.core.domain.repository.ProhibitedPeriodsRepository
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.ProhibitedPeriodsState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * Provides the Home screen with a single immutable state.
 *
 * All sources are local: the device clock through [CalendarRepository], and locally stored
 * preferences and location through their repositories. Nothing here performs network work, and
 * no API failure can crash the screen — failures arrive as values that the UI explains.
 *
 * The countdown only ticks while a real schedule exists. When the prayer engine is not
 * configured the time source emits once, so the UI stays static instead of running a fake
 * countdown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val locationRepository: LocationRepository,
    private val prayerScheduleRepository: PrayerScheduleRepository,
    private val calendarRepository: CalendarRepository,
    private val prohibitedPeriodsRepository: ProhibitedPeriodsRepository,
    private val uiStateFactory: HomeUiStateFactory,
    private val clock: Clock,
) : ViewModel() {

    private val location: Flow<LocationInfo?> = locationRepository.location

    private val scheduleState: Flow<PrayerScheduleState> = location.flatMapLatest { configured ->
        prayerScheduleRepository.observeSchedule(configured, LocalDate.now(clock))
    }

    private val prohibitedState: Flow<ProhibitedPeriodsState> = location.flatMapLatest { configured ->
        prohibitedPeriodsRepository.observePeriods(configured, LocalDate.now(clock))
    }

    private val now: Flow<ZonedDateTime> = scheduleState.flatMapLatest { state ->
        if (state is PrayerScheduleState.Available) tickingClock() else flowOf(ZonedDateTime.now(clock))
    }

    val uiState: StateFlow<HomeUiState> = combine(
        calendarRepository.observeToday(),
        location,
        scheduleState,
        prohibitedState,
        now,
    ) { calendar, configuredLocation, schedule, prohibited, currentMoment ->
        uiStateFactory.create(
            calendar = calendar,
            location = configuredLocation,
            scheduleState = schedule,
            prohibitedState = prohibited,
            now = currentMoment,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(subscribeTimeoutMillis),
        initialValue = HomeUiState.Loading,
    )

    /** Re-runs the provider lookup after a failure. The result reaches [uiState] as a value. */
    fun onRetry() {
        viewModelScope.launch {
            val configured = location.first()
            prayerScheduleRepository.refresh(configured, LocalDate.now(clock))
        }
    }

    private fun tickingClock(): Flow<ZonedDateTime> = flow {
        while (true) {
            emit(ZonedDateTime.now(clock))
            delay(1_000L)
        }
    }

    private companion object {
        const val subscribeTimeoutMillis = 5_000L
    }
}
