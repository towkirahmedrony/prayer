package io.github.towkirahmedrony.salah.data.prayer

import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleRequest
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerTimesProvider
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerTimesProviderResult
import io.github.towkirahmedrony.salah.core.domain.prayer.ProviderAvailability
import io.github.towkirahmedrony.salah.core.model.PrayerTimeSource
import io.github.towkirahmedrony.salah.core.model.UnavailabilityReason

/**
 * The provider boundary for Salah's future local astronomical engine.
 *
 * It is wired up in dependency injection so that the rest of the app already flows through the
 * provider abstraction, but it deliberately computes nothing: no astronomical library is
 * integrated in this phase. Reporting "not configured" is the honest state, and it is what
 * keeps the Home screen from displaying fabricated prayer times.
 *
 * The next phase replaces this implementation with a real local engine (Adhan Kotlin is the
 * candidate) once its calculation profile has been decided and verified.
 */
class UnconfiguredLocalPrayerTimesProvider : PrayerTimesProvider {

    override val id: String = "local-unconfigured"

    override val source: PrayerTimeSource = PrayerTimeSource.UNCONFIGURED

    override val isAvailableOffline: Boolean = true

    override fun availability(): ProviderAvailability = ProviderAvailability.NOT_CONFIGURED

    override suspend fun schedule(request: PrayerScheduleRequest): PrayerTimesProviderResult =
        PrayerTimesProviderResult.Unavailable(
            reason = UnavailabilityReason.CALCULATION_NOT_CONFIGURED,
            detail = "No local astronomical prayer-time engine is integrated yet.",
        )
}
