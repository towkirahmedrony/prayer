# Settings: every option, end to end

This document is the functional counterpart to [`SETTINGS_STRUCTURE.md`](SETTINGS_STRUCTURE.md). That
one records *where* each control lives; this one records what each control is wired to — its meaning,
its default, the key it is stored under, the component that applies it, which features react, whether
anything has to be recalculated or rescheduled, and what happens when the write or the downstream work
fails.

It is written to be checked against the code rather than trusted. Every claim below names the type that
implements it, and §7 separates what CI verifies from what only a device can.

The rule this file is held to: **a setting is complete only when its value is persisted, restored after
process death, and read by the component that actually does the work.** A stored value that no one
reads, or a control that reports success without changing behaviour, is a defect — §4 lists the four
that were found and repaired, and §8 lists what remains deliberately absent.

## 1. Storage model

There is **one** preference store: `salah_preferences` (`SalahPreferencesDataStore`), read and written
through three repositories, each owning its own keys and nothing else.

| Repository | Interface | Owns |
| --- | --- | --- |
| `DataStoreUserPreferencesRepository` | `UserPreferencesRepository` | theme, prayer calculation settings, time format, calendar settings |
| `DataStorePrayerNotificationPreferencesRepository` | `PrayerNotificationPreferencesRepository` | master switch, per-prayer sound/vibration/advance/enabled, sunrise, Sehri, Iftar |
| `DataStoreLocationRepository` | `LocationRepository` | the one stored location, its provenance, accuracy, area hierarchy and dataset version |

Properties that hold for all three, and are asserted by the three `DataStore*RepositoryTest` files:

- **Read-modify-write inside one transaction.** `updatePrayerSettings`, `updateCalendarSettings` and
  `update` take a transform and run it inside a single `dataStore.edit`, so two quick taps both take
  effect and no caller writes back a copy it read earlier.
- **Never fail to read.** `IOException` becomes an empty store; an unknown enum name, an out-of-range
  number or a partial record is replaced by the documented default. A store written by another version
  cannot make a screen unusable, and cannot make Salah forget a decision it can represent.
- **Clamped on write *and* on read.** Manual adjustments (±60 min), the Hijri offset (±2 days) and the
  advance/lead times are normalised in both directions, so no stored value can put a control into a
  state it cannot render.
- **No reset of unrelated keys.** Each write touches only its own keys; clearing a location removes only
  the location keys, so the theme, the timetable settings and the reminders survive it.

## 2. General — time format

| | |
| --- | --- |
| Control | `SettingsGeneralScreen`, `SettingsOptionGroup` over `TimeFormatPreference` |
| Values | `SYSTEM` (device locale), `TWELVE_HOUR` (`h:mm a`), `TWENTY_FOUR_HOUR` (`HH:mm`) |
| Default | `PrayerSettings.DEFAULT.timeFormat` = `SYSTEM` |
| Key | `time_format` (string enum name) |
| Written by | `SettingsViewModel.setTimeFormat` → `updatePrayerSettings` |
| Applied by | `timeFormatterFor` (`core/domain/TimeFormatting`) |
| Read by | `HomeUiStateFactory` (hero + timetable + prohibitions), `RamadanUiStateFactory` (Sehri/Iftar/Fajr/Maghrib), `AndroidPrayerNotificationPresenter` (each delivered reminder) |
| Recalculation | **None.** It is not part of `PrayerCalculationProfile`, which is the schedule's cache key, so no cached timetable is invalidated and no schedule is recalculated. |
| Rescheduling | **None.** `PrayerNotificationDeliverer` reads the preference at delivery time, so an alarm already registered shows its time in the new format without being rebuilt. |
| Failure | The write is a DataStore transaction; `IOException` surfaces to the coroutine and the `uiState` flow keeps the previously stored value, so the screen cannot display an unsaved choice. |

The screen reports no recalculation for exactly this reason — announcing one would be the only untrue
statement on it.

## 3. Appearance — theme

