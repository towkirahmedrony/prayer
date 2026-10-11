package io.github.towkirahmedrony.salah.data.prayer

import com.batoulapps.adhan2.CalculationMethod as AdhanCalculationMethod
import com.batoulapps.adhan2.CalculationParameters
import com.batoulapps.adhan2.Coordinates
import com.batoulapps.adhan2.HighLatitudeRule as AdhanHighLatitudeRule
import com.batoulapps.adhan2.Madhab
import com.batoulapps.adhan2.PrayerAdjustments
import com.batoulapps.adhan2.PrayerTimes
import com.batoulapps.adhan2.data.DateComponents
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleRequest
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerTimesProvider
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerTimesProviderResult
import io.github.towkirahmedrony.salah.core.domain.prayer.ProviderAvailability
import io.github.towkirahmedrony.salah.core.model.AsrCalculationMethod
import io.github.towkirahmedrony.salah.core.model.HighLatitudeRule
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationMethod
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationProfile
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerSchedule
import io.github.towkirahmedrony.salah.core.model.PrayerTimeEntry
import io.github.towkirahmedrony.salah.core.model.PrayerTimeSource
import io.github.towkirahmedrony.salah.core.model.SolarEvent
import io.github.towkirahmedrony.salah.core.model.SolarEventEntry
import io.github.towkirahmedrony.salah.core.model.UnavailabilityReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Salah's local astronomical prayer-time engine, backed by Adhan Kotlin.
 *
 * This is the primary prayer-time source and it is fully offline: the library is pure
 * arithmetic over coordinates and a date, so no network, no account and no permission is
 * involved. Nothing here knows about Compose, Android or persistence.
 *
 * Responsibilities kept strictly separate:
 *
 *  - the engine owns the astronomy (sun position, twilight angles, Asr shadow ratio);
 *  - this class owns the mapping to Salah's domain types, including the two engine calls that
 *    keep *solar events* apart from *prayers* (see [schedule]);
 *  - the repository owns caching, invalidation and error presentation.
 *
 * The engine returns UTC instants. Every value published here is converted into the selected
 * location's zone, so the device's own zone can never silently reinterpret a prayer time.
 */
