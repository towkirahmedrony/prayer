package io.github.towkirahmedrony.salah.feature.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
 * **It is geometry-compatible with `HomeContent`.** The outer padding and the 12.dp spacing are the
 * values the real sections use, the header row stands in for the place and the bell, the hero block
 * carries the same 28.dp radius, and the list block has the row count of the day's five prayers — so
 * swapping the skeleton for content does not move what is already on screen. Heights are approximate
 * by design: the point is the coarse shape and the position, not a pixel-identical block.
 *
 * Static by design — see [SalahSkeletonBlock] for why there is no shimmer.
 */
@Composable
fun HomeSkeleton(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // HomeHeader: the place name on the left, the reminder bell on the right.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SalahSkeletonBlock(height = 24.dp, modifier = Modifier.fillMaxWidth(0.45f))
            SalahSkeletonBlock(
                height = 44.dp,
                modifier = Modifier.width(44.dp),
                shape = CircleShape,
            )
        }

        // HomeDateLine: the Gregorian, Hijri and Bangla dates on one line.
        SalahSkeletonBlock(height = 16.dp, modifier = Modifier.fillMaxWidth(0.72f))

        // PrayerHero: the brand surface with the two headings, the prayer and countdown, and the arc —
        // the same 28.dp radius the real card carries, with an empty block where each reading goes.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(scheme.surfaceVariant)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            SalahSkeletonBlock(height = 12.dp, modifier = Modifier.fillMaxWidth(0.32f))
            Spacer(modifier = Modifier.height(16.dp))
            SalahSkeletonBlock(height = 34.dp, modifier = Modifier.fillMaxWidth(0.55f))
            Spacer(modifier = Modifier.height(12.dp))
            SalahSkeletonBlock(height = 14.dp, modifier = Modifier.fillMaxWidth(0.40f))
            Spacer(modifier = Modifier.height(24.dp))
            SalahSkeletonBlock(height = 72.dp, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(20.dp))
            SalahSkeletonBlock(height = 14.dp, modifier = Modifier.fillMaxWidth(0.30f))
        }

        // The heading row for the day's list, then one block per prayer in the card below it.
        SalahSkeletonBlock(height = 22.dp, modifier = Modifier.fillMaxWidth(0.28f))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(scheme.surface)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            repeat(PRAYER_COUNT) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(scheme.surfaceVariant),
                )
            }
        }
    }
}

/** The five daily prayers. Only the count matters — no row in the skeleton carries a value. */
private const val PRAYER_COUNT = 5
