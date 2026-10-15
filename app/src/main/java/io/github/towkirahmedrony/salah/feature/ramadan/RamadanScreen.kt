package io.github.towkirahmedrony.salah.feature.ramadan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.CalendarGlyph
import io.github.towkirahmedrony.salah.core.designsystem.GearGlyph
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSectionCard
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahStatusPill
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSwitch
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahUnavailableNote
import io.github.towkirahmedrony.salah.core.designsystem.theme.LocalSalahAccents
import io.github.towkirahmedrony.salah.core.domain.CountdownFormatter
import io.github.towkirahmedrony.salah.core.domain.calendar.FastingCountdown
import io.github.towkirahmedrony.salah.core.domain.calendar.FastingEventKind
import io.github.towkirahmedrony.salah.core.domain.calendar.RamadanStage
import io.github.towkirahmedrony.salah.core.model.PrayerTimeSource
import io.github.towkirahmedrony.salah.core.model.RamadanPhase
import io.github.towkirahmedrony.salah.feature.common.messageRes
import org.koin.androidx.compose.koinViewModel

/**
 * The Ramadan screen: the estimated window, the Sehri and Iftar timetable, and the reminders.
 *
 * The screen is three purposeful blocks and nothing else — a heading that states which Ramadan this is
 * and how long it runs, one card for the reminders, and one card for the day-by-day timetable. The
 * window used to be a card of its own that repeated the heading, and the table used to repeat the source
 * prayer beside every time; both are gone, so each fact now appears exactly once.
 *
 * ## How the month is laid out
 *
 * The timetable is grouped by Ramadan's three traditional stages — the first ten days, the middle ten,
 * and the rest — each under its own small heading. The grouping is a reading aid over the same dates:
 * the days, their times and their Hijri dates are exactly what the window and the schedules produced.
 * The last stage is open-ended, so a 29-day Ramadan shows nine days under it and a 30-day one ten; no
 * stage is told in advance how long Ramadan is.
 *
 * A day is labelled by its own **Gregorian date** (`19 Feb`) rather than by a repeated "Ramadan day N".
 * The Ramadan day number is still there — it is the day of the Hijri date printed under the Gregorian
 * one — and the stage heading states the day range it covers, so the number is legible without a second
 * copy of it on every row. The Bangla calendar date is deliberately absent: this list is about Ramadan
 * and its Gregorian dates, and the Bangla calendar remains a Calendar-screen feature.
 *
 * ## The three honesty rules that shape it
 *
 * Each one is still visible in the layout:
 *
 *  1. **The window is an estimate.** It is derived from the platform's Hijri table, so one sentence
 *     beside the range says it is an estimate and that local moon sighting may move it. Salah never
 *     claims an authority has confirmed it. The sentence appears once — the range line does not also
 *     repeat the word.
 *  2. **The times are the existing prayer times.** Sehri is the calculated Fajr and Iftar the
 *     calculated Maghrib, and the table says so once, in words, under its own title, instead of printing
 *     the same clock time twice under every row. The source of the times is named too, so nothing is
 *     presented as a separate Sehri/Iftar timetable Salah has not integrated.
 *  3. **No schedule means no table.** With no location, or on a day the engine cannot calculate, the
 *     screen says so; it does not fill in a plausible-looking time.
 */
@Composable
fun RamadanScreen(
    onOpenNotifications: () -> Unit,
    onOpenCalendar: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RamadanViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val countdown by viewModel.countdown.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (val state = uiState) {
            RamadanUiState.Loading -> {
                RamadanHeader(state = null, onOpenCalendar = onOpenCalendar)
                LoadingBlock()
            }

            is RamadanUiState.Failed -> {
                RamadanHeader(state = null, onOpenCalendar = onOpenCalendar)
                FailureBlock(state)
            }

            is RamadanUiState.Ready -> {
                RamadanHeader(state = state, onOpenCalendar = onOpenCalendar)
                FastingCountdownCard(countdown = countdown)
                RemindersCard(
                    state = state,
                    onOpenNotifications = onOpenNotifications,
                    viewModel = viewModel,
                )
                ImsakCard(state = state, viewModel = viewModel)
                ScheduleCard(state = state)
            }
        }
    }
}

