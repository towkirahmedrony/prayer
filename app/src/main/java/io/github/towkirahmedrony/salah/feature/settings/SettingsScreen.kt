package io.github.towkirahmedrony.salah.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.towkirahmedrony.salah.R

/**
 * Settings: the overview of categories.
 *
 * **A map, not a list of switches.** This screen answers one question — which kind of setting does the
 * user want to change — and each row opens the screen that owns that subject. Nothing here reads or
 * writes a preference, so the overview has no state, no loading case and no way to disagree with what
 * is stored; everything that can be changed lives one level down, beside the other settings of its kind.
 *
 * Every category is a real destination rather than a section that was moved under a heading: the
 * calculation controls, the notification controls, the location controls and the calendar controls each
 * carry enough state (option groups, per-prayer rows, permission states, a dataset) that a single page
 * containing all of them is the problem this structure exists to solve.
 *
 * The list is [SettingsCategory.entries], so the rows, the titles and the destinations are one
 * declaration. There is no back action: Settings is a top-level destination in the bottom bar, like Home
 * and Calendar, and the system back gesture leaves it the way it leaves those.
 */
@Composable
fun SettingsScreen(
    onOpenCategory: (SettingsCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(R.string.settings_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))

        SettingsCategory.entries.forEach { category ->
            SettingsCategoryRow(
                category = category,
                onClick = { onOpenCategory(category) },
            )
        }
    }
}
