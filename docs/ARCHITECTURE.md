# Salah — Architecture

This documents the architecture **as implemented in this repository**, together with the
dependency rules and the extension points for later phases.

## 1. Scope of this phase

Implemented:

- Gradle project with a version catalog, Kotlin DSL and wrapper `9.8.1`.
- Design system: emerald/ivory Material 3 theme, typography, shapes, hand-drawn glyphs.
- Salah Home screen: top bar, location and date header, the **live current-prayer hero** (the period
  that is open now, with its countdown), today's prayer-time strip, the prohibited-times card and the
  daily tools, plus loading/error/empty states and light/dark themes. Every value on it is bound to
  application state — see §7a for the exact source of each field.
- **Prayer calculation:** a real, offline local engine (Adhan Kotlin) producing the five prayers
  plus true sunrise, solar noon and sunset, with selectable calculation method, Asr method,
  high-latitude rule and per-prayer minute adjustments.
- **Prayer settings screen** bound to the persisted settings, recalculating the timetable when a
  setting changes.
- **Location foundation:** a bundled Bangladesh administrative dataset (8 divisions, 64 districts,
  507 upazilas/thanas with published reference coordinates) plus the simplified national boundary; a
  division → district → upazila picker and a request-based device fix; a Bangladesh-only policy with
  an explicit boundary tolerance; area resolution from a coordinate; location provenance and
  freshness. Manual entry needs no permission and no network, and works offline.
- Navigation: Home, Calendar, Tools, Settings (bottom bar), with the Prayer timetable, the Qibla
  compass and Ramadan reached from Tools and from Home's daily tools. See
  `docs/BOTTOM_NAVIGATION.md`.
- **Prayer notifications:** reminders scheduled at the calculated prayer times by `AlarmManager`,
  with per-prayer sound, vibration and advance-reminder settings, a global master switch, a separate
  sunrise reminder, notification channels, exact-alarm fallback, and recovery after reboot, clock,
  time-zone, date and app-update changes. Fully offline.
- **Azan playback machinery:** lifecycle, single-instance, audio-focus and release rules,
  implemented and unit-tested. No recording is bundled because no licence exists to redistribute
  one, so the settings screen reports the limitation rather than offering a dead control.
- **Qibla compass:** the initial great-circle bearing to the Kaaba computed locally, a
  sensor-driven dial built on the device's real orientation sensors, magnetic-declination
  correction from the platform's geomagnetic model, request-based device location alongside manual
  coordinates, location provenance and freshness, compass calibration guidance, and ten explicit UI
  states. Fully offline.
- **Calendars:** a Gregorian month grid, conversion to the **Bangladesh Bangla** calendar under the
  2019 rules, and **Hijri** dates from the platform's own Umm al-Qura table, all rendered over one
  Gregorian grid with the user's choice of date language. Calendar preferences (default view, Hijri
  day offset, date language) are persisted; a date outside a converter's supported range is reported
  rather than approximated. Fully offline.
- **Ramadan:** the estimated window derived from the Hijri calculation, a daily table carrying the
  Ramadan day number, Gregorian, Bangla and Hijri dates, Sehri end, Fajr, Iftar and Maghrib, and the
  provenance of the times. **Sehri and Iftar reminders** are planned by the existing notification
  subsystem for the days the calendar places inside Ramadan, with their own switches and lead times.
  Fully offline.
- Repository/provider boundaries for prayer schedules, prohibited periods, calendars, location,
  preferences and notification settings.
- **Optional remote publication (Supabase):** a revisioned, checksummed location dataset (§13) and a
  revisioned prayer configuration that supplies the calculation engine's *inputs* (§14). Both are off
  by default, validated before use, cached locally, and never required to calculate or show a time.
- Unit tests for domain, data and presentation-mapping logic.
- GitHub Actions workflow that runs tests, lint and builds the debug APK.

Deliberately **not** implemented (see §11): an authority-confirmed Hijri date or Sehri/Iftar
 timetable, prohibited-time rules, bundled Azan audio, third-party timetable providers, Room, widgets,
 foreground-service playback.

Detailed calendar documentation, including the Bangladesh rules and their source, the supported
ranges and the accuracy limits, lives in [`CALENDAR_SYSTEM.md`](CALENDAR_SYSTEM.md).

Detailed notification documentation lives in
[`PRAYER_NOTIFICATIONS.md`](PRAYER_NOTIFICATIONS.md); its tests and the manual device checklist are
in [`PRAYER_NOTIFICATIONS_TESTING.md`](PRAYER_NOTIFICATIONS_TESTING.md). The same pair exists for the
Qibla feature: [`QIBLA_COMPASS.md`](QIBLA_COMPASS.md) and
[`QIBLA_COMPASS_TESTING.md`](QIBLA_COMPASS_TESTING.md). The location foundation — setup flow, GPS and
manual selection, the country policy and its tolerance, the bundled dataset and its licences — is
documented in [`LOCATION_SYSTEM.md`](LOCATION_SYSTEM.md).

## 2. Gradle module decision

This phase uses **one Gradle module (`:app`) with package boundaries**, not a multi-module layout
(`core:*`, `data:*`, `feature:*`).

Why:

- A new module boundary costs configuration time and build overhead without buying isolation that
  a single package tree cannot already provide at this size.
- The dependency rules below are enforced by package structure and review, and the packages are
  named after the future modules, so extraction is a mechanical move rather than a rewrite.

Future extraction (`core:model`, `core:domain`, `data:prayer`, `feature:home`, …) must not require
changing business logic — only `build.gradle.kts` files and imports.

## 3. Package map (actual)

