package io.github.towkirahmedrony.salah.feature.home.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.CalendarGlyph
import io.github.towkirahmedrony.salah.core.designsystem.BellGlyph
import io.github.towkirahmedrony.salah.core.designsystem.ChevronDownGlyph
import io.github.towkirahmedrony.salah.core.designsystem.ClockGlyph
import io.github.towkirahmedrony.salah.core.designsystem.LocationPinGlyph
import io.github.towkirahmedrony.salah.core.designsystem.NightGlyph
import io.github.towkirahmedrony.salah.core.designsystem.QiblaGlyph
import io.github.towkirahmedrony.salah.core.designsystem.RamadanGlyph
import io.github.towkirahmedrony.salah.core.designsystem.SunGlyph
import io.github.towkirahmedrony.salah.core.designsystem.SunrisePrayerGlyph
import io.github.towkirahmedrony.salah.core.designsystem.SunsetGlyph
import io.github.towkirahmedrony.salah.core.designsystem.TasbihGlyph
import io.github.towkirahmedrony.salah.core.designsystem.theme.GoldAccent
import io.github.towkirahmedrony.salah.core.designsystem.theme.LocalSalahAccents
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.SolarEvent
import io.github.towkirahmedrony.salah.feature.common.labelRes
import io.github.towkirahmedrony.salah.feature.common.messageRes
import io.github.towkirahmedrony.salah.feature.home.HeroArcSection
import io.github.towkirahmedrony.salah.feature.home.HomeUiState
import io.github.towkirahmedrony.salah.feature.home.PrayerRow
import io.github.towkirahmedrony.salah.feature.home.SolarRow

/**
 * The Home header: the saved place, the date line and the reminder bell.
 *
 * The place is the primary line — the reference leads with where the times are for, not with the
 * app's name — and it opens the location screen when tapped, so the one control a reader would reach
 * for to change place is the place itself. The bell opens the notification settings.
 *
 * The stored label is split on its comma so the deeper name (a locality or upazila) is emphasised and
 * the district reads as the secondary line beside it. That is a rendering of the same one label every
 * other screen shows, not a second name composed here: when the label has no comma there is no
 * secondary line, and the screen never invents one.
 */
@Composable
fun HomeHeader(
    state: HomeUiState.Ready,
    onOpenLocation: () -> Unit,
    onOpenNotifications: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val label = state.location.label
        ?: stringResource(state.location.unavailable?.messageRes() ?: R.string.home_location_none_title)
    val primary = label.substringBefore(',').trim()
    val secondary = label.substringAfter(',', missingDelimiterValue = "").trim().takeIf { it.isNotEmpty() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onOpenLocation)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LocationPinGlyph(tint = scheme.primary, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = primary,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp),
                fontWeight = FontWeight.Bold,
                color = scheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (secondary != null) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(2.dp))
            ChevronDownGlyph(tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(scheme.surface)
                .border(1.dp, scheme.outlineVariant, CircleShape)
                .clickable(onClick = onOpenNotifications),
            contentAlignment = Alignment.Center,
        ) {
            BellGlyph(tint = scheme.onSurface, modifier = Modifier.size(22.dp))
        }
    }
}

/**
 * The date line: the Gregorian date, the Hijri date and the Bangla date, joined by a separator.
 *
 * Only the values that actually resolved are shown, so a converter that is still producing its date
 * does not leave a dangling separator. The Hijri value is the one tinted, matching the reference, which
 * distinguishes the Hijri reading from the two civil ones without a second line.
 */
