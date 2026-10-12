# Salah — Your Complete Islamic Companion

Salah is an **offline-first Islamic companion for Android**: prayer times, calendars, Ramadan
tooling and Islamic utilities designed to keep working without an internet connection and
without an account.

> **Current status:** the project foundation, the **local prayer-time calculation engine**,
> **prayer notifications**, the **Qibla compass** and the **calendar system** (Gregorian, Bangladesh
> Bangla, Hijri, and a Ramadan calendar with Sehri/Iftar times and reminders) are implemented and
> covered by tests. Several long-term features described below are *planned*. This README states
> exactly which is which, and the app itself labels unimplemented sections as “Not implemented yet”
> instead of showing placeholder data.

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
| Home screen: branding, Gregorian/Bangla/Hijri date header, location, calculation indicator, timetable, solar events, next-prayer card, prohibited-times section, shortcuts, data-source status | Implemented |
| **Prayer notifications** — reminders at the calculated prayer times, scheduled with `AlarmManager` from the same offline schedule the Home screen shows | **Implemented, offline** |
| **Qibla bearing** — initial great-circle direction to the Kaaba, in degrees from true north, plus the great-circle distance | **Implemented, offline** |
| **Qibla compass** — real sensor-driven dial with heading, signed turn and alignment, built on the fused rotation vector with two documented fallbacks | **Implemented** |
| Magnetic declination from the platform's World Magnetic Model, applied to the heading exactly once | Implemented |
| Location from the device on request (approximate or precise) with timeouts, permission and service states | Implemented |
| Manual coordinates that need no permission and no network, stored in the existing location repository | Implemented |
| **Bundled Bangladesh administrative dataset** — 8 divisions, 64 districts and 507 upazilas/thanas with published reference coordinates, plus the simplified national boundary | **Implemented, offline** |
| **Division → district → upazila picker** for choosing a location by area, with no permission and no network | **Implemented** |
| **Bangladesh-only location policy** with an explicit boundary tolerance, and a location the policy cannot place reported as unsupported rather than converted | **Implemented** |
| Location provenance and freshness, so a saved position is never presented as a current one | Implemented |
| Compass calibration guidance from the platform's own accuracy signal, with a warm-up gate; no invented accuracy score | Implemented |
| Qibla screen states for permission denied, services off, timeout, no sensor, tilted device and undefined direction | Implemented |
| Per-prayer notification settings: enable, sound (device default / bundled Salah chime / silent), vibration, optional advance reminder | Implemented, persisted locally |
| Global master switch, separate sunrise (solar event) reminder, sound preview, schedule status | Implemented |
| Reboot, clock, time-zone, date and app-update recovery; duplicate-alarm prevention; exact-alarm fallback | Implemented |
| Prayer notification settings screen, reached from Settings | Implemented |
| **Gregorian calendar** — device date, navigable month grid, today distinguished from the selection, selected-day details, locale week order | **Implemented, offline** |
| **Bangladesh Bangla calendar** — conversion under the 2019 rules, with the verified range stated and refused outside it | **Implemented, offline** |
| **Hijri calendar** — Umm al-Qura conversion from the platform's own `java.time` table, a user day offset, and observances derived from the lunar date | **Implemented, offline, always labelled as an estimate** |
| **Ramadan calendar** — estimated window, daily table with Ramadan day number, Gregorian, Bangla and Hijri dates, Sehri end, Fajr, Iftar and Maghrib, and the provenance of the times | **Implemented, offline** |
| **Sehri and Iftar reminders** — scheduled from the same calculated Fajr and Maghrib through the existing notification subsystem, only on the days the calendar places inside Ramadan | **Implemented, offline** |
| Calendar settings: default view, Hijri adjustment, date language (Bangla or Latin script and numerals) | Implemented, persisted locally |
| Navigation: Home, Prayer, Calendar (with its settings), Ramadan, Settings, Notifications, Qibla | Implemented (Qibla, Calendar and Ramadan are real screens; only Prayer remains a labelled boundary) |
| Loading, empty, error and offline states | Implemented |
| Unit tests for engine, domain, data, location, calendar, Ramadan, the notification subsystem, Home mapping and the Qibla feature | Implemented (516 test methods) |
| GitHub Actions debug APK build (tests + lint + artifact) | Implemented |
| **Azan playback machinery** (lifecycle, audio focus, single instance, stop, release) | **Implemented and unit-tested, but no recording is bundled** — no licence exists to redistribute one, so the settings screen says so and no dead switch is offered |
| Prohibited prayer-time intervals | Implemented, with a documented policy: sunrise-ascent, solar-noon (zawal) and sunset-setting intervals, each with start, end, anchor event, status and countdown. The minute boundaries are this app's configured policy, not a reviewed ruling — see `docs/FORBIDDEN_PRAYER_TIMES.md` |
| A moon-sighting or authority-confirmed Hijri date, and an integrated authority Sehri/Iftar timetable | **Not implemented** — so no date or time is ever labelled as confirmed |
| Quran, widgets, networking, Room | Not implemented |

