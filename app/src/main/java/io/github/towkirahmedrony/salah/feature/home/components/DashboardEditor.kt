package io.github.towkirahmedrony.salah.feature.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSectionCard
import io.github.towkirahmedrony.salah.core.model.DashboardCard
import io.github.towkirahmedrony.salah.feature.common.descriptionRes
import io.github.towkirahmedrony.salah.feature.common.labelRes
import io.github.towkirahmedrony.salah.feature.home.DashboardCardState
import io.github.towkirahmedrony.salah.feature.home.HomeUiState

/**
 * The dashboard customization list.
 *
 * Shown in place of the cards while editing, so a card's own actions cannot be tapped by accident
 * while the layout is being changed. One row per supported card: its name, what it shows, a switch
 * and two move controls. Every change is written immediately through the view model — the same
 * immediate-update convention the rest of Salah's settings use — so there is no unsaved state to
 * lose when the screen is left.
 *
 * Order matters, so move up/down controls are used rather than drag-and-drop: they are the same
 * stepper idiom as the prayer-adjustment controls, they work with a keyboard and a screen reader,
 * and they need no third-party gesture library. A control at the end of its range is disabled rather
 * than hidden, so the row's layout does not jump.
 */
@Composable
fun DashboardEditor(
    state: HomeUiState.Ready,
    onToggleCard: (DashboardCard, Boolean) -> Unit,
    onMoveCard: (DashboardCard, Int) -> Unit,
    onResetDashboard: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmReset by remember { mutableStateOf(false) }
    val cards = state.dashboard.cards

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SalahSectionCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.home_dashboard_edit_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.home_dashboard_edit_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onDone) {
                    Text(text = stringResource(R.string.home_dashboard_done))
                }
            }
        }

        cards.forEachIndexed { index, cardState ->
            DashboardEditorRow(
                cardState = cardState,
                canMoveUp = index > 0,
                canMoveDown = index < cards.lastIndex,
                onToggle = { enabled -> onToggleCard(cardState.card, enabled) },
                onMoveUp = { onMoveCard(cardState.card, -1) },
                onMoveDown = { onMoveCard(cardState.card, 1) },
            )
        }

        TextButton(onClick = { confirmReset = true }) {
            Text(text = stringResource(R.string.home_dashboard_reset))
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(text = stringResource(R.string.home_dashboard_reset_title)) },
            text = { Text(text = stringResource(R.string.home_dashboard_reset_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReset = false
                        onResetDashboard()
                    },
                ) {
                    Text(text = stringResource(R.string.home_dashboard_reset_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text(text = stringResource(R.string.home_dashboard_cancel))
                }
            },
        )
    }
}

/** One card's row in the customization list: its name, its switch and its move controls. */
@Composable
private fun DashboardEditorRow(
    cardState: DashboardCardState,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val title = stringResource(cardState.card.labelRes())
    // Resolved outside the `semantics` blocks below, which are not composable.
    val toggleLabel = stringResource(R.string.home_dashboard_toggle_card, title)
    val moveUpLabel = stringResource(R.string.home_dashboard_move_up, title)
    val moveDownLabel = stringResource(R.string.home_dashboard_move_down, title)

    SalahSectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(cardState.card.descriptionRes()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Switch(
                    checked = cardState.enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.semantics { contentDescription = toggleLabel },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = onMoveUp,
                        enabled = canMoveUp,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.semantics { contentDescription = moveUpLabel },
                    ) {
                        Text(text = "\u2191", style = MaterialTheme.typography.titleMedium)
                    }
                    TextButton(
                        onClick = onMoveDown,
                        enabled = canMoveDown,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.semantics { contentDescription = moveDownLabel },
                    ) {
                        Text(text = "\u2193", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}
