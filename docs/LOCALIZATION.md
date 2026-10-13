# Localization in Salah

How the interface language is chosen, stored and applied; which screens are translated; and how the
interface language stays independent of the calendar system and of the calendar's own date language.

## 1. Supported languages

| Preference        | Stable identifier (stored) | Locale            | Meaning                                        |
|-------------------|----------------------------|-------------------|------------------------------------------------|
| `AppLanguage.SYSTEM`  | `SYSTEM`               | *(none)*          | Follow the device's own locale — the default    |
| `AppLanguage.ENGLISH` | `ENGLISH`              | `en`              | English interface text                          |
| `AppLanguage.BANGLA`  | `BANGLA`               | `bn`              | Bangladeshi Bengali interface text              |

The **stored** value is the enum's `name`. The BCP-47 subtag (`en`, `bn`) is what a `Locale` is built
from and what the Android resource folder is keyed by (`values-bn`). A translated display label is never
stored: the labels live in string resources (`settings_language_english` etc.), so `AppLanguage` can
carry no translatable text into the preference store.

`SYSTEM` is the default, and it resolves to *no* locale rather than to a pinned one, so an existing
install keeps exactly the behaviour it had before the selector existed — the device's locale decides.
The preference is independent of the locale list the rest of the system uses; the app does not call
`Locale.setDefault`.

## 2. How the choice is stored

`AppLanguage` is one key in the single `salah_preferences` DataStore that already holds the theme, the
calculation settings and the calendar settings:

```kotlin
UserPreferencesRepository.appLanguage: Flow<AppLanguage>
UserPreferencesRepository.setAppLanguage(language: AppLanguage)
```

* `DataStoreUserPreferencesRepository` persists it under `stringPreferencesKey("app_language")` and
  reads it back through `AppLanguage.fromIdentifier`, which falls back to `SYSTEM` for an absent or
  unknown value, so a store written by another version cannot make the settings screen unusable.
* `setAppLanguage` writes **exactly one key**. It cannot reset the theme, the calculation settings, the
  preferred calendar system, the calendar's date language or the Hijri offset — asserted by
  `DataStoreUserPreferencesRepositoryTest`.
* The write is not swallowed. `DataStore.edit` throws when the store cannot be written, and
  `SettingsViewModel.setAppLanguage` catches that and reports `SettingsMessage.LanguageSaveFailed`, so
  the app cannot claim a language change that did not happen.

## 3. Where the selector is

**Settings → General** (`SalahDestination.SETTINGS_GENERAL`), as the first card on the screen, titled
`Language / ভাষা` in English resources and `ভাষা` in Bengali resources. It is a radio group over
`AppLanguage.entries` with `English`, `বাংলা` and `Follow the device`, and the selected radio is the
*stored* value rather than a local copy.

It is deliberately a card on the General screen rather than a category of its own: three choices do not
need a navigation layer, and the one control a reader who cannot read the current language most needs
should not be behind an extra tap. `SettingsCategoryTest` pins both properties that make it reachable —
the General category is on the overview (which renders `SettingsCategory.entries`) and it opens a
destination the navigation graph declares.

## 4. How the language reaches the UI

`SalahApp` reads `AppViewModel.appLanguage` and wraps the whole shell in `ProvideAppLanguageStrings`,
which provides a locale-pinned `LocalResources`:

```
MainActivity.setContent { SalahApp() }
  SalahApp                       → reads AppLanguage, wraps everything below it
    ProvideAppLanguageStrings    → provides LocalResources for the selected language
      SalahTheme
        AppShell / LocationSetupScreen
          SalahNavHost           → Home, Calendar, Tools, Settings, …
```

* **Why `LocalResources` and nothing else.** Compose's `stringResource` reads `LocalResources` (whose
  default is the current context's resources), not `LocalContext`. Providing only `LocalResources`
  changes what the interface is written in and nothing else. Two screens read `LocalContext` to walk up
  to the hosting `Activity` — the location screen to request a permission and the notification screen to
  open a system settings intent — and a context returned by `createConfigurationContext` is a *sibling*
  of the Activity, not a wrapper around it, so replacing `LocalContext` would have broken that walk.
* **No activity recreation.** The language arrives as a value, so changing it recomposes the subtree with
  new resources. There is no `Activity.recreate()`, no navigation reset, no lost scroll position and no
  re-read of the stored location, the prayer settings or the calendar settings.
