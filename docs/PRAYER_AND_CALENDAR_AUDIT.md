# Salah — Prayer Times, Calendars and Ramadan: Implementation & Correctness Audit

**Audit type:** read-only implementation and correctness audit.
**Repository:** `Towkirahmedrony/prayer` (remote `https://github.com/towkirahmedrony/prayer.git`)
**Branch audited:** `main`
**Commit audited (HEAD):** `8dd3ee1b3da764ecfe9e3bac9cf563b0e27e996d` — *"Fix the selected-day card expectation to the day it actually shows"*
**Date of audit:** 14 October 2026
**Scope of change made by this audit:** one new file — this report. No production source, tests, Gradle files, settings, workflows or existing documentation were modified.

> This document describes **what the code does today**. It does not implement, fix, refactor or re-configure anything. Where a religious convention, angle or minute boundary had to be judged, the finding is flagged as *requiring an authority decision* instead of choosing one.

---

## 0. Repository verification

| Item | Observed value |
|---|---|
| Remote `origin` (fetch) | `https://github.com/towkirahmedrony/prayer.git` |
| Remote `origin` (push) | `https://github.com/towkirahmedrony/prayer.git` |
| Current branch | `main` (tracking `origin/main`) |
| HEAD before audit | `552e17719bcf10a1e377b410e20de07cde01ef10` — *"Salah Phase 5"*, Tue 13 Oct 2026 |
| Action taken | `git fetch origin` + `git pull --ff-only origin main` → fast-forward `552e177..8dd3ee1` (3 commits) |
| HEAD after pull | `8dd3ee1b3da764ecfe9e3bac9cf563b0e27e996d` |
| Working tree | **clean** — no uncommitted, staged or untracked changes before or after the pull |
| Stashes | none created; none existing to preserve |
| Repositories modified | none; no `reset`, `clean`, `stash`, `checkout` or `restore` was run |

The checkout is the correct repository (`Towkirahmedrony/prayer`), on `main`, and the pull was a fast-forward with zero local commits to reconcile — no user work was at risk.

**Architecture at a glance (as found):** single-module Android app (`:app`), Compose UI, Koin DI (`app/src/main/java/io/github/towkirahmedrony/salah/di/SalahModules.kt`), DataStore-only persistence, no database, **no network client of any kind**, layers `feature → core/domain → data`. 160 Kotlin source files (~25.1k LOC) and 65 test files with 744 `@Test` methods (~16.4k LOC).

---

## 1. Executive summary

**How prayer times are actually calculated.** Entirely on-device, by a single astronomical engine: the third-party library **Adhan Kotlin** (`com.batoulapps.adhan:adhan2:0.0.5`), wrapped by one class, `AdhanPrayerTimesProvider` (`data/prayer/AdhanPrayerTimesProvider.kt:52`). There is no API, no HTTP client, no account, and no `INTERNET` permission anywhere in the manifest — so there is no remote source that could disagree with the local one. Times are computed from a coordinate pair, a date and a `PrayerCalculationProfile`, then converted into the **prayer location's** time zone. Salah ships Karachi (18°/18°) angles with **Hanafi** Asr as the default profile, both user-changeable.

**How dates are determined.** Three completely independent implementations:

| Calendar | Implementation | Nature |
|---|---|---|
| Gregorian | `java.time` + the device clock, in the **device's** current zone | Real, always available |
| Bangladesh Bangla | Custom Kotlin arithmetic, `BanglaCalendar` (`core/domain/calendar/BanglaCalendar.kt`) implementing the **2019 Bangla Academy (Bangladesh civil) revision**: 6 months × 31 days, Kartik–Magh 30, Chaitra 30, Falgun 29 (30 in a Gregorian leap year), New Year fixed on **14 April** | Deterministic, offline, range-limited to 14 Apr 2019 – 31 Dec 2399 |
| Hijri | The JDK's own `java.time.chrono.HijrahDate` — the **Umm al-Qura** table shipped inside `java.base` | A fixed published table, not local moon sighting; labelled *estimated*, never *confirmed* |

Ramadan, Sehri and Iftar are **derived from those same two things**: the Ramadan window comes from the Hijri table, and Sehri is the calculated **Fajr** while Iftar is the calculated **Maghrib** — read out of the very schedule the Home screen shows, never recomputed.

**Does it work offline?** Yes — completely. Prayer times are local arithmetic, the Bangla calendar is local arithmetic, the Hijri table is inside the JDK, the Bangladesh geography is a bundled asset, and preferences are in DataStore. There is no code path that requires a network, and the manifest deliberately omits `INTERNET` (`AndroidManifest.xml:41-42`).

**The most important confirmed problems** (detail in §5):

1. **Nothing is aligned to any authoritative Bangladeshi timetable or ruling.** The default method/angles, the Hanafi Asr default and the three prohibited-window minute boundaries are Salah's own configuration choices with *no verified source*. This is a claim risk, not a code defect — but it is the single thing most likely to make a user believe the times match their mosque when they do not.
2. **Hijri and therefore Ramadan dates are Umm al-Qura estimates that can be a day off Bangladesh's announced dates**, and Salah has no mechanism to accept a confirmed date.
3. **The Home header's Gregorian date is computed in the device's time zone while the timetable is computed for the prayer location's time zone.** When the two differ, the date above the times can name a different civil day than the times below it.

**Deliberate non-features:** no Imsak, no dedicated prohibited-times screen, no manual timetable import, no remote provider, no schedule database.

---

## 2. Implementation inventory

| Feature | Actual implementation | Source / API / library | Local or remote | Offline behaviour | Verification status |
|---|---|---|---|---|---|
| **Prayer times** (Fajr, Dhuhr, Asr, Maghrib, Isha) | `AdhanPrayerTimesProvider.calculate()` builds `CalculationParameters` from a `PrayerCalculationProfile`, calls `PrayerTimes(...)`, maps to `PrayerSchedule` | Adhan Kotlin `com.batoulapps.adhan:adhan2:0.0.5` (`gradle/libs.versions.toml:36,60`; `app/build.gradle.kts:101`) | **Local** | Fully offline; pure arithmetic, run on `Dispatchers.Default` | **Verified** (code read; 12 engine tests incl. polar/DST/southern-hemisphere, `AdhanPrayerTimesProviderTest.kt`) |
| **Next prayer / countdown** | `NextPrayerResolver.resolve()` over the day's schedule; `CountdownFormatter.format()` renders `H:mm:ss` / `mm:ss` | Own Kotlin domain code | Local | Works offline; needs only a complete schedule for today | **Verified** (`NextPrayerResolverTest.kt`, `CountdownFormatterTest.kt`) |
| **Current prayer period** | `CurrentPrayerPeriodResolver`: Fajr→sunrise, Dhuhr→Asr, Asr→Maghrib, Maghrib→Isha, Isha→**next day's Fajr**; half-open `[start, end)` | Own Kotlin domain code | Local | Offline; needs tomorrow's Fajr only to close Isha | **Verified** (`CurrentPrayerPeriodResolverTest.kt`, 496 lines) |
| **Sunrise / sunset / solar noon** | A **second, offset-free** engine call (`adjustedParameters.copy(methodAdjustments = PrayerAdjustments(), prayerAdjustments = PrayerAdjustments())`) | Adhan Kotlin, same version | Local | Offline | **Verified** — asserted independent of calculation method (`AdhanPrayerTimesProviderTest.kt:126-139`) |
| **Prohibited prayer times** | `ProhibitedPeriodCalculator` applies a `ProhibitedRuleSet` to the raw solar events; policy = `ConfiguredProhibitedRuleSet.DEFAULT` (sunrise +15 min; transit −5/+10 min; sunset −15 min) | Own Kotlin domain code; **rule set is application policy, `sources = emptyList()`** | Local | Offline; derived from the day's schedule | **Verified as implemented**; **Unverified as religiously authorised** |
| **Gregorian calendar** | Device clock → `LocalDate` in `ZoneId.systemDefault()`; `java.time.format` with an explicit locale | `java.time` (JDK/Android) | Local | Offline | **Verified** |
| **Bangladesh Bangla calendar** | Custom conversion in `BanglaCalendar` (2019 Bangladesh civil rules, fixed 14 April New Year, Falgun leap rule) | Own Kotlin arithmetic — **not** Android locale APIs, **not** a third-party library, **not** a remote API, **not** a static dataset | Local | Fully offline; pure arithmetic over `LocalDate` | **Partially Verified** — rules and reference dates are documented and unit-tested; no comparison against an official year-long almanac |
| **Hijri calendar** | `HijriCalendar` over `HijrahDate.from(...)`; month length and boundaries read from the same table; ±2-day user offset | `java.time.chrono.HijrahChronology` (**Umm al-Qura**, `java.base`) | Local | Offline; range-limited to the platform table (≈AH 1300–1600) | **Verified** as table conversion; **Unverified** as Bangladesh-announced dates (explicitly labelled an estimate) |
| **Ramadan / Sehri / Iftar** | `RamadanCalendar.windowFor()` (Hijri table) + `sehriEndOf(schedule) = Fajr`, `iftarOf(schedule) = Maghrib`; one `PrayerSchedule` per date loaded from the shared repository | Own Kotlin domain code + Adhan Kotlin + JDK Hijri | Local | Fully offline (window from the JDK table, times from local arithmetic) | **Verified as implemented**; Imsak **not implemented** |
| **Location & time zone** | `DataStoreLocationRepository` (single source of truth) + `BangladeshLocationPolicy` (BD-only) + bundled COD-AB dataset + `AreaResolver` | Bundled TSV assets (`assets/location/bd_areas.tsv`, `bd_boundary.tsv`), 586 + 1,682 rows | Local | Offline incl. GPS-denied path (manual area / manual coordinates) | **Verified** |
| **Prayer notifications** | `PrayerNotificationPlanner` (pure) + `PrayerNotificationCoordinator` (single reconciliation entry point) on `AlarmManager` | Own Kotlin + Android `AlarmManager` | Local | Offline; schedules from the same repository the UI uses | **Verified** (`PrayerNotificationPlannerTest.kt`, `PrayerNotificationCoordinatorTest.kt`) |

**Dependency inventory (prayer/calendar relevant):** the only astronomy dependency is `com.batoulapps.adhan:adhan2:0.0.5`, deliberately pinned because 0.0.6+ are compiled for a JVM target of 21 (`gradle/libs.versions.toml:29-36`). Calendar conversion adds **no** dependency — the Hijri side uses `java.base` **by design** (`HijriCalendar.kt:16-24`). There is no Calendar/ICU/Threeten-Extra/Bengali-calendar library in the build.

**No external API is used anywhere.** Repo-wide search for `http`, `retrofit`, `okhttp`, `ktor` and `api.` returns only one comment in `PrayerTimesProvider.kt:41`. The provider interface explicitly lists a remote provider as **not implemented** (`PrayerTimesProvider.kt:48-50`), and `PrayerTimeSource.REMOTE_API` / `USER_ENTERED_TIMETABLE` exist as enum values that nothing produces (`DataSourceInfo.kt:12-24`).

