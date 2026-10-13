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
| Hijri | Islamic Hijri calendar (platform Umm al-Qura table) | a Hijri month: the Hijri month and weekday names **in the interface language** (English `Ramadan`, Bengali `রমজান`), day numbers in that language's numerals, the Hijri year, laid out right-to-left |

## 3. Calendar mode vs date language

Salah keeps two preferences, and they answer different questions:

- **Calendar system** (`CalendarSettings.preferredSystem`) — *which* calendar the grid shows. It
  decides the month, its days, its length and its year. It is a statement about the **data**, not about
  the script: the Gregorian and Hijri grids are written in the interface language, and in the stored
  date language when the user has explicitly chosen one.
- **Date language** (`CalendarSettings.dateLanguage`) — the script and numerals of the **side-by-side
  date list** (the Today card and the selected-day card), which shows all three calendars at once and
  therefore needs one script chosen for all of them.

`CalendarFormatting` exposes both rules explicitly:

- `gridLocale(system, dateLanguage, systemDefault)` — the script of the grid and its heading.
  **Gregorian and Hijri both defer** to the stored date language, whose `SYSTEM` branch follows the
  interface language passed in as `systemDefault`; so a Bengali interface shows `রমজান ১৪৪৫` and an
  English one `Ramadan 1445` for the same Hijri month. Bangla is the one mode pinned to Bengali (`bn`),
  because its month names (`বৈশাখ`) are that calendar's own rather than an alternative rendering of a
  Gregorian month — there is no second language to render them in.
- `localeFor(dateLanguage, systemDefault)` — the script of the multi-calendar list.

**The interface language is the caller's, not the device's.** `AppLanguage` is threaded from the
preference store into this formatting as `systemDefault` (`CalendarViewModel` → `CalendarUiStateFactory`,
`RamadanViewModel` → `RamadanUiStateFactory`, and `DeviceClockCalendarRepository.interfaceLanguage` for
`observeToday()`), so "follow the interface" follows the app's own language selector. No composable and
no view model contains a hardcoded language check; the decision lives in these two methods.

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
- Numerals are per **rendering language**: English `12`, Bengali `১২` (`BanglaNumerals`), and
  Arabic-Indic `١٢` (`ArabicNumerals`) only for a genuinely Arabic locale, which the app never selects.
  They are explicit digit tables rather than a platform formatter, so the digits always match the month
  name's script and an English Hijri grid can never be a Bengali year printed in Latin digits (or the
  reverse). `CalendarFormatting.numerals(value, locale)` picks the table from the locale, and
  `localizeDigits` applies the same substitution to the platform's Gregorian output.
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
5. **Weekday abbreviations come from the platform's locale data** (`TextStyle.SHORT`) for the rendering
   locale, so they follow the interface language like every other label. The Hijri and Bangla month
   names and all numerals do not — those are composed from explicit tables, so a missing or incomplete
   locale on the device cannot fall back to Arabic script in an English or Bengali UI.
6. **No on-device visual verification has been performed in this change.** What is verified is the
   logic — the converters, the month resolver, the state mapping, the view-model flow and the
   persistence — through the unit tests listed in `CALENDAR_SYSTEM.md` §13. CI
   (`.github/workflows/android-apk.yml`) is the authoritative build/test/lint/APK environment.

## 11. The Hijri grid's language (the Arabic-only display fixed)

**Symptom.** The Hijri mode always rendered in Arabic script — `رَمَضان ١٤٤٥`, Arabic weekday headers and
Arabic-Indic day numbers — in an English or Bengali interface, while every other label on the screen was
in the selected language.

**Root cause.** It was in the *locale selection*, not in the converter or the data. `HijriMonth` already
carried `englishName`, `bengaliName` and `arabicName`, and `CalendarFormatting.hijri`/`hijriMonth` already
composed their string from a locale — but `gridLocale(system, dateLanguage)` returned `Locale("ar")` for
`CalendarSystem.HIJRI` unconditionally, and the numeral table was chosen the same way. The rendering
library was language-aware; the mode simply asked it for Arabic. The `AppLanguage` preference could not
influence it, because that value was only ever consulted for `DateLanguagePreference.SYSTEM` in the
side-by-side date list.

**Fix.** `gridLocale` now defers for Hijri exactly as it does for Gregorian
(`localeFor(dateLanguage, systemDefault)`), and `hijriMonthName`/`numerals` map the rendering locale to the
month's own name in that script — Bengali for `bn`, Arabic for `ar`, English otherwise. The Bangla mode
stays pinned to `bn`. No separate Hijri language preference was introduced: `AppLanguage` is the single
source of truth, and it is threaded in as `systemDefault` from every entry point (the Calendar view model,
the Ramadan view model, and the repository's `interfaceLanguage` for Home's date lines). `CalendarSystem`
and `AppLanguage` remain independent — changing the language never moves a date, the month span, the
selection or the Ramadan window; it only changes which words and digits are drawn.

| Mode | `AppLanguage.ENGLISH` | `AppLanguage.BANGLA` | `dateLanguage = BANGLA/ENGLISH` |
|---|---|---|---|
| Gregorian | `October 2026` | `অক্টোবর ২০২৬` | the pinned language wins |
| Bangla | `আশ্বিন ১৪৩১` | `আশ্বিন ১৪৩১` | pinned to Bengali either way |
| Hijri | `Ramadan 1445` | `রমজান ১৪৪৫` | the pinned language wins |

The cell, list and row semantics of §6 are unchanged: the grid's day number is the Hijri day only for
the *number*, while every cell keeps its Gregorian `LocalDate` as its identity.

**Coverage.** `CalendarFormattingTest` pins the formatter (English and Bengali Hijri strings, no Arabic
script in either, Arabic still reachable for an `ar` locale, and `gridLocale` per mode);
`CalendarUiStateFactoryTest` pins the grid, the weekday headers and both date cards in English and
Bengali and asserts that no label contains a character from the Arabic block;
`CalendarViewModelTest` pins the language change re-rendering the same month and the language being read
back from the store; `RamadanUiStateFactoryTest` pins the reuse of the formatter on the other screen that
shows Hijri dates. Arabic remains in the app only where it is deliberate religious content — see
`LOCALIZATION.md` §10 and §12.