* **`SYSTEM` is a genuine no-op.** It forwards the caller's own resources untouched, so the default and
  the pre-selector behaviour are the same code path.
* **No recalculation.** `SettingsViewModel.setAppLanguage` does not call
  `PrayerNotificationCoordinator.reconcile`. A language changes no calculated time, so the alarm set is
  not rebuilt and no timetable is invalidated.

## 5. Which screens respond

| Screen                     | Responds to the interface language | Notes |
|----------------------------|------------------------------------|-------|
| Bottom navigation          | Yes, in full                       | `nav_*` labels (`nav_home`, `nav_calendar`, `nav_settings`, …) |
| Home                       | Yes, in full                       | Every label, button, state and reason the composed screen can render. Its date lines follow the interface language too (§6); its prayer *times* keep the device-locale clock format. See §10 |
| Calendar                   | Yes, in full                       | Every string it can show, plus month/weekday/day rendering |
| Tools                      | Yes, in full                       | The Tools page and every screen it opens — Prayer, Qibla and Ramadan. See §11 |
| Settings                   | Yes, in full                       | The overview and every category it opens: General, Appearance, Prayer times, Notifications & Adhan, Location, Calendar and About. See §11 |
| Prayer notifications       | Yes, in full                       | A Settings category, also reached from the Calendar and Ramadan: the permission explanations, the schedule wording, each prayer's controls and the non-urgent status text |
| Location                   | Yes, in full                       | A Settings category and the first-launch gate: the picker, the manual form, its validation and every provider/permission/error message |
| Qibla, Ramadan             | Yes, in full                       | The two other Tools destinations, including their explanatory paragraphs and error descriptions |
| Work-in-progress destinations | Yes, in full                    | `wip_*` and `feature_prayer_*` |

The Bangla translation was **partial for explanatory wording** for a while: Salah's English sentences
make specific claims about sources and limits (what is an estimate, what is not verified, which
authority is not claimed), and those were left until they could be reviewed. Home and the Calendar were
completed first; Tools and Settings — including the explanation, permission, error and empty-state
wording — are completed now, so the partial policy no longer applies anywhere and `app/lint.xml` no
longer suppresses `MissingTranslation` for `bn` (§8). The only strings that still exist solely in the
default file are the three marked `translatable="false"` — `app_name` ("Salah"), the Arabic
`home_prayer_times_arabic` heading and the `—` / `…` placeholders — which carry no words to translate.

## 6. App language vs. calendar system vs. date language

Three independent preferences, deliberately:

| Preference | Where | What it decides |
|------------|-------|-----------------|
| `AppLanguage.appLanguage` | Settings → General | The words on every screen |
| `CalendarSettings.preferredSystem` | Settings → Calendar | Which calendar the grid displays (Gregorian, Bangladesh Bangla, Hijri) |
| `CalendarSettings.dateLanguage` | Settings → Calendar | The script and numerals dates are written in (`SYSTEM`/`BANGLA`/`ENGLISH`) |
| `CalendarSettings.hijriAdjustmentDays` | Settings → Calendar | The Hijri day offset, and therefore the Ramadan window |

Consequences, which are what the distinction exists for:

* **Language = Bengali, calendar system = Gregorian** → Bengali interface text, the same Gregorian days.
* **Language = English, calendar system = Bangla** → English interface text, the same Bangla calendar
  dates (the Bangla mode is always presented in Bengali, because that mode is about the dates).
* **Language = Bengali, calendar system = Hijri** → Bengali interface text, the same Hijri data, the same
  estimate and the same source wording.
* Changing the interface language **never** writes `preferredSystem`, `dateLanguage` or
  `hijriAdjustmentDays`, and never reschedules a Ramadan reminder. Asserted by
  `DataStoreUserPreferencesRepositoryTest` and `SettingsViewModelTest`.

### What the interface language does to date rendering

The Calendar's date presentation has to obey both the interface language and the stored date language,
and the rule is:

* The chosen `AppLanguage`'s locale is passed to `CalendarUiStateFactory` as the rendering locale, so
  with the stored date language on **`SYSTEM`** ("follow") a Bengali interface shows Bengali month names,
  Bengali weekday headers and Bengali day numbers.
