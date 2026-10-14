package io.github.towkirahmedrony.salah.feature.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSectionCard
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahStatusPill
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSubpageHeader
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSwitch
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahUnavailableNote
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationPlanner
import io.github.towkirahmedrony.salah.core.model.NotificationSound
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerNotificationPreference
import io.github.towkirahmedrony.salah.core.model.PrayerNotificationSettings
import io.github.towkirahmedrony.salah.feature.common.hintRes
import io.github.towkirahmedrony.salah.feature.common.labelRes
import org.koin.androidx.compose.koinViewModel

/**
 * Prayer notification settings.
 *
 * Uses Salah's existing design system — the same `SalahSectionCard`, status pill and unavailable
 * note the Home and Settings screens use — and is a destination of its own rather than a section
 * bolted onto Settings, because it carries enough state (per-prayer detail, permission state,
 * schedule status) to deserve a screen.
 *
 * Three honesty rules shape the content:
 *
 *  1. **Only implemented controls appear.** The sound, vibration and advance controls are all
 *     backed by behaviour that exists. There is no "play the Azan" switch, because no licensed
 *     recording ships; instead a card states that plainly and says what happens today.
 *  2. **Permission state is shown, not assumed.** Notification permission and exact-alarm access
 *     are separate permissions and are presented separately, each with its own explanation and its
 *     own action. Salah asks for each only when the user acts, and never nags.
 *  3. **Android's limits are stated.** Do Not Disturb, battery optimisation, delivery timing with
 *     inexact alarms and the fact that channel settings belong to the user are all spelled out, so
 *     nothing implies a guarantee Android does not offer.
 *
 * It is the "Notifications & Adhan" category of the Settings overview, and it is also reached from the
 * calendar and Ramadan screens, which point here rather than growing reminder controls of their own.
 * The back action pops, so it returns to whichever of those opened it.
 */
@Composable
fun PrayerNotificationSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PrayerNotificationSettingsViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Re-read the permission state when the screen appears: the user may have changed it in system
    // settings since Salah last looked.
    LaunchedEffect(Unit) { viewModel.refreshPermissionState() }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { viewModel.refreshPermissionState() }

    val systemSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { viewModel.refreshPermissionState() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SalahSubpageHeader(
            title = stringResource(R.string.notifications_title),
            backContentDescription = stringResource(R.string.action_back),
            onBack = onBack,
        )
        Text(
            text = stringResource(R.string.notifications_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PermissionCard(
            uiState = uiState,
            onRequestNotificationPermission = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    // The permission does not exist below API 33; notifications there are turned off
                    // at the app level, so the only useful destination is the app's settings page.
                    systemSettingsLauncher.launch(appNotificationSettingsIntent(context.packageName))
                }
            },
            onRequestExactAlarms = {
                systemSettingsLauncher.launch(exactAlarmSettingsIntent(context.packageName))
            },
            onOpenAppNotificationSettings = {
                systemSettingsLauncher.launch(appNotificationSettingsIntent(context.packageName))
            },
        )

        MasterSwitchCard(
            enabled = uiState.settings.masterEnabled,
            onEnabledChange = viewModel::setMasterEnabled,
        )

        ScheduleCard(
            uiState = uiState,
            onRecalculate = viewModel::recalculate,
        )

        Text(
            text = stringResource(R.string.notifications_prayers_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.notifications_prayers_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PrayerName.entries.forEach { prayer ->
            PrayerCard(
                prayer = prayer,
                preference = uiState.settings.preferenceFor(prayer),
                masterEnabled = uiState.settings.masterEnabled,
                onEnabledChange = { viewModel.setPrayerEnabled(prayer, it) },
                onSoundChange = { viewModel.setPrayerSound(prayer, it) },
                onVibrationChange = { viewModel.setPrayerVibration(prayer, it) },
                onAdvanceShift = { forward -> viewModel.shiftPrayerAdvance(prayer, forward) },
                onPreview = viewModel::previewSound,
            )
        }

        SolarEventCard(
            enabled = uiState.settings.sunriseReminderEnabled,
            onEnabledChange = viewModel::setSunriseReminderEnabled,
        )

        RamadanReminderCard(
            settings = uiState.settings,
            onSehriEnabledChange = viewModel::setSehriReminderEnabled,
            onSehriLeadShift = viewModel::shiftSehriLead,
            onIftarEnabledChange = viewModel::setIftarReminderEnabled,
            onIftarLeadShift = viewModel::shiftIftarLead,
        )

        AzanCard(uiState = uiState)

        LimitationsCard(uiState = uiState)

        MessageBlock(uiState.message)
    }
}

@Composable
private fun PermissionCard(
    uiState: PrayerNotificationSettingsUiState,
    onRequestNotificationPermission: () -> Unit,
    onRequestExactAlarms: () -> Unit,
    onOpenAppNotificationSettings: () -> Unit,
) {
    SalahSectionCard {
        Text(
            text = stringResource(R.string.notifications_permission_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(10.dp))

        PermissionRow(
            label = stringResource(R.string.notifications_permission_notifications),
            granted = uiState.permissions.notificationsAllowed,
        )
        PermissionRow(
            label = stringResource(R.string.notifications_permission_exact_alarms),
            granted = uiState.permissions.exactAlarmsAllowed,
        )

        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.notifications_permission_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!uiState.permissions.notificationsAllowed) {
                Button(onClick = onRequestNotificationPermission) {
                    Text(text = stringResource(R.string.notifications_permission_grant))
                }
            } else {
                OutlinedButton(onClick = onOpenAppNotificationSettings) {
                    Text(text = stringResource(R.string.notifications_permission_manage))
                }
            }

            if (!uiState.permissions.exactAlarmsAllowed) {
                OutlinedButton(onClick = onRequestExactAlarms) {
                    Text(text = stringResource(R.string.notifications_exact_alarm_grant))
                }
            }
        }

        if (uiState.permissions.exactAlarmFallbackInUse) {
            Spacer(modifier = Modifier.height(10.dp))
            SalahUnavailableNote(text = stringResource(R.string.notifications_exact_alarm_fallback))
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        SalahStatusPill(
            text = stringResource(
                if (granted) {
                    R.string.notifications_permission_granted
                } else {
                    R.string.notifications_permission_denied
                },
            ),
            containerColor = if (granted) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
            contentColor = if (granted) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onErrorContainer
            },
        )
    }
}

