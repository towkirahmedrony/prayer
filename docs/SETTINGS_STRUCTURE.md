# Settings: structure of the category experience

This document records how Salah's settings are organised after the category refactor: which screen owns
which preference, what each one writes to, and which behaviours are deliberately absent. It is the
counterpart to `docs/CALENDAR_SYSTEM.md`, `docs/LOCATION_SYSTEM.md` and `docs/PRAYER_NOTIFICATIONS.md`,
which describe the subsystems the settings point at.

## 1. The shape

`SettingsScreen` is an overview of categories, not a list of preferences. It renders
`SettingsCategory.entries` — one row each, with a mark, a title, a one-line description and a chevron —
and opens `category.destination`. The overview holds no state, reads no preference and writes nothing, so
it cannot disagree with what is stored; every control is one level down, on the screen that owns its
subject.

| Category | Screen | Route | Writes through |
| --- | --- | --- | --- |
| General | `SettingsGeneralScreen` | `settings_general` | `UserPreferencesRepository.updatePrayerSettings` (time format) |
| Appearance | `SettingsAppearanceScreen` | `settings_appearance` | `UserPreferencesRepository.setThemeMode` |
| Prayer times | `SettingsPrayerTimesScreen` | `settings_prayer_times` | `UserPreferencesRepository.updatePrayerSettings` |
| Notifications & Adhan | `PrayerNotificationSettingsScreen` | `notifications` | `PrayerNotificationPreferencesRepository` + `PrayerNotificationCoordinator` |
| Location | `LocationScreen` | `location` | `LocationRepository` + `PrayerNotificationCoordinator` |
| Calendar | `CalendarSettingsScreen` | `calendar_settings` | `UserPreferencesRepository.updateCalendarSettings` |
| About Salah | `SettingsAboutScreen` | `settings_about` | nothing (static) |

`SettingsCategory` holds the destination enum member, not a copied route string: a route spelled twice is
a route that can be spelled differently in the two places, and navigating to a route the graph does not
declare fails silently. `SettingsCategoryTest` asserts that every category opens a destination the graph
declares, that no two categories open the same one, that none of them is the Settings screen itself or a
bottom-bar tab, and that the four destinations declared for the overview all have a row.

## 2. What moved, and what did not change

Nothing was rewritten. Each preference kept its state source, its persistence and the view model or
repository that handles it; only the surface it is rendered on changed.

- **Time format** moved from the calculation card to General. It is stored in `PrayerSettings` and read
  by the Home screen, the Ramadan screen and the notification presenter; it is *not* part of
  `PrayerCalculationProfile`, so it changes no calculated time and the screen reports no recalculation.
- **Theme** is the Appearance screen. Same `ThemeMode` value, same DataStore key, still three modes
  (`Follow system`, `Light`, `Dark`), now as a radio group instead of three buttons.
- **Calculation method, Asr convention, high-latitude rule and the per-prayer corrections** are the
  Prayer times screen, unchanged in behaviour: every action still goes through
  `updatePrayerSettings`, whose read-modify-write happens inside one DataStore transaction, so two quick
  taps on a stepper both take effect.
- **Prayer notifications, location and the calendar preferences** already had screens of their own and
  keep them; they simply became the rows that open them. Their entry cards on the old Settings page are
  gone, so there is exactly one control per destination rather than a card plus a screen.
- **Manual coordinates** moved from the Settings page into `LocationScreen`, behind a disclosure. See
  section 3.
- **Everything else** — the About text, the version line, the prayer-engine status, the location
  overview and the notification controls — is the same content on a different screen.

## 3. The one behaviour that was repaired

The manual-coordinate form used to write the location through `SettingsViewModel` and stop there. The
other two ways in — a device fix and an area selection — call
`PrayerNotificationCoordinator.reconcile(ReconcileReason.LOCATION_CHANGED)` after writing, but the manual
path never did, so saving coordinates left the alarm set pointing at the previous location until the next
app start, reboot or explicit reconcile.

`LocationViewModel.saveManualLocation` now reconciles like the other two, and `LocationViewModelTest`
asserts that a saved entry reschedules (and that a refused one does not). This is the only behaviour the
refactor changes; a stored location, and the times calculated from it, are unchanged.

Also repaired while moving the form: the *clear* action no longer appears twice on the same screen. The
old Settings page offered a "Clear location" button as well as a link to the location screen, which
offers "Remove location" for the same store. Only the location screen's action remains; it is the same
`LocationRepository.clearLocation()` call, now with the reminder reconciliation it already had.

## 4. What is deliberately absent

- **No separate Language category.** The interface language *is* a preference (the selector lives on
  Settings > General, beside the time format), but it is a single three-way choice rather than a
  screenful of settings, so it gets a card rather than a category of its own — a category would put the
  one control a reader who cannot read the current language most needs behind an extra tap. The
  calendar's *date language* is a different preference and stays with the calendar. See
  `docs/LOCALIZATION.md`.
- **No account or profile section.** There are no accounts.
- **No date-format setting.** `TimeFormatPreference` is the only display format the app has.
- **No theme extras** (font size, density): the settings that exist are the settings that work.
- **No privacy-policy link, licences or publisher details.** Salah has no such URL or entity, and the
  About screen says what is true instead: the app calculates and stores everything on the device and
  needs no account.
- **No category without a preference behind it.** Seven categories, each mapping to a screen that can
  actually change something.

## 5. Navigation

Every configuration screen is reached by one `navigate` and left by `popBackStack`, so back returns to
whichever screen opened it — the Settings overview, the Calendar screen or Ramadan — and no screen keeps
a back stack of its own. The system back gesture and each subpage's visible back action do the same
thing.

Salah has no `TopAppBar`. Its screens state their title in the content, so the settings subpages use the
shared `SalahSubpageHeader` (a back button and the title) rather than introducing an app bar for one part
of the app. The button is a real `IconButton`, so its touch target is the platform's 48dp and TalkBack
announces it with the `action_back` label.

## 6. Tests

- `SettingsCategoryTest` — the overview's structure: every category opens its own declared destination,
  none loops back to Settings or takes a bottom-bar tab, the screens that already existed are still
  reachable, and the four destinations declared for the overview all have a row.
- `SettingsViewModelTest` — stored values are what the screens open on; every action writes through the
  repository; consecutive adjustments apply to the *stored* value rather than to one read at composition;
  the clamp is the model's, not the screen's; and only a change that invalidates the timetable claims a
  recalculation.
- `LocationViewModelTest` — the manual-entry path: a valid entry is stored as asserted (no country
  check) and reconciles the reminders; an invalid one is refused, marks the fields and changes nothing; a
  stored manual entry fills the form until the user types over it. Also asserted: no coordinate ever
  reaches the diagnostics log.
- `DataStoreUserPreferencesRepositoryTest`, `DataStoreLocationRepositoryTest`,
  `PrayerNotificationSettingsTest` — the persistence layers underneath, unchanged.