## Why it looks the way it does

Salah only shows values it actually has:

- The **Gregorian date** comes from the device clock — a real value.
- The **Bangla date** is converted on device from the Bangladesh civil rules revised in 2019, and is
  offered only for the years those rules are verified for. Where a date is outside that range the
  screen says so instead of approximating.
- The **Hijri date is an estimate and says so.** It comes from the Umm al-Qura table built into the
  platform, is labelled as an estimate wherever it appears, and is never presented as confirmed by
  any authority — because Salah has no moon-sighting source integrated. A day offset lets the user
  align it with their community; the result is still reported as an estimate.
- **Ramadan is derived, never hard-coded.** The estimated window comes from that same Hijri
  calculation, and when no reliable window can be derived the screen shows none rather than a
  guessed date.
- **Sehri and Iftar are the existing prayer times.** Sehri ends at the calculated Fajr and Iftar is
  the calculated Maghrib, read from the same schedule the Home screen and the reminders use. Salah
  adds no buffer to either and does not claim to reproduce any authority's timetable. See
  [`docs/CALENDAR_SYSTEM.md`](docs/CALENDAR_SYSTEM.md).
- **Prayer times** are calculated on the device by a local astronomical engine from the configured
  coordinates, the date and the selected method. They appear only when a **complete** schedule
  exists for **today at the configured location**; otherwise the screen explains why (no location,
  polar twilight, or a provider failure) and shows no invented values.
- **Sunrise, solar noon and sunset** are modelled and rendered as **solar events**, never as
  prayers, and are read from an unadjusted engine call so a method's offsets cannot move them.
- **Prohibited prayer times** follow an explicit, documented policy. The card names the convention in
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
- **A location is a place Salah can name, not only a coordinate.** The bundled dataset supplies the
  divisions, districts and upazilas, and a device fix is attributed to the nearest published centre
  within a stated threshold or honestly left unresolved. The country policy and its 5 km boundary
  tolerance are documented, and a tolerance-accepted position is never described as confirmed inside
  Bangladesh. See [`docs/LOCATION_SYSTEM.md`](docs/LOCATION_SYSTEM.md).
- **The Qibla bearing is geography, so it needs nothing.** It is calculated from the stored
  coordinates in closed form, so a device with no magnetometer, or a user who has declined location
  access, still gets an exact direction; only the live dial is withheld, and with a stated reason.
- **The dial never claims more than it measures.** A heading is only compared with the Qibla when
  it is on the same north reference, and the flat-convention reading is withheld rather than shown
  when the device is tilted past the point where the projection means anything.
