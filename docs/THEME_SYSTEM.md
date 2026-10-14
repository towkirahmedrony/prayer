# Salah — Theme System

This documents the appearance system **as implemented in this repository**: how a theme mode reaches the
screen, which colours are the scheme's and which are deliberately fixed, what the dark-theme audit found
and repaired, and what remains a limitation.

## 1. One source of truth

```
Settings → Appearance  ──▶  SettingsViewModel.setThemeMode  ──▶  DataStore (`theme_mode`)
                                                                        │
AppViewModel.themeMode (StateFlow, initial SYSTEM) ──────────────────────┘
        │
        ▼
SalahApp  ─────▶  SalahTheme(themeMode)  ─────▶  MaterialTheme(colorScheme = light | dark)
                                                        │
                                                        ├── every screen, dialog, menu and input
                                                        └── LocalSalahAccents (the fixed accents)
```

- **One theme for the whole app.** `SalahTheme` is applied once, in the shell (`SalahApp`), so a change
  re-themes every screen on the next emission — no restart, no activity recreation, no second theme
  state, and no `isSystemInDarkTheme()` call anywhere in a feature.
- **The stored preference is the applied preference.** `SalahTheme` takes the mode as a parameter with no
  "active" default, and the shell passes the stored value; a mode passed but not applied (or applied but
  not passed) is not possible.
- **The mode → scheme mapping lives in one function.** `resolveDarkTheme(themeMode, systemInDarkTheme)`
  answers `SYSTEM`/`LIGHT`/`DARK` and nothing else interprets the enum. `SalahColorSchemeTest` asserts
  both override directions.
- **System bars.** `SalahTheme` flips `isAppearanceLightStatusBars`/`isAppearanceLightNavigationBars` from
  the *resolved* theme rather than the platform's, so the icons stay visible when the user's explicit
  choice disagrees with the device. The bars themselves are transparent (`enableEdgeToEdge`, themes.xml),
  so the colour behind them is the active scheme's.

## 2. What is a scheme role and what is fixed

Every colour a Material component reads is declared in `SalahLightColorScheme` or `SalahDarkColorScheme`.
Three things are **deliberately fixed** and live in `SalahAccents` (provided as `LocalSalahAccents`):

| Accent | Value | Why it is not themed |
|---|---|---|
| `brandSurface` / `onBrandSurface` | `ForestGreenPrimary` / white | The next-prayer hero and the bottom bar are the app's identity, like white type on a brand-coloured button. Identical in both themes by design — declared once so the hero and the bar cannot drift apart. |
| `goldText` | `GoldText` (light) / `GoldMuted` (dark) | One gold cannot serve both canvases: the mid gold is ~2.4:1 on cream, the dark gold is too dim on near-black. The accent carries the value that belongs to the active theme. |
| `ramadanToday` / `onRamadanToday` | gold fill + readable content | The Ramadan table's "today" row, kept out of the shared scheme so it cannot leak onto a control. |

