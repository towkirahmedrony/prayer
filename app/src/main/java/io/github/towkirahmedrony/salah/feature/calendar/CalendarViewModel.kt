package io.github.towkirahmedrony.salah.feature.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.towkirahmedrony.salah.core.domain.location.LocationDayZone
import io.github.towkirahmedrony.salah.core.domain.repository.CalendarRepository
import io.github.towkirahmedrony.salah.core.domain.repository.UserPreferencesRepository
import io.github.towkirahmedrony.salah.core.model.AppLanguage
import io.github.towkirahmedrony.salah.core.model.CalendarSettings
import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
) : ViewModel() {

    /** A Gregorian day inside the displayed month; the month itself comes from the repository. */
    private val displayedAnchor = MutableStateFlow(today())
    private val selectedDate = MutableStateFlow(today())

    val uiState: StateFlow<CalendarUiState> = combine(
        userPreferencesRepository.calendarSettings,
        userPreferencesRepository.appLanguage,
        displayedAnchor,
        selectedDate,
    ) { settings, language, anchor, selected -> MonthRequest(settings, language, anchor, selected) }
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

    /** The four inputs a month is built from; kept as a value so the combine has one type. */
    private data class MonthRequest(
        val settings: CalendarSettings,
        /** The interface language, used only as the date-rendering locale. */
        val language: AppLanguage,
        val anchor: LocalDate,
        val selected: LocalDate,
    )

    private companion object {
        const val SUBSCRIBE_TIMEOUT_MILLIS = 5_000L
    }
}
