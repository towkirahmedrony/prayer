# Salah — Prayer-Time and Calendar Correctness Review

**Scope:** a static, evidence-based review of how the current code calculates and displays prayer times, solar
events, the Gregorian, Bangladesh Bangla and Hijri dates, Ramadan's Sehri/Iftar boundary, and the prohibited
prayer windows — plus what changed in the fix for the Home date/time-zone inconsistency and the missing next
prayer after Isha.
**Method:** reading source, tests, dependency declarations and workflow definitions. **No Gradle task, test,
lint run or APK build was executed locally**; build and test evidence comes from GitHub Actions.
**Audit type:** verification and reporting only. **No calculation method, angle, madhhab, adjustment,
prohibited-window boundary or calendar rule was changed by this review**, and none should be changed until the
decision in §7 is taken.

Every claim below cites a file and, where practical, a class/function and line range. Anything that could not
be settled from repository evidence is marked **Unverified** with the missing evidence named.

---

## 1. What this task changed (Part A), and what it deliberately did not

Two confirmed defects were fixed. Both are date/day plumbing; neither touches a religious value.

| Defect | Root cause | Fix |
|---|---|---|
| The Home header's Gregorian date could name a different civil day from the prayer timetable below it | The calendar took its day in the **device's** zone while the prayer schedule was calculated for the **location's** zone | One new authoritative source, `LocationDayZone`, resolves the zone a location-local day is decided in; the calendar repository and the Calendar screen both read it |
| The next prayer was empty after Isha even though tomorrow's Fajr was already loaded | `NextPrayerResolver` resolved only within the day it was handed and returned `null` once that day's last prayer had begun | `NextPrayerResolver.resolve` now takes the following day's **schedule** and returns its Fajr when nothing of today's is left |

**Not changed:** the calculation library or its version, the default method, the Fajr/Isha angles, the Asr
madhhab, any prayer adjustment, the prohibited-window minute values, the Hijri table, the ±2-day Hijri offset,
the Bangla algorithm, or any UI layout. §7 lists what still needs a decision.

---

## 2. Prayer-time methodology

### 2.1 Engine, dependency and version — **Verified**

- Dependency: `com.batoulapps.adhan:adhan2`, version `0.0.5` (`gradle/libs.versions.toml:36` and `:60`), consumed
  at `app/build.gradle.kts:101`.
- Version is pinned deliberately: `gradle/libs.versions.toml:29-35` records that 0.0.6 and later are compiled
  for a JVM target of 21, which this build does not use.
- Sole implementation and sole DI binding: `data/prayer/AdhanPrayerTimesProvider.kt:52`, bound at
  `di/SalahModules.kt:173`. There is no second engine, so two engines cannot disagree.
- The engine is astronomical and local. `AdhanPrayerTimesProvider.calculate()` (`:68`) builds
  `CalculationParameters` (`buildParameters`, `:132`) and calls `PrayerTimes` (`:96`).

### 2.2 Method and angles — **Verified as implemented; the choice itself is Unverified**

- Default: `PrayerCalculationMethod.KARACHI(18.0, 18.0)` (`core/model/PrayerCalculationMethod.kt:25`), selected by
  `DEFAULT` (`:67`).
- The angles Salah declares are cross-checked against the engine's own table rather than re-implemented: the
  identity-to-`AdhanCalculationMethod` mapping is one-to-one and explicitly does not restate angles
  (`AdhanPrayerTimesProvider.kt:147-159`), and the enum documents that "the engine stays the single source of
  truth for the arithmetic" (`PrayerCalculationMethod.kt:6-14`).
- **Methodology-validation gap, not a proven error:** nothing in the repository compares Salah's output against
  any published Bangladeshi timetable, and the class says so plainly — the default is "**not** a verified
  Islamic Foundation Bangladesh timetable and is never labelled as an official source"
  (`core/model/PrayerCalculationProfile.kt:54-63`). The engine regression test repeats the caveat: its expected
  values are "regression values taken from this engine version itself … **not** an independent religious
  verification" (`app/src/test/.../AdhanPrayerTimesProviderTest.kt:30-39`).
  *Therefore:* Karachi 18°/18° is **not** claimed here to be wrong for Bangladesh. It is **unvalidated**.
  *Missing evidence:* a published timetable (Islamic Foundation Bangladesh, or a named convention) for a set of
  (location, date) pairs, to compare against.