```text
io.github.towkirahmedrony.salah
├── SalahApplication            Application + Koin bootstrap
├── MainActivity                Single-activity Compose host
├── di/                         SalahModules (Koin container)
├── core/
│   ├── model/                  Pure data: PrayerName, SolarEvent, PrayerSchedule,
│   │                           PrayerCalculationMethod/Profile/Settings, LocationInfo,
│   │                           CalendarSnapshot, ProhibitedPeriod, DataSourceInfo,
│   │                           ThemeMode, UnavailabilityReason, TasbihSession
│   ├── domain/
│   │   ├── prayer/             PrayerTimesProvider (port), PrayerScheduleRequest/State
│   │   ├── prohibited/         ProhibitedWindowKind/RuleSet/RuleBook + ProhibitedPeriodCalculator,
│   │   │                       ProhibitedPeriodStatusResolver
│   │   ├── solar/              SolarDayResolver: solar midnight and day/night lengths, derived
│   │   │                       from the schedule's own events (docs/SOLAR_AND_NIGHT_PERIODS.md)
│   │   ├── night/              NightPhase/Boundary, NightPeriodCalculator, ConfiguredNightConvention:
│   │   │                       Islamic midnight, last third, Tahajjud from the Maghrib-to-Fajr
│   │   │                       interval (docs/SOLAR_AND_NIGHT_PERIODS.md)
│   │   ├── notification/       Notification subsystem, Android-free:
│   │   │                       ReminderTarget, PrayerAlarmSlot, PrayerReminderKind,
│   │   │                       PlannedPrayerNotification, PrayerNotificationIds,
│   │   │                       PrayerNotificationRequest, PrayerNotificationPlanner,
│   │   │                       PrayerNotificationScheduler (port), ReconcileReason,
│   │   │                       PrayerNotificationCoordinator, PrayerNotificationDeliverer,
│   │   │                       PrayerReminderDelivery, DeliveryOutcome,
│   │   │                       PrayerNotificationChannelId, NotificationPermissionStatus,
│   │   │                       AzanPlaybackManager + AzanAudioSource / AudioPlaybackEngine ports,
│   │   │                       NotificationSoundPreviewer (port)
│   │   ├── qibla/              Qibla subsystem, Android-free:
│   │   │                       KaabaReference, QiblaBearing, QiblaCalculator, HeadingMath
│   │   │                       (MagneticHeading / TrueHeading), HeadingSmoother, SensorFrames,
│   │   │                       CompassSensorSource + CompassReading + CompassAccuracyGate,
│   │   │                       GeomagneticDeclinationProvider, LocationProvider +
│   │   │                       LocationPermissionChecker + LocationProviderSelection,
│   │   │                       QiblaPhase / CalibrationAdvice / QiblaStateReducer
│   │   ├── tasbih/             Tasbih counter policy, Android-free: TasbihCounter
│   │   │                       (count / undo / reset / retarget / restart, archiving, retention)
│   │   ├── zakat/              Zakat calculation, Android-free: ZakatCalculator (the flow of
│   │   │                       docs/ZAKAT_METHODOLOGY.md §6, pure and deterministic)
│   │   ├── location/           Bangladesh location domain, Android-free:
│   │   │                       BangladeshLocationDataset (parser), BangladeshArea/Level/Ref models,
│   │   │                       AreaResolver + AreaSelection + AreaResolution,
│   │   │                       BangladeshLocationPolicy + BangladeshCheck,
│   │   │                       DeviceFixAdoptionPolicy, LocationSetup, GeoMath, LocationFreshness
│   │   ├── repository/         Repository interfaces (location, preferences, schedule,
│   │   │                       calendar, prohibited periods, notification settings, tasbih)
│   │   ├── DiagnosticsLogger   Structured diagnostics port + SalahDiagnosticEvents
│   │   ├── NextPrayerResolver  Pure next-prayer resolution
│   │   ├── TimeFormatting      Shared clock-format helper (Home + notifications)
│   │   ├── calendar/           Pure, Android-free converters: GregorianCalendarGrid, BanglaCalendar,
│   │   │                       HijriCalendar, RamadanCalendar, CalendarFormatting
│   │   └── CountdownFormatter  Pure countdown formatting
│   └── designsystem/           theme/, glyphs, shared components
├── data/
│   ├── preferences/            DataStore-backed preferences (theme + prayer settings)
│   ├── location/               DataStore-backed location store + AssetBangladeshAreaCatalog
│   │                           (parses the bundled bd_areas.tsv / bd_boundary.tsv assets once)
│   ├── calendar/               DeviceClockCalendarRepository: which Gregorian day is meant, the
│   │                           local-midnight and time-zone refresh, and the conversion of every
│   │                           day it lays out
│   ├── prayer/                 AdhanPrayerTimesProvider + offline-first schedule repository
│   ├── (zakat has no data package: the engine is pure and its inputs arrive from the screen —
│   │    see docs/ZAKAT_METHODOLOGY.md §8.4 on what is and is not stored)
│   ├── tasbih/                 DataStoreTasbihRepository + TasbihSessionCodec: the counter's
│   │                           session, its history and its haptics preference, in the one DataStore
│   ├── prohibited/             Rule-book-backed repository (documented policy)
│   ├── qibla/                  Android side of the Qibla feature:
│   │                           AndroidCompassSensorSource (SensorManager, three sensor families,
│   │                           display-rotation remap, tilt), AndroidGeomagneticDeclinationProvider
│   │                           (GeomagneticField), AndroidLocationProvider (LocationManagerCompat,
│   │                           one-shot), AndroidLocationPermissionChecker
│   └── notification/           Android side of the notification subsystem:
│                               AlarmManagerPrayerNotificationScheduler,
│                               AndroidNotificationChannels, AndroidPrayerNotificationPresenter,
│                               PrayerNotificationReceiver, SystemEventReconciliationReceiver,
│                               DataStorePrayerNotificationPreferencesRepository,
│                               AndroidNotificationPermissionStatusProvider,
│                               AndroidNotificationSoundPreviewer, AndroidDiagnosticsLogger,
│                               BundledAzanAudioSource, MediaPlayerAudioPlaybackEngine
├── feature/
│   ├── app/                    AppViewModel (theme)
│   ├── home/                   HomeUiState, HomeUiStateFactory, HomeViewModel, HomeScreen
│   ├── home/components/        Section composables (incl. CalculationCard)
│   ├── location/               Location state, view model, and the shared setup/management screen
│   ├── calendar/               Calendar state, view model, screen, calendar settings screen
│   ├── ramadan/                Ramadan state, view model, screen, reminder controls
│   ├── settings/               Settings state, view model, screen
│   ├── notifications/          Prayer notification settings state, view model, screen
│   ├── qibla/                  Qibla state, view model, screen, labels, compass dial
│   ├── tasbih/                 Tasbih state, state factory, view model, screen, confirmations
│   └── common/                 PlannedFeatureScreen, LocationFormState, resource mappers
└── navigation/                 SalahDestination, SalahNavHost, SalahApp
```

The Bangladesh geography is data rather than code: `app/src/main/assets/location/bd_areas.tsv` and
`bd_boundary.tsv` are generated by `tools/generate_bd_location_dataset.py` and parsed by
`core/domain/location/BangladeshLocationDataset`. See [`LOCATION_SYSTEM.md`](LOCATION_SYSTEM.md).

## 4. Layers and dependency rules

```text
UI (feature/*, navigation, designsystem)
        │  observes immutable StateFlow<UiState>
        ▼
ViewModel (feature/*)  ── combines repository flows, no business rules
        │
        ▼
Domain (core/domain)   ── resolver, provider port, repository interfaces, calculators, state types
        │
        ▼
Data (data/*)          ── DataStore, device clock, Adhan engine, rule book, repositories
```

Enforced rules:

1. **UI never touches a repository, DAO or API.** Composables render `HomeUiState`/`SettingsUiState`
   only.
2. **ViewModels contain no astronomical or calendar computation.** `NextPrayerResolver`,
   `CountdownFormatter` and `ProhibitedPeriodCalculator` live in `core/domain` and are pure.
3. **UI never formats dates, numbers or countdowns by itself.** Formatting happens in the state
   factories — `HomeUiStateFactory`, `RamadanUiStateFactory`, `CalendarUiStateFactory`,
   `TasbihUiStateFactory` — which need no `Context` and are unit-tested directly; the countdown is a
   string produced from a resolved `Duration`.
4. **Business logic depends on interfaces** (`PrayerTimesProvider`, `PrayerScheduleRepository`, …),
   never on a concrete client. The only place the Adhan engine is named outside its own file is
   `di/SalahModules.kt`.
5. **Domain models have no Android dependencies** — no `Context`, no `Resources`, no Compose.
   Resource mapping happens in `feature/common/DomainLabels.kt`.
6. **No circular dependencies**: `core` never imports `data` or `feature`; `data` never imports
   `feature`; `feature` imports `core` and is injected with `data` implementations by `di`. This is
   why `PrayerNotificationCoordinator` and `PrayerNotificationDeliverer` — orchestration over ports,
   with no Android import — live in `core/domain/notification`, while `AlarmManager`, `MediaPlayer`
   and `NotificationManager` implementations stay in `data/notification`. The notification settings
   screen therefore imports only `core`. `feature/qibla` follows the same rule: the sensors, the
   location service and the geomagnetic model sit behind ports in `core/domain/qibla`, so the
   screen, the state machine and the arithmetic are testable with fakes and only the thin Android
   adapters need a device. `LocationFormState` lives in `feature/common` because Settings and the
   Qibla screen share one manual-entry form and therefore one set of validation rules.
7. **Immutable state, unidirectional data flow**: repositories expose `Flow`, the ViewModel
   `combine`s them into one immutable state, the screen renders it and sends events back as method
   calls (`onRetry`, `setCalculationMethod`, `saveLocation`).
8. **Prayers and solar events are separate types** (`PrayerName` vs `SolarEvent`) so sunrise, solar
   noon and sunset can never be presented as prayers.

## 5. Offline-first data flow (what actually runs)

```text
DataStore (prayer settings, manual location) ─┐
Device clock (Gregorian date) ────────────────┼──► flows ─┐
                                              │           │
location + settings + 1s tick ──► request ────┘           │
                                     │                    │
                                     ▼                    │
                    PrayerScheduleRepository.ensureSchedule│
                      (AdhanPrayerTimesProvider when the   │
                       request-keyed cache misses)          ▼
                                              HomeUiStateFactory ──► StateFlow<HomeUiState>
                                                          ▲
                                                          │ pure mapping
                                               NextPrayerResolver
                                                          │
                            ProhibitedPeriodsRepository ──►┘
                              (rule book carries the documented policy)
```

- **The request is the cache key.** `HomeViewModel` derives a `PrayerScheduleRequest?` from the
  location, the (debounced, `distinctUntilChanged`) settings and the current moment, where the
  moment is converted into the location's zone before the date is taken. A change in the location,
  time zone, calculation method, Asr method, high-latitude rule, any manual adjustment or the
  calendar date produces a genuinely new request, which the repository answers by recalculating.
  The single `distinctUntilChanged` on the request is what prevents recalculation loops: the clock
  ticks every second but does not reach the repository unless the request actually changed.
