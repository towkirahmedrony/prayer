# Salah — Prayer Time Calculation

This document describes the prayer-time calculation **as implemented in the repository**. It
states what the code actually does, which parts have been verified by tests, and which claims are
deliberately *not* made.

Salah's primary calculation path is **local and offline**. No network call, no account and no
permission is involved in producing a daily timetable.

---

## 1. Calculation engine

| Item | Value |
|---|---|
| Engine | Adhan Kotlin — <https://github.com/batoulapps/adhan-kotlin> |
| Maven coordinates | `com.batoulapps.adhan:adhan2` |
| Pinned version | `0.0.5` (`gradle/libs.versions.toml`, key `adhan`) |
| Wrapper | `AdhanPrayerTimesProvider` (`data/prayer/AdhanPrayerTimesProvider.kt`) |
| Port | `PrayerTimesProvider` (`core/domain/prayer/PrayerTimesProvider.kt`) |

### Why `0.0.5` and not a newer release

The releases after `0.0.5` (`0.0.6`, `0.0.7`) are compiled for a JVM target of 21 — their class
files are major version 65 — while this project builds, tests and lints on the JDK 17 toolchain
(`app/build.gradle.kts` `compileOptions`, and Temurin 17 in the CI workflow). Loading them on JDK
17 fails with:

```
UnsupportedClassVersionError: … class file version 65.0,
this version of the Java Runtime only recognizes class file versions up to 61.0
```

`0.0.5` is the newest release published for a JVM target the project can actually run, so it is
pinned there deliberately. Upgrading it requires moving the toolchain to JDK 21 first, which is a
separate, explicit change — not a drive-by dependency bump.

### Why a library instead of a hand-written algorithm

The engine is pure arithmetic over coordinates and a date: no network, no Android API, no
permission. Reimplementing solar position, twilight angles, the Asr shadow ratio and the
high-latitude rules by hand would be a large surface for subtle error with no accuracy gain over a
mature, pinned implementation. The project therefore pins the library and treats it as the single
source of truth for the astronomy, while Salah owns the mapping to its own domain types.

### Two engine calls keep solar events distinct from prayers

`AdhanPrayerTimesProvider.schedule` makes **two** calls against the same coordinates and date:

1. One with the selected method's adjustments, producing the five obligatory prayers.
2. One with **every adjustment zeroed**, producing the true `sunrise`, solar transit and `sunset`.

This is why sunrise/sunset are never aliases of a prayer time: a method such as Dubai that offsets
Maghrib by a few minutes cannot move the reported sunset. `AdhanPrayerTimesProviderTest` asserts
exactly that (solar events identical across methods, Maghrib different).

---

## 2. Required prayer events and solar events

The domain keeps these as **two separate types** so they can never be confused:

- `PrayerName` — `FAJR`, `DHUHR`, `ASR`, `MAGHRIB`, `ISHA`
  (`core/model/PrayerName.kt`).
- `SolarEvent` — `SUNRISE`, `SOLAR_NOON`, `SUNSET`.

A `PrayerSchedule` carries `prayers: List<PrayerTimeEntry>` and
`solarEvents: List<SolarEventEntry>` separately. `PrayerName.entries` has exactly five members,
which is also what `PrayerSchedule.isComplete` checks.

### Result model

`core/model/PrayerSchedule.kt`:

```kotlin
data class PrayerTimeEntry(val prayer: PrayerName, val time: ZonedDateTime, val adjustmentMinutes: Int = 0)
data class SolarEventEntry(val event: SolarEvent, val time: ZonedDateTime)

data class PrayerSchedule(
    val date: LocalDate,
    val location: LocationInfo,
    val profile: PrayerCalculationProfile,
    val prayers: List<PrayerTimeEntry>,
    val solarEvents: List<SolarEventEntry>,
    val generatedAt: Instant,
)
```

Times are `ZonedDateTime`, never strings. The schedule also carries the `location` and `profile`
it was produced with and the `generatedAt` instant, which is what lets the cache refuse a
mismatched entry (see §5) and the UI disclose the method behind a timetable.

