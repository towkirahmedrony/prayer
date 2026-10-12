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
import io.github.towkirahmedrony.salah.core.domain.prohibited.ProhibitedPeriodPhase
import io.github.towkirahmedrony.salah.feature.common.labelRes
import io.github.towkirahmedrony.salah.feature.common.messageRes
import io.github.towkirahmedrony.salah.feature.home.CurrentPrayerSection
import io.github.towkirahmedrony.salah.feature.home.HomeUiState
import io.github.towkirahmedrony.salah.feature.home.NextPrayerSection
import io.github.towkirahmedrony.salah.feature.home.PrayerRow
import io.github.towkirahmedrony.salah.feature.home.ProhibitedSection
import io.github.towkirahmedrony.salah.feature.home.ProhibitedWindowRow
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

/**
 * Hero card: the prayer period that is open right now, and how long is left in it.
 *
 * This is the primary prayer reading on Home. It answers a different question from the next-prayer
 * card below it — "which period am I in, and when does it close?" rather than "what is coming?" —
 * so both are shown, with the current period first.
 *
 * The countdown is labelled as the time until the next prayer *time*. It is a reading of the
 * timetable, not a ruling on how long the prayer itself may be performed, and the wording here must
 * not be changed to suggest otherwise.
 */
@Composable
fun CurrentPrayerHero(state: CurrentPrayerSection, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(scheme.surface)
            .border(1.dp, scheme.primary.copy(alpha = 0.45f), shape)
            .padding(horizontal = 20.dp, vertical = 20.dp),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(100.dp))
                .background(scheme.primaryContainer)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Text(
                text = stringResource(R.string.home_current_prayer_title),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onPrimaryContainer,
            )
        }
        Spacer(modifier = Modifier.height(14.dp))

        val prayer = state.prayer
        when {
            // A period is open: the prayer, its start and the boundary that closes it.
            state.isActive && prayer != null -> {
                Text(
                    text = stringResource(prayer.labelRes()),
                    style = MaterialTheme.typography.headlineSmall,
                    color = scheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.home_current_prayer_active),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.primary,
                )
                state.countdown?.let { countdown ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = countdown,
                        style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
                        color = scheme.onSurface,
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                // A null start means the period began before today's timetable. Saying so is
                // honest; showing the previous day's time here would not be.
                Text(
                    text = state.startTime
                        ?.let { start -> stringResource(R.string.home_current_prayer_started, start) }
                        ?: stringResource(R.string.home_current_prayer_started_earlier),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.secondary,
                )
                boundaryLine(state = state)?.let { line ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.secondary,
                    )
                }
            }

            // Nothing can be resolved: the card explains the gap rather than showing a period it
            // cannot derive.
            state.unavailable != null -> {
                SalahUnavailableNote(
                    text = stringResource(state.unavailable.messageRes()),
                )
            }

            // Between two periods: the mid-morning, between sunrise and Dhuhr. This is a reading of
            // the timetable, not an error, so it is stated rather than excused.
            else -> {
                Text(
                    text = stringResource(R.string.home_current_prayer_between),
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface,
                )
                state.countdown?.let { countdown ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = countdown,
                        style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
                        color = scheme.onSurface,
                    )
                }
                boundaryLine(state = state)?.let { line ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.secondary,
                    )
                }
            }
        }
    }
}

/**
 * The sentence naming what closes the open period.
 *
 * A prayer boundary and a solar event read differently on purpose: Fajr's period closes at sunrise,
 * which is not one of the five prayers, and the label has to say so.
 */
@Composable
private fun boundaryLine(state: CurrentPrayerSection): String? {
    val endTime = state.endTime ?: return null
    val endingPrayer = state.endsWithPrayer
    val endingEvent = state.endsWithSolarEvent
    return when {
        endingPrayer != null && state.endsNextDay -> stringResource(
            R.string.home_current_prayer_until_prayer_next_day,
            stringResource(endingPrayer.labelRes()),
            endTime,
        )

        endingPrayer != null -> stringResource(
            R.string.home_current_prayer_until_prayer,
            stringResource(endingPrayer.labelRes()),
            endTime,
        )

        endingEvent != null -> stringResource(
            R.string.home_current_prayer_until_event,
            stringResource(endingEvent.labelRes()),
            endTime,
        )

        else -> null
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

/**
 * Prohibited prayer times: one row per configured interval, or an explanation when none can be
 * calculated.
 *
 * Each row names the interval, the solar event it is anchored to, its start and end, whether it is
 * active, upcoming or already past today, and — while it is upcoming or active — how long is left
 * before it opens or closes. The convention behind the boundaries is stated once, underneath,
 * because those minutes differ between scholarly conventions and Salah applies its own documented
 * policy rather than a universally agreed figure.
 */
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
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.home_prohibited_note),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )

        if (state.windows.isEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            SalahUnavailableNote(text = stringResource(R.string.home_prohibited_none))
        } else {
            state.windows.forEach { window ->
                HorizontalDivider(color = scheme.outlineVariant)
                ProhibitedWindow(window = window)
            }

            state.convention?.let { convention ->
                HorizontalDivider(color = scheme.outlineVariant)
                Text(
                    text = stringResource(R.string.prohibited_convention_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurface,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    // Which convention is in force is stated from the rule set itself, so the
                    // wording cannot drift away from the boundaries actually applied.
                    text = stringResource(
                        if (convention.isReviewed) {
                            R.string.prohibited_convention_reviewed
                        } else {
                            R.string.prohibited_convention_not_reviewed
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ProhibitedWindow(window: ProhibitedWindowRow) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(window.kind.labelRes()),
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurface,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            PhasePill(
                text = stringResource(window.phase.labelRes()),
                active = window.phase == ProhibitedPeriodPhase.ACTIVE,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(
                R.string.prohibited_window_range,
                window.startsAt,
                window.endsAt,
            ),
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            color = scheme.onSurface,
        )
        // The anchor event is shown separately from the interval: for the zenith interval these are
        // different instants, and the solar transit is not the displayed Dhuhr prayer time.
        window.eventTime?.let { eventTime ->
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(
                    R.string.prohibited_event_at,
                    stringResource(window.event.labelRes()),
                    eventTime,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        window.countdown?.let { countdown ->
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = when (window.phase) {
                    ProhibitedPeriodPhase.UPCOMING ->
                        stringResource(R.string.prohibited_countdown_until_open, countdown)

                    ProhibitedPeriodPhase.ACTIVE ->
                        stringResource(R.string.prohibited_countdown_until_close, countdown)

                    ProhibitedPeriodPhase.PASSED -> ""
                },
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                color = if (window.phase == ProhibitedPeriodPhase.ACTIVE) scheme.error else scheme.onSurface,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        // The configured boundaries, read straight off the rule set that produced the interval.
        Text(
            text = window.explanation,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
    }
}

/** Small status pill: filled while an interval is active, outlined otherwise. */
@Composable
private fun PhasePill(text: String, active: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(100.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (active) scheme.error else scheme.surfaceVariant)
            .then(if (active) Modifier else Modifier.border(1.dp, scheme.outlineVariant, shape))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) scheme.onError else scheme.onSurfaceVariant,
        )
    }
}