### 2.3 Asr madhhab — **Verified**

`AsrCalculationMethod.STANDARD → Madhab.SHAFI`, `HANAFI → Madhab.HANAFI`
(`AdhanPrayerTimesProvider.kt:161-164`); the default is Hanafi (`core/model/PrayerSettings.kt:26` and
`PrayerCalculationProfile.kt:46`). The change affects Asr only — asserted for every other prayer and every solar
event at `AdhanPrayerTimesProviderTest.kt:142-160`.

### 2.4 Prayer-time adjustments — **Verified**

Per-prayer manual corrections are passed as an engine `PrayerAdjustments` covering the five prayers only
(`AdhanPrayerTimesProvider.kt:138-144`); sunrise deliberately has no entry. The ±60-minute clamp lives in the
model (`core/model/PrayerSettings.kt:69-73`) and is applied on write and on read
(`data/preferences/DataStoreUserPreferencesRepository.kt:104-105` and `:147-148`).

### 2.5 Sunrise, solar noon and sunset — **Verified**

Solar events are taken from a **second, offset-free** engine call: `rawParameters` is
`adjustedParameters.copy(methodAdjustments = PrayerAdjustments(), prayerAdjustments = PrayerAdjustments())`
(`AdhanPrayerTimesProvider.kt:92-96`), and only that call supplies sunrise, solar noon and sunset
(`:112-114`). Consequence, asserted rather than assumed: a method that offsets sunrise or Maghrib cannot move a
solar event (`AdhanPrayerTimesProviderTest.kt:126-139` — Karachi and Dubai produce identical sunrise/sunset
while Maghrib moves). This is why the prohibited windows in §6 are anchored to raw solar events.

### 2.6 Are user settings actually persisted and consumed? — **Verified**

Method, Asr method, high-latitude rule, the five adjustments and the time format are one flat immutable
`PrayerSettings` (`core/model/PrayerSettings.kt:24-78`) persisted as a unit
(`DataStoreUserPreferencesRepository.kt:71-89`) with keys at `:153-165`, and read into the calculation profile by
`toProfile()` (`PrayerSettings.kt:60-65`). The profile is part of the schedule cache key
(`data/prayer/ProviderBackedPrayerScheduleRepository.kt:124-129`), so changing a setting invalidates a cached
timetable rather than silently reusing it. The time format is deliberately **not** part of the profile, because
it changes no calculated instant (`feature/settings/SettingsViewModel.kt:123-140`).

### 2.7 Zone, day boundary and rounding — **Verified**

- Every published instant is converted into the prayer location's zone (`AdhanPrayerTimesProvider.kt:98` and
  `:183`), never the device's.
- The day calculated is the date **at the location** (`feature/home/HomeViewModel.kt:152-159`).
- The engine truncates each event to a whole minute and Salah applies no rounding policy of its own; the test
  file states there is no sub-minute tolerance to apply (`AdhanPrayerTimesProviderTest.kt:41-45`).
  *Remaining uncertainty:* if a chosen authority's timetable rounds rather than truncates, Salah can differ from
  it by up to a minute on any given prayer. This is a **methodology** question (see §7), not a defect.

---

## 3. The Home date / time-zone inconsistency — root cause, fix and residual

### 3.1 Root cause — **Verified**

The calendar and the timetable asked two different questions:

- `DeviceClockCalendarRepository.todayAt()` placed the instant on a day in `ZoneId.systemDefault()` — the
  **device's** zone.
- `HomeViewModel`'s request date placed the same instant on a day in `location.zoneId` — the **prayer location's**
  zone.

`Asia/Dhaka` is UTC+06:00, so for a device more than six hours ahead (or sufficiently behind) the two disagree
for part of every day, and the header could name one civil day while the times below it belonged to another. The
`CalendarRepository` KDoc even stated the intended rule — "the configured zone places the instant on a day"
(`core/domain/repository/CalendarRepository.kt:18-20`) — while the implementation used the device zone.

### 3.2 Fix — one shared source of the day zone

**New:** `core/domain/location/LocationDayZone.kt` (`:45`).

- `zone: Flow<ZoneId>` (`:66`) — re-emits when the stored location changes; consumers that must *react* (the
  calendar repository restarts its wait for the next local midnight on it) collect this.
- `current(): ZoneId` (`:76`) — the zone right now; reads the device's zone **fresh** on each call while no
  location is configured, so a device time-zone change still takes effect.