---

## 3. Evidence-based findings

Every finding below cites the file, the class/function/property, and the line range where the behaviour is observable in the source.

### 3.1 Prayer time calculation engine

**F1 — There is exactly one calculation engine, and it is Adhan Kotlin.**
`AdhanPrayerTimesProvider` (`data/prayer/AdhanPrayerTimesProvider.kt:52-198`) is the only `PrayerTimesProvider` implementation and the only binding in DI (`SalahModules.kt:173`). Its declared identity, source and offline flag are `local-adhan-kotlin` / `PrayerTimeSource.LOCAL_CALCULATION` / `isAvailableOffline = true` (`AdhanPrayerTimesProvider.kt:56-62`). `availability()` always returns `CONFIGURED` (line 62), so the repository's "not configured" branch (`ProviderBackedPrayerScheduleRepository.kt:68-74`) is unreachable in this build.
*Why it matters:* there is no second engine and no API, so Home, the Ramadan table, and the notifications cannot disagree about a prayer time — they all read the same `PrayerSchedule`.
*Confidence:* **Verified.**

**F2 — The engine consumes a coordinate pair, a Gregorian date and a profile; nothing else.**
`calculate()` reads `request.location`, `request.date`, `request.profile` (`AdhanPrayerTimesProvider.kt:68-96`). `LocalDate` → `DateComponents(year, monthValue, dayOfMonth)` (lines 179-180). No device zone, no clock other than the `generatedAt` stamp (line 116), no locale.
*Confidence:* **Verified.**

**F3 — Two engine calls, so solar events are never aliases of a prayer.**
`prayerTimes` is computed with the profile's method **and** prayer adjustments; `rawTimes` is computed with `methodAdjustments` and `prayerAdjustments` both replaced by an empty `PrayerAdjustments()` (lines 91-96). Sunrise/solar-noon/sunset are then published **only** from `rawTimes` (lines 111-115).
*Why it matters:* a method that offsets sunrise or Maghrib (e.g. Dubai: sunrise −3, Maghrib +3) cannot move a prohibited window, which is anchored to those raw events.
*Confidence:* **Verified** — and asserted: `AdhanPrayerTimesProviderTest.kt:126-139` proves sunrise/sunset are byte-identical across Karachi and Dubai while Maghrib moves.

**F4 — Every published instant is converted into the prayer location's zone, never the device's.**
`Long.atZone(zone)` uses `ZonedDateTime.ofInstant(Instant.ofEpochMilli(this), zone)` with `zone = location.zoneId` (lines 98, 182-184). `PrayerSchedule.zoneId` is `location.zoneId` (`core/model/PrayerSchedule.kt:38`).
*Confidence:* **Verified** — asserted across a DST transition with a UTC test clock (`AdhanPrayerTimesProviderTest.kt:226-247`).

**F5 — The provider refuses rather than guesses.**
Out-of-range coordinates → `Unavailable(CALCULATION_NOT_POSSIBLE)` (lines 70-77); an engine `IllegalStateException` (polar day/night) → `Unavailable(CALCULATION_NOT_POSSIBLE)` with a `POLAR_DETAIL` explanation (lines 82-89, 189-192).
*Confidence:* **Verified** — Tromo… Tromsø on both solstices asserted (`AdhanPrayerTimesProviderTest.kt:206-223`).

### 3.2 Calculation parameters

**F6 — The default profile is Karachi 18°/18° with Hanafi Asr and `AUTOMATIC` high-latitude handling.**
`PrayerCalculationMethod.DEFAULT = KARACHI` with `KARACHI(18.0, 18.0)` (`core/model/PrayerCalculationMethod.kt:25, 67`); `PrayerCalculationProfile.asrMethod` defaults to `AsrCalculationMethod.HANAFI` (`PrayerCalculationProfile.kt:46`, mirrored in `PrayerSettings.kt:26`); `highLatitudeRule` defaults to `AUTOMATIC` (`PrayerCalculationProfile.kt:47`).
The class documentation states plainly that this is "only a configurable default… **not** a verified Islamic Foundation Bangladesh timetable and is never labelled as an official source" (`PrayerCalculationProfile.kt:54-63`).
*Confidence:* **Verified as implemented.** The *appropriateness* of Karachi 18/18 + Hanafi for Bangladesh is **Unverified** — no comparison against a published timetable exists anywhere in the repository.

**F7 — Calculation parameters are per-user, persisted, and passed through unchanged.**
`PrayerSettings` is one flat immutable object (`core/model/PrayerSettings.kt:24-78`) persisted as a unit (`data/preferences/DataStoreUserPreferencesRepository.kt:71-89`, keys at `:153-165`); `toProfile()` maps it to the engine's input (`PrayerSettings.kt:60-65`). The engine maps method identity to `AdhanCalculationMethod` one-to-one and explicitly does not re-declare angles (`AdhanPrayerTimesProvider.kt:147-159`), consistent with the enum's own note that "the engine stays the single source of truth for the arithmetic" (`PrayerCalculationMethod.kt:6-14`).
*Confidence:* **Verified** (`DataStoreUserPreferencesRepositoryTest.kt`, `AdhanPrayerTimesProviderTest.kt:269-277`).

**F8 — Asr madhhab is functional and affects only Asr.**
`AsrCalculationMethod.STANDARD → Madhab.SHAFI`, `HANAFI → Madhab.HANAFI` (`AdhanPrayerTimesProvider.kt:161-164`).
*Confidence:* **Verified** — 15:58 Hanafi vs 15:06 standard for Dhaka 2026-10-11, with every other prayer and all solar events unchanged (`AdhanPrayerTimesProviderTest.kt:142-160`).

**F9 — Manual per-prayer adjustments apply to the five prayers only, clamped to ±60 minutes.**
`prayerAdjustments = PrayerAdjustments(fajr, dhuhr, asr, maghrib, isha)` (lines 138-144); sunrise deliberately has no entry (comment at lines 136-137). The clamp lives in the model: `MAX_ADJUSTMENT_MINUTES = 60`, `clampAdjustment()` (`PrayerSettings.kt:69-73`), applied on write and on read (`DataStoreUserPreferencesRepository.kt:104-105`, `:147-148`).
*Confidence:* **Verified** (`AdhanPrayerTimesProviderTest.kt:175-203`; `PrayerSettingsTest.kt`).

**F10 — High-latitude handling exists but is inert at Bangladeshi latitudes.**
The four `HighLatitudeRule` values map to the engine's rule, with `AUTOMATIC → null` meaning "let the engine choose for this latitude" (`AdhanPrayerTimesProvider.kt:166-177`). The model documents that at Bangladesh's latitude "this rule is never reached" (`PrayerSettings.kt:27-31`).
*Confidence:* **Verified as implemented.**

**F11 — Time zone, date boundary and rounding.**
The calculation day is the date *at the location*: `date = at.withZoneSameInstant(location.zoneId).toLocalDate()` (`feature/home/HomeViewModel.kt:152-159`), and the same rule is used for tomorrow (`:201-209`). There is no DST in `Asia/Dhaka`; for a manual entry the zone is whatever the user typed, so other zones are reachable (see F22, F30). The engine truncates each event to a whole minute, and the test suite documents that there is therefore no sub-minute tolerance to apply (`AdhanPrayerTimesProviderTest.kt:41-45`).
*Confidence:* **Verified** for Asia/Dhaka; **Partially Verified** for the general rounding policy — Salah applies no rounding of its own and the engine's truncation is only observable through its own output.

### 3.3 Next prayer, current period and countdown

**F12 — The current period convention is explicit, half-open, and Isha closes at tomorrow's Fajr.**
`CurrentPrayerPeriodResolver.resolve()` (`core/domain/CurrentPrayerPeriodResolver.kt:122-268`): before Fajr the *previous* day's Isha period is reported with `startsAt = null` (lines 168-180); Fajr closes at sunrise and the post-sunrise morning is `Between` (lines 182-209); Dhuhr→Asr, Asr→Maghrib, Maghrib→Isha are plain period boundaries (lines 211-245); Isha's closing boundary is `nextDayFajr`, supplied by Home from the *same repository* (lines 247-267; `HomeViewModel.kt:201-232`). Incomplete, duplicate or out-of-order times are rejected as `Unavailable` rather than interpreted (lines 130-166).
*Why it matters:* the "one engine" property holds here too — the Isha boundary is not a second calculation.
*Confidence:* **Verified** (`CurrentPrayerPeriodResolverTest.kt`, 496 lines).

**F13 — The "next prayer" resolver stops at the end of the day.**
`NextPrayerResolver.resolve()` returns `null` when the schedule's local date is not today's (lines 32-33) and `null` when no prayer is still ahead (lines 35-38), using `isAfter` so that at the exact instant a prayer begins the *following* prayer is returned. Its own KDoc says "After the last prayer of the day, `null` is returned; the next day's schedule is a separate calculation and is not guessed here" (`NextPrayerResolver.kt:22-24`).
**Consequence:** after Isha the Home "next prayer" section is empty (`HomeUiStateFactory.kt:90-95`), even though the view model already holds tomorrow's Fajr for the very same purpose (`HomeViewModel.kt:224-226`, consumed at `HomeUiStateFactory.kt:66`). See §5 H-2.
*Confidence:* **Verified.**

**F14 — The countdown is recomputed from one shared 1 Hz tick, so both countdowns agree.**
`moment` is a `StateFlow<ZonedDateTime>` ticking every 1,000 ms with the zone re-read each tick (`HomeViewModel.kt:140-149`); the request, the schedule, the prohibited windows and both countdowns are all derived from it. The tick runs only while Home is subscribed (`SharingStarted.WhileSubscribed(5_000)`), so nothing ticks in the background.
`CountdownFormatter.format()` truncates to whole seconds and clamps negatives to `0:00` (`CountdownFormatter.kt:20-29`).
*Why it matters:* returning from the background re-subscribes the flow and re-derives the request; there is no cached "now" and no WorkManager involvement. There is no explicit `ON_RESUME` hook — the mechanism is flow re-subscription plus the 1 Hz tick.
*Confidence:* **Verified** (code read); the *return-from-background* path is **Partially Verified** — no instrumented/lifecycle test exists.

**F15 — Stale-cache and cross-request protection is explicit and equality-based.**
`ProviderBackedPrayerScheduleRepository` keys an in-memory `LinkedHashMap` by the **full request** (date + location + profile) and re-serves an entry only when `date`, `location` and `profile` all match (`data/prayer/ProviderBackedPrayerScheduleRepository.kt:119-130`). A provider answer for another day or city is refused as a failure (lines 87-99), and a schedule is stored only under the exact request that produced it so a slow response cannot overwrite a newer one (lines 100-102). The cache is bounded to 4 entries (lines 164-172) and there is **no database** — the reasoning is documented at lines 38-42.
*Confidence:* **Verified** (`ProviderBackedPrayerScheduleRepositoryTest.kt`).