@Composable
fun HomeDateLine(state: HomeUiState.Ready, modifier: Modifier = Modifier) {
    val accents = LocalSalahAccents.current
    val gregorian = state.gregorianDate?.let { date ->
        state.weekday?.let { weekday ->
            stringResource(R.string.home_weekday_and_date, weekday, date)
        } ?: date
    }
    val hijri = state.hijriSection.value
    val bangla = state.banglaSection.value
    if (gregorian == null && hijri == null && bangla == null) return

    val separator = stringResource(R.string.home_date_separator)
    val line = buildAnnotatedString {
        var first = true
        fun join(block: () -> Unit) {
            if (!first) append(separator)
            first = false
            block()
        }
        gregorian?.let { value -> join { append(value) } }
        hijri?.let { value -> join { withStyle(SpanStyle(color = accents.goldText)) { append(value) } } }
        bangla?.let { value -> join { append(value) } }
    }

    Text(
        text = line,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * The hero card: the next prayer and how long is left, over the day's arc and its solar span.
 *
 * Its fill and everything drawn on it come from the theme's brand pairing, so the white type can never
 * end up on a fill a later theme change moved out from under it. The prayer named is the one that
 * opens the next period, and the countdown is the time until that boundary — read from the resolved
 * current period, so the hero and the timetable cannot disagree about what time it is.
 */
@Composable
fun CurrentPrayerHeroCard(
    state: HomeUiState.Ready,
    modifier: Modifier = Modifier,
) {
    val accents = LocalSalahAccents.current
    val onBrand = accents.onBrandSurface
    val hero = state.currentPrayer
    val next = state.nextPrayer
    val prayer = next.prayer ?: hero.prayer
    val prayerName = prayer?.let { stringResource(it.labelRes()) }
    val subtitleTime = next.time ?: hero.endTime
    val countdown = hero.countdownCompact ?: next.countdownCompact ?: hero.countdown ?: next.countdown
    val currentName = hero.prayer?.let { stringResource(it.labelRes()) }
    val caption = when {
        hero.isActive && currentName != null -> stringResource(R.string.home_hero_ends_then, currentName)
        prayerName != null -> stringResource(R.string.home_hero_time_until, prayerName)
        else -> null
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(accents.brandSurface),
    ) {
        HeroCornerArt(onBrand = onBrand, modifier = Modifier.matchParentSize())

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.home_hero_label_next_prayer),
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp),
                    fontWeight = FontWeight.SemiBold,
                    color = GoldAccent,
                )
                Text(
                    text = stringResource(R.string.home_hero_label_time_left),
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp),
                    fontWeight = FontWeight.SemiBold,
                    color = GoldAccent,
                )
            }
            Spacer(modifier = Modifier.height(10.dp))

            if (prayerName != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = prayerName,
                            style = MaterialTheme.typography.displaySmall.copy(
                                fontSize = 34.sp,
                                fontFamily = FontFamily.Serif,
                            ),
                            fontWeight = FontWeight.Bold,
                            color = onBrand,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (subtitleTime != null) {
                            Text(
                                text = stringResource(R.string.home_next_prayer_at, subtitleTime),
                                style = MaterialTheme.typography.bodyMedium,
                                color = onBrand.copy(alpha = 0.82f),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = countdown ?: stringResource(R.string.home_time_unavailable),
                            style = MaterialTheme.typography.displaySmall.copy(
                                fontSize = 34.sp,
                                fontFamily = FontFamily.Serif,
                            ),
                            fontWeight = FontWeight.Bold,
                            color = onBrand,
                        )
                        caption?.let { text ->
                            Text(
                                text = text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = onBrand.copy(alpha = 0.82f),
                                textAlign = TextAlign.End,
                            )
                        }
                    }
                }
            } else {
                Text(
                    text = stringResource(hero.unavailable?.messageRes() ?: R.string.home_next_prayer_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = onBrand.copy(alpha = 0.9f),
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            HomeArcChart(section = state.heroArc, onBrand = onBrand)
            Spacer(modifier = Modifier.height(14.dp))

            HorizontalDivider(color = onBrand.copy(alpha = 0.18f))
            Spacer(modifier = Modifier.height(12.dp))
            SolarSpanRow(state = state, onBrand = onBrand)
        }
    }
}

/** A faint pair of rings in the hero's top corner, echoing the reference's decorative line art. */
@Composable
private fun HeroCornerArt(onBrand: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val center = Offset(size.width * 0.86f, size.height * 0.10f)
        drawCircle(
            color = onBrand.copy(alpha = 0.09f),
            radius = size.width * 0.14f,
            center = center,
            style = Stroke(width = 1.5f),
        )
        drawCircle(
            color = onBrand.copy(alpha = 0.06f),
            radius = size.width * 0.22f,
            center = center,
            style = Stroke(width = 1.5f),
        )
    }
}

/**
 * The day's arc: the sun's path from Fajr to Isha, each prayer marked where it falls, and the displayed
 * moment marked on it.
 *
 * Every position is a fraction the state factory resolved from the schedule, so this draws geometry and
 * decides nothing. The next prayer is drawn as a ring and the current moment as a filled gold mark; a
 * passed prayer's dot is dimmed so the day reads at a glance. It renders nothing when there is no
 * schedule, rather than a chart with invented marks.
 */
