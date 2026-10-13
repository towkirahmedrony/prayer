package io.github.towkirahmedrony.salah.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSectionCard
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahStatusPill
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahUnavailableNote
import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import io.github.towkirahmedrony.salah.core.model.RamadanPhase
import io.github.towkirahmedrony.salah.feature.common.labelRes
import io.github.towkirahmedrony.salah.feature.common.messageRes
import io.github.towkirahmedrony.salah.feature.common.sectionTitleRes
import org.koin.androidx.compose.koinViewModel
import java.time.LocalDate

/**
 * The Calendar screen.
 *
 * One month, shown in whichever of the three calendars is selected. The view chips switch the grid
 * itself: Gregorian reads as a Gregorian month, Bangla as a Bangladesh Bangla month (Bengali month
 * and weekday names, Bengali numerals) and Hijri as a Hijri month whose month name, weekday names and
 * numerals follow the interface language — `Ramadan 1445` in English, `রমজান ১৪৪৫` in Bengali — laid
 * out right-to-left. Every day also carries its other two representations in the selected-day card
 * below, and a day's Gregorian date remains its identity for prayer times and Ramadan.
 *
 * Everything shown is produced by the repository and mapped by [CalendarUiStateFactory], so this file
 * contains layout and no date arithmetic. Two rules it does enforce:
 *
 *  - a value that could not be produced is rendered as the reason it could not, never as a blank that
 *    reads like a date;
 *  - the Hijri date is always accompanied by the wording that says it is an estimate, because it is.
 */
@Composable
fun CalendarScreen(
    onOpenSettings: () -> Unit,
    onOpenRamadan: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CalendarViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = stringResource(R.string.nav_calendar),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )

        when (val state = uiState) {
            CalendarUiState.Loading -> LoadingBlock()

            is CalendarUiState.Failed -> SalahSectionCard {
                Text(
                    text = stringResource(R.string.state_error_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = state.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(onClick = viewModel::onGoToToday) {
                    Text(text = stringResource(R.string.calendar_go_to_today))
                }
            }

            is CalendarUiState.Ready -> ReadyContent(
                state = state,
                onPreviousMonth = viewModel::onPreviousMonth,
                onNextMonth = viewModel::onNextMonth,
                onGoToToday = viewModel::onGoToToday,
                onSelectDate = viewModel::onSelectDate,
                onSelectSystem = viewModel::onSelectSystem,
                onOpenSettings = onOpenSettings,
                onOpenRamadan = onOpenRamadan,
            )
        }
    }
}