### 3.4 Location and time zone

**F16 — There is exactly one location store, and no silent default.**
`DataStoreLocationRepository` is the only `LocationRepository` binding (`SalahModules.kt:118`) and holds label, latitude, longitude, zone, origin, accuracy, acquisition time, country code and the area hierarchy in one DataStore file (`data/location/DataStoreLocationRepository.kt:44-255`). With nothing stored, the repository emits `null`, `LocationSetup.evaluate()` returns `Required` and the app shows the setup screen rather than a guessed city (`core/domain/location/LocationSetup.kt:88-97`; `feature/app/AppViewModel.kt:78-93`).
*Why it matters:* Home, Ramadan, Qibla and the notification coordinator all read the same flow — `startup.storedLocation` (`HomeViewModel.kt:115`), `locationRepository.location` (`RamadanViewModel.kt:80`), `locations.location.first()` (`PrayerNotificationCoordinator.kt:110`).
*Confidence:* **Verified.**

**F17 — Manually selected areas really do use the selected area's published coordinate.**
`saveSelectedArea()` requires a complete division→district→upazila hierarchy, then writes `area.latitude` / `area.longitude` from the dataset entry (`feature/location/LocationViewModel.kt:342-363`, via `AreaSelection.resolvedArea()` → `dataset.area(upazilaId)`, `core/domain/location/AreaSelection.kt:112-113`). The three-level requirement is enforced in the state itself (`AreaSelection.isComplete`, lines 43-44; invalid ids are ignored, lines 57-81). The label written is the hierarchy's label (`LocationViewModel.kt:360`).
*Why it matters:* the area name is not merely decorative — the coordinate that drives the calculation is the one displayed next to it (`LocationViewModel.kt:522-524`).
*Confidence:* **Verified.**

**F18 — The bundled geography is a generated, auditable dataset.**
Two TSV assets (`assets/location/bd_areas.tsv`, 586 lines; `bd_boundary.tsv`, 1,682 lines) generated by `tools/generate_bd_location_dataset.py` from OCHA/HDX COD-AB BGD v03 (documented in `BangladeshLocationDataset.kt:11-15`). Parsing rejects malformed lines into a bounded `rejectedLines` list instead of crashing (`BangladeshLocationDataset.kt:128-157`); `isUsable` requires all three levels **and** a boundary (`:55-57`). Version is pinned in code rather than read from the file (`AssetBangladeshAreaCatalog.kt:101-109`).
*Confidence:* **Verified** (`BundledDatasetIntegrityTest.kt`, `BangladeshLocationDatasetTest.kt`).

**F19 — GPS acquisition is permission-gated, timed out, and never auto-saved on the location screen.**
`useCurrentLocation()` guards against concurrent acquisition and clears the acquiring flag on every outcome (`LocationViewModel.kt:147-181`); the timeout is 20 s (`:563`). On the location screen the fix is held as a `PendingDeviceFix` until the user confirms (`:246-271`); only the first-launch screen passes `saveOnArrival = true` (`:136-146`). Permission denial, services-disabled, timeout and provider failure each map to a specific message (`:169-175`).
*Confidence:* **Verified** (`LocationViewModelTest.kt`, `LocationPermissionPolicyTest.kt`).

**F20 — The time zone is `Asia/Dhaka`, not the device zone, for every area or device-fix location.**
`BangladeshLocationPolicy.TIME_ZONE_ID = "Asia/Dhaka"` (`core/domain/location/BangladeshLocationPolicy.kt:93`), used by `saveAreaLocation` (`DataStoreLocationRepository.kt:113`), by the location screen's device fix (`LocationViewModel.kt:284-287`) and by the Qibla screen's adopted fix (`feature/qibla/QiblaViewModel.kt` — `timeZoneId = BangladeshLocationPolicy.TIME_ZONE_ID`).
*Why it matters:* a user selecting Sylhet while travelling abroad still gets Sylhet's times.
*Confidence:* **Verified.**

**F21 — A device-fix path exists outside the location screen, and it is deliberately stricter.**
The Qibla screen writes the app's single location too (`QiblaViewModel.storeDeviceLocation`), gated by `DeviceFixAdoptionPolicy.decide()` (`core/domain/location/DeviceFixAdoptionPolicy.kt:74-91`), which shares `BangladeshLocationPolicy` and `AreaResolver` with the location screen. It refuses a fix that is only within the boundary tolerance and refuses one that cannot be attributed to an area — both cases the setup flow tolerates — because there is no confirmation step (`DeviceFixAdoptionPolicy.kt:47-70`). In both refusal cases the stored location is left untouched.
*Confidence:* **Verified** (`DeviceFixAdoptionPolicyTest.kt`, `QiblaViewModelTest.kt`).

**F22 — Manual coordinates bypass the Bangladesh-only check and may carry any IANA zone.**
`LocationViewModel.saveManualLocation()` calls `locations.saveManualLocation(label, lat, lon, timeZoneId = form.timeZoneId.trim())` with no country check at all (`LocationViewModel.kt:400-446`; documented as intentional at lines 400-413). `LocationFormState.blankWithSuggestedZone(deviceZoneId)` pre-fills the **device** zone (`feature/common/LocationFormState.kt:44-53`), and the Qibla screen can re-suggest the device zone (`QiblaViewModel.kt` — `suggestDeviceZone()` uses `ZoneId.systemDefault().id`).
*Why it matters:* this is the one path by which the prayer engine can be pointed at a non-Bangladesh coordinate and a non-`Asia/Dhaka` zone. See §5 M-3.
*Confidence:* **Verified.**

**F23 — Country verification degrades safely but not identically on both screens.**
`BangladeshLocationPolicy.check()` returns four distinct outcomes — `Inside`, `WithinBoundaryTolerance(5 km)`, `Outside(distance)`, `BoundaryUnavailable` (`BangladeshLocationPolicy.kt:11-44, 119-130`); the tolerance is explicitly *not* described as containment (lines 95-106). The location screen's acceptance condition is `if (!verified && (!insideCoarseBounds || rejectionDistance != null))` (`LocationViewModel.kt:214`), so when the dataset failed to load but the coordinate is inside the coarse rectangle (`lat 20–27, lon 87.5–93`), the fix is **accepted without boundary verification** and stored with `countryCode = null` (lines 246-254). This is described in the source as intentional (lines 206-208) and `LocationSetup` corroborates the policy (`LocationSetup.kt:108-124`).
*Confidence:* **Verified** as code behaviour; whether the risk is acceptable is a product decision → §5 M-4.

**F24 — Location freshness distinguishes a declaration from an observation.**
`LocationFreshness.of()` returns `DECLARED` for anything that is not a device fix (a typed coordinate or a selected area does not decay), and `FRESH`/`RECENT`/`STALE`/`UNKNOWN` for fixes against 10-minute and 24-hour windows, treating a future timestamp as fresh rather than as an error (`core/domain/location/LocationFreshness.kt:15-72`).
*Confidence:* **Verified** (`LocationFreshnessTest.kt`).

### 3.5 Calendar systems

**F25 — The Gregorian date is the device clock read in the device's zone.**
`DeviceClockCalendarRepository.todayAt()` → `instant.atZone(ZoneId.systemDefault()).toLocalDate()` (`data/calendar/DeviceClockCalendarRepository.kt:199-200`); the calendar view model does the same for its displayed month (`feature/calendar/CalendarViewModel.kt:147`). The choice of the **device** zone for a calendar (versus the location zone for prayer times) is deliberate and documented at `DeviceClockCalendarRepository.kt:42-47`.
*Why it matters:* the calendar is a civil artifact of where the user is, while prayer times are astronomical for where they pray — but the two are combined on one Home screen, which is the source of H-1.
*Confidence:* **Verified.**

**F26 — The Bangladesh Bangla calendar is a custom implementation of the 2019 Bangladesh civil rules.**
`BanglaCalendar` (`core/domain/calendar/BanglaCalendar.kt:62-170`) implements: 14 April fixed New Year (`NEW_YEAR_MONTH = 4`, `NEW_YEAR_DAY = 14`, lines 157-159); month lengths `31,31,31,31,31,31,30,30,30,30,29,30` (line 168); Falgun 30 days when `Year.isLeap(banglaYear + 594)` (lines 123-126); era offset 593 (line 162); supported range 2019-04-14 → 2399-12-31 (lines 145-155), outside which `convert()` returns `null` (lines 71-72).
The class header states explicitly that this is the **Bangladesh** calendar as revised by the Bangla Academy in 2019, *not* the West Bengal Bengali calendar, and that the two differ in both month lengths and the New Year date (lines 12-26).
*Why it matters:* this answers "is the Bangla calendar based on Bangladesh's civil rules?" — **yes, explicitly and only for dates from 14 April 2019 onward**, and the pre-2019 rules (Ashwin 30 days) are *not* applied retroactively: the app reports out-of-range instead (lines 45-58).
*Confidence:* **Partially Verified.** The rule set and arithmetic are internally consistent, the round-trip is exhaustively tested for a full year, and the reference dates are cited to published sources (`BanglaCalendarTest.kt:13-29`, citing The Daily Star and Dhaka Tribune). What is **not** verified is that the output matches an official Bangladeshi almanac for arbitrary dates — no such comparison exists, and the report makes no claim that it does.

**F27 — Leap years and month boundaries are handled by construction, with one honest, documented gap.**
Falgun absorbs the leap day so that Chaitra still begins 15 March and 1 Boishakh stays 14 April in every year (`BanglaCalendar.kt:21-28, 117-126`; asserted at `BanglaCalendarTest.kt:87-96`). The range stops at 31 Dec 2399 precisely because the published Bangladeshi leap-year wording ("divisible by four and not by 100") and the Gregorian rule disagree for years divisible by 400, and nothing is guessed in the gap (lines 52-58).
*Confidence:* **Verified** (deliberate limitation, documented and tested at `BanglaCalendarTest.kt:147-159`).

**F28 — The displayed Bangla date is genuinely calculated, never formatted from another calendar.**
`DeviceClockCalendarRepository.banglaDateState()` calls `banglaCalendar.convert(date)` and formats the result through `CalendarFormatting.bangla()` (`DeviceClockCalendarRepository.kt:164-177`; `CalendarFormatting.kt:162-171`). Day numbers and month names are composed from `BanglaMonth`'s own `englishName`/`bengaliName` plus an explicit numeral table (`core/model/CalendarModels.kt:20-33`; `CalendarFormatting.kt:230-244`). A date the converter cannot represent becomes `CalendarDateState.NotAvailable(OUT_OF_SUPPORTED_RANGE)` with an explanation, never a Gregorian fallback (`DeviceClockCalendarRepository.kt:165-169`; `CalendarMonthResolver.kt:81-89` refuses a month that runs past the range).
*Confidence:* **Verified.**