| | |
| --- | --- |
| Control | `SettingsAppearanceScreen`, radio group over `ThemeMode` |
| Values | `SYSTEM`, `LIGHT`, `DARK` |
| Default | `ThemeMode.SYSTEM` (`AppViewModel`'s initial value and the repository's fallback) |
| Key | `theme_mode` (string enum name) |
| Written by | `SettingsViewModel.setThemeMode` → `setThemeMode` |
| Applied by | `SalahApp`, which collects `AppViewModel.themeMode` and passes it to `SalahTheme` |
| Read by | the composition root only — `SalahTheme` also flips the system-bar icon colours for the explicit modes |
| Recalculation / rescheduling | none |
| Failure | unknown stored value → `SYSTEM`; the write is transactional, and the `StateFlow` the shell collects is the repository's own flow, so an unsaved value cannot be rendered |

`SalahTheme` is applied once, at the shell, so a change re-themes every screen on the next emission
without a restart or an activity recreation.

## 4. Defects found and repaired

Four integrations stored a value correctly and then stopped: the value was persisted and restored, the
control reflected it, and the behaviour that depended on it never learned about the change. Each is
repaired at the point where the value is written, using the coordinator the rest of the app already
routes through (`PrayerNotificationCoordinator.reconcile`), and each has a regression test.

### 4.1 The theme was persisted but never applied — **repaired**

`AppViewModel.themeMode` was built from the store and read by nothing. `SalahApp` took
`themeMode: ThemeMode = ThemeMode.SYSTEM` and `MainActivity` called `SalahApp()` with no argument, so
`SalahTheme` always followed the device: the Appearance radio group showed the stored value across
restarts while the app's colours never changed.

**Repair.** `SalahApp` no longer takes a theme parameter. It collects `appViewModel.themeMode` with
`collectAsStateWithLifecycle` and passes it to `SalahTheme`, so the stored preference is the single
source of truth for the applied theme. (`ThemeMode` is still a parameter of `SalahTheme` itself, which
is what keeps the light-theme previews in `HomeSections.kt` possible.)

Guard: the value the shell applies is the repository's own flow — `SettingsViewModelTest` asserts the
written mode is what a subsequent read returns, and `DataStoreUserPreferencesRepositoryTest` asserts
the round trip through the real store. The composition itself is not unit-testable here; see §8.

### 4.2 A calculation change did not reschedule the reminders — **repaired**

Every control on the Prayer times screen moves a calculated time, and the reminders are planned from
those times. `SettingsViewModel` wrote the new profile and stopped, so the alarms kept announcing the
previous method, Asr convention, high-latitude rule or correction until the next app start, date
change or reboot.

**Repair.** `SettingsViewModel.updateSettings` now calls
`coordinator.reconcile(ReconcileReason.SETTINGS_CHANGED)` after the write. The coordinator reads the
persisted profile itself, so it plans against the value just stored rather than a copy read earlier,
and its plan is derived from the repository, so a repeated call replaces the alarm set instead of
duplicating it. `setTimeFormat` deliberately does **not** reconcile: it changes no calculated time.

Guards: `SettingsViewModelTest` asserts that a method change produces exactly one reconciliation with a
non-empty alarm set, and that a time-format change produces none.

### 4.3 Moving the Hijri offset did not reschedule the Ramadan reminders — **repaired**

Which Gregorian dates are in Ramadan is decided by the stored Hijri offset, and the Sehri and Iftar
reminders are planned from that window. `CalendarSettingsViewModel` stored the offset and stopped, so
the reminders moved only at the next reconciliation — a reminder for a day the calendar no longer
places in Ramadan could still fire.

**Repair.** `CalendarSettingsViewModel.update` observes whether the offset actually changed *inside*
the transaction and, only then, reconciles. The default view and the date language change no time and
reconcile nothing; a stepper tap that the clamp returns unchanged reschedules nothing.

Guards: `CalendarSettingsViewModelTest` — a real move reconciles once and stores the new offset; two
moves then a clamped no-op reconcile exactly twice; the default view and date language reconcile zero
times; `resetHijriAdjustment` writes `0` and reconciles.

### 4.4 A location saved from the Qibla screen did not reschedule the reminders — **repaired**

`QiblaViewModel` adopts a device fix, stores manual coordinates and clears the location through the
same `LocationRepository` the prayer engine uses — so the location is genuinely shared — but it did not
perform the reconciliation the location screen performs, even though `SETTINGS_STRUCTURE.md` §3 records
exactly that omission as the reason the manual-coordinate path was repaired when it moved to
`LocationSelectionScreen`. Alarms therefore kept pointing at the previous place after a location change made
from the Qibla screen.

