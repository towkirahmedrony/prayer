# Salah — Architecture

This documents the architecture **as implemented in this phase**, together with the dependency
rules and the extension points for later phases.

## 1. Scope of this phase

Implemented:

- Gradle project with a version catalog, Kotlin DSL and wrapper `9.8.1`.
- Design system: emerald/ivory Material 3 theme, typography, shapes, hand-drawn glyphs.
- Salah Home screen: branding, date header, location, next-prayer card, daily timetable, solar
  events, prohibited-times section, shortcuts, data-source status, plus loading/error/empty
  states and light/dark themes.
- Minimum navigation: Home, Prayer, Calendar, Ramadan, Settings (bottom bar) plus a Qibla
  destination reached from Home.
- Settings: theme mode and manual location, persisted locally with DataStore.
- Repository/provider boundaries for prayer schedules, prohibited periods, calendars, location
  and preferences.
- Unit tests for the domain and presentation mapping logic.
- GitHub Actions workflow that builds the debug APK.

Deliberately **not** implemented (see §8): the prayer calculation engine, Bangla/Hijri
conversion, prohibited-time rules, notifications, network providers, Room, widgets, Qibla.

## 2. Gradle module decision

This phase uses **one Gradle module (`:app`) with package boundaries**, not the multi-module
layout (`core:*`, `data:*`, `feature:*`) sketched in the original README.

Why:

- A new module boundary costs configuration time and build overhead without buying isolation
  that a single package tree cannot already provide at this size.
- The dependency rules below are enforced by package structure and review, and the packages are
  named after the future modules, so extraction is a mechanical move rather than a rewrite.

Future extraction (`core:model`, `core:domain`, `data:prayer`, `feature:home`, …) must not
require changing business logic — only `build.gradle.kts` files and imports.

## 3. Package map (actual)

```
io.github.towkirahmedrony.salah
├── SalahApplication            Application + Koin bootstrap
├── MainActivity                Single-activity Compose host
├── di/                         SalahModules (Koin container)
├── core/
│   ├── model/                  Pure data: PrayerName, SolarEvent, PrayerSchedule,
│   │                           LocationInfo, PrayerCalculationProfile, CalendarSnapshot,
│   │                           ProhibitedPeriod, DataSourceInfo, ThemeMode, UnavailabilityReason
│   ├── domain/
│   │   ├── prayer/             PrayerTimesProvider (port), PrayerScheduleState
│   │   ├── repository/         Repository interfaces (location, preferences, schedule,
│   │   │                       calendar, prohibited periods)
│   │   ├── NextPrayerResolver  Pure next-prayer resolution
│   │   └── CountdownFormatter  Pure countdown formatting
│   └── designsystem/           theme/, glyphs, shared components
├── data/
│   ├── preferences/            DataStore-backed preferences
│   ├── location/               DataStore-backed manual location
│   ├── calendar/               Gregorian from the device clock; Bangla/Hijri unavailable
│   ├── prayer/                 Provider wiring + offline-first schedule repository
│   └── prohibited/             Honest "rules not verified" state
├── feature/
│   ├── app/                    AppViewModel (theme)
│   ├── home/                   HomeUiState, HomeUiStateFactory, HomeViewModel, HomeScreen
│   ├── home/components/        Section composables
│   ├── settings/               Settings state, view model, screen
│   └── common/                 PlannedFeatureScreen + resource mappers
└── navigation/                 SalahDestination, SalahNavHost, SalahApp
```

## 4. Layers and dependency rules

```
UI (feature/*, navigation, designsystem)
        │  observes immutable StateFlow<UiState>
        ▼
ViewModel (feature/*)  ── combines repository flows, no business rules
        │
        ▼
Domain (core/domain)   ── resolver, provider port, repository interfaces, state types
        │
        ▼
Data (data/*)          ── DataStore, device clock, provider implementations
```

Enforced rules:

1. **UI never touches a repository, DAO or API.** Composables render `HomeUiState` only.
2. **ViewModels contain no astronomical or calendar computation.** `NextPrayerResolver` lives in
   `core/domain` and is pure.
3. **UI never formats dates or counts down by itself.** Formatting happens in
   `HomeUiStateFactory`; the countdown is a string produced from a resolved `Duration`.
4. **Business logic depends on interfaces** (`PrayerTimesProvider`,
   `PrayerScheduleRepository`, …), never on a concrete client.
5. **Domain models have no Android dependencies** — no `Context`, no `Resources`, no Compose.
   Resource mapping happens in `feature/common/DomainLabels.kt`.
6. **No circular dependencies**: `core` never imports `data` or `feature`; `data` never imports
   `feature`; `feature` imports `core` and is injected with `data` implementations by `di`.
7. **Immutable state, unidirectional data flow**: repositories expose `Flow`, the ViewModel
   `combine`s them into one immutable state, the screen renders it and sends events back as
   method calls (`onRetry`, `setThemeMode`, `saveLocation`).
8. **Prayers and solar events are separate types** (`PrayerName` vs `SolarEvent`) so sunrise,
   solar noon and sunset can never be presented as prayers.

## 5. Offline-first data flow (what actually runs)

```
DataStore (preferences, manual location) ──┐
Device clock (Gregorian date) ─────────────┼──► flows ──► HomeUiStateFactory ──► StateFlow<HomeUiState>
In-memory schedule cache + provider state ─┘                     ▲
                                                                │ pure mapping
                                                  NextPrayerResolver
```

