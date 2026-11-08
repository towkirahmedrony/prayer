package io.github.towkirahmedrony.salah.feature.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.LocationPinGlyph
import io.github.towkirahmedrony.salah.core.designsystem.NightGlyph
import io.github.towkirahmedrony.salah.core.designsystem.SunGlyph
import io.github.towkirahmedrony.salah.core.designsystem.SunrisePrayerGlyph
import io.github.towkirahmedrony.salah.core.designsystem.SunsetGlyph
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahUnavailableNote
import io.github.towkirahmedrony.salah.core.domain.nafl.NaflKind
import io.github.towkirahmedrony.salah.core.domain.prohibited.ProhibitedPeriodPhase
import io.github.towkirahmedrony.salah.core.domain.prohibited.ProhibitedWindowKind
import io.github.towkirahmedrony.salah.core.model.SolarEvent
import io.github.towkirahmedrony.salah.feature.common.labelRes
import io.github.towkirahmedrony.salah.feature.common.messageRes
import io.github.towkirahmedrony.salah.feature.home.FastingEvent
import io.github.towkirahmedrony.salah.feature.home.HomeUiState
import io.github.towkirahmedrony.salah.feature.home.NaflRow
import io.github.towkirahmedrony.salah.feature.home.ProhibitedSection
import io.github.towkirahmedrony.salah.feature.home.PrayerRow

/**
 * The Prayer screen's header: the page name over the place and date the times belong to.
 *
 * Only the Gregorian date is repeated here. The Bangla and Hijri dates stay on the screens that are
 * about dates; this page is about the timetable, and the header leads with the times.
 */
@Composable
fun PrayerTimetableHeader(state: HomeUiState.Ready, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val weekday = state.weekday
    val gregorianDate = state.gregorianDate
    val dateLine = when {
        weekday != null && gregorianDate != null ->
            stringResource(R.string.home_weekday_and_date, weekday, gregorianDate)

        else -> weekday ?: gregorianDate
    }
    val location = state.location.label?.takeIf { it.isNotBlank() }
    val separator = stringResource(R.string.home_date_separator)
    val meta = listOfNotNull(location, dateLine).joinToString(separator)

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.prayer_page_title),
            style = MaterialTheme.typography.headlineMedium,
            color = scheme.onBackground,
        )
        if (meta.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (location != null) {
                    LocationPinGlyph(tint = scheme.primary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }
}

/**
 * The five obligatory prayers, directly under the page header.
 *
 * A row is a period, so its time is the range the period spans. The open period is tinted and marked
 * "Now" with its remaining time; the next period is marked "Up next". Everything else stays plain, so
 * the eye lands on the two rows that matter without a second summary above them.
 */
@Composable
fun PrayerTimesList(state: HomeUiState.Ready, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        state.timetableNote?.let { note ->
            SalahUnavailableNote(text = stringResource(note.messageRes()))
            Spacer(Modifier.height(6.dp))
        }
        state.prayerRows.forEach { row ->
            PrayerTimeRow(state = state, row = row)
        }
    }
}

@Composable
private fun PrayerTimeRow(state: HomeUiState.Ready, row: PrayerRow) {
    val scheme = MaterialTheme.colorScheme
    val current = row.isCurrent
    // A next prayer that has already passed belongs to the following day (today's Fajr after Isha),
    // so it is not marked on today's row.
    val next = row.isNext && !row.isPassed && !current
    val start = row.periodStartLabel ?: row.timeLabel
    val time = if (start != null && row.endTimeLabel != null) {
        stringResource(R.string.home_time_range, start, row.endTimeLabel)
    } else {
        start ?: stringResource(R.string.home_time_unavailable)
    }
    val countdown = if (current) {
        state.currentPrayer.countdownCompact?.let {
            stringResource(R.string.home_prayer_status_time_left, it)
        }
    } else {
        null
    }
    val adjustment = if (row.adjustmentMinutes != 0) {
        stringResource(R.string.home_adjusted_by, signedMinutes(row.adjustmentMinutes))
    } else {
        null
    }
    val nextDay = if (row.endsNextDay) stringResource(R.string.home_ends_next_day) else null
    val detail = listOfNotNull(countdown, adjustment, nextDay).joinToString(" · ")

    TimetableRow(
        name = stringResource(row.prayer.labelRes()),
        time = time,
        glyph = { tint -> PrayerRowGlyph(row.prayer, tint) },
        glyphTint = if (current || next) scheme.primary else scheme.onSurfaceVariant,
        nameColor = if (current) scheme.primary else scheme.onSurface,
        timeColor = if (current || next) scheme.primary else scheme.onSurface,
        containerColor = if (current) scheme.primary.copy(alpha = 0.12f) else null,
        statusText = when {
            current -> stringResource(R.string.home_prayer_status_now)
            next -> stringResource(R.string.home_prayer_status_up_next)
            else -> null
        },
        statusIsPill = current,
        statusColor = scheme.primary,
        statusContentColor = scheme.onPrimary,
        detail = detail.takeIf { it.isNotEmpty() },
        detailColor = if (current) scheme.primary else scheme.onSurfaceVariant,
    )
}