- **Local reads never block.** `ensureSchedule` answers from an in-memory entry and never performs
  I/O on its first emission. Only a cache miss — or an explicit `refresh` — reaches the provider.
- **The cache is request-keyed, not time-keyed.** An entry is served only when its `date`,
  `location` **and** `profile` equal the request (`ProviderBackedPrayerScheduleRepository`). A
  mismatched provider response is refused as `Failed` and not cached.
- **No Room.** A schedule is cheap, deterministic arithmetic, so derived schedules are not
  persisted; the durable state is the preferences and the location in DataStore. See
  `docs/PRAYER_CALCULATION.md` §5 for the reasoning and the single extension point if caching is
  ever added.
- **The tick is lifecycle-scoped.** Both the moment flow and the UI state use
  `SharingStarted.WhileSubscribed`, so nothing keeps ticking in the background once the user leaves
  Home. No WorkManager job is involved: a countdown is not a deferrable task.
- **Failures are values.** Provider failures become `PrayerScheduleState.Failed` and arrive as
  `failureDetail` with a retry action; expected problems never throw. A network failure cannot erase
  settings or schedules: the optional publication checks (§13, §14) only ever *refresh* the inputs a
  schedule is calculated from, and any failure along that path leaves the bundled or last-known-good
  configuration in force.
- **Provenance travels with the data.** `DataSourceInfo(source, providerId, observedAt,
  isAvailableOffline)` is attached to every schedule state and rendered in the provenance card.

### 5.1 How a prayer schedule becomes a reminder

The notification subsystem is a **second consumer of the same repository**, not a second
calculation path:

```text
                        trigger (app start / settings / location / reboot / date /
                                 time-zone / app update / just delivered)
                                          │
                                          ▼
                       PrayerNotificationCoordinator.reconcile(reason)
                                          │
        ┌─────────────────────────────────┼─────────────────────────────────┐
        ▼                                 ▼                                 ▼
PrayerNotificationPreferences    LocationRepository              UserPreferencesRepository
      (DataStore)                     (DataStore)                    (DataStore)
        │                                 │                                 │
        │ settings                        │ location (zone) + profile        │
        ▼                                 ▼                                 │
   master switch off?            PrayerScheduleRepository.ensureSchedule◄───┘
        │ yes → cancel all, stop        (THE SAME offline-first repository
        │                                the Home screen uses)
        │                                 │ one schedule per date in
        │                                 │ the 2-day window
        │                                 ▼
        │                        PrayerNotificationPlanner  (pure)
        │                  schedules + settings + Clock instant
        │                                 │
        │                                 ▼
        │                  List<PlannedPrayerNotification>
        │                                 │
        └────────────► PrayerNotificationScheduler ──► AlarmManager
                       cancel(obsolete) + schedule(plan)   (exact, or windowed
                                                            fallback)
                                          │
                                          ▼
                             PrayerNotificationReceiver (fired alarm)
                                          │
                                          ▼
                             PrayerNotificationDeliverer
                     re-reads settings + permission + Clock
                                          │
                    ┌─────────────────────┴─────────────────────┐
                    ▼                                           ▼
        AndroidPrayerNotificationPresenter            AzanPlaybackManager
              (post / suppress / fail)                (play / unavailable)
```

What the diagram is asserting:

- **One calculation, two consumers.** The scheduler reads
  `PrayerScheduleRepository.ensureSchedule` — the same call `HomeViewModel` makes — so the times on
  screen and the times scheduled cannot diverge, and there is no second engine to keep in step.
- **The request is still the cache key.** The coordinator builds a `PrayerScheduleRequest` per date
  from the location and the persisted `PrayerCalculationProfile`, so a change to the calculation
  method produces a genuinely new schedule rather than a cached one.
- **The clock is injected, not read.** `Clock` is bound once in `di/SalahModules.kt` and every
  conversion into the location's zone is explicit, which is what makes time-change handling testable.
- **Planning is pure.** `PrayerNotificationPlanner` takes schedules, settings and an instant and
  returns reminders. It has no Android import, no coroutine and no repository, so the whole of
  "what should be scheduled" is unit-tested directly.
- **Nothing is derived twice.** The scheduler does not read the Home UI state and the UI does not
  read the alarm set; both are projections of the same repository and the same preferences.

### 5.2 How a stored location becomes a Qibla direction

```text
       QiblaScreen  (LifecycleEventEffect ON_START / ON_STOP)
            │
            ▼
       QiblaViewModel ──────────────┬───────────────────────────────────────┐
            │                        │                                       │
            │ starts collection      │                                       │
            ▼                        ▼                                       ▼
   CompassSensorSource      LocationRepository                   LocationProvider
   (cold flow; register     (the same DataStore the              (one-shot on request;
    on collect, unregister   prayer engine and Settings           manual entry needs
    in awaitClose)           already use)                         neither permission
            │                        │                            nor network)
            │ CompassReading         │ LocationInfo
            │ (magnetic azimuth +    │ (origin, accuracy, acquiredAt)
            │  accuracy + tilt)      │
            ▼                        ├──► GeomagneticDeclinationProvider ──► declination
      HeadingSmoother                │        (GeomagneticField, on device)
      (unit-circle average,          │
       adaptive weight)              └──► QiblaCalculator ────────────────► QiblaBearing
            │                                    (pure; refuses invalid,           │
            │ smoothed magnetic heading           at-Kaaba and antipodal)          │
            ▼                                                                     │
      HeadingMath.toTrueHeading  ◄──── declination ─────────────────────────────┐  │
            │  TrueHeading                                                      │  │
            └───────────────────────► HeadingMath.turnFromTrue ◄───────────────┘  │
                                             │                                    │
                                             ▼                                    ▼
                                  QiblaStateReducer.phase(...)  ◄── sensor availability,
                                             │                      LocationFreshness
                                             ▼
                                        QiblaUiState
```

What the diagram is asserting:

- **Three state holders, because they change at wildly different rates.** The location snapshot is
  recomputed only when the stored location changes, so the declination model is not evaluated and
  DataStore is not read at 10 Hz — a sensor sample costs one pure calculation.
- **The bearing needs nothing but coordinates.** It is closed-form trigonometry, so it is produced
  with no sensor, no permission and no network. Only the dial depends on hardware.
- **Declination crosses the boundary exactly once, as a type.** `toTrueHeading` consumes a
  `MagneticHeading` and produces a `TrueHeading`; nothing accepts a `TrueHeading` and adds
  declination, so double correction is unrepresentable rather than merely discouraged.
- **One location, one store.** The Qibla screen writes through the same `LocationRepository` and the
  same DataStore as the prayer engine and Settings, so the bearing can never be computed from a
  location other parts of the app do not see. A device fix obtained there is validated and attributed
  by the shared `BangladeshLocationPolicy` and `AreaResolver` (through `DeviceFixAdoptionPolicy`) before
  it replaces anything, so that screen cannot write a location the setup flow would refuse.
- **The state machine is decided in one tested place.** The precedence between "no location", "no
  bearing" and "no sensor" lives in `QiblaStateReducer`, not in a chain of `if`s in the layout.

## 6. Provider abstraction

```kotlin
interface PrayerTimesProvider {
    val id: String
    val source: PrayerTimeSource
    val isAvailableOffline: Boolean
    fun availability(): ProviderAvailability
    suspend fun schedule(request: PrayerScheduleRequest): PrayerTimesProviderResult
}
```

**Wired today:** `AdhanPrayerTimesProvider` (`data/prayer`) — the local astronomical engine. Its
`source` is `LOCAL_CALCULATION`, `isAvailableOffline` is `true`, and `availability()` is
`CONFIGURED`. It produces the five prayers and the true solar events, converts every value into the
prayer location's zone, and reports polar cases as `Unavailable` rather than guessing.

Planned implementations, each independent of the Home screen:

| Provider | Source kind | Entry criteria |
|---|---|---|
| AlAdhan API | `REMOTE_API` | Response validation, local caching, error/latency handling, documented licence terms |
| User-imported mosque timetable | `USER_ENTERED_TIMETABLE` | Import format defined; validation before storage |

Only one provider is bound at a time in `di/SalahModules.kt`; the UI never learns which one it is.

## 7. State handling

