# Home Dashboard

Phase 7 turns the Home screen into a **personalised dashboard**: a list of independent cards whose
visibility and order the user owns, backed entirely by features Salah already implements. This
document describes what was built, how it is persisted, and how to add a card.

The dashboard adds no calculation. Every card renders a slice of the existing
[`HomeUiState.Ready`](../app/src/main/java/io/github/towkirahmedrony/salah/feature/home/HomeUiState.kt),
which
[`HomeViewModel`](../app/src/main/java/io/github/towkirahmedrony/salah/feature/home/HomeViewModel.kt)
and
[`HomeUiStateFactory`](../app/src/main/java/io/github/towkirahmedrony/salah/feature/home/HomeUiStateFactory.kt)
resolve from the same repositories, engines and converters the corresponding screens use.

---

## 1. Card identity

A card is a `DashboardCard` enum member
([`core/model/DashboardCard.kt`](../app/src/main/java/io/github/towkirahmedrony/salah/core/model/DashboardCard.kt)).
Each carries:

| Field | Purpose |
| --- | --- |
| `id` | The **stable identifier** persisted in DataStore. Never a translated label. |
| `defaultEnabled` | Whether a fresh install shows the card. |

Declaration order is the default order and the order new cards are appended in.

### Supported card ids and their responsibilities

| `id` | Default | Renders | Source |
| --- | --- | --- | --- |
| `next_prayer` | on | `CurrentPrayerHeroCard`: the open or next prayer, its countdown, the day's arc and solar span | `HomeUiState.currentPrayer`, `nextPrayer`, `heroArc`, `solarRows` |
| `today_timetable` | on | `TodayPrayerList`: the five prayers with the current/next marker | `HomeUiState.prayerRows` |
| `forbidden_times` | on | `ForbiddenTimesCard`: the prohibited intervals around the day's solar events | `HomeUiState.prohibited` |
| `calendar` | on | `CalendarCard`: today's Gregorian, Bangla and Hijri dates | `HomeUiState.gregorianSection`, `banglaSection`, `hijriSection` |
| `ramadan` | on | `RamadanHomeCard`: Ramadan day, Sehri and Iftar, or days until it begins | `HomeUiState.ramadan` |
| `night_periods` | on | `NightPeriodsCard`: Islamic midnight, the last third, Tahajjud | `HomeUiState.night` |
| `daily_tools` | on | `DailyToolsSection`: shortcuts to Qibla, Calendar, Ramadan and Tools | navigation only |
| `solar_geometry` | off | `SolarGeometryCard`: solar midnight, day and night length | `HomeUiState.solarDay` |
| `qibla` | off | `QiblaHomeCard`: the Qibla bearing and distance from the saved location | `HomeUiState.qibla` |

The header (saved place, reminder bell, customize control) is **not** a card: it is always present,
because every reader needs it regardless of how they arranged their cards. Hiding a card never
disables the feature behind it — the feature stays reachable from the bottom bar, the Tools screen or
Settings, and its reminders and background work are unchanged.

---

## 2. Persistence format

The layout is one preference group in the single `salah_preferences` DataStore, written by
[`DataStoreUserPreferencesRepository`](../app/src/main/java/io/github/towkirahmedrony/salah/data/preferences/DataStoreUserPreferencesRepository.kt):

| Key | Type | Contents |
| --- | --- | --- |
| `dashboard_config_version` | int | The layout format version (currently `1`). |
| `dashboard_card_order` | string | Comma-separated card ids, in the user's order. |
| `dashboard_hidden_cards` | string | Comma-separated hidden card ids (sorted, for stability). |

The value object is
[`DashboardConfig`](../app/src/main/java/io/github/towkirahmedrony/salah/core/domain/dashboard/DashboardConfig.kt).
It is independent of every other preference: prayer calculation settings, the location, the language
and the reminder settings live in different keys and are never read or written by a dashboard change.

### Default ordering

`DashboardConfig.DEFAULT` uses `DashboardCard.entries` order with every `defaultEnabled == true`
card shown. A fresh install therefore opens on the daily prayer routine; the occasional readings
(solar geometry, Qibla bearing) are available but off.

---

## 3. Migration and recovery

`DashboardConfig.normalized()` is the single recovery rule, applied on read *and* before every
write. It never fails:

- **Unknown ids** (a card removed in a later version) are dropped.
- **Duplicate ids** are de-duplicated, keeping the first position.
- **Missing ids** (a card introduced since the layout was written) are appended in declaration order,
  so a new card never moves the cards around it.
- **Hidden ids** are intersected with the supported set.
- **An unknown version** is stamped to `CURRENT_VERSION`.

Normalisation is idempotent — normalising an already-normal layout returns the same instance. The
view model compares the normalised result against the stored value before writing, so a tap that
changed nothing does not touch the store.

---

## 4. Behaviour

- **Show / hide** — `HomeViewModel.setCardEnabled`. Only the layout is written.
- **Reorder** — `HomeViewModel.moveCard(card, delta)`, clamped to the ends. The editor uses up/down
  steppers (the same idiom as the prayer-adjustment controls), so it needs no gesture library and
  works with a keyboard and a screen reader.
- **Reset** — `HomeViewModel.resetDashboard` writes `DashboardConfig.DEFAULT`. A confirmation dialog
  explains that only the layout is reset; the prayer settings, location, language and reminders are
  untouched.
- **Edit mode** — a transient `rememberSaveable` flag driven by the header's customize control. It
  shows the management list in place of the cards, so a card's own actions cannot be tapped by
  accident. There is no unsaved state: every change is written immediately.
- **Rendering** — `HomeScreen` renders `state.dashboard.enabled` (filtering the Ramadan card out when
  `state.ramadan.visible` is false) as `LazyColumn` items keyed by `DashboardCard.id`, so a data
  update cannot reshuffle the list.

---

## 5. Context-aware cards

- **Ramadan** (`HomeUiStateFactory.ramadanSection`) uses the same `RamadanWindow` the Ramadan screen
  reads (via `CalendarRepository.observeRamadanWindow`). The Sehri and Iftar times are read from
  today's schedule through `RamadanCalendar.sehriEndOf` / `iftarOf` — never recalculated. The card is
  only visible while today is inside the estimated window or within its 60-day lead; outside that it
  is **not rendered**, so Salah never claims it is Ramadan when it is not.
- **Qibla** (`HomeUiStateFactory.qiblaSection`) uses the injected `QiblaCalculator` — the same one the
  Qibla screen uses. A missing location or refused coordinates are reported as
  `QiblaSection.unavailable`, never as a number.
- The Ramadan window is a *progressive* input: it is seeded with `null` so it can never hold the rest
  of the dashboard back. All cards render from locally calculated or cached data; none needs network.

---

## 6. Adding a card

1. Add a member to `DashboardCard` with a new stable `id` and its `defaultEnabled`.
2. Add its `labelRes()` / `descriptionRes()` mappings in
   [`feature/common/DomainLabels.kt`](../app/src/main/java/io/github/towkirahmedrony/salah/feature/common/DomainLabels.kt)
   and the strings (English **and** Bengali).
3. If it needs new data, resolve it in `HomeUiStateFactory` from an **existing** repository — do not
   introduce a second engine.
4. Add the branch to `DashboardCardContent` in `HomeScreen.kt`. The `when` is exhaustive, so the
   compiler forces this step.
5. Add any new renderable strings to `HOME_STRINGS` in
   [`LocalizationResourcesTest`](../app/src/test/java/io/github/towkirahmedrony/salah/localization/LocalizationResourcesTest.kt).

Existing users keep their saved order; the new card is appended and defaults according to
`defaultEnabled`.

---

## 7. Tests

Unit tests only (no emulator, no network, no sensors):

- `core/domain/dashboard/DashboardConfigTest` — default order, enable/disable, reorder, reset,
  duplicate/unknown/new ids, version stamping, idempotence.
- `data/preferences/DataStoreDashboardConfigTest` — real DataStore persistence, recovery of a
  corrupt store, and isolation from the other preferences.
- `feature/home/HomeDashboardViewModelTest` — the enabled cards and their order through the view
  model, the actions, context-aware Ramadan and Qibla behaviour, and stability across data updates.

Run them all with:

```
./gradlew :app:testDebugUnitTest
```

or a single class:

```
./gradlew :app:testDebugUnitTest --tests '*DashboardConfigTest'
```