class AdhanPrayerTimesProvider(
    private val clock: Clock,
) : PrayerTimesProvider {

    override val id: String = PROVIDER_ID

    override val source: PrayerTimeSource = PrayerTimeSource.LOCAL_CALCULATION

    override val isAvailableOffline: Boolean = true

    override fun availability(): ProviderAvailability = ProviderAvailability.CONFIGURED

    override suspend fun schedule(request: PrayerScheduleRequest): PrayerTimesProviderResult =
        withContext(Dispatchers.Default) { calculate(request) }

    /** Calculation is a few microseconds of arithmetic, so it runs inline on a background thread. */
    private fun calculate(request: PrayerScheduleRequest): PrayerTimesProviderResult {
        val location: LocationInfo = request.location
        val coordinates = try {
            Coordinates(location.latitude, location.longitude)
        } catch (rejected: IllegalArgumentException) {
            return PrayerTimesProviderResult.Unavailable(
                reason = UnavailabilityReason.CALCULATION_NOT_POSSIBLE,
                detail = "The configured coordinates are outside the range the engine accepts.",
            )
        }

        val profile = request.profile
        val adjustedParameters = buildParameters(profile)

        val prayerTimes = try {
            PrayerTimes(coordinates, request.date.toDateComponents(), adjustedParameters)
        } catch (impossible: IllegalStateException) {
            return PrayerTimesProviderResult.Unavailable(
                reason = UnavailabilityReason.CALCULATION_NOT_POSSIBLE,
                detail = POLAR_DETAIL,
            )
        }

        // True sunrise, true sunset and the solar transit, free of every prayer offset.
        val rawParameters = adjustedParameters.copy(
            methodAdjustments = EMPTY_ADJUSTMENTS,
            prayerAdjustments = EMPTY_ADJUSTMENTS,
        )
        val rawTimes = PrayerTimes(coordinates, request.date.toDateComponents(), rawParameters)

        val zone = location.zoneId
        return PrayerTimesProviderResult.Success(
            PrayerSchedule(
                date = request.date,
                location = location,
                profile = profile,
                prayers = listOf(
                    prayerEntry(PrayerName.FAJR, prayerTimes.fajr.toEpochMilliseconds(), zone, profile),
                    prayerEntry(PrayerName.DHUHR, prayerTimes.dhuhr.toEpochMilliseconds(), zone, profile),
                    prayerEntry(PrayerName.ASR, prayerTimes.asr.toEpochMilliseconds(), zone, profile),
                    prayerEntry(PrayerName.MAGHRIB, prayerTimes.maghrib.toEpochMilliseconds(), zone, profile),
                    prayerEntry(PrayerName.ISHA, prayerTimes.isha.toEpochMilliseconds(), zone, profile),
                ),
                solarEvents = listOf(
                    SolarEventEntry(SolarEvent.SUNRISE, rawTimes.sunrise.toEpochMilliseconds().atZone(zone)),
                    SolarEventEntry(SolarEvent.SOLAR_NOON, rawTimes.dhuhr.toEpochMilliseconds().atZone(zone)),
                    SolarEventEntry(SolarEvent.SUNSET, rawTimes.maghrib.toEpochMilliseconds().atZone(zone)),
                ),
                generatedAt = clock.instant(),
            ),
        )
    }

    private fun prayerEntry(
        prayer: PrayerName,
        engineEpochMilliseconds: Long,
        zone: ZoneId,
        profile: PrayerCalculationProfile,
    ): PrayerTimeEntry = PrayerTimeEntry(
        prayer = prayer,
        time = engineEpochMilliseconds.atZone(zone),
        adjustmentMinutes = profile.adjustmentFor(prayer),
    )

    private fun buildParameters(profile: PrayerCalculationProfile): CalculationParameters =
        profile.method.toAdhanMethod().parameters.copy(
            madhab = profile.asrMethod.toMadhab(),
            highLatitudeRule = profile.highLatitudeRule.toAdhanRule(),
            // Only the five obligatory prayers carry a manual correction. Sunrise has no entry
            // here on purpose: it is a solar event, not a prayer.
            prayerAdjustments = PrayerAdjustments(
                fajr = profile.adjustmentFor(PrayerName.FAJR),
                dhuhr = profile.adjustmentFor(PrayerName.DHUHR),
                asr = profile.adjustmentFor(PrayerName.ASR),
                maghrib = profile.adjustmentFor(PrayerName.MAGHRIB),
                isha = profile.adjustmentFor(PrayerName.ISHA),
            ),
        )

    private fun PrayerCalculationMethod.toAdhanMethod(): AdhanCalculationMethod = when (this) {
        PrayerCalculationMethod.KARACHI -> AdhanCalculationMethod.KARACHI
        PrayerCalculationMethod.MUSLIM_WORLD_LEAGUE -> AdhanCalculationMethod.MUSLIM_WORLD_LEAGUE
        PrayerCalculationMethod.EGYPTIAN -> AdhanCalculationMethod.EGYPTIAN
        PrayerCalculationMethod.UMM_AL_QURA -> AdhanCalculationMethod.UMM_AL_QURA
        PrayerCalculationMethod.DUBAI -> AdhanCalculationMethod.DUBAI
        PrayerCalculationMethod.MOON_SIGHTING_COMMITTEE -> AdhanCalculationMethod.MOON_SIGHTING_COMMITTEE
        PrayerCalculationMethod.NORTH_AMERICA -> AdhanCalculationMethod.NORTH_AMERICA
        PrayerCalculationMethod.KUWAIT -> AdhanCalculationMethod.KUWAIT
        PrayerCalculationMethod.QATAR -> AdhanCalculationMethod.QATAR
        PrayerCalculationMethod.SINGAPORE -> AdhanCalculationMethod.SINGAPORE
        PrayerCalculationMethod.TURKEY -> AdhanCalculationMethod.TURKEY
    }

    private fun AsrCalculationMethod.toMadhab(): Madhab = when (this) {
        AsrCalculationMethod.STANDARD -> Madhab.SHAFI
        AsrCalculationMethod.HANAFI -> Madhab.HANAFI
    }

    /**
     * Maps to the engine's rule, or to `null` for [HighLatitudeRule.AUTOMATIC].
     *
     * The engine has no "no adjustment" option: a `null` rule means "use the rule recommended
     * for this latitude", which is exactly what [HighLatitudeRule.AUTOMATIC] promises.
     */
    private fun HighLatitudeRule.toAdhanRule(): AdhanHighLatitudeRule? = when (this) {
        HighLatitudeRule.AUTOMATIC -> null
        HighLatitudeRule.MIDDLE_OF_THE_NIGHT -> AdhanHighLatitudeRule.MIDDLE_OF_THE_NIGHT
        HighLatitudeRule.SEVENTH_OF_THE_NIGHT -> AdhanHighLatitudeRule.SEVENTH_OF_THE_NIGHT
        HighLatitudeRule.TWILIGHT_ANGLE -> AdhanHighLatitudeRule.TWILIGHT_ANGLE
    }

    private fun LocalDate.toDateComponents(): DateComponents =
        DateComponents(year = year, month = monthValue, day = dayOfMonth)

    /** The engine reports UTC instants; render them in the prayer location's own zone. */
    private fun Long.atZone(zone: ZoneId): ZonedDateTime =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(this), zone)

    private companion object {
        const val PROVIDER_ID = "local-adhan-kotlin"

        const val POLAR_DETAIL =
            "The sun does not reach the required twilight angle at this location on this date, " +
                "so Fajr, sunrise and Isha have no defined time. Adjust the calculation method or " +
                "the high-latitude rule, or choose a different location."

        /** Zero offsets, used to ask the engine for the unadjusted solar events. */
        val EMPTY_ADJUSTMENTS = PrayerAdjustments()
    }
}
