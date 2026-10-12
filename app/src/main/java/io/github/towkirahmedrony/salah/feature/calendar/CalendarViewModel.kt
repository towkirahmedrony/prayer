package io.github.towkirahmedrony.salah.feature.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.towkirahmedrony.salah.core.domain.repository.CalendarRepository
import io.github.towkirahmedrony.salah.core.domain.repository.UserPreferencesRepository
import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Drives the Calendar screen.
 *
 * Holds exactly two pieces of state of its own — which month is displayed and which day is selected —
 * and derives everything else from the calendar repository and the stored calendar settings. It
 * performs no conversion and no formatting: it asks the repository for a month, which already carries
 * the Bangla date, the Hijri date, the Ramadan number and the occasions of every day in it, and hands
 * the whole thing to [CalendarUiStateFactory].
 *
 * **Which month.** Read in the device's current zone rather than the injected [Clock]'s zone. The
 * clock is deliberately bound to UTC so that every consumer converts explicitly, and a *calendar*
 * month is a property of where the user is, not of UTC — near a month boundary the two disagree.
 *
 * **The selection always stays inside the displayed month.** Navigating months with no selection in
 * view would leave the details card describing a day the user cannot see, so the selection moves with
 * the month, keeping the day number where the target month is long enough for it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModel(
    private val calendarRepository: CalendarRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val uiStateFactory: CalendarUiStateFactory,
    private val clock: Clock,
) : ViewModel() {

    private val displayedMonth = MutableStateFlow(YearMonth.from(today()))
    private val selectedDate = MutableStateFlow(today())

    val uiState: StateFlow<CalendarUiState> = combine(
        displayedMonth.flatMapLatest { month -> calendarRepository.observeMonth(month) },
        userPreferencesRepository.calendarSettings,
        selectedDate,
    ) { month, settings, selected ->
        uiStateFactory.create(month = month, settings = settings, selected = selected)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MILLIS),
        initialValue = CalendarUiState.Loading,
    )

    fun onPreviousMonth() = shiftMonth(-1)

    fun onNextMonth() = shiftMonth(1)

    /** Selects [date], which is always a day of the displayed month. */
    fun onSelectDate(date: LocalDate) {
        selectedDate.value = date
    }

    /** Returns to the current month with today selected. */
    fun onGoToToday() {
        val now = today()
        displayedMonth.value = YearMonth.from(now)
        selectedDate.value = now
    }

    /**
     * Switches which calendar the screen is reading.
     *
     * Persisted, so the choice survives and the next visit opens on it — the setting is a real
     * preference rather than screen-local state.
     */
    fun onSelectSystem(system: CalendarSystem) {
        viewModelScope.launch {
            userPreferencesRepository.updateCalendarSettings { current ->
                current.copy(preferredSystem = system)
            }
        }
    }

    private fun shiftMonth(deltaMonths: Int) {
        val next = displayedMonth.value.plusMonths(deltaMonths.toLong())
        displayedMonth.value = next
        selectedDate.update { current -> keepInMonth(current, next) }
    }

    /** [date] unchanged when it is already in [month], else its day number clamped into [month]. */
    private fun keepInMonth(date: LocalDate, month: YearMonth): LocalDate =
        if (YearMonth.from(date) == month) {
            date
        } else {
            month.atDay(date.dayOfMonth.coerceAtMost(month.lengthOfMonth()))
        }

    private fun today(): LocalDate = clock.instant().atZone(ZoneId.systemDefault()).toLocalDate()

    private companion object {
        const val SUBSCRIBE_TIMEOUT_MILLIS = 5_000L
    }
}