- **Magnetic declination is applied exactly once, and the types make a second application
  impossible** rather than relying on a reviewer to notice.

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
| Persistence | DataStore (prayer settings, theme, manual location, calendar preferences, notification preferences) |
| Calendar | `java.time` only — `LocalDate`/`YearMonth` arithmetic for Gregorian and the Bangladesh Bangla calendar, and `java.time.chrono.HijrahChronology` (the platform's Umm al-Qura table) for Hijri. No calendar dependency was added |
| Bangla date language | Explicit setting selecting Bangla script and numerals (০–৯) or Latin script, independent of the device locale |
| Prayer engine | Adhan Kotlin `com.batoulapps.adhan:adhan2:0.0.5` (pinned; local, offline) |
| Dependency injection | Koin `4.2.2` |
| Navigation | Navigation Compose `2.10.2` |
| Build | Gradle `9.8.1` (Kotlin DSL, version catalog), AGP `9.3.3`, JDK `17` |
| SDK | `compileSdk` `37` (minor `0`, i.e. `android-37.0`), `targetSdk` `36`, `minSdk` `26` |
| Notifications | `AlarmManager` (exact where permitted, windowed fallback) + `NotificationManagerCompat`; 7 notification channels |
| Sensors | `SensorManager` — rotation vector, geomagnetic rotation vector, or accelerometer + magnetometer; 10 Hz sampling; no foreground service |
| Location | `LocationManagerCompat.getCurrentLocation` — one-shot and cancellable, no Play Services dependency |
| Declination | `GeomagneticField` — the platform's World Magnetic Model, on device |
| Permissions | `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `SCHEDULE_EXACT_ALARM`, `VIBRATE`, `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION` — no `INTERNET`, no background location |
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
8. The Qibla bearing is computed locally from a locally stored position, and magnetic declination
   is evaluated on device from the platform's own geomagnetic model. Automatic location uses the
   device's own provider, and manual coordinates work with neither network nor permission.
9. The whole calendar system is computed locally: the Gregorian date from the device clock, the
   Bangla date from published civil rules, the Hijri date from the table inside the platform's
   `java.time`, and the Ramadan window from that Hijri calculation. The one thing Salah cannot do
   offline is show a confirmed date or an authority timetable — so it never claims to, and says so
   where a user would look for it.

## Repository structure

```text
.
├── .github/workflows/android-apk.yml   CI build (tests, lint, debug APK, artifact)
├── app/
│   └── src/main/java/io/github/towkirahmedrony/salah/
│       ├── core/{model,domain,designsystem}   pure logic, ports, calculator, theme
│       │   ├── domain/calendar/               Gregorian grid, Bangladesh Bangla, Hijri, Ramadan, formatting
│       │   ├── domain/notification/           planner, coordinator, deliverer, Azan lifecycle
│       │   ├── domain/qibla/                  bearing, heading maths, frames, state reducer
│       │   └── domain/location/               dataset parser, area resolution/selection, country policy, freshness
│       ├── data/{preferences,location,calendar,prayer,prohibited}
│       │   ├── location/                      DataStore location + bundled-asset area catalog
│       │   ├── notification/                  AlarmManager, channels, receiver, presenter, Azan IO
│       │   └── qibla/                         SensorManager, GeomagneticField, LocationManagerCompat
│       ├── feature/{app,home,location,calendar,ramadan,settings,notifications,qibla,common}
│       ├── navigation/                        destinations, nav host, app shell
│       └── di/                                Koin container
│   ├── src/main/assets/location/              generated bd_areas.tsv + bd_boundary.tsv
│   ├── src/main/res/raw/salah_chime.wav       bundled reminder tone (see the note below)
│   └── src/test/…                             unit tests
├── docs/CALENDAR_SYSTEM.md                    Gregorian, Bangladesh Bangla, Hijri, Ramadan, Sehri/Iftar
├── docs/LOCATION_SYSTEM.md                    setup flow, GPS/manual selection, country policy, dataset, limits
├── docs/PRAYER_CALCULATION.md                 engine, methods, zones, limits, prohibited windows
├── docs/PRAYER_NOTIFICATIONS.md               scheduler, permissions, channels, Azan, limitations
├── docs/PRAYER_NOTIFICATIONS_TESTING.md       automated coverage + the manual device checklist
├── docs/QIBLA_COMPASS.md                      bearing formula, sensors, declination, limits
├── docs/QIBLA_COMPASS_TESTING.md              automated coverage + the manual device checklist
├── docs/TECH_STACK.md                         real versions + compatibility decisions
├── docs/ARCHITECTURE.md                       structure, rules, extension points
├── gradle/libs.versions.toml                  version catalog
├── tools/generate_bd_location_dataset.py      dataset generator (documents the sources and the join)
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
- **The Hijri date and the Ramadan window are estimates.** Umm al-Qura is not the calendar
  Bangladesh's religious authorities announce Ramadan and the Eids with, so Salah labels both as
  estimates everywhere and never as confirmed. Local moon sighting may move an observance by a day;
  the Hijri adjustment exists so the user can align Salah with their own community.
- **Sehri and Iftar are calculated references**, not an authority's timetable, and Salah adds no
  buffer to either. A user who follows a published timetable should follow it.
- **The Bangladesh Bangla calendar is implemented only from 14 April 2019 onwards**, and only for
  Bangladesh's rules — not for West Bengal's, which differ in month lengths and in the New Year date.
- **Hijri conversion is limited to the platform's table** (roughly AH 1300–1600), and the Bangla
  calendar to 14 April 2019 – 31 December 2399. Outside those ranges the screens state the limit
  rather than converting anyway.
- **The calendar screens have not been verified on a physical device.** The converters, the state
  mapping, the persistence and the reminder planning are covered by the automated tests documented in
  [`docs/CALENDAR_SYSTEM.md`](docs/CALENDAR_SYSTEM.md) §13; the visual layout, both themes, large font
  scales and a real device time-zone change are not.
- Prohibited-time boundaries are this application's **configured policy**, disclosed as such on the
  screen that shows them; no source for the minute values has been verified in this repository.

## Roadmap

1. **Prohibited periods** — the shipped policy is applied and disclosed; add a reviewed `ProhibitedRuleSet` with its sources to replace it.
2. **A verified authority source** — if a published, redistributable Sehri/Iftar timetable or a
   moon-sighting announcement feed is ever integrated, it must be linked to its source, labelled as
   such, and must leave the local calculation working when it cannot be reached.
3. **Licensed Azan audio** — source a recording whose redistribution rights are established, add it
   to `res/raw`, and return it from `BundledAzanAudioSource`. Decide deliberately whether
   unattended long-form playback warrants a foreground service.
4. **On-device verification** — work through the notification and Qibla checklists on real hardware
   and record the results, including device-specific Doze, battery and magnetometer behaviour.
5. **Remote providers and caching** — optional AlAdhan integration with validation, caching and
   provenance display.
6. **Quran and content modules.**

## Contributing

Changes should preserve the dependency rules in `docs/ARCHITECTURE.md`. Contributions that
affect prayer calculations, prohibited periods or religious content must include references and
tests; passing automated tests alone does not establish religious correctness.

## License

A `LICENSE` file has not been established for this repository yet; until one is added, do not
assume the source is available under a particular open-source licence.