| Situation | Representation | Rendering |
|---|---|---|
| Sources not emitted yet | `HomeUiState.Loading` | `HomeSkeleton`: Home's own geometry with no value in it |
| Device date unreadable | `HomeUiState.Failed` | Error text + Retry |
| No location | `LocationSection.unavailable = NO_LOCATION_CONFIGURED` | The location label states it and opens the `location` destination; no times and no countdown are shown |
| No location for the schedule | `timetableNote = NO_LOCATION_CONFIGURED` | Note in the timetable; no times, no countdown |
| Twilight not reached (polar) | `timetableNote = CALCULATION_NOT_POSSIBLE` | Note explains it; no times, no countdown |
| Provider failed | `failureDetail` | Error banner with Retry; other sections remain |
| Nothing applicable in the rule book | `prohibited.unavailable = RULES_NOT_VERIFIED` | Explanation instead of intervals |
| Bangla/Hijri missing | `CALENDAR_NOT_IMPLEMENTED` | Explanation instead of a fabricated date |

### 7a. Where each Home field comes from

`HomeScreen` renders `HomeUiState` and nothing else: it never reads a repository, never formats a
date and never computes a countdown. Every displayed value is bound to application state.

| Home field | Source | Kind |
|---|---|---|
| Current prayer name, its start time, the boundary that closes its period, and the countdown | `CurrentPrayerPeriodResolver` over today's `PrayerSchedule` | local calculation |
| Today's five times, and the highlight on the one that is next | `PrayerSchedule` from `PrayerScheduleRepository.ensureSchedule` | local calculation, stored locally |
| Tomorrow's Fajr (so Isha's period can be closed at it) | the same repository, for the following local date | local calculation |
| Location label, coordinates, zone and origin | the persisted location, read at start-up into `StartupSnapshot` | persisted state |
| Calculation method, Asr method, high-latitude rule, manual adjustments, time format | persisted `PrayerSettings` | persisted state |
| Gregorian date, weekday, Bangla and Hijri dates | `CalendarRepository.observeToday()` (device clock plus the local converters) | local calculation, progressive |
| Prohibited intervals, their anchor events, phase and countdown | `ProhibitedPeriodsRepository` over the same schedule | local calculation, under the shipped (unreviewed) rule set |
| Failure detail and retry | `PrayerScheduleState.Failed` | derived |

**There is no remote source in this repository.** `AdhanPrayerTimesProvider` is the only bound
provider and it calculates on the device, so every field above works offline. The `REMOTE_API` and
`USER_ENTERED_TIMETABLE` sources exist in the model but have no implementation (§6).

**The countdown.** `HomeViewModel` holds one `StateFlow<ZonedDateTime>` that re-reads the clock once
per second while Home is subscribed. Both countdowns — the remaining time in the open period and
the time until the next prayer — are derived from that single tick and the same schedule, so they
cannot disagree about what time it is. The *schedule* is recalculated only when the request changes
(the local date at the saved location, the location, or the calculation profile), and the request is
exactly the repository's cache key. A tick therefore never recalculates a timetable. The tick is
started with `SharingStarted.WhileSubscribed` — the subscription that `collectAsStateWithLifecycle`
in `HomeScreen` owns — so it suspends when the screen is not visible.

**What Home does not show.** The Home layout renders no calculation card, no data-source card and
no per-prayer manual-adjustment disclosure. `HomeUiState.Ready.calculation`, `.provenance` and
`PrayerRow.adjustmentMinutes` all carry those values, and the timetable on the `prayer` destination
does disclose adjustments; whether Home should surface them too is a design question, not a missing
data source.

## 8. Design system and navigation

- `core/designsystem/theme`: emerald primary, warm ivory background, brass accent, slate for solar
  information; light and dark schemes — each declaring the **full** Material 3 role set, so no component
  falls back to Material's baseline lavender/plum — plus the fixed `SalahAccents` (brand green surface,
  theme-appropriate gold). `SalahTheme` resolves the mode, supplies the accents, and flips the
  system-bar icon colours from the *resolved* theme. See `docs/THEME_SYSTEM.md`.
- `core/designsystem/SalahGlyphs.kt`: icons drawn with Compose `Canvas`, so no icon library
  dependency exists.
- `navigation/SalahDestination`: the four bottom-bar destinations — `home`, `calendar`, `tools`,
  `settings` — plus `prayer`, `qibla`, `ramadan`, `location`, `calendar_settings` and
  `notifications`. Every route has a real screen behind it in `SalahNavHost`, and
  `feature/common/PlannedFeatureScreen` is now unreferenced. `bottomBarDestinations` is the bar's item
  list in declaration order and `SalahApp` renders it, so the bar cannot show a destination the graph
  does not declare and the order is written down once. `prayer` is the live prayer-period and
  timetable screen: it left the bar when `tools` took that place, it renders from the same
  `HomeViewModel` and the same schedule as Home's prayer content, and it is reached from the `tools`
  screen's first row and from Home's “View all” and “View details”. `tools` is a bottom-bar
  destination whose rows come from `ToolsEntry` — one `SalahDestination` member each, so no route
  string is spelled in the feature package — and `qibla` and `ramadan` are its rows as well as Home's
  daily tools; `location` is reached from Home's location label and from Settings; `notifications` is
  reached from Settings and is not in the bottom bar, because it is configuration rather than a place
  the user works. `calendar_settings` is reached from the Calendar screen and holds the one place a
  date can be affected from.
- Navigation state is saved/restored per destination (`saveState`/`restoreState`,
  `launchSingleTop`), so returning to Home keeps its state. The bar's items and the Tools screen's
  rows both open with `launchSingleTop`, so a repeated tap cannot stack a second copy of a screen;
  a Tools row deliberately saves no state, so back returns to the Tools list.

## 9. Testing strategy

