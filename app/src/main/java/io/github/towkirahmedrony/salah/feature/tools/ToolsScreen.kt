package io.github.towkirahmedrony.salah.feature.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.ChevronRightGlyph
import io.github.towkirahmedrony.salah.core.designsystem.LanternGlyph
import io.github.towkirahmedrony.salah.core.designsystem.QiblaGlyph
import io.github.towkirahmedrony.salah.core.designsystem.RamadanGlyph
import io.github.towkirahmedrony.salah.core.designsystem.TasbihBeadsGlyph
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSectionCard
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahUnavailableNote

/**
 * Tools: the entry points to Salah's standalone utilities.
 *
 * **A list of destinations, not a dashboard.** Nothing on this screen reads or calculates anything —
 * it shows no time, no bearing and no date — so it cannot go stale, cannot disagree with the screen it
 * opens, and has no loading, empty or failure case. Each row is [ToolsEntry], and the row navigates with
 * the destination that entry holds.
 *
 * The rows are the Settings overview's own pattern — a card per destination with a mark, a name, a
 * one-line description and a chevron — because the question they answer is the same one: which screen
 * does the user want. They are rendered as a single column rather than a grid so a description can be
 * read without wrapping into a tile's width.
 *
 * **What is not here is stated, not implied.** Salah has no Quran or Dua feature, and the note at the
 * bottom says so instead of leaving a user to conclude the tools are hidden somewhere. The list itself
 * contains no disabled row and no placeholder: every entry opens a screen that exists, and the note names
 * only what is still missing — the Tasbih row is on this screen, so it is no longer on that list.
 *
 * There is no back action: Tools is a top-level destination in the bottom bar, so the system back
 * gesture leaves it the way it leaves Home and Calendar.
 */
@Composable
fun ToolsScreen(
    onOpenTool: (ToolsEntry) -> Unit,
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
            text = stringResource(R.string.nav_tools),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(R.string.tools_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))

        ToolsEntry.entries.forEach { entry ->
            ToolsEntryRow(
                entry = entry,
                onClick = { onOpenTool(entry) },
            )
        }

        SalahUnavailableNote(text = stringResource(R.string.tools_unavailable_note))
    }
}

/**
 * One tool: a mark, a name, what it opens, and a chevron.
 *
 * The whole card is the touch target rather than the title alone, because the row *is* the navigation
 * control; the icon and the two lines of text together exceed the 48dp minimum height.
 */
@Composable
private fun ToolsEntryRow(
    entry: ToolsEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SalahSectionCard(modifier = modifier, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                ToolsEntryGlyph(
                    entry = entry,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp),
            ) {
                Text(
                    text = stringResource(entry.titleRes),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(entry.descriptionRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ChevronRightGlyph(tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The tool's mark.
 *
 * A `when` over the enum rather than a composable lambda held in the enum, so the icon is resolved by
 * the compiler and adding a tool without giving it a mark is a compile error rather than a blank square.
 * The marks are Salah's own canvas drawings, so this screen needs no icon dependency and stays in the
 * same visual language as the bottom bar. The prayer-times row keeps the lantern the Prayer tab used, so
 * the screen it opens is still recognisable from the bar it left.
 */
@Composable
private fun ToolsEntryGlyph(entry: ToolsEntry, tint: Color) {
    when (entry) {
        ToolsEntry.PRAYER_TIMES -> LanternGlyph(tint = tint)
        ToolsEntry.QIBLA -> QiblaGlyph(tint = tint)
        ToolsEntry.RAMADAN -> RamadanGlyph(tint = tint)
        // The counter's own mark, not the Tools tab's: see TasbihBeadsGlyph for why the two differ.
        ToolsEntry.TASBIH -> TasbihBeadsGlyph(tint = tint)
    }
}