* An **explicit** `DateLanguagePreference.BANGLA`/`ENGLISH` still wins, because that setting is a
  deliberate statement about the dates themselves rather than about the words around them.
* The **Bangla** grid mode is the only one that pins its script, because that mode presents the
  Bangladeshi civil calendar in its own Bengali month names (`বৈশাখ`) rather than an alternative
  rendering of a Gregorian month. The **Gregorian and Hijri** grids both defer, so a Bengali interface
  shows the Hijri month as `রমজান ১৪৪৫` and an English one as `Ramadan 1445` — see §12.
* The **underlying dates do not change**: the Gregorian day is still the identity used by prayer times
  and by Ramadan, the displayed month is still built from the same Gregorian anchor, and switching
  language selects nothing and navigates nowhere.

Home's date lines are produced by `DeviceClockCalendarRepository`, which now receives the stored
`AppLanguage` as an input of its own: `observeToday()` renders today's Gregorian date, weekday, Bangla
date and Hijri date in the interface language, so selecting Bengali on an English phone gives Bengali
month and weekday names for the same day. The precedence is the same one the Calendar uses —
`DateLanguagePreference.SYSTEM` means "follow the interface", while an explicit `BANGLA`/`ENGLISH`
still wins, because that setting is a statement about the dates themselves. The conversions, the day
and the calendar system are untouched: only the locale the strings are written in changes, and
`AppLanguage.SYSTEM` still resolves to `Locale.getDefault()`, so a fresh install behaves exactly as it
did before the selector existed.

**Prayer *times* are deliberately not part of that rule.** A time is formatted by `timeFormatterFor`
from `TimeFormatPreference`, whose `SYSTEM` branch means the device's own clock convention — a
different, stored setting from the interface language — and the same formatter writes the times in
prayer notifications. Making Home's times follow the interface language would therefore make the app
and its own reminders disagree about how "5:12 PM" is written, which is the disagreement
`TimeFormatting` exists to prevent. The countdown is also left alone: `CountdownFormatter` emits
`H:MM:SS` digits under `Locale.ROOT` on purpose, because it is a reading of elapsed time rather than a
religious value. Either way no calculation changes — only the words around a time do.

Changing the interface language triggers no recalculation of the prayer schedule.

## 7. Bengali numerals and typography

* **Day numbers and Bangla/Hijri dates** use the explicit digit tables in `CalendarFormatting`
  (`BanglaNumerals`, `ArabicNumerals`), not a platform formatter, so they are identical on every device
  regardless of how complete its locale data is. The table is chosen by the **rendering locale**, so a
  Hijri date in an English or Bengali interface uses that language's digits — Arabic-Indic digits appear
  only for a genuinely Arabic locale, which this app never selects.
* **Gregorian month names, weekday names and word order** come from the platform's CLDR data via
  `java.time` with the chosen locale, which is the project's existing convention for Gregorian
  formatting.
* **Gregorian *digits* do not.** The platform's own output is passed through `BanglaNumerals` /
  `ArabicNumerals` before it is shown, because the two platforms disagree: the JVM renders `bn` as
  `মার্চ 2024` (Bengali month name, Western digits) while Android's ICU renders it as `মার্চ ২০২৪`. Left
  alone, that mixed heading would sit above a grid of Bengali day numbers. The substitution only
  rewrites `0`–`9` and is idempotent, so a device that already produced Bengali digits is unaffected and
  both platforms render identically. Pinned by `CalendarFormattingTest`.
* **Fonts.** No font is bundled. Bengali is rendered with the platform's system font stack, which ships
  Bengali coverage; `SalahTheme` documents this. No glyph substitution or fallback font is configured,
  and the `Language / ভাষা` title is written so the selector is legible even if a glyph were missing.
* **Layout.** The selector is a standard `SettingsOptionGroup` radio list, the same component used by the
  time-format and calculation-method groups, so no card dimensions, colours or spacing changed. Nothing
  in the Calendar layout was resized: Bengali month names and weekday headers occupy the same grid cells
  as their Latin equivalents, and a long heading wraps inside the existing month-navigation row.

## 8. Tests