| Layer | Covered by | Notes |
|---|---|---|
| Engine integration | `AdhanPrayerTimesProviderTest` | Pinned reference values (Dhaka, Karachi/Hanafi), event ordering, Asr-only change, method change, manual adjustments affecting prayers only, solar events independent of method, polar `Unavailable`, DST zone/offset, southern hemisphere, determinism |
| Next-prayer resolution | `NextPrayerResolverTest` | Sunrise excluded, exact-time boundary, no countdown after Isha, other-date schedule, incomplete schedule, cross-zone instants |
| Countdown formatting | `CountdownFormatterTest` | `Locale.ROOT`, negative clamped |
| Prohibited-window calculus | `ProhibitedPeriodCalculatorTest` | Anchors, ordering, zone, refusal on an unreviewed rule set, loud failure on a missing anchor (synthetic rule set, not a religious source) |
| Prohibited repository wiring | `RuleBookProhibitedPeriodsRepositoryTest` | Shipped policy → three windows plus the not-reviewed disclosure; reviewed set takes precedence; nothing applicable → `RULES_NOT_VERIFIED`; missing anchor → `NotAvailable` |
| Prohibited active/next selection | `ProhibitedPeriodSelectionTest` | The active window and the nearest upcoming one across the day, at exact boundaries, and with an empty day |
| Solar geometry | `SolarDayResolverTest` | Measured day/night lengths, solar noon vs clock noon, solar midnight as the anti-transit, leap day, next-local-date case, each field `null` when its event is absent, absent schedule |
| Night periods | `NightPeriodCalculatorTest` | Maghrib-to-next-Fajr interval crossing midnight, Islamic midnight as the midpoint, the last third, both Tahajjud conventions, every phase boundary, cross-zone phases, the next boundary and countdown, missing/invalid input |
| Night & solar Home mapping | `HomeNightSectionTest` | Both new sections mapped into `HomeUiState`, the advancing phase and countdown, missing-input cases |
| Location validation | `LocationInfoTest` | Ranges, IANA zone, blank label, NaN |
| Offline-first repository | `ProviderBackedPrayerScheduleRepositoryTest` | No location, engine not configured, cache reuse without re-calling the provider, recalculation on date/location/profile change, stale or wrong-place response rejection, exception → visible failure |
| Settings persistence | `DataStoreUserPreferencesRepositoryTest` | Real DataStore: persists/restores every setting including the calendar preferences, transactional updates, the Hijri offset clamped on write and read, safe fallback on unknown values, and calendar/prayer/theme preferences not overwriting one another |
| Home mapping rules | `HomeUiStateFactoryTest` | No fabricated data, countdown only for a real schedule, sunrise not a prayer, failure surfaced, time-format preference |
| First-launch location gate | `AppViewModelTest` | The decision the shell renders: fresh install → setup, stored usable location → the app, outside-Bangladesh and unknown-area locations → setup with the reason, unreadable dataset reported without locking out a valid location, a stored location decided without waiting for the dataset, and the gate moving in **both** directions as the location is saved and cleared |
| Location actions | `LocationViewModelTest` | A fix held for confirmation in the change context but stored on arrival on first launch, permission denial and timeout leaving the manual option, an out-of-country fix refused in both contexts, one request per tap, the hierarchy cascade dropping an incompatible child, a confirmed selection storing the chosen area's published centre, and two screens reading one saved location |
| Location selection wiring | `LocationSelectionWiringTest` | Exactly one selection screen and one hierarchical picker exist, the first-launch gate and exactly one navigation destination render that screen (with the first-launch and change contexts), and no per-entry copy of it is declared |
| Home data contract | `HomeViewModelTest` | Real resolvers and factory, faked repositories: the active period and countdown come from the repository's schedule for the saved location; the schedule is calculated once per request and not once per tick; a new local day and a new saved location each re-request; Home waits for a real schedule before showing times, but not for the calendar; no location is reported as such rather than fabricated. Runs on virtual time via `Dispatchers.setMain` — see §9.1 |
| Calendar rollover and day resolution | `DeviceClockCalendarRepositoryTest` | Local-midnight re-emission, no polling, DST-length local day, the Hijri month boundary at midnight, the date language, out-of-range reporting for both converters, a month laid out with real Bangla/Hijri dates and Ramadan day numbers, the Ramadan window and its movement with the Hijri offset |
| Bangladesh Bangla calendar | `BanglaCalendarTest` | Published 2019 rules (six 31-day months, Falgun 29/30, Chaitra 30) against reference dates, every month start, the 14 April New Year, leap years, a whole supported year round-tripped, refusal before 2019 and after 2399 |
| Hijri calendar | `HijriCalendarTest` | Umm al-Qura reference conversions, month boundaries on both sides, the user offset and its source label, 29/30-day Ramadan windows, the window search, observances, and the platform table's bounds |
| Ramadan and Sehri/Iftar rules | `RamadanCalendarTest`, `RamadanUiStateFactoryTest` | Sehri = Fajr and Iftar = Maghrib read from the real schedule, a manual Fajr adjustment moving Sehri with it, missing times producing no value, day numbering, phase boundaries, the daily table, provenance, and rendering in the location's zone |
| Month layout | `GregorianCalendarGridTest` | Seven cells per row, day order, padding, four different week starts, leap and century years, year transitions, extreme months |
| Date rendering | `CalendarFormattingTest` | Bangla numerals, the language preference choosing the locale, composed Bangla/Hijri strings that cannot mix scripts, the platform formatter for Gregorian dates |
| Calendar screen state | `CalendarUiStateFactoryTest` | Today vs selection vs both, three resolved date lines with the Hijri source, out-of-range stated rather than blanked, no window → no range, the adjustment moving the Hijri date and Ramadan number together |
| Bottom navigation and destination reachability | `SalahBottomNavigationTest`, `SettingsCategoryTest` | The bar is exactly Home, Calendar, Tools, Settings in that order; `prayer` left the bar but kept its route and resolves back from it; every destination's route is unique and round-trips through `fromRoute`; every Tools row opens a declared destination of its own that is not the Tools screen or a bottom-bar tab; every category likewise; and Home, Calendar, Settings, Prayer, Qibla and Ramadan all remain reachable |
| Ramadan reminders | `RamadanReminderPlanTest` | Sehri as a lead-in only, Iftar at Maghrib plus an optional lead-in, nothing outside Ramadan, switches and master switch, unique slots and request codes, a Ramadan end leaving nothing behind, inherited sound/vibration, lead-time clamping |
| Ramadan reminders, scheduled | `RamadanReminderCoordinatorTest` | What the alarm registry holds after a reconciliation: the Sehri lead-in at the calculated Fajr and Iftar at the calculated Maghrib, the Hijri offset deciding which days are in Ramadan (both directions) and the day that leaves Ramadan being cancelled, the last day of Ramadan keeping its reminders while the following day schedules none and then cancels them, one switch affecting only its own alarms, the master switch and a missing location leaving none, and repetition not duplicating an alarm |
| Notification planning | `PrayerNotificationPlannerTest` | Enabled/disabled, master switch, past events, advance reminders, sunrise separated from prayers, missing schedule, duplicates, deterministic order, cancellation set |
| Notification identity | `PrayerNotificationIdsTest`, `PrayerNotificationRequestTest`, `PrayerNotificationChannelIdTest` | Request-code uniqueness across 400 days × 6 targets × 2 kinds, URI round trip, malformed-input rejection, bounded and stable channel ids |
| Notification settings | `PrayerNotificationSettingsTest`, `DataStorePrayerNotificationPreferencesRepositoryTest` | Defaults, clamping, sparseness, real persistence across recreation, safe fallback on bad stored data, coexistence with the other preferences in one store |
| Scheduling, recovery, delivery | `PrayerNotificationCoordinatorTest`, `PrayerNotificationDelivererTest` | Reboot recovery, duplicate broadcasts, master switch, location/date/time-zone change, exact-alarm fallback, missing permission, one failure not aborting the rest, suppression rules, staleness, Azan attempt boundaries, structured logging with no coordinates |
| Qibla bearing | `QiblaCalculatorTest` | Two independently implemented formulations (a 3-D tangent-plane projection and the spherical law of cosines) checked against the production formula across a global grid, a frozen 13-city table, human-checkable sanity cases, range and wraparound, invalid and non-finite input, the poles, the Kaaba point, the antipode, and the great-circle distance against a chord derivation |
| Heading and north reference | `HeadingMathTest`, `HeadingSmootherTest`, `SensorFramesTest`, `CompassAccuracyGateTest` | Normalization and non-finite folding, signed shortest difference and its antisymmetry, declination applied once with the double-correction property asserted at a non-zero declination, measured smoothing lag bounds and jitter reduction, the four screen-rotation frames and their cycle, and the calibration warm-up gate |
| Qibla state and inputs | `QiblaStateReducerTest`, `LocationProviderSelectionTest`, `LocationFreshnessTest` | Every phase and the precedence between them, every calibration advice, exhaustiveness over the accuracy values, provider ordering per granted precision, and both freshness window boundaries including clock skew |
| Location provenance | `DataStoreLocationRepositoryTest` | Real DataStore on a temporary file: manual and device round trips, metadata not inherited on a manual save, implausible accuracy discarded, recreation, clearing without touching other preferences, stores written before origins were recorded, and partial writes |
| Azan playback | `AzanPlaybackManagerTest` | Single instance, stop, release, missing asset, engine failure — against a fake engine; device behaviour is unverified because no recording is bundled |

Not yet covered (and therefore not claimed): Compose UI tests, instrumentation tests, Android's
actual alarm and notification delivery on a device, `MediaPlayer` against real audio, real sensor
delivery and the three-family fallback, `remapCoordinateSystem` behaviour under a physical screen
rotation, `GeomagneticField` declination values, a real location fix, and any religious-correctness
validation, which requires verified timetables and review rather than unit tests. The device-only
scenarios for both features are listed, unperformed, in
[`PRAYER_NOTIFICATIONS_TESTING.md`](PRAYER_NOTIFICATIONS_TESTING.md) §4 and
[`QIBLA_COMPASS_TESTING.md`](QIBLA_COMPASS_TESTING.md) §4. The engine's reference values are regression values from the pinned engine version through
Salah's mapping, not an independent verification — see `docs/PRAYER_CALCULATION.md` §6.

### 9.1 Testing the view models on the JVM

View models reach `android.os` indirectly: `HomeViewModel` records start-up timing through
`StartupTrace`, which reads `Process.getStartElapsedRealtime()`, `SystemClock.elapsedRealtime()` and
`Log`. Those are stubs in a JVM unit test and throw `Method … not mocked` unless the unit-test
variant returns default values, so `app/build.gradle.kts` sets
`testOptions.unitTests.isReturnDefaultValues = true`. With that, the trace is inert in tests — a `0`
elapsed time and a no-op log line — and the view model's flow assembly is testable without an
emulator or Robolectric.

