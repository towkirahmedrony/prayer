package io.github.towkirahmedrony.salah.feature.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import io.github.towkirahmedrony.salah.core.model.CalendarSettings
import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import io.github.towkirahmedrony.salah.core.model.DateLanguagePreference
import io.github.towkirahmedrony.salah.feature.common.displayNameRes
import io.github.towkirahmedrony.salah.feature.common.hintRes
import io.github.towkirahmedrony.salah.feature.common.labelRes
import org.koin.androidx.compose.koinViewModel

/**
 * Calendar settings.
 *
 * Three controls, each of which changes something the user can immediately see elsewhere:
 *
 *  - the **default view**, which the Calendar screen opens on;
 *  - the **Hijri day offset**, which moves every Hijri date, the Ramadan range and the Ramadan
 *    reminders, because all of them are derived from the same adjusted date;
 *  - the **date language**, which chooses whether dates are rendered in Bangla or in Latin script and
 *    numerals.
 *
 * What is deliberately absent: a Ramadan reminder section. Those settings live with the other prayer
 * reminders, on the notifications screen, so that there is one place that owns notification
 * permissions, channels and scheduling, and so the Ramadan reminders cannot drift away from the
 * subsystem they depend on. The screen says where they are and links there.
 */
@Composable
fun CalendarSettingsScreen(
    onOpenNotifications: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CalendarSettingsViewModel = koinViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.calendar_settings_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(R.string.calendar_settings_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SalahSectionCard {
            Text(
                text = stringResource(R.string.calendar_default_view_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.calendar_default_view_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(6.dp))
            CalendarSystem.entries.forEach { system ->
                ChoiceRow(
                    label = stringResource(system.displayNameRes()),
                    hint = stringResource(system.hintRes()),
                    selected = settings.preferredSystem == system,
                    onClick = { viewModel.setPreferredSystem(system) },
                )
            }
        }

        SalahSectionCard {
            Text(
                text = stringResource(R.string.calendar_hijri_adjustment_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.calendar_hijri_adjustment_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { viewModel.shiftHijriAdjustment(-1) },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(text = "−", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    text = stringResource(
                        R.string.calendar_hijri_adjustment_value,
                        signedDayCount(settings.hijriAdjustmentDays),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (settings.hijriAdjustmentDays == 0) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(min = 140.dp),
                )
                TextButton(
                    onClick = { viewModel.shiftHijriAdjustment(1) },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(text = "+", style = MaterialTheme.typography.titleMedium)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.calendar_hijri_adjustment_range,
                    CalendarSettings.MAX_HIJRI_ADJUSTMENT_DAYS,
                    CalendarSettings.MAX_HIJRI_ADJUSTMENT_DAYS,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (settings.hijriAdjustmentDays != 0) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = viewModel::resetHijriAdjustment) {
                    Text(text = stringResource(R.string.calendar_hijri_adjustment_reset))
                }
            }
        }

        SalahSectionCard {
            Text(
                text = stringResource(R.string.calendar_language_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.calendar_language_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(6.dp))
            DateLanguagePreference.entries.forEach { preference ->
                ChoiceRow(
                    label = stringResource(preference.labelRes()),
                    hint = null,
                    selected = settings.dateLanguage == preference,
                    onClick = { viewModel.setDateLanguage(preference) },
                )
            }
        }

        SalahSectionCard {
            Text(
                text = stringResource(R.string.calendar_sources_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.calendar_sources_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.calendar_reminders_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(onClick = onOpenNotifications) {
                Text(text = stringResource(R.string.calendar_open_notifications))
            }
        }
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    hint: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            hint?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Renders a signed day count, so an adjustment never reads as an unsigned offset. */
private fun signedDayCount(days: Int): String = if (days > 0) "+$days" else days.toString()