| Test | What it pins |
|------|--------------|
| `AppLanguageTest` | The `en`/`bn` identifiers, `SYSTEM` resolving to no locale, unknown identifiers falling back, round-tripping through the stored name |
| `DataStoreUserPreferencesRepositoryTest` | Default is `SYSTEM`; the chosen language survives repository recreation; an unknown stored value falls back; a language write adds exactly one key and rewrites nothing else |
| `SettingsViewModelTest` | The selector opens on the stored value; choosing Bengali/English stores the right identifier; the choice is restored on a new view model; a failed write is reported as `LanguageSaveFailed` with the stored language unchanged; a language change leaves the theme, the calculation settings and every calendar setting alone and reschedules nothing |
| `SettingsCategoryTest` | The General category is a visible row on the overview and opens a declared destination; both languages are offered under stable identifiers |
| `CalendarFormattingTest` | Bangla and Arabic-Indic numerals replace every decimal digit; a Bengali Gregorian heading and date carry Bengali digits and no Western one; the substitution is idempotent; English output is unchanged; the Hijri date and month strings follow the rendering locale (English `Ramadan 1445`, Bengali `রমজান ১৪৪৫`, Arabic only for `ar`) and `gridLocale` defers for both the Gregorian and the Hijri mode |
| `CalendarUiStateFactoryTest` | A Bengali interface renders the Gregorian grid's heading, weekday header and day numbers in Bengali with the same days, month, selection and Ramadan data; an explicit date language still wins; the Bangla mode's heading is Bengali in both; the language changes no calendar system, line or source; the Hijri grid, its weekday headers and both date cards render in English or Bengali with **no Arabic-script character anywhere** (§12) |
| `CalendarViewModelTest` | Selecting Hijri shows the Hijri month in the interface language; a language change re-renders the same month span, days and selection without touching a stored calendar setting; a stored language is read back by a fresh view model |
| `RamadanUiStateFactoryTest` | The Ramadan table's Hijri and Bangla date labels follow the rendering locale, with no Arabic script in a Bengali row |
| `LocalizationResourcesTest` | Every `calendar_*` key has a non-blank Bengali value; the shared loading/error/retry and out-of-range strings exist in Bengali; the navigation labels are written in Bengali script; the Home and selector keys exist in both languages; no Bengali key is an orphan the English default does not define; a translated Calendar string keeps its English format arguments. Since the Home audit (§10) it also pins **every** string the Home composition tree can render — non-missing, non-blank, in Bengali script, with matching format arguments — and it reads the Home composables' own sources to assert that none of them renders a hardcoded sentence (a literal containing a space). Since the Tools/Settings audit (§11) it does the same for every key under `tools_`, `settings_`, `method_`, `asr_`, `high_latitude_`, `time_format_`, `notification(s)_`, `location_`, `qibla_` and `ramadan_`: presence, non-blank, Bengali script and matching format arguments, plus a scan of those six packages' composables for a hardcoded sentence, and it checks that the Tools page's own copy and the Settings category names exist in both languages |
| `DeviceClockCalendarRepositoryTest` | Today's date lines are written in the interface language for the same day, with no Western digit left in the Gregorian date; an explicit date language still wins over the interface language; the default (`SYSTEM` + device locale) is unchanged |

`LocalizationResourcesTest` reads the real files from `app/src/main/res`, found by walking up from the
working directory, so it checks what ships. It exists because a missing resource key compiles, ships and
silently falls back to English, and the `MissingTranslation` ignore in `app/lint.xml` would stay quiet
about it.

No Gradle task is run locally; `.github/workflows/android-apk.yml` runs `testDebugUnitTest`, `lintDebug`
and `assembleDebug` on every push to `main` and uploads the debug APK.

## 9. Known limitations

* **Nothing user-facing is left in English any more.** Home, the Calendar, Tools and every Settings
  screen are translated in full, and the remaining screens' strings are translated too, so the only
  English a Bengali interface can still show is the three `translatable="false"` values (`Salah`, the
  Arabic heading, the dash) and machine values that are not translatable by design — a provider id, the
  place name the user chose, a formatted time under `TimeFormatPreference`, and `CountdownFormatter`'s
  `Locale.ROOT` digits (§6). A future string added without a Bengali value is caught by lint (the
  `MissingTranslation` ignore is gone) and by `LocalizationResourcesTest`.
* **Home's prayer *times* do not follow the interface language**, and neither does the countdown. They
  follow `TimeFormatPreference` and the device's own clock convention — deliberately, and for the reason
  given in §6: the same formatter writes the times inside prayer notifications, so a language-driven
  time format would make the app and its own reminders disagree. The words around a time are Bengali;
  the digits and the clock convention are not. See §10 for why this is the one gap left on Home.