**F29 — The Hijri date is the platform's Umm al-Qura table.**
`HijriCalendar.dateOf()` calls `HijrahDate.from(gregorian)` and reads month/year/day from it; the user offset is applied with `base.plus(days, ChronoUnit.DAYS)` **before** reading, so month and year transitions move with it (`core/domain/calendar/HijriCalendar.kt:56-75`). `monthStart`, `monthLength` and `ramadanWindow` all read the same table, so a 29-day month stays 29 (`HijriCalendar.kt:84-130`, `:95-98`). It is **not** an astronomical (moon-position) calculation, **not** a remote API, and **not** a Bangladesh dataset. Outside the table the conversion returns `null` and the UI reports out-of-range (`DeviceClockCalendarRepository.kt:184-189`).
*Confidence:* **Verified** (`HijriCalendarTest.kt`, incl. both month boundaries and the table edges AH 1300 / 1600).

**F30 — Hijri dates can never be officially confirmed by this app, and the code says so in the type system.**
`HijriDateSource` has exactly two values, `ESTIMATED_UMM_AL_QURA` and `ESTIMATED_USER_ADJUSTED` (`core/model/CalendarModels.kt:93-99`); the enum's own documentation states that if an authority source is ever integrated "this enum gains a third constant and only that source may set it" (lines 85-92). The Home snapshot hard-codes `isVerifiedSource = false` for Hijri with the comment "Never true: this is an Umm al-Qura estimate, not a moon-sighting announcement" (`DeviceClockCalendarRepository.kt:190-196`). The Bangla and Gregorian entries are set to `true` (lines 104-108, 170-176).
*Why it matters:* there is **no** Bangladesh-specific moon-sighting confirmation mechanism, and the absence is explicit rather than accidental.
*Confidence:* **Verified.**

**F31 — The user can shift the Hijri date by ±2 days, and Ramadan/reminders follow it.**
`CalendarSettings.hijriAdjustmentDays` with `MAX_HIJRI_ADJUSTMENT_DAYS = 2`, clamped on write and on read (`CalendarModels.kt:166-186`; `DataStoreUserPreferencesRepository.kt:104-106, :121-123`). The offset flows into `dateOf`, `monthStart` and `ramadanWindow` consistently, and moving it triggers a notification reconciliation (`feature/calendar/CalendarSettingsViewModel.kt:56-72`).
*Confidence:* **Verified** (`HijriCalendarTest.kt:127-152`; `CalendarSettingsViewModelTest.kt`).

**F32 — Hijri month names follow the interface language, not Arabic (fixed at HEAD).**
`CalendarFormatting.hijriMonthName()` returns Arabic **only** when the rendering locale is genuinely Arabic (`"ar"`), otherwise the Bengali or English name (`CalendarFormatting.kt:201-205`); `gridLocale()` sends the Gregorian **and Hijri** grids through `localeFor(dateLanguage, systemDefault)`, where `SYSTEM` resolves to the interface language, and pins Bengali only for the Bangla mode (`CalendarFormatting.kt:98-106`). `AppLanguage` offers only `SYSTEM`, `ENGLISH` and `BANGLA` (`core/model/AppLanguage.kt:29-39`) — there is no Arabic option — so with the app in English or Bengali the Hijri month is English or Bengali, and `arabicName` is reachable only on a device whose own locale is Arabic.
**Why this question arose:** the *previous* behaviour was different — commit `9509ec1` *"Render Hijri dates in the app's language instead of always Arabic"* records that "the Hijri grid and the Ramadan table asked the formatter for an Arabic locale no matter which language was selected". That commit is the immediate parent of HEAD, so the Arabic-forcing behaviour was still present one commit before the audited revision.
**This is a formatting/localisation matter, not a date-calculation matter:** the conversion, the ±2-day offset and the Ramadan window are untouched by it (`CalendarFormattingTest.kt:186-190` still asserts the Arabic script is reachable for an Arabic locale).
*Confidence:* **Verified** for the current revision.

**F33 — Ramadan dates inherit every property and every limitation of the Hijri estimate.**
`RamadanWindow` carries its `HijriDateSource` (`CalendarModels.kt:257-265`); the range label is built from Gregorian dates, and the screen shows the source (`feature/calendar/CalendarUiStateFactory.kt:138-157`). The 60-day "upcoming" window is the only proximity rule (`CalendarModels.kt:287-296`).
*Confidence:* **Verified.**

### 3.6 Prohibited prayer times

**F34 — All three windows are implemented, as three distinct rules with separate parameters.**
`ProhibitedWindowKind` = `SUNRISE_ASCENT`, `ZENITH_DECLINE`, `SUNSET_SETTING`, each carrying its own anchor event (`core/domain/prohibited/ProhibitedRules.kt:13-38`). `ProhibitedPeriodCalculator.calculate()` builds each window from an explicit rule-set parameter — sunrise +`sunriseAscentMinutes`, transit −`zenithBeforeMinutes`/+`zenithAfterMinutes`, sunset −`sunsetSettingMinutes` (`ProhibitedPeriodCalculator.kt:44-61`). There is no hardcoded duration in the calculation path (lines 11-15), and a rule set that is not applicable throws rather than silently under-reporting (lines 36-38); `ProhibitedRuleSet.windowKinds` is derived from which parameters are positive (`ProhibitedRules.kt:91-95`).
*Confidence:* **Verified** (`ProhibitedPeriodCalculatorTest.kt`).

**F35 — The windows are anchored to the raw solar events, never to prayer times.**
`schedule.requireSolarEvent(SUNRISE / SOLAR_NOON / SUNSET)` (`ProhibitedPeriodCalculator.kt:45-60, 79-82`). The rule set has no anchor to Fajr or Maghrib at all (`ProhibitedRules.kt:25-37`), and the calculator asserts every window is expressed in the schedule's zone (lines 69-76).
*Why it matters:* a method offset that moves Maghrib (or the 15-minute manual Asr adjustment) cannot move a prohibited interval.
*Confidence:* **Verified** (`ProhibitedPeriodCalculatorTest.kt`, `AdhanPrayerTimesProviderTest.kt:126-139`).

**F36 — The policy in force is the application's own, and it is disclosed as such.**
`ConfiguredProhibitedRuleSet.DEFAULT`: sunrise ascent **+15 min**, zenith **−5 / +10 min**, sunset setting **−15 min**, `verification = CONFIGURED`, `sources = emptyList()` (`core/domain/prohibited/ConfiguredProhibitedRuleSet.kt:57-66`). The rule book filters out anything `UNREVIEWED`, so an unconfigured rule set can never reach the calculator (`ProhibitedRules.kt:116-135`). The convention travels to the UI as a `ProhibitedConvention` carrying `isReviewed` and `hasVerifiedSources` (`core/model/ProhibitedPeriod.kt:36-44`), and the screens state which policy is in force and that disagreements exist (`ConfiguredProhibitedRuleSet.kt:8-25`).
*Why it matters:* the three intervals are **not** presented as a confirmed religious ruling. The sunrise and sunset windows happen to share the 15-minute magnitude, and the source explicitly says they are "**not** derived from one another" (lines 45-49).
*Confidence:* **Verified as implemented.** The minute values themselves are **Unverified** — `sources` is empty and no reference is cited anywhere in the repository. Flagged for an authority decision in §6.

**F37 — The three boundary conventions are not identical and are not presented as such.**
The zenith window is deliberately asymmetric, and the five minutes *before* the transit are described as "a precautionary margin around a calculated instant, not a claim that the time before the meridian is itself prohibited" (`ConfiguredProhibitedRuleSet.kt:50-55`).
*Confidence:* **Partially Verified** — the implementation matches the stated policy; whether the policy matches any school's ruling is outside what this repository evidences.

**F38 — Active/upcoming/passed classification is half-open, satisfying the `[start, end)` convention used by prayer periods.**
`ProhibitedPeriodStatusResolver.classify()`: before start → `UPCOMING` with `startsIn`; before end → `ACTIVE` with `endsIn`; otherwise `PASSED` (`ProhibitedPeriodStatusResolver.kt:57-80`). The card prefers `startsIn` while upcoming and `endsIn` while active (`HomeUiStateFactory.kt:308-331`).
*Confidence:* **Verified** (`ProhibitedPeriodStatusResolverTest.kt`, 442 lines).

**F39 — The intervals are not user-configurable, and there is no dedicated screen.**
There is no setting anywhere that reaches `ProhibitedRuleBook` — DI wires the constant `ConfiguredProhibitedRuleSet.ruleBook()` (`SalahModules.kt:185`), and no preference key exists for it (`DataStoreUserPreferencesRepository.kt:153-165`). Home renders the intervals inside `ForbiddenTimesCard` and `View details` opens the prayer-day destination (`feature/home/HomeScreen.kt:120-131`); the source says a dedicated screen "would be a design decision, not a data binding".
*Confidence:* **Verified.**

### 3.7 Ramadan, Sehri and Iftar

**F40 — Ramadan's start and end come from the Hijri table, with the month's own length.**
`HijriCalendar.ramadanWindow(hijriYear, adjustmentDays)` reads the first day of month 9 and `firstOfRamadan.lengthOfMonth()` (`HijriCalendar.kt:111-130`); AH 1445 gives 11 Mar – 9 Apr 2024 (30 days) and AH 1446 gives 1 Mar – 29 Mar 2025 (29 days) (`HijriCalendarTest.kt:98-111`). `ramadanWindowFor()` returns the containing window or the next one, and `null` when the table does not cover it (`HijriCalendar.kt:139-146`).
*Confidence:* **Verified.**

**F41 — Ramadan day numbers are positional within the window.**
`RamadanWindow.dayNumber(date)` = `(date.toEpochDay() − start.toEpochDay()) + 1`, `null` outside (`CalendarModels.kt:276-278`). The window is carried in the month snapshot so a day's number can never disagree with the range shown beside it (`DeviceClockCalendarRepository.kt:123-125, 147-162`).
*Confidence:* **Verified.**

**F42 — Sehri is the calculated Fajr; Iftar is the calculated Maghrib. Neither is recomputed, and no buffer is invented.**
`RamadanCalendar.sehriEndOf(schedule) = schedule.timeOf(FAJR)`, `iftarOf(schedule) = schedule.timeOf(MAGHRIB)` (`core/domain/calendar/RamadanCalendar.kt:54-65`). The class states there is "**no invented buffer**" and that an arbitrary reduction would be a religious claim Salah cannot make (lines 19-23). `RamadanUiStateFactory` reads the times from the schedule the Home screen uses (`feature/ramadan/RamadanUiStateFactory.kt:94-107`), and the Ramadan view model loads one schedule per window date from the **same** `PrayerScheduleRepository` (`RamadanViewModel.kt:65-69, 207-218`).
*Confidence:* **Verified** (`RamadanCalendarTest.kt`, `RamadanUiStateFactoryTest.kt`).

