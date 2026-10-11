package io.github.towkirahmedrony.salah.feature.settings

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.towkirahmedrony.salah.BuildConfig
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSectionCard
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahStatusPill
import io.github.towkirahmedrony.salah.core.model.AsrCalculationMethod
import io.github.towkirahmedrony.salah.core.model.HighLatitudeRule
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationMethod
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerSettings
import io.github.towkirahmedrony.salah.core.model.ThemeMode
import io.github.towkirahmedrony.salah.core.model.TimeFormatPreference
import io.github.towkirahmedrony.salah.feature.common.hintRes
import io.github.towkirahmedrony.salah.feature.common.labelRes
import org.koin.androidx.compose.koinViewModel

/**
 * Settings: the only place that writes to local persistence.
 *
 * Theme, the manual location and every prayer-calculation option are stored on the device with
 * DataStore. Each control is bound to the persisted value, so the screen always shows what is
 * actually stored, and no option is changed without the user doing it.
 *
 * Only options the engine really supports are offered. There is no control for prohibited
 * prayer windows, because no rule set has been reviewed; the Home screen explains that gap
 * instead.
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = uiState.prayerSettings

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )

        // Appearance ---------------------------------------------------------
        Text(
            text = stringResource(R.string.settings_theme_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeOption(
                label = stringResource(R.string.settings_theme_system),
                selected = uiState.themeMode == ThemeMode.SYSTEM,
                onClick = { viewModel.setThemeMode(ThemeMode.SYSTEM) },
            )
            ThemeOption(
                label = stringResource(R.string.settings_theme_light),
                selected = uiState.themeMode == ThemeMode.LIGHT,
                onClick = { viewModel.setThemeMode(ThemeMode.LIGHT) },
            )
            ThemeOption(
                label = stringResource(R.string.settings_theme_dark),
                selected = uiState.themeMode == ThemeMode.DARK,
                onClick = { viewModel.setThemeMode(ThemeMode.DARK) },
            )
        }

        // Manual location ----------------------------------------------------
        SalahSectionCard {
            Text(
                text = stringResource(R.string.settings_location_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.settings_location_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(14.dp))

            OutlinedTextField(
                value = uiState.form.label,
                onValueChange = { value -> viewModel.onFormChange { it.copy(label = value) } },
                label = { Text(stringResource(R.string.settings_location_label)) },
                singleLine = true,
                isError = uiState.form.hasValidationError,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = uiState.form.latitude,
                    onValueChange = { value -> viewModel.onFormChange { it.copy(latitude = value) } },
                    label = { Text(stringResource(R.string.settings_latitude)) },
                    singleLine = true,
                    isError = uiState.form.hasValidationError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = uiState.form.longitude,
                    onValueChange = { value -> viewModel.onFormChange { it.copy(longitude = value) } },
                    label = { Text(stringResource(R.string.settings_longitude)) },
                    singleLine = true,
                    isError = uiState.form.hasValidationError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = uiState.form.timeZoneId,
                onValueChange = { value -> viewModel.onFormChange { it.copy(timeZoneId = value) } },
                label = { Text(stringResource(R.string.settings_time_zone)) },
                placeholder = { Text("Asia/Dhaka") },
                singleLine = true,
                isError = uiState.form.hasValidationError,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = viewModel::saveLocation) {
                    Text(text = stringResource(R.string.settings_save))
                }
                OutlinedButton(onClick = viewModel::clearLocation) {
                    Text(text = stringResource(R.string.settings_clear))
                }
            }
        }

        // Calculation settings ----------------------------------------------
        SalahSectionCard {
            Text(
                text = stringResource(R.string.settings_calculation_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.settings_calculation_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(14.dp))
            OptionGroup(
                title = stringResource(R.string.settings_method_title),
                body = stringResource(R.string.settings_method_body),
                options = PrayerCalculationMethod.entries,
                selected = settings.method,
                label = { it.labelRes() },
                hint = { it.hintRes() },
                onSelect = viewModel::setCalculationMethod,
            )

            Spacer(modifier = Modifier.height(14.dp))
            OptionGroup(
                title = stringResource(R.string.settings_asr_title),
                body = stringResource(R.string.settings_asr_body),
                options = AsrCalculationMethod.entries,
                selected = settings.asrMethod,
                label = { it.labelRes() },
                hint = { it.hintRes() },
                onSelect = viewModel::setAsrMethod,
            )

            Spacer(modifier = Modifier.height(14.dp))
            OptionGroup(
                title = stringResource(R.string.settings_high_latitude_title),
                body = stringResource(R.string.settings_high_latitude_body),
                options = HighLatitudeRule.entries,
                selected = settings.highLatitudeRule,
                label = { it.labelRes() },
                hint = { null },
                onSelect = viewModel::setHighLatitudeRule,
            )

            Spacer(modifier = Modifier.height(14.dp))
            OptionGroup(
                title = stringResource(R.string.settings_time_format_title),
                body = null,
                options = TimeFormatPreference.entries,
                selected = settings.timeFormat,
                label = { it.labelRes() },
                hint = { null },
                onSelect = viewModel::setTimeFormat,
            )
        }

        // Manual adjustments -------------------------------------------------
        SalahSectionCard {
            Text(
                text = stringResource(R.string.settings_adjustments_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.settings_adjustments_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(10.dp))

            PrayerName.entries.forEachIndexed { index, prayer ->
                AdjustmentRow(
                    label = stringResource(prayer.labelRes()),
                    minutes = settings.adjustmentFor(prayer),
                    onDecrease = { viewModel.shiftAdjustment(prayer, -1) },
                    onIncrease = { viewModel.shiftAdjustment(prayer, 1) },
                )
                if (index != PrayerName.entries.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }

            if (settings.hasAdjustments) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(onClick = viewModel::clearAdjustments) {
                    Text(text = stringResource(R.string.settings_adjustments_clear))
                }
            }
        }

        SettingsMessageBlock(uiState.message)

        // Prayer engine status ----------------------------------------------
        SalahSectionCard {
            Text(
                text = stringResource(R.string.settings_engine_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(10.dp))
            SalahStatusPill(
                text = stringResource(R.string.status_source_local_calculation),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.settings_engine_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // About --------------------------------------------------------------
        SalahSectionCard {
            Text(
                text = stringResource(R.string.settings_about_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.settings_about_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(
                    R.string.settings_version,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A titled group of mutually exclusive options, rendered as an accessible radio list. */
