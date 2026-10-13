# Salah — Calendar mode implementation

This is the focused record of how the three calendar **modes** work: what selecting Gregorian, Bangla
or Hijri actually changes, where the conversion happens, how a displayed date keeps its Gregorian
identity, and how the choice is persisted. It complements [`docs/CALENDAR_SYSTEM.md`](CALENDAR_SYSTEM.md),
which documents the calendar *systems* themselves (the Bangladesh rules, the Umm al-Qura table, the
Ramadan window). Where the two overlap, `CALENDAR_SYSTEM.md` is the deeper reference.

## 1. The bug this file documents the fix for

The Calendar screen has a three-way view switcher (Gregorian / Bangla / Hijri). Selecting Bangla or
Hijri did not change the calendar that was shown: the header, weekday names, day numbers and month
navigation all stayed Gregorian, so the selection had no visible effect.

The cause was in the mapping, not in the converters — the converters were already correct and
separately tested:

1. `CalendarRepository.observeMonth(month: YearMonth)` always laid out a **Gregorian** month, and the
   `CalendarViewModel` navigated Gregorian `YearMonth`s.
2. `CalendarUiStateFactory` always rendered that Gregorian month: `monthLabel` was always the Gregorian
   month, `weekdayLabels` were always the Gregorian weekday names, and each cell's number was always
   `date.dayOfMonth` (the Gregorian day).
3. `CalendarSettings.preferredSystem` reached the UI state only as `Ready.system` (the highlighted
   chip) and `selectedOutOfRange`, so swapping the mode changed nothing a user could see in the grid.

The correction makes the **selected system decide which month the repository lays out** and how the
factory renders it, while every cell keeps its Gregorian date as its identity.

## 2. Supported calendar systems

| Mode | Calendar system | Grid shows |
|---|---|---|
| Gregorian | the platform's `java.time` Gregorian calendar | a Gregorian month: Gregorian month/weekday names, Gregorian day numbers, Gregorian year, English (or the device language) |
| Bangla | Bangladesh civil Bangla calendar (2019 Bangla Academy rules) | a Bangladesh Bangla month: Bengali month names and weekday names, Bengali day numbers (১–৯), the Bangla year |
| Hijri | Islamic Hijri calendar (platform Umm al-Qura table) | a Hijri month: Arabic month names and Arabic weekday names, Arabic-Indic day numbers (٠–٩), the Hijri year, laid out right-to-left |

## 3. Calendar mode vs date language

Salah keeps two preferences, and they answer different questions:

- **Calendar system** (`CalendarSettings.preferredSystem`) — *which* calendar the grid shows. It
  decides the month, its days, its length and its year. It also decides the grid's script, because a
  Bangla or Hijri calendar shown in Latin script is not the calendar the mode names.
- **Date language** (`CalendarSettings.dateLanguage`) — the script and numerals of the **side-by-side
  date list** (the Today card and the selected-day card), which shows all three calendars at once and
  therefore needs one script chosen for all of them.

`CalendarFormatting` exposes both rules explicitly:

- `gridLocale(system, dateLanguage)` — the script of the grid and its heading. Gregorian follows the
  date language; Bangla is always Bengali (`bn`); Hijri is always Arabic (`ar`).
- `localeFor(dateLanguage)` — the script of the multi-calendar list.

Changing the calendar system therefore switches the calendar representation; it does not change the
application's UI strings, and it does not silently rewrite the date-language preference.

## 4. How a selection reaches the grid

```text
user taps a chip
    → CalendarViewModel.onSelectSystem(system)
        → re-anchors the displayed month on the selected day
        → persists preferredSystem in DataStore
    → combine(calendarSettings, anchor, selected) re-emits
    → flatMapLatest { calendarRepository.observeMonth(preferredSystem, anchor) }
        → CalendarMonthResolver.monthContaining(system, anchor, hijriOffset)
            → the Gregorian days of that system's month
        → GregorianCalendarGrid.layout(dates, firstDayOfWeek, today, window)
    → CalendarUiStateFactory.create(month, settings, selected)
        → heading, weekday labels and day numbers rendered in the mode's script
    → CalendarScreen recomposes
```

There is one source of truth: the stored `preferredSystem`. The screen holds no second copy, so it
cannot drift, and no refresh or restart is needed — the state flow re-emits as soon as the preference
changes.

## 5. Date conversion strategy

- **Gregorian** — `java.time.YearMonth`/`LocalDate`. No conversion.
- **Bangla** — `BanglaCalendar` (Bangladesh civil calendar, 2019 Bangla Academy rules; Poila Boishakh
  fixed on 14 April, the first six months 31 days, Falgun 29 days or 30 in a Gregorian leap year). The
  month start comes from `toGregorian(BanglaDate(year, month, 1))` and the length from
  `lengthOfMonth`, so both directions walk the same table. Conversion is refused before 14 April 2019
  and after 31 December 2399 rather than approximated. See `CALENDAR_SYSTEM.md` §4.
