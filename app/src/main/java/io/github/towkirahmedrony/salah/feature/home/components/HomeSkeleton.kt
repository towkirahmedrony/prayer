package io.github.towkirahmedrony.salah.feature.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.towkirahmedrony.salah.core.designsystem.components.SalahSkeletonBlock

/**
 * Home's layout with no values in it.
 *
 * Shown in the two places where Salah used to show a centred spinner and a "Loading…" label: while
 * the location gate is still deciding, and while the Home state itself is still `Loading`. The wait
 * is short, and a screen that already has the shape of the answer is a smaller visual change than a
 * spinner followed by a whole screen of new layout.
 *
 * **It cannot show a value.** Every block is contentless, so there is no prayer name, no time and no
 * countdown on this screen — nothing here can be mistaken for a calculated time, and nothing here
 * has to be corrected when the real state arrives.
 *
 * **It is geometry-compatible with `HomeContent`.** The outer padding, the 14.dp spacing, the 24.dp
 * hero radius and the 6.dp / 16.dp prayer-chip row are the same values the real sections use, so
 * swapping the skeleton for content does not move what is already on screen. Heights are approximate
 * by design: the point is that the coarse shape and the position match, not that every block is
 * pixel-identical to the text it stands in for.
 *
 * Static by design — see [SalahSkeletonBlock] for why there is no shimmer.
 */
@Composable
fun HomeSkeleton(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // HomeHeader: the place name and the date line, with the crescent glyph beside them.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                SalahSkeletonBlock(height = 20.dp, modifier = Modifier.fillMaxWidth(0.42f))
                Spacer(modifier = Modifier.height(6.dp))
                SalahSkeletonBlock(height = 14.dp, modifier = Modifier.fillMaxWidth(0.62f))
            }
            Spacer(modifier = Modifier.width(12.dp))
            SalahSkeletonBlock(
                height = 30.dp,
                modifier = Modifier.width(30.dp),
                shape = CircleShape,
            )
        }

        // NextPrayerHero: the same visible container — surface fill, 0.4-alpha secondary border and
        // 24.dp radius — with an empty next-prayer block instead of a prayer, a time and a countdown.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(scheme.surface)
                .border(1.dp, scheme.secondary.copy(alpha = 0.4f), RoundedCornerShape(24.dp))
                .padding(horizontal = 20.dp, vertical = 20.dp),
        ) {
            SalahSkeletonBlock(
                height = 24.dp,
                modifier = Modifier.fillMaxWidth(0.34f),
                shape = RoundedCornerShape(100.dp),
            )
            Spacer(modifier = Modifier.height(14.dp))
            SalahSkeletonBlock(height = 24.dp, modifier = Modifier.fillMaxWidth(0.48f))
            Spacer(modifier = Modifier.height(8.dp))
            SalahSkeletonBlock(height = 40.dp, modifier = Modifier.fillMaxWidth(0.42f))
            Spacer(modifier = Modifier.height(8.dp))
            SalahSkeletonBlock(height = 14.dp, modifier = Modifier.fillMaxWidth(0.36f))
        }

        // PrayerStrip: five chips, the same count as the five daily prayers, in a row that uses the
        // strip's own 6.dp gap and the chip's own 16.dp radius.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PRAYER_STRIP_GAP),
        ) {
            repeat(PRAYER_COUNT) {
                SalahSkeletonBlock(
                    height = 58.dp,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                )
            }
        }

        // The first section card below the strip: one block at the card shape and radius, standing in
        // for whatever the screen shows in that position.
        SalahSkeletonBlock(
            height = 96.dp,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
        )
    }
}

/** The five daily prayers. Only the count matters — no chip in the skeleton carries a value. */
private const val PRAYER_COUNT = 5

/** The gap `PrayerStrip` puts between its chips. */
private val PRAYER_STRIP_GAP = 6.dp
