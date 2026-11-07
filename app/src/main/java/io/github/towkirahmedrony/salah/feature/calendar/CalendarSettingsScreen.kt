package io.github.towkirahmedrony.salah.feature.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.theme.LocalSalahStyle
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSectionCard
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSubpageHeader
import io.github.towkirahmedrony.salah.core.designsystem.components.SettingsChoiceRow
import io.github.towkirahmedrony.salah.core.designsystem.components.SettingsGroupTitle
import io.github.towkirahmedrony.salah.core.designsystem.components.SettingsStepper
import io.github.towkirahmedrony.salah.core.designsystem.components.SettingsSupportingText
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
 * The copy is deliberately short: each option carries at most one line saying what it is, and the
 * estimate warning that matters is stated once, next to the control that changes it. No calculation,
 * conversion or offset logic lives on this screen; it only reads and writes the stored preference.
 *
 * It is the "Calendar" category of Settings, reachable from the Settings overview as well as from the
 * Calendar screen. The back action pops, which returns to whichever of the two opened it.
 *
 * What is deliberately absent: a Ramadan reminder section. Those settings live with the other prayer
 * reminders, on the notifications screen, so that there is one place that owns notification
 * permissions, channels and scheduling. This screen says where they are and links there.
 */
@Composable
fun CalendarSettingsScreen(
    onBack: () -> Unit,
    onOpenNotifications: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CalendarSettingsViewModel = koinViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    val layout = LocalSalahStyle.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = layout.screenHorizontalPadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(layout.listSpacing),
    ) {
        SalahSubpageHeader(
            title = stringResource(R.string.calendar_settings_title),
            backContentDescription = stringResource(R.string.action_back),
            onBack = onBack,
        )

        SalahSectionCard {
            SettingsGroupTitle(text = stringResource(R.string.calendar_default_view_title))
            Spacer(modifier = Modifier.height(2.dp))
            SettingsSupportingText(text = stringResource(R.string.calendar_default_view_body))
            Spacer(modifier = Modifier.height(4.dp))

            Column(modifier = Modifier.selectableGroup()) {
                CalendarSystem.entries.forEach { system ->
                    val selected = settings.preferredSystem == system
                    SettingsChoiceRow(
                        label = stringResource(system.displayNameRes()),
                        selected = selected,
                        onClick = { viewModel.setPreferredSystem(system) },
                        // Only the chosen view explains itself, so the three options stay one line
                        // each and the longest hint in the list is never shown three times.
                        hint = if (selected) stringResource(system.hintRes()) else null,
                    )
                }
            }
        }

        SalahSectionCard {
            SettingsGroupTitle(text = stringResource(R.string.calendar_hijri_adjustment_title))
            Spacer(modifier = Modifier.height(2.dp))
            SettingsSupportingText(text = stringResource(R.string.calendar_hijri_adjustment_body))
            Spacer(modifier = Modifier.height(2.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                SettingsStepper(
                    value = stringResource(
                        R.string.calendar_hijri_adjustment_value,
                        signedDayCount(settings.hijriAdjustmentDays),
                    ),
                    onDecrease = { viewModel.shiftHijriAdjustment(-1) },
                    onIncrease = { viewModel.shiftHijriAdjustment(1) },
                    // The default is the Bangladesh-aligned offset, not zero, so "at the default" is
                    // compared against the model's constant rather than against a hard-coded 0.
                    emphasized = settings.hijriAdjustmentDays !=
                        CalendarSettings.DEFAULT_HIJRI_ADJUSTMENT_DAYS,
                    valueMinWidth = 120.dp,
                )
            }

            SettingsSupportingText(
                text = stringResource(
                    R.string.calendar_hijri_adjustment_range,
                    CalendarSettings.MAX_HIJRI_ADJUSTMENT_DAYS,
                    CalendarSettings.MAX_HIJRI_ADJUSTMENT_DAYS,
                ),
            )

            if (settings.hijriAdjustmentDays != CalendarSettings.DEFAULT_HIJRI_ADJUSTMENT_DAYS) {
                TextButton(onClick = viewModel::resetHijriAdjustment) {
                    Text(text = stringResource(R.string.calendar_hijri_adjustment_reset))
                }
            }
        }

        SalahSectionCard {
            SettingsGroupTitle(text = stringResource(R.string.calendar_language_title))
            Spacer(modifier = Modifier.height(2.dp))
            SettingsSupportingText(text = stringResource(R.string.calendar_language_body))
            Spacer(modifier = Modifier.height(4.dp))

            Column(modifier = Modifier.selectableGroup()) {
                DateLanguagePreference.entries.forEach { preference ->
                    SettingsChoiceRow(
                        label = stringResource(preference.labelRes()),
                        selected = settings.dateLanguage == preference,
                        onClick = { viewModel.setDateLanguage(preference) },
                    )
                }
            }
        }

        SalahSectionCard {
            SettingsGroupTitle(text = stringResource(R.string.calendar_sources_title))
            Spacer(modifier = Modifier.height(4.dp))
            SettingsSupportingText(text = stringResource(R.string.calendar_sources_body))
            Spacer(modifier = Modifier.height(4.dp))
            SettingsSupportingText(text = stringResource(R.string.calendar_reminders_note))
            Spacer(modifier = Modifier.height(2.dp))
            TextButton(onClick = onOpenNotifications) {
                Text(text = stringResource(R.string.calendar_open_notifications))
            }
        }
    }
}

/** Renders a signed day count, so an adjustment never reads as an unsigned offset. */
private fun signedDayCount(days: Int): String = if (days > 0) "+$days" else days.toString()
