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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
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
import io.github.towkirahmedrony.salah.core.designsystem.SlidersGlyph
import io.github.towkirahmedrony.salah.core.designsystem.SunGlyph
import io.github.towkirahmedrony.salah.core.designsystem.SunrisePrayerGlyph
import io.github.towkirahmedrony.salah.core.designsystem.SunsetGlyph
import io.github.towkirahmedrony.salah.core.designsystem.TasbihGlyph
import io.github.towkirahmedrony.salah.core.designsystem.theme.GoldAccent
import io.github.towkirahmedrony.salah.core.designsystem.theme.LocalSalahAccents
import io.github.towkirahmedrony.salah.core.domain.prohibited.ProhibitedPeriodPhase
import io.github.towkirahmedrony.salah.core.model.CalendarHeadline
import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.SolarEvent
import io.github.towkirahmedrony.salah.feature.common.labelRes
import io.github.towkirahmedrony.salah.feature.common.messageRes
import io.github.towkirahmedrony.salah.feature.home.FastingEvent
import io.github.towkirahmedrony.salah.feature.home.FastingTimesSection
import io.github.towkirahmedrony.salah.feature.home.HeroArcSection
import io.github.towkirahmedrony.salah.feature.home.HeroNaflKind
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
    onCustomize: () -> Unit,
    isEditing: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    // Resolved here rather than inside the `semantics` block, which is not composable.
    val customizeLabel = stringResource(R.string.home_dashboard_customize)
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
        // The customize control is the discoverable way into the dashboard editor. It is the place
        // the reader already reaches for a Home-level action, beside the reminder bell, and it
        // states which mode it is in so the screen never looks the same in both.
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (isEditing) scheme.primaryContainer else scheme.surface)
                .border(1.dp, scheme.outlineVariant, CircleShape)
                .clickable(onClick = onCustomize)
                .semantics { contentDescription = customizeLabel },
            contentAlignment = Alignment.Center,
        ) {
            SlidersGlyph(
                tint = if (isEditing) scheme.onPrimaryContainer else scheme.onSurface,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
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

/** The fill of the hero ring's progress arc: a light mint that reads on the brand surface in both themes. */
private val HeroRingMint = Color(0xFF7FDDB6)

/** The prohibited-time red: light enough to read on the brand surface in both themes. */
private val ProhibitedRed = Color(0xFFFF8A80)

/**
 * The hero card: today's three dates over the open prayer, drawn as a ring, beside the next Sehri end
 * and Iftar.
 *
 * Three rows of content, all read from [HomeUiState.Ready] and none calculated here:
 *  - **The dates**: the Gregorian, Bangla and Hijri dates as three columns, each a large day-and-month
 *    over a small gold line. A calendar that has not resolved is simply absent, and with none resolved
 *    the row and its divider are left out.
 *  - **The ring**: the prayer whose period is open, the time left in it as a fixed-width clock and when
 *    it ends. The arc is how far through the period the moment is, measured by the state factory, and
 *    is left empty when the factory had nothing honest to report. Between sunrise and Dhuhr no period
 *    is open, so the ring names the prayer that opens next, says so ("up next") and counts down to it.
 *  - **Sehri and Iftar**: the next fasting pair, with the next event highlighted and counting down. See
 *    [FastingPanel].
 *
 * The countdown is the time to the next boundary in the timetable, not a statement of how long the
 * prayer may be performed. Its fill and everything drawn on it come from the theme's brand pairing, so
 * the white type can never end up on a fill a later theme change moved out from under it.
 *
 * @param onOpenReminders opens the notification settings so the reader can set a Sehri or Iftar
 *   reminder. When `null` the two fasting blocks are plain text and carry no bell, which is how the
 *   Prayer screen shows this card.
 */
@Composable
fun CurrentPrayerHeroCard(
    state: HomeUiState.Ready,
    modifier: Modifier = Modifier,
    onOpenReminders: (() -> Unit)? = null,
) {
    val accents = LocalSalahAccents.current
    val onBrand = accents.onBrandSurface
    val shape = RoundedCornerShape(28.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(accents.brandSurface)
            .border(1.dp, GoldAccent.copy(alpha = 0.28f), shape),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
            if (state.heroDates.isNotEmpty()) {
                HeroDateRow(dates = state.heroDates, onBrand = onBrand)
                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = onBrand.copy(alpha = 0.18f))
                Spacer(modifier = Modifier.height(14.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PrayerRing(state = state, onBrand = onBrand, modifier = Modifier.weight(1f))
                // A fixed height rather than an intrinsic one: the ring sizes itself from its width, and
                // an intrinsic measurement would ask it for a height before it has a width to use.
                VerticalDivider(
                    modifier = Modifier.padding(horizontal = 12.dp).height(112.dp),
                    color = onBrand.copy(alpha = 0.18f),
                )
                FastingPanel(
                    section = state.fasting,
                    onBrand = onBrand,
                    onOpenReminders = onOpenReminders,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * The hero's date columns, one per calendar that resolved, separated by thin vertical rules.
 *
 * Each column is the date's large part over a small gold line: the Gregorian line is the weekday and the
 * year, the Bangla and Hijri lines name their calendar and the year. The labels are string resources;
 * every date part arrives already written in the script and numerals the date language chose.
 */
@Composable
private fun HeroDateRow(
    dates: List<CalendarHeadline>,
    onBrand: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        dates.forEachIndexed { index, date ->
            if (index > 0) {
                VerticalDivider(
                    modifier = Modifier.fillMaxHeight(),
                    color = onBrand.copy(alpha = 0.18f),
                )
            }
            val detail = when (date.system) {
                CalendarSystem.GREGORIAN -> date.weekday?.let { weekday ->
                    stringResource(R.string.home_hero_date_weekday_year, weekday, date.year)
                } ?: date.year
                CalendarSystem.BANGLA -> stringResource(R.string.home_hero_date_bangla_year, date.year)
                CalendarSystem.HIJRI -> stringResource(R.string.home_hero_date_hijri_year, date.year)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (index == 0) 4.dp else 12.dp, end = 4.dp),
            ) {
                Text(
                    text = date.primary,
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
                    fontWeight = FontWeight.Bold,
                    color = onBrand,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = GoldAccent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The open prayer as a ring: the name, the clock counting down and when the period ends, inside an arc
 * that fills as the period elapses.
 *
 * With no open period (between sunrise and Dhuhr) the ring is the "up next" reading instead: a small
 * status line, the prayer that opens next, the clock to it and when it begins — and the arc is empty,
 * because nothing has started. With no schedule at all the ring explains the gap in words rather than
 * showing a clock that does not exist.
 */
@Composable
private fun PrayerRing(
    state: HomeUiState.Ready,
    onBrand: Color,
    modifier: Modifier = Modifier,
) {
    val hero = state.currentPrayer
    val reading = ringReadingOf(state)
    val progress = reading.progress
    val arcColor = reading.arcColor
    val name = reading.name

    Box(
        modifier = modifier.aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val strokeWidth = 8.dp.toPx()
            val inset = strokeWidth / 2f
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = onBrand.copy(alpha = 0.16f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth),
            )
            if (progress != null) {
                drawArc(
                    color = arcColor,
                    startAngle = -90f,
                    sweepAngle = 360f * progress.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
            }
        }

        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (name != null) {
                reading.tag?.let { tag ->
                    Text(
                        text = tag,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = reading.tagColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = name,
                    style = if (reading.compactName) {
                        MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp)
                    } else {
                        MaterialTheme.typography.titleLarge
                    },
                    fontWeight = FontWeight.Bold,
                    color = onBrand,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = reading.clock ?: stringResource(R.string.home_time_unavailable),
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp),
                    fontWeight = FontWeight.Bold,
                    color = onBrand,
                    maxLines = 1,
                )
                reading.subtitle?.let { subtitle ->
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = onBrand.copy(alpha = 0.82f),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // A prohibited window that overlaps the open fard period (Asr through the sunset window)
                // is a red line beneath it: the prayer is still the open one, and the prohibition is
                // what the reader must not miss.
                reading.warning?.let { warning ->
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 13.sp),
                        fontWeight = FontWeight.SemiBold,
                        color = ProhibitedRed,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text(
                    text = stringResource(hero.unavailable?.messageRes() ?: R.string.home_next_prayer_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = onBrand.copy(alpha = 0.9f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** What the ring says, resolved once so the drawing only places it. */
private data class RingReading(
    val tag: String?,
    val tagColor: Color,
    val name: String?,
    val compactName: Boolean,
    val clock: String?,
    val subtitle: String?,
    val progress: Float?,
    val arcColor: Color,
    val warning: String?,
)

/**
 * The ring's reading. A fard period that is open always leads. With none open, an open prohibited
 * window or open Duha takes the ring over, because it is what explains the moment; otherwise the ring
 * names the prayer that opens next.
 */
@Composable
private fun ringReadingOf(state: HomeUiState.Ready): RingReading {
    val hero = state.currentPrayer
    val next = state.nextPrayer
    val nafl = state.heroNafl
    val open = hero.isActive && hero.prayer != null

    if (!open && nafl != null) {
        val prohibited = nafl.kind == HeroNaflKind.PROHIBITED
        val windowKind = nafl.windowKind
        return RingReading(
            tag = stringResource(
                if (prohibited) R.string.home_hero_status_prohibited else R.string.home_hero_status_nafl,
            ),
            tagColor = if (prohibited) ProhibitedRed else GoldAccent,
            name = if (prohibited && windowKind != null) {
                stringResource(windowKind.labelRes())
            } else {
                stringResource(R.string.nafl_duha)
            },
            compactName = prohibited,
            clock = nafl.countdownClock,
            subtitle = stringResource(R.string.home_hero_ends, nafl.endLabel),
            progress = nafl.progress,
            arcColor = if (prohibited) ProhibitedRed else HeroRingMint,
            warning = null,
        )
    }

    val prayer = if (open) hero.prayer else (next.prayer ?: hero.next.prayer)
    val subtitle = if (open) {
        hero.endTime?.let { end ->
            stringResource(
                if (hero.endsNextDay) R.string.home_hero_ends_next_day else R.string.home_hero_ends,
                end,
            )
        }
    } else {
        (next.time ?: hero.next.time)?.let { time -> stringResource(R.string.home_next_prayer_at, time) }
    }
    val clock = if (open) {
        hero.countdownClock ?: hero.countdown
    } else {
        hero.countdownClock ?: next.countdown ?: hero.next.countdown
    }
    val overlapping = open && nafl != null && nafl.kind == HeroNaflKind.PROHIBITED

    return RingReading(
        tag = if (!open && prayer != null) stringResource(R.string.home_hero_status_up_next) else null,
        tagColor = GoldAccent,
        name = prayer?.let { value -> stringResource(value.labelRes()) },
        compactName = false,
        clock = clock,
        subtitle = subtitle,
        progress = if (open) hero.periodProgress else null,
        arcColor = HeroRingMint,
        warning = if (overlapping && nafl != null) {
            stringResource(R.string.home_hero_prohibited_left, nafl.countdownCompact)
        } else {
            null
        },
    )
}

/**
 * The Sehri end above Iftar, each under its own gold label, with a thin rule between them.
 *
 * The next event is highlighted and counts down; a time that has passed is dimmed; the other is shown
 * at a reduced emphasis. When [section] describes tomorrow, the labels say so.
 */
@Composable
private fun FastingPanel(
    section: FastingTimesSection,
    onBrand: Color,
    onOpenReminders: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
    ) {
        FastingTimeColumn(
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(
                if (section.isTomorrow) R.string.home_hero_tomorrow_sehri_ends else R.string.home_hero_sehri_ends,
            ),
            value = section.sehriEndLabel,
            passed = section.sehriPassed,
            highlighted = section.nextEvent == FastingEvent.SEHRI_END,
            countdown = section.countdown.takeIf { section.nextEvent == FastingEvent.SEHRI_END },
            reminderLabel = stringResource(R.string.home_hero_set_sehri_reminder),
            onClick = onOpenReminders,
            onBrand = onBrand,
        )
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 6.dp),
            color = onBrand.copy(alpha = 0.18f),
        )
        FastingTimeColumn(
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(
                if (section.isTomorrow) R.string.home_hero_tomorrow_iftar else R.string.home_hero_iftar_begins,
            ),
            value = section.iftarLabel,
            passed = section.iftarPassed,
            highlighted = section.nextEvent == FastingEvent.IFTAR,
            countdown = section.countdown.takeIf { section.nextEvent == FastingEvent.IFTAR },
            reminderLabel = stringResource(R.string.home_hero_set_iftar_reminder),
            onClick = onOpenReminders,
            onBrand = onBrand,
        )
    }
}

@Composable
private fun FastingTimeColumn(
    modifier: Modifier,
    label: String,
    value: String?,
    passed: Boolean,
    highlighted: Boolean,
    countdown: String?,
    reminderLabel: String,
    onClick: (() -> Unit)?,
    onBrand: Color,
) {
    // Emphasis is decided from the flags the factory resolved: the next event at full strength, a time
    // that has passed dimmed, anything else in between.
    val timeColor = when {
        highlighted -> onBrand
        passed -> onBrand.copy(alpha = 0.55f)
        else -> onBrand.copy(alpha = 0.78f)
    }
    val clickable = if (onClick != null) {
        Modifier.clickable(onClickLabel = reminderLabel, onClick = onClick)
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .then(clickable)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.6.sp),
                fontWeight = FontWeight.SemiBold,
                color = GoldAccent,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (onClick != null) {
                Spacer(modifier = Modifier.width(6.dp))
                BellGlyph(tint = GoldAccent, modifier = Modifier.size(14.dp))
            }
        }
        Text(
            text = value ?: stringResource(R.string.home_time_unavailable),
            style = MaterialTheme.typography.headlineSmall.copy(fontSize = 26.sp),
            fontWeight = FontWeight.Bold,
            color = timeColor,
            maxLines = 1,
        )
        if (highlighted && countdown != null) {
            Text(
                text = stringResource(R.string.home_hero_fasting_in, countdown),
                style = MaterialTheme.typography.bodySmall,
                color = onBrand.copy(alpha = 0.82f),
            )
        }
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
                        text = stringResource(R.string.home_prayer_status_later),
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
internal fun PrayerRowGlyph(prayer: PrayerName, tint: Color) {
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
                            val active = window.phase == ProhibitedPeriodPhase.ACTIVE
                            Text(
                                text = stringResource(
                                    R.string.home_prohibited_window,
                                    stringResource(window.kind.labelRes()),
                                    window.startsAt,
                                    window.endsAt,
                                ),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = if (active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val left = window.countdown
                            if (active && left != null) {
                                Text(
                                    text = stringResource(R.string.prohibited_countdown_until_close, left),
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                    color = MaterialTheme.colorScheme.error,
                                    maxLines = 1,
                                )
                            }
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