- The answer is the configured location's own `zoneId` — the same zone the schedule is calculated and displayed
  in — falling back to the device's zone only when no location is configured. **`Asia/Dhaka` is not hardcoded in
  any UI component**; it is what the location model already carries for an area selection or an adopted device
  fix (`core/domain/location/BangladeshLocationPolicy.kt:93`, written at
  `data/location/DataStoreLocationRepository.kt:113` and `feature/location/LocationViewModel.kt:287`). A
  manually entered location carries the zone the user supplied, unchanged.

**Wired:** one binding, `di/SalahModules.kt` (`single { LocationDayZone(locationSource = get<LocationRepository>().location, scope = get()) }`), injected into `DeviceClockCalendarRepository` and `CalendarViewModel`. Both therefore resolve the day from the same instance and cannot diverge from each other.

**Changed consumers:**
- `data/calendar/DeviceClockCalendarRepository.kt` — `dayZone` parameter; `zoneNow()` (`:227`);
  `todayAt()` (`:217`) and `millisUntilNextLocalMidnight()` (`:275`) use it; `midnightTicks()` (`:246`) is
  restarted through `zoneChanges()` (`:263`) when the zone changes, so a new zone never inherits the previous
  zone's wait for midnight. With no `dayZone` supplied the previous device-zone behaviour is preserved exactly,
  which is what the existing domain-level tests pin.
- `feature/calendar/CalendarViewModel.kt` — its own `today()` (`:160`) uses the same zone, so the month it opens
  on contains the day the repository marks as today.
- `core/domain/repository/CalendarRepository.kt` — KDoc corrected to describe the location-local day.

**Preserved deliberately:** the Bangladesh-only location policy, the location model, all three location write
paths, the prayer methodology, the UI, and the `Clock`'s UTC binding (consumers still convert explicitly).

### 3.3 Residual behaviour — **Verified, documented rather than hidden**

The next-day **Fajr** handed to the current-period resolver is still derived from whatever tomorrow-schedule is
in hand, and `CurrentPrayerPeriodResolver` does not re-check that it belongs to the same place. During the brief
window after a location change — while today's schedule is still the old place's and tomorrow's is already the
new place's — Isha's closing boundary could momentarily be the new place's Fajr. This behaviour is
**pre-existing** (the value was derived from the same flow before this change) and is not part of either reported
defect, so it was left alone rather than widening the diff. The next-**prayer** path *is* guarded: the resolver
accepts a next-day schedule only when its date, location and profile all match (`NextPrayerResolver.kt:84-101`),
so the visible defect cannot reappear through a mismatched schedule.

---

## 4. The missing next prayer after Isha — root cause, fix and boundaries

### 4.1 Root cause — **Verified**

`NextPrayerResolver.resolve()` previously returned `null` when no prayer of the given day's schedule was still
ahead (`NextPrayerResolver.kt`, prior revision). After Isha, that is always the case for today, so the
"next prayer" card was empty all night — while `HomeViewModel` already held tomorrow's schedule for the Isha
boundary (`feature/home/HomeViewModel.kt:212-236`).

### 4.2 Fix — tomorrow's *schedule*, not a bare instant

`NextPrayerResolver.resolve(schedule, now, nextDaySchedule = null)` (`:45`):

- Unchanged while a prayer of the day is still ahead (`:56-67`).
- Otherwise, tomorrow's **Fajr** is returned (`:69-73`).
- Tomorrow's schedule is accepted only when it is **complete**, dated exactly `schedule.date + 1`, and carries the
  **same location and the same calculation profile** (`:84-101`). Anything else yields `null` — no fabrication,
  no reuse of today's numbers, and no negative countdown (`:97-99`).
- The value returned is converted into the current schedule's zone, so the countdown is the real duration to a
  real instant.

`HomeUiStateFactory.create()` now takes `nextDaySchedule: PrayerSchedule?` instead of a bare `nextDayFajr`
(`feature/home/HomeUiStateFactory.kt:71`), derives `nextDayFajr` from it (`:96`) and passes the schedule to the
resolver (`:101`). One calculation therefore serves both Isha's closing boundary and the next-prayer countdown.
`HomeViewModel` publishes tomorrow's **schedule** (`:231`) rather than its Fajr.

### 4.3 Behaviour across the day — **Verified**