**F43 — Imsak is not implemented.**
A repository-wide search for `imsak` (source and docs, case-insensitive) returns **zero** matches. Sehri and Iftar are the only two Ramadan boundaries, and the settings model has no Imsak field (`core/model/PrayerNotificationSettings.kt:60-118`). A missing Fajr (polar case) yields no Sehri time rather than a guess (`RamadanCalendar.kt:55-61`).
*Confidence:* **Verified absence.**

**F44 — Sehri/Iftar reminders are lead-in based and reuse the existing notification machinery.**
`planner.ramadanReminders()` schedules a Sehri reminder **only as an advance** before Fajr (an at-time one would collide with the Fajr reminder) and an Iftar reminder at Maghrib plus an optional lead-in (`PrayerNotificationPlanner.kt:143-148, 177-223`). Sound and vibration are inherited from Fajr/Maghrib respectively (lines 169-175), so no new channel is created. Lead choices are 5–30 min for Sehri and 0–30 min for Iftar, with `0` meaning "at Iftar only" (`PrayerNotificationSettings.kt:171-184`); both are off by default (lines 82-117).
*Confidence:* **Verified** (`RamadanReminderPlanTest.kt`, `RamadanReminderCoordinatorTest.kt`).

**F45 — Which dates are "Ramadan" is decided once, from the stored Hijri offset, at reconciliation time.**
`PrayerNotificationCoordinator` builds `ramadanDates` from `ramadanCalendar.isRamadan(date, calendarSettings.hijriAdjustmentDays)` (`PrayerNotificationCoordinator.kt:142-146`) and hands the set to the pure planner. Because the cancellation set is *derived* (the full `(date, target, kind)` cross-product over a widened date range, lines 240-243) rather than remembered, ending Ramadan — or moving the offset — removes the Sehri/Iftar alarms that no longer belong (lines 155-170).
*Confidence:* **Verified** (`PrayerNotificationCoordinatorTest.kt`, `RamadanReminderCoordinatorTest.kt`).

### 3.8 Offline-first, data flow and consistency

**F46 — The authoritative sources of truth are: DataStore for user state, the bundled assets for geography, and derived calculation for everything else.**
Location (one DataStore file, `DataStoreLocationRepository.kt:44-46`), preferences (one DataStore file, `DataStoreUserPreferencesRepository.kt:37-39`, `SalahPreferencesDataStore.kt:14-16`), notification settings (`DataStorePrayerNotificationPreferencesRepository`), geography (assets, `AssetBangladeshAreaCatalog.kt`). Schedules are **not** persisted — the source explains why (pure arithmetic, no schema, no migration, no new class of staleness, `ProviderBackedPrayerScheduleRepository.kt:38-42`).
*Confidence:* **Verified.**

**F47 — Nothing needs the network, and nothing can be corrupted by a failed request.**
No HTTP client, no `INTERNET` permission (`AndroidManifest.xml:35-42, 45-50`). Every failure the application can meet is modelled as a value: `Unavailable(reason, detail)` / `Failed(detail)` (`core/domain/prayer/PrayerScheduleState.kt:13-34`; `PrayerTimesProvider.kt:24-35`), provider exceptions are converted rather than propagated (`ProviderBackedPrayerScheduleRepository.kt:76-85`), and the corridor is unit-tested.
*Consequence:* the audit items "what happens when the device is offline", "what happens when API requests fail or return malformed data" and "how is stale data identified" have the same answer here — **there is no API**; malformed derived data is rejected (out-of-order or duplicated times, `CurrentPrayerPeriodResolver.kt:159-166`; a provider answer for the wrong day/city, `ProviderBackedPrayerScheduleRepository.kt:87-99`) and reported.
*Confidence:* **Verified** for the absence of a remote path; **Unverified** for real-device behaviour under Doze/permission revocation (no instrumented tests).

**F48 — Changing location, calculation settings or the Hijri offset invalidates the cached schedule without an explicit invalidation call.**
The cache key *is* the request, and `PrayerSchedule.matches()` compares `date`, `location` and `profile` by value equality (`ProviderBackedPrayerScheduleRepository.kt:119-130`). `PrayerCalculationProfile` deliberately excludes the time format, so changing 12/24-hour does **not** invalidate anything — and `SettingsViewModel` says exactly that (`SettingsViewModel.kt:123-140`).
*Confidence:* **Verified** (`ProviderBackedPrayerScheduleRepositoryTest.kt`, `SettingsViewModelTest.kt`).

**F49 — Notifications use the same authoritative calculation as the UI, through the same repository.**
`PrayerNotificationCoordinator.resolveSchedules()` calls `schedules.ensureSchedule(request).first()` with the persisted profile read from the same repository the UI observes (`PrayerNotificationCoordinator.kt:277-331`). The time *format* is shared too: `timeFormatterFor()` is used by Home and by the reminder path (`core/domain/TimeFormatting.kt:20-28`, referenced from `HomeUiStateFactory.kt:262-272`).
*Confidence:* **Verified** (`PrayerNotificationCoordinatorTest.kt`).

**F50 — Reminder reconciliation is triggered from every path that can invalidate a schedule.**
App start, settings change (`SettingsViewModel.kt:152-167`), location change from the location screen (`LocationViewModel.kt:487-492`) and from Qibla (`QiblaViewModel.reconcileNotifications()`), Hijri offset change (`CalendarSettingsViewModel.kt:69-71`), reboot/clock/time-zone/date/app-update (`SystemEventReconciliationReceiver` via `AndroidManifest.xml:112-124`), and delivery itself.
*Confidence:* **Verified** (code read; workflow runs green for the current HEAD — §8).

**F51 — The in-memory cache is bounded and process-scoped.**
`MAX_CACHED_REQUESTS = 4`, evicting the oldest entry (`ProviderBackedPrayerScheduleRepository.kt:136-142, 172`). Process death clears it; the next read recalculates in microseconds on `Dispatchers.Default`.
*Confidence:* **Verified.**

### 3.9 Settings wiring

**F52 — Every control on the Prayer times, General, Calendar-settings, Appearance and Notifications screens is persisted and consumed.**
The full chain is wired for: calculation method, Asr method, high-latitude rule, per-prayer ±minute adjustments, time format, theme, interface language, default calendar system, Hijri offset, date language, location mode and selected area, per-prayer reminder enable/sound/vibration/advance, sunrise reminder, Sehri reminder + lead, Iftar reminder + lead.
Evidence: `DataStoreUserPreferencesRepository.kt:41-165` (keys at `:153-165`); `SettingsViewModel.kt:78-168`; `CalendarSettingsViewModel.kt:44-72`; `DataStorePrayerNotificationPreferencesRepository`; UI at `SettingsPrayerTimesScreen.kt:60-145` (method / Asr / high-latitude / adjustments), `SettingsGeneralScreen.kt:67-81` (language / time format), `CalendarSettingsScreen.kt:88-216` (system / offset / date language).
*Confidence:* **Verified.**

**F53 — Classification of each requested setting.**
| Setting | State |
|---|---|
| Prayer calculation method | **Fully functional** — UI → DataStore → profile → engine → cache key → displayed times → reschedule |
| Fajr / Isha adjustments | **Fully functional** — per-prayer ±60 min, applied by the engine, surfaced on the row (`HomeUiStateFactory.kt:135-142`) |
| Asr madhhab | **Fully functional** — `STANDARD`→Shafi, `HANAFI`→Hanafi, affects Asr only |
| Manual prayer-time adjustments (all five) | **Fully functional** |
| Hijri date adjustment | **Fully functional** — ±2 days, moves date, month boundaries, Ramadan window and reminders |
| Location mode and selected area | **Fully functional** — and the selected area's own coordinate is what is stored |
| Time format | **Fully functional** for rendering; deliberately **not** part of the calculation profile |
| Locale / interface language | **Fully functional** for UI strings and calendar dates (`AppLanguage.kt`; `CalendarViewModel.kt:91`; `RamadanViewModel.kt:103`) — with the exception noted in M-2 |
| Calendar system | **Fully functional** — the selected system's month is what the grid lays out (`CalendarMonthResolver.kt:62-96`) |
| Sehri / Iftar and notification preferences | **Fully functional** |
| High-latitude rule | **Fully functional but unobservable in Bangladesh** — at 20–27°N the rule never binds (`PrayerSettings.kt:27-31`) |
| Prohibited-window boundaries | **Implemented but not exposed** — policy is a code constant (`SalahModules.kt:185`) |
| Imsak | **Not implemented** |
| Manual/imported timetable, remote provider | **Not implemented** — interfaces and enum values exist, nothing produces them (`PrayerTimesProvider.kt:48-50`) |
| *UI-only / persisted-but-unused settings* | **None found.** `PlannedFeatureScreen` exists but is referenced by nothing (`feature/common/PlannedFeatureScreen.kt:33`) — dead code, not a reachable screen |
*Confidence:* **Verified.**

### 3.10 Tests and correctness evidence

**F54 — The suite is large, layered and behaviour-oriented.**
65 test files (3 of them fixtures), 744 `@Test` methods, ~16.4k lines. Strongest relevant files: `AdhanPrayerTimesProviderTest.kt` (12 tests against the real engine: exact Dhaka values, method/Asr isolation, adjustment isolation, polar solstices, a DST transition with a UTC clock, southern hemisphere, determinism), `CurrentPrayerPeriodResolverTest.kt` (496 lines), `ProviderBackedPrayerScheduleRepositoryTest.kt`, `BanglaCalendarTest.kt` (published reference dates, whole-year round-trip, range refusals), `HijriCalendarTest.kt` (JDK table values, both month boundaries, table edges, offset semantics), `ProhibitedPeriodCalculatorTest.kt` / `ProhibitedPeriodStatusResolverTest.kt`, `RamadanCalendarTest.kt`, `RamadanReminderPlanTest.kt`, `HomeUiStateFactoryTest.kt` (611 lines), `HomeViewModelTest.kt`, `CalendarUiStateFactoryTest.kt` / `CalendarViewModelTest.kt`, `DataStoreUserPreferencesRepositoryTest.kt`, `LocationViewModelTest.kt`, `QiblaViewModelTest.kt`, `LocalizationResourcesTest.kt` (760 lines), `SettingsViewModelTest.kt`.
The tests assert concrete values and invariants rather than non-nullness, and they pin the *engine's* numbers deliberately.
*Confidence:* **Verified.**

