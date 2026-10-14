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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.ChevronLeftGlyph
import io.github.towkirahmedrony.salah.core.designsystem.ChevronRightGlyph
import io.github.towkirahmedrony.salah.core.designsystem.GearGlyph
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
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

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
 *
 * **Layout rule for the month navigation.** The previous/next controls are fixed-size icon buttons
 * and only the month title is flexible. They used to be text buttons ("Previous month" / "Next
 * month"); with a long month name, in the right-to-left Hijri card, the title and the first button
 * used up the whole row and the second button was measured at zero width, so its label wrapped one
 * character per line and stretched the row to several hundred dp — the large empty bands above and
 * below the month name. A fixed-size control cannot be squeezed out that way.
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
        CalendarHeader(onOpenSettings = onOpenSettings)

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

/**
 * The screen's title and the one entry point to the calendar settings.
 *
 * The settings used to be a card of their own at the bottom of the page, which repeated what this
 * button does. The button is a real [IconButton], so its touch target is the platform's 48dp.
 */
@Composable
private fun CalendarHeader(onOpenSettings: () -> Unit) {
    val settingsDescription = stringResource(R.string.calendar_open_settings)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.nav_calendar),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onOpenSettings) {
            GearGlyph(
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { contentDescription = settingsDescription },
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
            Spacer(modifier = Modifier.height(6.dp))
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
            MonthLegend(weeks = state.weeks)
            if (state.system == CalendarSystem.HIJRI && state.hijriAdjustmentDays != 0) {
                HijriAdjustmentNote(
                    days = state.hijriAdjustmentDays,
                    onOpenSettings = onOpenSettings,
                )
            }
            if (!state.selectedIsToday) {
                Spacer(modifier = Modifier.height(10.dp))
                GoToTodayButton(onClick = onGoToToday)
            }
        }
    }

    SelectedDayCard(state = state)

    RamadanCard(state = state, onOpenRamadan = onOpenRamadan)
}

@Composable
private fun TodayCard(state: CalendarUiState.Ready) {
    SalahSectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.calendar_today_label),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = state.today.weekday,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
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

/**
 * Previous month, the month's name, next month.
 *
 * The two controls are fixed-size [IconButton]s (the platform's 48dp touch target) and only the title
 * is flexible, so a long month name wraps inside its own space instead of pushing a control out of
 * the row. Read inside the month card's layout-direction scope, it mirrors in the Hijri card: the
 * first control is then on the right, and its chevron points right — towards the earlier month,
 * because time runs right to left there.
 */