* **The calculation-option names are translated, but Home does not render them.** `method_*`,
  `asr_*`, `high_latitude_*` and `time_format_*` are the conventional names of calculation authorities
  (`Umm al-Qura University, Makkah`, `ISNA (North America)`, …) and of settings choices. They are now
  Bengali, because Settings → Prayer times and the time-format control on General render them and both
  are localised in full (§11). The one Home component that would render them (`CalculationCard`) is
  still not part of the composed Home screen, so nothing on Home shows them either way.
* **No per-app language integration with Android's system settings.** The choice is stored by Salah and
  applied by Salah; it does not appear in the OS "App languages" list, and it does not use
  `AppCompatDelegate.setApplicationLocales` (this app has no AppCompat dependency).
* **No Compose or instrumentation tests**, as recorded in `docs/SETTINGS_FUNCTIONALITY.md` §7, so the
  applied-resources behaviour is covered by the resource-file tests and the date-rendering unit tests
  rather than by a UI test.
* **`SYSTEM` follows the device locale at the time the shell composes.** Changing the device locale
  while the app is running re-resolves through Compose's own configuration handling; the app does not
  pin or restore a locale itself.

## 10. The Home audit

Home is the screen the app opens on, so it is the screen where a leftover English word is noticed
first. This section records the audit that closed it: what was actually wrong, and what is
deliberately still English.

### The three root causes

The visible English came from three different places, and only one of them was a missing translation.
Treating all three as "add strings" would have left two of them in place.

1. **Labels written straight into composables.** `PremiumHomeSections` and `HomeSections` rendered
   around twenty literals — the hero card's `CURRENT PRAYER` / `UP NEXT`, `Time until …`, `Starts …`,
   `Ends …`, `Next prayer`, `Today's Prayer Times`, `View all →`, the prohibited card's heading and
   `View details →`, `Your Daily Tools` and its four tile names, the fortnightly `ends next day`, and
   the `Salah` / `Your Complete Islamic Companion` brand line. No resource lookup, so no language — and
   because it was not a *resource*, `values-bn` could never have covered it. Every one of them is now a
   `stringResource`, including the joins (`%1$s, %2$s`, `%1$s – %2$s`, `%1$s — %2$s`), because the
   separator and the word order in a joined string are properties of the language rather than of the
   layout.
2. **Keys with no Bengali value.** Every string the composed screen *did* pull from resources —
   the prayer and solar-event names, the prohibited-interval names, the location card, the next-prayer
   card, the calculation and provenance cards, the shared loading/error/retry states and the `reason_*`
   sentences that explain an empty section — had no `values-bn` entry, so it fell back to English. The
   `reason_*` set is the one that mattered most in practice: those are the sentences a reader meets when
   a schedule, a location or the rules are missing.
3. **A sentence assembled in code.** `HomeUiStateFactory` returned
   `HomeUiState.Failed(detail = "The Gregorian date is unavailable.")` — an English sentence built in an
   Android-free factory and handed to the UI as text. It is now
   `UnavailabilityReason.DEVICE_DATE_UNAVAILABLE`, a stable identifier that `DomainLabels.messageRes()`
   maps to a resource, which is the pattern every other section of Home already used. The same
   treatment was **not** applied to a *provider* failure's diagnostic text: that is a report about a
   software fault (a provider id and an exception), it is deliberately reported verbatim rather than
   rewritten, and only appears on `Ready.failureDetail`, whose title and retry action are translated.

A fourth cause was found and fixed on the date line rather than in a resource (§6): today's weekday and
dates were rendered in the *device* locale, so Bengali showed `Saturday, 13 October 2026`. The
interface language is now that rendering locale, with an explicit date-language preference still
winning.

### What is deliberately still English, and why