**F55 — The provenance of the hardcoded prayer-time expectations is honestly declared, but it is not independent.**
The expected times are "regression values taken from this engine version itself", explicitly "**not** an independent religious verification" (`AdhanPrayerTimesProviderTest.kt:30-39`). The Hijri fixtures are printed from the same JDK table and cross-checked against Saudi Umm al-Qura announcements, with no claim about Bangladesh (`HijriCalendarTest.kt:14-27`). The Bangla fixtures cite published sources for the *rules* and derive the day numbers from them (`BanglaCalendarTest.kt:13-29`).
*Confidence:* **Verified.**

**F56 — Coverage gaps.**
- No test asserts that the Home header's Gregorian date and the schedule's date agree when the device zone differs from the location zone.
- No test covers the next-prayer rollover past Isha (the current behaviour returns `null`).
- No instrumented/on-device tests exist at all: the project has a single `src/test` (JVM) source set, so permission prompts, Doze, alarm delivery and lifecycle resume are covered by inference from pure classes only.
- `CurrentPrayerPeriodResolverTest` does cover boundary instants extensively; `RamadanCalendarTest` covers the Sehri/Iftar rule.
*Confidence:* **Verified** for the first two (by reading the files); the instrumented gap is **Verified** by the absence of an `androidTest` source set.

**F57 — Several intermediate commits on `main` had a red test run, repaired by the following commit.**
GitHub Actions run history for `main`: run 64 (`9509ec1`, the Hijri-localisation fix) — **failure at "Run unit tests"**; run 61 (`a8edfca`), run 59 (`98dd6c3`) and run 58 (`4db4719`) also failed; each was followed by a green commit (62, 65). Run **65** (`8dd3ee1`, the audited HEAD) is **success**: unit tests, lint and `assembleDebug` all passed and the APK artifact was produced.
*Why it matters:* the localisation/calendar batches did temporarily leave the test suite out of step with production code. At the audited revision it is in step.
*Confidence:* **Verified** (GitHub API run/job/step data; exact URLs in §8).

---

## 4. How to read the findings: defects, risks and recommendations

Every item in §5 carries an ID and one of four kinds, so a confirmed defect is never confused with an opinion:

| Kind | Meaning |
|---|---|
| **Confirmed defect** | The source demonstrably does not do what its own documentation or the user-facing label implies. Reproducible from the code alone. |
| **Potential risk** | The source does exactly what it says, but the behaviour can mislead or mis-state under conditions the app can actually meet. |
| **Architectural recommendation** | No current defect; a change that would remove a class of future problem. |
| **Requires authority decision** | Correctness depends on a religious or methodological choice that this audit must not make. Implementation must wait for that decision. |

Severity reflects consequences for a user relying on the app for worship, not code quality.

---

## 5. Problems and risks

### Critical correctness risk

**C-1 — No calculation parameter or prohibited-window boundary is aligned to any authoritative Bangladeshi timetable or ruling. (Confirmed as a claim risk; no code defect.)**
The default is Karachi 18°/18° with Hanafi Asr (`PrayerCalculationMethod.kt:25,67`; `PrayerCalculationProfile.kt:46`), and the three prohibited windows use +15 / −5+10 / −15 minutes (`ConfiguredProhibitedRuleSet.kt:57-66`). `sources = emptyList()` for the rule set, and no comparison against a published timetable exists anywhere in the repository — the engine test file says so itself (`AdhanPrayerTimesProviderTest.kt:36-39`). The code and the UI are careful never to claim authority (`PrayerCalculationProfile.kt:54-63`; `ConfiguredProhibitedRuleSet.kt:8-25`), which makes this a *claim* risk rather than a defect: the numbers are the app's own choice, and a user in Bangladesh may reasonably expect them to reproduce their mosque's or the Islamic Foundation's timetable.
**Requires authority decision** before anything is changed (see §6 step 0).

**C-2 — Ramadan, Eid and Sehri/Iftar dates are Umm al-Qura estimates that can differ from Bangladesh's announced dates by a day. (Confirmed behaviour; correctness depends on an authority.)**
`HijriCalendar` reads the JDK's Saudi Umm al-Qura table (`HijriCalendar.kt:47-75`), and `HijriDateSource` contains no "confirmed" state at all (`CalendarModels.kt:85-99`). `isVerifiedSource` is hard-coded `false` for Hijri (`DeviceClockCalendarRepository.kt:193-195`). Everything downstream inherits it: the Ramadan window (`HijriCalendar.kt:111-130`), the Sehri/Iftar reminders (`PrayerNotificationCoordinator.kt:142-146`) and the Home/Calendar date cards.
**Why it matters:** for Ramadan the start and end dates are the whole feature. The app is honest about the estimate, but there is no mechanism to accept a confirmed date — only a ±2-day manual offset (`CalendarModels.kt:166-186`), which is a workaround, not confirmation.
**Requires authority decision.**

### High priority

**H-1 — The Home header's Gregorian date is calculated in the *device* zone while the timetable is calculated for the *location* zone, so the two can name different civil days. (Confirmed defect in consistency.)**
`DeviceClockCalendarRepository.todayAt()` uses `ZoneId.systemDefault()` (`DeviceClockCalendarRepository.kt:199-200`), and that date becomes the Home header (`HomeUiStateFactory.kt:72-76, 98`). The prayer request uses the *location's* zone (`HomeViewModel.kt:152-159`). `Asia/Dhaka` is UTC+06:00; any device set to a zone more than six hours ahead (or sufficiently behind) can be on a different date from the location during the hours around local midnight. In that window the header names one day and the times below it belong to another.
Both choices are individually defensible and documented (`DeviceClockCalendarRepository.kt:42-47`), but they are combined on one screen with no reconciliation.
*Confidence:* **Verified by code.** Not covered by any test (F56).

**H-2 — After Isha the "next prayer" is empty even though tomorrow's Fajr is already loaded. (Confirmed defect.)**
`NextPrayerResolver.resolve()` returns `null` when no prayer of today's schedule is still ahead (`NextPrayerResolver.kt:35-38`). Home already computes and holds tomorrow's Fajr for exactly this purpose (`HomeViewModel.kt:201-232`) and passes it to the period resolver (`HomeUiStateFactory.kt:66, 123-130`), but not to the next-prayer resolver (`HomeUiStateFactory.kt:90-95`). The result is that the current-period card correctly counts down to tomorrow's Fajr while the "next prayer" card beside it shows nothing all night.
*Why it matters:* the missing datum exists in the same `HomeUiStateFactory.create()` call — this is an interface gap, not a missing calculation.
*Confidence:* **Confirmed defect** (`NextPrayerResolver.kt:22-24` documents the current behaviour rather than justifying it as desirable).

**H-3 — The Hijri/Ramadan estimate is presented in the same visual register as the verified Gregorian and Bangla dates. (Potential risk.)**
The type system distinguishes them (`HijriDateSource`, `HijriDateSource.ESTIMATED_*`) and the Home snapshot reports `isVerifiedSource = false` (`DeviceClockCalendarRepository.kt:190-196`), but the Home *header* renders the three date lines identically (`CalendarSection`, `HomeUiStateFactory.kt:100-102`). A user scanning the header has no cue that one of the three is an estimate that may move; the qualification is only discoverable in the calendar's selected-day card and the settings screens.
*Confidence:* **Partially Verified** — the flag exists and is correct; whether it reaches the Home header's presentation was not verified without rendering the UI on a device.

### Medium priority

**M-1 — Rounding policy is delegated entirely to the engine's minute truncation. (Potential risk.)**
The engine truncates each event to a whole minute and Salah applies no rounding of its own; the test file states there is "no sub-minute tolerance to apply" (`AdhanPrayerTimesProviderTest.kt:41-45`). A published table that rounds rather than truncates will differ by up to a minute from Salah for the same method, and there is no configuration to reconcile them beyond the ±1-minute manual adjustment (whose own effect is also truncated).
*Confidence:* **Partially Verified** — the truncation is asserted by the project's own tests; the engine's internals were not read (see U-2).

**M-2 — The calendar's week-start day still follows the *device* locale, not the selected interface language. (Confirmed inconsistency.)**
`DeviceClockCalendarRepository.monthAt()` computes its locale as `formatting.localeFor(calendarSettings.dateLanguage)` **without** the `interfaceLanguage` argument (`DeviceClockCalendarRepository.kt:122`), so a `SYSTEM` date-language preference resolves to `Locale.getDefault()` and `WeekFields.of(locale).firstDayOfWeek` (lines 134, 141) follows the phone's locale. The parallel `snapshotAt()` path *does* pass the interface language (`:101`), and every visible label on the grid comes from `CalendarUiStateFactory` with the interface locale (`CalendarUiStateFactory.kt:63, 74`).
*Consequence:* a Bengali interface on an English phone can show Bengali month names and weekday labels over a Sunday-first grid, and the grid re-lays out if the phone's locale changes but not when the app language changes — even though the class header promises "the whole month … is one consistent snapshot" (`CalendarModels.kt:214-224`).
*Confidence:* **Verified by code.**

**M-3 — Manual coordinate entry bypasses the Bangladesh-only policy and may store any IANA zone. (Confirmed behaviour; documented as intentional.)**
`LocationViewModel.saveManualLocation()` performs no country check (`LocationViewModel.kt:414-446`), and the zone is free text pre-filled with the device zone (`LocationFormState.kt:44-53`), as is the Qibla screen's equivalent path (`QiblaViewModel.saveManualLocation()`).
*Why it matters:* the app's other location paths deliberately force `Asia/Dhaka` and reject non-Bangladesh coordinates (`BangladeshLocationPolicy.kt:78-130`; `DeviceFixAdoptionPolicy.kt:74-91`), while this one can point the engine anywhere — and the Bangla calendar, the "Bangladesh" framing and the Bangladesh-specific defaults then apply to a location they were not chosen for.
*Confidence:* **Verified** — the trade-off is argued explicitly at `LocationViewModel.kt:400-413`, which is why this is listed as a risk with a product decision attached, not as a bug.

**M-4 — A device fix can be accepted without boundary verification when the dataset failed to load. (Confirmed behaviour; bounded.)**
`if (!verified && (!insideCoarseBounds || rejectionDistance != null))` (`LocationViewModel.kt:214`): with `BoundaryUnavailable`, `insideCoarseBounds == true` and `rejectionDistance == null`, the guard does not trigger, so the fix is saved with `countryCode = null` and `isCountryVerified = false` (lines 246-254). The coordinate is confined to the coarse rectangle (lat 20–27, lon 87.5–93) which strictly contains Bangladesh plus border margins up to ~300 km.
*Why it matters:* "the dataset is read-only and bundled" makes a load failure unlikely, so the exposure is small — but the code path exists and is the only one that stores an unverified location from a device fix. `DeviceFixAdoptionPolicy` (the Qibla path) rejects the same situation outright (`DeviceFixAdoptionPolicy.kt:78-80`), so the two paths disagree.
*Confidence:* **Verified by code.**