@Composable
private fun HomeArcChart(
    section: HeroArcSection,
    onBrand: Color,
    modifier: Modifier = Modifier,
) {
    if (section.markers.isEmpty() && section.progress == null) return
    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(modifier = Modifier.fillMaxWidth().height(86.dp)) {
            val baseline = size.height * 0.88f
            val peak = size.height * 0.10f
            val start = Offset(0f, baseline)
            val control = Offset(size.width / 2f, peak)
            val end = Offset(size.width, baseline)

            fun pointAt(t: Float): Offset {
                val u = 1f - t
                return Offset(
                    u * u * start.x + 2f * u * t * control.x + t * t * end.x,
                    u * u * start.y + 2f * u * t * control.y + t * t * end.y,
                )
            }

            drawLine(
                color = onBrand.copy(alpha = 0.22f),
                start = start,
                end = end,
                strokeWidth = 1.5f,
            )
            // A cubic whose control points sit two-thirds of the way to the parabola's single control
            // point is exactly the quadratic through it, drawn with the long-stable `cubicTo`.
            val c1 = Offset(start.x + (control.x - start.x) * 2f / 3f, start.y + (control.y - start.y) * 2f / 3f)
            val c2 = Offset(end.x + (control.x - end.x) * 2f / 3f, end.y + (control.y - end.y) * 2f / 3f)
            val arc = Path().apply {
                moveTo(start.x, start.y)
                cubicTo(c1.x, c1.y, c2.x, c2.y, end.x, end.y)
            }
            drawPath(
                path = arc,
                color = GoldAccent.copy(alpha = 0.85f),
                style = Stroke(width = 2.5f, cap = StrokeCap.Round),
            )

            section.markers.forEach { marker ->
                val point = pointAt(marker.position.coerceIn(0f, 1f))
                if (marker.isNext) {
                    drawCircle(color = onBrand, radius = 7f, center = point, style = Stroke(width = 2f))
                    drawCircle(color = GoldAccent, radius = 3f, center = point)
                } else if (!marker.isCurrent) {
                    drawCircle(
                        color = GoldAccent.copy(alpha = if (marker.isPassed) 0.45f else 0.9f),
                        radius = 4f,
                        center = point,
                    )
                }
            }

            section.progress?.let { progress ->
                val point = pointAt(progress.coerceIn(0f, 1f))
                drawCircle(color = GoldAccent.copy(alpha = 0.30f), radius = 13f, center = point)
                drawCircle(color = GoldAccent, radius = 6.5f, center = point)
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ArcLabel(text = stringResource(R.string.prayer_fajr), tint = onBrand)
            ArcLabel(text = stringResource(R.string.prayer_dhuhr), tint = onBrand)
            ArcLabel(text = stringResource(R.string.prayer_isha), tint = onBrand)
        }
    }
}

@Composable
private fun ArcLabel(text: String, tint: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = tint.copy(alpha = 0.85f),
    )
}

/** Sunrise, the measured sunlight span and sunset, under the arc. */
@Composable
private fun SolarSpanRow(state: HomeUiState.Ready, onBrand: Color) {
    val sunrise = state.solarRows.firstOrNull { it.event == SolarEvent.SUNRISE }?.timeLabel
    val sunset = state.solarRows.firstOrNull { it.event == SolarEvent.SUNSET }?.timeLabel
    if (sunrise == null && sunset == null && state.daylight == null) return

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        SolarSpanStat(
            label = stringResource(R.string.event_sunrise),
            value = sunrise,
            alignment = Alignment.Start,
            onBrand = onBrand,
            modifier = Modifier.weight(1f),
        )
        SolarSpanStat(
            label = stringResource(R.string.home_solar_daylight),
            value = state.daylight,
            alignment = Alignment.CenterHorizontally,
            onBrand = onBrand,
            modifier = Modifier.weight(1f),
        )
        SolarSpanStat(
            label = stringResource(R.string.event_sunset),
            value = sunset,
            alignment = Alignment.End,
            onBrand = onBrand,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SolarSpanStat(
    label: String,
    value: String?,
    alignment: Alignment.Horizontal,
    onBrand: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = alignment) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = GoldAccent,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value ?: stringResource(R.string.home_time_unavailable),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = onBrand,
        )
    }
}

/**
 * The day's timetable as a vertical list: one row per prayer, the open one highlighted.
 *
 * The current marker comes from the state's own [PrayerRow.isCurrent] / [PrayerRow.isNext] / [isPassed]
 * flags rather than being re-selected here, so this list and the Prayer screen's timetable always agree
 * about which prayer is open.
 */
@Composable
fun TodayPrayerList(
    state: HomeUiState.Ready,
    onViewAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.calendar_today_label),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.home_action_full_timetable),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onViewAll)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 8.dp)) {
                state.prayerRows.forEach { row ->
                    PrayerListRow(row = row, currentCountdown = state.currentPrayer.countdownCompact)
                }
            }
        }
    }
}

