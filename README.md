# Salah — Your Complete Islamic Companion

Salah is an **offline-first Islamic companion for Android**: prayer times, calendars, Ramadan
tooling and Islamic utilities designed to keep working without an internet connection and
without an account.

> **Current status:** the project foundation and the **local prayer-time calculation engine** are
> implemented and covered by tests. Several long-term features described below are *planned*. This
> README states exactly which is which, and the app itself labels unimplemented sections as
> “Not implemented yet” instead of showing placeholder data.

---

## What works today

| Area | Status |
|---|---|
| Project foundation (Gradle 9.8.1, AGP 9.3.3, Kotlin 2.4.21, Material 3, Koin) | Implemented |
| Design system: emerald/ivory light + dark themes, typography, custom glyphs | Implemented |
| **Prayer calculation** — local Adhan engine: five prayers plus true sunrise, solar noon and sunset; calculation method, Asr method, high-latitude rule and per-prayer minute adjustments | **Implemented, offline, without permissions** |
| Prayer settings screen (method, Asr, high-latitude rule, time format, manual adjustments) | Implemented, persisted locally |
| Manual location (validated coordinates + IANA zone), stored on device | Implemented |
| Next prayer + countdown resolved from the real schedule | Implemented |
| Home screen: branding, date header, location, calculation indicator, timetable, solar events, next-prayer card, prohibited-times section, shortcuts, data-source status | Implemented |
| Navigation: Home, Prayer, Calendar, Ramadan, Settings, Qibla | Implemented (boundaries) |
| Loading, empty, error and offline states | Implemented |
| Unit tests for engine, domain, data and Home mapping rules | Implemented |
| GitHub Actions debug APK build (tests + lint + artifact) | Implemented |
| Prohibited prayer-time intervals | **Calculator implemented and tested, but unconfigured** — no rule set has been reviewed, so no intervals are shown |
| Bangladesh Bangla and Hijri calendar conversion | **Not implemented** — honestly reported as unavailable |
| Ramadan, Qibla, Quran, notifications, widgets, networking, Room, device location | Not implemented |

## Why it looks the way it does

Salah only shows values it actually has:

- The **Gregorian date** comes from the device clock — a real value.
- **Bangla and Hijri dates** are not estimated; those sections explain that conversion is not
  implemented yet.
- **Prayer times** are calculated on the device by a local astronomical engine from the configured
  coordinates, the date and the selected method. They appear only when a **complete** schedule
  exists for **today at the configured location**; otherwise the screen explains why (no location,
  polar twilight, or a provider failure) and shows no invented values.
- **Sunrise, solar noon and sunset** are modelled and rendered as **solar events**, never as
  prayers, and are read from an unadjusted engine call so a method's offsets cannot move them.
- **Prohibited prayer times** are not invented. The rule book ships empty, so the section explains
  that no reviewed rules have been configured.
- **Timings are shown with the settings behind them** — the calculation method and Asr method are
  disclosed, and any manual minute adjustment is labelled next to the time it moved.

The calculated times are **not** claimed to match any authority's published timetable: that
requires an actual comparison for the same location and date, which has not been done. See
[`docs/PRAYER_CALCULATION.md`](docs/PRAYER_CALCULATION.md).

---

## Technology stack

| Component | Version / choice |
|---|---|
| Language | Kotlin `2.4.21` |
| UI | Jetpack Compose, Material 3 (Compose BOM `2026.09.00`) |
| Architecture | MVVM with Clean Architecture boundaries, unidirectional data flow |
| Async | Kotlin Coroutines + Flow |
| Persistence | DataStore (prayer settings, theme, manual location) |
| Prayer engine | Adhan Kotlin `com.batoulapps.adhan:adhan2:0.0.5` (pinned; local, offline) |
| Dependency injection | Koin `4.2.2` |
| Navigation | Navigation Compose `2.10.2` |
| Build | Gradle `9.8.1` (Kotlin DSL, version catalog), AGP `9.3.3`, JDK `17` |
| SDK | `compileSdk` `37` (minor `0`, i.e. `android-37.0`), `targetSdk` `36`, `minSdk` `26` |
| Tests | JUnit 4 + kotlinx-coroutines-test |

Full version list, official references and compatibility decisions: [`docs/TECH_STACK.md`](docs/TECH_STACK.md).
The calculation engine and its pinned version are explained in
[`docs/PRAYER_CALCULATION.md`](docs/PRAYER_CALCULATION.md).

