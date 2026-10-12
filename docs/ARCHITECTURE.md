# Salah — Architecture

This documents the architecture **as implemented in this repository**, together with the
dependency rules and the extension points for later phases.

## 1. Scope of this phase

Implemented:

- Gradle project with a version catalog, Kotlin DSL and wrapper `9.8.1`.
- Design system: emerald/ivory Material 3 theme, typography, shapes, hand-drawn glyphs.
- Salah Home screen: branding, date header, location, calculation indicator, next-prayer card,
  daily timetable, solar events, prohibited-times section, shortcuts, data-source status, plus
  loading/error/empty states and light/dark themes.
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
- Navigation: Home, Prayer, Calendar, Ramadan, Settings (bottom bar) plus a Qibla destination
  reached from Home.
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
- Unit tests for domain, data and presentation-mapping logic.
- GitHub Actions workflow that runs tests, lint and builds the debug APK.

Deliberately **not** implemented (see §11): an authority-confirmed Hijri date or Sehri/Iftar
 timetable, prohibited-time rules, bundled Azan audio, network providers, Room, widgets,
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
│   │                           ThemeMode, UnavailabilityReason
│   ├── domain/
│   │   ├── prayer/             PrayerTimesProvider (port), PrayerScheduleRequest/State
│   │   ├── prohibited/         ProhibitedWindowKind/RuleSet/RuleBook + ProhibitedPeriodCalculator
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
│   │   ├── location/           Bangladesh location domain, Android-free:
│   │   │                       BangladeshLocationDataset (parser), BangladeshArea/Level/Ref models,
│   │   │                       AreaResolver + AreaSelection + AreaResolution,
│   │   │                       BangladeshLocationPolicy + BangladeshCheck,
│   │   │                       DeviceFixAdoptionPolicy, LocationSetup, GeoMath, LocationFreshness
│   │   ├── repository/         Repository interfaces (location, preferences, schedule,
│   │   │                       calendar, prohibited periods, notification settings)
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
│   ├── prohibited/             Rule-book-backed repository (ships unconfigured)
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
3. **UI never formats dates or counts down by itself.** Formatting happens in
   `HomeUiStateFactory`; the countdown is a string produced from a resolved `Duration`.
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
                              (rule book ships unconfigured)
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
  `failureDetail` with a retry action; expected problems never throw. A network failure cannot
  erase settings or schedules because no network call exists in this phase.
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
| Sources not emitted yet | `HomeUiState.Loading` | Spinner + “Loading…” |
| Device date unreadable | `HomeUiState.Failed` | Error text + Retry |
| No location | `LocationSection.unavailable = NO_LOCATION_CONFIGURED` | Card explains it and links to Settings |
| No location for the schedule | `timetableNote = NO_LOCATION_CONFIGURED` | Note in the timetable; no times, no countdown |
| Twilight not reached (polar) | `timetableNote = CALCULATION_NOT_POSSIBLE` | Note explains it; no times, no countdown |
| Provider failed | `failureDetail` | Error banner with Retry; other sections remain |
| Rules not verified | `prohibited.unavailable = RULES_NOT_VERIFIED` | Explanation instead of intervals |
| Bangla/Hijri missing | `CALENDAR_NOT_IMPLEMENTED` | Explanation instead of a fabricated date |

An `Available` schedule also drives the calculation card (method, Asr method, high-latitude rule
when pinned, adjustment disclosure) so a time is never presented without the settings behind it.

## 8. Design system and navigation

- `core/designsystem/theme`: emerald primary, warm ivory background, brass accent, slate for solar
  information; light and dark schemes; `SalahTheme` also flips system-bar icon colours for the
  explicit light/dark overrides.
- `core/designsystem/SalahGlyphs.kt`: icons drawn with Compose `Canvas`, so no icon library
  dependency exists.
- `navigation/SalahDestination`: the five bottom-bar destinations plus `qibla`,
  `calendar_settings` and `notifications`. Only the `prayer` destination still renders
  `PlannedFeatureScreen`, explicitly labelled “Not implemented yet”; Calendar and Ramadan are real
  screens. `notifications` is a real destination reached from Settings and is not in the bottom bar,
  because it is configuration rather than a place the user works. `calendar_settings` is reached
  from the Calendar screen and holds the one place a date can be affected from. `qibla` is a real
  screen reached from the Home screen's Islamic tools, and is not in the bottom bar because it is a
  tool rather than a place the user dwells.
- Navigation state is saved/restored per destination (`saveState`/`restoreState`,
  `launchSingleTop`), so returning to Home keeps its state.

## 9. Testing strategy

