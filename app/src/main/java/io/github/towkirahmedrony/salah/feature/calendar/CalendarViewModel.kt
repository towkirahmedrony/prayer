package io.github.towkirahmedrony.salah.feature.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.towkirahmedrony.salah.core.domain.repository.CalendarRepository
import io.github.towkirahmedrony.salah.core.domain.repository.UserPreferencesRepository
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
 * **Which month.** Read in the device's current zone rather than the injected [Clock]'s zone. The
 * clock is deliberately bound to UTC so that every consumer converts explicitly, and a *calendar*
 * month is a property of where the user is, not of UTC — near a month boundary the two disagree.
 *
 * **The selection always stays inside the displayed month.** Navigating months with no selection in
 * view would leave the details card describing a day the user cannot see, so navigation selects the
 * first day of the new month (or the last day of the previous one), and switching calendar system
 * re-anchors on the selected day so its month is the one shown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModel(
    private val calendarRepository: CalendarRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val uiStateFactory: CalendarUiStateFactory,
    private val clock: Clock,
) : ViewModel() {

    /** A Gregorian day inside the displayed month; the month itself comes from the repository. */
    private val displayedAnchor = MutableStateFlow(today())
    private val selectedDate = MutableStateFlow(today())

    val uiState: StateFlow<CalendarUiState> = combine(
        userPreferencesRepository.calendarSettings,
        displayedAnchor,
        selectedDate,
    ) { settings, anchor, selected -> MonthRequest(settings, anchor, selected) }
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

    private fun today(): LocalDate = clock.instant().atZone(ZoneId.systemDefault()).toLocalDate()

    /** The three inputs a month is built from; kept as a value so the combine has one type. */
    private data class MonthRequest(
        val settings: CalendarSettings,
        val anchor: LocalDate,
        val selected: LocalDate,
    )

    private companion object {
        const val SUBSCRIBE_TIMEOUT_MILLIS = 5_000L
    }
}