Nothing else in the test source set imports `android.*`, so no existing test relied on a stub
throwing and the flag cannot hide a real "not mocked" failure elsewhere. `HomeViewModelTest`,
`AppViewModelTest` and `LocationViewModelTest` each install a `StandardTestDispatcher` as
`Dispatchers.Main` (the dispatcher behind `viewModelScope`) and pass the same dispatcher to
`runTest`, so the view model and the test share one scheduler and one virtual clock. The clock itself
is a hand-moved `Clock` (or a fixed one), so no test depends on the wall clock or on the machine's
time zone. Where a test needs "the view model is still waiting" as an observable state — a dataset
read, a location fix — the fake holds its answer on a `CompletableDeferred` rather than delaying.

## 10. Extension points

- **Add or replace a provider**: implement `PrayerTimesProvider`, bind it in
  `di/SalahModules.kt`. No UI change is required; the schedule populates the existing sections.
- **Persist schedules**: add Room beside the in-memory cache inside
  `ProviderBackedPrayerScheduleRepository`. The repository interface does not change.
- **A different Hijri source (for example a moon-sighting feed):** add a `HijriDateSource` constant
  and a converter beside `HijriCalendar` that can set it, then bind it where the screens read the
  calendar. Only that source may report a confirmed date; the current constants are both estimates by
  construction, and `docs/CALENDAR_SYSTEM.md` §5.2 records why.
- **A different Bangla or Hijri rule set**: the converters are pure and take no device state, so a
  variant is a new class behind `CalendarRepository` plus its own tests. `BanglaCalendar` documents
  the range it is verified for, which any replacement must do too.
- **Prohibited periods**: the configured policy ships and is disclosed; add one reviewed `ProhibitedRuleSet`
  calculator, repository and state types already exist and are tested.
- **Notifications**: implemented. `PrayerNotificationPlanner` is the only place that decides what
  is scheduled, so a new reminder type (a Ramadan reminder, a Qibla alert) is a change to the
  planner plus a `ReminderTarget` constant — the coordinator, receivers, channels and UI need no
  new mechanism.
- **Azan audio**: implement `AzanAudioSource` to return a licensed recording (or return it from
  `BundledAzanAudioSource.resolve()`) and playback begins working, because the lifecycle, focus,
  single-instance and release rules already exist and are tested. A foreground-service variant for
  unattended long-form playback is a separate, deliberate piece of work — see
  `PRAYER_NOTIFICATIONS.md` §5.4.
- **A different time-critical mechanism**: implement `PrayerNotificationScheduler`. The planner,
  coordinator, delivery path and UI are unaffected.
- **A different sensor strategy**: implement `CompassSensorSource`. The smoothing, the declination
  step, the state machine and the screen are unaffected — which is why the three-family fallback was
  cheap to add and is cheap to extend.
- **A different declination model**: implement `GeomagneticDeclinationProvider`. The correction step
  and the north-reference types do not change.
- **Remote calculation settings**: implemented in Phase 3 (§14) through the same publication seam as
  §13. A new supported value is added by extending the explicit mapping in `PrayerConfigurationParser`
  *and* the engine mapping in `AdhanPrayerTimesProvider` together, so an unknown value is rejected
  rather than silently dropped.
- **A Qibla map or a remote computation**: not implemented, and the bearing is closed-form and offline by
  design. The `INTERNET` permission the app now holds (§13) is used *only* for the optional dataset and
  prayer-configuration publication checks; no Qibla feature opens a connection.
- **Splitting modules**: move packages into `core:*`, `data:*`, `feature:*` Gradle modules; the
  rules in §4 are already the intended boundaries.

## 11. Known limitations of this phase

- Prayer times are calculated locally but have **not** been compared against any published
  authority timetable; no such claim is made. (`docs/PRAYER_CALCULATION.md` §6.)
- Prohibited prayer windows are **implemented and configured** with this application's documented
  policy. Confirming the boundaries is a data change: add a reviewed `ProhibitedRuleSet`. See
  `docs/FORBIDDEN_PRAYER_TIMES.md`.
- Solar midnight is an **approximation** of the true lower culmination (the transit plus twelve
  hours), and Tahajjud's start is a **documented convention** (the last third), not a ruling. Both
  are derived from the schedule's own events and neither is aligned to any authority timetable. See
  `docs/SOLAR_AND_NIGHT_PERIODS.md`.

## 12. Local dataset foundation

This section describes the local half of Salah's data story: which dataset the app is running on, how a
validated one safely replaces it, and what is guaranteed when that goes wrong. It was written before a
remote tier existed, and it remains the description of the mechanism a published dataset arrives through:
the remote path added in Phase 2 (§13) supplies a `DatasetPayload` and calls `LocalDatasetStore.activate`,
so the arrival was a change of supplier rather than a change of architecture. Read this section for the
local guarantees and §13 for the network path that feeds it.

### 12.1 The model

```
bundled assets  ──►  active dataset  ──►  UI
   (always present)     (may be applied)
```

- **Bundled assets** (`app/src/main/assets/location/*.tsv`) are the floor. They ship inside the APK, are
  never overwritten, and are the dataset on a fresh install and after any failure.
- **An applied dataset** is one that passed validation and now lives in the application's own storage.
- `BangladeshAreaCatalog` is the port; `LocalDatasetStore` implements it by putting an applied dataset in
  front of `AssetBangladeshAreaCatalog` and falling back to it. **Callers are unchanged** — the setup
  gate, the picker, the search, the area resolver and the locality resolver all ask for "the dataset" and
  receive whichever is in force.

### 12.2 Storage layout

```
<filesDir>/datasets/bd_locations/
  active.tsv                     the metadata record: the active payload, and the one before it
  payloads/
    <sha256>/                    one directory per applied payload, named by its own checksum
      dataset.version            the payload describes its own version
      bd_areas.tsv
      bd_boundary.tsv
      bd_localities.tsv
    .staging-<n>/                an in-flight write; never referenced by the record
```

The payload files are **the same three tab-separated texts, parsed by the same parser** as the bundled
assets. There is one format and one parser, so a future published payload is not a new kind of data —
it is the same data, movable. The bundled TSV files are not regenerated, reformatted or re-verified by
anything in this design.

The dataset lives under `filesDir` rather than in DataStore on purpose: what matters about it is that it
is consistent *with the files beside it*, so it is kept beside them. DataStore remains the home of user
preferences and the stored location, which is what it is good at.

### 12.3 Safe activation, and the one operation it depends on

A multi-step write cannot be made atomic; the design instead arranges the steps so that only **one** of
them needs to be, and so that every interruption before it leaves the previous dataset intact:

1. write the payload into a fresh `.staging-<n>` directory and flush it to storage;
2. rename that directory to `payloads/<checksum>` — a single directory rename. Until step 3 the
   directory is unreferenced, so publishing it early is harmless;
3. rewrite `active.tsv` by writing a sibling temporary file and renaming it over the old one. **This
   single rename is the commit point.**
4. delete payload directories the new record does not name. Housekeeping only.

The invariant that makes this safe: **the record is the only pointer, and it is only ever moved after
the thing it points at is complete.** Step 1 is the only place a partial dataset can exist, and nothing
references it. The previous applied payload is retained alongside the new one, so a payload that was
valid on activation and is unreadable later is recovered from rather than lost.

**Recovery states, all covered by tests** (`LocalDatasetStoreTest`):

| State on disk | Result |
|---|---|
| No record | Bundled dataset |
| Record names a payload directory that is absent | Bundled dataset |
| Record's version disagrees with the payload's own version | Bundled dataset |
| Payload bytes do not match the checksum that names the directory | Bundled dataset (and the fallback is logged) |
| Payload parses but is structurally defective | Bundled dataset |
| Active payload unreadable, previous payload readable | Previous applied payload |
| Both unreadable | Bundled dataset |
| Stray `.staging-<n>` directory present | Ignored, then discarded; active dataset unaffected |

Reading never trusts the record: each named payload is re-read, its checksum recomputed from the bytes
on disk and compared to the directory name. A record that disagrees with the bytes is a *detected*
condition, not a corrupted dataset.

### 12.4 What a checksum does and does not establish

The checksum detects **accidental corruption** — a truncated write, a half-published payload, contents
that are not what was published. It does **not** establish authenticity: whoever can change the payload
can change the checksum beside it. There is no key-management design in this phase, so no signature is
pretended. When published datasets arrive, integrity and authenticity are separate decisions.