| Moment | Next prayer | Why |
|---|---|---|
| Normal day | the following prayer of today | unchanged |
| Immediately before Isha | Isha | unchanged |
| Immediately after Isha begins | **tomorrow's Fajr** | new; counted down to tomorrow's own Fajr |
| Past local midnight | **the new day's Fajr** | the request date rolls over and the new day's schedule arrives; Fajr is simply today's next prayer |
| Tomorrow's schedule unavailable | nothing | honest: neither Isha's boundary nor the next prayer is invented |

No duplicates or reordering are possible: only the five prayers of the day in hand, plus at most one Fajr of the
following day, are ever returned.

---

## 5. Calendar correctness

### 5.1 Gregorian — **Verified**

`java.time` over the device clock; the day is the instant placed in the zone §3 resolves
(`DeviceClockCalendarRepository.kt:214`). Formatting is locale-driven and independent of the calculation: a
language change re-renders the same dates (`feature/calendar/CalendarViewModel.kt:91`,
`CalendarRepository`-driven tests in `CalendarViewModelTest`).

### 5.2 Bangladesh Bangla calendar — **Partially Verified**

- Custom Kotlin arithmetic in `core/domain/calendar/BanglaCalendar.kt` (`:62`). Not Android locale APIs, not a
  third-party library, not a remote API, not a static dataset.
- The **2019 Bangladesh (Bangla Academy) civil rules**: New Year fixed at 14 April (`NEW_YEAR_MONTH = 4`,
  `NEW_YEAR_DAY = 14`, `:158-159`); month lengths `31,31,31,31,31,31,30,30,30,30,29,30` (`:168`); Falgun takes
  30 days when the Gregorian year is a leap year (`:124`); era offset 593 (`:162`).
- Supported range `2019-04-14 → 2399-12-31` (`:147`, `:155`), enforced by `isSupported` (`:129`) and by
  `convert` returning `null` outside it (`:71-72`) — an out-of-range date is **reported**, never approximated.
  The range stops at 2399 because the published Bangladeshi leap wording and the Gregorian rule first disagree
  for years divisible by 400.
- Home and the Calendar screen use the **same** implementation: both go through
  `DeviceClockCalendarRepository` → `BanglaCalendar` (`:175`, `:183`), and the device-level conversions are pure
  and shared as singletons (`di/SalahModules.kt`).
- App language changes **formatting only**: the converter is asked for a `BanglaDate` and the locale only decides
  the script and numerals (`core/domain/calendar/CalendarFormatting.kt`), asserted by
  `BanglaCalendarTest` (rules/reference dates) and `DeviceClockCalendarRepositoryTest` (the same dates rendered in
  Bengali).
- *Remaining uncertainty:* the rules and reference dates are cited and tested, but no year-long comparison against
  an officially published Bangladesh almanac exists. **Unverified** that the output matches the official calendar
  in every supported year. *Missing evidence:* an official Bangla calendar for the supported range.

### 5.3 Hijri — **Verified as a table conversion; Unverified as a Bangladesh announcement**

- `java.time.chrono.HijrahDate` — the JDK's **Umm al-Qura** table (`core/domain/calendar/HijriCalendar.kt:47`,
  `:57`). Not astronomical moon-position calculation, not a remote API, not a Bangladesh dataset.
- Month boundaries and lengths come from that table (`monthLength`, `:95`; `monthStart`, `:84`), so a 29-day month
  stays 29; the ±2-day user offset is applied **before** reading the year/month/day, so a month or year rollover
  moves with it (`:56-75`).
- The date is explicitly an **estimate**: `HijriDateSource` has only `ESTIMATED_UMM_AL_QURA` and
  `ESTIMATED_USER_ADJUSTED` (`core/model/CalendarModels.kt:85-99`), and the Home snapshot hard-codes
  `isVerifiedSource = false` for Hijri with the comment "Never true: this is an Umm al-Qura estimate, not a
  moon-sighting announcement" (`DeviceClockCalendarRepository.kt:211-213`).
- **There is no Bangladesh moon-sighting confirmation mechanism, and no code claims one.** The type system has no
  "confirmed" state to set, which is what makes the absence structural rather than accidental.
- The user offset is consistent and persists: one `CalendarSettings.hijriAdjustmentDays` clamped
  `-2..+2` (`CalendarModels.kt:178`, `:184-185`) on write and on read
  (`DataStoreUserPreferencesRepository.kt:104-106`, `:121-123`), used by *every* Hijri consumer — the date cards,
  the month grid and the Ramadan window — and it triggers a reminder reconciliation when it moves
  (`feature/calendar/CalendarSettingsViewModel.kt:56-72`).
