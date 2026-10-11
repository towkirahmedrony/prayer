# Salah — Your Complete Islamic Companion

Salah is an **offline-first Islamic companion for Android**: prayer times, calendars, Ramadan
tooling and Islamic utilities designed to keep working without an internet connection and
without an account.

> **Current status:** the project foundation, the **local prayer-time calculation engine** and
> **prayer notifications** are implemented and covered by tests. Several long-term features
> described below are *planned*. This README states exactly which is which, and the app itself
> labels unimplemented sections as “Not implemented yet” instead of showing placeholder data.

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
| **Prayer notifications** — reminders at the calculated prayer times, scheduled with `AlarmManager` from the same offline schedule the Home screen shows | **Implemented, offline** |
| Per-prayer notification settings: enable, sound (device default / bundled Salah chime / silent), vibration, optional advance reminder | Implemented, persisted locally |
| Global master switch, separate sunrise (solar event) reminder, sound preview, schedule status | Implemented |
| Reboot, clock, time-zone, date and app-update recovery; duplicate-alarm prevention; exact-alarm fallback | Implemented |
| Prayer notification settings screen, reached from Settings | Implemented |
| Navigation: Home, Prayer, Calendar, Ramadan, Settings, Qibla, Notifications | Implemented (boundaries) |
| Loading, empty, error and offline states | Implemented |
| Unit tests for engine, domain, data, Home mapping and the notification subsystem | Implemented (191 tests) |
| GitHub Actions debug APK build (tests + lint + artifact) | Implemented |
| **Azan playback machinery** (lifecycle, audio focus, single instance, stop, release) | **Implemented and unit-tested, but no recording is bundled** — no licence exists to redistribute one, so the settings screen says so and no dead switch is offered |
| Prohibited prayer-time intervals | **Calculator implemented and tested, but unconfigured** — no rule set has been reviewed, so no intervals are shown |
| Bangladesh Bangla and Hijri calendar conversion | **Not implemented** — honestly reported as unavailable |
| Ramadan, Qibla, Quran, widgets, networking, Room, device location | Not implemented |

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
- **Reminders come from those same calculated times.** The scheduler reads the identical
  `PrayerScheduleRepository` the Home screen reads, so a reminder can never announce a time the
  timetable does not show, and a missing timetable produces **no** reminder rather than a guessed
  one.
- **Azan audio is not bundled and is not pretended.** No recording ships, so the settings screen
  states that playback is unavailable and why, rather than showing a switch that could never be
  turned on.
- **Android's limits are stated, not glossed over.** If exact alarms are not permitted, if
  notification permission is denied, or if a channel has been silenced by the user, the app says
  what that means instead of implying a guarantee Android does not offer.

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
| Notifications | `AlarmManager` (exact where permitted, windowed fallback) + `NotificationManagerCompat`; 7 notification channels |
| Permissions | `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `SCHEDULE_EXACT_ALARM`, `VIBRATE` — no `INTERNET`, no location permission |
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
6. Preferences, the manual location and the notification settings live on the device; no account
   is required.
7. Notifications are scheduled, delivered and recovered after a reboot **without any network
   access**. Salah declares no `INTERNET` permission at all, so there is nothing it could send.

## Repository structure

```text
.
├── .github/workflows/android-apk.yml   CI build (tests, lint, debug APK, artifact)
├── app/
│   └── src/main/java/io/github/towkirahmedrony/salah/
│       ├── core/{model,domain,designsystem}   pure logic, ports, calculator, theme
│       │   └── domain/notification/           planner, coordinator, deliverer, Azan lifecycle
│       ├── data/{preferences,location,calendar,prayer,prohibited}
│       │   └── notification/                  AlarmManager, channels, receiver, presenter, Azan IO
│       ├── feature/{app,home,settings,notifications,common}
│       ├── navigation/                        destinations, nav host, app shell
│       └── di/                                Koin container
│   ├── src/main/res/raw/salah_chime.wav       bundled reminder tone (see the note below)
│   └── src/test/…                             unit tests
├── docs/PRAYER_CALCULATION.md                 engine, methods, zones, limits, prohibited windows
├── docs/PRAYER_NOTIFICATIONS.md               scheduler, permissions, channels, Azan, limitations
├── docs/PRAYER_NOTIFICATIONS_TESTING.md       automated coverage + the manual device checklist
├── docs/TECH_STACK.md                         real versions + compatibility decisions
├── docs/ARCHITECTURE.md                       structure, rules, extension points
├── gradle/libs.versions.toml                  version catalog
├── build.gradle.kts  settings.gradle.kts  gradle.properties
└── gradlew  gradle/wrapper/
```

The project uses a **single `:app` module with package boundaries** at this stage; the packages
mirror the intended future modules. The reasoning is documented in `docs/ARCHITECTURE.md`.

**On `res/raw/salah_chime.wav`:** this is the only audio asset in the repository. It is a short
reminder tone that was **synthesised for this project** and carries no third-party rights, so it can
be redistributed with the source. It is explicitly *not* an Azan recording, and no Azan recording is
bundled — see [`docs/PRAYER_NOTIFICATIONS.md`](docs/PRAYER_NOTIFICATIONS.md) §5.

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
- No Ramadan schedule, Qibla compass, Quran, duas or tasbih.
- **No Azan recording is bundled.** The playback subsystem is implemented and tested; the asset is
  missing for licensing reasons, and the UI reports that rather than offering a control that cannot
  work.
- **Notification delivery has not been verified on a physical device.** Reboot recovery, Doze
  behaviour, system permission surfaces and real alarm timing need hardware. The checklist exists
  and is explicitly unperformed:
  [`docs/PRAYER_NOTIFICATIONS_TESTING.md`](docs/PRAYER_NOTIFICATIONS_TESTING.md) §4.
- Notification reminders may be delayed or silenced by Android: without exact-alarm access they use
  a ten-minute window, and Do Not Disturb, battery optimisation and a force-stopped app can defer or
  withhold them. Salah uses only supported APIs and does not attempt to bypass these.
- No network client, no Room database; the schedule cache is in-memory (and cheaply recomputed).
- No device-location provider: location is entered manually. No location permission is requested.
- UI strings are English only; Bangla/Arabic script rendering relies on system fonts.

## Roadmap

1. **Calendars** — Bangladesh Bangla and Hijri conversion with confirmed-date overrides, keeping
   conversion logic independent of the UI.
2. **Prohibited periods and Ramadan** — add a reviewed `ProhibitedRuleSet` (the calculator is
   ready) and derive Sehri/Iftar from the same prayer schedule.
3. **Licensed Azan audio** — source a recording whose redistribution rights are established, add it
   to `res/raw`, and return it from `BundledAzanAudioSource`. Decide deliberately whether
   unattended long-form playback warrants a foreground service.
4. **On-device verification of notifications** — work through the manual checklist on real hardware
   and record the results, including device-specific Doze and battery behaviour.
5. **Remote providers and caching** — optional AlAdhan integration with validation, caching and
   provenance display.
6. **Qibla, Quran and content modules.**

## Contributing

Changes should preserve the dependency rules in `docs/ARCHITECTURE.md`. Contributions that
affect prayer calculations, prohibited periods or religious content must include references and
tests; passing automated tests alone does not establish religious correctness.

## License

A `LICENSE` file has not been established for this repository yet; until one is added, do not
assume the source is available under a particular open-source licence.
