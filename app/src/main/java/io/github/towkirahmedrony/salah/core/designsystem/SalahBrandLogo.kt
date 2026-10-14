package io.github.towkirahmedrony.salah.core.designsystem

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.towkirahmedrony.salah.R

/**
 * The Salah application logo, as one shared composable.
 *
 * Every screen that shows the app's identity — the home top bar, the brand header, the first-run
 * location setup — renders this instead of drawing its own mark, so a future artwork change is a
 * single swap of `drawable/salah_brand_logo.xml` (generated from `docs/artwork/salah_icon_full.svg`)
 * rather than an edit in every feature.
 *
 * By default the image is [decorative][contentDescription]: it sits next to the visible app name,
 * so an accessibility service announcing it again would only add noise. Pass a [contentDescription]
 * when a screen ever shows the logo where the name is not visible.
 */
@Composable
fun SalahBrandLogo(
    size: Dp = 40.dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Image(
        painter = painterResource(R.drawable.salah_brand_logo),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
    )
}
