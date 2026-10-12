package io.github.towkirahmedrony.salah.feature.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.CrescentGlyph
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSectionCard
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahUnavailableNote
import io.github.towkirahmedrony.salah.feature.common.labelRes
import io.github.towkirahmedrony.salah.feature.home.HomeUiState
import io.github.towkirahmedrony.salah.feature.home.NextPrayerSection
import io.github.towkirahmedrony.salah.feature.home.PrayerRow
import io.github.towkirahmedrony.salah.feature.home.ProhibitedSection
import io.github.towkirahmedrony.salah.feature.home.SolarRow

/**
 * Premium Home sections.
 *
 * Every value shown here comes from [HomeUiState]; a missing value renders the existing
 * unavailable placeholder or note, never an invented time.
 */

/** Compact header: location (opens settings), device date, and Hijri/Bangla dates when known. */
@Composable
fun HomeHeader(
    state: HomeUiState.Ready,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val place = state.location.label?.takeIf { state.location.unavailable == null }
        ?: stringResource(R.string.home_location_none_title)
    val weekday = state.weekday ?: stringResource(R.string.home_weekday_unknown)
    // The calendar is a progressive input: before it lands, this is a placeholder, never a
    // fabricated date. It is the smallest stand-in for the one value that is missing.
    val dateLine = state.gregorianDate ?: stringResource(R.string.home_date_pending)
    val otherDates = listOfNotNull(state.hijriSection.value, state.banglaSection.value)
        .joinToString(" \u00B7 ")

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.clickable(role = Role.Button, onClick = onOpenSettings),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = place,
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "\u25BE",
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = "$weekday, $dateLine",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
            if (otherDates.isNotEmpty()) {
                Text(
                    text = otherDates,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.secondary,
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        CrescentGlyph(tint = scheme.secondary)
    }
}

/** Hero card: the next prayer, its time and the live countdown. */
@Composable
fun NextPrayerHero(state: NextPrayerSection, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(scheme.surface)
            .border(1.dp, scheme.secondary.copy(alpha = 0.4f), shape)
            .padding(horizontal = 20.dp, vertical = 20.dp),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(100.dp))
                .background(scheme.secondaryContainer)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Text(
                text = stringResource(R.string.home_next_prayer_title),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSecondaryContainer,
            )
        }
        Spacer(modifier = Modifier.height(14.dp))

        val prayer = state.prayer
        if (prayer == null) {
            SalahUnavailableNote(text = stringResource(R.string.home_next_prayer_none))
        } else {
            Text(
                text = stringResource(prayer.labelRes()),
                style = MaterialTheme.typography.headlineSmall,
                color = scheme.onSurface,
            )
            state.countdown?.let { countdown ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = countdown,
                    style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
                    color = scheme.onSurface,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.home_next_prayer_at, state.time.orEmpty()),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.secondary,
            )
        }
    }
}

/** The five prayers as one row of chips; the next prayer is filled with the accent colour. */
@Composable
fun PrayerStrip(rows: List<PrayerRow>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        rows.forEach { row ->
            PrayerChip(row = row, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun PrayerChip(row: PrayerRow, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val container = if (row.isNext) scheme.secondary else scheme.surface
    val primaryText = if (row.isNext) scheme.onSecondary else scheme.onSurface
    val mutedText = if (row.isNext) scheme.onSecondary else scheme.onSurfaceVariant
    val borderModifier = if (row.isNext) {
        Modifier
    } else {
        Modifier.border(1.dp, scheme.outlineVariant, shape)
    }

    Column(
        modifier = modifier
            .clip(shape)
            .background(container)
            .then(borderModifier)
            .padding(horizontal = 2.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(row.prayer.labelRes()),
            style = MaterialTheme.typography.labelMedium,
            color = mutedText,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = row.timeLabel ?: stringResource(R.string.home_time_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = primaryText,
            fontWeight = if (row.isNext) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
        )
        // A manually corrected time is never shown as a raw calculation: the shift is disclosed.
        if (row.adjustmentMinutes != 0) {
            Text(
                text = signedMinuteLabel(row.adjustmentMinutes),
                style = MaterialTheme.typography.labelSmall,
                color = if (row.isNext) scheme.onSecondary else scheme.secondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun signedMinuteLabel(minutes: Int): String =
    if (minutes > 0) "+$minutes" else minutes.toString()

/** Sunrise, solar noon and sunset in one compact card, still labelled as solar events. */
@Composable
fun SolarStrip(rows: List<SolarRow>, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    SalahSectionCard(modifier = modifier) {
        Text(
            text = stringResource(R.string.home_solar_title),
            style = MaterialTheme.typography.titleSmall,
            color = scheme.onSurface,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.home_solar_note),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            rows.forEach { row ->
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(row.event.labelRes()),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = row.timeLabel ?: stringResource(R.string.home_time_unavailable),
                        style = MaterialTheme.typography.bodyLarge,
                        color = scheme.onSurface,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** Prohibited prayer times: the real windows, or the existing explanation when none exist. */
@Composable
fun ProhibitedPanel(state: ProhibitedSection, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    SalahSectionCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(scheme.error),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.home_prohibited_title),
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onSurface,
            )
        }
        Spacer(modifier = Modifier.height(10.dp))

        if (state.windows.isEmpty()) {
            SalahUnavailableNote(text = stringResource(R.string.home_prohibited_none))
        } else {
            state.windows.forEachIndexed { index, window ->
                if (index > 0) {
                    HorizontalDivider(color = scheme.outlineVariant)
                }
                Text(
                    text = window,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 9.dp),
                )
            }
        }
    }
}
