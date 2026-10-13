# Salah — Calendar, Bangla Calendar, Hijri Calendar and Ramadan

This documents the calendar system **as implemented in this repository**: the Gregorian calendar, the
Bangladesh (Bangla) civil calendar, the Hijri calendar, the Ramadan window derived from them, and the
Sehri/Iftar schedule and reminders built on the existing prayer calculation engine.

It is not a design proposal. Every mechanism described here exists in the source tree, and every
behavioural claim is backed by a test named in §13. Where something is not implemented, or is inherently
uncertain, it says so — and the app says so too rather than showing an estimated value as a confirmed
one.

---

## 1. Scope

Implemented:

- **Gregorian calendar** — the device date, a navigable month grid, today marked distinctly from the
  selection, weekday and month names in the configured date language, and a selected-day details view.
- **Bangladesh Bangla calendar** — conversion under the 2019 rules, with the supported range stated and
  refused outside it.
- **Hijri calendar** — conversion from the platform's Umm al-Qura table, a user-configurable day offset,
  and Islamic observances derived from the lunar date.
- **Ramadan calendar** — the estimated window, a daily table with Ramadan day number, Gregorian, Bangla
  and Hijri dates, Sehri end, Fajr, Iftar and Maghrib, and the provenance of the times.
- **Sehri and Iftar reminders** — scheduled by the existing Phase 3 notification subsystem, only for the
  dates the calendar places inside Ramadan, with their own switches and lead times.
- **Offline operation** — every calendar value is produced on the device from the platform clock, the
  platform's Hijri table and local arithmetic. There is no network call anywhere in this feature, and
  Salah declares no `INTERNET` permission.

Deliberately **not** implemented, and reported as such in the UI rather than hidden:

- **Any authority-confirmed timetable.** Neither a moon-sighting announcement nor the Islamic
  Foundation's published Sehri/Iftar timetable is integrated. Salah therefore never labels a date or a
  time as confirmed, and never claims to reproduce an authority's timetable (§5.3, §7).
- **A remote timetable source.** If one is added later it must follow the rules in §11 and §12 rather
  than replacing local calculation.
- **Pre-2019 Bangla dates.** The current rules are not the old ones, so dates before 14 April 2019 are
  refused instead of converted (§4.4).
- **Hijri years outside the platform's table** (roughly AH 1300–1600) are refused rather than
  extrapolated (§5.4).
- **Laylat al-Qadr and similar observances whose date is a matter of interpretation.** Salah does not
  pin a contested date to a day (§5.5).

---

## 2. Architecture

```text
  Calendar screen                    Ramadan screen
        │                                  │
   CalendarViewModel                  RamadanViewModel
        │                                  │
        │                    CalendarRepository ──────────────┐
        │                                  │                  │
   CalendarUiStateFactory          RamadanUiStateFactory      │
        │                                  │                  │
        └──────────────┬───────────────────┘                  │
                       ▼                                      ▼
        core/domain/calendar (pure, Android-free)      PrayerScheduleRepository
        ├── GregorianCalendarGrid                      (the existing Phase 2 engine,
        ├── CalendarMonthResolver                       unchanged, offline)
        ├── BanglaCalendar
        ├── HijriCalendar
        ├── RamadanCalendar
        └── CalendarFormatting                    PrayerNotificationCoordinator
                                                        (the existing Phase 3
                                                         scheduler, unchanged)
```

Dependency direction, and why each boundary exists:

| Component | Owns | Does not do |
|---|---|---|
| `GregorianCalendarGrid` | the week layout of any run of consecutive days | read a clock, a locale or a device; format text |
| `CalendarMonthResolver` | which Gregorian days the selected system's month covers | format text or read a clock |
| `BanglaCalendar` | Bangladesh conversion in both directions | any zone, locale, clock or I/O |
| `HijriCalendar` | Umm al-Qura conversion, the day offset, Hijri month starts/lengths, the Ramadan window, observances | any UI wording |
| `RamadanCalendar` | the Sehri/Iftar *rule* (Sehri = Fajr, Iftar = Maghrib) | calculate prayer times |
| `CalendarFormatting` | choosing a locale and composing date strings | contain domain rules |
| `DeviceClockCalendarRepository` | which Gregorian day is meant, and the midnight/zone refresh | format for a layout |
| `CalendarUiStateFactory`, `RamadanUiStateFactory` | mapping domain values to immutable UI state | convert dates |
| ViewModels | the displayed month and the selection; reminder writes | contain any conversion |

