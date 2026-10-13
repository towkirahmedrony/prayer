package io.github.towkirahmedrony.salah.feature.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSectionCard
import io.github.towkirahmedrony.salah.core.model.TimeFormatPreference
import io.github.towkirahmedrony.salah.feature.common.labelRes
import org.koin.androidx.compose.koinViewModel

/**
 * General settings.
 *
 * One preference today: the clock Salah writes times in. It is general rather than a prayer-time
 * setting because it is not an input to the calculation — the engine computes the same instants either
 * way — but a display choice the Home screen, the Ramadan timetable and every delivered reminder all
 * read, which is why changing it is immediate everywhere and changes no calculated time.
 *
 * It is deliberately its own screen rather than a section of Prayer times: on that screen every control
 * changes a time, and mixing in one that only changes how a time is printed would invite the reader to
 * wonder which is which.
 *
 * There is no app-language control. Salah ships a deliberately partial Bangla translation chosen at
 * build time (see `app/lint.xml`), and the calendar's date language is a calendar preference; neither is
 * a language selector, so no control is offered rather than one that appears to switch the interface and
 * does not.
 */
@Composable
fun SettingsGeneralScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsSubpage(
        title = stringResource(R.string.settings_category_general_title),
        onBack = onBack,
        modifier = modifier,
    ) {
        Text(
            text = stringResource(R.string.settings_general_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SalahSectionCard {
            SettingsOptionGroup(
                title = stringResource(R.string.settings_time_format_title),
                body = stringResource(R.string.settings_time_format_body),
                options = TimeFormatPreference.entries,
                selected = uiState.prayerSettings.timeFormat,
                label = { it.labelRes() },
                hint = { null },
                onSelect = viewModel::setTimeFormat,
            )
            Spacer(modifier = Modifier.height(2.dp))
        }
    }
}
