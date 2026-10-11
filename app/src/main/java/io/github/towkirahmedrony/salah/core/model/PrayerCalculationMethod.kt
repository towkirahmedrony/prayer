package io.github.towkirahmedrony.salah.core.model

/**
 * The prayer-time calculation methods Salah can select.
 *
 * Every entry corresponds one-to-one to a method implemented by the integrated local engine
 * (Adhan Kotlin, `com.batoulapps.adhan:adhan2`). The angles documented here are the values that
 * engine ships for the method; the engine stays the single source of truth for the arithmetic,
 * and Salah passes the method identity through rather than substituting its own numbers.
 *
 * These methods are *conventions of calculation*, not declarations of religious authority.
 * Salah does not claim that any of them reproduces the timetable of a national or local
 * authority, and no such claim may be added without an actual comparison against an identified
 * timetable for the same location and date. See `docs/PRAYER_CALCULATION.md`.
 */
enum class PrayerCalculationMethod(
    /** Fajr is calculated at this solar depression angle, in degrees below the horizon. */
    val fajrAngleDegrees: Double,
    /** Isha is calculated at this solar depression angle. Ignored when [ishaIntervalMinutes] > 0. */
    val ishaAngleDegrees: Double,
    /** When greater than zero, Isha is a fixed interval after Maghrib instead of an angle. */
    val ishaIntervalMinutes: Int = 0,
) {
    /** University of Islamic Sciences, Karachi — Fajr 18°, Isha 18°. */
    KARACHI(18.0, 18.0),

    /** Muslim World League — Fajr 18°, Isha 17°. */
    MUSLIM_WORLD_LEAGUE(18.0, 17.0),

    /** Egyptian General Authority of Survey — Fajr 19.5°, Isha 17.5°. */
    EGYPTIAN(19.5, 17.5),

    /** Umm al-Qura University, Makkah — Fajr 18.5°, Isha 90 minutes after Maghrib. */
    UMM_AL_QURA(18.5, 0.0, ishaIntervalMinutes = 90),

    /** The Gulf region — Fajr and Isha 18.2°. */
    DUBAI(18.2, 18.2),

    /** Moonsighting Committee — Fajr and Isha 18°, with seasonal twilight adjustment. */
    MOON_SIGHTING_COMMITTEE(18.0, 18.0),

    /** ISNA (North America) — Fajr and Isha 15°. */
    NORTH_AMERICA(15.0, 15.0),

    /** Kuwait — Fajr 18°, Isha 17.5°. */
    KUWAIT(18.0, 17.5),

    /** Qatar — Fajr 18°, Isha 90 minutes after Maghrib. */
    QATAR(18.0, 0.0, ishaIntervalMinutes = 90),

    /** Singapore — Fajr 20°, Isha 18°, rounded up to the next minute. */
    SINGAPORE(20.0, 18.0),

    /** Diyanet İşleri Başkanlığı, Turkey — Fajr 18°, Isha 17°. */
    TURKEY(18.0, 17.0),
    ;

    companion object {
        /**
         * Starting point offered to a new user.
         *
         * Karachi angles (18°/18°) with Hanafi Asr is the convention this project already used
         * before the engine was integrated, and it is in wide use across South Asia, including
         * Bangladesh. It is a **default setting, not a verified authority**: Salah labels it in
         * the UI as a selectable method and lets the user change it.
         */
        val DEFAULT: PrayerCalculationMethod = KARACHI
    }
}
