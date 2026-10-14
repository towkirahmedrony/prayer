package io.github.towkirahmedrony.salah.feature.home.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.designsystem.*
import io.github.towkirahmedrony.salah.core.designsystem.theme.GoldAccent
import io.github.towkirahmedrony.salah.core.designsystem.theme.LocalSalahAccents
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.feature.common.labelRes
import io.github.towkirahmedrony.salah.feature.common.messageRes
import io.github.towkirahmedrony.salah.feature.home.CurrentPrayerSection
import io.github.towkirahmedrony.salah.feature.home.HomeUiState
import io.github.towkirahmedrony.salah.feature.home.PrayerRow
import io.github.towkirahmedrony.salah.feature.home.SolarRow

@Composable
/**
 * The Home header: the brand mark, the app's name and the two icon buttons beside it.
 *
 * The glyphs and their rings are drawn from the scheme's `primary` rather than the brand green constant
 * they used to name directly. That constant is Salah's *light* primary, so on the dark canvas it measured
 * about **1.7:1** — the two buttons were there and all but invisible — while `primary` is the green the
 * active theme says is legible: the same deep green in light mode, the scheme's light mint in dark mode.
 * The tagline's gold comes from the theme's accents for the same reason; see [LocalSalahAccents].
 */
@Composable
fun HomeTopBar(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val accents = LocalSalahAccents.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SalahBrandLogo(size = 28.dp)
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    // `app_name` is not translatable: a product name is the same in every language.
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource(R.string.app_tagline),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = accents.goldText,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .border(1.dp, scheme.primary.copy(alpha = 0.2f), CircleShape)
                    .clickable(onClick = onOpenSettings),
                contentAlignment = Alignment.Center,
            ) {
                BellGlyph(tint = scheme.primary, modifier = Modifier.size(20.dp))
            }
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, scheme.primary, CircleShape)
                    .clickable(onClick = onOpenSettings),
                contentAlignment = Alignment.Center,
            ) {
                PersonGlyph(tint = scheme.primary, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
fun LocationAndDateHeader(
    state: HomeUiState.Ready,
    onOpenLocation: () -> Unit,
    onOpenCalendar: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.clickable(onClick = onOpenLocation),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LocationPinGlyph(tint = scheme.primary, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = state.location.label
                        ?: stringResource(state.location.unavailable?.messageRes() ?: R.string.home_location_none_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = "⌵", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                // The weekday and the date are both formatted values, and the order they are joined in
                // is a property of the language, not of this layout — so the join is a resource too.
                text = state.gregorianDate?.let { date ->
                    state.weekday?.let { weekday ->
                        stringResource(R.string.home_weekday_and_date, weekday, date)
                    } ?: date
                } ?: stringResource(R.string.home_date_pending),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.hijriSection.value?.let { hijri ->
                Text(
                    text = hijri,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, scheme.primary.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                .clickable(onClick = onOpenCalendar),
            contentAlignment = Alignment.Center,
        ) {
            CalendarGlyph(tint = scheme.primary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun CurrentPrayerHeroCard(
    state: CurrentPrayerSection,
    modifier: Modifier = Modifier,
) {
    val active = state.isActive && state.prayer != null
    val prayer = state.prayer ?: state.next.prayer
    val prayerName = prayer?.let { stringResource(it.labelRes()) }
        ?: stringResource(state.unavailable?.messageRes() ?: R.string.reason_unknown)
    val countdown = state.countdown ?: state.next.countdown
    val nextLabel = state.endsWithPrayer?.let { stringResource(it.labelRes()) }
        ?: state.endsWithSolarEvent?.let { stringResource(it.labelRes()) }
        ?: state.next.prayer?.let { stringResource(it.labelRes()) }
    val nextTime = state.endTime ?: state.next.time

    // The hero is Salah's brand surface, not a themed one, so its fill and everything drawn on it come
    // from the theme's own brand pairing: the two values are declared together, and this screen cannot
    // end up with white type on a fill that a later theme change moved out from under it.
    val accents = LocalSalahAccents.current
    val onBrand = accents.onBrandSurface

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(accents.brandSurface)
            .padding(16.dp),
    ) {
        SunRaysArt(
            tint = GoldAccent.copy(alpha = 0.75f),
            modifier = Modifier
                .size(132.dp)
                .align(Alignment.CenterEnd),
        )

        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(
                    if (active) R.string.home_hero_status_current else R.string.home_hero_status_up_next,
                ),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                color = onBrand.copy(alpha = 0.75f),
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = prayerName,
                style = MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp),
                fontWeight = FontWeight.Bold,
                color = onBrand,
            )
            Text(
                text = stringResource(
                    if (active) R.string.home_hero_subtitle_active else R.string.home_hero_subtitle_next,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = onBrand.copy(alpha = 0.85f),
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (countdown != null) {
                Text(
                    text = countdown,
                    style = MaterialTheme.typography.displaySmall.copy(fontSize = 32.sp, fontWeight = FontWeight.Bold),
                    color = onBrand,
                )
                // The caption names the *time* the countdown runs to, never the prayer as something to
                // be performed: Salah does not rule on how long a prayer may be offered.
                val countdownCaption = when {
                    active && nextLabel != null ->
                        stringResource(R.string.home_hero_time_until, nextLabel)

                    !active && nextLabel != null ->
                        stringResource(R.string.home_hero_time_until_begins, nextLabel)

                    else -> stringResource(R.string.home_hero_time_remaining)
                }
                Text(
                    text = countdownCaption,
                    style = MaterialTheme.typography.bodySmall,
                    color = onBrand.copy(alpha = 0.85f),
                )
            } else {
                Text(
                    text = stringResource(state.unavailable?.messageRes() ?: R.string.home_time_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = onBrand.copy(alpha = 0.9f),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (active) {
                    // `startTime` is null only for the Isha period that began before today's schedule.
                    Text(
                        text = state.startTime?.let { start ->
                            stringResource(R.string.home_hero_starts, start)
                        } ?: stringResource(R.string.home_hero_since_last_night),
                        style = MaterialTheme.typography.bodySmall,
                        color = onBrand.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (nextTime != null) {
                        // The closing time can be tomorrow's Fajr, which is why it may differ from
                        // today's Fajr in the list by a minute; say so instead of leaving it ambiguous.
                        Text(
                            text = if (state.endsNextDay) {
                                stringResource(R.string.home_hero_ends_next_day, nextTime)
                            } else {
                                stringResource(R.string.home_hero_ends, nextTime)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = onBrand.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.home_next_prayer_title),
                        style = MaterialTheme.typography.bodySmall,
                        color = onBrand.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (nextLabel != null && nextTime != null) {
                        Text(
                            text = stringResource(R.string.home_hero_next_at, prayerName, nextTime),
                            style = MaterialTheme.typography.bodySmall,
                            color = onBrand.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TodayPrayerTimesSection(
    rows: List<PrayerRow>,
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
                text = stringResource(R.string.home_today_prayer_times_title),
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.home_action_view_all),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onViewAll),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The current-prayer marker comes from the shared state, not from this composable choosing
            // a prayer: [PrayerRow.isCurrent] is resolved once in HomeUiStateFactory, and the Prayer
            // screen reads the very same flag, so the two strips cannot select different prayers.
            val hasActivePeriod = rows.any { it.isCurrent }
            rows.forEach { row ->
                val isSelected = if (hasActivePeriod) row.isCurrent else row.isNext
                TodayPrayerCard(row = row, isSelected = isSelected, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TodayPrayerCard(row: PrayerRow, isSelected: Boolean, modifier: Modifier = Modifier) {
    // A selected chip is a *container* and an unselected one an outlined surface: two different
    // treatments rather than two tints of one, which is what keeps the selected prayer readable once the
    // canvas goes dark. The fills are the scheme's own roles, so the pale mint written here by hand
    // cannot become a near-white card on the dark canvas, and the label inherits the container it sits on.
    val scheme = MaterialTheme.colorScheme
    val container = if (isSelected) scheme.primaryContainer else Color.Transparent
    val content = if (isSelected) scheme.onPrimaryContainer else scheme.onSurface
    val glyph = if (isSelected) scheme.onPrimaryContainer else scheme.primary
    val border = if (isSelected) Color.Transparent else scheme.outlineVariant

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(container)
            .border(1.dp, border, RoundedCornerShape(16.dp))
            .padding(vertical = 10.dp, horizontal = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Fixed-size icon slot so every chip lines up, whatever the glyph.
        Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            when (row.prayer) {
                PrayerName.FAJR -> SunrisePrayerGlyph(glyph, modifier = Modifier.size(20.dp))
                PrayerName.DHUHR -> OctagramGlyph(glyph, modifier = Modifier.size(20.dp))
                PrayerName.ASR -> ClockGlyph(glyph, modifier = Modifier.size(20.dp))
                PrayerName.MAGHRIB -> SunsetGlyph(glyph, modifier = Modifier.size(20.dp))
                PrayerName.ISHA -> NightGlyph(glyph, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(row.prayer.labelRes()),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = content,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = row.timeLabel ?: stringResource(R.string.home_time_unavailable),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            fontWeight = FontWeight.SemiBold,
            color = content,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        // The prayer's end time, under its start, joined by a short dash so it reads as a range.
        val endTime = row.endTimeLabel
        if (endTime != null) {
            Box(
                modifier = Modifier
                    .padding(vertical = 3.dp)
                    .width(8.dp)
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant),
            )
            Text(
                text = endTime,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
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
                    // The card's own heading, shared with the full `ProhibitedTimesCard` so one card
                    // cannot end up with two names for the same thing.
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
 * Shown as solar events (not prayers). Renders nothing when the schedule carries none of them, so
 * no time is ever invented.
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
            // Each tile is labelled with the destination it opens, using that destination's own name
            // resource, so a tile cannot say one thing and lead to a screen headed another.
            DailyToolItem(title = stringResource(R.string.shortcut_qibla), glyph = { tint -> QiblaGlyph(tint) }, onClick = { onNavigate("qibla") }, modifier = Modifier.weight(1f))
            DailyToolItem(title = stringResource(R.string.nav_calendar), glyph = { tint -> CalendarGlyph(tint) }, onClick = { onNavigate("calendar") }, modifier = Modifier.weight(1f))
            DailyToolItem(title = stringResource(R.string.nav_ramadan), glyph = { tint -> RamadanGlyph(tint) }, onClick = { onNavigate("ramadan") }, modifier = Modifier.weight(1f))
            // Labelled for the screen it opens: this tile navigates to the tools destination, and Salah
            // has no Tasbih feature to name it after. The mark is the tools destination's own glyph.
            DailyToolItem(title = stringResource(R.string.nav_tools), glyph = { tint -> TasbihGlyph(tint) }, onClick = { onNavigate("tools") }, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * One tile of the daily-tools row.
 *
 * The tile is filled with the scheme's `primaryContainer` and its glyph and label are drawn in
 * `onPrimaryContainer`, so the pairing is the theme's rather than a mint picked for the light canvas. The
 * disc behind the glyph is the scheme's `surface` at a low alpha — white over mint in light mode, the
 * card tone over deep green in dark mode — so it reads as a punched-out circle in both themes without
 * introducing a colour the scheme does not carry. The tint is passed to the glyph instead of being
 * closed over, the same way `SalahShortcutTile` does it.
 */
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