The converters are **pure functions over `java.time` values**: no Android type, no clock, no locale and
no coroutine appears in `core/domain/calendar`. That is what makes every rule in this document
unit-testable on the JVM, and it is why the Koin graph binds them as stateless singletons — two
instances could only ever produce the same answer.

---

## 3. Gregorian calendar

- **Which day it is** comes from the device clock, converted to a date **in the device's current zone**
  (`ZoneId.systemDefault()`), read fresh on every pass. A device date or time-zone change therefore
  takes effect at the next local midnight, and a snapshot is never rendered in a stale zone.
- **The device zone, not the prayer location's zone**, is the right choice for a *calendar*: a civil
  calendar is an artifact of where the user is, while prayer times are an astronomical property of where
  they pray. The prayer location's zone governs the *times* (§8).
- **Month navigation** is `java.time`'s arithmetic in the Gregorian mode, so month, year and leap-year
  boundaries cannot be off by one. February 2024 has 29 days, February 1900 and 2100 have 28, and
  February 2000 has 29 (`GregorianCalendarGridTest`).
- **The grid is the selected calendar system's month.** The Gregorian mode shows a Gregorian month; the
  Bangla and Hijri modes show a Bangladesh Bangla month and a Hijri month, which do not line up with
  Gregorian months. `CalendarMonthResolver` answers "which Gregorian days does the selected system's
  month containing this day cover", and every cell keeps its Gregorian date as its identity, so prayer
  times and Ramadan still key off the same day (`CalendarMonthResolverTest`).
- **Today is carried inside the month value** (`CalendarMonth.today`) rather than re-read by the screen,
  so the grid, the header and the details card are one consistent snapshot even after the user navigates
  to another month.
- **Today and the selection are drawn as different treatments.** Today alone is a muted fill, the
  selection is an outlined tint, and the two together are the solid accent, so navigating away from the
  current month cannot make the selection look like today (`CalendarUiStateFactoryTest`).
- **The first day of the week is the locale's own.** `WeekFields.of(locale).firstDayOfWeek` is passed in
  and carried on the month (`CalendarMonth.firstDayOfWeek`), and the column headers are built from that
  same value, so a header can never sit above the wrong column when the week does not start on Sunday.

---

## 4. The Bangladesh (Bangla) calendar

### 4.1 The rules implemented

The **Bangladesh** civil calendar as revised in 2019, not the Bengali calendar used in West Bengal:

| Months | Length |
|---|---|
| Boishakh, Jyaistha, Ashadh, Shraban, Bhadra, Ashwin (1–6) | 31 days each |
| Kartik, Agrahayan, Poush, Magh (7–10) | 30 days each |
| Falgun (11) | 29 days, **30 when the Gregorian year it falls in is a leap year** |
| Chaitra (12) | 30 days |

That is `6 × 31 + 4 × 30 + 29 + 30 = 365`, or 366 when Falgun has 30. **Poila Boishakh is fixed on
14 April** — in Bangladesh the civil New Year does not move between 14 and 15 April.

### 4.2 The authoritative reference

The month lengths and the leap rule are stated by **The Daily Star** ("Since 2019, the first six months
… of the year have had 31 days. The rest of the six months of the year now had 30 days, other than Falgun,
which had 29 days. On years divisible by four and not by 100, however, Falgun would have 30 days", and
"Pahela Falgun … shifted … to February 14"), and by **Dhaka Tribune**, "Ashshin in 31 days in revised
Bangla calendar" ("From now on, the first six months of Bangla year will have 31 days"). The Daily Star
article also records the change this revision made: before it, 1 Falgun was 13 February, and the 1987
rules had five 31-day months rather than six.

The individual day-number fixtures the tests use follow from those published lengths and the published
starting dates, and are the dates an independently published Bangladesh calendar gives: **1 Ashwin is
16 September** (the source notes that 16 October 2019 was the first 31st of Ashwin, which requires
1 Ashwin 1426 to have been 16 September 2019), **1 Poush is 16 December**, **1 Falgun is 14 February**,
**1 Chaitra is 15 March** and **1 Boishakh is 14 April**.

### 4.3 Why not the West Bengal calendar