| Item | Reason |
|------|--------|
| `app_name` (`Salah`), `home_prayer_times_arabic` (`مواقيت الصلاة`), `home_time_unavailable` (`—`) | Marked `translatable="false"`: a product name, the traditional Arabic heading, and a dash are the same in every language |
| `method_*`, `asr_*`, `high_latitude_*`, `time_format_*` | Conventional names of calculation authorities and of settings choices; rendered only by `CalculationCard`, which is not part of the composed Home screen (§9) |
| A provider failure's `detail` | Diagnostic text naming a provider and its error; rewriting it would destroy the diagnosis. Its heading (`state_error_title`) and its retry action are Bengali |
| Prayer times and the countdown digits | Formatted values under `TimeFormatPreference` / `CountdownFormatter`, deliberately shared with prayer notifications (§6, §9) |
| The place name | The user's own selected location (`Dhaka`, a division, a GPS-derived area name). Localising it would be inventing a name for a place the user chose |
| `shortcut_qibla` and the pilgrimage/prayer names | Proper names, rendered as the established Bengali terms (`কিবলা`, `ফজর`, `যোহর`, …) rather than as transliterations of the English |

### Components inspected

The whole composition tree, not just `HomeScreen`: `HomeTopBar`, `LocationAndDateHeader`,
`FailureBanner`, `CurrentPrayerHeroCard`, `TodayPrayerTimesSection` / `TodayPrayerCard`,
`ForbiddenTimesCard`, `SunTimesCard`, `DailyToolsSection` / `DailyToolItem`, and — on the prayer screen
Home's two actions open — `TimetableCard`. The dead components that live in the same package
(`BrandHeader`, `CalculationCard`, `ProvenanceCard`, `ShortcutsCard`, `NextPrayerCard`,
`SolarEventsCard`, `ProhibitedTimesCard`, `CalendarCard`, `LocationCard`) were inspected and their
literals localised too, so re-wiring one of them cannot reintroduce English; the resources they use are
translated as far as the table above allows.

### Two labels that changed wording on purpose

* The prohibited-times card was headed `Forbidden Prayer Times` in the composable while the project's
  own resource for the same card read `Prohibited prayer times`. The composable now uses
  `home_prohibited_title`, so one card has one name — and that name is the one the documentation and
  the Bengali file already used. The English wording of that heading therefore changes slightly.
* The Home brand line and header are rendered from `app_name` / `app_tagline` instead of literals, which
  is what makes `Your Complete Islamic Companion` a translated string rather than a frozen one.

## 11. The Tools and Settings audit

Tools and Settings were the last two surfaces left partially translated. Home and the Calendar were
done first (§10), so the work here was to finish the same job for the Tools page and for the Settings
overview and every category it opens, without changing what any screen does or how either language is
stored.

### What was actually wrong

The English text a Bengali reader met on these screens had two causes, and neither was a single missing
label:

1. **Keys with no Bengali value.** Most of the strings the screens *did* pull from resources — the
   Settings category names and descriptions, the Appearance, Prayer-times, Notifications, Location and
   About copy, the calculation-method, Asr and high-latitude option names and their hints, the
   time-format choices, the whole notification-settings screen and the whole location screen — had no
   `values-bn` entry, so Android fell back to English for every one of them. This was the bulk of the
   work: 310 keys, listed and translated in `app/src/main/res/values-bn/strings.xml`.
2. **Strings that only existed in English resources the screens shared with Home.** The Tools page
   names its rows after the destinations they open (`nav_ramadan`, `shortcut_qibla`, …), so those had to
   agree with the Bengali the Home daily-tools row already used — and they now do, from one resource.

A hardcoded sentence in a composable, which was one of Home's three causes (§10), was **not** found on
any of these screens: the audit scanned every composable under `tools`, `settings`, `notifications`,
`location`, `qibla`, `ramadan` and `common` for a rendered literal, and the only literals with a space
are a separator (`" · "`) and a space (`" "`), not copy. So the fix here really was the resources — and
the test that now guards that (below) makes a pasted sentence a failure rather than a silent one.

### What was localised

* **Tools** — the heading, its description, all three tool rows (name and one-line description), and the
  note that states Quran, Dua and Tasbih are not part of Salah yet.
* **The Tools destinations** — the Prayer screen (already covered by the Home keys), the whole Qibla
  screen and the whole Ramadan screen, including their explanatory paragraphs, error descriptions, the
  manual-coordinate form and the accessibility descriptions of the dial.
* **Settings** — the overview title, subtitle and every category row; General (language and time
  format); Appearance (the three theme modes); Prayer times (calculation method, Asr convention,
  high-latitude rule and the per-prayer minute corrections); Notifications & Adhan (the permission
  cards, the master switch, the schedule card, each prayer's sound/vibration/advance controls, the
  sunrise and Ramadan reminders, the Azan disclaimer and the limitations card); Location (the setup
  screen, the management screen, the division/district/upazila picker, the manual form and every
  validation, permission and provider message); Calendar (already translated); and About.
