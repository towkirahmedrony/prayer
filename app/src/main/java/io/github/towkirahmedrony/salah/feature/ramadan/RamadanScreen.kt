package io.github.towkirahmedrony.salah.feature.ramadan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSectionCard
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahStatusPill
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahUnavailableNote
import io.github.towkirahmedrony.salah.core.model.PrayerTimeSource
import io.github.towkirahmedrony.salah.core.model.RamadanPhase
import io.github.towkirahmedrony.salah.feature.common.labelRes
import io.github.towkirahmedrony.salah.feature.common.messageRes
import org.koin.androidx.compose.koinViewModel

/**
 * The Ramadan screen: the estimated window, a daily Sehri and Iftar table, and the reminders.
 *
 * Three honesty rules shape it, and each one is visible in the layout:
 *
 *  1. **The window is an estimate.** It is derived from the platform's Hijri table, so it carries an
 *     "estimated" badge and a sentence saying that local moon sighting may move it, and Salah never
 *     claims an authority has confirmed it.
 *  2. **The times are the existing prayer times.** Sehri is shown next to Fajr and Iftar next to
 *     Maghrib, with the source named underneath, so nothing is presented as a separate Sehri/Iftar
 *     timetable — and the screen says the times were calculated on the device rather than taken from
 *     the Islamic Foundation's timetable, which Salah has not integrated.
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
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = stringResource(R.string.nav_ramadan),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(R.string.ramadan_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (val state = uiState) {
            RamadanUiState.Loading -> Row(
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

            is RamadanUiState.Failed -> SalahSectionCard {
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

            is RamadanUiState.Ready -> {
                WindowCard(state = state, onOpenCalendar = onOpenCalendar)
                ReminderCard(state = state, onOpenNotifications = onOpenNotifications, viewModel = viewModel)
                ScheduleCard(state = state)
            }
        }
    }
}

@Composable
private fun WindowCard(state: RamadanUiState.Ready, onOpenCalendar: () -> Unit) {
    SalahSectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.ramadan_window_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (state.rangeLabel != null) {
                SalahStatusPill(
                    text = stringResource(R.string.calendar_estimated_badge),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        if (state.rangeLabel == null) {
            // No reliable range could be derived, which is a real outcome of a bounded Hijri table.
            SalahUnavailableNote(text = stringResource(R.string.ramadan_window_unavailable))
        } else {
            Text(
                text = stringResource(R.string.calendar_ramadan_range, state.rangeLabel),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            state.hijriYear?.let { year ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.ramadan_hijri_year, year),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.totalDays?.let { days ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.ramadan_day_count, days),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.todayDayNumber?.let { dayNumber ->
                Spacer(modifier = Modifier.height(8.dp))
                SalahStatusPill(
                    text = stringResource(R.string.calendar_ramadan_day, dayNumber),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
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
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.ramadan_estimate_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.hijriAdjustmentDays != 0) {
                Spacer(modifier = Modifier.height(6.dp))
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

        Spacer(modifier = Modifier.height(10.dp))
        OutlinedButton(onClick = onOpenCalendar) {
            Text(text = stringResource(R.string.ramadan_open_calendar))
        }
    }
}

@Composable
private fun ReminderCard(
    state: RamadanUiState.Ready,
    onOpenNotifications: () -> Unit,
    viewModel: RamadanViewModel,
) {
    SalahSectionCard {
        Text(
            text = stringResource(R.string.ramadan_reminders_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.ramadan_reminders_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!state.masterEnabled) {
            Spacer(modifier = Modifier.height(8.dp))
            SalahUnavailableNote(text = stringResource(R.string.notifications_prayer_master_off))
        }

        Spacer(modifier = Modifier.height(10.dp))
        ReminderRow(
            title = stringResource(R.string.notifications_ramadan_sehri),
            body = stringResource(R.string.ramadan_sehri_rule),
            enabled = state.sehriReminderEnabled,
            enabledChange = viewModel::setSehriReminderEnabled,
            leadLabel = stringResource(R.string.notifications_prayer_advance),
            leadValue = stringResource(
                R.string.notifications_prayer_advance_value,
                state.sehriLeadMinutes,
            ),
            leadShift = viewModel::shiftSehriLead,
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        ReminderRow(
            title = stringResource(R.string.notifications_ramadan_iftar),
            body = stringResource(R.string.ramadan_iftar_rule),
            enabled = state.iftarReminderEnabled,
            enabledChange = viewModel::setIftarReminderEnabled,
            leadLabel = stringResource(R.string.notifications_ramadan_iftar_lead),
            leadValue = if (state.iftarLeadMinutes == 0) {
                stringResource(R.string.notifications_ramadan_iftar_lead_at_time)
            } else {
                stringResource(R.string.notifications_prayer_advance_value, state.iftarLeadMinutes)
            },
            leadShift = viewModel::shiftIftarLead,
        )

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.ramadan_reminders_shared),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedButton(onClick = onOpenNotifications) {
            Text(text = stringResource(R.string.calendar_open_notifications))
        }

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

@Composable
private fun ReminderRow(
    title: String,
    body: String,
    enabled: Boolean,
    enabledChange: (Boolean) -> Unit,
    leadLabel: String,
    leadValue: String,
    leadShift: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = enabledChange)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = leadLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = { leadShift(false) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                enabled = enabled,
            ) {
                Text(text = "−", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                text = leadValue,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 96.dp),
            )
            TextButton(
                onClick = { leadShift(true) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                enabled = enabled,
            ) {
                Text(text = "+", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun ScheduleCard(state: RamadanUiState.Ready) {
    SalahSectionCard {
        Text(
            text = stringResource(R.string.ramadan_schedule_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(6.dp))

        when {
            !state.locationConfigured -> {
                SalahUnavailableNote(text = stringResource(R.string.notifications_schedule_no_location))
            }

            state.rows.isEmpty() -> {
                SalahUnavailableNote(text = stringResource(R.string.ramadan_window_unavailable))
            }

            else -> {
                Text(
                    text = stringResource(
                        R.string.ramadan_schedule_source,
                        sourceLabel(state.timeSource, state.timeSourceProviderId),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.ramadan_sehri_iftar_rule),
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
                state.rows.forEach { row ->
                    DayRow(row = row)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
        Column(modifier = Modifier.weight(1.4f)) {
            Text(
                text = stringResource(R.string.ramadan_column_day),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text(
                text = stringResource(R.string.calendar_sehri_end),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
            )
        }
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text(
                text = stringResource(R.string.calendar_iftar),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun DayRow(row: RamadanDayRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1.4f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.calendar_ramadan_day, row.ramadanDayNumber),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                if (row.isToday) {
                    Spacer(modifier = Modifier.size(6.dp))
                    SalahStatusPill(
                        text = stringResource(R.string.calendar_today_label),
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = row.gregorianLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            row.banglaLabel?.let { bangla ->
                Text(
                    text = bangla,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            row.hijriLabel?.let { hijri ->
                Text(
                    text = hijri,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!row.hasSchedule) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = row.unavailable?.let { stringResource(it.messageRes()) }
                        ?: stringResource(R.string.ramadan_day_no_schedule),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text(
                text = row.sehriEndLabel ?: stringResource(R.string.home_time_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
                fontWeight = FontWeight.SemiBold,
            )
            row.fajrLabel?.let { fajr ->
                Text(
                    text = stringResource(R.string.ramadan_fajr_time, fajr),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                )
            }
        }

        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text(
                text = row.iftarLabel ?: stringResource(R.string.home_time_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
                fontWeight = FontWeight.SemiBold,
            )
            row.maghribLabel?.let { maghrib ->
                Text(
                    text = stringResource(R.string.ramadan_maghrib_time, maghrib),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                )
            }
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