`fsync` is applied to payload and record writes before their rename. This defends without qualification
against **process death**; against power loss it is best-effort, because a directory rename is durable
only once the *directory* is synced and not every platform permits opening one. The commit point is the
rename, not the flush. No test here claims to prove power-loss durability.

### 12.5 Stable location identity

An area's **identity** is its id, level and parent. Its **description** is its name, Bangla name and
coordinates. `AreaIdentity` and `DatasetIdentity` keep those apart, and the rules are:

- an existing id keeps meaning the same place: ids are never regenerated from names or list positions;
- a corrected name, Bangla name or coordinate changes nothing about identity;
- removing an id, changing its level or changing its parent **is** a breaking change, because it can
  invalidate a location a user has already saved;
- adding an area is an extension and invalidates nothing;
- reusing a retired id for a different place is detected through level or parent, not through the name.

Two things enforce it:

1. **At runtime**, `DatasetValidator` compares a candidate's identity against the active dataset's and
   rejects any breaking change. This is the seam a published dataset must pass.
2. **In CI**, `BundledDatasetIdentityTest` compares the shipped dataset against the checked-in baseline
   `app/src/test/resources/location/bd_identity.baseline.tsv`, which contains only `level`, `id` and
   `parent id`. A legitimate data change is **reviewable** — it shows up as a small diff to a file whose
   only content is identity — and cannot pass unnoticed. A corrected label or coordinate does not appear
   in that file at all, so ordinary edits do not fail the build.

### 12.6 The location-compatibility guarantee

`LocationSetup.evaluate` reports `Required(AREA_NOT_IN_DATASET)` when the stored location's area is not in
the dataset, and the shell answers that by replacing the navigation graph with the setup screen. That is
correct for a location that genuinely cannot be resolved, and unacceptable for one that could have been
if the update had been refused first. So:

- a candidate that does not contain the area of the **saved location** is rejected before anything is
  written (`DatasetProblem.StoredLocationWouldBreak`);
- a rejected candidate changes nothing, so the saved location never becomes unresolvable because of one;
- `LocalDatasetStore` takes the stored location only as an **input** to validation and has no way to
  write one, so no failed update can clear it;
- when a location truly cannot be resolved against any available dataset, the setup screen is still shown
  — and now explains why, rather than presenting a bare first-launch prompt.

### 12.7 Backup and restore ownership

| Data | Backed up | Why |
|---|---|---|
| Preferences and the saved location (`files/datastore/`) | **Yes** | Small, user-owned, not reconstructible |
| Applied dataset payloads (`files/datasets/`) | **No** | Replaceable, potentially large, and a stale copy restored over a newer one is worse than nothing |
| Bundled assets | n/a | Inside the APK |

Because only `<include>` entries appear in the rule files, everything not listed is excluded, so the
dataset cache needs no explicit `<exclude>`. `BackupRulesConfigurationTest` asserts these properties as
configuration, including that the manifest still points at the rule files.

**Manual verification still required.** A JVM test cannot prove that the platform captures and restores
the files. To verify on a device or emulator:

1. set a theme, a language and a manual area selection;
2. record them, then run `adb shell bmgr backupnow <applicationId>` (or use Settings → System → Backup);
3. clear the app's data, then restore (`adb shell bmgr restore <token> <applicationId>`), or perform a
   device-to-device transfer with a cable;
4. confirm the theme, the language **and the saved location** survive, and that the app opens on Home
   rather than on location setup.

Step 4 is the assertion that matters: losing the saved location is exactly the failure the previous
`sharedpref`-only configuration produced, and it presents as a completed setup being asked for again.

### 12.8 Extension points (as of the local foundation)

- **A published dataset**: supply a `DatasetPayload` and call `LocalDatasetStore.activate`. Everything
  else — validation, atomicity, recovery, identity protection — is already in place and tested.
- **A manifest**: version comparison is `DatasetVersion.isSameAs`, deliberately equality-only. Ordering,
  if ever wanted, belongs in a separate publication field rather than smuggled into the version string.
- **A second dataset**: the root is namespaced per dataset (`datasets/bd_locations`), so published
  timetables or Ramadan calendars can be added without moving this one.
- **A remote publication**: implemented in Phase 2 over this exact seam — the synchroniser supplies a
  `DatasetPayload` and calls `LocalDatasetStore.activate`. See §13.
- **Still not planned in this phase**: remote theming, remote Ramadan data, a published prayer timetable,
  Room or KSP. Nothing here depends on any of them.

## 13. Remote dataset publication and synchronisation

Phase 2 adds the first real remote-data path. It is deliberately the *smallest complete* one — the
Bangladesh location dataset — because its purpose is to establish a working, secure, reusable end-to-end
pattern, not to move every dataset online.

```
bundled verified fallback
        ▼
active local dataset  ──►  UI  (reads local data only)
        ▲
        │ validated activation (Phase 1, unchanged)
        │
  DatasetSynchronizer ──►  RemoteDatasetSource ──►  Supabase REST + Storage
        │
        └─ DatasetSyncStateStore (throttle + last attempt)
```

The dependency direction is `UI → repository/use cases → synchroniser → remote source + local store`.
No composable ever calls Supabase; remote work is never on the path to drawing a screen.

### 13.1 The publication contract

A manifest is one small row describing one immutable release: `dataset_id` (the slot), `publication_id`
(the opaque release id), `version`, `payload_schema_version`, `status`, `checksum`, `payload_path`, and
optionally `payload_size_bytes`, `area_count`, `locality_count`, `boundary_ring_count`, `published_at`,
`notes`. It is read from the `dataset_current_publication` view over PostgREST and parsed by
`DatasetPublicationParser`. `docs/DATABASE-SCHEMA.md` is the schema reference.

**Version semantics.** `version` is descriptive and is *never* used to decide which release is newer —
comparison is `DatasetVersion.isSameAs`, equality only. *Which* publication differs is decided from
`publication_id`/`checksum`, not from any ordering of strings.

**Only published releases are served.** The app re-checks `status == published` even though the view
already filters it, and the database refuses to point the current-publication pointer at a non-published
row. Both ends enforce it.

### 13.2 The payload and its checksum

The payload is one immutable ZIP archive holding the same three tab-separated tables and the version
file the bundled assets hold, decoded by `DatasetPayloadArchive` into the existing `DatasetPayload`. The
checksum published in the manifest is the `DatasetChecksum` of those **logical parts**, not a hash of the
archive, and the app recomputes it from the decoded payload — so compression framing is not trusted and
the value is one the local activation path independently agrees with. See §12.4 for what a checksum
does and does not establish.

### 13.3 The synchronisation sequence

1. fetch the manifest (cheap; no payload);
2. validate it — well-formed, supported schema, published, checksum-shaped;
3. compare with the active dataset (equal checksum → **download nothing**);
4. fetch the payload under a size ceiling;
5. decode the archive;
6. verify the payload checksum against the published one;
7. verify the asserted record counts;
8. hand the payload to `LocalDatasetStore.activate` (Phase 1) with the user's saved area as an input.

**Every failure leaves the active dataset exactly as it was.** The synchroniser has no branch that writes
user data, clears the stored location, or removes a dataset; its only mutation is `activate`, which itself
writes nothing unless the whole candidate passes validation. A failed synchronisation cannot make the app
unavailable.

**Failure handling.** No network, DNS/connection failure, timeout, HTTP error, missing or malformed
manifest, unsupported schema, unpublished release, oversized payload, invalid checksum, truncated archive,
invalid TSV, duplicate ids, a missing saved location, a local write failure and interrupted activation are
all handled as *values*: `DatasetSyncOutcome` distinguishes "no update available" (`UpToDate`) from "the
sync failed" (`Failed`) and from "the candidate was unacceptable" (`Rejected`). Retries are bounded by the
throttle, never a loop.

### 13.4 Triggers and throttling

A check is attempted at application start-up and when the app comes to the foreground, both off the main
thread and after Home's own work, so neither can delay the first frame or the location gate.
`SyncThrottlePolicy` allows at most one background check per 12 hours after a success; after a failure it
retries in 15 minutes and **doubles that wait on each consecutive transient failure**, capped at 6 hours, so
a device that stays offline is not retried on every foreground trip and does not produce a retry storm when
it reconnects. A small injected jitter (up to a fifth of the interval) spreads retries across devices. A
user-initiated (`forced`) check is never suppressed. This is deliberately *not* a poll: there is no timer,
and nothing checks on a recomposition or a UI event.

