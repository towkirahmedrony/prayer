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
- **Manual location** with validation, persisted locally, working with no permission and offline.
- Navigation: Home, Prayer, Calendar, Ramadan, Settings (bottom bar) plus a Qibla destination
  reached from Home.
- **Prayer notifications:** reminders scheduled at the calculated prayer times by `AlarmManager`,
  with per-prayer sound, vibration and advance-reminder settings, a global master switch, a separate
  sunrise reminder, notification channels, exact-alarm fallback, and recovery after reboot, clock,
  time-zone, date and app-update changes. Fully offline.
- **Azan playback machinery:** lifecycle, single-instance, audio-focus and release rules,
  implemented and unit-tested. No recording is bundled because no licence exists to redistribute
  one, so the settings screen reports the limitation rather than offering a dead control.
- Repository/provider boundaries for prayer schedules, prohibited periods, calendars, location,
  preferences and notification settings.
- Unit tests for domain, data and presentation-mapping logic.
- GitHub Actions workflow that runs tests, lint and builds the debug APK.

Deliberately **not** implemented (see §11): Bangla/Hijri conversion, prohibited-time rules, bundled
Azan audio, network providers, Room, widgets, Qibla compass, device location, foreground-service
playback.

Detailed notification documentation lives in
[`PRAYER_NOTIFICATIONS.md`](PRAYER_NOTIFICATIONS.md); its tests and the manual device checklist are
in [`PRAYER_NOTIFICATIONS_TESTING.md`](PRAYER_NOTIFICATIONS_TESTING.md).

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
│   │   ├── repository/         Repository interfaces (location, preferences, schedule,
│   │   │                       calendar, prohibited periods, notification settings)
│   │   ├── DiagnosticsLogger   Structured diagnostics port + SalahDiagnosticEvents
│   │   ├── NextPrayerResolver  Pure next-prayer resolution
│   │   ├── TimeFormatting      Shared clock-format helper (Home + notifications)
│   │   └── CountdownFormatter  Pure countdown formatting
│   └── designsystem/           theme/, glyphs, shared components
├── data/
│   ├── preferences/            DataStore-backed preferences (theme + prayer settings)
│   ├── location/               DataStore-backed manual location
│   ├── calendar/               Gregorian from the device clock, re-emitted at local midnight
│   ├── prayer/                 AdhanPrayerTimesProvider + offline-first schedule repository
│   ├── prohibited/             Rule-book-backed repository (ships unconfigured)
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
│   ├── settings/               Settings state, view model, screen
│   ├── notifications/          Prayer notification settings state, view model, screen
│   └── common/                 PlannedFeatureScreen + resource mappers
└── navigation/                 SalahDestination, SalahNavHost, SalahApp
```

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
   screen therefore imports only `core`.
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
- `navigation/SalahDestination`: the five bottom-bar destinations plus `qibla` and
  `notifications`. Unimplemented destinations render `PlannedFeatureScreen`, explicitly labelled
  “Not implemented yet”. `notifications` is a real destination reached from Settings and is not in
  the bottom bar, because it is configuration rather than a place the user works.
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
| Settings persistence | `DataStoreUserPreferencesRepositoryTest` | Real DataStore: persists/restores every setting, transactional updates, safe fallback on unknown values |
| Home mapping rules | `HomeUiStateFactoryTest` | No fabricated data, countdown only for a real schedule, sunrise not a prayer, failure surfaced, time-format preference |
| Calendar rollover | `DeviceClockCalendarRepositoryTest` | Local-midnight re-emission, no polling, DST-length local day |
| Notification planning | `PrayerNotificationPlannerTest` | Enabled/disabled, master switch, past events, advance reminders, sunrise separated from prayers, missing schedule, duplicates, deterministic order, cancellation set |
| Notification identity | `PrayerNotificationIdsTest`, `PrayerNotificationRequestTest`, `PrayerNotificationChannelIdTest` | Request-code uniqueness across 400 days × 6 targets × 2 kinds, URI round trip, malformed-input rejection, bounded and stable channel ids |
| Notification settings | `PrayerNotificationSettingsTest`, `DataStorePrayerNotificationPreferencesRepositoryTest` | Defaults, clamping, sparseness, real persistence across recreation, safe fallback on bad stored data, coexistence with the other preferences in one store |
| Scheduling, recovery, delivery | `PrayerNotificationCoordinatorTest`, `PrayerNotificationDelivererTest` | Reboot recovery, duplicate broadcasts, master switch, location/date/time-zone change, exact-alarm fallback, missing permission, one failure not aborting the rest, suppression rules, staleness, Azan attempt boundaries, structured logging with no coordinates |
| Azan playback | `AzanPlaybackManagerTest` | Single instance, stop, release, missing asset, engine failure — against a fake engine; device behaviour is unverified because no recording is bundled |

Not yet covered (and therefore not claimed): Compose UI tests, instrumentation tests, Android's
actual alarm and notification delivery on a device, `MediaPlayer` against real audio, and any
religious-correctness validation, which requires verified timetables and review rather than unit
tests. The notification subsystem's device-only scenarios are listed, unperformed, in
[`PRAYER_NOTIFICATIONS_TESTING.md`](PRAYER_NOTIFICATIONS_TESTING.md) §4. The engine's reference values are regression values from the pinned engine version through
Salah's mapping, not an independent verification — see `docs/PRAYER_CALCULATION.md` §6.

## 10. Extension points

- **Add or replace a provider**: implement `PrayerTimesProvider`, bind it in
  `di/SalahModules.kt`. No UI change is required; the schedule populates the existing sections.
- **Persist schedules**: add Room beside the in-memory cache inside
  `ProviderBackedPrayerScheduleRepository`. The repository interface does not change.
- **Bangla/Hijri dates**: implement a conversion in `data/calendar` returning
  `CalendarDateState.Available`; the Home screen already renders that shape.
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
- **Splitting modules**: move packages into `core:*`, `data:*`, `feature:*` Gradle modules; the
  rules in §4 are already the intended boundaries.

## 11. Known limitations of this phase

- Prayer times are calculated locally but have **not** been compared against any published
  authority timetable; no such claim is made. (`docs/PRAYER_CALCULATION.md` §6.)
- Prohibited prayer windows are **implemented but unconfigured**: the rule book ships empty, so the
  app reports the honest unavailability state.
- The schedule cache is in-memory only; process death clears it (and it is cheaply recalculated).
- No network client, no Room and no WorkManager job exist. The manifest declares exactly four
  permissions, all for notifications (`POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`,
  `SCHEDULE_EXACT_ALARM`, `VIBRATE`) — there is still **no** `INTERNET` permission.
- **No Azan recording is bundled.** The playback subsystem is implemented and tested; the asset is
  missing for licensing reasons, and the UI says so instead of offering a control that cannot work.
- Notification delivery has **not** been verified on a physical device. Reboot recovery, Doze
  behaviour, system permission surfaces and real alarm timing require hardware; the checklist is
  written but unperformed (`PRAYER_NOTIFICATIONS_TESTING.md` §4).
- Bangla and Hijri conversion are not implemented; only the device Gregorian date is available.
- Reason strings and UI text are English; full localisation and Bangla translations are future
  work.
