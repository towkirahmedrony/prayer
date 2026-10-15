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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.ChevronLeftGlyph
import io.github.towkirahmedrony.salah.core.designsystem.ChevronRightGlyph
import io.github.towkirahmedrony.salah.core.designsystem.CrescentGlyph
import io.github.towkirahmedrony.salah.core.designsystem.GearGlyph
import io.github.towkirahmedrony.salah.core.designsystem.RamadanGlyph
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahStatusPill
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahUnavailableNote
import io.github.towkirahmedrony.salah.core.designsystem.theme.LocalSalahAccents
import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import io.github.towkirahmedrony.salah.core.model.RamadanPhase
import io.github.towkirahmedrony.salah.feature.common.displayNameRes
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
 * Laid out to the reference: the Gregorian (or selected system's) month and year as the page title,
 * with `Today` and the two month chevrons beside it; a subtitle naming the Hijri and Bangla months the
 * displayed span covers; a three-way system switcher; the month as a card of rounded day cells with a
 * crescent on each Hijri month start and a legend beneath; and the selected day as a card of three date
 * lines with the day's prayer times and the Ramadan row below it.
 *
 * Every value is produced by `CalendarUiStateFactory` from the repository, so this file lays out and
 * decides no dates: a converter that could not cover a day is rendered as the reason, and the Hijri
 * date always carries the source that says it is an estimate.
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
        when (val state = uiState) {
            CalendarUiState.Loading -> {
                CalendarTitleFallback()
                LoadingBlock()
            }

            is CalendarUiState.Failed -> {
                CalendarTitleFallback()
                FailureCard(
                    detail = state.detail,
                    onGoToToday = viewModel::onGoToToday,
                )
            }

            is CalendarUiState.Ready -> {
                CalendarHeader(
                    monthLabel = state.monthLabel,
                    onGoToToday = viewModel::onGoToToday,
                    onPreviousMonth = viewModel::onPreviousMonth,
                    onNextMonth = viewModel::onNextMonth,
                )
                MonthSpanLine(state = state, onOpenSettings = onOpenSettings)
                SystemSwitcher(system = state.system, onSelectSystem = viewModel::onSelectSystem)
                MonthGridCard(
                    state = state,
                    onSelectDate = viewModel::onSelectDate,
                    onOpenSettings = onOpenSettings,
                )
                SelectedDayCard(state = state)
                RamadanRowCard(state = state, onOpenRamadan = onOpenRamadan)
            }
        }
    }
}

/** The page title while no month has arrived yet: just the word, so the header does not jump. */
@Composable
private fun CalendarTitleFallback() {
    Text(
        text = stringResource(R.string.nav_calendar),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
    )
}

/**
 * The month and year, the `Today` action and the two month controls.
 *
 * The title is the largest type on the page and the only flexible element in the row, so a long month
 * name wraps inside its own space instead of squeezing a control out of the row; the controls are
 * fixed-size [androidx.compose.material3.IconButton]s and therefore keep the platform's 48dp touch
 * target.
 */
@Composable
private fun CalendarHeader(
    monthLabel: String,
    onGoToToday: () -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
) {
    val previousDescription = stringResource(R.string.calendar_previous_month)
    val nextDescription = stringResource(R.string.calendar_next_month)
    val tint = MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = monthLabel,
            style = MaterialTheme.typography.displaySmall.copy(
                fontFamily = FontFamily.Serif,
                fontSize = 32.sp,
            ),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.calendar_today_label),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = LocalSalahAccents.current.goldText,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(role = Role.Button, onClick = onGoToToday)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
        MonthControl(
            description = previousDescription,
            onClick = onPreviousMonth,
            glyph = { modifier -> ChevronLeftGlyph(tint = tint, modifier = modifier) },
        )
        MonthControl(
            description = nextDescription,
            onClick = onNextMonth,
            glyph = { modifier -> ChevronRightGlyph(tint = tint, modifier = modifier) },
        )
    }
}