@Composable
private fun MasterSwitchCard(enabled: Boolean, onEnabledChange: (Boolean) -> Unit) {
    SalahSectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.notifications_master_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(
                if (enabled) {
                    R.string.notifications_master_body_on
                } else {
                    R.string.notifications_master_body_off
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScheduleCard(
    uiState: PrayerNotificationSettingsUiState,
    onRecalculate: () -> Unit,
) {
    SalahSectionCard {
        Text(
            text = stringResource(R.string.notifications_schedule_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))

        val summary = uiState.scheduleSummary
        when {
            !uiState.locationConfigured -> {
                SalahUnavailableNote(text = stringResource(R.string.notifications_schedule_no_location))
            }

            summary == null -> {
                Text(
                    text = stringResource(R.string.notifications_schedule_unknown),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                Text(
                    text = stringResource(
                        R.string.notifications_schedule_count,
                        summary.scheduled,
                        PrayerNotificationPlanner.WINDOW_DAYS,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (summary.datesWithoutSchedule > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    SalahUnavailableNote(
                        text = stringResource(
                            R.string.notifications_schedule_missing_dates,
                            summary.datesWithoutSchedule,
                        ),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.notifications_schedule_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedButton(onClick = onRecalculate) {
            Text(text = stringResource(R.string.notifications_schedule_recalculate))
        }
    }
}

@Composable
private fun PrayerCard(
    prayer: PrayerName,
    preference: PrayerNotificationPreference,
    masterEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onSoundChange: (NotificationSound) -> Unit,
    onVibrationChange: (Boolean) -> Unit,
    onAdvanceShift: (Boolean) -> Unit,
    onPreview: (NotificationSound) -> Unit,
) {
    SalahSectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(prayer.labelRes()),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Switch(
                checked = preference.enabled && masterEnabled,
                onCheckedChange = onEnabledChange,
                enabled = masterEnabled,
            )
        }

        if (!masterEnabled) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.notifications_prayer_master_off),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SalahSectionCard
        }

        if (!preference.enabled) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.notifications_prayer_disabled),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SalahSectionCard
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.notifications_prayer_sound),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(2.dp))

        NotificationSound.entries.forEach { sound ->
            ChoiceRow(
                label = stringResource(sound.labelRes()),
                hint = stringResource(sound.hintRes()),
                selected = preference.sound == sound,
                onClick = { onSoundChange(sound) },
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
        TextButton(onClick = { onPreview(preference.sound) }) {
            Text(text = stringResource(R.string.notifications_prayer_test_sound))
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.notifications_prayer_vibration),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.notifications_prayer_vibration_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = preference.vibration, onCheckedChange = onVibrationChange)
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.notifications_prayer_advance),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.notifications_prayer_advance_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { onAdvanceShift(false) },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(text = "−", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    text = if (preference.advanceMinutes == 0) {
                        stringResource(R.string.notifications_prayer_advance_off)
                    } else {
                        stringResource(
                            R.string.notifications_prayer_advance_value,
                            preference.advanceMinutes,
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (preference.advanceMinutes == 0) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(min = 84.dp),
                )
                TextButton(
                    onClick = { onAdvanceShift(true) },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(text = "+", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun SolarEventCard(enabled: Boolean, onEnabledChange: (Boolean) -> Unit) {
    SalahSectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.notifications_solar_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.notifications_solar_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Ramadan reminders.
 *
 * Two independent switches rather than one, because Sehri and Iftar are wanted at different times of
 * day and one of them may be redundant for a given user. Both are backed by real scheduling: they use
 * the same planner, the same channels and the same reconciliation as the prayer reminders, and they are
 * only ever scheduled for the dates the calendar places inside Ramadan.
 */
@Composable
private fun RamadanReminderCard(
    settings: PrayerNotificationSettings,
    onSehriEnabledChange: (Boolean) -> Unit,
    onSehriLeadShift: (Boolean) -> Unit,
    onIftarEnabledChange: (Boolean) -> Unit,
    onIftarLeadShift: (Boolean) -> Unit,
) {
    SalahSectionCard {
        Text(
            text = stringResource(R.string.notifications_ramadan_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.notifications_ramadan_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!settings.masterEnabled) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.notifications_prayer_master_off),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SalahSectionCard
        }

        Spacer(modifier = Modifier.height(10.dp))
        RamadanReminderRow(
            title = stringResource(R.string.notifications_ramadan_sehri),
            body = stringResource(R.string.notifications_ramadan_sehri_body),
            enabled = settings.sehriReminderEnabled,
            onEnabledChange = onSehriEnabledChange,
            leadLabel = stringResource(R.string.notifications_prayer_advance),
            leadValue = stringResource(
                R.string.notifications_prayer_advance_value,
                settings.sehriLeadMinutes,
            ),
            onLeadShift = onSehriLeadShift,
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        RamadanReminderRow(
            title = stringResource(R.string.notifications_ramadan_iftar),
            body = stringResource(R.string.notifications_ramadan_iftar_body),
            enabled = settings.iftarReminderEnabled,
            onEnabledChange = onIftarEnabledChange,
            leadLabel = stringResource(R.string.notifications_ramadan_iftar_lead),
            leadValue = if (settings.iftarLeadMinutes == 0) {
                stringResource(R.string.notifications_ramadan_iftar_lead_at_time)
            } else {
                stringResource(R.string.notifications_prayer_advance_value, settings.iftarLeadMinutes)
            },
            onLeadShift = onIftarLeadShift,
        )

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.notifications_ramadan_source_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RamadanReminderRow(
    title: String,
    body: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    leadLabel: String,
    leadValue: String,
    onLeadShift: (Boolean) -> Unit,
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
        // The same switch the Ramadan page draws. These are the same two reminders the Ramadan card
        // switches, and an off state has to be as visible on this screen as it is on that one.
        SalahSwitch(checked = enabled, onCheckedChange = onEnabledChange)
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
                onClick = { onLeadShift(false) },
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
                onClick = { onLeadShift(true) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                enabled = enabled,
            ) {
                Text(text = "+", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun AzanCard(uiState: PrayerNotificationSettingsUiState) {
    SalahSectionCard {
        Text(
            text = stringResource(R.string.notifications_azan_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))

        SalahStatusPill(
            text = stringResource(
                if (uiState.azanAvailable) {
                    R.string.notifications_azan_available
                } else {
                    R.string.notifications_azan_unavailable
                },
            ),
            containerColor = if (uiState.azanAvailable) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (uiState.azanAvailable) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(
                if (uiState.azanAvailable) {
                    R.string.notifications_azan_body_available
                } else {
                    R.string.notifications_azan_body_unavailable
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        uiState.azanAttribution?.let { attribution ->
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.notifications_azan_attribution, attribution),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LimitationsCard(uiState: PrayerNotificationSettingsUiState) {
    SalahSectionCard {
        Text(
            text = stringResource(R.string.notifications_limitations_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.notifications_limitations_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.notifications_channel_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.notifications_offline_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!uiState.permissions.notificationsAllowed) {
            Spacer(modifier = Modifier.height(8.dp))
            SalahStatusPill(
                text = stringResource(R.string.notifications_status_no_permission),
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun MessageBlock(message: NotificationSettingsMessage?) {
    if (message == null) return
    val isError = message == NotificationSettingsMessage.SoundPreviewFailed ||
        message == NotificationSettingsMessage.ScheduleUnavailable

    Text(
        text = stringResource(
            when (message) {
                NotificationSettingsMessage.SoundPreviewStarted ->
                    R.string.notifications_message_preview_started

                NotificationSettingsMessage.SoundPreviewFailed ->
                    R.string.notifications_message_preview_failed

                NotificationSettingsMessage.ScheduleRecalculated ->
                    R.string.notifications_message_recalculated

                NotificationSettingsMessage.ScheduleUnavailable ->
                    R.string.notifications_message_schedule_unavailable
            },
        ),
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun ChoiceRow(
    label: String,
    hint: String,
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
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Opens the per-app notification settings page, which exists at every supported API level. */
private fun appNotificationSettingsIntent(packageName: String): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

/**
 * Opens the system's exact-alarm grant screen.
 *
 * Only reachable on API 31 and later: below that `SCHEDULE_EXACT_ALARM` does not exist, so
 * `exactAlarmsAllowed` is always true and the button that leads here is never shown. The action
 * constant itself is a compile-time `String`, hence the suppression. If no activity handles the
 * intent the caller's result callback still fires and the permission state is re-read unchanged.
 */
@SuppressLint("InlinedApi")
private fun exactAlarmSettingsIntent(packageName: String): Intent =
    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
        data = Uri.fromParts("package", packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
