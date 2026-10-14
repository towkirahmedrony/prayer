package io.github.towkirahmedrony.salah.feature.ramadan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahUnavailableNote
import io.github.towkirahmedrony.salah.core.designsystem.theme.LocalSalahAccents
import io.github.towkirahmedrony.salah.core.model.PrayerTimeSource
import io.github.towkirahmedrony.salah.core.model.RamadanPhase
import io.github.towkirahmedrony.salah.feature.common.messageRes
import org.koin.androidx.compose.koinViewModel

/**
 * The Ramadan screen: the estimated window, the Sehri and Iftar timetable, and the reminders.
 *
 * The screen is three purposeful blocks and nothing else — a heading that states which Ramadan this
 * is and how long it runs, one card for the reminders, and one card for the day-by-day timetable.
 * The window used to be a card of its own that repeated the heading, and the table used to repeat
 * the source prayer beside every time; both are gone, so each fact now appears exactly once.
 *
 * Three honesty rules still shape it, and each one is visible in the layout:
 *
 *  1. **The window is an estimate.** It is derived from the platform's Hijri table, so the heading
 *     carries "(Estimated)" beside the range and one sentence says local moon sighting may move it.
 *     Salah never claims an authority has confirmed it.
 *  2. **The times are the existing prayer times.** Sehri is the calculated Fajr and Iftar the
 *     calculated Maghrib, and the table says so once, in words, under its own title, instead of
 *     printing the same clock time twice under every row. The source of the times is named too, so
 *     nothing is presented as a separate Sehri/Iftar timetable Salah has not integrated.
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
                RemindersCard(
                    state = state,
                    onOpenNotifications = onOpenNotifications,
                    viewModel = viewModel,
                )
                ScheduleCard(state = state)
            }
        }
    }
}

/**
 * The heading: which Ramadan this is, how long it runs, where today stands, and the one entry point
 * to the Calendar screen.
 *
 * [state] is `null` while the window is still loading or could not be built, and the heading then
 * falls back to the screen's own name — the screen never invents a year or a range to fill the gap.
 */
@Composable
private fun RamadanHeader(
    state: RamadanUiState.Ready?,
    onOpenCalendar: () -> Unit,
) {
    val calendarDescription = stringResource(R.string.ramadan_open_calendar)
    val subtitle = if (state == null) {
        null
    } else {
        val range = state.rangeLabel
        val days = state.totalDays
        when {
            range == null -> null
            days == null -> range
            else -> stringResource(R.string.ramadan_window_summary, range, days)
        }
    }

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

        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state != null) {
            RamadanStatus(state = state)
        }
    }
}

/**
 * Where today stands against the window: its Ramadan day when it is inside, the phase when it is not,
 * and the one line that says the whole range is an estimate.
 *
 * Only one of the day and the phase sentence is ever drawn, because they say the same kind of thing.
 * The estimate sentence is not optional: the range comes from a calculation no authority has
 * confirmed, and the screen must not present it as though one had.
 */
@Composable
private fun RamadanStatus(state: RamadanUiState.Ready) {
    val rangeLabel = state.rangeLabel
    if (rangeLabel == null) {
        SalahUnavailableNote(text = stringResource(R.string.ramadan_window_unavailable))
        return
    }

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

    Text(
        text = stringResource(R.string.ramadan_estimate_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

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
        Switch(
            checked = enabled && masterEnabled,
            onCheckedChange = enabledChange,
            enabled = masterEnabled,
        )
    }
}

/**
 * The day-by-day timetable: one header row, one row per Ramadan day, and today's row highlighted.
 *
 * The card states both the rule and the provenance once, in prose. Every row then carries only what
 * is unique to it, so the columns can be read straight down: no clock time is printed twice, and no
 * date representation is repeated in a second place on the same screen.
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

            state.rows.isEmpty() -> {
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
                state.rows.forEachIndexed { index, row ->
                    val previousIsToday = index > 0 && state.rows[index - 1].isToday
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

@Composable
private fun TableHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = stringResource(R.string.ramadan_column_day),
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
 * One Ramadan day: its number and dates on the left, the two times on the right.
 *
 * The three date representations are joined into one wrapped line instead of three stacked ones.
 * They are one fact — "which day is this" — and stacking them made every row four lines tall, which
 * is what made a thirty-day table feel longer than it needed to.
 */
@Composable
private fun DayRow(row: RamadanDayRow) {
    val accents = LocalSalahAccents.current
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val contentColor = if (row.isToday) accents.onRamadanToday else onSurface
    val mutedColor = if (row.isToday) accents.onRamadanToday else onSurfaceVariant

    val dates = listOfNotNull(row.gregorianLabel, row.banglaLabel, row.hijriLabel).joinToString(" · ")

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
                text = stringResource(R.string.calendar_ramadan_day, row.ramadanDayNumber),
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = dates,
                style = MaterialTheme.typography.bodySmall,
                color = mutedColor,
            )
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

        Text(
            text = row.sehriEndLabel ?: stringResource(R.string.home_time_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(TIME_COLUMN_WEIGHT),
        )
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

/** The day column is the widest: it carries the number and up to three dates. */
private const val DAY_COLUMN_WEIGHT = 1.5f

/** The two time columns are equal so the times line up down the table. */
private const val TIME_COLUMN_WEIGHT = 1f
