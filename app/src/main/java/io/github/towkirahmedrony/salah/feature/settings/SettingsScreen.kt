package io.github.towkirahmedrony.salah.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.towkirahmedrony.salah.BuildConfig
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSectionCard
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahStatusPill
import io.github.towkirahmedrony.salah.core.model.ThemeMode
import org.koin.androidx.compose.koinViewModel

/**
 * Settings: the only place that writes to local persistence in this phase.
 *
 * Theme mode and the manual location are stored on the device with DataStore. The screen also
 * states plainly that no prayer calculation engine is integrated yet, so configuration that
 * does not exist is not shown as if it worked.
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

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
                onValueChange = { value ->
                    viewModel.onFormChange { it.copy(label = value) }
                },
                label = { Text(stringResource(R.string.settings_location_label)) },
                singleLine = true,
                isError = uiState.form.hasValidationError,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = uiState.form.latitude,
                    onValueChange = { value ->
                        viewModel.onFormChange { it.copy(latitude = value) }
                    },
                    label = { Text(stringResource(R.string.settings_latitude)) },
                    singleLine = true,
                    isError = uiState.form.hasValidationError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = uiState.form.longitude,
                    onValueChange = { value ->
                        viewModel.onFormChange { it.copy(longitude = value) }
                    },
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
                onValueChange = { value ->
                    viewModel.onFormChange { it.copy(timeZoneId = value) }
                },
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

            SettingsMessageBlock(uiState.message)
        }

        // Prayer calculation -------------------------------------------------
        SalahSectionCard {
            Text(
                text = stringResource(R.string.settings_engine_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(10.dp))
            SalahStatusPill(
                text = stringResource(R.string.reason_calculation_not_configured),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
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
    Spacer(modifier = Modifier.height(10.dp))
    Text(
        text = when (message) {
            SettingsMessage.LocationSaved -> stringResource(R.string.settings_location_saved)
            SettingsMessage.LocationCleared -> stringResource(R.string.settings_location_cleared)
            SettingsMessage.LocationRejected -> stringResource(R.string.settings_location_invalid)
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (message == SettingsMessage.LocationRejected) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.primary
        },
    )
}
