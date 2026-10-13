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
import io.github.towkirahmedrony.salah.core.model.AppLanguage
import io.github.towkirahmedrony.salah.core.model.TimeFormatPreference
import io.github.towkirahmedrony.salah.feature.common.labelRes
import org.koin.androidx.compose.koinViewModel

/**
 * General settings.
 *
 * Two preferences, both of which apply to the app as a whole rather than to one calculation:
 *
 *  - the **interface language**, in the `Language / ভাষা` group at the top. It is the first control on
 *    the screen because it is the one a user who cannot read the current language needs to find
 *    before anything else, and it is a plain radio group rather than a subpage of its own: three
 *    choices do not need a navigation layer, and a selector behind one more tap is the failure this
 *    placement exists to avoid;
 *  - the **time format**, which is general rather than a prayer-time setting because it is not an
 *    input to the calculation — the engine computes the same instants either way — but a display
 *    choice the Home screen, the Ramadan timetable and every delivered reminder all read, which is why
 *    changing it is immediate everywhere and changes no calculated time.
 *
 * The language control is deliberately not a calendar control. Salah has *two* language-ish
 * preferences and they do different things: this one decides the words on every screen, while the
 * calendar's own "date language" decides the script and numerals dates are written in and lives with
 * the calendar preferences next to the dates themselves. Both are offered, each once, and neither
 * changes the other — see `docs/LOCALIZATION.md`.
 *
 * A change to either preference is stored through the repository, so the selection the screen shows is
 * the stored value rather than a local copy: reopening this screen, or restarting the app, shows the
 * same choice. The one thing that could look like success without being it — a write that failed —
 * is reported by [SettingsMessageBlock] rather than silently swallowed.
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
                title = stringResource(R.string.settings_language_title),
                body = stringResource(R.string.settings_language_body),
                options = AppLanguage.entries,
                selected = uiState.appLanguage,
                label = { it.labelRes() },
                hint = { null },
                onSelect = viewModel::setAppLanguage,
            )
            Spacer(modifier = Modifier.height(2.dp))
        }

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

        // Only ever the language control's failure: the time format below the prayer times has its own
        // message on that screen, and both are the same component.
        SettingsMessageBlock(uiState.message)
    }
}