## Offline-first design

1. Local state is the primary source the UI observes — repositories expose `Flow`, the screen
   never waits on the network.
2. Prayer times are computed on the device; nothing in this phase performs a network call. A
   remote provider, if added later, would refresh a local cache, never replace it.
3. Provider failures become visible states with a retry action; they never crash the app.
4. A cached schedule is served only for the exact date, location and calculation profile it was
   produced with, so it can never appear current after the user changes location or settings.
5. Cached data carries provenance (`source`, `providerId`, `observedAt`, `available offline`), so
   the user can see where a value came from.
6. Preferences and the manual location live on the device; no account is required, and no
   permission is requested.

## Repository structure

```text
.
├── .github/workflows/android-apk.yml   CI build (tests, lint, debug APK, artifact)
├── app/
│   └── src/main/java/io/github/towkirahmedrony/salah/
│       ├── core/{model,domain,designsystem}   pure logic, ports, calculator, theme
│       ├── data/{preferences,location,calendar,prayer,prohibited}
│       ├── feature/{app,home,settings,common}
│       ├── navigation/                        destinations, nav host, app shell
│       └── di/                                Koin container
│   └── src/test/…                             unit tests
├── docs/PRAYER_CALCULATION.md                 engine, methods, zones, limits, prohibited windows
├── docs/TECH_STACK.md                         real versions + compatibility decisions
├── docs/ARCHITECTURE.md                       structure, rules, extension points
├── gradle/libs.versions.toml                  version catalog
├── build.gradle.kts  settings.gradle.kts  gradle.properties
└── gradlew  gradle/wrapper/
```

The project uses a **single `:app` module with package boundaries** at this stage; the packages
mirror the intended future modules. The reasoning is documented in `docs/ARCHITECTURE.md`.

## Building the APK (GitHub Actions)

The APK is **not** built locally. `.github/workflows/android-apk.yml` runs on:

- every push to `main`
- manual runs (`workflow_dispatch`)

Each run: checkout → Temurin JDK 17 → Gradle setup with caching → Android SDK packages
(`platforms;android-37.0`, `build-tools;37.0.0`) → wrapper validation → `testDebugUnitTest` →
`lintDebug` → `assembleDebug` → non-empty APK check → artifact upload.

**Where to find the APK:** open the repository on GitHub → *Actions* → the
*“Salah Android APK”* run for your commit → the run summary shows the commit and the artifact
**`salah-debug-apk`** (retained for 14 days, downloadable from the run's *Artifacts* section).
The workflow only reads repository contents (`permissions: contents: read`), needs no secrets
and no signing credentials.

## Current limitations

- Prayer times are calculated locally but have **not** been compared against any published
  authority timetable; no such comparison is claimed.
- Prohibited-time rules are **not** configured: the mechanism and tests exist, the reviewed rule
  set does not.
- No Bangla or Hijri conversion; only the device Gregorian date is available.
- No Ramadan schedule, Qibla compass, Quran, duas, tasbih or notifications.
- No network client, no Room database; the schedule cache is in-memory (and cheaply recomputed).
- No device-location provider: location is entered manually. No location permission is requested.
- UI strings are English only; Bangla/Arabic script rendering relies on system fonts.

## Roadmap

1. **Calendars** — Bangladesh Bangla and Hijri conversion with confirmed-date overrides, keeping
   conversion logic independent of the UI.
2. **Prohibited periods and Ramadan** — add a reviewed `ProhibitedRuleSet` (the calculator is
   ready) and derive Sehri/Iftar from the same prayer schedule.
3. **Notifications** — `AlarmManager`/`NotificationManager` scheduling outside the UI, driven by
   `PrayerScheduleRepository`, with explicit handling of exact-alarm permission and device
   restarts.
4. **Remote providers and caching** — optional AlAdhan integration with validation, caching and
   provenance display.
5. **Qibla, Quran and content modules.**

## Contributing

Changes should preserve the dependency rules in `docs/ARCHITECTURE.md`. Contributions that
affect prayer calculations, prohibited periods or religious content must include references and
tests; passing automated tests alone does not establish religious correctness.

## License

A `LICENSE` file has not been established for this repository yet; until one is added, do not
assume the source is available under a particular open-source licence.