/**
 * The heading: which Ramadan this is, its range, where today stands in it, and the one entry point to
 * the Calendar screen.
 *
 * The block is deliberately four short lines at most. It states the window once — as a range plus a day
 * count — and never repeats a date the table below already carries: the range used to be printed beside
 * a status line that restated the same period, and the "(Estimated)" marker duplicated the sentence that
 * explains what "estimated" means. Removing that duplication is what brings the timetable back above the
 * fold on a phone.
 *
 * [state] is `null` while the window is still loading or could not be built, and the heading then falls
 * back to the screen's own name — the screen never invents a year or a range to fill the gap.
 */
@Composable
private fun RamadanHeader(
    state: RamadanUiState.Ready?,
    onOpenCalendar: () -> Unit,
) {
    val calendarDescription = stringResource(R.string.ramadan_open_calendar)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state?.titleLabel ?: stringResource(R.string.nav_ramadan),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onOpenCalendar) {
                CalendarGlyph(
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { contentDescription = calendarDescription },
                )
            }
        }

        if (state != null) {
            RamadanStatus(state = state)
        }
    }
}

/**
 * Where today stands against the window: the range it covers, the Ramadan day today is on when it is
 * inside, the phase sentence when it is not, and the one line that says the range is an estimate.
 *
 * The range and the Ramadan day are different facts and each is drawn once. The estimate sentence is not
 * optional, but it is the only place the estimate is stated: the range line used to carry an
 * "(Estimated)" beside it as well, saying the same thing twice in two words' worth of space.
 */
