package io.github.towkirahmedrony.salah.feature.home.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import io.github.towkirahmedrony.salah.feature.common.messageRes
import io.github.towkirahmedrony.salah.feature.home.QiblaSection
import io.github.towkirahmedrony.salah.feature.home.RamadanSection
import java.util.Locale

/**
 * The Ramadan card.
 *
 * Context-aware rather than always-on: the screen only renders it when [RamadanSection.visible] is
 * true, which is only while today is inside the estimated window or within two months of it. The
 * Sehri and Iftar times are the calculated Fajr and Maghrib from today's timetable — the same values
 * the Ramadan screen shows — so the card can never disagree with the timetable or claim a fast Salah
 * did not calculate. The estimate wording is the same note the Ramadan screen uses.
 */
@Composable
fun RamadanHomeCard(
    section: RamadanSection,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SalahSectionCard(modifier = modifier) {
        CardHeader(titleRes = R.string.home_card_ramadan_title, actionRes = R.string.home_ramadan_open, onAction = onOpen)
        Spacer(modifier = Modifier.height(8.dp))

        if (section.inRamadan) {
            val day = section.dayNumber
            val total = section.totalDays
            if (day != null && total != null) {
                Text(
                    text = stringResource(R.string.home_ramadan_day, day, total),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            section.sehriEndLabel?.let { sehri ->
                Text(
                    text = stringResource(R.string.home_ramadan_sehri, sehri),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            section.iftarLabel?.let { iftar ->
                Text(
                    text = stringResource(R.string.home_ramadan_iftar, iftar),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        } else {
            section.daysUntilStart?.let { days ->
                Text(
                    text = stringResource(R.string.home_ramadan_starts_in, days),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.ramadan_estimate_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The Qibla card.
 *
 * The bearing is pure geography from the saved coordinates and needs no permission, sensor or
 * network, so the card states it whenever a location exists. When there is no usable location the
 * card explains the gap instead of inventing a direction; tapping it opens the Qibla compass, which
 * owns the live device heading.
 */
@Composable
fun QiblaHomeCard(
    section: QiblaSection,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SalahSectionCard(modifier = modifier, onClick = onOpen) {
        CardHeader(titleRes = R.string.home_card_qibla_title, actionRes = R.string.home_qibla_open, onAction = null)
        Spacer(modifier = Modifier.height(8.dp))

        val bearing = section.bearingDegrees
        if (bearing != null) {
            Text(
                text = stringResource(R.string.home_qibla_bearing, formatNumber(bearing)),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            section.distanceKm?.let { distance ->
                Text(
                    text = stringResource(R.string.home_qibla_distance, formatNumber(distance)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            SalahUnavailableNote(
                text = stringResource(section.unavailable?.messageRes() ?: R.string.reason_unknown),
            )
        }
    }
}

/** A card's title and an optional trailing action, both localised. */
@Composable
private fun CardHeader(
    titleRes: Int,
    actionRes: Int,
    onAction: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        val action = stringResource(actionRes)
        Text(
            text = action,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = if (onAction != null) {
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            } else {
                Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            },
        )
    }
}

/** One decimal place, in the current locale. The degree glyph is supplied by the string resource. */
private fun formatNumber(value: Double): String =
    String.format(Locale.getDefault(), "%.1f", value)
