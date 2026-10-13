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
| Bottom navigation          | Yes                                | `nav_*` labels (`nav_home`, `nav_calendar`, `nav_settings`, …) |
| Settings overview + General| Yes                                | Including the selector's own labels and the back action |
| Home                       | Yes (as far as its strings are translated) | Home's own date lines and prayer times keep the calendar/date-language and device-locale formatting described below |
| Calendar                   | Yes, in full                       | Every string it can show, plus month/weekday/day rendering |
| Ramadan, Qibla, Location, Notifications | Only the strings already translated in `values-bn` | These screens are out of scope for this phase; they keep the deliberate partial translation |

The Bangla translation stays **partial for explanatory wording** on purpose: Salah's English sentences
make specific claims about sources and limits (what is an estimate, what is not verified, which
authority is not claimed), and translating them loosely would change an accuracy claim. `app/lint.xml`
records that decision and scopes its `MissingTranslation` ignore to `bn` by message, so adding any other
untranslated locale is still an error.

The **Calendar is the exception**: every string it can render is translated, and its completeness is
enforced by tests rather than by lint (§8).

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
* The **Bangla** and **Hijri** grid modes ignore both, because those modes present a calendar in its own
  script (Bengali and Arabic respectively). Only the Gregorian grid defers.
* The **underlying dates do not change**: the Gregorian day is still the identity used by prayer times
  and by Ramadan, the displayed month is still built from the same Gregorian anchor, and switching
  language selects nothing and navigates nowhere.

Home's date lines and prayer times are produced by `DeviceClockCalendarRepository` and
`HomeUiStateFactory` from the stored date-language preference and the device locale's numeral policy;
this phase does not change that, and changing the interface language triggers no recalculation of the
prayer schedule.

## 7. Bengali numerals and typography

* **Day numbers and Bangla/Hijri dates** use the explicit digit tables in `CalendarFormatting`
  (`BanglaNumerals`, `ArabicNumerals`), not a platform formatter, so they are identical on every device
  regardless of how complete its locale data is.
* **Gregorian month names, weekday names and years** come from the platform's CLDR data via
  `java.time` with the chosen locale, which is the project's existing convention for Gregorian
  formatting.
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
| `CalendarUiStateFactoryTest` | A Bengali interface renders the Gregorian grid's heading, weekday header and day numbers in Bengali with the same days, month, selection and Ramadan data; an explicit date language still wins; the Bangla mode's heading is Bengali in both; the language changes no calendar system, line or source |
| `LocalizationResourcesTest` | Every `calendar_*` key has a non-blank Bengali value; the shared loading/error/retry and out-of-range strings exist in Bengali; the navigation labels are written in Bengali script; the Home and selector keys exist in both languages; no Bengali key is an orphan the English default does not define; a translated Calendar string keeps its English format arguments |

`LocalizationResourcesTest` reads the real files from `app/src/main/res`, found by walking up from the
working directory, so it checks what ships. It exists because a missing resource key compiles, ships and
silently falls back to English, and the `MissingTranslation` ignore in `app/lint.xml` would stay quiet
about it.

No Gradle task is run locally; `.github/workflows/android-apk.yml` runs `testDebugUnitTest`, `lintDebug`
and `assembleDebug` on every push to `main` and uploads the debug APK.

## 9. Known limitations

* **The translation is partial outside the Calendar.** The explanatory wording on Home, Ramadan, Qibla,
  Location, Notifications and the Qibla/About screens stays in English until it can be reviewed by
  someone competent to review those accuracy claims (see `app/lint.xml`). Selecting Bengali therefore
  changes those screens' already-translated labels and leaves their paragraphs in English.
* **Home's date lines and prayer times do not follow the interface language.** They follow the calendar's
  date-language preference and the device locale's time formatting; making them follow the interface
  language would change what a time looks like and is deliberately out of scope here.
* **No per-app language integration with Android's system settings.** The choice is stored by Salah and
  applied by Salah; it does not appear in the OS "App languages" list, and it does not use
  `AppCompatDelegate.setApplicationLocales` (this app has no AppCompat dependency).
* **No Compose or instrumentation tests**, as recorded in `docs/SETTINGS_FUNCTIONALITY.md` §7, so the
  applied-resources behaviour is covered by the resource-file tests and the date-rendering unit tests
  rather than by a UI test.
* **`SYSTEM` follows the device locale at the time the shell composes.** Changing the device locale
  while the app is running re-resolves through Compose's own configuration handling; the app does not
  pin or restore a locale itself.
