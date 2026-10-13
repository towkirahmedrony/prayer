package io.github.towkirahmedrony.salah.feature.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSectionCard
import io.github.towkirahmedrony.salah.core.model.AsrCalculationMethod
import io.github.towkirahmedrony.salah.core.model.HighLatitudeRule
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationMethod
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.feature.common.hintRes
import io.github.towkirahmedrony.salah.feature.common.labelRes
import org.koin.androidx.compose.koinViewModel

/**
 * Prayer times settings: everything that decides what the engine calculates, plus the correction the
 * user applies on top of it.
 *
 * Each control writes through `UserPreferencesRepository.updatePrayerSettings`, which reads and writes
 * inside one DataStore transaction — so two quick taps on a stepper both take effect, and no control can
 * show a value that is not stored. Nothing here holds a local copy of the settings, and no default is
 * written over a saved value on open.
 *
 * Only options the engine really supports are offered: the methods and Asr conventions the calculation
 * profile accepts, and the high-latitude rule it already applies. The corrections are minute offsets
 * added to the calculated time, which is the whole of what the engine exposes — there is no control for
 * prohibited prayer windows, because no rule set has been reviewed, and the Home screen says so instead
 * of this screen pretending otherwise.
 */
@Composable
fun SettingsPrayerTimesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = uiState.prayerSettings

    SettingsSubpage(
        title = stringResource(R.string.settings_category_prayer_times_title),
        onBack = onBack,
        modifier = modifier,
    ) {
        Text(
            text = stringResource(R.string.settings_calculation_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SalahSectionCard {
            Text(
                text = stringResource(R.string.settings_calculation_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )

            Spacer(modifier = Modifier.height(12.dp))
            SettingsOptionGroup(
                title = stringResource(R.string.settings_method_title),
                body = stringResource(R.string.settings_method_body),
                options = PrayerCalculationMethod.entries,
                selected = settings.method,
                label = { it.labelRes() },
                hint = { it.hintRes() },
                onSelect = viewModel::setCalculationMethod,
            )

            Spacer(modifier = Modifier.height(14.dp))
            SettingsOptionGroup(
                title = stringResource(R.string.settings_asr_title),
                body = stringResource(R.string.settings_asr_body),
                options = AsrCalculationMethod.entries,
                selected = settings.asrMethod,
                label = { it.labelRes() },
                hint = { it.hintRes() },
                onSelect = viewModel::setAsrMethod,
            )

            Spacer(modifier = Modifier.height(14.dp))
            SettingsOptionGroup(
                title = stringResource(R.string.settings_high_latitude_title),
                body = stringResource(R.string.settings_high_latitude_body),
                options = HighLatitudeRule.entries,
                selected = settings.highLatitudeRule,
                label = { it.labelRes() },
                hint = { null },
                onSelect = viewModel::setHighLatitudeRule,
            )
        }

        SalahSectionCard {
            Text(
                text = stringResource(R.string.settings_adjustments_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.settings_adjustments_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(10.dp))

            PrayerName.entries.forEachIndexed { index, prayer ->
                SettingsAdjustmentRow(
                    label = stringResource(prayer.labelRes()),
                    minutes = settings.adjustmentFor(prayer),
                    decreaseLabelRes = R.string.settings_adjustment_decrease,
                    increaseLabelRes = R.string.settings_adjustment_increase,
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
    }
}

/**
 * The last action's outcome, when saying nothing would leave the user unsure.
 *
 * Two outcomes are worth a line, and only two. The recalculation is reported because adding a minute
 * changes every time on the Home screen, and a change that large deserves an explanation. A rejected
 * language write is reported because the selector renders the *stored* value: without this line the
 * user would be looking at a choice that did not stick. Everything else on these screens shows its own
 * result in the control that changed it, so a success message would be noise.
 *
 * Its text is inline rather than a snackbar, because a snackbar over a scrolling list can be missed and
 * neither message has an action to take. A failure is drawn in the error colour rather than the
 * primary one, so "did not save" cannot be mistaken at a glance for "recalculated".
 */
@Composable
internal fun SettingsMessageBlock(message: SettingsMessage?) {
    if (message == null) return
    Text(
        text = when (message) {
            SettingsMessage.PrayerSettingsSaved -> stringResource(R.string.settings_calculation_saved)
            SettingsMessage.LanguageSaveFailed -> stringResource(R.string.settings_language_save_failed)
        },
        style = MaterialTheme.typography.bodySmall,
        color = when (message) {
            SettingsMessage.LanguageSaveFailed -> MaterialTheme.colorScheme.error
            SettingsMessage.PrayerSettingsSaved -> MaterialTheme.colorScheme.primary
        },
    )
}
