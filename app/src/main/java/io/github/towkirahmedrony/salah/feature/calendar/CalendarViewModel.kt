package io.github.towkirahmedrony.salah.feature.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.towkirahmedrony.salah.core.domain.location.LocationDayZone
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleRequest
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleState
import io.github.towkirahmedrony.salah.core.domain.repository.CalendarRepository
import io.github.towkirahmedrony.salah.core.domain.repository.PrayerScheduleRepository
import io.github.towkirahmedrony.salah.core.domain.repository.UserPreferencesRepository
import io.github.towkirahmedrony.salah.core.domain.startup.PrayerSettingsState
import io.github.towkirahmedrony.salah.core.domain.startup.StartupSnapshot
import io.github.towkirahmedrony.salah.core.domain.startup.StoredLocationState
import io.github.towkirahmedrony.salah.core.model.AppLanguage
import io.github.towkirahmedrony.salah.core.model.CalendarSettings
import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.PrayerSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Drives the Calendar screen.
 *
 * Holds exactly two pieces of state of its own — which month is displayed and which day is selected —
 * and derives everything else from the calendar repository and the stored calendar settings. It
 * performs no conversion and no formatting: it asks the repository for a month *of the selected
 * calendar system*, which already carries the Bangla date, the Hijri date, the Ramadan number and the
 * occasions of every day in it, and hands the whole thing to [CalendarUiStateFactory].
 *
 * **The displayed month is a Gregorian anchor, not a `YearMonth`.** A Gregorian `YearMonth` cannot
 * express "the previous Bangla month" or "the next Hijri month", because those months do not line up
 * with Gregorian ones. So the screen holds a Gregorian day inside the displayed month and asks the
 * repository for the month of the selected system that contains it; previous and next move to the day
 * before the month's start and the day after its end, which is one month of *that* system in either
 * direction. This is what makes month navigation follow the selected calendar.
 *
 * **Which month.** "Today" is read in the *configured location's* zone, not the injected [Clock]'s zone
 * and not the device's. The clock is deliberately bound to UTC so that every consumer converts
 * explicitly, and the day a month is anchored on has to be the same day the repository marks as today
 * and the prayer timetable is calculated for — otherwise the screen can open on the month before the
 * one containing the day it is highlighting. [LocationDayZone] resolves it, so this screen and the
 * calendar repository answer that question from one place; with no location configured the device's own
 * zone answers, which is the behaviour this screen had before.
 *
 * **The selection always stays inside the displayed month.** Navigating months with no selection in
 * view would leave the details card describing a day the user cannot see, so navigation selects the
 * first day of the new month (or the last day of the previous one), and switching calendar system
 * re-anchors on the selected day so its month is the one shown.
 *
 * **The interface language reaches the dates, without touching the data.** [AppLanguage] is combined
 * in and passed to [CalendarUiStateFactory] as the locale the grid and the date list are rendered in,
 * which is what makes a Bengali interface show Bengali month and weekday names for the same
 * Gregorian days. It is an input to the *request* and to nothing else: it does not move the displayed
 * month, change the selection, re-read the calendar or alter the stored calendar system, so switching
 * language re-renders the same dates rather than navigating anywhere. An explicitly chosen
 * [io.github.towkirahmedrony.salah.core.model.DateLanguagePreference] still wins over it, because that
 * setting is a deliberate statement about the dates themselves.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModel(
    private val calendarRepository: CalendarRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val uiStateFactory: CalendarUiStateFactory,
    private val clock: Clock,
    /**
     * The zone a location-local civil day is decided in, or `null` to follow the device's own zone.
     *
     * `null` is the default so a construction with no location keeps the behaviour the existing tests
     * pinned; the application passes the real one, the same instance the calendar repository resolves
     * its days with, so the anchor month and the "today" cell cannot disagree.
     */
    private val dayZone: LocationDayZone? = null,
    /**
     * The hot snapshot of the stored location and prayer settings.
     *
     * Injected (rather than read from the repositories) for the same reason Home reads it: the prayer
     * times beside the selected date need the saved location and the clock-format preference, and the
     * snapshot already publishes both as a `StateFlow`. `null` leaves the prayer section unavailable,
     * which is what the factory's own tests want.
     */
    private val startup: StartupSnapshot? = null,
    /**
     * The same offline-first schedule repository the Home screen and the reminders read.
     *
     * `null` (the unit-test default) means no prayer source, so the selected-date card shows no
     * timetable instead of inventing one.
     */
    private val prayerScheduleRepository: PrayerScheduleRepository? = null,
) : ViewModel() {

    /** A Gregorian day inside the displayed month; the month itself comes from the repository. */
    private val displayedAnchor = MutableStateFlow(today())
    private val selectedDate = MutableStateFlow(today())

    /**
     * The configured location, or `null` when none is stored or no snapshot was injected.
     *
     * Mapped through the same `Loaded` gate Home uses, so Home and the Calendar — and the repository
     * below them — describe one stored location rather than one that is still being read.
     */
    private val locationFlow: Flow<LocationInfo?> = startup
        ?.storedLocation
        ?.transform { state -> if (state is StoredLocationState.Loaded) emit(state.location) }
        ?: flowOf(null)

    /** The prayer settings, or the defaults when no snapshot was injected. */
    private val prayerSettingsFlow: Flow<PrayerSettings> = startup
        ?.prayerSettings
        ?.transform { state -> if (state is PrayerSettingsState.Loaded) emit(state.settings) }
        ?: flowOf(PrayerSettings.DEFAULT)

    /**
     * The selected day's schedule at the saved location, with the settings it was asked with.
     *
     * Keyed on the selection, so picking another date asks the repository for that day and the times
     * beside the date card update with it. The request is the repository's cache key, so moving the
     * selection back and forth does not recompute a timetable that is already held.
     */
    private val prayerContext: Flow<PrayerContext> =
        combine(locationFlow, prayerSettingsFlow, selectedDate) { location, settings, date ->
            PrayerInput(location, settings, date)
        }.flatMapLatest { input ->
            val repository = prayerScheduleRepository
            val location = input.location
            if (repository == null || location == null) {
                flowOf(PrayerContext(location, input.settings, PrayerScheduleState.Loading))
            } else {
                repository
                    .ensureSchedule(
                        PrayerScheduleRequest(
                            date = input.date,
                            location = location,
                            profile = input.settings.toProfile(),
                        ),
                    )
                    .map { state -> PrayerContext(location, input.settings, state) }
            }
        }

    val uiState: StateFlow<CalendarUiState> = combine(
        userPreferencesRepository.calendarSettings,
        userPreferencesRepository.appLanguage,
        displayedAnchor,
        selectedDate,
        prayerContext,
    ) { settings, language, anchor, selected, prayers ->
        MonthRequest(settings, language, anchor, selected, prayers)
    }
        .flatMapLatest { request ->
            // Reading the month of the *selected* system: a Bangla or Hijri selection re-emits a grid
            // of that system's days, so the screen updates as soon as the chip is tapped. A change to
            // any stored calendar setting also re-emits this request, and the repository's own flow
            // re-emits with the new locale, so switching the date language re-renders immediately too.
            calendarRepository.observeMonth(request.settings.preferredSystem, request.anchor)
                .map { month ->
                    uiStateFactory.create(
                        month = month,
                        settings = request.settings,
                        selected = request.selected,
                        // The locale the interface is currently written in, which is the locale the
                        // grid and the date list fall back to whenever the stored date language says
                        // "follow the interface". SYSTEM keeps the device's own locale, which is both
                        // the default and the behaviour the app had before a selector existed.
                        systemLocale = request.language.locale() ?: Locale.getDefault(),
                        prayerSchedule = request.prayers.scheduleState,
                        prayerSettings = request.prayers.settings,
                        location = request.prayers.location,
                    )
                }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MILLIS),
            initialValue = CalendarUiState.Loading,
        )

    fun onPreviousMonth() = shiftMonth(forward = false)

    fun onNextMonth() = shiftMonth(forward = true)

    /** Selects [date], which is always a day of the displayed month. */
    fun onSelectDate(date: LocalDate) {
        selectedDate.value = date
    }

    /** Returns to the current month with today selected. */
    fun onGoToToday() {
        val now = today()
        displayedAnchor.value = now
        selectedDate.value = now
    }

    /**
     * Switches which calendar the screen is reading.
     *
     * Persisted, so the choice survives and the next visit opens on it. The displayed month is
     * re-anchored on the selected day, so the new system's month is the one that contains what the
     * user was looking at — switching modes never jumps to a month the selection is not in.
     */
    fun onSelectSystem(system: CalendarSystem) {
        displayedAnchor.value = selectedDate.value
        viewModelScope.launch {
            userPreferencesRepository.updateCalendarSettings { current ->
                current.copy(preferredSystem = system)
            }
        }
    }

    /**
     * Moves one month of the *selected* calendar system in the requested direction.
     *
     * The boundaries come from the displayed month itself, so this is exact for a 29-day Hijri month
     * and for a Bangla month whose length depends on the leap year: the day before the month's first
     * day is the last day of the previous month of the same system.
     */
    private fun shiftMonth(forward: Boolean) {
        val ready = uiState.value as? CalendarUiState.Ready ?: return
        val target = if (forward) ready.monthEnd.plusDays(1) else ready.monthStart.minusDays(1)
        displayedAnchor.value = target
        selectedDate.value = target
    }

    private fun today(): LocalDate =
        clock.instant().atZone(dayZone?.current() ?: ZoneId.systemDefault()).toLocalDate()

    /** The five inputs a month is built from; kept as a value so the combine has one type. */
    private data class MonthRequest(
        val settings: CalendarSettings,
        /** The interface language, used only as the date-rendering locale. */
        val language: AppLanguage,
        val anchor: LocalDate,
        val selected: LocalDate,
        val prayers: PrayerContext,
    )

    /** The selected day and the settings a prayer schedule is asked with. */
    private data class PrayerInput(
        val location: LocationInfo?,
        val settings: PrayerSettings,
        val date: LocalDate,
    )

    /** The saved location, the prayer settings, and the schedule resolved for the selected day. */
    private data class PrayerContext(
        val location: LocationInfo?,
        val settings: PrayerSettings,
        val scheduleState: PrayerScheduleState,
    )

    private companion object {
        const val SUBSCRIBE_TIMEOUT_MILLIS = 5_000L
    }
}