/** Duha, Awwabin and Tahajjud with their periods; the recommended last third is a plain line. */
@Composable
fun NaflTimetableSection(state: HomeUiState.Ready, modifier: Modifier = Modifier) {
    val nafl = state.nafl
    TimetableSection(title = stringResource(R.string.nafl_page_title), modifier = modifier) {
        if (nafl.unavailable != null) {
            SalahUnavailableNote(text = stringResource(nafl.unavailable.messageRes()))
        } else {
            nafl.rows.forEach { row -> NaflTimeRow(row = row) }
            nafl.lastThirdStartsLabel?.let { label ->
                Spacer(Modifier.height(6.dp))
                TimetableStatRow(
                    label = stringResource(R.string.home_night_last_third),
                    value = label,
                )
            }
        }
    }
}

@Composable
private fun NaflTimeRow(row: NaflRow) {
    val scheme = MaterialTheme.colorScheme
    TimetableRow(
        name = stringResource(row.kind.titleRes()),
        time = stringResource(R.string.home_time_range, row.startLabel, row.endLabel),
        glyph = { tint -> NaflGlyph(row.kind, tint) },
        glyphTint = if (row.isOpen) scheme.primary else scheme.onSurfaceVariant,
        nameColor = if (row.isOpen) scheme.primary else scheme.onSurface,
        timeColor = if (row.isOpen) scheme.primary else scheme.onSurface,
        containerColor = if (row.isOpen) scheme.primary.copy(alpha = 0.12f) else null,
        statusText = if (row.isOpen) stringResource(R.string.nafl_status_open) else null,
        statusIsPill = row.isOpen,
        statusColor = scheme.primary,
        statusContentColor = scheme.onPrimary,
        detail = row.preferredFromLabel?.let { stringResource(R.string.nafl_duha_preferred, it) },
        detailColor = scheme.onSurfaceVariant,
    )
}