Status and provenance are separate types: `PrayerScheduleState`
(`Loading` / `Available` / `Unavailable` / `Failed`) and `DataSourceInfo(source, providerId,
observedAt, isAvailableOffline)`.

---

## 3. Calculation methods

`PrayerCalculationMethod` (`core/model/PrayerCalculationMethod.kt`) maps one-to-one onto engine
methods. Salah passes the method *identity* to the engine and does not substitute its own angles —
the angles listed here document what the engine ships for each method, they are not a second
implementation.

| Salah value | Engine method | Fajr | Isha |
|---|---|---|---|
| `KARACHI` | `KARACHI` | 18° | 18° |
| `MUSLIM_WORLD_LEAGUE` | `MUSLIM_WORLD_LEAGUE` | 18° | 17° |
| `EGYPTIAN` | `EGYPTIAN` | 19.5° | 17.5° |
| `UMM_AL_QURA` | `UMM_AL_QURA` | 18.5° | 90 min after Maghrib |
| `DUBAI` | `DUBAI` | 18.2° | 18.2° |
| `MOON_SIGHTING_COMMITTEE` | `MOON_SIGHTING_COMMITTEE` | 18° | 18° (seasonal twilight) |
| `NORTH_AMERICA` | `NORTH_AMERICA` | 15° | 15° |
| `KUWAIT` | `KUWAIT` | 18° | 17.5° |
| `QATAR` | `QATAR` | 18° | 90 min after Maghrib |
| `SINGAPORE` | `SINGAPORE` | 20° | 18° |
| `TURKEY` | `TURKEY` | 18° | 17° |

These are **conventions of calculation**, not declarations of religious authority. Selecting one
does not claim it reproduces any national or local authority's published timetable.

### Asr configuration

`AsrCalculationMethod` maps directly to the engine's `Madhab`:

