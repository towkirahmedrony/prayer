# Bottom navigation: Home, Calendar, Tools, Settings

This document records what Salah's fixed bottom bar contains, where the screens that left it are still
reached from, and what the bar's navigation options do. It is the counterpart to
`docs/SETTINGS_STRUCTURE.md`, which describes the Settings overview the fourth tab opens.

## 1. The four destinations

The bar renders `SalahDestination.bottomBarDestinations` — the destination enum's own members in
declaration order — and opens `dest.route` for the item tapped:

| Position | Route | Screen | What it is |
| --- | --- | --- | --- |
| 1 | `home` | `HomeScreen` | Today's dates, the current prayer period, the timetable and the prohibited-time cards, plus the location label and the daily tools |
| 2 | `calendar` | `CalendarScreen` | Gregorian, Bangla and Hijri dates, the month grid and the observances; opens `calendar_settings` |
| 3 | `tools` | `ToolsScreen` | The landing screen for the utilities that are not places the user dwells — see section 3 |
| 4 | `settings` | `SettingsScreen` | The category overview; each row opens its own screen (see `docs/SETTINGS_STRUCTURE.md`) |

Labels come from `SalahDestination.labelRes`, so the bar and each destination's own heading are the same
string (`nav_home`, `nav_calendar`, `nav_tools`, `nav_settings`). Icons are Salah's own canvas glyphs,
resolved in `SalahApp`'s `when` over the destination: dome for Home, calendar for Calendar, tasbih for
Tools, gear for Settings. The selected item is the one whose route equals the current back-stack entry's
route, with the same white tint and white-alpha indicator the bar already used.

**The highlight is an exact-route match.** A screen that is not a top-level destination — `prayer`,
`qibla`, `ramadan`, `calendar_settings`, any Settings category — therefore leaves *no* tab selected
rather than lighting an unrelated one. That was true before this change and is unchanged by it.

## 2. What changed

- **`prayer` left the bar and `tools` took its place.** Nothing else about the Prayer screen moved: the
  same route, screen, view model, calculation engine, repositories and saved preferences; the change is
  `inBottomBar = false` on one enum member and `true` on another.
- **The bar no longer hardcodes its list.** `SalahApp` previously held its own `listOf(HOME, PRAYER,
  CALENDAR, SETTINGS)`; it now renders `bottomBarDestinations`, so a tab cannot be shown for a
  destination the graph does not declare and the order lives in one place.
- **The Tools screen was rebuilt** from two shortcut tiles into rows driven by `ToolsEntry` (section 3).
  The tiles' routes were string literals inside the feature package; each row now holds a
  `SalahDestination` member, so no route is spelled outside the destination enum.
- **Home's daily-tools row was corrected in one word.** Its last tile was labelled "Tasbih" while it
  navigated to the `tools` route — a name for a feature Salah does not have, on a control that opened
  something else. It now reads "Tools", which is the destination it opens and the tab it matches; the
  glyph, the destination and the rest of the row are unchanged.
- **`Tools` gained a subtitle and an honesty note**, because a tab is the first place a user looks for a
  feature that is not there (section 3).

## 3. The Tools screen

`ToolsScreen` renders `ToolsEntry.entries` — one card per entry, each with a mark, a title, a one-line
description and a chevron — and opens `entry.destination`. The screen reads no repository, calculates
nothing and holds no state, so it has no loading, empty or failure case and cannot disagree with the
screen it opens.

| Row | Route | Screen | Notes |
| --- | --- | --- | --- |
| Prayer times | `prayer` | `PrayerScreen` | The live prayer period and the whole day's timetable, with the per-prayer corrections disclosed. Keeps the lantern mark the Prayer tab used |
| Qibla | `qibla` | `QiblaScreen` | Bearing to the Kaaba, the sensor in use and its limits |
| Ramadan | `ramadan` | `RamadanScreen` | The estimated Ramadan window and the daily Sehri and Iftar table |

Every row opens a screen that already existed. The rows are the Settings overview's own pattern
(`SalahSectionCard` with an icon chip, two lines of text and a chevron) so the two lists read as one
system, and they are a single column rather than a grid so a description is readable without wrapping
into a tile's width.

**Deliberately not listed.** A tool row is a navigation control, so a row without somewhere to go would
be a button that does nothing:

- **Quran, Dua and Tasbih** are not implemented in Salah. The screen says so in one sentence
  (`tools_unavailable_note`) instead of leaving a user to conclude they are hidden elsewhere.