**M-5 — Stale documentation in `docs/PRAYER_CALCULATION.md` contradicts the current implementation. (Confirmed defect in documentation.)**
Lines 309-321 of that file still state:
- `:309` — "**Not verified against any published authority timetable.**" (accurate, and the important one),
- `:312` — "**No Bangla or Hijri calendar conversion;** only the device Gregorian date is available." — **false** since the Phase 4 calendar work (`BanglaCalendar.kt`, `HijriCalendar.kt`),
- `:317` — "**Prayer notifications are not implemented.**" — **false**; the scheduler, planner, coordinator and delivery path exist and are tested,
- `:321` — "**UI strings are English only.**" — **false**; `values-bn/strings.xml` exists and there is a language selector (`AppLanguage.kt`).

*Why it matters:* this file is the one a maintainer would read before touching the calculation path, and three of its five bullets describe a previous phase.
*Confidence:* **Verified.**

**M-6 — The Home screen's time formatting and provenance stamps ignore the interface-language setting. (Confirmed inconsistency.)**
`HomeUiStateFactory.create()` uses `Locale.getDefault()` for both the time formatter and the timestamp formatter (`HomeUiStateFactory.kt:80-86`). The calendar path was localised to the interface language (`CalendarViewModel.kt:91`; `RamadanViewModel.kt:103`; `DeviceClockCalendarRepository.kt:101`, `:209-210`), so Home is now the one screen where a Bengali interface on an English phone renders times and stamps with the phone's locale conventions.
*Confidence:* **Verified by code.**

### Low priority

**L-1 — The high-latitude control is reachable but can have no effect for any location the app accepts.** `PrayerSettings.kt:27-31` states the rule is never reached at Bangladesh's latitude, and the location policy means no accepted location is north of ~27°N (`BangladeshLocationPolicy.kt:146-155`). The control is persisted and consumed, so it is not a defect — only an unobservable setting. **Verified.**

**L-2 — `PlannedFeatureScreen` is dead code.** Defined at `feature/common/PlannedFeatureScreen.kt:33`, referenced by nothing. No UI-only placeholder screen is reachable, which is itself a positive finding (F53). **Verified.**

**L-3 — `CountdownFormatter` clamps negative durations to `0:00` rather than switching state** (`CountdownFormatter.kt:20-24`). Harmless because the resolvers decide what is displayed, but a `0:00` is momentarily possible on the tick that crosses a boundary. **Verified.**

**L-4 — The schedule cache is process-scoped, so a device whose clock or time zone changes mid-day silently recomputes different dates.** Intended (`ProviderBackedPrayerScheduleRepository.kt:38-42`) and mitigated by the `TIMEZONE_CHANGED`/`TIME_SET` receivers (`AndroidManifest.xml:120-122`), but nothing surfaces the change to the user. **Verified as intended.**

**L-5 — No `PrayerTimeSource.REMOTE_API` or `USER_ENTERED_TIMETABLE` producer exists** (`DataSourceInfo.kt:12-24`; `PrayerTimesProvider.kt:48-50`). Two enum values and a documented extension point with no implementation — worth noting so the audit does not read as though a fallback source exists. **Verified absence.**

**L-6 — There is no dedicated prohibited-times screen**; the intervals live in a Home card whose "View details" opens the prayer-day destination (`HomeScreen.kt:120-131`). Informational only. **Verified.**

### Missing verification or insufficient evidence

