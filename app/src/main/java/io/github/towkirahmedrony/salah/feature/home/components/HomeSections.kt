package io.github.towkirahmedrony.salah.feature.home.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.CalendarGlyph
import io.github.towkirahmedrony.salah.core.designsystem.CrescentGlyph
import io.github.towkirahmedrony.salah.core.designsystem.GearGlyph
import io.github.towkirahmedrony.salah.core.designsystem.LanternGlyph
import io.github.towkirahmedrony.salah.core.designsystem.QiblaGlyph
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSectionCard
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahShortcutTile
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahStatusPill
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahUnavailableNote
import io.github.towkirahmedrony.salah.core.designsystem.theme.SalahTheme
import io.github.towkirahmedrony.salah.core.model.LocationOrigin
import io.github.towkirahmedrony.salah.core.model.PrayerTimeSource
import io.github.towkirahmedrony.salah.core.model.ThemeMode
import io.github.towkirahmedrony.salah.feature.common.labelRes
import io.github.towkirahmedrony.salah.feature.common.messageRes
import io.github.towkirahmedrony.salah.feature.common.sectionTitleRes
import io.github.towkirahmedrony.salah.feature.home.CalendarSection
import io.github.towkirahmedrony.salah.feature.home.HomeUiState
import io.github.towkirahmedrony.salah.feature.home.LocationSection
import io.github.towkirahmedrony.salah.feature.home.NextPrayerSection
import io.github.towkirahmedrony.salah.feature.home.ProhibitedSection
import io.github.towkirahmedrony.salah.feature.home.ProvenanceSection

/** Salah branding: name, tagline and the Arabic prayer-times label. */
@Composable
fun BrandHeader(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(
                Brush.verticalGradient(
                    colors = listOf(scheme.primary, scheme.primary.copy(alpha = 0.86f)),
                ),
            )
            .padding(20.dp),
    ) {
        Column {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium,
                        color = scheme.onPrimary,
                    )
                    Text(
                        text = stringResource(R.string.app_tagline),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onPrimary.copy(alpha = 0.85f),
                    )
                }
                CrescentGlyph(tint = scheme.onPrimary.copy(alpha = 0.9f))
            }
        }
    }
}

/** Shows a provider failure instead of hiding it, with a retry action. */
@Composable
fun FailureBanner(
    detail: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = scheme.errorContainer),
        border = BorderStroke(1.dp, scheme.error),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.state_error_title),
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onErrorContainer,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onErrorContainer,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onRetry) {
                Text(text = stringResource(R.string.action_retry))
            }
        }
    }
}

/**
 * Date header: the real device date, plus the Bangla and Hijri sections.
 *
 * The Bangla and Hijri rows render the reason they have no value, never an invented date.
 */
@Composable
fun CalendarCard(state: HomeUiState.Ready, modifier: Modifier = Modifier) {
    SalahSectionCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.weekday ?: stringResource(R.string.home_weekday_unknown),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            SalahStatusPill(
                text = stringResource(R.string.calendar_source_computed),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = state.gregorianDate,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(14.dp))
        CalendarRow(state.gregorianSection)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        CalendarRow(state.banglaSection)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        CalendarRow(state.hijriSection)
    }
}