@Composable
private fun RamadanStatus(state: RamadanUiState.Ready) {
    val rangeLabel = state.rangeLabel
    if (rangeLabel == null) {
        SalahUnavailableNote(text = stringResource(R.string.ramadan_window_unavailable))
        return
    }

    val totalDays = state.totalDays
    Text(
        text = if (totalDays == null) {
            rangeLabel
        } else {
            stringResource(R.string.ramadan_window_summary, rangeLabel, totalDays)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    val dayNumber = state.todayDayNumber
    if (dayNumber != null) {
        SalahStatusPill(
            text = stringResource(R.string.calendar_ramadan_day, dayNumber),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    } else {
        Text(
            text = stringResource(
                when (state.phase) {
                    RamadanPhase.IN_RAMADAN -> R.string.calendar_ramadan_in_ramadan
                    RamadanPhase.UPCOMING -> R.string.calendar_ramadan_upcoming
                    RamadanPhase.FAR_AWAY -> R.string.calendar_ramadan_not_near
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (state.hijriAdjustmentDays != 0) {
        Text(
            text = stringResource(
                R.string.ramadan_adjustment_note,
                signedDays(state.hijriAdjustmentDays),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    Text(
        text = stringResource(R.string.ramadan_estimate_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
private fun FailureBlock(state: RamadanUiState.Failed) {
    SalahSectionCard {
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
    }
}

/**
 * The Sehri and Iftar reminders, in the same two switches the notification screen writes to.
 *
 * The card is a quick control, not a second settings screen: it turns each reminder on or off, shows
 * what it will do, and its gear opens the full reminder settings for anything finer. That is why the
 * long explanatory paragraph and the duplicate lead-time stepper are gone — both live on the screen
 * the gear opens, and repeating them here made the card the tallest thing on the page.
 */
@Composable
private fun RemindersCard(
    state: RamadanUiState.Ready,
    onOpenNotifications: () -> Unit,
    viewModel: RamadanViewModel,
) {
    val settingsDescription = stringResource(R.string.calendar_open_notifications)

    SalahSectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.ramadan_reminders_card_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onOpenNotifications) {
                GearGlyph(
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { contentDescription = settingsDescription },
                )
            }
        }

        if (!state.masterEnabled) {
            Spacer(modifier = Modifier.height(4.dp))
            SalahUnavailableNote(text = stringResource(R.string.notifications_prayer_master_off))
        }

        Spacer(modifier = Modifier.height(4.dp))
        ReminderRow(
            title = stringResource(R.string.notifications_ramadan_sehri),
            lead = if (state.sehriLeadMinutes == 0) {
                stringResource(R.string.ramadan_reminder_at_time)
            } else {
                stringResource(R.string.notifications_prayer_advance_value, state.sehriLeadMinutes)
            },
            enabled = state.sehriReminderEnabled,
            masterEnabled = state.masterEnabled,
            enabledChange = viewModel::setSehriReminderEnabled,
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        ReminderRow(
            title = stringResource(R.string.notifications_ramadan_iftar),
            lead = if (state.iftarLeadMinutes == 0) {
                stringResource(R.string.ramadan_reminder_at_time)
            } else {
                stringResource(R.string.notifications_prayer_advance_value, state.iftarLeadMinutes)
            },
            enabled = state.iftarReminderEnabled,
            masterEnabled = state.masterEnabled,
            enabledChange = viewModel::setIftarReminderEnabled,
        )

        state.message?.let { message ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(
                    when (message) {
                        RamadanMessage.ScheduleUpdated -> R.string.ramadan_message_updated
                        RamadanMessage.NoSchedule -> R.string.ramadan_message_no_schedule
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (message == RamadanMessage.NoSchedule) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
    }
}

/**
 * One reminder: its switch, and the lead time that says when it fires.
 *
 * The lead is stated, not edited here. The stepper that used to change it lived on this card *and* on
 * the reminder settings screen, so the same setting could be changed from two places with the card
 * being the only one without the surrounding context. The card's gear is the single way through to
 * the control.
 *
 * The switch is [SalahSwitch] rather than Material's own, because the baseline switch colours do not
 * survive Salah's palette: an off switch was drawn in colours that matched the card it sat on. The
 * control still *is* a Material switch — same gesture, same touch target, same announced state — and the
 * state it shows is the stored preference *and* the master switch, so a switch can never read as on
 * while the reminder is silenced by the master.
 */
@Composable
private fun ReminderRow(
    title: String,
    lead: String,
    enabled: Boolean,
    masterEnabled: Boolean,
    enabledChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = lead,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled && masterEnabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        SalahSwitch(
            checked = enabled && masterEnabled,
            onCheckedChange = enabledChange,
            enabled = masterEnabled,
        )
    }
}

/**
 * The day-by-day timetable: one header row, the days grouped under Ramadan's three stages, and today's
 * row highlighted.
 *
 * The card states both the rule and the provenance once, in prose. Every row then carries only what is
 * unique to it, so the columns can be read straight down: no clock time is printed twice, and no date
 * representation is repeated in a second place on the same screen.
 *
 * The grouping is a heading per stage and nothing more — no nested card per stage, because a card inside
 * a card adds a border and a padding for every group and turns a thirty-row list into a stack of
 * panels. The stages are drawn as tinted label rows in the table's own column, which is what makes the
 * transition between them obvious while keeping the list one continuous surface.
 */
@Composable
private fun ScheduleCard(state: RamadanUiState.Ready) {
    SalahSectionCard {
        Text(
            text = stringResource(R.string.ramadan_schedule_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

        when {
            !state.locationConfigured -> {
                Spacer(modifier = Modifier.height(8.dp))
                SalahUnavailableNote(text = stringResource(R.string.notifications_schedule_no_location))
            }

            state.stageGroups.isEmpty() -> {
                Spacer(modifier = Modifier.height(8.dp))
                SalahUnavailableNote(text = stringResource(R.string.ramadan_window_unavailable))
            }

            else -> {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        R.string.ramadan_schedule_source_rule,
                        sourceLabel(state.timeSource, state.timeSourceProviderId),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.daysWithoutSchedule > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    SalahUnavailableNote(
                        text = stringResource(
                            R.string.ramadan_days_without_schedule,
                            state.daysWithoutSchedule,
                        ),
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                TableHeader()
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                state.stageGroups.forEachIndexed { groupIndex, group ->
                    // The gap before a stage heading is what separates the stages: it is larger than the
                    // spacing between rows, so the list reads as three passages rather than one.
                    if (groupIndex > 0) {
                        Spacer(modifier = Modifier.height(14.dp))
                    }
                    StageHeader(group = group)
                    group.rows.forEachIndexed { index, row ->
                        val previousIsToday = index > 0 && group.rows[index - 1].isToday
                        // No rule hugs the highlighted row: a divider against the gold block would read
                        // as an edge of the block rather than as a separator between two plain rows.
                        if (index > 0 && !row.isToday && !previousIsToday) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        DayRow(row = row)
                    }
                }
            }
        }
    }
}

/**
 * The heading of one stage: its name, and the Ramadan days it covers.
 *
 * Both facts come from the group, so the heading cannot disagree with the rows under it — the range is
 * read off the days that are actually there. That is what lets the last stage read `Days 21–29` in one
 * year and `Days 21–30` in the next without the heading being told which; there is no "day 29" or "day
 * 30" anywhere in this screen, only the days the window produced.
 */
@Composable
private fun StageHeader(group: RamadanStageGroup) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(group.stage.labelRes()),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(
                R.string.ramadan_stage_range,
                group.firstDayNumber,
                group.lastDayNumber,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The stage's name, resolved by the screen so the label follows the interface language. */
private fun RamadanStage.labelRes(): Int = when (this) {
    RamadanStage.RAHMAH -> R.string.ramadan_stage_rahmah
    RamadanStage.MAGHFIRAH -> R.string.ramadan_stage_maghfirah
    RamadanStage.NAJAT -> R.string.ramadan_stage_najat
}

@Composable
private fun TableHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = stringResource(R.string.ramadan_column_date),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(DAY_COLUMN_WEIGHT),
        )
        Text(
            text = stringResource(R.string.calendar_sehri_end),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(TIME_COLUMN_WEIGHT),
        )
        Text(
            text = stringResource(R.string.calendar_iftar),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(TIME_COLUMN_WEIGHT),
        )
    }
}

/**
 * One Ramadan day: its date on the left, the two times on the right.
 *
 * The date is the Gregorian one (`19 Feb`) and it is the row's label, because a list of days in a
 * calendar is read by its dates. The line under it is the day's Hijri date, which is where the Ramadan
 * day number lives: `1 Ramadan 1445 AH` and "Ramadan day 1" are the same number, so printing both would
 * be one fact in two lines and thirty rows of it. The Bangla calendar date is not drawn at all — this is
 * a Ramadan list, and the Bangla calendar's own screen is where that date belongs.
 */
@Composable
private fun DayRow(row: RamadanDayRow) {
    val accents = LocalSalahAccents.current
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val contentColor = if (row.isToday) accents.onRamadanToday else onSurface
    val mutedColor = if (row.isToday) accents.onRamadanToday else onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (row.isToday) accents.ramadanToday else Color.Transparent)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(DAY_COLUMN_WEIGHT).padding(end = 8.dp)) {
            Text(
                text = row.gregorianLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                fontWeight = FontWeight.SemiBold,
            )
            // A day outside the platform's Hijri table has no Hijri date, and the row then shows only
            // the date it really has rather than a Ramadan number it cannot back up.
            row.hijriLabel?.let { hijri ->
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = hijri,
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedColor,
                )
            }
            // The Bangladesh (Bangla) civil date of the day, in its own script and numerals. Absent
            // outside the range the 2019 rules are verified for, where the row states only the dates it
            // really has rather than an approximated one.
            row.banglaLabel?.let { bangla ->
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = bangla,
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedColor,
                )
            }
            if (!row.hasSchedule) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = row.unavailable?.let { stringResource(it.messageRes()) }
                        ?: stringResource(R.string.ramadan_day_no_schedule),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (row.isToday) accents.onRamadanToday else MaterialTheme.colorScheme.error,
                )
            }
        }

        Column(
            modifier = Modifier.weight(TIME_COLUMN_WEIGHT),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                text = row.sehriEndLabel ?: stringResource(R.string.home_time_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End,
            )
            // The precautionary Imsak time, when one is configured, is shown *under* the Sehri deadline
            // and labelled with its own word — never in place of it — because it is a separate, earlier
            // stop and not the end of Sehri.
            row.imsakLabel?.let { imsak ->
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.ramadan_imsak_row, imsak),
                    style = MaterialTheme.typography.labelSmall,
                    color = mutedColor,
                    textAlign = TextAlign.End,
                )
            }
        }
        Text(
            text = row.iftarLabel ?: stringResource(R.string.home_time_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(TIME_COLUMN_WEIGHT),
        )
    }
}

/**
 * The next fasting event and how long remains.
 *
 * Rendered from the countdown-only flow, so the one-second tick that refreshes it recomposes this card
 * and nothing else. When there is no fasting event Salah can state — outside Ramadan, on a day with no
 * timetable, or after Iftar on the last day — the card is absent entirely, rather than showing a
 * placeholder that could read as a countdown to a fast that is not there.
 */
@Composable
private fun FastingCountdownCard(countdown: FastingCountdown?) {
    if (countdown == null) return

    SalahSectionCard {
        Text(
            text = stringResource(R.string.ramadan_countdown_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(
                when (countdown.kind) {
                    FastingEventKind.SEHRI_END -> R.string.ramadan_countdown_sehri
                    FastingEventKind.IFTAR -> R.string.ramadan_countdown_iftar
                },
                CountdownFormatter.format(countdown.remaining),
            ),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * The optional **precautionary Imsak** time and its reminder.
 *
 * Imsak is deliberately kept visually separate from Sehri: this card explains that it is an earlier
 * precautionary stop, not the end of the fast, and it never turns on with a rule of Salah's own — the
 * switch configures a documented interval (minutes before the calculated Fajr) or clears it, and while
 * it is off the card says so instead of showing a time.
 */
@Composable
private fun ImsakCard(state: RamadanUiState.Ready, viewModel: RamadanViewModel) {
    SalahSectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.ramadan_imsak_card_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            SalahSwitch(
                checked = state.imsakConfigured,
                onCheckedChange = viewModel::setImsakEnabled,
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.ramadan_imsak_card_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val minutes = state.imsakMinutesBeforeFajr
        if (minutes == null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.ramadan_imsak_off),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SalahSectionCard
        }

        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.ramadan_imsak_minutes_before_fajr, minutes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { viewModel.shiftImsakMinutes(false) },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(text = "−", style = MaterialTheme.typography.titleMedium)
                }
                TextButton(
                    onClick = { viewModel.shiftImsakMinutes(true) },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(text = "+", style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.ramadan_imsak_reminder),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            SalahSwitch(
                checked = state.imsakReminderEnabled && state.masterEnabled,
                onCheckedChange = viewModel::setImsakReminderEnabled,
                enabled = state.masterEnabled,
            )
        }
    }
}

/** Names where the displayed times actually came from, without overstating any of them. */
@Composable
private fun sourceLabel(source: PrayerTimeSource?, providerId: String?): String = when (source) {
    PrayerTimeSource.LOCAL_CALCULATION -> stringResource(R.string.status_source_local_calculation)
    PrayerTimeSource.REMOTE_API -> stringResource(
        R.string.status_source_remote_api,
        providerId ?: stringResource(R.string.status_source_unconfigured),
    )

    PrayerTimeSource.USER_ENTERED_TIMETABLE -> stringResource(
        R.string.status_source_user_timetable,
        providerId ?: stringResource(R.string.status_source_unconfigured),
    )

    PrayerTimeSource.UNCONFIGURED, null -> stringResource(R.string.status_source_unconfigured)
}

/** Renders a signed day count, so an adjustment never reads as an unsigned offset. */
private fun signedDays(days: Int): String = if (days > 0) "+$days" else days.toString()

/** The date column is the widest: it carries a Gregorian date and, under it, a Hijri one. */
private const val DAY_COLUMN_WEIGHT = 1.5f

/** The two time columns are equal so the times line up down the table. */
private const val TIME_COLUMN_WEIGHT = 1f