* **Shared surfaces** — the shared loading/error/retry strings, the back action, the notification
  channels and reminder content Android draws, and the planned-destination screen.

Nothing about behaviour changed. The DataStore keys, the stored enum identifiers, the calendar
preferences, the location system, the calculation profile and the reminder scheduler are all untouched:
this change adds translations and the tests that pin them, and no stored value or navigation route moved
(confirmed by the existing `DataStoreUserPreferencesRepositoryTest`, `SettingsViewModelTest`,
`SettingsCategoryTest` and `SalahBottomNavigationTest`, which still hold).

### Bengali terminology

The translation uses the terms the earlier work established, so the same idea reads the same on every
screen:

| English | Bengali | English | Bengali |
|---------|---------|---------|---------|
| Tools | টুলস | Settings | সেটিংস |
| Prayer times | নামাজের সময়সূচি | Location | অবস্থান |
| Qibla | কিবলা | Notifications | নোটিফিকেশন |
| Ramadan | রমজান | Adhan | আজান |
| Sehri | সেহরি | Appearance | চেহারা ও প্রদর্শন |
| Iftar | ইফতার | About Salah | Salah সম্পর্কে |
| Back | ফিরে যান | Loading… | লোড হচ্ছে… |
| Retry | আবার চেষ্টা করুন | Save | সংরক্ষণ করুন |
| Cancel | বাতিল | On / Off | চালু / বন্ধ |

