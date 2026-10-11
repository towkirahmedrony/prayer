# Salah — Your Complete Islamic Companion

Salah is an **offline-first Islamic companion for Android**: prayer times, calendars, Ramadan
tooling and Islamic utilities designed to keep working without an internet connection and
without an account.

> **Current status:** the project foundation and the first screen are implemented. Most of the
> long-term features described below are *planned*, not built. This README states exactly which
> is which, and the app itself labels unimplemented sections as “Not implemented yet” instead of
> showing placeholder data.

---

## What works today

| Area | Status |
|---|---|
| Project foundation (Gradle 9.8.1, AGP 9.3.3, Kotlin 2.4.21, Material 3, Koin) | Implemented |
| Design system: emerald/ivory light + dark themes, typography, custom glyphs | Implemented |
| Home screen: branding, date header, location, timetable, solar events, next-prayer card, prohibited-times section, shortcuts, data-source status | Implemented |
| Navigation: Home, Prayer, Calendar, Ramadan, Settings, Qibla | Implemented (boundaries) |
| Settings: theme mode + manual location, stored locally with DataStore | Implemented |
| Loading, empty, error and offline states | Implemented |
| Unit tests for domain and Home mapping rules | Implemented |
| GitHub Actions debug APK build | Implemented |
| Prayer time calculation | **Not implemented** — the engine is not integrated, so no times are shown |
| Bangladesh Bangla and Hijri calendar conversion | **Not implemented** — honestly reported as unavailable |
| Prohibited prayer-time intervals | **Not implemented** — no rule set has been reviewed |
| Ramadan, Qibla, Quran, notifications, widgets, networking, Room | Not implemented |

## Why it looks the way it does

Salah only shows values it actually has:

- The **Gregorian date** comes from the device clock — a real value.
- **Bangla and Hijri dates** are not estimated; those sections explain that conversion is not
  implemented yet.
- **Prayer times and the next-prayer countdown** appear only when a provider returns a complete
  schedule for today. Until then the screen says *“Prayer calculation is not configured yet.”*
- **Prohibited prayer times** are not invented; the section explains that no verified rules have
  been configured.
- **Sunrise, solar noon and sunset** are modelled as solar events, never as prayers.

---

## Technology stack

| Component | Version / choice |
|---|---|
| Language | Kotlin `2.4.21` |
| UI | Jetpack Compose, Material 3 (Compose BOM `2026.09.00`) |
| Architecture | MVVM with Clean Architecture boundaries, unidirectional data flow |
| Async | Kotlin Coroutines + Flow |
| Persistence | DataStore (preferences, manual location) |
| Dependency injection | Koin `4.2.2` |
| Navigation | Navigation Compose `2.10.2` |
| Build | Gradle `9.8.1` (Kotlin DSL, version catalog), AGP `9.3.3`, JDK `17` |
| SDK | `compileSdk`/`targetSdk` `36`, `minSdk` `26` |
| Tests | JUnit 4 + kotlinx-coroutines-test |

Full version list, official references and compatibility decisions: [`docs/TECH_STACK.md`](docs/TECH_STACK.md).

## Offline-first design

1. Local state is the primary source the UI observes — repositories expose `Flow`, the screen
   never waits on the network.
2. Nothing in this phase performs a network call; a remote provider would refresh a local cache,
   never replace it.
3. Provider failures become visible states with a retry action; they never crash the app.
4. Cached data carries provenance (`source`, `provider`, `observedAt`, `available offline`), so
   the user can see where a value came from.
5. Preferences and the manual location live on the device; no account is required, and no
   permission is requested.

## Repository structure

```
.
├── .github/workflows/android-apk.yml   CI build (tests, lint, debug APK, artifact)
├── app/
│   └── src/main/java/io/github/towkirahmedrony/salah/
│       ├── core/{model,domain,designsystem}   pure logic, ports, theme
│       ├── data/{preferences,location,calendar,prayer,prohibited}
│       ├── feature/{app,home,settings,common}
│       ├── navigation/                        destinations, nav host, app shell
│       └── di/                                Koin container
│   └── src/test/…                             unit tests
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
(`platforms;android-36`, `build-tools;36.0.0`) → wrapper validation → `testDebugUnitTest` →
`lintDebug` → `assembleDebug` → non-empty APK check → artifact upload.

**Where to find the APK:** open the repository on GitHub → *Actions* → the
*“Salah Android APK”* run for your commit → the run summary shows the commit and the artifact
**`salah-debug-apk`** (retained for 14 days, downloadable from the run's *Artifacts* section).
The workflow only reads repository contents (`permissions: contents: read`), needs no secrets
and no signing credentials.

## Current limitations

- No prayer times are calculated or displayed: the provider is a declared boundary that reports
  “not configured”.
- No Bangla or Hijri conversion; only the device Gregorian date is available.
- No prohibited-time rules, Ramadan schedule, Qibla compass, Quran, duas, tasbih or notifications.
- No network client, no Room database, no background work; the schedule cache is in-memory.
- UI strings are English only; Bangla/Arabic script rendering relies on system fonts.
- The date header is emitted per collection, so it does not roll over at midnight yet.

## Roadmap

1. **Prayer engine** — integrate a local astronomical provider (Adhan Kotlin is the candidate),
   decide and verify the calculation profile (method, Hanafi Asr, high-latitude rule), store
   schedules, and validate results against verified local timetables.
2. **Calendars** — Bangladesh Bangla and Hijri conversion with confirmed-date overrides, keeping
   conversion logic independent of the UI.
3. **Prohibited periods and Ramadan** — encode reviewed rules and derive Sehri/Iftar from the
   same prayer schedule.
4. **Notifications** — `AlarmManager`/`NotificationManager` scheduling outside the UI, with
   explicit handling of exact-alarm permission and device restarts.
5. **Remote providers and caching** — optional AlAdhan integration with validation, caching and
   provenance display; Qibla; Quran and content modules.

## Contributing

Changes should preserve the dependency rules in `docs/ARCHITECTURE.md`. Contributions that
affect prayer calculations, prohibited periods or religious content must include references and
tests; passing automated tests alone does not establish religious correctness.

## License

A `LICENSE` file has not been established for this repository yet; until one is added, do not
assume the source is available under a particular open-source licence.