Before any request, both synchronisers ask `NetworkStatusProvider` whether the device is *clearly* offline;
if it is, the attempt returns `Offline` without touching the network and **without recording an attempt**, so
returning to connectivity is not delayed by a backoff window. The check fails open — a captive portal passes
it and is caught by the transport — and there is no continuous connectivity observer.

`FileDatasetSyncStateStore` keeps `lastAttemptAtEpochMillis` (moved by every attempt), `lastSuccessAtEpochMillis`
(moved **only** by a success, so a failed request is never reported as fresh), `lastAttemptedPublicationId`,
`lastFailureCode` and `consecutiveFailures`, under `files/datasets/` — machine state, outside the backup set
(§12.7). The file is forward/backward-compatible: a file written before the freshness fields existed reads
them as defaults. It also lets a publication that was downloaded and then *terminally* declined (a malformed
manifest, an unsupported schema, a checksum mismatch, a rejected candidate) avoid being re-downloaded on
every launch, while a transient failure is retried with the bounded backoff above.

The full reliability contract — offline guarantees, the synchronisation lifecycle, manifest and checksum
validation, retry/timeout policy, cache freshness, atomic activation and recovery, temporary-file cleanup,
the diagnostic events and the tests that pin each claim — is documented in
[`DATA_RELIABILITY.md`](DATA_RELIABILITY.md).

### 13.5 Bundled fallback and first install

The first-install offline path is mandatory and unchanged: the app opens and works with **no** Supabase
URL, **no** key, no network, no published dataset and no successful synchronisation. The bundled assets
remain the permanent recovery floor. A missing configuration does not crash the app and is reported as
`dataset_sync_not_configured`, not as a successful sync.

### 13.6 Security model

App clients read only through the `dataset_current_publication` view (and the read policy on the private
`datasets` Storage bucket); the base tables have RLS enabled with no client policy and their privileges are
revoked, so a client cannot publish, edit or delete. Publishing is a `service_role` operation. The APK
carries only the publishable/anon key, supplied as two non-secret Gradle properties; a privileged key must
never appear in the app. See `docs/DATABASE-SCHEMA.md` and `docs/SUPABASE_SETUP.md`.

### 13.7 Networking choice

One minimal HTTP surface (`HttpTransport`), implemented with the JDK's `HttpURLConnection`, for exactly two
GETs. No Retrofit, OkHttp, Ktor or Supabase SDK was introduced: two reads with explicit timeouts and a body
ceiling do not justify a general client, and a Supabase Kafka/Kotlin SDK's real API and version were not
assumed. The transport is faked in tests; the synchroniser above it is fully unit-tested without a socket.

### 13.8 Reuse for future datasets

The slot-based schema and the `datasetId`-keyed manifest mean a future published dataset type reuses the
same shape, synchroniser structure and activation seam, in a new slot, without an architectural change.
Phase 3 has already taken that step for one such type — the prayer configuration (§14), which keeps its own
tables and view while following the same rules — without touching the prayer-time calculation engine.
Theme presets, published Ramadan calendars, Hijri adjustments and general feature configuration remain
future and would follow the same pattern.

> **Status.** The remote path is implemented and unit-tested against fakes. No Supabase project is
> configured for this repository, so neither migration has been applied and no remote publication or live
> integration has been verified. See `docs/SUPABASE_SETUP.md` §5 for what still needs a live test.

## 14. Remote prayer configuration and settings resolution

Phase 3 adds the second published slot. It reuses the publication pattern of §13 — immutable revisions, a
single current-publication pointer, one client-readable view, service-role-only publishing — for a
different payload, and it does **not** touch the calculation engine.

```
bundled default (permanent floor)
      ▲
      │ resolution precedence (deepest first)
      │   user override → upazila → district → division → global → bundled
      ▼
effective PrayerCalculationProfile ──► AdhanPrayerTimesProvider (unchanged)
      ▲
      │ validated, atomically activated snapshot
      │
PrayerConfigSynchronizer ──► RemotePrayerConfigSource ──► prayer_config_current_publication (Supabase)
      │
      └─ PrayerConfigSyncStateStore (throttle + last evaluated revision)
```

### 14.1 What it configures

Exactly the inputs the engine already consumes, and nothing it cannot represent:

| Setting | Supported values |
|---|---|
| Calculation method | the eleven methods `AdhanPrayerTimesProvider` maps |
| Asr convention | `STANDARD`, `HANAFI` |
| High-latitude rule | `AUTOMATIC`, `MIDDLE_OF_THE_NIGHT`, `SEVENTH_OF_THE_NIGHT`, `TWILIGHT_ANGLE` |
| Prayer adjustments | ±60 minutes per obligatory prayer (sunrise, noon and sunset are solar events and carry none) |

The value of every setting is a `PrayerCalculationProfile` — the same object the engine already takes — so
a remote configuration can never express anything the engine cannot calculate. There is no separate
on-disk format either: the stored snapshot is re-parsed by the same `PrayerConfigurationParser` that reads
a downloaded one, and its writer emits a canonical byte-identical form. Every named value is mapped
explicitly, and an unknown method, Asr convention, high-latitude rule, scope or prayer is reported as a
problem rather than dropped — because dropping it would silently change the prayer times a publisher
intended.

### 14.2 The model and the revision rules

A publication is one complete snapshot: `configuration_id`, an immutable `revision_id`, a monotonic
`revision_number`, a `schema_version`, the global profile, and optional per-area profiles keyed by the
**stable area id** of the verified dataset. `docs/PRAYER_CONFIGURATION.md` is the full reference and
`docs/DATABASE-SCHEMA.md` the schema reference. The rules the two ends enforce:

1. published revisions are immutable (a database trigger and an app check);
2. changing a published configuration creates a new revision;
3. an unsupported schema version is left alone, not guessed;
4. invalid parameter values are rejected;
5. a revision id reused with different content is rejected;
6. a revision that is not newer than the accepted one is refused;
7. draft/archived revisions never become active;
8. any failed download or validation preserves the last known-good configuration.

### 14.3 Resolution and precedence

Resolution uses the stable area ids the verified location dataset publishes; editable names and coordinates
are never keys, so manual selection works without the location permission and without GPS. The precedence,
deepest first:

```
explicit user override → applicable upazila/thana → district → division → global default → bundled default
```

`PrayerConfigResolver` is the one place this is applied and the one place the user override is layered on;
it is pure and synchronous, so every branch is directly testable. A field the user owns keeps the user's
value; every other field comes from the resolved chain, which is what lets a user correct only Asr without
losing a district's published Fajr and Isha settings. An area override that names an unknown area, or sits
at the wrong level, is *dropped* and falls back safely — it can never change a known area's times.

### 14.4 How it reaches the engine

Rather than teach every consumer about remote configuration, `PrayerConfigApplier` resolves the effective
profile once and writes the non-overridden fields back into the one `PrayerSettings` DataStore. Home, the
notification coordinator and the startup warm-up therefore read exactly what they always did, and
`AdhanPrayerTimesProvider` consumes the same `PrayerCalculationProfile`. Only the calculation fields are
written; the time format, theme, calendar settings, stored location and reminders are never touched.

### 14.5 Security

`prayer_config_publications` and `prayer_config_current_publications` have RLS enabled with **no**
`anon`/`authenticated` policy and their privileges revoked; clients read only the
`prayer_config_current_publication` view, which re-filters on `status = 'published'`. Publishing is a
`service_role` or migration operation. No service-role key, database password or privileged credential
appears in the app; only the project URL and publishable key are compiled in, and both are empty by
default. The dataset tables of §13 are untouched: the two slots are deliberately separate, so a change to
one cannot disturb the other.

### 14.6 Settings UI

The existing Prayer times screen shows the effective configuration's **origin** (bundled default, published
Bangladesh default, published area configuration, or the user's own override) and offers a **reset to
applicable defaults** action that releases the user-owned fields. It exposes only the settings the engine
can apply, labels them in Bangla and English through the existing resources, and never shows a raw payload
or a database detail.

> **Status.** Implemented and unit-tested against fakes, like §13. Not verified against a live Supabase
> project — see `docs/SUPABASE_SETUP.md` and `docs/PRAYER_CONFIGURATION.md` §11.
