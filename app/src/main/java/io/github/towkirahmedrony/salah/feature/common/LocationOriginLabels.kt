package io.github.towkirahmedrony.salah.feature.common

import androidx.annotation.StringRes
import io.github.towkirahmedrony.salah.R
import io.github.towkirahmedrony.salah.core.model.LocationOrigin

/**
 * How a stored location's source is named on screen.
 *
 * **Why one mapping for the whole app.** The same stored location is read by more than one screen, and
 * each screen inventing its own vocabulary is how one of them ends up describing an area selection as
 * a typed coordinate. The distinction is not cosmetic: [LocationOrigin.AREA_SELECTION] stores an
 * administrative centre rather than the user's position, and
 * [LocationOrigin.DEVICE_GPS] stores an observation with an accuracy and a time, so the three sources
 * make three different claims about what Salah knows.
 *
 * Lives in `feature/common` beside [LocationFormState], which the Qibla screen and the location screen
 * already share for the same reason.
 *
 * Android-free apart from the resource ids: the domain never depends on `R`, so this belongs to the
 * feature layer rather than to `core/domain/location`.
 */
@StringRes
fun LocationOrigin.sourceLabelRes(): Int = when (this) {
    LocationOrigin.DEVICE_GPS -> R.string.location_source_gps
    LocationOrigin.AREA_SELECTION -> R.string.location_source_area
    LocationOrigin.MANUAL_ENTRY -> R.string.location_source_manual
}