West Bengal uses a different arrangement (five 31-day months with the rest 30, and a New Year that has
moved between 14 and 15 April). Applying one to the other produces a date that is wrong by a day for part
of the year, which is why the two are not interchangeable and why this implementation is documented as
Bangladesh-specific. Salah does **not** currently offer the West Bengal calendar at all: the UI says which
calendar is implemented rather than presenting a generic "Bengali" conversion.

### 4.4 Supported range, and what happens outside it

Conversion is offered for **14 April 2019 through 31 December 2399**. Two boundaries are deliberate:

1. **Before the 2019 revision the rules were different**, so applying the current ones to an earlier date
   would produce a date that was never the official one. `BanglaCalendar.convert` returns `null` and the
   screens show `OUT_OF_SUPPORTED_RANGE` with an explanation.
2. **The leap rule for a year divisible by 400 is not settled by the sources.** The published wording is
   "divisible by four and not by 100"; the Gregorian calendar also treats 2400 as a leap year, so the two
   rules first disagree in 2400. Nothing is guessed: the range stops before the first year where they
   could diverge. Within the range the two rules agree.

A refusal is a real answer here: `Conversion is refused outside the verified range instead of
approximated` is asserted in `BanglaCalendarTest`.

---

## 5. The Hijri calendar

### 5.1 Method: the platform's Umm al-Qura table

Conversion uses `java.time.chrono.HijrahChronology` — the **Umm al-Qura** calendar (`islamic-umalqura`)
that ships inside `java.base`. Reasons:

- **No new dependency.** No licence question, no maintenance risk, and no JVM-target risk — the JVM-target
  problem that pins the prayer engine (see `docs/PRAYER_CALCULATION.md` §1) would be a real hazard for an
  arbitrary calendar library.
- **Deterministic.** Umm al-Qura is a *published tabular* calendar, so the same input produces the same
  output on every device, unlike a calculation that varies with an implementation's constants.

Reference conversions the tests assert, printed from that same table: AH 1445-09-01 = **11 March 2024**,
AH 1445-10-01 = 10 April 2024, AH 1445-12-10 = 16 June 2024, AH 1446-09-01 = **1 March 2025**,
AH 1446-10-01 = 30 March 2025, AH 1446-12-10 = 6 June 2025, AH 1447-09-01 = 18 February 2026.

### 5.2 Estimated, adjusted, or confirmed

Every Hijri date Salah produces is an **estimate** and is labelled as one:

| State | Meaning | Where it comes from |
|---|---|---|
| `ESTIMATED_UMM_AL_QURA` | the raw Umm al-Qura conversion | the platform's table |
| `ESTIMATED_USER_ADJUSTED` | the same conversion, shifted by the user's own day offset | the user, applied by Salah |

There is **no "confirmed" state at all** — `HijriDateSource` has exactly two constants, and
`HijriCalendarTest` asserts that list. Salah has no moon-sighting or authority data source integrated, so
it cannot honestly produce a confirmed date; adding one would mean adding a third constant that only that
source may set. The Home screen's Hijri section is likewise never marked as a verified source
(`isVerifiedSource = false`), unlike the Gregorian and Bangla dates, which are computed from a device
clock and a published civil definition respectively.

### 5.3 The adjustment

The Hijri offset (`CalendarSettings.hijriAdjustmentDays`, clamped to `−2…+2`) exists because local moon
sighting can move an observance by a day. Its semantics are exact and are tested:

- it is applied **before** the value is read, so a shifted date is a genuinely different Hijri day and the
  month and year transition with it;
- a **positive** offset moves every Hijri date **later**, which moves the Gregorian *start* of Ramadan
  correspondingly **earlier** — the same relationship in both directions, so the range and the dates can
  never disagree;
- it moves the Ramadan day numbers, the estimated window, the Hijri labels in the Ramadan table and the
  Sehri/Iftar reminders, because all of them are derived from that one value;
- the result is still labelled `ESTIMATED_USER_ADJUSTED`, because the offset changes *which* estimate is
  shown, not how reliable it is.

The offset is written and read through one DataStore transaction and clamped on both paths, so a stored
value the screen cannot represent is impossible (`DataStoreUserPreferencesRepositoryTest`).

### 5.4 Supported range