**Repair.** `QiblaViewModel` now takes the coordinator and calls it after every successful write
(`ReconcileReason.LOCATION_CHANGED`) and after a clear — the same call, for the same reason, as
`LocationViewModel`. A refused fix and a rejected entry reconcile nothing, and a failed reconciliation
is logged rather than thrown, because the location is already stored and the next app start reconciles
again.

Guards: `QiblaViewModelTest` — an adopted fix reconciles once with a non-empty alarm set; a
fix outside Bangladesh leaves the alarm set empty and the counter at zero; a stored manual entry
reconciles once and a rejected one not at all.

## 5. Prayer times — calculation settings

All four groups write through `SettingsViewModel` → `UserPreferencesRepository.updatePrayerSettings`,
which rewrites `PrayerSettings` in one transaction. `PrayerSettings.toProfile()` produces the
`PrayerCalculationProfile` that is **the schedule's cache key**: the profile travels with every
schedule, and `ProviderBackedPrayerScheduleRepository` serves a cached entry only when its date,
location *and* profile match the request. So a change here is not a display change — it is a new
request, which is what makes the recalculation real rather than announced.

| Control | Values (default first where marked) | Key | Applied by |
| --- | --- | --- | --- |
| Calculation method | `KARACHI` (**default**), `MUSLIM_WORLD_LEAGUE`, `EGYPTIAN`, `UMM_AL_QURA`, `DUBAI`, `MOON_SIGHTING_COMMITTEE`, `NORTH_AMERICA`, `KUWAIT`, `QATAR`, `SINGAPORE`, `TURKEY` | `prayer_calculation_method` | `AdhanPrayerTimesProvider.toAdhanMethod()` |
| Asr convention | `HANAFI` (**default**), `STANDARD` | `prayer_asr_method` | `toMadhab()` (`Madhab.HANAFI` / `Madhab.SHAFI`) |
| High-latitude rule | `AUTOMATIC` (**default**), `MIDDLE_OF_THE_NIGHT`, `SEVENTH_OF_THE_NIGHT`, `TWILIGHT_ANGLE` | `prayer_high_latitude_rule` | `toAdhanRule()`; `AUTOMATIC` maps to the engine's latitude-recommended rule |
| Per-prayer correction | five prayers, each −60..+60 min, `0` (**default**) | `prayer_adjustment_<PRAYER>` (removed when `0`) | `PrayerAdjustments` in the engine call; carried on each `PrayerTimeEntry` |

Every method and every rule offered is executed by the engine — there is no option the engine silently
substitutes, and the mapping is an exhaustive `when`, so a value that cannot be executed is a compile
error rather than a quiet fallback.

- **Recalculation:** a new profile is a new request; the repository recalculates only for the affected
  dates, and `HomeViewModel` de-duplicates the request, so the tick cannot cause repeated work.
- **Refresh:** the Home timetable, hero, countdown and prohibited windows, the `prayer` destination and
  the Ramadan table all read from that same repository, so they move together.
- **Rescheduling:** §4.2.
- **Failure:** an invalid adjustment clamps in the model, not the screen; a write failure leaves the
  previous profile in the flow.

## 6. Location, Notifications and Calendar

These three categories are described in full by their own documents; this section records only the part
that matters for "does the setting work".

### 6.1 Location

- **Ways in:** current location (`LocationViewModel.useCurrentLocation`), the
  Division → District → Upazila/thana picker (`selectDivision`/`selectDistrict`/`selectUpazila` →
  `saveSelectedArea`), manual coordinates (`saveManualLocation`), and removal (`clearLocation`).
- **Applied by:** `DataStoreLocationRepository`; read by `PrayerScheduleRepository` requests, `QiblaViewModel`,
  `RamadanViewModel`, `HomeViewModel` and the notification coordinator — one store, so a location cannot
  mean two things.