**U-1 — No comparison against any authoritative timetable, for any location or date.** Requested explicitly by the audit brief ("compare implementation behavior against established algorithm specifications or authoritative documentation already available in the repository"). The repository contains the algorithm specification (the engine's identity and the method table) but **no** authoritative result set, so this comparison cannot be made from repository evidence alone. **Missing evidence:** a published timetable (Islamic Foundation Bangladesh, or a named convention) for a set of (location, date) pairs.

**U-2 — The engine's internals were not audited.** Adhan Kotlin 0.0.5's solar-position and twilight-angle arithmetic, its truncation rule and its `PrayerAdjustments()` default constructor are taken as given. They are exercised by the project's own tests and by CI, but the library source/artifact was not available in this workspace, so no independent reading of the algorithm was possible. **Missing evidence:** the pinned artifact or its source.

**U-3 — No on-device verification exists.** There is no `androidTest` source set: GPS permission flows, Doze behaviour, alarm delivery, notification visibility and lifecycle resume are covered by pure-class tests and by inference only. **Missing evidence:** instrumented or manual device runs (which this audit was explicitly not authorised to perform — no Gradle/APK work was executed).

**U-4 — The Bangla calendar's agreement with an official almanac is unverified beyond a handful of reference dates.** The rules are cited and the fixtures follow from them (`BanglaCalendarTest.kt:13-29`), and the whole of 2024-04-14 → 2025-04-14 is round-tripped, but no year-long comparison against an officially published Bangladesh calendar was found. **Missing evidence:** an official Bangla calendar (e.g. the government/Bangla Academy almanac) for the supported range.

**U-5 — The prohibited-window minute values have no source.** `sources` is deliberately empty and the class says no reference "has been verified in this repository" (`ConfiguredProhibitedRuleSet.kt:17-18`). **Missing evidence:** the juristic/methodological source a reviewer would approve.

**U-6 — The claim that Home and the Calendar cannot display inconsistent dates for the same location and day was not verified.** The code paths use different zones (H-1) and there is no test covering the case. **Missing evidence:** a test or a device run in a zone other than `Asia/Dhaka`.

**U-7 — CI adds no behavioural evidence beyond compilation, unit tests, lint and packaging.** The workflow runs `testDebugUnitTest`, `lintDebug` and `assembleDebug` (`/.github/workflows/android-apk.yml`) — powerful, but all JVM-side. **Missing evidence:** an instrumented job.

---

## 6. Recommended improvement plan

The plan preserves the current architecture: one local engine, one schedule repository, one location store, one notification coordinator. Nothing here requires a rewrite, a new module or a new dependency. Steps are ordered so that the cheapest, most certain corrections land first, and **step 0 is a decision, not a change**.

### Step 0 — Decide the authority before changing any religious value *(requires the user's decision)*

Nothing in C-1, C-2 or U-5 can be implemented responsibly until these are chosen, because each one changes numbers a user will pray by:

1. **Prayer-time authority.** Which timetable or convention must Salah reproduce — the Islamic Foundation Bangladesh timetable, a specific method (e.g. University of Islamic Sciences Karachi 18°/18°, or the Bangladesh-adjusted angles), and which Asr madhhab? *If the answer is "match a published timetable", the plan needs the timetable for a range of locations and dates; the existing engine already accepts per-prayer offsets, so alignment may be a data change rather than a code change.*
2. **Prohibited-window convention.** Which minute boundaries, from which cited source, and whether all three windows are to be shown. The rule set is already a parameterised data object, so this is a one-line replacement in `ConfiguredProhibitedRuleSet` plus `sources`.
3. **Hijri/Ramadan confirmation policy.** Which is acceptable: (a) keep the Umm al-Qura estimate and label it clearly, (b) add a user-adjustable "confirmed start" for Ramadan, or (c) integrate an authority announcement. Option (c) is the only one that would require a network path and would break the present complete-offline property.

Salah must not choose a religious authority on the user's behalf, which is why this is step 0.

### Step 1 — Remove the date-zone inconsistency on Home (H-1) — small, deterministic

Derive the header's Gregorian date in the same zone the schedule uses, or state the divergence explicitly. The cleanest minimal change is to let the Home calendar snapshot be produced in the **prayer location's** zone (falling back to the device zone only when no location is configured), so the date above the times and the times themselves are the same civil day. This touches `DeviceClockCalendarRepository` and its tests only.
*Precondition:* decide whether the **Calendar screen** should also follow the location zone or stay on the device zone. If the Calendar stays device-zoned, the divergence must be documented on screen rather than silently present.

### Step 2 — Close the next-prayer gap after Isha (H-2) — small

Feed tomorrow's already-loaded schedule into `NextPrayerResolver` (the plumbing exists: `HomeViewModel.nextDayScheduleState` is already combined into `HomeUiStateFactory.create()`). Keep `null` as the answer only when tomorrow's schedule genuinely cannot be produced.

### Step 3 — Make the calendar's week start follow the interface language (M-2) — small

Pass `interfaceLanguage` into the `monthAt()` path in `DeviceClockCalendarRepository` exactly as `snapshotAt()` already does, and add a test pinning the first day of the week for a Bengali interface on an English device.

### Step 4 — Localise the Home screen's time and provenance formatting (M-6) — small

Replace `Locale.getDefault()` in `HomeUiStateFactory.create()` with the interface language (falling back to the device locale for `SYSTEM`), matching the Ramadan and Calendar paths.

### Step 5 — Correct the stale documentation (M-5) — small

Update `docs/PRAYER_CALCULATION.md:309-321` so the "known limitations" list matches the shipped phases (calendar implemented, notifications implemented, UI localised), while keeping the accurate "not verified against any published authority timetable" bullet prominent.

### Step 6 — Build the verification harness the audit could not build (U-1, U-4)

Once step 0 names an authority, add a **fixture set** — a committed table of (location, date, expected times) transcribed from that authority — and a test that compares the engine's output against it, reporting per-prayer deltas. This is the only way the project can ever move from "our own regression values" to an evidence-based correctness claim, and it is a test-only change. The same applies to the Bangla calendar: a year-long fixture from an official almanac.

### Step 7 — Reconcile the two device-fix acceptance paths (M-4)

Decide whether the tolerance-accepting path in `LocationViewModel` should match `DeviceFixAdoptionPolicy`'s stricter rule. Either is defensible; what should not remain is two policies that differ only because one screen has a confirmation step.

### Step 8 — Decide the manual-entry scope (M-3)

Either keep manual coordinates unrestricted and label them as "outside the supported country" in the UI, or apply the same country policy as every other path. The current middle ground — unrestricted coordinates, Bangladesh-specific calendars and defaults — is the least explicit of the three options.

### Step 9 — Architectural recommendations (no current defect)

- **Rounding:** if an authority's timetable rounds rather than truncates, add an explicit rounding policy in `AdhanPrayerTimesProvider` rather than relying on the engine's truncation (M-1).
- **Instrumented coverage:** add an `androidTest` source set for permission flows and alarm delivery (U-3). CI would need a job for it.
- **Persisted schedule (only if the product needs it):** the absence of a schedule database is a deliberate, well-argued choice (`ProviderBackedPrayerScheduleRepository.kt:38-42`). Do not add one unless a real requirement appears; a bad clock would poison a persisted table more than it poisons a recomputation.
- **Dead code:** remove `PlannedFeatureScreen` or wire it to a real destination (L-2).

### Suggested order

`Step 0` (decision) → `1, 2, 3, 4, 5` (small correctness/localisation fixes, each independently testable) → `6` (evidence harness) → `7, 8` (policy unification) → `9` (opportunistic).

---

## 7. Explicit answers to the required questions

**Are prayer times calculated locally, fetched from an API, or both?**
**Calculated locally, only.** One engine, one provider, no API. There is no HTTP client in the project and the manifest deliberately omits `INTERNET` (`AndroidManifest.xml:41-42`). A remote provider is documented as *not implemented* (`PrayerTimesProvider.kt:48-50`).

**Which calculation engine is actually used?**
**Adhan Kotlin**, `com.batoulapps.adhan:adhan2:0.0.5` (`gradle/libs.versions.toml:36,60`; `app/build.gradle.kts:100-101`), wrapped by `AdhanPrayerTimesProvider` (`data/prayer/AdhanPrayerTimesProvider.kt:52`). It is the only engine; there is no custom astronomical code and no second engine, so no two engines can disagree. Two calls are made per day: one for the five prayers with the profile's adjustments, one unadjusted for sunrise/solar-noon/sunset (`:91-115`).

**Which location coordinates and time zone drive the calculations?**
The single location stored in `DataStoreLocationRepository` — the only location store in the app. Its coordinates come from one of three validated paths: a selected **upazila/thana's own published coordinate** (division → district → upazila is required before saving), a **device GPS fix** that passed the Bangladesh-only policy, or a **manually typed** coordinate. Its time zone is **`Asia/Dhaka`** for area selections and device fixes (`BangladeshLocationPolicy.TIME_ZONE_ID`, `DataStoreLocationRepository.kt:113`, `LocationViewModel.kt:287`) and **whatever the user types** for manual entry (pre-filled with the device zone). Every published time is converted into that zone (`AdhanPrayerTimesProvider.kt:182-184`), and the day to calculate is the date *at that location* (`HomeViewModel.kt:152-159`). There is no silent default location: with nothing stored the app shows location setup.

**Is the Bangla calendar based on Bangladesh's civil calendar rules?**
**Yes, explicitly and only.** `BanglaCalendar` implements the 2019 Bangla Academy (Bangladesh civil) revision: fixed 14 April New Year, first six months 31 days each, Kartik–Magh 30, Chaitra 30, Falgun 29 (30 in a Gregorian leap year), conversion limited to 14 Apr 2019 – 31 Dec 2399 with out-of-range reported rather than approximated. It is **not** Android locale APIs, **not** a third-party library, **not** a remote API, and **not** a static dataset; it is custom Kotlin arithmetic. The class states explicitly that it is the Bangladesh calendar rather than the West Bengal Bengali calendar, and the two differ in month lengths and the New Year date. It is **not** verified against an official year-long almanac (U-4).

**How are Hijri dates calculated, and can they be officially confirmed?**
Calculated from the **JDK's own Umm al-Qura table** (`java.time.chrono.HijrahDate`), i.e. a fixed published calendar table, not astronomical calculation and not a Bangladesh announcement. Month boundaries and month lengths are read from that same table, so a 29-day month stays 29. **They cannot be officially confirmed by this app:** the `HijriDateSource` type has no "confirmed" state at all, and the Home snapshot hard-codes `isVerifiedSource = false` for Hijri. The only user control is a ±2-day offset, which is the user's own alignment rather than a confirmation. The range is bounded by the platform's table (≈AH 1300–1600).

**How are prohibited prayer times determined?**
By `ProhibitedPeriodCalculator` applying a `ProhibitedRuleSet` to the schedule's **raw solar events** — never to prayer times. The policy in force is Salah's own `ConfiguredProhibitedRuleSet.DEFAULT`: sunrise → sunrise + 15 min; solar transit − 5 min → transit + 10 min; sunset − 15 min → sunset. All three intervals are implemented as three separate rules with their own parameters (so they are not obliged to share a duration), the convention is disclosed to the user with `isReviewed = false`, and the values have **no cited source** (`sources` is empty). They are not user-configurable.

**How are Sehri and Iftar determined?**
**Sehri ends at the calculated Fajr; Iftar is the calculated Maghrib** — read out of the very `PrayerSchedule` the Home screen shows, never recomputed: `RamadanCalendar.sehriEndOf(schedule) = schedule.timeOf(FAJR)`, `iftarOf(schedule) = schedule.timeOf(MAGHRIB)`. No "safe minutes" buffer is added, on the stated grounds that an arbitrary reduction would be a religious claim. **Imsak is not implemented** and does not appear anywhere in the codebase. The Ramadan window itself comes from the Hijri table, so the dates are estimates. Reminders: the Sehri reminder is only ever a lead-in before Fajr (never at Fajr, which already has its own reminder); the Iftar reminder is at Maghrib plus an optional lead-in.

**Which features work offline?**
**All of them.** Prayer times (local arithmetic), the current period and both countdowns, sunrise/sunset/solar noon, prohibited windows, the Gregorian/Bangla/Hijri dates, the Ramadan table, Sehri/Iftar, Qibla (pure spherical trigonometry over a stored position), location selection including the full division→district→upazila hierarchy and boundary check (bundled assets), manual coordinates, all settings (DataStore), and prayer notifications (planned from the local schedule; `AlarmManager` is a platform facility, not a network service). Reboot, clock, time-zone and date-change recovery all work with the radio off.

**Which Settings options actually affect calculation results?**
Prayer calculation method, Asr madhhab, the high-latitude rule, and the five per-prayer manual adjustments — all four reach the engine and are part of the cache key. The **Hijri offset** affects the Hijri date, the Hijri month boundaries and the Ramadan window (and therefore Sehri/Iftar reminder dates, which are reconciled immediately). **Location mode / selected area** changes the coordinates and zone. The **interface language** and the calendar's **date language** change how dates are rendered but no date value. **Time format** changes no calculated time at all — it is not part of the calculation profile, and the code says so. The **calendar system** selector changes which month the grid lays out but not any date. **Theme** affects no calculation. Sehri/Iftar and notification preferences change reminders, not times. No setting exists for prohibited windows, and no setting is "UI-only" or "persisted but unused" — with the sole caveat that the **high-latitude rule can never bind** for any location the app accepts.

**What is the single highest-priority correctness issue to address next?**
**C-1 — the absence of any authority alignment for the calculation parameters and the prohibited-window boundaries.** It is the only finding that can make a user pray at a time that differs from the timetable they trust, and it cannot be fixed by code alone: step 0 of the plan is the user's decision about which authority the app must reproduce. The next item after that decision is **H-1** (the header date and the timetable can name different days), because it is a definite, reproducible inconsistency that a small change fixes with no religious judgement required.

---

## 8. Documentation and Git safety

### What was written

| Path | Action |
|---|---|
| `docs/PRAYER_AND_CALENDAR_AUDIT.md` | **Created** — this report. The only file added or modified by the audit. |

Nothing else was touched: no production source, no test, no Gradle or version-catalog file, no `gradle.properties`, no workflow, no manifest, no string resource, and no pre-existing document. The target path did not exist before the audit (verified by listing `docs/`), so there was no user-authored file to overwrite and no alternative path was needed. The `docs/` directory already existed and held 17 other documents, none of which were disturbed.

**No Gradle task, test, lint run or APK build was executed locally**, per the brief. All build/test evidence below comes from GitHub Actions.

### Repository state at the time of writing

- Repository confirmed: `Towkirahmedrony/prayer`, branch `main`, tracking `origin/main`.
- `git fetch origin` + `git pull --ff-only origin main` fast-forwarded `552e177` → `8dd3ee1` (3 commits, no merge, no conflict). The working tree was clean before and after, so **no user work was reset, cleaned, stashed, overwritten or discarded**.
- No `reset`, `clean`, `stash`, `checkout`, `restore` or force-push was performed at any point.

### Commit and push status

- **Commit:** the report is committed locally on `main` as a single commit containing only `docs/PRAYER_AND_CALENDAR_AUDIT.md`.
- **Push: NOT performed — no write credentials are available in this environment.** The push was attempted twice and refused both times:

```
$ GIT_TERMINAL_PROMPT=0 git push --dry-run origin HEAD:refs/heads/accio-audit-probe
remote: No anonymous write access.
fatal: Authentication failed for 'https://github.com/towkirahmedrony/prayer.git/'

$ GIT_TERMINAL_PROMPT=0 git push origin main
fatal: could not read Username for 'https://github.com': terminal prompts disabled
```

  `origin/main` was re-read afterwards and is unchanged (`8dd3ee1b…`), so nothing reached the remote.

  Anonymous **read** access works (`git ls-remote` returns `8dd3ee1b3da764ecfe9e3bac9cf563b0e27e996d refs/heads/main`), which is how the repository was fetched and how the CI status below was read. No `credential.helper` is configured, no `~/.git-credentials` exists, no `GH_TOKEN`/`GITHUB_TOKEN` is set, `gh auth status` reports "not logged into any GitHub hosts", and no GitHub connector is authorised for this account.

  **This is a credential limitation, not a repository conflict:** the checkout is the correct repository, the branch was level with `origin/main` before the commit, the tree was clean, and the push would have been a plain fast-forward. Because the write could not be authenticated, the commit remains **local only** — `main` is now ahead of `origin/main` by 1 commit. Nothing was forced, and the remote is untouched. To publish it, either authenticate `git` on this machine or run `git push origin main` from a checkout that has credentials.

### GitHub Actions status

- **A run for this commit does not exist**, because the push did not happen — the workflow triggers on `push` to `main` and on `workflow_dispatch` (`.github/workflows/android-apk.yml`), and nothing was pushed. No CI result is claimed for this audit commit.
- **The audited revision is green.** The run triggered by the last push to `main` (`8dd3ee1`, the commit this audit examined) succeeded in full:

| Run | Commit | Event | Conclusion |
|---|---|---|---|
| #65 | `8dd3ee1` | push | **success** — unit tests, Android lint, `assembleDebug` and the APK artifact upload all passed |

  Run URL: `https://github.com/towkirahmedrony/prayer/actions/runs/38423156252`
  Job `Build debug APK`: step "Run unit tests" → success; "Run Android lint" → success; "Build the debug APK" → success; "Upload the Salah debug APK artifact" → success.

- **Recent history is not uniformly green.** Several earlier commits on `main` failed their test step and were repaired by the next commit — run #64 (`9509ec1`, the Hijri-localisation fix) failed at "Run unit tests"; runs #61 (`a8edfca`), #59 (`98dd6c3`) and #58 (`4db4719`) also failed. This is the concrete evidence behind finding **F57**: the localisation/calendar batches temporarily left the suite out of step with production code, and each was fixed in the following commit. Run #65 is the first green run after that sequence.

### Caveats on this audit

- Everything is based on **reading source, tests, dependency declarations, documentation and workflow definitions**, plus the public GitHub Actions API. No code was executed.
- Claims marked **Partial** or **Unverified** above are not defects — they are places where repository evidence alone cannot settle the question, and each one names the missing evidence.
- Nothing in this audit should be read as a religious ruling. Where a convention had to be judged, it was flagged for decision instead (step 0 of §6).
