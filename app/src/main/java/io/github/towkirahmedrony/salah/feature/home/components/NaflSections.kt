package io.github.towkirahmedrony.salah.feature.home.components

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSectionCard
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahUnavailableNote
import io.github.towkirahmedrony.salah.core.domain.nafl.NaflKind
import io.github.towkirahmedrony.salah.core.domain.prohibited.ProhibitedPeriodPhase
import io.github.towkirahmedrony.salah.feature.common.labelRes
import io.github.towkirahmedrony.salah.feature.common.messageRes
import io.github.towkirahmedrony.salah.feature.home.HomeUiState
import io.github.towkirahmedrony.salah.feature.home.NaflRow
import io.github.towkirahmedrony.salah.feature.home.ProhibitedSection

@StringRes
private fun NaflKind.titleRes(): Int = when (this) {
    NaflKind.DUHA -> R.string.nafl_duha
    NaflKind.AWWABIN -> R.string.nafl_awwabin
    NaflKind.TAHAJJUD -> R.string.nafl_tahajjud
}

/**
 * The Home card for the Nafl prayers: Duha, Awwabin and Tahajjud with their times and where each
 * stands. Every value is already resolved in the state; this composable formats and decides nothing.
 * The times are Salah's documented conventions, and the card says so rather than presenting a ruling.
 */
@Composable
fun NaflHomeCard(
    state: HomeUiState.Ready,
    onViewDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val nafl = state.nafl
    SalahSectionCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.home_card_nafl_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.home_action_view_details),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onViewDetails)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))

        if (nafl.unavailable != null) {
            SalahUnavailableNote(text = stringResource(nafl.unavailable.messageRes()))
        } else {
            nafl.rows.forEachIndexed { index, row ->
                NaflRowItem(row = row, showPreferred = false)
                if (index != nafl.rows.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.nafl_home_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The full Nafl-prayer detail for the Prayer screen: every period, the recommended last third and the notes. */
@Composable
fun NaflDetailCard(
    state: HomeUiState.Ready,
    modifier: Modifier = Modifier,
) {
    val nafl = state.nafl
    SalahSectionCard(modifier = modifier) {
        Text(
            text = stringResource(R.string.nafl_page_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(6.dp))

        if (nafl.unavailable != null) {
            SalahUnavailableNote(text = stringResource(nafl.unavailable.messageRes()))
        } else {
            nafl.rows.forEachIndexed { index, row ->
                NaflRowItem(row = row, showPreferred = true)
                if (index != nafl.rows.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            nafl.lastThirdStartsLabel?.let { label ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.nafl_last_third_begins, label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        NaflNote(R.string.nafl_home_note)
        NaflNote(R.string.nafl_note_duha)
        NaflNote(R.string.nafl_note_awwabin)
        NaflNote(R.string.nafl_note_disliked)
    }
}

@Composable
private fun NaflNote(@StringRes text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun NaflRowItem(row: NaflRow, showPreferred: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val statusText = when {
        row.isOpen -> stringResource(R.string.nafl_status_open)
        row.isPassed -> stringResource(R.string.home_prayer_status_passed)
        row.startsNextDay -> stringResource(R.string.nafl_status_next_dawn)
        else -> stringResource(R.string.home_prayer_status_later)
    }
    val nameColor = if (row.isOpen) scheme.primary else scheme.onSurface

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = stringResource(row.kind.titleRes()),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = nameColor,
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (row.isOpen) scheme.primary else scheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.home_time_range, row.startLabel, row.endLabel),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = nameColor,
                maxLines = 1,
                softWrap = false,
            )
        }
        if (showPreferred) {
            row.preferredFromLabel?.let { preferred ->
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.nafl_duha_preferred, preferred),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The prohibited intervals with their names, ranges and where each stands. An interval that is open
 * right now is drawn in the error colour with the time left, so it cannot be missed.
 */
@Composable
fun ProhibitedWindowsCard(
    section: ProhibitedSection,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    SalahSectionCard(modifier = modifier) {
        Text(
            text = stringResource(R.string.home_prohibited_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = scheme.onSurface,
        )
        Spacer(modifier = Modifier.height(6.dp))

        if (section.windows.isEmpty()) {
            SalahUnavailableNote(
                text = stringResource(section.unavailable?.messageRes() ?: R.string.home_prohibited_none),
            )
        } else {
            section.windows.forEachIndexed { index, window ->
                val active = window.phase == ProhibitedPeriodPhase.ACTIVE
                val tint = if (active) scheme.error else scheme.onSurface
                val subTint = if (active) scheme.error else scheme.onSurfaceVariant
                val left = window.countdown

                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(window.kind.labelRes()),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                            color = tint,
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                        )
                        Text(
                            text = stringResource(R.string.prohibited_window_range, window.startsAt, window.endsAt),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            color = tint,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    Text(
                        text = stringResource(window.phase.labelRes()),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        color = subTint,
                    )
                    if (left != null) {
                        Text(
                            text = stringResource(
                                if (active) R.string.prohibited_countdown_until_close else R.string.prohibited_countdown_until_open,
                                left,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = subTint,
                        )
                    }
                }
                if (index != section.windows.lastIndex) {
                    HorizontalDivider(color = scheme.outlineVariant)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.home_prohibited_note),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
    }
}
