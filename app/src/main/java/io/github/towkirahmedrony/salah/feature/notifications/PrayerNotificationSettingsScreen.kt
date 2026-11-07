package io.github.towkirahmedrony.salah.feature.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSectionCard
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahStatusPill
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSubpageHeader
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahUnavailableNote
import io.github.towkirahmedrony.salah.core.designsystem.components.SettingsChoiceRow
import io.github.towkirahmedrony.salah.core.designsystem.components.SettingsDivider
import io.github.towkirahmedrony.salah.core.designsystem.components.SettingsGroupTitle
import io.github.towkirahmedrony.salah.core.designsystem.components.SettingsStepper
import io.github.towkirahmedrony.salah.core.designsystem.components.SettingsStepperRow
import io.github.towkirahmedrony.salah.core.designsystem.components.SettingsSupportingText
import io.github.towkirahmedrony.salah.core.designsystem.components.SettingsSwitchRow
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
 * One card per group, one line per setting: the screen is a control panel, not an explanation of one.
 * Every behaviour behind these controls is unchanged. What changed is presentation: long paragraphs
 * became a single line where the line is needed, and disappeared where the control already said it.
 *
 * Three honesty rules still shape what appears:
 *
 *  1. **Only implemented controls appear.** Sound, vibration and advance are all backed by behaviour
 *     that exists. There is no "play the Azan" switch, because no licensed recording ships; the Azan
 *     card states that in one line.
 *  2. **Permission state is shown, not assumed.** Notification permission and exact-alarm access are
 *     separate, so they are separate rows with their own state and their own action.
 *  3. **Android's limits are stated.** Do Not Disturb, battery management, delivery timing and channel
 *     ownership are kept, in one short line each, so nothing implies a guarantee Android does not offer.
 *
 * It is the "Notifications & Adhan" category of the Settings overview, and it is also reached from the
 * calendar and Ramadan screens. The back action pops, so it returns to whichever of those opened it.
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
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SalahSubpageHeader(
            title = stringResource(R.string.notifications_title),
            backContentDescription = stringResource(R.string.action_back),
            onBack = onBack,
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

        RemindersCard(
            uiState = uiState,
            onMasterEnabledChange = viewModel::setMasterEnabled,
            onRecalculate = viewModel::recalculate,
        )

        PrayersCard(
            settings = uiState.settings,
            onEnabledChange = viewModel::setPrayerEnabled,
            onSoundChange = viewModel::setPrayerSound,
            onVibrationChange = viewModel::setPrayerVibration,
            onAdvanceShift = viewModel::shiftPrayerAdvance,
            onPreview = viewModel::previewSound,
        )

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
            onImsakEnabledChange = viewModel::setImsakReminderEnabled,
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
        SettingsGroupTitle(text = stringResource(R.string.notifications_permission_title))
        Spacer(modifier = Modifier.height(2.dp))

        PermissionRow(
            label = stringResource(R.string.notifications_permission_notifications),
            granted = uiState.permissions.notificationsAllowed,
        )
        PermissionRow(
            label = stringResource(R.string.notifications_permission_exact_alarms),
            granted = uiState.permissions.exactAlarmsAllowed,
        )

        // The exact-alarm explanation is the only part of the old paragraph worth keeping, and only
        // while it applies: it says what the missing permission actually costs.
        if (!uiState.permissions.exactAlarmsAllowed) {
            Spacer(modifier = Modifier.height(2.dp))
            SettingsSupportingText(text = stringResource(R.string.notifications_permission_body))
        }

        Spacer(modifier = Modifier.height(2.dp))
        if (uiState.permissions.notificationsAllowed) {
            TextButton(onClick = onOpenAppNotificationSettings) {
                Text(text = stringResource(R.string.notifications_permission_manage))
            }
        } else {
            TextButton(onClick = onRequestNotificationPermission) {
                Text(text = stringResource(R.string.notifications_permission_grant))
            }
        }
        if (!uiState.permissions.exactAlarmsAllowed) {
            TextButton(onClick = onRequestExactAlarms) {
                Text(text = stringResource(R.string.notifications_exact_alarm_grant))
            }
        }

        if (uiState.permissions.exactAlarmFallbackInUse) {
            Spacer(modifier = Modifier.height(4.dp))
            SalahUnavailableNote(text = stringResource(R.string.notifications_exact_alarm_fallback))
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
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
private fun RemindersCard(
    uiState: PrayerNotificationSettingsUiState,
    onMasterEnabledChange: (Boolean) -> Unit,
    onRecalculate: () -> Unit,
) {
    SalahSectionCard {
        SettingsSwitchRow(
            title = stringResource(R.string.notifications_master_title),
            checked = uiState.settings.masterEnabled,
            onCheckedChange = onMasterEnabledChange,
            supporting = stringResource(
                if (uiState.settings.masterEnabled) {
                    R.string.notifications_master_body_on
                } else {
                    R.string.notifications_master_body_off
                },
            ),
        )

        SettingsDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.notifications_schedule_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onRecalculate) {
                Text(text = stringResource(R.string.notifications_schedule_recalculate))
            }
        }

        val summary = uiState.scheduleSummary
        when {
            !uiState.locationConfigured -> {
                SalahUnavailableNote(text = stringResource(R.string.notifications_schedule_no_location))
            }

            summary == null -> {
                SettingsSupportingText(text = stringResource(R.string.notifications_schedule_unknown))
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
                    Spacer(modifier = Modifier.height(6.dp))
                    SalahUnavailableNote(
                        text = stringResource(
                            R.string.notifications_schedule_missing_dates,
                            summary.datesWithoutSchedule,
                        ),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        SettingsSupportingText(text = stringResource(R.string.notifications_schedule_body))
    }
}

/**
 * The five prayers, one row each, expanding into their own controls only when they are on.
 *
 * A single card rather than five: a disabled prayer is one switch row (or one muted row when the
 * master switch is off), so a user who only wants Fajr and Maghrib scans five lines instead of five
 * cards. Each prayer still owns its sound, vibration and advance reminder exactly as before.
 */
@Composable
private fun PrayersCard(
    settings: PrayerNotificationSettings,
    onEnabledChange: (PrayerName, Boolean) -> Unit,
    onSoundChange: (PrayerName, NotificationSound) -> Unit,
    onVibrationChange: (PrayerName, Boolean) -> Unit,
    onAdvanceShift: (PrayerName, Boolean) -> Unit,
    onPreview: (NotificationSound) -> Unit,
) {
    SalahSectionCard {
        SettingsGroupTitle(text = stringResource(R.string.notifications_prayers_title))

        PrayerName.entries.forEachIndexed { index, prayer ->
            if (index > 0) SettingsDivider()
            PrayerRow(
                prayer = prayer,
                preference = settings.preferenceFor(prayer),
                masterEnabled = settings.masterEnabled,
                onEnabledChange = { onEnabledChange(prayer, it) },
                onSoundChange = { onSoundChange(prayer, it) },
                onVibrationChange = { onVibrationChange(prayer, it) },
                onAdvanceShift = { forward -> onAdvanceShift(prayer, forward) },
                onPreview = onPreview,
            )
        }
    }
}

@Composable
private fun PrayerRow(
    prayer: PrayerName,
    preference: PrayerNotificationPreference,
    masterEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onSoundChange: (NotificationSound) -> Unit,
    onVibrationChange: (Boolean) -> Unit,
    onAdvanceShift: (Boolean) -> Unit,
    onPreview: (NotificationSound) -> Unit,
) {
    SettingsSwitchRow(
        title = stringResource(prayer.labelRes()),
        checked = preference.enabled && masterEnabled,
        onCheckedChange = onEnabledChange,
        enabled = masterEnabled,
    )

    if (!masterEnabled || !preference.enabled) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.notifications_prayer_sound),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        TextButton(onClick = { onPreview(preference.sound) }) {
            Text(text = stringResource(R.string.notifications_prayer_test_sound))
        }
    }

    Column(modifier = Modifier.selectableGroup()) {
        NotificationSound.entries.forEach { sound ->
            val selected = preference.sound == sound
            SettingsChoiceRow(
                label = stringResource(sound.labelRes()),
                selected = selected,
                onClick = { onSoundChange(sound) },
                // Only the chosen sound explains itself, so Silent cannot be mistaken for "no
                // reminder" while the list itself stays three short lines.
                hint = if (selected) stringResource(sound.hintRes()) else null,
            )
        }
    }

    SettingsDivider()

    SettingsSwitchRow(
        title = stringResource(R.string.notifications_prayer_vibration),
        checked = preference.vibration,
        onCheckedChange = onVibrationChange,
    )

    SettingsDivider()

    SettingsStepperRow(
        label = stringResource(R.string.notifications_prayer_advance),
        value = if (preference.advanceMinutes == 0) {
            stringResource(R.string.notifications_prayer_advance_off)
        } else {
            stringResource(R.string.notifications_prayer_advance_value, preference.advanceMinutes)
        },
        onDecrease = { onAdvanceShift(false) },
        onIncrease = { onAdvanceShift(true) },
        emphasized = preference.advanceMinutes != 0,
    )
}

@Composable
private fun SolarEventCard(enabled: Boolean, onEnabledChange: (Boolean) -> Unit) {
    SalahSectionCard {
        SettingsSwitchRow(
            title = stringResource(R.string.notifications_solar_title),
            checked = enabled,
            onCheckedChange = onEnabledChange,
            supporting = stringResource(R.string.notifications_solar_body),
        )
    }
}

/**
 * Ramadan reminders.
 *
 * Two independent switches, because Sehri and Iftar are wanted at different times of day and one may
 * be redundant for a given user. Both use the same planner, channels and reconciliation as the prayer
 * reminders, and are only scheduled for the dates the calendar places inside Ramadan.
 */
@Composable
private fun RamadanReminderCard(
    settings: PrayerNotificationSettings,
    onSehriEnabledChange: (Boolean) -> Unit,
    onSehriLeadShift: (Boolean) -> Unit,
    onIftarEnabledChange: (Boolean) -> Unit,
    onIftarLeadShift: (Boolean) -> Unit,
    onImsakEnabledChange: (Boolean) -> Unit,
) {
    SalahSectionCard {
        SettingsGroupTitle(text = stringResource(R.string.notifications_ramadan_title))
        Spacer(modifier = Modifier.height(2.dp))
        SettingsSupportingText(text = stringResource(R.string.notifications_ramadan_body))

        if (!settings.masterEnabled) return@SalahSectionCard

        SettingsDivider()

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

        SettingsDivider()

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

        SettingsDivider()

        // The precautionary Imsak reminder has no lead stepper of its own: its time is the Imsak
        // interval relative to Fajr, configured on the Ramadan screen, and the reminder fires at
        // Imsak. Without a configured interval the planner schedules nothing, which the body says.
        SettingsSwitchRow(
            title = stringResource(R.string.notifications_ramadan_imsak),
            checked = settings.imsakReminderEnabled,
            onCheckedChange = onImsakEnabledChange,
            supporting = stringResource(R.string.notifications_ramadan_imsak_body),
        )

        Spacer(modifier = Modifier.height(4.dp))
        SettingsSupportingText(text = stringResource(R.string.notifications_ramadan_source_note))
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
    SettingsSwitchRow(
        title = title,
        checked = enabled,
        onCheckedChange = onEnabledChange,
        supporting = body,
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = leadLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(end = 8.dp),
        )
        SettingsStepper(
            value = leadValue,
            onDecrease = { onLeadShift(false) },
            onIncrease = { onLeadShift(true) },
            emphasized = enabled,
            enabled = enabled,
            valueMinWidth = 88.dp,
        )
    }
}

@Composable
private fun AzanCard(uiState: PrayerNotificationSettingsUiState) {
    SalahSectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsGroupTitle(
                text = stringResource(R.string.notifications_azan_title),
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
            )
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
        }

        Spacer(modifier = Modifier.height(4.dp))
        SettingsSupportingText(
            text = stringResource(
                if (uiState.azanAvailable) {
                    R.string.notifications_azan_body_available
                } else {
                    R.string.notifications_azan_body_unavailable
                },
            ),
        )

        uiState.azanAttribution?.let { attribution ->
            Spacer(modifier = Modifier.height(4.dp))
            SettingsSupportingText(
                text = stringResource(R.string.notifications_azan_attribution, attribution),
            )
        }
    }
}

@Composable
private fun LimitationsCard(uiState: PrayerNotificationSettingsUiState) {
    SalahSectionCard {
        SettingsGroupTitle(text = stringResource(R.string.notifications_limitations_title))
        Spacer(modifier = Modifier.height(4.dp))
        SettingsSupportingText(text = stringResource(R.string.notifications_limitations_body))
        Spacer(modifier = Modifier.height(4.dp))
        SettingsSupportingText(text = stringResource(R.string.notifications_channel_note))
        Spacer(modifier = Modifier.height(4.dp))
        SettingsSupportingText(text = stringResource(R.string.notifications_offline_note))

        if (!uiState.permissions.notificationsAllowed) {
            Spacer(modifier = Modifier.height(6.dp))
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