/** The three prohibited intervals; the one open now is the only row that is tinted. */
@Composable
fun ProhibitedTimetableSection(section: ProhibitedSection, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    TimetableSection(title = stringResource(R.string.home_prohibited_title), modifier = modifier) {
        if (section.windows.isEmpty()) {
            SalahUnavailableNote(
                text = stringResource(
                    section.unavailable?.messageRes() ?: R.string.home_prohibited_none,
                ),
            )
        } else {
            section.windows.forEach { window ->
                val active = window.phase == ProhibitedPeriodPhase.ACTIVE
                val upcoming = window.phase == ProhibitedPeriodPhase.UPCOMING
                val countdown = window.countdown?.let { remaining ->
                    stringResource(
                        if (active) {
                            R.string.prohibited_countdown_until_close
                        } else {
                            R.string.prohibited_countdown_until_open
                        },
                        remaining,
                    )
                }
                TimetableRow(
                    name = stringResource(window.kind.labelRes()),
                    time = stringResource(
                        R.string.prohibited_window_range,
                        window.startsAt,
                        window.endsAt,
                    ),
                    glyph = { tint -> ProhibitedGlyph(window.kind, tint) },
                    glyphTint = if (active) scheme.error else scheme.onSurfaceVariant,
                    nameColor = if (active) scheme.onErrorContainer else scheme.onSurface,
                    timeColor = if (active) scheme.onErrorContainer else scheme.onSurface,
                    containerColor = if (active) scheme.errorContainer else null,
                    statusText = when {
                        active -> stringResource(R.string.prohibited_phase_active)
                        upcoming -> stringResource(R.string.prohibited_phase_upcoming)
                        else -> null
                    },
                    statusIsPill = active,
                    statusColor = if (active) scheme.error else scheme.primary,
                    statusContentColor = if (active) scheme.onError else scheme.onPrimary,
                    detail = if (active || upcoming) countdown else null,
                    detailColor = if (active) scheme.onErrorContainer else scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Sehri end and Iftar, with the next one highlighted and its countdown. */
@Composable
fun SawmTimetableSection(state: HomeUiState.Ready, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val fasting = state.fasting
    TimetableSection(title = stringResource(R.string.prayer_section_sawm), modifier = modifier) {
        val sehriActive = fasting.nextEvent == FastingEvent.SEHRI_END
        val iftarActive = fasting.nextEvent == FastingEvent.IFTAR
        val sehriLabel = if (fasting.isTomorrow) {
            R.string.home_hero_tomorrow_sehri_ends
        } else {
            R.string.calendar_sehri_end
        }
        val iftarLabel = if (fasting.isTomorrow) {
            R.string.home_hero_tomorrow_iftar
        } else {
            R.string.calendar_iftar
        }
        val countdown = fasting.countdown?.let {
            stringResource(R.string.home_hero_fasting_in, it)
        }

        TimetableRow(
            name = stringResource(sehriLabel),
            time = fasting.sehriEndLabel ?: stringResource(R.string.home_time_unavailable),
            glyph = { tint -> SunrisePrayerGlyph(tint, modifier = Modifier.size(22.dp)) },
            glyphTint = if (sehriActive) scheme.primary else scheme.onSurfaceVariant,
            nameColor = if (sehriActive) scheme.primary else scheme.onSurface,
            timeColor = if (sehriActive) scheme.primary else scheme.onSurface,
            containerColor = if (sehriActive) scheme.primary.copy(alpha = 0.12f) else null,
            detail = if (sehriActive) countdown else null,
            detailColor = scheme.primary,
        )
        TimetableRow(
            name = stringResource(iftarLabel),
            time = fasting.iftarLabel ?: stringResource(R.string.home_time_unavailable),
            glyph = { tint -> SunsetGlyph(tint, modifier = Modifier.size(22.dp)) },
            glyphTint = if (iftarActive) scheme.primary else scheme.onSurfaceVariant,
            nameColor = if (iftarActive) scheme.primary else scheme.onSurface,
            timeColor = if (iftarActive) scheme.primary else scheme.onSurface,
            containerColor = if (iftarActive) scheme.primary.copy(alpha = 0.12f) else null,
            detail = if (iftarActive) countdown else null,
            detailColor = scheme.primary,
        )
    }
}

/** Sunrise and sunset, then the measured solar geometry that used to sit in its own card. */
@Composable
fun SunTimetableSection(state: HomeUiState.Ready, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val solar = state.solarDay
    TimetableSection(title = stringResource(R.string.prayer_section_sun), modifier = modifier) {
        state.solarRows
            .filter { row -> row.event == SolarEvent.SUNRISE || row.event == SolarEvent.SUNSET }
            .forEach { row ->
                TimetableRow(
                    name = stringResource(row.event.labelRes()),
                    time = row.timeLabel ?: stringResource(R.string.home_time_unavailable),
                    glyph = { tint ->
                        if (row.event == SolarEvent.SUNRISE) {
                            SunrisePrayerGlyph(tint, modifier = Modifier.size(22.dp))
                        } else {
                            SunsetGlyph(tint, modifier = Modifier.size(22.dp))
                        }
                    },
                    glyphTint = scheme.onSurfaceVariant,
                )
            }

        if (solar.unavailable != null) {
            Spacer(Modifier.height(6.dp))
            SalahUnavailableNote(text = stringResource(solar.unavailable.messageRes()))
        } else {
            Spacer(Modifier.height(6.dp))
            TimetableStatRow(
                label = stringResource(R.string.home_solar_midnight),
                value = solar.solarMidnightLabel,
            )
            TimetableStatRow(
                label = stringResource(R.string.home_solar_day_length),
                value = solar.dayLengthLabel,
            )
            TimetableStatRow(
                label = stringResource(R.string.home_solar_night_length),
                value = solar.nightLengthLabel,
            )
        }
    }
}

/** The night interval, its phase and the boundaries derived from it. */
@Composable
fun NightTimetableSection(state: HomeUiState.Ready, modifier: Modifier = Modifier) {
    val night = state.night
    TimetableSection(title = stringResource(R.string.home_night_title), modifier = modifier) {
        if (night.unavailable != null) {
            SalahUnavailableNote(text = stringResource(night.unavailable.messageRes()))
        } else {
            PhaseLine(
                phase = night.phase,
                boundary = night.nextBoundary,
                countdown = night.countdown,
            )
            Spacer(Modifier.height(6.dp))
            TimetableStatRow(
                label = stringResource(R.string.home_night_duration),
                value = night.durationLabel,
            )
            TimetableStatRow(
                label = stringResource(R.string.home_night_islamic_midnight),
                value = night.islamicMidnightLabel,
            )
            TimetableStatRow(
                label = stringResource(R.string.home_night_last_third),
                value = night.lastThirdStartsLabel,
            )
        }
    }
}

@Composable
private fun TimetableSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = content,
        )
    }
}

/**
 * One timetable line: glyph, name (with an optional status), optional detail and the time at the end.
 *
 * The time is an unweighted child, so it is measured first and can never be squeezed onto two lines;
 * the name column takes what is left and may wrap for Bangla. A tinted [containerColor] is what marks
 * the open or next period, so no row needs a border or a divider.
 */
@Composable
private fun TimetableRow(
    name: String,
    time: String,
    glyph: @Composable (Color) -> Unit,
    modifier: Modifier = Modifier,
    glyphTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    nameColor: Color = MaterialTheme.colorScheme.onSurface,
    timeColor: Color = MaterialTheme.colorScheme.onSurface,
    containerColor: Color? = null,
    statusText: String? = null,
    statusIsPill: Boolean = false,
    statusColor: Color = MaterialTheme.colorScheme.primary,
    statusContentColor: Color = MaterialTheme.colorScheme.onPrimary,
    detail: String? = null,
    detailColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val shape = MaterialTheme.shapes.medium
    val horizontalPadding = if (containerColor != null) 12.dp else 0.dp

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (containerColor != null) Modifier.background(containerColor) else Modifier)
            .heightIn(min = 48.dp)
            .padding(
                start = horizontalPadding,
                end = horizontalPadding,
                top = 8.dp,
                bottom = 8.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            glyph(glyphTint)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = nameColor,
                    maxLines = 2,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (statusText != null) {
                    Spacer(Modifier.width(8.dp))
                    if (statusIsPill) {
                        StatusPill(
                            text = statusText,
                            containerColor = statusColor,
                            contentColor = statusContentColor,
                        )
                    } else {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = statusColor,
                            maxLines = 1,
                        )
                    }
                }
            }
            if (detail != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = detailColor,
                    maxLines = 2,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = time,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = timeColor,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun StatusPill(text: String, containerColor: Color, contentColor: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(containerColor)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = contentColor,
            maxLines = 1,
        )
    }
}

@Composable
private fun TimetableStatRow(label: String, value: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 2,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value ?: stringResource(R.string.home_time_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

@Composable
private fun NaflGlyph(kind: NaflKind, tint: Color) {
    when (kind) {
        NaflKind.DUHA -> SunGlyph(tint, modifier = Modifier.size(22.dp))
        NaflKind.AWWABIN -> SunsetGlyph(tint, modifier = Modifier.size(22.dp))
        NaflKind.TAHAJJUD -> NightGlyph(tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun ProhibitedGlyph(kind: ProhibitedWindowKind, tint: Color) {
    when (kind) {
        ProhibitedWindowKind.SUNRISE_ASCENT ->
            SunrisePrayerGlyph(tint, modifier = Modifier.size(22.dp))

        ProhibitedWindowKind.ZENITH_DECLINE ->
            SunGlyph(tint, modifier = Modifier.size(22.dp))

        ProhibitedWindowKind.SUNSET_SETTING ->
            SunsetGlyph(tint, modifier = Modifier.size(22.dp))
    }
}

/** Signed minute count, so an adjustment cannot be misread as an unsigned offset. */
private fun signedMinutes(minutes: Int): String =
    if (minutes > 0) "+$minutes" else minutes.toString()