@Composable
private fun PrayerListRow(row: PrayerRow, currentCountdown: String?) {
    val scheme = MaterialTheme.colorScheme
    val isCurrent = row.isCurrent
    val container = if (isCurrent) scheme.primaryContainer else Color.Transparent
    val labelColor = if (isCurrent) scheme.onPrimaryContainer else scheme.onSurface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(container)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (isCurrent) scheme.primary else scheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            val tint = if (isCurrent) scheme.onPrimary else scheme.onPrimaryContainer
            PrayerRowGlyph(prayer = row.prayer, tint = tint)
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(row.prayer.labelRes()),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = labelColor,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    isCurrent -> {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(scheme.primary)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.home_prayer_status_now),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = scheme.onPrimary,
                            )
                        }
                        if (currentCountdown != null) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.home_prayer_status_time_left, currentCountdown),
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onPrimaryContainer,
                            )
                        }
                    }
                    row.isNext -> Text(
                        text = stringResource(R.string.home_prayer_status_up_next),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.secondary,
                    )
                    row.isPassed -> Text(
                        text = stringResource(R.string.home_prayer_status_passed),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                    else -> Text(
                        text = stringResource(R.string.home_prayer_status_tonight),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = row.timeLabel ?: stringResource(R.string.home_time_unavailable),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = labelColor,
            )
            row.endTimeLabel?.let { end ->
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.home_time_to, end),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The per-prayer marks: sunrise for Fajr, a sun for Dhuhr, a clock for Asr, sunset for Maghrib, night for Isha. */
@Composable
private fun PrayerRowGlyph(prayer: PrayerName, tint: Color) {
    when (prayer) {
        PrayerName.FAJR -> SunrisePrayerGlyph(tint, modifier = Modifier.size(22.dp))
        PrayerName.DHUHR -> SunGlyph(tint, modifier = Modifier.size(22.dp))
        PrayerName.ASR -> ClockGlyph(tint, modifier = Modifier.size(22.dp))
        PrayerName.MAGHRIB -> SunsetGlyph(tint, modifier = Modifier.size(22.dp))
        PrayerName.ISHA -> NightGlyph(tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun ForbiddenTimesCard(
    state: HomeUiState.Ready,
    onViewDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accents = LocalSalahAccents.current
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.home_prohibited_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.home_action_view_details),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onViewDetails),
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                    if (state.prohibited.windows.isEmpty()) {
                        Text(
                            text = stringResource(state.prohibited.unavailable?.messageRes() ?: R.string.reason_unknown),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        state.prohibited.windows.take(3).forEach { window ->
                            Text(
                                text = stringResource(
                                    R.string.home_prohibited_window,
                                    stringResource(window.kind.labelRes()),
                                    window.startsAt,
                                    window.endsAt,
                                ),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Text(text = "☼", fontSize = 26.sp, color = accents.goldText)
            }
        }
    }
}

/**
 * Sunrise, solar noon and sunset for today, straight from the schedule's solar events.
 *
 * Kept as a standalone card for callers that want the three readings on their own; the composed Home
 * screen renders the same values inside the hero's footer instead.
 */
@Composable
fun SunTimesCard(
    rows: List<SolarRow>,
    modifier: Modifier = Modifier,
) {
    if (rows.none { it.timeLabel != null }) return
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            rows.forEach { row ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(row.event.labelRes()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = row.timeLabel ?: stringResource(R.string.home_time_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
        }
    }
}

@Composable
fun DailyToolsSection(
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.home_daily_tools_title),
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DailyToolItem(title = stringResource(R.string.shortcut_qibla), glyph = { tint -> QiblaGlyph(tint) }, onClick = { onNavigate("qibla") }, modifier = Modifier.weight(1f))
            DailyToolItem(title = stringResource(R.string.nav_calendar), glyph = { tint -> CalendarGlyph(tint) }, onClick = { onNavigate("calendar") }, modifier = Modifier.weight(1f))
            DailyToolItem(title = stringResource(R.string.nav_ramadan), glyph = { tint -> RamadanGlyph(tint) }, onClick = { onNavigate("ramadan") }, modifier = Modifier.weight(1f))
            DailyToolItem(title = stringResource(R.string.nav_tools), glyph = { tint -> TasbihGlyph(tint) }, onClick = { onNavigate("tools") }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun DailyToolItem(
    title: String,
    glyph: @Composable (Color) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(scheme.primaryContainer)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(scheme.surface.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            glyph(scheme.onPrimaryContainer)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onPrimaryContainer,
        )
    }
}