- `STANDARD` → `Madhab.SHAFI` (object's own shadow): the majority position.
- `HANAFI` → `Madhab.HANAFI` (twice the object's shadow): a later Asr.

Salah's shipped default is **Hanafi Asr**. `AdhanPrayerTimesProviderTest` asserts that changing
only the Asr method moves Asr and nothing else.

### High-latitude handling

`HighLatitudeRule` maps to the engine's `HighLatitudeRule`:

| Salah value | Engine value |
|---|---|
| `AUTOMATIC` (default) | `null` — the engine applies the rule recommended for the latitude |
| `MIDDLE_OF_THE_NIGHT` | `MIDDLE_OF_THE_NIGHT` |
| `SEVENTH_OF_THE_NIGHT` | `SEVENTH_OF_THE_NIGHT` |
| `TWILIGHT_ANGLE` | `TWILIGHT_ANGLE` |

There is intentionally no "off" switch: without *some* bound, a summer Fajr or Isha at high
latitude is undefined. At Bangladesh's latitude the rule is never reached, so it does not change
the calculated times there.

### Manual minute adjustments

Each of the five prayers may carry an optional manual correction in minutes
(`PrayerSettings.withAdjustment`, clamped to ±60). The correction is applied **by the engine**
through `PrayerAdjustments`, and the applied value is recorded on the resulting `PrayerTimeEntry`
so the UI can disclose it. Sunrise and sunset carry no adjustment on purpose — they are solar
events, not prayers.

---

## 4. Bangladesh-focused defaults

- The shipped default profile is **Karachi angles (18°/18°) with Hanafi Asr** and the automatic
  high-latitude rule (`PrayerCalculationProfile.DEFAULT_KARACHI_HANAFI`, derived from
  `PrayerSettings.DEFAULT`). This is a **default setting, not a verified authority**: it is in wide
  use across South Asia including Bangladesh, it is clearly labelled in Settings as a selectable
  method, and the user can change it.
- Salah does **not** hardcode one city for everyone. A location is never invented: with no
  configured location the app shows an explicit "no location configured" state instead of times.
  There is no silent Dhaka default and no silent replacement of a chosen location.
- No religious authority claim is made. Salah does **not** claim to match Islamic Foundation
  Bangladesh (or any other) published timetables, because no such comparison has been performed.
  See §6 for what such a claim would require.

Salah does not select a "Bangladesh official method" because no such choice has been verified
against an identified authority. The available methods are exposed as clearly labelled,
configurable settings instead of being asserted as authoritative.

---

## 5. Repository, caching, invalidation and (non-)persistence of schedules

`ProviderBackedPrayerScheduleRepository` is the offline-first schedule repository.

- **Reads are local first.** `ensureSchedule` answers from an in-memory entry on the first
  emission and never performs I/O, so the UI is never blocked.
- **Cache key = the whole request.** An entry is served only when its `date` **and** `location`
  **and** `profile` equal the incoming request. Because those are value types, changing the
  location, the time zone, the calculation method, the Asr method, the high-latitude rule, a
  manual adjustment or the calendar date invalidates the entry automatically — there is no
  separate "invalidate" call to forget.
- **A mismatched provider response is refused.** If a provider answers for another day, city or
  request, the repository returns `Failed` and does not cache it, so times for the wrong place can
  never be shown as this request's answer (`ProviderBackedPrayerScheduleRepositoryTest`).
- **No Room, deliberately.** A daily schedule is cheap, deterministic arithmetic over a date and a
  coordinate pair, recomputed in microseconds offline. Persisting derived schedules would add a
  schema, a migration path and a new class of staleness bug for no benefit. The durable state is
  the user's **preferences and location**, both in DataStore (see §8). If a future phase adds
  caching, this repository is the single extension point; the `PrayerScheduleRepository` interface
  does not change.

### Time-zone handling

- The engine returns UTC instants. The provider converts **every** value into the *prayer
  location's* `ZoneId` before publishing it, so the device's own zone can never reinterpret a
  prayer time.
- `HomeViewModel` derives the day to calculate from the instant **at the location**, not at the
  device: `date = at.withZoneSameInstant(location.zoneId).toLocalDate()`.
- The current-device zone is re-read on every tick (`ZoneId.systemDefault()`), so a device
  time-zone change takes effect immediately instead of after a restart.
- `AdhanPrayerTimesProviderTest` pins a location's zone and offset across a daylight-saving
  transition (London, 29 March 2026) while the test clock runs in UTC, and asserts every entry is
  emitted in the location's zone with the location's offset.

`NextPrayerResolver` compares **instants**, converts `now` into the schedule's zone, and returns
`null` (no countdown) when the schedule is not for the current day at the location, is incomplete,
or has already finished. Sunrise is never returned as a prayer because the resolver reads
`prayers` only.

---

## 6. Verified results vs. local-authority timetables

`AdhanPrayerTimesProviderTest` asserts exact minute values for documented inputs:

| Location | Date | Zone | Profile | Events |
|---|---|---|---|---|
| Dhaka (23.8103, 90.4125) | 2026-10-11 | `Asia/Dhaka` | Karachi, Hanafi | Fajr 04:39, Dhuhr 11:46, Asr 15:58, Maghrib 17:36, Isha 18:51; sunrise 05:54, solar noon 11:45, sunset 17:36 |
| London (51.5074, −0.1278) | 2026-03-29 | `Europe/London` | Muslim World League | Fajr 05:07, Dhuhr 13:06, Isha 21:05, solar noon 13:05 |
| Sydney (−33.8688, 151.2093) | 2026-10-11 | `Australia/Sydney` | Muslim World League | complete schedule, ordered |

**Provenance and tolerance.** These are **regression values taken from this pinned engine version
through Salah's own mapping**, compared exactly at the engine's one-minute rounding (there is no
sub-minute tolerance to apply). They pin the engine version and the Salah→engine mapping so a
silent drift in either is caught. They are **not** an independent religious verification.

**What is explicitly not claimed.** Nothing in this repository establishes that a calculated time
matches the timetable published by Islamic Foundation Bangladesh or any other authority. Doing so
would require a genuine comparison against an *identified, authoritative* timetable for the *same
location and date*, with the difference recorded and its tolerance justified. That comparison has
not been performed, so no such claim appears in the app or the docs.

---

## 7. Prohibited prayer-time windows — status: **unconfigured pending review**

The prohibited-window feature is split deliberately into an **implemented, tested mechanism** and
an **empty, unreviewed rule book**:

- **Implemented and unit-tested:** `ProhibitedWindowKind` (`SUNRISE_ASCENT`, `ZENITH_DECLINE`,
  `SUNSET_SETTING`) as *distinct rules* rather than one generic "minutes around sunrise and
  sunset"; `ProhibitedRuleSet` carrying every boundary as an explicit, reviewable parameter plus a
  `RuleVerificationStatus` and the sources a reviewer approved; and `ProhibitedPeriodCalculator`,
  which anchors each window to the correct solar event and **refuses to run at all** on an
  unreviewed rule set.
- **Deliberately empty in production:** `ProhibitedRuleBook()` ships with **no** rule sets. The
  repository therefore reports `UnavailabilityReason.RULES_NOT_VERIFIED`, and the Home screen
  explains the gap. **No prohibited interval is displayed**, because none has been reviewed.

Consequences, stated plainly:

- The exact durations are **not** hardcoded and are **not** claimed to be universally
  authoritative. They can only come from a reviewed `ProhibitedRuleSet`.
- Prohibited-time calculation is **kept separate** from ordinary prayer-time calculation: the
  calculator consumes an already-computed schedule and can never move a prayer time.
- The synthetic rule set used in `ProhibitedPeriodCalculatorTest` exists only to exercise the
  arithmetic of anchors, ordering and zone handling. Its durations are **not** a religious source
  and it does not ship in the app.

**Remaining review requirement.** Enabling the feature is a data change: supply one
`ProhibitedRuleSet` with `RuleVerificationStatus.REVIEWED`, its boundaries, and the sources a
qualified reviewer approved. Until a qualified reviewer confirms the boundaries and their sources,
the feature stays unconfigured. Passing automated tests does not establish religious correctness.

---

## 8. Location and settings persistence

- **Location:** manual entry only, in this phase. `LocationInfo.create` validates a non-blank
  label, latitude in [−90, 90], longitude in [−180, 180] and a real IANA zone id; invalid input is
  rejected and reported, never partially written. It is stored in DataStore
  (`DataStoreLocationRepository`) and works entirely offline. No location permission is declared
  in the manifest and none is requested, so a manual location is always available even when a
  device provider is unavailable or permission is denied.
- **Settings:** one DataStore preference store holds the theme, the calculation method, the Asr
  method, the high-latitude rule, the time format and the per-prayer adjustments
  (`DataStoreUserPreferencesRepository`). Updates are read-modify-write **inside one transaction**
  (`updatePrayerSettings { it.copy(...) }`), so two quick taps cannot lose each other's change.
  Unknown or out-of-range stored values fall back to a safe default. Room is not used; schedule
  caching is a separate concern that does not exist yet (§5).

---

## 9. Offline behaviour

- The engine is pure arithmetic: the primary path needs no network, no account and no permission.
- The schedule flow emits a value for every outcome (`Available`, `Unavailable`, `Failed`); it
  does not throw for expected problems.
- No network client exists in this phase, so a network failure cannot erase local settings or
  computed schedules — there is nothing networked to fail.
- Cached data is labelled with provenance (`source`, `providerId`, `observedAt`,
  `isAvailableOffline`) and shown as such.

---

## 10. Known limitations

- **Not verified against any published authority timetable.** See §6.
- **Prohibited windows are unconfigured.** See §7.
- **No Bangla or Hijri calendar conversion;** only the device Gregorian date is available.
- **The schedule cache is in-memory.** Process death clears it and the day is recalculated (cheap),
  which is intended.
- **Polar edge cases surface as `Unavailable`, not as invented times.**
  `AdhanPrayerTimesProviderTest` covers Tromsø on both solstices.
- **Prayer notifications are not implemented.** The extension point is
  `PrayerScheduleRepository`: a scheduler can depend on that interface directly and never needs
  the Home screen to be visible. Exact prayer-time alarms are out of scope for this phase, and a
  periodic WorkManager job is deliberately *not* used as a substitute.
- **UI strings are English only.** Bangla/Arabic script relies on platform fonts.