@Composable
private fun MonthNavigation(
    monthLabel: String,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val previousDescription = stringResource(R.string.calendar_previous_month)
    val nextDescription = stringResource(R.string.calendar_next_month)
    val tint = MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPreviousMonth) {
            val glyphModifier = Modifier.semantics { contentDescription = previousDescription }
            if (isRtl) {
                ChevronRightGlyph(tint = tint, modifier = glyphModifier)
            } else {
                ChevronLeftGlyph(tint = tint, modifier = glyphModifier)
            }
        }
        Text(
            text = monthLabel,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
        )
        IconButton(onClick = onNextMonth) {
            val glyphModifier = Modifier.semantics { contentDescription = nextDescription }
            if (isRtl) {
                ChevronLeftGlyph(tint = tint, modifier = glyphModifier)
            } else {
                ChevronRightGlyph(tint = tint, modifier = glyphModifier)
            }
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
                maxLines = 1,
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
 * which is the only annotation the grid needs — the legend under the grid names the markers and the
 * details card spells out the day.
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
    val isSelected = cell.stage == CalendarCellStage.SELECTED ||
        cell.stage == CalendarCellStage.TODAY_SELECTED

    // The spoken description is the cell's full date in the interface language ("Wednesday, 14
    // October 2026"), not the ISO text "2026-10-14" a screen reader would spell out digit by digit.
    // The locale is read from LocalResources, which carries the app's chosen language.
    val locale = LocalResources.current.configuration.locales[0]
    val date = cell.date
    val spokenDate = remember(date, locale) { date?.let { fullDate(it, locale) } }
    val description = if (spokenDate == null) {
        // Padding cells carry no information, so they say nothing.
        ""
    } else {
        stringResource(R.string.calendar_cell_description, spokenDate)
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
            .semantics {
                contentDescription = description
                selected = isSelected
            }
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

/**
 * Names the two markers a cell can carry. Drawn only for a marker that actually appears in the
 * displayed month, so a month with no observance does not explain a dot nobody can see.
 */
@Composable
private fun MonthLegend(weeks: List<List<CalendarCell>>) {
    val hasRamadan = weeks.any { week -> week.any { it.isRamadanDay } }
    val hasOccasion = weeks.any { week -> week.any { it.occasion != null } }
    if (!hasRamadan && !hasOccasion) return

    Spacer(modifier = Modifier.height(10.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasRamadan) {
            LegendItem(
                color = MaterialTheme.colorScheme.secondary,
                label = stringResource(R.string.calendar_legend_ramadan),
            )
        }
        if (hasOccasion) {
            LegendItem(
                color = MaterialTheme.colorScheme.tertiary,
                label = stringResource(R.string.calendar_legend_occasion),
            )
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The Hijri adjustment, on one line under the Hijri grid, only when it is not zero.
 *
 * It tells the reader why the dates may differ from another source, and opens the setting that
 * changes it. With no adjustment there is nothing to say, so nothing is drawn.
 */
@Composable
private fun HijriAdjustmentNote(days: Int, onOpenSettings: () -> Unit) {
    // Wrapped in a full-width Row, like the legend above it, so it starts on the same side in the
    // right-to-left Hijri card; a bare Text would take the Column's own (left-to-right) alignment.
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.calendar_hijri_adjustment_value, signedDays(days)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable(onClick = onOpenSettings)
                .padding(vertical = 6.dp),
        )
    }
}

/** A centred pill instead of a text button, so it sits in the middle of the card in either direction. */
@Composable
private fun GoToTodayButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(100.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.calendar_go_to_today),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/**
 * The selected day's details.
 *
 * When the selected day is today, the Today card above already shows all three dates, so this card
 * would only repeat them. In that case it carries only what the Today card does not — a Ramadan day,
 * an observance, an out-of-range note — and is left out entirely when there is none of those.
 */
@Composable
private fun SelectedDayCard(state: CalendarUiState.Ready) {
    val day = state.selected
    val hasDetails = day.ramadanDayNumber != null ||
        day.occasions.isNotEmpty() ||
        state.selectedOutOfRange != null
    if (state.selectedIsToday && !hasDetails) return

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

        if (!state.selectedIsToday) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = day.weekday,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(10.dp))
            DateLineList(day = day)
        }

        day.ramadanDayNumber?.let { dayNumber ->
            Spacer(modifier = Modifier.height(10.dp))
            SalahStatusPill(
                text = stringResource(R.string.calendar_ramadan_day, dayNumber),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }

        if (day.occasions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            day.occasions.forEach { occasion ->
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

/**
 * A day's three date lines.
 *
 * The estimate wording of a Hijri line sits under the line at the card's full width. It used to sit
 * in a narrow right-aligned column beside the label, where its three-line sentence was squeezed into
 * half the card and read as a ragged block.
 */
@Composable
private fun DateLineList(day: SelectedDay) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        day.lines.forEach { line ->
            Column(modifier = Modifier.fillMaxWidth()) {
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
                    Text(
                        text = line.value ?: stringResource(R.string.home_time_unavailable),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.End,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp),
                    )
                }
                line.source?.let { source ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(source.labelRes()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * The Ramadan summary: the estimated range, where today stands against it, and a way into the
 * Ramadan screen.
 *
 * The long "this is an estimate" paragraph that used to follow is gone from this card: the heading
 * carries the Estimated badge and the range line itself begins "Estimated Ramadan", so the estimate
 * is stated twice on a short card instead of a third time in a paragraph.
 */
@Composable
private fun RamadanCard(state: CalendarUiState.Ready, onOpenRamadan: () -> Unit) {
    val ramadan = state.ramadan
    SalahSectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.calendar_ramadan_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (ramadan.rangeLabel != null) {
                SalahStatusPill(
                    text = stringResource(R.string.calendar_estimated_badge),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
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
                ramadan.todayDayNumber?.let { dayNumber ->
                    SalahStatusPill(
                        text = stringResource(R.string.calendar_ramadan_day, dayNumber),
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Text(
                    text = when (ramadan.phase) {
                        RamadanPhase.IN_RAMADAN -> stringResource(R.string.calendar_ramadan_in_ramadan)
                        RamadanPhase.UPCOMING -> stringResource(R.string.calendar_ramadan_upcoming)
                        RamadanPhase.FAR_AWAY -> stringResource(R.string.calendar_ramadan_not_near)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        OutlinedButton(
            onClick = onOpenRamadan,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.calendar_open_ramadan))
        }
    }
}

/** The full date in [locale], for a screen reader: weekday, day, month and year in words. */
private fun fullDate(date: LocalDate, locale: Locale): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).format(date)

/** Renders a signed day count, so an adjustment never reads as an unsigned offset. */
private fun signedDays(days: Int): String = if (days > 0) "+$days" else days.toString()

/** Minimum touch target height for a month cell. */
private val CELL_HEIGHT = 44.dp