@Composable
private fun MonthControl(
    description: String,
    onClick: () -> Unit,
    glyph: @Composable (Modifier) -> Unit,
) {
    // 48dp square, the platform's minimum touch target, so the two month controls stay reachable
    // whatever the title's line count does to the row's height.
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        glyph(Modifier.semantics { contentDescription = description })
    }
}

/**
 * The subtitle naming the Hijri and Bangla months the displayed span covers, with the settings action.
 *
 * Only the halves that could be derived are shown, joined by a separator, so a table that does not
 * cover the month leaves no dangling punctuation.
 */
@Composable
private fun MonthSpanLine(state: CalendarUiState.Ready, onOpenSettings: () -> Unit) {
    val parts = listOfNotNull(state.hijriSpanLabel, state.banglaSpanLabel)
    val settingsDescription = stringResource(R.string.calendar_open_settings)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (parts.isNotEmpty()) {
            Text(
                text = parts.joinToString(stringResource(R.string.calendar_month_span_separator)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(onClick = onOpenSettings),
            contentAlignment = Alignment.Center,
        ) {
            GearGlyph(
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(20.dp)
                    .semantics { contentDescription = settingsDescription },
            )
        }
    }
}

/**
 * The three calendar systems as one segmented control.
 *
 * The selected segment is a filled pill and the others sit on the track, so the choice reads as one
 * control rather than three independent chips. Each segment is a real click target across its third of
 * the row, and the selected one is announced as selected.
 */
@Composable
private fun SystemSwitcher(system: CalendarSystem, onSelectSystem: (CalendarSystem) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(100.dp))
            .background(scheme.surfaceVariant)
            .padding(4.dp),
    ) {
        CalendarSystem.entries.forEach { candidate ->
            val isSelected = candidate == system
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(100.dp))
                    .background(if (isSelected) scheme.primary else Color.Transparent)
                    .clickable(role = Role.Tab, onClick = { onSelectSystem(candidate) })
                    .semantics { selected = isSelected }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(candidate.displayNameRes()),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSelected) scheme.onPrimary else scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The month as a card: weekday headers, the grid, its legend and — in the Hijri view — the adjustment
 * note.
 *
 * Only the grid mirrors for the Hijri view, which is a property of that view rather than of the labels:
 * a Hijri month reads right to left, so its weekday columns and day cells mirror while the page's
 * headings and controls do not.
 */