| Layer | Covered by | Notes |
|---|---|---|
| Engine integration | `AdhanPrayerTimesProviderTest` | Pinned reference values (Dhaka, Karachi/Hanafi), event ordering, Asr-only change, method change, manual adjustments affecting prayers only, solar events independent of method, polar `Unavailable`, DST zone/offset, southern hemisphere, determinism |
| Next-prayer resolution | `NextPrayerResolverTest` | Sunrise excluded, exact-time boundary, no countdown after Isha, other-date schedule, incomplete schedule, cross-zone instants |
| Countdown formatting | `CountdownFormatterTest` | `Locale.ROOT`, negative clamped |
| Prohibited-window calculus | `ProhibitedPeriodCalculatorTest` | Anchors, ordering, zone, refusal on an unreviewed rule set, loud failure on a missing anchor (synthetic rule set, not a religious source) |
| Prohibited repository wiring | `RuleBookProhibitedPeriodsRepositoryTest` | Shipped rule book is empty → `RULES_NOT_VERIFIED`; a reviewed set produces windows |
| Location validation | `LocationInfoTest` | Ranges, IANA zone, blank label, NaN |
| Offline-first repository | `ProviderBackedPrayerScheduleRepositoryTest` | No location, engine not configured, cache reuse without re-calling the provider, recalculation on date/location/profile change, stale or wrong-place response rejection, exception → visible failure |
| Settings persistence | `DataStoreUserPreferencesRepositoryTest` | Real DataStore: persists/restores every setting including the calendar preferences, transactional updates, the Hijri offset clamped on write and read, safe fallback on unknown values, and calendar/prayer/theme preferences not overwriting one another |
| Home mapping rules | `HomeUiStateFactoryTest` | No fabricated data, countdown only for a real schedule, sunrise not a prayer, failure surfaced, time-format preference |
| Calendar rollover and day resolution | `DeviceClockCalendarRepositoryTest` | Local-midnight re-emission, no polling, DST-length local day, the Hijri month boundary at midnight, the date language, out-of-range reporting for both converters, a month laid out with real Bangla/Hijri dates and Ramadan day numbers, the Ramadan window and its movement with the Hijri offset |
| Bangladesh Bangla calendar | `BanglaCalendarTest` | Published 2019 rules (six 31-day months, Falgun 29/30, Chaitra 30) against reference dates, every month start, the 14 April New Year, leap years, a whole supported year round-tripped, refusal before 2019 and after 2399 |
| Hijri calendar | `HijriCalendarTest` | Umm al-Qura reference conversions, month boundaries on both sides, the user offset and its source label, 29/30-day Ramadan windows, the window search, observances, and the platform table's bounds |
| Ramadan and Sehri/Iftar rules | `RamadanCalendarTest`, `RamadanUiStateFactoryTest` | Sehri = Fajr and Iftar = Maghrib read from the real schedule, a manual Fajr adjustment moving Sehri with it, missing times producing no value, day numbering, phase boundaries, the daily table, provenance, and rendering in the location's zone |
| Month layout | `GregorianCalendarGridTest` | Seven cells per row, day order, padding, four different week starts, leap and century years, year transitions, extreme months |
| Date rendering | `CalendarFormattingTest` | Bangla numerals, the language preference choosing the locale, composed Bangla/Hijri strings that cannot mix scripts, the platform formatter for Gregorian dates |
| Calendar screen state | `CalendarUiStateFactoryTest` | Today vs selection vs both, three resolved date lines with the Hijri source, out-of-range stated rather than blanked, no window → no range, the adjustment moving the Hijri date and Ramadan number together |
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
- **Prohibited periods**: add one reviewed `ProhibitedRuleSet` to `ProhibitedRuleBook`; the
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
- **A Qibla map or a remote computation**: would need an `INTERNET` permission, which Salah
  deliberately does not hold. The bearing is closed-form and offline by design.
- **Splitting modules**: move packages into `core:*`, `data:*`, `feature:*` Gradle modules; the
  rules in §4 are already the intended boundaries.

## 11. Known limitations of this phase

- Prayer times are calculated locally but have **not** been compared against any published
  authority timetable; no such claim is made. (`docs/PRAYER_CALCULATION.md` §6.)
- Prohibited prayer windows are **implemented but unconfigured**: the rule book ships empty, so the
  app reports the honest unavailability state.
- The schedule cache is in-memory only; process death clears it (and it is cheaply recalculated).
- No network client, no Room and no WorkManager job exist. The manifest declares exactly six
  permissions, each tied to a feature (`POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`,
  `SCHEDULE_EXACT_ALARM`, `VIBRATE`, `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`) — there is
  still **no** `INTERNET` permission, and no background location.
- **No Azan recording is bundled.** The playback subsystem is implemented and tested; the asset is
  missing for licensing reasons, and the UI says so instead of offering a control that cannot work.
- **The Qibla bearing is exact; the compass is not.** The bearing is closed-form trigonometry over
  stored coordinates, verified against independent formulations. The dial's error is dominated by
  local magnetic interference and device magnetometer quality, which no software can correct, and
  the screen states that rather than implying a precision it does not have.
- **The Qibla feature has not been verified on a physical device.** Sensors, screen rotation,
  declination values and real location fixes require hardware; the checklist is written and
  unperformed (`QIBLA_COMPASS_TESTING.md` §4).
- Notification delivery has **not** been verified on a physical device. Reboot recovery, Doze
  behaviour, system permission surfaces and real alarm timing require hardware; the checklist is
  written but unperformed (`PRAYER_NOTIFICATIONS_TESTING.md` §4).
- **The Hijri date and the Ramadan window are estimates**, and Salah says so wherever they appear:
  Umm al-Qura is not the calendar Bangladesh's religious authorities announce Ramadan and the Eids
  with, and no moon-sighting source is integrated. Sehri and Iftar are the calculated Fajr and
  Maghrib, with no buffer and no claim to match any authority's timetable.
  (`docs/CALENDAR_SYSTEM.md` §5, §7.)
- **The calendar screens have not been verified on a physical device.** The converters, the state
  mapping, the persistence and the reminder planning are unit-tested; the layout, both themes, large
  font scales and a real device time-zone change are not.
- Reason strings and UI text are English, with a partial Bangla translation of navigation, calendar
  and prayer labels; the calendar's own date language is a separate, explicit setting. Full
  localisation is future work.
