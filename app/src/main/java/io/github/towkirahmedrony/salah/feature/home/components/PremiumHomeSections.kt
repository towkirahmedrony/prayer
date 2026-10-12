package io.github.towkirahmedrony.salah.feature.home.components

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
import io.github.towkirahmedrony.salah.core.designsystem.theme.ForestGreenPrimary
import io.github.towkirahmedrony.salah.core.designsystem.theme.GoldAccent
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.feature.common.labelRes
import io.github.towkirahmedrony.salah.feature.common.messageRes
import io.github.towkirahmedrony.salah.feature.home.CurrentPrayerSection
import io.github.towkirahmedrony.salah.feature.home.HomeUiState
import io.github.towkirahmedrony.salah.feature.home.PrayerRow

@Composable
fun HomeTopBar(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CrescentGlyph(tint = ForestGreenPrimary, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Salah",
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "Your Complete Islamic Companion",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = GoldAccent,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .border(1.dp, ForestGreenPrimary.copy(alpha = 0.2f), CircleShape)
                    .clickable(onClick = onOpenSettings),
                contentAlignment = Alignment.Center,
            ) {
                BellGlyph(tint = ForestGreenPrimary, modifier = Modifier.size(22.dp))
            }
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, ForestGreenPrimary, CircleShape)
                    .clickable(onClick = onOpenSettings),
                contentAlignment = Alignment.Center,
            ) {
                PersonGlyph(tint = ForestGreenPrimary, modifier = Modifier.size(25.dp))
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.clickable(onClick = onOpenLocation),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LocationPinGlyph(tint = ForestGreenPrimary, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = state.location.label
                        ?: stringResource(state.location.unavailable?.messageRes() ?: R.string.home_location_none_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = "⌵", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = state.gregorianDate?.let { date -> state.weekday?.let { "$it, $date" } ?: date }
                    ?: stringResource(R.string.home_date_pending),
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
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, ForestGreenPrimary.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                .clickable(onClick = onOpenCalendar),
            contentAlignment = Alignment.Center,
        ) {
            CalendarGlyph(tint = ForestGreenPrimary, modifier = Modifier.size(20.dp))
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

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(ForestGreenPrimary)
            .padding(22.dp),
    ) {
        SunRaysArt(
            tint = GoldAccent.copy(alpha = 0.75f),
            modifier = Modifier
                .size(160.dp)
                .align(Alignment.CenterEnd),
        )

        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = if (active) "CURRENT PRAYER" else "UP NEXT",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                color = Color.White.copy(alpha = 0.75f),
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = prayerName,
                style = MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp),
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                text = if (active) "Currently in prayer time" else "Next prayer",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.85f),
            )
            Spacer(modifier = Modifier.height(14.dp))
            if (countdown != null) {
                Text(
                    text = countdown,
                    style = MaterialTheme.typography.displaySmall.copy(fontSize = 38.sp, fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
                val countdownCaption = when {
                    active && nextLabel != null -> "Time until $nextLabel"
                    !active && nextLabel != null -> "Time until $nextLabel begins"
                    else -> "Time remaining"
                }
                Text(
                    text = countdownCaption,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                )
            } else {
                Text(
                    text = stringResource(state.unavailable?.messageRes() ?: R.string.home_time_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.9f),
                )
            }
            Spacer(modifier = Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val startTime = if (active) state.startTime else null
                Text(
                    text = if (active && startTime != null) "$prayerName — $startTime" else "Next prayer",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (nextLabel != null && nextTime != null) {
                    Text(
                        text = if (active) "$nextLabel — $nextTime" else "$prayerName — $nextTime",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun TodayPrayerTimesSection(
    rows: List<PrayerRow>,
    onViewAll: () -> Unit,
    currentPrayer: PrayerName? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Today's Prayer Times",
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "View all →",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = ForestGreenPrimary,
                modifier = Modifier.clickable(onClick = onViewAll),
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            rows.forEach { row ->
                val isSelected = currentPrayer?.let { row.prayer == it } ?: row.isNext
                TodayPrayerCard(row = row, isSelected = isSelected, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TodayPrayerCard(row: PrayerRow, isSelected: Boolean, modifier: Modifier = Modifier) {
    val bg = if (isSelected) Color(0xFFD3E8DC) else Color.Transparent
    val borderCol = if (isSelected) Color.Transparent else Color(0xFFE2E0D8)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .border(1.dp, borderCol, RoundedCornerShape(16.dp))
            .padding(vertical = 14.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (row.prayer) {
            PrayerName.FAJR -> SunrisePrayerGlyph(ForestGreenPrimary, modifier = Modifier.size(24.dp))
            PrayerName.DHUHR -> OctagramGlyph(ForestGreenPrimary, modifier = Modifier.size(24.dp))
            PrayerName.ASR -> Text("◷", fontSize = 20.sp, color = ForestGreenPrimary)
            PrayerName.MAGHRIB -> CrescentGlyph(ForestGreenPrimary, modifier = Modifier.size(22.dp))
            PrayerName.ISHA -> CrescentGlyph(ForestGreenPrimary, modifier = Modifier.size(22.dp))
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(row.prayer.labelRes()),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = row.timeLabel ?: "—",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun ForbiddenTimesCard(
    state: HomeUiState.Ready,
    onViewDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE8E6DE)),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Forbidden Prayer Times",
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "View details →",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = ForestGreenPrimary,
                    modifier = Modifier.clickable(onClick = onViewDetails),
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                    if (state.prohibited.windows.isEmpty()) {
                        Text(
                            text = stringResource(state.prohibited.unavailable?.messageRes() ?: R.string.reason_unknown),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        state.prohibited.windows.take(3).forEach { window ->
                            Text(
                                text = "${stringResource(window.kind.labelRes())} — ${window.startsAt}–${window.endsAt}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Text(text = "☼", fontSize = 42.sp, color = GoldAccent)
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
            text = "Your Daily Tools",
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DailyToolItem(title = "Qibla", icon = { QiblaGlyph(ForestGreenPrimary) }, onClick = { onNavigate("qibla") }, modifier = Modifier.weight(1f))
            DailyToolItem(title = "Calendar", icon = { CalendarGlyph(ForestGreenPrimary) }, onClick = { onNavigate("calendar") }, modifier = Modifier.weight(1f))
            DailyToolItem(title = "Ramadan", icon = { CrescentGlyph(ForestGreenPrimary) }, onClick = { onNavigate("ramadan") }, modifier = Modifier.weight(1f))
            DailyToolItem(title = "Tasbih", icon = { TasbihGlyph(ForestGreenPrimary) }, onClick = { onNavigate("tools") }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun DailyToolItem(
    title: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFFD4E8DC))
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = ForestGreenPrimary,
        )
    }
}