- **Validation:** a device fix passes `BangladeshLocationPolicy` and is attributed by `AreaResolver`
  before it replaces anything; a manual entry records a position the user *asserts* and applies no
  country check (it is the only path for a place the bundled dataset does not contain), which the form
  states. Nothing falls back to a default city: `null` means "not configured" and every consumer
  handles it.
- **Permission:** requested only from an explicit tap (`rememberDeviceLocationAction`). Opening the
  screen reads the permission state and nothing else; an existing manual location stays fully usable
  without the permission.
- **Refresh and rescheduling:** every successful write and a clear call
  `PrayerNotificationCoordinator.reconcile(LOCATION_CHANGED)` — from the location screen *and*, since
  §4.4, from the Qibla screen.
- **Failure:** the repository returns `false` for invalid input and stores nothing; the screen marks the
  fields and shows the message. A fix outside Bangladesh is refused with its distance and the previous
  location preserved.

### 6.2 Notifications and Adhan

| Control | Effect |
| --- | --- |
| Master switch | planner returns nothing and the coordinator cancels every managed alarm; `PrayerNotificationDeliverer` re-checks at delivery time |
| Per-prayer enabled | a disabled prayer produces no plan entry and is suppressed at delivery |
| Per-prayer sound | `SYSTEM_DEFAULT` / `SALAH_CHIME` (bundled `res/raw/salah_chime.wav`) / `SILENT` → the channel the reminder is posted to (`PrayerNotificationChannelId.of(sound, vibration)`) |
| Per-prayer vibration | the same channel's `enableVibration`, i.e. the platform mechanism the API level requires |
| Per-prayer advance | `0..30` min → an additional reminder planned before the prayer; `0` is off |
| Sunrise reminder | its own switch, planned at the true sunrise as a solar event, never as a prayer and never with an advance reminder |
| Sehri reminder + lead | planned as a lead-in to the calculated Fajr, only for dates the calendar places in Ramadan |
| Iftar reminder + lead | planned at the calculated Maghrib plus an optional lead-in, same restriction |
| Recalculate now | `PrayerNotificationSettingsViewModel.recalculate` → `reconcile(EXPLICIT)` |
| Sound preview | `NotificationSoundPreviewer`; a silent choice reports success because silence is what was asked for, and an unplayable sound reports failure |
| Permission rows and actions | `NotificationPermissionStatusProvider` + the platform's notification and exact-alarm screens; the state is re-read on return |

- **Persistence:** sparse per-prayer entries — a preference equal to the default is removed rather than
  written, so the store holds decisions rather than copies of today's defaults.
- **Rescheduling:** every write reconciles (`SETTINGS_CHANGED`), and the cancellation set is derived
  from the full `(date, target, kind)` cross-product, so a slot scheduled under older settings is still
  found and cancelled. Scheduling is idempotent, so a repeated reconciliation cannot duplicate an alarm.
- **Failure:** a denied `POST_NOTIFICATIONS` produces a suppression with a reason and a banner, not a
  claim that reminders are on; an unavailable schedule produces `ScheduleUnavailable`; a platform
  refusal to register one alarm is counted and logged without aborting the rest.
- **Azan:** no licensed recording ships, so no control is offered. `AzanPlaybackManager` is implemented
  and unit-tested and becomes reachable when `BundledAzanAudioSource.resolve()` returns a source. The
  screen states this rather than offering a switch that cannot work.

### 6.3 Calendar

| Control | Values | Key | Applied by |
| --- | --- | --- | --- |
| Default view | `GREGORIAN` (default), `BANGLA`, `HIJRI` | `calendar_preferred_system` | `CalendarMonthResolver` decides which Gregorian days the *selected system's* month covers; `CalendarUiStateFactory` renders that month, its heading, its cell numbers and its numerals |
| Hijri offset | −2..+2 days, `0` (default) | `calendar_hijri_adjustment_days` | `HijriCalendar.dateOf`/`monthStart`, the Ramadan window, the Home header, the Ramadan table — and the notification schedule (§4.3) |
| Date language | `SYSTEM` (default), `BANGLA`, `ENGLISH` | `calendar_date_language` | `CalendarFormatting.localeFor`/`gridLocale` on every screen that prints a date |

