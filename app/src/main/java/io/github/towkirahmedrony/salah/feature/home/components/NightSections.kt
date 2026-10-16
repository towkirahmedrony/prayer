package io.github.towkirahmedrony.salah.feature.home.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSectionCard
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahUnavailableNote
import io.github.towkirahmedrony.salah.core.domain.night.NightBoundary
import io.github.towkirahmedrony.salah.core.domain.night.NightPhase
import io.github.towkirahmedrony.salah.feature.common.messageRes
import io.github.towkirahmedrony.salah.feature.home.HomeUiState

/**
 * The night interval card: Islamic midnight, the start of the last third, Tahajjud, the phase of the
 * moment being shown, and a countdown to the next boundary.
 *
 * Every value comes from `HomeUiState.Ready.night`, already resolved; this composable formats nothing
 * and decides nothing, so it cannot disagree with the timetable or recompute any astronomy. When the
 * interval cannot be built the card explains the gap instead of showing an invented boundary, and it
 * states that Tahajjud is voluntary and its start is a convention rather than presenting a ruling.
 */
@Composable
fun NightPeriodsCard(
    state: HomeUiState.Ready,
    modifier: Modifier = Modifier,
) {
    val night = state.night
    SalahSectionCard(modifier = modifier) {
        SectionTitle(text = stringResource(R.string.home_night_title))
        Spacer(modifier = Modifier.height(8.dp))

        if (night.unavailable != null) {
            SalahUnavailableNote(text = stringResource(night.unavailable.messageRes()))
        } else {
            PhaseLine(
                phase = night.phase,
                boundary = night.nextBoundary,
                countdown = night.countdown,
            )
            Spacer(modifier = Modifier.height(10.dp))
            StatRow(label = stringResource(R.string.home_night_duration), value = night.durationLabel)
            StatRow(label = stringResource(R.string.home_night_islamic_midnight), value = night.islamicMidnightLabel)
            StatRow(label = stringResource(R.string.home_night_last_third), value = night.lastThirdStartsLabel)
            StatRow(label = stringResource(R.string.home_night_tahajjud), value = night.tahajjudStartsLabel)
        }

        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.home_night_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The solar-geometry card: solar midnight and the measured day and night lengths.
 *
 * Solar midday is the transit, which is deliberately not labelled as clock noon or as the Dhuhr
 * prayer time; solar midnight is the anti-transit. The card keeps them separate from the Islamic
 * midnight on the night card, and says so.
 */
@Composable
fun SolarGeometryCard(
    state: HomeUiState.Ready,
    modifier: Modifier = Modifier,
) {
    val solar = state.solarDay
    SalahSectionCard(modifier = modifier) {
        SectionTitle(text = stringResource(R.string.home_solar_geometry_title))
        Spacer(modifier = Modifier.height(8.dp))

        if (solar.unavailable != null) {
            SalahUnavailableNote(text = stringResource(solar.unavailable.messageRes()))
        } else {
            StatRow(label = stringResource(R.string.home_solar_midnight), value = solar.solarMidnightLabel)
            StatRow(label = stringResource(R.string.home_solar_day_length), value = solar.dayLengthLabel)
            StatRow(label = stringResource(R.string.home_solar_night_length), value = solar.nightLengthLabel)
        }

        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.home_solar_geometry_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun PhaseLine(
    phase: NightPhase?,
    boundary: NightBoundary?,
    countdown: String?,
) {
    val scheme = MaterialTheme.colorScheme
    val phaseRes = when (phase) {
        NightPhase.EARLY_NIGHT -> R.string.home_night_phase_early
        NightPhase.LATE_NIGHT -> R.string.home_night_phase_late
        NightPhase.LAST_THIRD -> R.string.home_night_phase_last_third
        null -> null
    }
    val countdownRes = when (boundary) {
        NightBoundary.ISLAMIC_MIDNIGHT -> R.string.home_night_countdown_midnight
        NightBoundary.LAST_THIRD_STARTS -> R.string.home_night_countdown_last_third
        NightBoundary.FAJR -> R.string.home_night_countdown_fajr
        null -> null
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = phaseRes?.let { stringResource(it) } ?: stringResource(R.string.home_night_outside),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onSurface,
        )
        if (countdownRes != null && countdown != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(countdownRes, countdown),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.secondary,
            )
        }
    }
}

@Composable
private fun StatRow(label: String, value: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value ?: stringResource(R.string.home_time_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