@Composable
private fun MonthGridCard(
    state: CalendarUiState.Ready,
    onSelectDate: (LocalDate) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (state.system == CalendarSystem.HIJRI) {
                    LayoutDirection.Rtl
                } else {
                    LayoutDirection.Ltr
                },
            ) {
                WeekdayHeader(labels = state.weekdayLabels)
                Spacer(modifier = Modifier.height(6.dp))
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
            }
            GridLegend(weeks = state.weeks)
            if (state.system == CalendarSystem.HIJRI && state.hijriAdjustmentDays != 0) {
                HijriAdjustmentNote(
                    days = state.hijriAdjustmentDays,
                    onOpenSettings = onOpenSettings,
                )
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
                style = MaterialTheme.typography.labelMedium,
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
 * Today and the selection are drawn as different treatments rather than one, because they are different
 * facts: after navigating away from the current month the selected day is not today. Today is the solid
 * fill, the selection is an outline, and the two together carry both. A Hijri month start is marked with
 * a crescent in the corner and a Ramadan day or an observance with a small dot, named by the legend.
 */
@Composable
private fun MonthCell(
    cell: CalendarCell,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val isSelected = cell.stage == CalendarCellStage.SELECTED ||
        cell.stage == CalendarCellStage.TODAY_SELECTED
    val filled = cell.stage == CalendarCellStage.TODAY ||
        cell.stage == CalendarCellStage.TODAY_SELECTED

    val container = if (filled) scheme.primary else Color.Transparent
    val content = when (cell.stage) {
        CalendarCellStage.TODAY, CalendarCellStage.TODAY_SELECTED -> scheme.onPrimary
        CalendarCellStage.SELECTED -> scheme.primary
        CalendarCellStage.REGULAR -> scheme.onSurface
        CalendarCellStage.PADDING -> scheme.onSurfaceVariant
    }

    // The spoken description is the cell's full date in the interface language, not the ISO text a
    // screen reader would spell out digit by digit.
    val locale = LocalResources.current.configuration.locales[0]
    val date = cell.date
    val spokenDate = remember(date, locale) { date?.let { fullDate(it, locale) } }
    val description = if (spokenDate == null) {
        ""
    } else {
        stringResource(R.string.calendar_cell_description, spokenDate)
    }

    Box(
        modifier = modifier
            .height(CELL_HEIGHT)
            .padding(3.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(container)
            .then(
                if (isSelected && !filled) {
                    Modifier.border(1.5.dp, scheme.primary, RoundedCornerShape(14.dp))
                } else {
                    Modifier
                },
            )
            .clickable(enabled = date != null, onClick = onClick)
            .semantics {
                contentDescription = description
                selected = isSelected
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (cell.dayLabel != null) {
                Text(
                    text = cell.dayLabel,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (filled || isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = content,
                )
            }
            if (cell.isRamadanDay || cell.occasion != null) {
                Spacer(modifier = Modifier.height(3.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (cell.isRamadanDay) {
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .clip(CircleShape)
                                .background(scheme.secondary),
                        )
                    }
                    if (cell.occasion != null) {
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .clip(CircleShape)
                                .background(scheme.tertiary),
                        )
                    }
                }
            }
        }
        if (cell.isHijriMonthStart) {
            CrescentGlyph(
                tint = content,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 4.dp)
                    .size(10.dp),
            )
        }
    }
}

/**
 * Names the marks a cell can carry, one per column, and only for a mark that actually appears in the
 * displayed month — so a month with no observance does not explain a dot nobody can see.
 */
@Composable
private fun GridLegend(weeks: List<List<CalendarCell>>) {
    val hasHijriStart = weeks.any { week -> week.any { it.isHijriMonthStart } }
    val hasRamadan = weeks.any { week -> week.any { it.isRamadanDay } }
    val hasOccasion = weeks.any { week -> week.any { it.occasion != null } }
    if (!hasHijriStart && !hasRamadan && !hasOccasion) return

    Spacer(modifier = Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasHijriStart) {
            LegendItem(
                label = stringResource(R.string.calendar_legend_hijri_start),
                modifier = Modifier.weight(1f),
            ) {
                CrescentGlyph(
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        if (hasRamadan) {
            LegendItem(
                label = stringResource(R.string.calendar_legend_ramadan),
                modifier = Modifier.weight(1f),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondary),
                )
            }
        }
        if (hasOccasion) {
            LegendItem(
                label = stringResource(R.string.calendar_legend_occasion),
                modifier = Modifier.weight(1f),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.tertiary),
                )
            }
        }
    }
}

@Composable
private fun LegendItem(
    label: String,
    modifier: Modifier = Modifier,
    mark: @Composable () -> Unit,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        mark()
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
    }
}

/**
 * The Hijri adjustment, under the Hijri grid, only when it is not zero.
 *
 * It tells the reader why the dates may differ from another source, and opens the setting that changes
 * it. With no adjustment there is nothing to say, so nothing is drawn.
 */