The three modes are real conversions, not relabelled Gregorian months: Bangla uses the 2019 Bangladesh
civil rules, Hijri uses the platform's Umm al-Qura table, and each mode's month navigation moves one
month *of that system*. The Gregorian date stays the identity that prayer times and Ramadan are keyed
by, so changing the mode changes the presentation and never the timetable. A date outside a converter's
range is reported as out of range rather than approximated. Hijri dates are always labelled as
estimates; `HijriDateSource` has no confirmed state because no authority source is integrated.

## 7. Tests

Added or repaired in this change:

| Test | What it asserts |
| --- | --- |
| `SettingsViewModelTest` — *a calculation change rebuilds the reminders from the new profile* | after a method change: exactly one reconciliation, and a non-empty alarm set |
| `SettingsViewModelTest` — *a time-format change reschedules nothing* | the format change reconciles zero times |
| `CalendarSettingsViewModelTest` (new) | moving the offset reconciles once and stores it; two moves then a clamped no-op reconcile exactly twice; the default view and date language reconcile zero times; `resetHijriAdjustment` writes `0` and reconciles |
| `QiblaViewModelTest` — *adopting a device fix reschedules the reminders*, *a refused fix leaves the alarms alone*, *storing manual coordinates reschedules the reminders* | a stored location reconciles once with real alarms behind it; a refused fix and a rejected entry reconcile nothing |
| `SettingsViewModelTest`, `QiblaViewModelTest` (repaired) | both now build the view model with the real coordinator over fake platform edges, so the scheduling assertions are about the alarms that were registered rather than about a method being called |

The fakes are the production ports (`FakePrayerNotificationScheduler` behaves like `AlarmManager`:
registering a slot twice replaces it), the clock is fixed, the dataset is the synthetic miniature, and
no test needs a device, a permission, a network or the wall clock.

Not covered by tests, and therefore not claimed:

- **The theme actually re-theming the tree.** The project has no Compose UI test dependency
  (`docs/ARCHITECTURE.md` §9 records this), so the shell's collection of the preference is verified by
  inspection and by the store-level tests either side of it, not by a rendered assertion.
- The device-only behaviours already listed in `PRAYER_NOTIFICATIONS_TESTING.md` §4 and
  `QIBLA_COMPASS_TESTING.md` §4: real alarm delivery, `MediaPlayer` against real audio, real sensors
  and a real GNSS fix.

CI (`.github/workflows/android-apk.yml`) is the authoritative check: it runs
`testDebugUnitTest`, `lintDebug` and `assembleDebug` on every push to `main` and uploads the debug APK.

## 8. Known limitations

- **Azan playback is unavailable.** No recording with a redistributable licence is bundled, so no
  control is offered and the screen says so. The playback machinery exists and is tested. The setting
  becomes live by implementing `AzanAudioSource`.
- **The interface language is a preference, and only the Calendar is fully translated.** Settings >
  General offers English, Bengali and "Follow the device", and the choice is applied to every screen
  without recreating the activity. The *Calendar* screen is translated in full; the rest of the app keeps
  the deliberate partial translation recorded in `app/lint.xml`, so some explanatory paragraphs are still
  English. The calendar's *date language* remains a separate calendar preference — see
  `docs/LOCALIZATION.md`.
- **There is no date-format setting.** `TimeFormatPreference` is the only display format the app has.
- **The Hijri date is an estimate.** The offset is the only correction available; Salah does not
  integrate an authority source and never labels a date as confirmed.
- **Prohibited prayer windows ship as `CONFIGURED`, not `REVIEWED`,** and the Home screen discloses
  that; there is no setting for them.
- **Long-form Azan playback from a foreground service** is deliberately not implemented, so a reminder
  cannot play audio beyond the notification itself.
- **No Compose or instrumentation tests**, as noted in §7.

## 9. How to check a setting yourself

1. Change it, then kill the app from the recents list and reopen it — the control must show the new
   value (all three repositories persist before returning).
2. Watch the effect where the table above says it lands: the Home timetable for a calculation change,
   the whole UI for the theme, the Calendar grid for a calendar mode, the notification settings
   screen's schedule card after *Recalculate now* for a reminder change.
3. For a change that reschedules, open the notifications screen and tap *Recalculate now*: it reports
   the count of alarms the scheduler holds, which is the same set a background reconciliation would
   produce.