- Local state is the primary read source: `observeSchedule` never performs I/O.
- `PrayerScheduleRepository.refresh` is the only place that would call a provider; it converts
  every failure into a value (`Failed`, `Unavailable`) instead of throwing.
- A cached schedule is only served when both `date` **and** `location` match the request, so a
  schedule can never be reused for another day or city.
- The countdown ticks (1 s) **only** while an `Available` schedule exists; otherwise the time
  source emits once, so no fake countdown can run.
- Network failure cannot crash the app: there is no network call in this phase, and provider
  failures arrive as `failureDetail` with a retry action.
- Cached vs fresh: `DataSourceInfo(source, providerId, observedAt, isAvailableOffline)` is
  attached to every schedule state and rendered in the provenance card.

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

Wired today: `UnconfiguredLocalPrayerTimesProvider` → `availability() == NOT_CONFIGURED`, which
surfaces as “Prayer calculation is not configured yet.”

Planned implementations, each independent of the Home screen:

| Provider | Source kind | Entry criteria |
|---|---|---|
| Local astronomical engine (Adhan Kotlin candidate) | `LOCAL_CALCULATION` | Calculation profile decided and verified; pin a stable release; add time-comparison tests against verified timetables |
| AlAdhan API | `REMOTE_API` | Response validation, local caching, error/latency handling, documented licence terms |
| User-imported mosque timetable | `USER_ENTERED_TIMETABLE` | Import format defined; validation before storage |

Only one provider is bound at a time in `di/SalahModules.kt`; the UI never learns which one it is.

## 7. State handling

| Situation | Representation | Rendering |
|---|---|---|
| Sources not emitted yet | `HomeUiState.Loading` | Spinner + “Loading…” |
| Device date unreadable | `HomeUiState.Failed` | Error text + Retry |
| No location | `LocationSection.unavailable = NO_LOCATION_CONFIGURED` | Card explains it and links to Settings |
| Engine not configured | `timetableNote = CALCULATION_NOT_CONFIGURED` | Note in the timetable; no times, no countdown |
| Provider failed | `failureDetail` | Error banner with Retry, screen keeps other sections |
| Rules not verified | `prohibited.unavailable = RULES_NOT_VERIFIED` | Explanation instead of intervals |
| Bangla/Hijri missing | `CALENDAR_NOT_IMPLEMENTED` | Explanation instead of a fabricated date |

## 8. Design system and navigation

- `core/designsystem/theme`: emerald primary, warm ivory background, brass accent, slate for
  solar information; light and dark schemes; `SalahTheme` also flips system-bar icon colours for
  the explicit light/dark overrides.
- `core/designsystem/SalahGlyphs.kt`: icons drawn with Compose `Canvas`, so no icon library
  dependency exists.
- `navigation/SalahDestination`: the five bottom-bar destinations plus `qibla`. Unimplemented
  destinations render `PlannedFeatureScreen`, which is explicitly labelled “Not implemented yet”.
- Navigation state is saved/restored per destination (`saveState`/`restoreState`,
  `launchSingleTop`), so returning to Home keeps its state.

## 9. Testing strategy

| Layer | Covered by | Notes |
|---|---|---|
| Next-prayer resolution | `NextPrayerResolverTest` | Sunrise excluded, exact-time boundary, no countdown after Isha, other-date schedule, incomplete schedule |
| Countdown formatting | `CountdownFormatterTest` | `Locale.ROOT`, negative clamped |
| Location validation | `LocationInfoTest` | Ranges, IANA zone, blank label, NaN |
| Offline-first repository | `ProviderBackedPrayerScheduleRepositoryTest` | No location, engine not configured, cache reuse without re-calling the provider, stale-date rejection, exception → visible failure |
| Home mapping rules | `HomeUiStateFactoryTest` | No fabricated data, countdown only for a real schedule, sunrise not a prayer, failure surfaced |

Not yet covered (and therefore not claimed): Compose UI tests, instrumentation tests, and any
religious-correctness validation, which requires verified timetables and review rather than unit
tests.

## 10. Extension points

- **Add a prayer engine**: implement `PrayerTimesProvider`, bind it in `di/SalahModules.kt`.
  No UI change is required; the schedule will populate the existing sections.
- **Persist schedules**: add Room next to the in-memory cache inside
  `ProviderBackedPrayerScheduleRepository`. The repository interface does not change.
- **Bangla/Hijri dates**: implement a conversion in `data/calendar` that returns
  `CalendarDateState.Available`; the Home screen already renders that shape.
- **Prohibited periods**: return `ProhibitedPeriodsState.Available` once the rule set has been
  reviewed; the model and UI already exist.
- **Notifications**: a scheduler must observe the same repository/domain types and must not be
  reached from any screen, so it can be added without touching the UI.
- **Splitting modules**: move packages into `core:*`, `data:*`, `feature:*` Gradle modules;
  the rules in §4 are already the intended boundaries.

## 11. Known limitations of this phase

- The date header does not re-emit at midnight (it is emitted per collection); that belongs with
  the scheduler phase.
- The schedule cache is in-memory only; process death clears it.
- No network client, no Room, no background work, no permissions are declared in the manifest.
- Reason strings are English; full localisation and Bangla translations are future work.