- The Ramadan window inherits the same source and carries it: `HijriCalendar.ramadanWindow` (`:111`) reads the
  table's own first day of month 9 and the month's own length, and `RamadanWindow` carries the
  `HijriDateSource` through to the screens (`core/model/CalendarModels.kt:257-265`).
- *Remaining uncertainty:* **no verified Bangladesh confirmation source exists in the repository, and none was
  invented by this review.** A computed Umm al-Qura date can differ from Bangladesh's announced date by up to a
  day, which is exactly why the code labels it an estimate. Changing that requires the decision in §7.

### 5.4 The Hijri month-name language question — **Verified**

`CalendarFormatting.hijriMonthName` returns Arabic **only** when the rendering locale is genuinely Arabic
(`"ar"`), otherwise Bengali or English; the Gregorian and Hijri *grids* are rendered in the interface language,
with Bengali pinned only for the Bangla mode (`core/domain/calendar/CalendarFormatting.kt:98-106`, `:201-205`).
`AppLanguage` offers only `SYSTEM`, `ENGLISH`, `BANGLA` (`core/model/AppLanguage.kt:29-39`) — there is no Arabic
option — so an Arabic Hijri month name is reachable only on a device whose own locale is Arabic. The
always-Arabic behaviour was removed in commit `9509ec1`, i.e. **one commit before the revision this review
verifies**. This was a formatting matter throughout: conversion, offset and the Ramadan window were never
involved.

---

## 6. Ramadan, Sehri/Iftar and the prohibited windows

### 6.1 Ramadan boundaries — **Verified**

- Sehri ends at the **calculated Fajr**; Iftar is the **calculated Maghrib**:
  `RamadanCalendar.sehriEndOf` (`core/domain/calendar/RamadanCalendar.kt:61`) and `iftarOf` (`:64`). Neither is
  recomputed: both read the day's `PrayerSchedule`, so no second timetable can disagree.
  No "safety minutes" buffer is added, on the stated grounds that an arbitrary reduction would be a religious
  claim (`RamadanCalendar.kt` class doc).
- **Imsak is not implemented.** A repository-wide, case-insensitive search for `imsak` returns no match in
  source. A missing Fajr yields no Sehri time rather than a guess (`:55-61`).
- Ramadan day numbers are positional within the window (`CalendarModels.kt:276-278`), and the window is carried
  in the month snapshot so a day's number can never disagree with the range shown beside it.
- The Ramadan screen loads one schedule per window date **from the same repository the Home screen uses**
  (`feature/ramadan/RamadanViewModel.kt:65-69`, `:207-218`).
- Notifications: Sehri is only ever an **advance** reminder before Fajr (an at-time one would collide with the
  Fajr reminder) and Iftar is at Maghrib plus an optional lead-in, with sound and vibration inherited from Fajr
  and Maghrib respectively (`core/domain/notification/PrayerNotificationPlanner.kt:143-148`, `:177-223`). Which
  dates are "Ramadan" is decided **once**, from the stored Hijri offset, at reconciliation time
  (`core/domain/notification/PrayerNotificationCoordinator.kt:142-146`), and because the cancellation set is
  derived rather than remembered, leaving Ramadan — or moving the offset — removes the reminders that no longer
  belong (`:155-170`).

### 6.2 Prohibited prayer windows — **Verified as implemented; the values are Unverified**

- Three windows, each with its own parameters: `ProhibitedWindowKind.SUNRISE_ASCENT`, `ZENITH_DECLINE`,
  `SUNSET_SETTING` (`core/domain/prohibited/ProhibitedRules.kt:13-38`).
- Data flow, end to end: `ConfiguredProhibitedRuleSet.DEFAULT` (`core/domain/prohibited/ConfiguredProhibitedRuleSet.kt:57-66`)
  → `ProhibitedRuleBook` (`:69`, reachable only for a rule set that is not `UNREVIEWED`) →
  `ProhibitedPeriodCalculator.calculate` (`core/domain/prohibited/ProhibitedPeriodCalculator.kt:35`) →
  `RuleBookProhibitedPeriodsRepository` → `HomeUiStateFactory` → the Home card.