- **Hijri** — `HijriCalendar` over `java.time.chrono.HijrahChronology`, the **Umm al-Qura** table
  shipped with the platform. Every date is an *estimate* and is labelled as one; Salah has no
  moon-sighting/authority source, so it never presents a confirmed Hijri date. The user's ±2-day
  adjustment is applied before the value is read, so the month start, the day numbers and the Ramadan
  window all move together. See `CALENDAR_SYSTEM.md` §5.
- **Hijri month start/length** — `HijriCalendar.monthStart(year, month, offset)` and
  `monthLength(year, month)`, both read from the platform's table, so a 29-day lunar month stays 29
  days and a 30-day one stays 30.

`CalendarMonthResolver.monthContaining(system, anchor, hijriOffset)` returns a `CalendarMonthSpan`
(`start`, `end`, `length`, `dates`) — the run of **Gregorian** days the selected system's month
covers. It returns `null` when that month cannot be established (a date outside the verified Bangla
range or the platform's Hijri table); the repository then lays out the Gregorian month and the screen
states the reason, so the grid is never empty and no date is invented.

## 6. Date identity and mapping

A cell's identity is its **Gregorian `LocalDate`** (`CalendarCell.date`, `CalendarDay.date`). The
displayed number in Bangla and Hijri modes is a *rendering* of that date, never a key:

- `CalendarCell.dayOfMonth` / `dayLabel` are the day number in the selected system and its numerals.
- Prayer times, the Ramadan day number, Sehri/Iftar and every other date-dependent feature continue to
  key off the Gregorian date, so switching modes cannot make the app look up the wrong day's data.
- The month the screen displays is held as a **Gregorian anchor date**, not a `YearMonth`, because a
  Gregorian `YearMonth` cannot express "the previous Bangla month" or "the next Hijri month". Previous
  and next move to the day before the month's start and the day after its end, which is exactly one
  month of the selected system in either direction.

## 7. Month navigation

- Previous/next move one month of the **selected calendar system**.
- The boundaries come from the displayed month itself (`Ready.monthStart` / `Ready.monthEnd`), so a
  29-day Hijri month and a leap-year Falgun are handled exactly; no calendar month is assumed to be 30
  or 31 days.
- The selection follows into the new month, so the details card never describes a day that is not in
  view.

## 8. Grid and numerals

- The weekday header is built from `CalendarMonth.firstDayOfWeek` — the same value the grid used for
  its leading padding — so a header can never sit above the wrong column. (The first day of the week
  is still the locale's own, via `WeekFields.of(locale).firstDayOfWeek`.)
- Numerals are per mode: English `12`, Bangla `১২` (`BanglaNumerals`), Hijri Arabic-Indic `١٢`
  (`ArabicNumerals`). They are explicit digit tables rather than a platform formatter, so the digits
  always match the month name's script.
- The Hijri grid is laid out right-to-left. The direction is scoped to the month card only
  (`LocalLayoutDirection` inside that card), so the rest of the app is unaffected.

## 9. Preference persistence

Nothing new is persisted, and there is no second store. The mode lives in the existing shared DataStore
under the existing key:

| Preference | Key | Default |
|---|---|---|
| Calendar system shown | `calendar_preferred_system` | `GREGORIAN` |
| Hijri day offset | `calendar_hijri_adjustment_days` | `0` |
| Date language (the side-by-side list) | `calendar_date_language` | `SYSTEM` |

Unknown or out-of-range stored values fall back to the documented default on read, and the offset is
clamped on write and on read, so the screen can always represent what is stored.

## 10. Known limitations

1. **The Hijri date is an estimate.** Umm al-Qura is not what Bangladesh's religious authorities
   announce; Salah labels it as an estimate everywhere and never as confirmed.
2. **Bangla conversion is offered only for 14 April 2019 onwards** and only for Bangladesh's rules.
3. **Hijri conversion is limited to the platform's table** (roughly AH 1300–1600).
4. **Out-of-range modes fall back to the Gregorian month.** When the Bangla or Hijri month cannot be
   established for the displayed anchor, the grid shows the Gregorian month and the selected-day card
   states the reason; the mode is not silently substituted.
5. **Weekday abbreviations come from the platform's locale data** for the mode's script (`Locale("bn")`,
   `Locale("ar")`). The month names and numerals do not — those are composed from explicit tables.
6. **No on-device visual verification has been performed in this change.** What is verified is the
   logic — the converters, the month resolver, the state mapping, the view-model flow and the
   persistence — through the unit tests listed in `CALENDAR_SYSTEM.md` §13. CI
   (`.github/workflows/android-apk.yml`) is the authoritative build/test/lint/APK environment.