- **Location and prayer notifications** are Settings categories. Listing them again would open the same
  destination from two top-level tabs, which is the duplication this structure exists to avoid.
- **Prohibited prayer times and today's prayer strip** are content on the Home screen, not screens; the
  Prayer times row is the destination that shows the timetable in full.
- **Calendar year navigation, the Hijri offset and the date language** are the Calendar tab and its
  settings — already one tap away.

## 4. Where the Prayer screen is reached from

Two entry points, both on route `prayer`:

1. **Tools → Prayer times** (`ToolsEntry.PRAYER_TIMES`).
2. **Home → "View all" and "View details"** on the prayer content, unchanged.

Nothing else navigated to `prayer` before this change — there is no deep link to it (section 6) — so
removing the tab removed a shortcut, not an entry point. Home's two links go through `navigateTo`,
which pops back to Home, keeps a single instance and restores that destination's state, exactly as they
did when `prayer` was also a tab.

## 5. Back stack and repeat taps

| From | Options | Result |
| --- | --- | --- |
| A bottom-bar item | `popUpTo(startDestination) { saveState = true }`, `launchSingleTop = true`, `restoreState = true` | Switching tabs keeps each tab's state; back from any tab returns to Home; a repeated tap on the current tab does not stack a second copy |
| A Tools row | `navigate` with `launchSingleTop = true` only | Back returns to the Tools list the row was tapped from; two taps on the same row do not stack two copies |
| A Settings category, Calendar settings, Ramadan, the notification screens | `navigate` + `popBackStack` (unchanged) | Back returns to whichever screen opened it |

System back therefore returns to the previous screen wherever there is one, and leaves the app only from
Home, which is the graph's start destination — the behaviour the bar already had.

A tool screen is recreated each time it is opened from Tools (the row does not save or restore state),
so it re-reads its data: the timetable comes from the same repository and the same request cache, and
the Qibla screen re-registers its sensors. Tool screens that were already opened from Home behaved this
way; `prayer` now does too when it is opened from Tools.

## 6. Notification navigation and deep links

Neither is affected by the bar's contents, and both were checked rather than assumed:

- `AndroidPrayerNotificationPresenter.openAppIntent()` builds `Intent(context, MainActivity::class.java)`
  with no route extras, so tapping a reminder opens the app at its own start destination. There is no
  per-reminder target screen to break.
- `AndroidManifest.xml` declares no `<intent-filter>` with a scheme, host or path, and `SalahNavHost`
  declares no `deepLinks` — Salah has no deep links. If a route link is added later it should point at a
  `SalahDestination` route, which is the only list of routes the app has.

## 7. Tests

`SalahBottomNavigationTest` asserts the declarations the bar and the Tools list are drawn from:

- the bar is exactly Home, Calendar, Tools and Settings, in that order;
- `prayer` is no longer a bar item, still has route `prayer`, and still resolves back from it;
- `tools` is a bar item with its own declared route;
- every destination has a unique route and `fromRoute` round-trips for all of them (a duplicated route
  would silently shadow a member, because `fromRoute` returns the first match);
- every Tools row opens a declared destination of its own, not the Tools screen itself and not a
  bottom-bar tab;
- every Tools row has its own title and description;
- Home, Calendar and Settings are still bar items and Prayer, Qibla and Ramadan are all reachable from
  Tools.

What it cannot see: Compose rendering (the project has no Compose UI-test dependency), so the pixels,
the glyphs and the selected tint rest on source inspection plus a clean compile — a destination without a
glyph is a `when` exhaustiveness error rather than a blank square. Device behaviour (alarms, sensors,
real GNSS) remains unverified and is listed in the existing device checklists.

## 8. Known limitations

- **No tab is highlighted on a subpage or a tool.** The selection rule is an exact route match; making a
  tool light up its parent tab would mean maintaining a second parent map, and lighting a different tab
  would be wrong. Unchanged from before this change.
- **Tools is three rows** because three standalone features are implemented. It will stay short until
  more exist, and it grows by adding a `ToolsEntry` plus a destination.
- **A tool screen does not keep state across visits** (section 5). The alternative — saving the state of
  a screen the user left — would also restore a stale compass reading or a stale timetable, so the
  screens that need freshness are recreated on purpose.
- **The bar is a fixed four-item bar**, not a `NavigationSuiteScaffold` or a rail: Salah has one window
  size story (phone portrait) and adding a second navigation style is a design change this task did not
  include.