@Composable
private fun LoadingBlock() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.size(12.dp))
        Text(
            text = stringResource(R.string.state_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReadyContent(
    state: CalendarUiState.Ready,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onGoToToday: () -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onSelectSystem: (CalendarSystem) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRamadan: () -> Unit,
) {
    TodayCard(state = state)

    ViewSwitcher(system = state.system, onSelectSystem = onSelectSystem)

    SalahSectionCard {
        // The Hijri month card keeps its right-to-left presentation — a property of the mode rather
        // than of the labels, so localising the month names leaves it unchanged. The direction is
        // scoped to the month card alone, so the app's navigation, headings and every other card are
        // untouched; inside it the weekday columns and the previous/next controls mirror.
        CompositionLocalProvider(
            LocalLayoutDirection provides if (state.system == CalendarSystem.HIJRI) {
                LayoutDirection.Rtl
            } else {
                LayoutDirection.Ltr
            },
        ) {
            MonthNavigation(
                monthLabel = state.monthLabel,
                onPreviousMonth = onPreviousMonth,
                onNextMonth = onNextMonth,
            )
            Spacer(modifier = Modifier.height(10.dp))
            WeekdayHeader(labels = state.weekdayLabels)
            Spacer(modifier = Modifier.height(4.dp))
            state.weeks.forEach { week ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    week.forEach { cell ->
                        MonthCell(
                            cell = cell,
                            onClick = { cell.date?.let(onSelectDate) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onGoToToday) {
                Text(text = stringResource(R.string.calendar_go_to_today))
            }
        }
    }

    SelectedDayCard(state = state)

    RamadanCard(state = state, onOpenRamadan = onOpenRamadan)

    SalahSectionCard {
        Text(
            text = stringResource(R.string.calendar_settings_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.calendar_settings_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(
                R.string.calendar_hijri_adjustment_value,
                signedDays(state.hijriAdjustmentDays),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedButton(onClick = onOpenSettings) {
            Text(text = stringResource(R.string.calendar_open_settings))
        }
    }
}

@Composable
private fun TodayCard(state: CalendarUiState.Ready) {
    SalahSectionCard {
        Text(
            text = stringResource(R.string.calendar_today_label),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        DateLineList(day = state.today)
    }
}

@Composable
private fun ViewSwitcher(system: CalendarSystem, onSelectSystem: (CalendarSystem) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CalendarSystem.entries.forEach { candidate ->
            FilterChip(
                selected = candidate == system,
                onClick = { onSelectSystem(candidate) },
                label = { Text(text = stringResource(candidate.sectionTitleRes())) },
            )
        }
    }
}

@Composable
private fun MonthNavigation(
    monthLabel: String,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onPreviousMonth) {
            Text(text = stringResource(R.string.calendar_previous_month))
        }
        Text(
            text = monthLabel,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onNextMonth) {
            Text(text = stringResource(R.string.calendar_next_month))
        }
    }
}

@Composable
private fun WeekdayHeader(labels: List<String>) {
    Row(modifier = Modifier.fillMaxWidth()) {
        labels.forEach { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * One day cell.
 *
 * Selection and "today" are drawn as different treatments rather than one, because they are different
 * facts: after navigating to another month the selected day is not today, and a grid that highlighted
 * both identically would hide that. A Ramadan day and a day with an observance carry a small marker,
 * which is the only annotation the grid needs — the details card spells it out.
 */
@Composable
private fun MonthCell(
    cell: CalendarCell,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Three distinct treatments, because today and the selection are three different facts: today
    // alone is a muted fill, the selection is a tinted fill inside an outline, and the two together are
    // the solid accent. A grid that drew today and the selection the same way would hide which is
    // which as soon as the user navigated away from the current month.
    val container = when (cell.stage) {
        CalendarCellStage.TODAY_SELECTED -> MaterialTheme.colorScheme.primary
        CalendarCellStage.SELECTED -> MaterialTheme.colorScheme.primaryContainer
        CalendarCellStage.TODAY -> MaterialTheme.colorScheme.surfaceVariant
        CalendarCellStage.REGULAR, CalendarCellStage.PADDING -> Color.Transparent
    }
    val content = when (cell.stage) {
        CalendarCellStage.TODAY_SELECTED -> MaterialTheme.colorScheme.onPrimary
        CalendarCellStage.SELECTED -> MaterialTheme.colorScheme.onPrimaryContainer
        CalendarCellStage.TODAY -> MaterialTheme.colorScheme.onSurfaceVariant
        CalendarCellStage.REGULAR -> MaterialTheme.colorScheme.onSurface
        CalendarCellStage.PADDING -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val date = cell.date
    val description = if (date == null) {
        // Padding cells carry no information, so they are hidden from accessibility services.
        ""
    } else {
        stringResource(R.string.calendar_cell_description, date.toString())
    }

    Column(
        modifier = modifier
            .height(CELL_HEIGHT)
            .padding(2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(container)
            .then(
                if (cell.stage == CalendarCellStage.SELECTED) {
                    Modifier.border(
                        width = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(10.dp),
                    )
                } else {
                    Modifier
                },
            )
            .clickable(enabled = date != null, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (cell.dayLabel != null) {
            Text(
                // Already rendered in the calendar mode's numerals by the state factory, so the mode
                // selection changes the digits as well as the month and weekday names.
                text = cell.dayLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = content,
                fontWeight = when (cell.stage) {
                    CalendarCellStage.SELECTED, CalendarCellStage.TODAY_SELECTED,
                    CalendarCellStage.TODAY,
                    -> FontWeight.SemiBold
                    else -> FontWeight.Normal
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                if (cell.isRamadanDay) {
                    Box(
                        modifier = Modifier
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondary),
                    )
                }
                if (cell.occasion != null) {
                    Box(
                        modifier = Modifier
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.tertiary),
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectedDayCard(state: CalendarUiState.Ready) {
    SalahSectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.calendar_selected_day),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (state.selectedIsToday) {
                SalahStatusPill(
                    text = stringResource(R.string.calendar_today_label),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = state.selected.weekday,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        DateLineList(day = state.selected)

        state.selected.ramadanDayNumber?.let { dayNumber ->
            Spacer(modifier = Modifier.height(10.dp))
            SalahStatusPill(
                text = stringResource(R.string.calendar_ramadan_day, dayNumber),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }

        if (state.selected.occasions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            state.selected.occasions.forEach { occasion ->
                Text(
                    text = stringResource(occasion.labelRes()),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.calendar_occasion_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.selectedOutOfRange?.let { reason ->
            Spacer(modifier = Modifier.height(10.dp))
            SalahUnavailableNote(text = stringResource(reason.messageRes()))
        }
    }
}

@Composable
private fun DateLineList(day: SelectedDay) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        day.lines.forEach { line ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = stringResource(line.system.sectionTitleRes()),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.padding(start = 12.dp),
                ) {
                    Text(
                        text = line.value ?: stringResource(R.string.home_time_unavailable),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.End,
                    )
                    line.source?.let { source ->
                        Text(
                            text = stringResource(source.labelRes()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RamadanCard(state: CalendarUiState.Ready, onOpenRamadan: () -> Unit) {
    val ramadan = state.ramadan
    SalahSectionCard {
        Text(
            text = stringResource(R.string.calendar_ramadan_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (ramadan.rangeLabel == null) {
            SalahUnavailableNote(text = stringResource(R.string.calendar_ramadan_unavailable))
        } else {
            Text(
                text = stringResource(R.string.calendar_ramadan_range, ramadan.rangeLabel),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SalahStatusPill(
                    text = stringResource(R.string.calendar_estimated_badge),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ramadan.todayDayNumber?.let { dayNumber ->
                    SalahStatusPill(
                        text = stringResource(R.string.calendar_ramadan_day, dayNumber),
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = when (ramadan.phase) {
                    RamadanPhase.IN_RAMADAN -> stringResource(R.string.calendar_ramadan_in_ramadan)
                    RamadanPhase.UPCOMING -> stringResource(R.string.calendar_ramadan_upcoming)
                    RamadanPhase.FAR_AWAY -> stringResource(R.string.calendar_ramadan_not_near)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.calendar_ramadan_estimate_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        OutlinedButton(onClick = onOpenRamadan) {
            Text(text = stringResource(R.string.calendar_open_ramadan))
        }
    }
}

/** Renders a signed day count, so an adjustment never reads as an unsigned offset. */
private fun signedDays(days: Int): String = if (days > 0) "+$days" else days.toString()

/** Minimum touch target height for a month cell. */
private val CELL_HEIGHT = 44.dp
