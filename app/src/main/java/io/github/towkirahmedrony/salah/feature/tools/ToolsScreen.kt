package io.github.towkirahmedrony.salah.feature.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import io.github.towkirahmedrony.salah.core.designsystem.LanternGlyph
import io.github.towkirahmedrony.salah.core.designsystem.QiblaGlyph
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahShortcutTile

/**
 * The Tools destination: entry points to the Islamic tools that are not top-level destinations.
 *
 * Each tile opens a destination that states plainly whether it is implemented; this screen
 * itself shows no data.
 */
@Composable
fun ToolsScreen(
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = stringResource(R.string.home_shortcuts_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SalahShortcutTile(
                title = stringResource(R.string.shortcut_qibla),
                subtitle = stringResource(R.string.shortcut_qibla_subtitle),
                glyph = { QiblaGlyph(tint = it) },
                onClick = { onNavigate("qibla") },
                modifier = Modifier.weight(1f),
            )
            SalahShortcutTile(
                title = stringResource(R.string.nav_ramadan),
                subtitle = stringResource(R.string.shortcut_ramadan_subtitle),
                glyph = { LanternGlyph(tint = it) },
                onClick = { onNavigate("ramadan") },
                modifier = Modifier.weight(1f),
            )
        }
    }
}