@Composable
private fun HijriAdjustmentNote(days: Int, onOpenSettings: () -> Unit) {
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

/**
 * The selected day's details: its weekday and (for a Friday) the Jumu'ah label, the three date lines,
 * and the day's prayer times.
 *
 * The three lines are the same value rendered in the three calendars, so they always describe the one
 * selected date; the Hijri line carries the pill that says it is calculated. A date a converter could
 * not cover is stated as the reason instead of a blank, and the out-of-range notes travel with it.
 */
@Composable
private fun SelectedDayCard(state: CalendarUiState.Ready) {
    val day = state.selected
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.calendar_selected_date_heading, day.weekday),
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (day.isJumuah) {
                    SalahStatusPill(
                        text = stringResource(R.string.calendar_jumuah),
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(14.dp))

            day.lines.forEach { line ->
                DateLine(label = stringResource(line.system.sectionTitleRes()), line = line)
                Spacer(modifier = Modifier.height(12.dp))
            }

            state.selectedOutOfRange?.let { reason ->
                SalahUnavailableNote(text = stringResource(reason.messageRes()))
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (day.occasions.isNotEmpty()) {
                day.occasions.forEach { occasion ->
                    Text(
                        text = stringResource(occasion.labelRes()),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.calendar_occasion_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            PrayerTimesBox(section = state.prayerTimes)
        }
    }
}

/** One calendar of the selected day: its name, its value, and the estimate pill for a Hijri value. */
@Composable
private fun DateLine(label: String, line: CalendarDateLine) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            modifier = Modifier.width(LABEL_WIDTH),
        )
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = line.value ?: stringResource(line.unavailable?.messageRes() ?: R.string.reason_unknown),
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (line.source != null) {
                Spacer(modifier = Modifier.width(8.dp))
                SalahStatusPill(
                    // Every source the model can carry is an estimate, so the badge states that rather
                    // than naming the table behind it; the full wording lives in the settings screen.
                    text = stringResource(R.string.calendar_estimated_badge),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The selected day's five prayers, at the saved location.
 *
 * Rendered from the schedule the repository resolved for that date, so the times match the Home
 * timetable and the reminders. When no schedule exists the card states why rather than showing dashes.
 */
@Composable
private fun PrayerTimesBox(section: PrayerTimesSection) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(scheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = section.locationLabel?.let { location ->
                stringResource(R.string.calendar_prayer_times_with_location, location)
            } ?: stringResource(R.string.calendar_prayer_times_title),
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
            fontWeight = FontWeight.SemiBold,
            color = scheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (section.unavailable != null) {
            Text(
                text = stringResource(section.unavailable.messageRes()),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            return@Column
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            section.rows.forEach { row ->
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(row.prayer.labelRes()),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = row.timeLabel ?: stringResource(R.string.home_time_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = scheme.onSurface,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * The Ramadan summary as one row: the crescent, the estimated window and where today stands, opening
 * the Ramadan screen.
 *
 * The range is stated with its source wording; when no range could be derived the row says so rather
 * than showing a guessed date.
 */
@Composable
private fun RamadanRowCard(state: CalendarUiState.Ready, onOpenRamadan: () -> Unit) {
    val ramadan = state.ramadan
    val phase = when (ramadan.phase) {
        RamadanPhase.IN_RAMADAN -> stringResource(R.string.calendar_ramadan_in_ramadan)
        RamadanPhase.UPCOMING -> stringResource(R.string.calendar_ramadan_upcoming)
        RamadanPhase.FAR_AWAY -> stringResource(R.string.calendar_ramadan_not_near)
    }

    Card(
        onClick = onOpenRamadan,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                RamadanGlyph(
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    // Both halves are needed for the titled form: a range without its Hijri year, or a
                    // year without a range, would have to be padded with a placeholder, so the plain
                    // title is shown instead.
                    text = ramadan.hijriYear?.let { year ->
                        ramadan.rangeLabel?.let { range ->
                            stringResource(R.string.calendar_ramadan_row_title, year, range)
                        }
                    } ?: stringResource(R.string.calendar_ramadan_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    // The phase wording is a whole sentence of its own and already says that it is this
                    // device's calculation, so it is shown as it stands rather than wrapped in another
                    // estimate label.
                    text = if (ramadan.rangeLabel == null) {
                        stringResource(R.string.calendar_ramadan_unavailable)
                    } else {
                        phase
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            ChevronRightGlyph(
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
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
private fun FailureCard(detail: String, onGoToToday: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.state_error_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(onClick = onGoToToday) {
                Text(text = stringResource(R.string.calendar_go_to_today))
            }
        }
    }
}

/** The full date in [locale], for a screen reader: weekday, day, month and year in words. */
private fun fullDate(date: LocalDate, locale: Locale): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).format(date)

/** Renders a signed day count, so an adjustment never reads as an unsigned offset. */
private fun signedDays(days: Int): String = if (days > 0) "+$days" else days.toString()

/** Minimum touch target height for a month cell. */
private val CELL_HEIGHT = 52.dp

/** Width of the date-line label column, so the three values start on one vertical line. */
private val LABEL_WIDTH = 84.dp