A few strings are deliberately **not** translated or transliterated: `settings_language_english` stays
`English` (a language's name is written in its own script), the Arabic `home_prayer_times_arabic`
heading stays Arabic, `app_name` stays `Salah`, and the astronomical coordinates and IANA time-zone ids
(`Asia/Dhaka`) stay in Latin because they are values rather than words.

### How a new screen adds strings

1. Add the English string to `app/src/main/res/values/strings.xml` with a name that follows the
existing convention (`<surface>_<thing>`, e.g. `settings_foo_title`, `tools_foo_description`).
2. Add the Bengali string under the **same name** to `app/src/main/res/values-bn/strings.xml`. The name
   is the key in both files; only the value changes.
3. Keep the format arguments identical **and in the same order** (`%1$s`, `%2$d`, …). A reordered or
   dropped placeholder is an `IllegalFormatException` at runtime, and `LocalizationResourcesTest`
   compares the arguments of a Bengali string against its English original.
4. Do not build a sentence in code. Pass a stable value or a resource id through the UI state and let
   the screen resolve it, as `DomainLabels` and `LocationOriginLabels` do.
5. If the surface is one of the four with a completeness test, add the key to the relevant list in
   `LocalizationResourcesTest`; if it belongs to a prefix the test already checks (`settings_`,
   `tools_`, `notification`, `location_`, …), nothing else is needed — the test finds it by prefix.

### Tests added or updated

`LocalizationResourcesTest` gained seven checks (§8): completeness, non-blank, Bengali-script and
format-argument checks over the `tools_`/`settings_`/`method_`/`asr_`/`high_latitude_`/`time_format_`/
`notification(s)_`/`location_`/`qibla_`/`ramadan_` prefixes; explicit existence checks for the Tools
page's own copy and the Settings category names; and the composable scan described above. No existing
test was removed or weakened, and no behavioural test changed: the change is additive.

### Verification

No Gradle task is run locally (the project's Android build runs only in CI). The changes were checked
statically — resource references, format arguments, prefix completeness and the composable scan were all
re-run over the real files — and the pushed commit's GitHub Actions run is the authoritative
`testDebugUnitTest` / `lintDebug` / `assembleDebug` result, reported with the change.

## 12. The Hijri calendar audit

The Calendar screen, the Ramadan table and Home's date line all show Hijri dates, and all three rendered
them in **Arabic script** regardless of the selected language: `رَمَضان ١٤٤٥`, Arabic weekday headers and
Arabic-Indic day numbers, in an English or Bengali interface whose every other label was in the selected
language. (`رَمَضان` in this historical description is the bug being quoted, not a value the app
produces any more.)

### The root cause

Not a missing translation and not a missing resource. `HijriMonth` already carried `englishName`,
`bengaliName` and `arabicName`, and `CalendarFormatting.hijri` / `hijriMonth` already composed their
string from a locale, mapping `bn` to the Bengali name and everything else to the English one. The bug
was one line of *locale selection*: the grid locale for the Hijri mode was pinned to `Locale("ar")`,
and the numeral table was chosen from the same value. So the mode asked a language-aware formatter for
Arabic and got Arabic.

`AppLanguage` was the right input and was already stored, but it reached the *side-by-side date list*
only; the grid never consulted it. Home's date line already followed the interface language through
`DeviceClockCalendarRepository.interfaceLanguage`, so two screens disagreed about the language of the
same month.

### The fix

* `CalendarFormatting.gridLocale(system, dateLanguage, systemDefault)` now defers for Hijri exactly as it
does for Gregorian, and takes the interface language as `systemDefault`. Only the Bangla mode stays
pinned, because its month names are that calendar's own.
* `hijriMonthName` maps the rendering locale to the month's name in that script — Bengali for `bn`,
Arabic for `ar`, English otherwise — and `numerals` picks the matching digit table, so an English Hijri
grid can neither show a Bengali year in Latin digits nor an English month over Arabic-Indic ones.
* The interface language is passed in as a parameter from every entry point rather than read from the
device inside the formatter: `CalendarViewModel` → `CalendarUiStateFactory` (already), the repository's
`observeToday()` (already), and `RamadanViewModel` → `RamadanUiStateFactory`, which previously used the
factory's own default and so followed the device's locale.
* No new preference and no language check in any composable. `AppLanguage` remains the single source of
truth, and the screens that *display* dates now agree with the screens that display text.

### What deliberately did not change

* **The calculation.** The Hijri conversion, the ±2-day adjustment, the Ramadan window and every value
keyed off a Gregorian `LocalDate` are untouched: this is a rendering change, and `HijriCalendarTest` and
`CalendarMonthResolverTest` pass unmodified.
* **The independence of language and calendar system.** Choosing Bengali still does not switch the grid
to the Bangla civil calendar, move the displayed month, reselect a day or rewrite a stored setting.
* **The Arabic that is meant to be Arabic.** The traditional `المواقيت` heading, Quranic text and du'as
are religious content rather than date labels and are not translated here (§10). Arabic-script *output*
is also still supported and tested for a genuinely Arabic locale, so nothing was removed from the
formatter.
* **The UI.** No layout, spacing, colour or component changed, and no feature was removed.

### Tests added or updated

| Test | Change |
|------|--------|
| `CalendarFormattingTest` | The Hijri case now pins exact English and Bengali strings, asserts neither contains Arabic script, asserts the `ar` locale still produces Arabic, and covers `gridLocale` per mode. Every `HijriMonth` is checked for a non-blank English and Bengali name with no Arabic script in either. |
| `CalendarUiStateFactoryTest` | The Hijri grid case asserts `Ramadan 1445` and Western day numbers in English; new cases cover the Bengali Hijri grid and both date cards in both languages; a shared `assertNoArabicScript` helper checks the heading, the weekday headers, every cell and both cards. |
| `CalendarViewModelTest` | The Hijri case asserts the interface language; a new case changes the language mid-session and pins the unchanged month span, days and selection; a new case reads the stored language back on a fresh view model. |
| `RamadanUiStateFactoryTest` | A new Bengali-rendering case over the same day as the English one, asserting the Hijri and Bangla row labels and that nothing on the row or in the window heading is in Arabic script. |

No test was deleted, weakened or made conditional, and no assertion was relaxed to accommodate the
change. The Ramadan *view model*'s pass-through of `AppLanguage` to the factory has no unit test of its
own (there is no `RamadanViewModelTest` in the source set, and the view model has seven collaborators);
the pass-through is covered by inspection, and the behaviour it feeds is covered by the factory test
above.

### Verification

No Gradle task is run locally (the project's Android build runs only in CI). The changes were checked
statically: every Arabic-script literal remaining in `app/src/main` and `app/src/test` was enumerated,
and each one is either an intentional table (`HijriMonth.arabicName`, `ArabicNumerals`), a KDoc example,
or a test that deliberately passes an Arabic locale. The pushed commit's GitHub Actions run is the
authoritative `testDebugUnitTest` / `lintDebug` / `assembleDebug` result, reported with the change.