The platform's table covers roughly **AH 1300 (12 November 1882) to AH 1600 (25 November 2174)**. Rather
than hard-coding a guessed boundary, conversion is attempted and any date the table does not cover yields
`null`, which the UI reports as `OUT_OF_SUPPORTED_RANGE`. `HijriCalendarTest` asserts both edges, including
that 1882-11-11 and 2175-01-01 are refused.

### 5.5 Observances

Observances are **derived from the lunar date**, never stored as a fixed Gregorian date: Islamic New Year
(1 Muharram), Ashura (10 Muharram), Mawlid (12 Rabi' al-Awwal), Ramadan begins (1 Ramadan), Day of Arafah
(9 Dhu al-Hijjah), Eid al-Adha (10 Dhu al-Hijjah) and Eid al-Fitr (1 Shawwal). Each is shown with the note
that local moon sighting may move it by a day, and none is presented as a confirmed public holiday.

Deliberately absent: **Laylat al-Qadr**. Its date is a matter of religious interpretation rather than a
calendar datum, so Salah does not pin it to a day.

---

## 6. The Ramadan window

The window is **derived, not stored**:

1. `HijriCalendar.ramadanWindow(hijriYear, offset)` asks the platform's table for 1 Ramadan of that Hijri
   year and reads the month's own length, so a 29-day Ramadan stays 29 days and a 30-day one stays 30.
2. The start is that first day shifted by the user's offset (§5.3), and the end is `start + length − 1`.
3. `ramadanWindowFor(today)` returns the window containing today, or the **next** one after it, or `null`
   when neither is inside the platform's table.
4. `RamadanWindow.phase` classifies today: `IN_RAMADAN` (both ends inclusive), `UPCOMING` within 60 days
   before the start, and `FAR_AWAY` otherwise — including the day *after* the end, because the next Ramadan
   is a year away and a countdown to nothing would be misleading.

Consequences, each asserted in `RamadanCalendarTest` and `HijriCalendarTest`:

- **Nothing is hard-coded.** There is no Ramadan date in the source tree; changing the offset or the Hijri
  year changes the range.
- **An estimated range is always said to be estimated.** The screens carry an "Estimated" badge and the
  sentence that the range comes from the Umm al-Qura calculation rather than from an announcement by
  Bangladesh's religious authorities, and that moon sighting may move the start and end by a day.
- **No range means no dates.** When the table does not cover a Ramadan near today, the screens show an
  honest "no reliable range" state rather than estimated dates, and the daily table has no rows.
- **The window and the day numbers come from one value.** `CalendarMonth` and the Ramadan inputs carry the
  window, so a day's Ramadan number can never disagree with the range displayed beside it.
- **The Ramadan *start date* is not treated as official.** Salah reports an estimated window and the day
  numbers within it; it does not claim that the first day of fasting in Bangladesh is decided.

---

## 7. Sehri and Iftar

The rule is one sentence, and it is implemented in exactly one place
(`RamadanCalendar.sehriEndOf` / `iftarOf`):

> **Sehri ends at the calculated Fajr. Iftar is the calculated Maghrib.**

- **No second prayer engine.** Both values are read out of the *same* `PrayerSchedule` object the Home
  timetable and the notification planner use, so the Ramadan table, the Home screen and the reminder
  cannot disagree. `RamadanUiStateFactoryTest` asserts the Sehri column equals the schedule's Fajr and the
  Iftar column equals its Maghrib.
- **No invented buffer.** Salah applies **no** "safe minutes" reduction to Sehri. An arbitrary offset would
  be a religious claim the app cannot make. The supported way to align with a published timetable is the
  app's existing per-prayer manual adjustment, and because Sehri *is* Fajr that adjustment moves the Sehri
  reference with it (`RamadanCalendarTest`).
- **No invented source.** Whether a displayed time is a local calculation, a remote timetable or a
  user-entered one is decided by the schedule's own provenance (`DataSourceInfo`) and carried to the UI,
  which names it under the table. Nothing claims to match the Islamic Foundation's timetable.
- **A missing time is missing.** At a latitude where the engine cannot produce Fajr or Maghrib, the row
  shows no Sehri or Iftar time and says why, rather than showing a plausible-looking figure.
- **The screens say what to do about it**: follow the relevant local authority or a timetable you trust
  for observance.

---

## 8. Location and time zone

- **The calendar day** is the device's local day (§3).
- **The times** are rendered in the **prayer location's zone** (`PrayerSchedule.zoneId`), with the user's
  own clock format (`TimeFormatPreference`), so the Ramadan table matches the timetable. The formatter is
  the same `timeFormatterFor` the Home screen and the notifications use.
- **Daylight-saving transitions** are handled by construction: the repository computes the wait to the
  next local midnight with `atStartOfDay` rather than "plus 24 hours", so a 23- or 25-hour local day is
  correct (`DeviceClockCalendarRepositoryTest` asserts a 22 h 30 m wait across the British Summer Time
  change).
- **Midnight and date boundaries**: the Hijri date crosses its own month boundary at *local* midnight,
  asserted directly in `DeviceClockCalendarRepositoryTest`.
- **A location or calculation-method change recalculates the schedule**, because the request key includes
  the date, the location and the persisted calculation profile — the existing cache contract from Phase 2
  is reused unchanged rather than duplicated.
- **No coordinates are logged.** Nothing in this feature writes a coordinate to the diagnostics log.

---

## 9. Persistence, and what is cached

Persisted (in the existing shared DataStore, keys prefixed `calendar_`):

| Preference | Key | Default |
|---|---|---|
| Default calendar view | `calendar_preferred_system` | `GREGORIAN` |
| Hijri day offset | `calendar_hijri_adjustment_days` | `0` |
| Date language (script and numerals) | `calendar_date_language` | `SYSTEM` |

Ramadan reminder preferences live with the other notification preferences, so there is one store that owns
whether a reminder exists (`notification_sehri_reminder_enabled`, `notification_sehri_reminder_lead_minutes`,
`notification_iftar_reminder_enabled`, `notification_iftar_reminder_lead_minutes`).

- **No new database.** "Room for structured cached data only where appropriate" resolves to *nowhere* here:
  the month is a pure function of a date and a few settings, so persisting it would only create a copy that
  could go stale. The prayer schedule cache already exists and is reused unchanged.
- **No duplicate copies.** Nothing in this feature stores a prayer time; the Ramadan table reads the same
  repository the Home screen does.
- **Unknown or out-of-range stored values fall back** to the documented default rather than making a screen
  unusable, and the offset is clamped on write *and* on read.

**Invalidation.** Everything derived is invalidated by the preference that feeds it, with no cache to
clear:

| Change | What re-emits |
|---|---|
| Device date rolls over (local midnight) | today's snapshot, the displayed month, the Ramadan window |
| Device time zone or DST | same, at the next local midnight |
| Hijri offset | every Hijri date, the Ramadan window and day numbers, the Hijri labels, the reminders |
| Date language | every formatted date and month label |
| Default view | the displayed month itself — its heading, weekday names, day numbers and length all switch to the selected calendar system |
| Location or calculation method | the schedules, and therefore every Sehri/Iftar time |

---

## 10. Reminder integration

Sehri and Iftar reminders are **planned by the existing `PrayerNotificationPlanner`,
scheduled by the existing `PrayerNotificationScheduler` and reconciled by the existing
`PrayerNotificationCoordinator`.** There is no second alarm path.

- **Two new targets** — `ReminderTarget.SEHRI` and `ReminderTarget.IFTAR` — appended to the enum so that
  alarms scheduled by an earlier build keep their identity. The request code is
  `floorMod(epochDay, 1_000_000) * 16 + target.ordinal * 2 + kind.ordinal`; with 8 targets and 2 kinds the
  16-slot block is exactly full and every `(target, kind)` pair still has its own code, so a Ramadan alarm
  can never be confused with the prayer alarm it derives from (`RamadanReminderPlanTest`).
- **Sehri is a lead-in only.** An at-time Sehri reminder would land on the Fajr prayer reminder, so Sehri
  is only ever planned as `ADVANCE`. Iftar is planned **at** Maghrib, plus a lead-in when the user chooses
  one.
- **Only inside Ramadan.** The coordinator asks `RamadanCalendar` which of the planned dates are in
  Ramadan, using the same stored offset as the screens, and passes the resulting set to the planner. An
  empty set produces no Ramadan reminder — never an invented one. Because the input is a *set of dates*,
  a Hijri-offset change or the end of Ramadan simply produces a different set, and the coordinator's
  derived cancellation removes the days that are no longer in it. No stale alarm can survive a Ramadan
  end. That is asserted against the alarm registry itself, not against a plan, in
  `RamadanReminderCoordinatorTest`.
- **Sound, vibration and channels are inherited** from the prayer each derives from (Fajr for Sehri,
  Maghrib for Iftar), so the Ramadan reminders reuse the existing channels and the user's existing choices
  instead of adding new ones.
- **The master switch and the switches on the notifications screen both apply**, and the switches are
  persisted settings with real effect, not decorative controls.
- **Lead times** are the offered choices 5/10/15/20/30 minutes for Sehri and 0/5/10/15/20/30 for Iftar,
  where `0` means "at Iftar only". A value outside the offered set is clamped to the nearest choice.
- **Boot, clock, time-zone and date changes** are handled by the existing receivers, unchanged.

---

## 11. Offline behaviour

- **Nothing in this feature performs a network call**, and Salah declares no `INTERNET` permission, so it
  could not.
- The Gregorian date comes from the device clock, the Bangla date from local arithmetic over the published
  rules, the Hijri date from the table in the platform's own `java.time`, and the times from the local
  astronomical engine that Phase 2 already provides.
- A previously viewed month or a calculated schedule is available again offline, because it is recomputed
  from the same inputs rather than fetched.
- **Stale data is never presented as fresh**: a schedule is only ever used for the date, location and
  calculation profile it was produced with, and its provenance and observation time travel with it.
- If an authority-confirmed timetable is ever integrated, it must display an honest unavailable state when
  it cannot be retrieved, and the calculated calendar must keep working without it.

---

## 12. Limitations and known accuracy constraints

1. **The Hijri date is an estimate.** Umm al-Qura is not the calendar Bangladesh's religious authorities
   announce Ramadan and the Eids with. Salah labels it as an estimate everywhere and never as confirmed.
2. **The Ramadan window is an estimate**, may differ by a day from local sighting, and Salah does not know
   the official Bangladeshi start date.
3. **Sehri and Iftar are calculated references**, not an authority's timetable, and no buffer is applied.
   A community that follows a different Sehri end must align it through the manual adjustment or their own
   timetable.
4. **The Bangla calendar is implemented only for 14 April 2019 onwards**, and only for Bangladesh's rules.
5. **Hijri conversion is limited to the platform's table** (roughly AH 1300–1600).
6. **The calendar day follows the device zone**, so a user whose device zone differs from their location's
   zone will see the Gregorian/Bangla/Hijri date of where the device is, while the times are those of the
   configured location. This is deliberate (§3) but it is a real difference the user could notice.
7. **A date outside a converter's range is refused.** Selecting Bangla or Hijri for a month the converter
   cannot cover falls back to the Gregorian month for the grid and states the reason on the selected day,
   rather than showing a fabricated month.
8. **No on-device verification has been performed.** The screens have not been exercised on physical
   hardware, in either theme, at a large font scale, or with a real device time-zone change. What is
   verified is the logic: the converters, the state mapping and the persistence are covered by the tests
   in §13.
9. **The date language setting is not a full app translation.** It chooses the script and numerals of
   *calendar* dates; the surrounding UI strings come from the app's resources, which are currently English
   with a partial Bangla translation (see `README.md`).

---

## 13. Automated tests

| Test | Covers |
|---|---|
| `BanglaCalendarTest` | the published rules and reference dates; every month start; month, year and leap-year boundaries; the 14 April New Year; a whole supported year converted and converted back; refusal before 2019 and after 2399 |
| `HijriCalendarTest` | the Umm al-Qura reference conversions; month boundaries on both sides; the adjustment and its source label; Hijri month starts and lengths (29 and 30 days); Ramadan window lengths; the window search; `isRamadan` under an offset; observances; the table's bounds |
| `CalendarMonthResolverTest` | which Gregorian days each system's month covers; the Gregorian leap day; the Bangla month boundaries (Bhadra/Ashwin, Falgun's leap 30 days); the Hijri month length and its movement with the adjustment; refusal outside both supported ranges; every day of every month numbered 1..length |
| `RamadanCalendarTest` | Sehri = Fajr and Iftar = Maghrib; the manual adjustment moving Sehri with Fajr; a missing Fajr/Maghrib producing no time; day numbering; `IN_RAMADAN`/`UPCOMING`/`FAR_AWAY` on both sides of each boundary |
| `GregorianCalendarGridTest` | seven cells per row; day order and padding; leading blanks for four different week starts; leap years including the century rules; year transitions; extreme months; `today`, the Ramadan window and the first day of week carried through; a span that is not a Gregorian month |
| `CalendarFormattingTest` | Bangla and Arabic-Indic numerals; the language preference choosing the locale; the grid locale following the calendar system; Bangla and Hijri date and month strings composed without mixing scripts; the platform's formatter for Gregorian dates; weekday labels |
| `DeviceClockCalendarRepositoryTest` | the device day in the device's zone; the verified/unverified source flags; the date language; the rollover at local midnight; the Hijri month boundary at midnight; DST; out-of-range reporting for both converters; a month laid out with real Bangla/Hijri dates and Ramadan day numbers; the Ramadan window and its movement with the offset |
| `CalendarUiStateFactoryTest` | today vs the selection vs both; three resolved date lines and the Hijri source; out-of-range stated rather than blanked; no window → no range; weekday headers labelling the columns the grid lays out; the Bangla and Hijri views laying out their own month, heading, weekday names and numerals; the adjustment moving the Hijri date and the Ramadan day number together |
| `CalendarViewModelTest` | selecting a mode changing the displayed month immediately; the choice persisted; the stored mode restored on open; month navigation moving one month of the selected system; the selection staying inside the displayed month |
| `RamadanUiStateFactoryTest` | one row per day with day numbers; Sehri/Iftar columns equal to Fajr/Maghrib; a missing Fajr; missing schedules counted once and shown per row; provenance; no window → no rows; the Hijri adjustment; today marked by date; the location's zone |
| `RamadanReminderPlanTest` | Sehri planned as a lead-in only; Iftar at Maghrib plus an optional lead-in; nothing outside Ramadan; switches and master switch; unique slots and request codes; a Ramadan end leaving nothing behind; inherited sound/vibration; a passed Fajr; lead-time clamping |
| `RamadanReminderCoordinatorTest` | what the coordinator actually registers: the Sehri lead-in at the calculated Fajr and Iftar at the calculated Maghrib for both window days and nothing else; the Hijri offset moving which days are in Ramadan, in both directions, and the day that leaves Ramadan being cancelled; the last day of Ramadan keeping its reminders while the day after schedules none, and those reminders going on the next day; one switch cancelling only its own alarms; the master switch and a missing location leaving nothing; repeating a reconciliation not duplicating an alarm |
| `DataStoreUserPreferencesRepositoryTest` | calendar preferences persisted and restored; the adjustment clamped on write and read; unknown names falling back; calendar, prayer and theme preferences not overwriting each other |
| `DataStorePrayerNotificationPreferencesRepositoryTest` | the Ramadan switches and lead times persisted and restored; values outside the offered set clamped on write and read; "off" stored as off; the prayer preferences untouched |
| `HomeUiStateFactoryTest` | the Home screen carrying the real Bangla and Hijri values, and explaining an out-of-range date |