@Composable
private fun CalendarRow(section: CalendarSection) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = stringResource(section.system.sectionTitleRes()),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(2.dp))
        if (section.value != null) {
            Text(
                text = section.value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            Text(
                text = stringResource(section.unavailable?.messageRes() ?: R.string.reason_unknown),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Location card, including the explicit "no location" state with a settings action. */
@Composable
fun LocationCard(location: LocationSection, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    SalahSectionCard(modifier = modifier) {
        Text(
            text = stringResource(R.string.home_location_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(10.dp))

        if (location.unavailable != null) {
            Text(
                text = stringResource(R.string.home_location_none_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.home_location_none_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onOpenSettings) {
                Text(text = stringResource(R.string.action_open_settings))
            }
        } else {
            Text(
                text = location.label.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.home_location_coordinates,
                    location.latitude ?: 0.0,
                    location.longitude ?: 0.0,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            location.timeZoneId?.let { zone ->
                Text(
                    text = stringResource(R.string.home_location_timezone, zone),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (location.origin == LocationOrigin.MANUAL_ENTRY) {
                Spacer(modifier = Modifier.height(10.dp))
                SalahStatusPill(
                    text = stringResource(R.string.home_location_manual),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/**
 * Next prayer card.
 *
 * The countdown is rendered only when a real next prayer was resolved; otherwise the card
 * explains that no countdown is shown.
 */
@Composable
fun NextPrayerCard(state: NextPrayerSection, modifier: Modifier = Modifier) {
    SalahSectionCard(modifier = modifier) {
        Text(
            text = stringResource(R.string.home_next_prayer_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(10.dp))

        val prayer = state.prayer
        if (prayer != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CrescentGlyph(tint = MaterialTheme.colorScheme.primary)
                Column {
                    Text(
                        text = stringResource(prayer.labelRes()),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.home_next_prayer_at, state.time.orEmpty()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            state.countdown?.let { countdown ->
                Text(
                    text = stringResource(R.string.home_next_prayer_remaining, countdown),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        } else {
            SalahUnavailableNote(text = stringResource(R.string.home_next_prayer_none))
        }
    }
}

/** Daily timetable, with a highlighted next prayer and an honest explanation when empty. */
@Composable
fun TimetableCard(state: HomeUiState.Ready, modifier: Modifier = Modifier) {
    SalahSectionCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.home_prayer_timetable_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.home_prayer_times_arabic),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                textDirection = TextDirection.Rtl,
                textAlign = TextAlign.End,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        state.timetableNote?.let { note ->
            SalahUnavailableNote(text = stringResource(note.messageRes()))
            Spacer(modifier = Modifier.height(8.dp))
        }

        state.prayerRows.forEachIndexed { index, row ->
            TimeRow(
                label = stringResource(row.prayer.labelRes()),
                time = row.timeLabel ?: stringResource(R.string.home_time_unavailable),
                highlighted = row.isNext,
            )
            if (index != state.prayerRows.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/** Sunrise, solar noon and sunset, explicitly presented as solar events. */
@Composable
fun SolarEventsCard(state: HomeUiState.Ready, modifier: Modifier = Modifier) {
    SalahSectionCard(modifier = modifier) {
        Text(
            text = stringResource(R.string.home_solar_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.home_solar_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))

        state.solarRows.forEachIndexed { index, row ->
            TimeRow(
                label = stringResource(row.event.labelRes()),
                time = row.timeLabel ?: stringResource(R.string.home_time_unavailable),
                highlighted = false,
            )
            if (index != state.solarRows.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/** Prohibited prayer times: explanation first, since no verified rules exist yet. */
@Composable
fun ProhibitedTimesCard(state: ProhibitedSection, modifier: Modifier = Modifier) {
    SalahSectionCard(modifier = modifier) {
        Text(
            text = stringResource(R.string.home_prohibited_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (state.windows.isEmpty()) {
            SalahUnavailableNote(text = stringResource(R.string.home_prohibited_none))
        } else {
            state.windows.forEach { window ->
                Text(
                    text = window,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** Shortcuts to the planned sections of Salah. */
@Composable
fun ShortcutsCard(onNavigate: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.home_shortcuts_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SalahShortcutTile(
                title = stringResource(R.string.nav_ramadan),
                subtitle = stringResource(R.string.shortcut_ramadan_subtitle),
                glyph = { LanternGlyph(tint = it) },
                onClick = { onNavigate("ramadan") },
                modifier = Modifier.weight(1f),
            )
            SalahShortcutTile(
                title = stringResource(R.string.nav_calendar),
                subtitle = stringResource(R.string.shortcut_calendar_subtitle),
                glyph = { CalendarGlyph(tint = it) },
                onClick = { onNavigate("calendar") },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SalahShortcutTile(
                title = stringResource(R.string.shortcut_qibla),
                subtitle = stringResource(R.string.shortcut_qibla_subtitle),
                glyph = { QiblaGlyph(tint = it) },
                onClick = { onNavigate("qibla") },
                modifier = Modifier.weight(1f),
            )
            SalahShortcutTile(
                title = stringResource(R.string.nav_settings),
                subtitle = stringResource(R.string.shortcut_settings_subtitle),
                glyph = { GearGlyph(tint = it) },
                onClick = { onNavigate("settings") },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Provenance card: where the shown data came from and whether it works offline. */
@Composable
fun ProvenanceCard(state: ProvenanceSection, modifier: Modifier = Modifier) {
    SalahSectionCard(modifier = modifier) {
        Text(
            text = stringResource(R.string.status_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(10.dp))

        SalahStatusPill(
            text = stringResource(R.string.status_offline_ready),
            containerColor = if (state.offlineReady) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (state.offlineReady) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = stringResource(R.string.status_source_label, sourceLabel(state)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        state.providerId?.let { provider ->
            Text(
                text = provider,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.observedAtLabel?.let { observed ->
            Text(
                text = stringResource(R.string.status_observed_at, observed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun sourceLabel(state: ProvenanceSection): String = when (state.source) {
    PrayerTimeSource.UNCONFIGURED ->
        stringResource(R.string.status_source_unconfigured)

    PrayerTimeSource.LOCAL_CALCULATION ->
        stringResource(R.string.status_source_local_calculation)

    PrayerTimeSource.REMOTE_API ->
        stringResource(R.string.status_source_remote_api, state.providerId ?: "—")

    PrayerTimeSource.USER_ENTERED_TIMETABLE ->
        stringResource(R.string.status_source_user_timetable, state.providerId ?: "—")
}

@Composable
private fun TimeRow(label: String, time: String, highlighted: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = if (highlighted) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            color = if (highlighted) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Text(
            text = time,
            style = if (highlighted) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            color = if (highlighted) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFBF8F1)
@Composable
private fun BrandHeaderPreview() {
    SalahTheme(themeMode = ThemeMode.LIGHT) {
        BrandHeader(modifier = Modifier.padding(16.dp))
    }
}