- **The values, as configured:** sunrise `+15` minutes; solar transit `−5` / `+10` minutes; sunset `−15`
  minutes (`ConfiguredProhibitedRuleSet.kt:60-63`). Confirmed against the source, not assumed.
- They are anchored to **raw solar events**, never to prayer times (`ProhibitedPeriodCalculator.kt:47`, `:52`,
  `:58`, via `requireSolarEvent`, `:79`), which is why a method offset or a manual prayer adjustment cannot move
  a prohibited interval.
- The sunrise and sunset windows happen to share a 15-minute magnitude but are **not** derived from one another
  (`ConfiguredProhibitedRuleSet.kt:45-49`), and the zenith window is deliberately asymmetric — the minutes before
  the meridian are described as a precautionary margin, not as a claim that the time before the transit is itself
  prohibited (`:50-55`).
- The convention carried to the UI states its own status: `verification = CONFIGURED`, `sources = emptyList()`
  (`:64-65`), surfaced as `isReviewed = false` / `hasVerifiedSources = false`
  (`core/model/ProhibitedPeriod.kt:36-44`). The screens disclose which policy is in force rather than presenting
  it as a ruling.
- **Remaining uncertainty, reported rather than resolved:** the minute values have no cited source, and no
  madhhab-specific variants are implemented. The three intervals are **not** asserted here to be either correct
  or incorrect. Selecting a convention is a decision for the user (§7); this review did not alter them.

---

## 7. Decisions that require the user's approval — **do not implement unilaterally**

Each item below changes a number a user will pray by. Existing behaviour is left exactly as it is until one of
the options is chosen.

1. **Prayer-time authority and calculation profile.**
   *Affected:* `core/model/PrayerCalculationMethod.kt` (default `KARACHI`), `core/model/PrayerSettings.kt`
   (default `HANAFI` Asr), the engine binding in `data/prayer/AdhanPrayerTimesProvider.kt`.
   *Options:* (a) keep Karachi 18°/18° + Hanafi and say so in-app; (b) adopt a named authority's method and
   angles (e.g. the Islamic Foundation Bangladesh timetable); (c) adopt a named authority but align by per-prayer
   offsets, which the profile already supports as a data change.
   *Consequence:* (a) leaves Salah unvalidated against any published table; (b) or (c) make it reproducible
   against one — and against that one only. **Requires the authority to be named before any code or data
   changes.**
2. **Prohibited-window convention and its source.**
   *Affected:* `ConfiguredProhibitedRuleSet.DEFAULT` (the 15 / −5+10 / −15 values) and `sources`.
   *Options:* keep the current policy and its disclosure; or replace the values with a cited convention (and
   decide whether all three windows are shown, and whether any madhhab-specific variant is offered).
   *Consequence:* the current values remain the app's own choice with no source.
3. **Hijri/Ramadan confirmation policy.**
   *Affected:* `HijriDateSource`, the ±2-day offset, and — if an announcement is to be consumed — the offline
   guarantee.
   *Options:* (a) keep the Umm al-Qura estimate and the clear "estimated" labelling; (b) add an explicit
   "Ramadan has been announced" user confirmation that the offset records; (c) integrate an authority
   announcement, which would introduce the first network dependency in the app.
   *Consequence:* (a) may differ from Bangladesh's announced dates by a day; (b) keeps the app offline but makes
   confirmation manual; (c) changes the offline posture materially.
4. **Rounding policy.** If the chosen authority rounds rather than truncates, decide whether Salah adopts a
   rounding rule in `AdhanPrayerTimesProvider` or accepts up to a one-minute difference.

---

## 8. Offline behaviour — what is genuinely local, and what is not inferred

The app performs **no network request anywhere**: no HTTP client exists in the project, and the manifest omits
`INTERNET` (`app/src/main/AndroidManifest.xml:41`, the five permissions it does declare are at `:45-50`). That absence is a fact about the
code, but §8 does **not** infer offline behaviour from it alone — each item below names what actually answers it.