Run them with `./gradlew testDebugUnitTest` — or, in CI, as part of the debug APK workflow that also runs
lint and builds the APK.

---

## 14. References

- **Bangladesh Bangla calendar rules**: The Daily Star, "Why Pahela Falgun is celebrated on February 14
  instead of 13" (13 February 2024) — the 2019 revision, the six 31-day months, Falgun's 29/30 days and the
  "divisible by four and not by 100" leap wording; Dhaka Tribune, "Ashshin in 31 days in revised Bangla
  calendar" (22 October 2019) — the first six months at 31 days.
- **Hijri calendar**: the `islamic-umalqura` chronology in `java.time.chrono.HijrahChronology`, i.e. the
  Umm al-Qura table published with the platform (see `docs/TECH_STACK.md`).
- **Prayer times and their provenance**: [`docs/PRAYER_CALCULATION.md`](PRAYER_CALCULATION.md).
- **The notification subsystem the Ramadan reminders use**:
  [`docs/PRAYER_NOTIFICATIONS.md`](PRAYER_NOTIFICATIONS.md).
- **Structure and dependency rules**: [`docs/ARCHITECTURE.md`](ARCHITECTURE.md).
- **The calendar-mode implementation in detail** (how a selection reaches the grid, and the two-locale
  rule): [`docs/CALENDAR_IMPLEMENTATION.md`](CALENDAR_IMPLEMENTATION.md).