@Composable
private fun <T> OptionGroup(
    title: String,
    body: String?,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> Int,
    hint: @Composable (T) -> Int?,
    onSelect: (T) -> Unit,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
    body?.let {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(modifier = Modifier.height(4.dp))

    options.forEach { option ->
        ChoiceRow(
            label = stringResource(label(option)),
            hint = hint(option)?.let { stringResource(it) },
            selected = option == selected,
            onClick = { onSelect(option) },
        )
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
        // The row is clickable as a whole; the button only reports state to accessibility.
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

@Composable
private fun AdjustmentRow(
    label: String,
    minutes: Int,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = onDecrease,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(text = "−", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                text = stringResource(R.string.settings_adjustment_minutes, signed(minutes)),
                style = MaterialTheme.typography.bodyMedium,
                color = if (minutes == 0) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 56.dp),
            )
            TextButton(
                onClick = onIncrease,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(text = "+", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** Renders a signed minute count, so an adjustment never reads as an unsigned offset. */
private fun signed(minutes: Int): String =
    if (minutes > 0) "+$minutes" else minutes.toString()

@Composable
private fun ThemeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(text = label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(text = label) }
    }
}

@Composable
private fun SettingsMessageBlock(message: SettingsMessage?) {
    if (message == null) return
    val isError = message == SettingsMessage.LocationRejected
    Text(
        text = when (message) {
            SettingsMessage.LocationSaved -> stringResource(R.string.settings_location_saved)
            SettingsMessage.LocationCleared -> stringResource(R.string.settings_location_cleared)
            SettingsMessage.LocationRejected -> stringResource(R.string.settings_location_invalid)
            SettingsMessage.PrayerSettingsSaved -> stringResource(R.string.settings_calculation_saved)
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    )
}