`secondary` (the brand's mid gold, `#C49A45`) is *not* re-tinted: it draws the Qibla dial's band and the
calendar's Ramadan marker, and the palette keeps it gold in both themes. See §6.

## 3. Completing the palette (root cause 1)

A `lightColorScheme`/`darkColorScheme` call fills every parameter it is **not** given with Material's
*baseline* palette. Salah had declared the roles the first screens used and left the rest out, so:

- `tertiary` — used by the Calendar's "observance" marker and its legend — rendered as Material's plum
  (`#7D5260` light, `#EFB8C8` dark), a colour that appears nowhere else in the app.
- `surfaceContainer*` — read by `DropdownMenu` (the location form's area pickers) and by any unselected
  control fill — rendered as Material's lavender `#F3EDF7`, not Salah's card.
- `surfaceTint`, `inverseSurface`/`inverseOnSurface`/`inversePrimary`, `surfaceDim`, `surfaceBright`,
  `scrim`, `error`/`errorContainer`/`onErrorContainer` were equally unstated.

**Repair.** Both schemes now declare the full role set. The surface tiers are Salah's own paper tones
(light descends from white to `#ECE9E1`; dark ascends from `#0A110E` to `#313E39`), `tertiary` is
Salah's terracotta, and the error roles use the contrast-tested Material values. `SalahColorSchemeTest`
asserts each stated role differs from the baseline scheme's value, so a future Material upgrade cannot
silently reintroduce the lavender and the plum, and that the tiers are ordered and step away from the card.

## 4. Two line roles, not one (root cause 2)

`outline` (the boundary of a control) and `outlineVariant` (a decorative rule) were **the same cream
hairline** (`#E2E0D8` light, `#23322C` dark). Anything that outlines itself with `outline` therefore drew
a boundary at about **1.2:1** — the Calendar's Bangla/Gregorian/Hijri view chips and the location form's
unfocused text fields were the visible cases.

**Repair.** `outline` is now `#7E8E86` (light, 3.4:1 on the card and 3.1:1 on the canvas) and `#6E8078`
(dark, 3.9:1 and 4.4:1), clearing WCAG's 3:1 for the parts of a control that identify it.
`outlineVariant` keeps the soft hairline for dividers, where a low-contrast rule is the intent. The test
asserts they differ, that the boundary clears 3:1, and that the rule stays quieter than the boundary.

## 5. The Home screen's hardcoded light palette (root cause 3)

`feature/home/components/PremiumHomeSections.kt` predated the shared theme and named the light palette
directly. On the dark canvas that produced the reported symptoms:

| Was | Effect in dark theme | Now |
|---|---|---|
| `ForestGreenPrimary` as glyph tint and ring | ~**1.7:1** — the bell, profile, location and calendar buttons were effectively invisible | `colorScheme.primary` (deep green in light, the scheme's mint in dark) |
| `GoldText` for the tagline | ~**3.3:1** at 12sp | `accents.goldText` (the theme's own gold) |
| `Color(0xFFD3E8DC)` chip fill with `onBackground` labels | Light-mint fill with light-grey text — the selected prayer's name and time **disappeared** | `primaryContainer` + `onPrimaryContainer`; unselected chips keep `outlineVariant` borders (identical to the old light values) |
| `Color.White` card containers (`ForbiddenTimesCard`, `SunTimesCard`) + `#E8E6DE` border | **White cards on a dark page** | `colorScheme.surface` + `outlineVariant` |
| `Color(0xFFD4E8DC)` tool tiles, `Color.White.copy(0.5f)` disc | Bright mint tiles, and a white disc | `primaryContainer`, disc = `surface` at 0.6 alpha, glyph/label `onPrimaryContainer` |
| `ForestGreenPrimary` "View all"/"View details" links, `GoldAccent` ☼ | Invisible link text; a 2.5:1 gold glyph | `colorScheme.primary`, `accents.goldText` |
| `Color.White` hero and bottom-bar content | Correct—but scattered | `accents.brandSurface` / `onBrandSurface`, the one place the pairing is declared |

The light theme is unchanged by these edits wherever the old literal happened to equal the scheme role
(the chip fill, the chip border, the card fill, the icon green, the link green are byte-identical).

## 6. Branding and gold on the location setup screen

`LocationSetupScreen` drew the wordmark, the tagline and the map's ornaments in the fixed mid gold. On the
cream canvas that is ~**2.4:1** — under the floor even for the 28sp wordmark. They now use
`accents.goldText`, which is the readable gold of the active theme.

## 7. Every screen and component category, audited

| Category | Where | Result |
|---|---|---|
| Startup / window / splash | `values/`, `values-night/`, `values-v31/`, `values-night-v31/themes.xml`, `colors.xml` | Theme-aware in both (splash on API 31+ has a `night` twin so the night qualifier cannot shadow it). The splash icon backdrop is the fixed brand green — intentional |
| Home | `HomeScreen`, `PremiumHomeSections`, `HomeSkeleton` | Repaired in §5; the skeleton already used `surface`/`surfaceVariant`/`secondary` |
| Prayer / timetable | `PrayerScreen`, `TimetableCard`, `CurrentPrayerHeroCard` | Scheme roles; hero content via the brand accent |
| Calendar | `CalendarScreen`, `CalendarSettingsScreen` | Scheme roles + the new `tertiary`; chips inherit the fixed `outline` |
| Ramadan | `RamadanScreen` | Scheme roles + `LocalSalahAccents` |
| Qibla | `QiblaScreen`, `QiblaCompassDial` | Dial reads `surface`, `secondary`, `primary`, `onSurfaceVariant`; the Kaaba marker is punched out of the band in the face colour, which is what keeps it visible on the dark dial |
| Location | `LocationScreen`, `LocationSetupScreen` | `OutlinedTextField` (§4), `DropdownMenu` (§3), `RadioButton` rows, map art gold (§6) |
| Settings | `SettingsScreen` + subpages, `SettingsComponents` | Scheme roles; the Appearance radio group is the same pattern as every other option group |
| Notifications | `PrayerNotificationSettingsScreen`, `SalahSwitch` | Scheme roles; the switch palette is declared in `salahSwitchPalette`, which fixes its own contrast defect (see that function's documentation) |
| Tools, empty/error/loading, planned-feature | `ToolsScreen`, `HomeSkeleton`, `FailureBanner`, `SalahUnavailableNote`, `PlannedFeatureScreen` | Scheme roles (`errorContainer`/`onErrorContainer`, `surfaceVariant`, `secondaryContainer`) |
| Navigation | `SalahApp` (bottom bar), `docs/BOTTOM_NAVIGATION.md` | Repaired in §5 |
| Dialogs / sheets / menus / inputs | none of `AlertDialog`/`ModalBottomSheet`/`Snackbar`/`TopAppBar` exist in the app; `DropdownMenu` + `OutlinedTextField` + `FilterChip` + `RadioButton` + `Checkbox`-free `SalahSwitch` do | All follow the active theme |

`SalahGlyphs.kt` and `PrayerGlyphs.kt` take a tint parameter and carry no colour of their own; the vector
drawables are the launcher/splash artwork and the status-bar icon (which must be a single white shape).

## 8. Tests

- `SalahColorSchemeTest` — mode mapping, role coverage against Material's baseline, tier ordering, both
  schemes' text contrast (≥4.5:1) and control-boundary contrast (≥3:1), the two line roles, and every
  accent pairing.
- `SalahSwitchPaletteTest` — the switch's own contrast pairs in both themes.

Both are pure JVM tests (WCAG ratios computed from the colour components), so they run in the same
`testDebugUnitTest` task as the rest of the suite. There is no screenshot/emulator test in this project;
see §9.

## 9. Known limitations

1. **Startup window flash for an explicit override.** The window background is resolved by the platform
   from `themes.xml`/`values-night` before any app code runs, i.e. from the *system* setting; the stored
   preference is read asynchronously from DataStore. A user who chose Dark on a light device therefore
   sees the platform-matching (light) window background for the first frames. The alternatives are worse
   than the flash: forcing the platform night mode (`UiModeManager.setApplicationNightMode`) recreates
   the activity on every change and would introduce a second theme state that can disagree with the
   stored preference, and a synchronous second store is exactly what the preference architecture avoids.
2. **`secondary` on the light card is ~2.6:1.** It is the brand's mid gold and is used for the Qibla
   dial's band and the Calendar's Ramadan marker and legend. Re-pitching it would repaint the light
   design's gold; the gold that carries *text* uses `accents.goldText` and is asserted at 4.5:1.
3. **No visual verification.** This environment has no JDK, no emulator and no screenshot testing, so the
   audit is source-level plus the automated colour maths above; the visual checklist (Home, prayer cards,
   settings, dialogs, inputs, navigation) has not been inspected on a device. GitHub Actions is the
   authoritative build.