| Behaviour | Answered by | Category |
|---|---|---|
| Prayer times, solar events | Local astronomical arithmetic by the bundled engine | Pure local calculation |
| Current period, both countdowns, next prayer | Pure domain logic over a schedule the repository produced | Pure local calculation |
| Prohibited windows | Pure arithmetic over that schedule's solar events | Pure local calculation |
| Gregorian / Bangla / Hijri dates | `java.time` plus `BanglaCalendar` (custom code) and the JDK's Hijri table | Pure local calculation |
| Ramadan window, Sehri/Iftar | The same two sources | Pure local calculation |
| Location restore, area hierarchy, boundary check | DataStore plus bundled TSV assets (`assets/location/bd_areas.tsv`, `bd_boundary.tsv`) | Local persistent/asset data |
| User settings | DataStore | Local persistent data |
| Prayer schedules | **Not persisted** — recomputed in memory per request, cache bounded to 4 entries keyed by the full request (`data/prayer/ProviderBackedPrayerScheduleRepository.kt:44`, `:124-129`, `:172`) | Local calculation; no cache to go stale |
| Reminder scheduling | Android `AlarmManager` — a platform facility, not a network service | Local platform |
| Azan audio playback | **Not available**: no licensed recording is bundled, so the source resolves to `null` and playback reports unavailable (`di/SalahModules.kt:223`) | Local; deliberately inert |

*Remaining uncertainty:* none of this was exercised on a device — there is no `androidTest` source set, so
permission prompts, Doze, alarm delivery and lifecycle resume are covered by pure-class tests and inference only.

---

## 9. Remaining known risks (unchanged by this task)

These are carried over from the wider implementation audit and are **not** addressed here:

1. **Calendar week-start day still follows the device locale.** `DeviceClockCalendarRepository.monthAt` resolves
   its locale without the interface language, so `WeekFields.of(locale).firstDayOfWeek` follows the phone while
   every visible label follows the interface language. Small, cosmetic, real.
2. **A manually entered location may carry any IANA zone** (`feature/location/LocationViewModel.kt:414-446`,
   documented as intentional there), so the engine can be pointed outside Bangladesh while the Bangladesh-specific
   calendars still apply. A product decision, not a bug.
3. **The Home screen's time and provenance formatting still uses `Locale.getDefault()`**
   (`feature/home/HomeUiStateFactory.kt:80-86`) rather than the interface language, so a Bengali interface on an
   English phone shows Latin-digit times. Presentation only; no date value is affected.
4. **A device fix can be accepted without boundary verification when the dataset fails to load**
   (`feature/location/LocationViewModel.kt:214`), confined to the coarse bounding rectangle and stored with no
   country code. The Qibla path refuses the same situation outright.
5. **`docs/PRAYER_CALCULATION.md` §10 is stale** — it still states that no Bangla/Hijri conversion, no prayer
   notifications and no localisation exist. Correcting it is a documentation-only change and was left out of this
   task's diff to keep the change relevant.

---

## 10. Regression coverage added for the two fixes

**Timezone and date** (`app/src/test/.../data/calendar/DeviceClockCalendarRepositoryTest.kt`,
`.../core/domain/location/LocationDayZoneTest.kt`, `.../feature/calendar/CalendarViewModelTest.kt`,
`.../feature/home/HomeViewModelTest.kt`):

- the day is the **location's** day when the device's differs (exact Gregorian, Bangla and Hijri values);
- a location change re-emits the day in the new zone, with the device zone unchanged;
- rollover happens at the **location's** local midnight, while the device's own day has not moved;
- the header date and the timetable name the **same** location-local day (device on UTC, location on UTC+6);
- the Calendar screen opens on the month containing the location's day;
- the zone source itself: device fallback with no location, the location's zone once configured, re-emission on a
  zone move but not on a same-zone re-save, fallback again when the location is cleared, and a fresh device-zone
  read.

**Next prayer** (`.../core/domain/NextPrayerResolverTest.kt`, `.../feature/home/HomeUiStateFactoryTest.kt`,
`.../feature/home/HomeViewModelTest.kt`):

- normal day, immediately before Isha, immediately after Isha, and past midnight;
- the countdown to tomorrow's Fajr, and its agreement with Isha's closing boundary;
- date rollover while the app is active, and a location change while the next-prayer state is loaded;
- refusal of a next-day schedule that is another date, another place, another profile, incomplete, or whose Fajr
  is not in the future — so today's Fajr can never be reused as tomorrow's.

All new tests use deterministic clocks and explicit zones; none depends on the real current time. No existing
test was removed or weakened; the two that changed did so because the behaviour they pinned was the reported
defect (`HomeUiStateFactoryTest` now supplies tomorrow's schedule, and its comment was corrected), and the
`.github` workflow runs `testDebugUnitTest`, `lintDebug` and `assembleDebug` on every push.
