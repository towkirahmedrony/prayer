# Salah — Location System

This document describes the location foundation **as implemented in this repository**: how a user's
location is obtained, what Salah is willing to say about it, where the bundled geography comes from,
and what has and has not been verified. Where a fact is *measured* from the shipped files, the number
is given and the test that checks it is named. Where something is *documented provenance* rather than
independently confirmed accuracy, that is said explicitly.

Related documents: [`ARCHITECTURE.md`](ARCHITECTURE.md) (layers and ports),
[`PRAYER_CALCULATION.md`](PRAYER_CALCULATION.md) (how the coordinates become times),
[`QIBLA_COMPASS.md`](QIBLA_COMPASS.md) (how they become a bearing).

---

## 1. What this is

Salah ties prayer times, the Qibla bearing, the Ramadan timetable and prayer reminders to **one stored
location**. That location can be obtained two ways:

- a **device fix** from the platform's location provider, requested on demand; or
- a **manual choice** of a Bangladeshi administrative area (division → district → upazila/thana), or
  a hand-typed coordinate and IANA zone.

Both are stored locally and both work offline. The bundled geography is what makes the manual picker
possible and what lets a device fix be attributed to a named area without a network call.

The domain code lives in `core/domain/location` and is Android-free; the Android adapters live in
`data/location` and `data/qibla`. See [`ARCHITECTURE.md`](ARCHITECTURE.md) §3.

## 2. First-launch setup flow

There is **no `setup_complete` flag**. The app derives whether setup is needed from the location
itself (`LocationSetup.evaluate`), so a usable location and a completed setup can never disagree:

- `Checking` — the bundled dataset and the stored location are still being read.
- `Complete(location)` — a usable location exists.
- `Required(reason, stored)` — a location must be chosen. `reason` is one of:
  - `NO_LOCATION` (nothing stored),
  - `OUTSIDE_SUPPORTED_COUNTRY` (stored, but not usable under the Bangladesh-only policy),
  - `AREA_NOT_IN_DATASET` (stored, in Bangladesh, but its area is unknown to the current dataset).
- `DatasetUnavailable(detail)` — the bundled dataset could not be read and no location is stored, so
  neither way of choosing a location can be completed.

The setup screen (`LocationSetupScreen`) offers exactly the two ways forward. A device that already had
a location from an earlier build is not sent through setup again; a user who declined GPS and chose an
area manually is complete, because **no permission state is part of the evaluation**; and a location
cleared in Android settings or by the user returns the app to setup, because the repository then emits
`null`.

`AREA_NOT_IN_DATASET` is reported rather than ignored: displaying an area name the current dataset can
no longer verify, and offering it as if it were still selectable, would be a quiet lie. The location
screen (`LocationScreen`) manages an existing location with the same rules.

**Where the screen is shown.** The shell (`SalahApp`) renders one of exactly three things, and the
stored location decides which: the navigation graph when a location is usable, `LocationSetupScreen`
when `requiresSetup`, and Home's own skeleton while the answer is still `Checking`. While setup is
required there is no navigation graph and no bottom bar — nowhere to go that would not have to invent
a time — and no code marks setup as finished, because **saving a location is what closes the gate**.
`AppViewModel.locationSetup` is the only thing the shell consults, and it is derived (above), so the
answer survives process death and is re-derived identically on the next launch.

**What the screen is.** One scrolling column: the app's mark and name, the heading, what a location
is for, an illustration of Bangladesh, then the device option and the manual option separated by
`OR SELECT MANUALLY`, and a closing line. Like the rest of the app it is drawn from the theme's
colours and type scale, so it follows light and dark mode. The illustration is generated from the
**same** `bd_boundary.tsv` the country policy validates against — projected, simplified to a couple of
hundred points and drawn as a path — so the shape shown is the country the app already knows rather
than a second drawing of it.

## 3. GPS and manual selection

**Device fix.** `LocationProvider` is a one-shot port. The Android implementation
(`AndroidLocationProvider`) uses `LocationManagerCompat.getCurrentLocation` — the framework provider,
not Google Play Services — with a bounded wait, and registers no listener after returning. A fix is
always checked against the country policy and attributed to an area where possible, and **when it is
written depends on which screen asked for it**:

- **First launch** stores it as soon as it passes the policy. Granting the permission and getting a
  fix is the whole interaction, and the app opens on it: a fresh install has no location to compare
  against, and a confirmation step there stands between the user and the app for a decision that is as
  reversible in Settings as any other. The screen shows the progress indicator until the write has
  landed, so a second tap cannot start a second request.
- **The location screen**, reached later from Settings, holds it as a *pending* fix. That screen is
  reviewing a location the user already has, and the area name is a nearest-match estimate worth a
  look before it replaces something they chose. Nothing replaces the stored location until the user
  taps **Use this location**.

Either way a position outside Bangladesh is refused and nothing is written: saving without asking is
not saving without checking. `useCurrentLocation(saveOnArrival = …)` is the whole of the difference,
and the validation, the messages and the manual option are identical on both paths.

**Manual selection.** Three levels, in order: the divisions, then the districts of the chosen division,
then the upazilas/thanas of the chosen district. `AreaSelection` is the guard: choosing a level clears
the levels below it, and an id that does not belong under the chosen parent is ignored rather than
stored, so a half-valid hierarchy cannot exist. A complete selection saves the **upazila's published
centre** and the division/district/upazila names.

The two screens present the same three levels differently — the setup screen as three dropdowns
(division, district, upazila/thana), the management screen as an expandable list per level — because
they answer different questions: "pick an area" against "check and change the area you have". Both
read `LocationUiState` and call the same `selectDivision` / `selectDistrict` / `selectUpazila` /
`saveSelectedArea` actions, so the rules above are enforced once, in the view model and in
`AreaSelection` — never in the layout. A level is offered only once its parent is chosen, and one
whose parent has not been chosen is disabled rather than shown empty.

**Manual coordinates.** The Settings screen and the Qibla screen accept typed coordinates and an IANA
zone. These are stored as entered; the country check is not applied to a typed coordinate (the Settings
text says so), because a user typing a position is asserting a place, and rejecting it silently would be
worse than storing what they asked for.

## 4. Permission behaviour

Salah reads the location permission state; it never prompts outside a user action.

- `LocationPermissionChecker.state()` reports `PRECISE` (fine), `COARSE` (approximate only) or
  `DENIED`. Both `ACCESS_COARSE_LOCATION` and `ACCESS_FINE_LOCATION` are declared and either alone is
  enough.
- The decision to offer the runtime dialog, or to send the user to the app's settings, is
  `LocationPermissionPolicy.action` (pure and unit-tested). Android reports
  `shouldShowRequestPermissionRationale` as `false` both *before* the first request and *after* the
  user has chosen "don't ask again", so the policy pairs that flag with whether Salah has already shown
  the dialog: a first request is a `REQUEST`, an ordinary denial is still a `REQUEST`, and a denial the
  system will no longer re-prompt for becomes `OPEN_APP_SETTINGS` — avoiding a button that would
  silently do nothing.
- **Manual selection needs no permission and no network** and stays usable whatever the permission
  answer. There is **no** background-location permission, and no `INTERNET` permission at all.

## 5. The bundled dataset: sources, version and licences

The two files the app ships are **generated, not hand-written**, and the generator is committed so the
derivation is auditable and repeatable:

- `app/src/main/assets/location/bd_areas.tsv` — the hierarchy, names and coordinates.
- `app/src/main/assets/location/bd_boundary.tsv` — the simplified national boundary.
- `app/src/main/assets/location/bd_localities.tsv` — the named urban localities and the district each
  one was verified to lie in (§8).
- `tools/generate_bd_location_dataset.py` — the generator for the hierarchy and the boundary.
- `tools/generate_bd_locality_dataset.py` — the generator for the locality layer.

**Sources.**

1. **Bangladesh Common Operational Dataset on Administrative Boundaries (COD-AB)**, published by
   OCHA / HDX as `cod-ab-bgd`. Provides the administrative hierarchy (p-codes), the English names, the
   geometry, and a published `center_lat` / `center_lon` per unit. Used for the hierarchy, the English
   names, the coordinates and the national boundary. Licence: **Creative Commons Attribution for
   Intergovernmental Organisations (CC BY 3.0 IGO)**, http://creativecommons.org/licenses/by/3.0/igo/.
2. **`nuhil/bangladesh-geocode`** (MIT). Used **only** for the Bangla name of a unit, joined by name.
   Licence: MIT, https://github.com/nuhil/bangladesh-geocode.
3. **OpenStreetMap**, queried through the Overpass API. Used **only** for the locality layer: the name
   (English, and the matching Bangla name is in the source but not shipped), its kind and its point.
   Licence: **Open Database License (ODbL) 1.0**, https://www.openstreetmap.org/copyright. The generated
   file is a Derivative Database of OpenStreetMap data and is offered under the ODbL; the asset's own
   header states that, the `(c) OpenStreetMap contributors` attribution it requires, and the timestamp
   of the data it was extracted from.

**Version.** The dataset identifies itself as `BGD-COD-AB v03 (2023-05-21)`, the value of
`AssetBangladeshAreaCatalog.VERSION` shown in the UI and stored with a selected area. The generator
labels the same release `COD_VERSION_NOTE = "COD-AB BGD v03 (valid_on 2023-05-21)"`; the two strings
are not byte-identical (one is written for display, the other for the file header), but both name the
same source release, COD-AB v03 with a validity date of 2023-05-21. The version is a constant, not read
from the file, so it identifies the build's dataset rather than whatever a particular copy of the file
happens to contain.

**Format.** Tab-separated text with `#` comments, seven columns per data row
(`level, id, parent_id, name, bn_name, latitude, longitude`) and three for the boundary
(`ring_index, latitude, longitude`). The locality file follows the same shape with five columns
(`kind, district_id, name, latitude, longitude`). The parser is `BangladeshLocationDataset` (pure
Kotlin, no JSON or CSV dependency).

**Size and coverage (measured from the shipped files).**

| Quantity | Value |
|---|---|
| Divisions | 8 |
| Districts | 64 |
| Upazilas / thanas | 507 |
| Upazilas with no Bangla name (English name shown instead) | 69 |
| Boundary rings | 13 |
| Boundary points | 1 676 |
| Named localities | 1 216 |
| Districts carrying a locality layer | 33 of 64 |
| `bd_areas.tsv` / `bd_boundary.tsv` / `bd_localities.tsv` size | ≈ 45 KB / ≈ 37 KB / ≈ 59 KB |

**Bangla names.** The Bangla join is best-effort and never guessed: an exact match first, then a
similarity match **within the same district** at a high threshold, and otherwise **no Bangla name at
all** (the English name is shown). 69 of the 507 upazilas keep `null` rather than a transliteration
Salah invented.

**What has and has not been verified.** The generator performs a data-quality check against the
source's *own* geometry (every published centre must fall inside its own boundary) and reports
failures rather than dropping them. That is a check of the source against itself. Salah has **not**
compared these coordinates against any independent dataset, and makes no claim that they are
externally verified beyond what OCHA publishes. The locality generator performs a stronger check of a
different kind: it joins every locality to the district whose **polygon contains it**, in the source's
geometry, and drops (with a printed count) any locality that falls in no district of that dataset —
none did; 141 rows without an English name, one unusable name and 55 duplicates were dropped, and
those counts are recorded in the asset's header, so the loss from the extract to the shipped file is
visible in the repository rather than recovered from memory. See §6 and §12.

## 6. Representative coordinates vs precise GPS coordinates

Every area's stored coordinate is the **published representative centre** for that unit — the point
OCHA publishes with the boundary it defines — **not** the user's position, and not necessarily the
administrative office or a population-weighted centre. Selecting an area therefore stores a real,
creditable coordinate, but the resulting prayer times are the times for that point, which may differ
from the user's own position by minutes. The UI labels a selected area as a selected area
(`LocationOrigin.AREA_SELECTION`), distinct from a hand-typed coordinate (`MANUAL_ENTRY`) and a device
fix (`DEVICE_GPS`), and the location screen states the limitation in the `location_area_reference_note`
string.

A device fix, by contrast, is the position the platform reported, stored with its reported accuracy and
its acquisition time. The two are never conflated: `LocationOrigin` records which is which, and
`LocationFreshness` reports how old a device fix is without ever presenting a saved fix as a live one.

## 7. The Bangladesh-only policy and its boundary tolerance

Salah's calculation settings, its Hijri and Bangla calendars, and its bundled dataset are all
Bangladesh-specific, so a location outside Bangladesh is refused — explicitly, not by quietly
converting it into a Bangladesh one. The whole rule lives in `BangladeshLocationPolicy`.

`check(point, boundaryRings)` returns one of four outcomes:

- **`Inside`** — strictly inside the boundary polygon. The *only* outcome reported as confirmed
  containment.
- **`WithinBoundaryTolerance(distance)`** — outside the polygon but within
  `BOUNDARY_TOLERANCE_METRES` (**5 000 m**) of it. Accepted so a border-adjacent fix is not thrown
  away, but never reported as containment.
- **`Outside(distance)`** — beyond the tolerance.
- **`BoundaryUnavailable`** — the boundary could not be read.

The tolerance exists for two errors: the boundary is simplified with Ramer–Douglas–Peucker at 0.005°
(≈ 550 m), and a GPS fix is uncertain by tens of metres. **The tolerance is not a claim of
containment, and it cannot be, because it is symmetric**: it also covers a few kilometres of a
neighbouring country. This is not hypothetical — measured against the bundled boundary, the Indian city
of **Agartala** is about **3.4 km** from the simplified line and therefore inside the tolerance. That is
precisely why `WithinBoundaryTolerance` is a distinct outcome from `Inside`, and why the UI has separate
wording for the two:

- a strictly-inside fix is reported as "Confirmed inside Bangladesh using the bundled national
  boundary";
- a tolerance-accepted fix is reported as "Within the boundary tolerance … accepted for GPS
  uncertainty rather than confirmed inside the country".

`BOUNDARY_TOLERANCE_METRES` is preserved (not tightened away) because removing it would reject genuine
border villages and would not resolve the ambiguity — only move it — while making the app refuse a
correct position.

**Where the tolerance is accepted, and where it is not.** `LocationSetup` accepts both `Inside` and
`WithinBoundaryTolerance` (keeping a border-adjacent user working), and the location screen shows a
pending fix for either. The Qibla write path (§10) is stricter and adopts only a strictly-inside,
area-attributable fix, because it has no confirmation step.

## 8. Administrative hierarchy and area resolution

Salah models three levels: **division** (8), **district** (64) and **upazila/thana** (507). The union
level exists in Bangladesh's administration but is not modelled — it has no published representative
coordinate in the adopted dataset, so offering it would mean inventing coordinates.

`AreaResolver.resolve(point, dataset)` attributes a coordinate **offline**, by nearest published centre
rather than by point-in-polygon (Salah bundles representative points, not the tens-of-megabytes of
sub-national boundaries):

- an **upazila** is named when its centre is within `UPAZILA_MATCH_METRES` (20 km);
- otherwise the **district** is named when its centre is within `DISTRICT_MATCH_METRES` (75 km);
- otherwise the fix is **`Unresolved`** and Salah says it cannot tell which area the position is in,
  reporting how far the nearest centre was, rather than confidently naming a distant district.

The matched level travels with the result (`AreaSuggestion.matchedLevel`), so a district-level match is
never shown as an upazila-level one.

This is **not** reverse geocoding: no address is produced, no street is named, and nothing is sent
anywhere. The area name is presented as approximate, and the user can always select the area manually.

### 8.1 The locality layer: why it exists

Inside the twelve **city corporations** the administrative dataset has no unit smaller than the
corporation: COD-AB's third level is 495 upazilas **plus 12 city corporations**, so a fix anywhere in
Dhaka resolves, at its deepest, to *Dhaka North* or *Dhaka South City Corporation* — correct, and no
more useful than saying which half of the city the phone was in. That is what made a Salah location
read as a generic name.

Salah therefore ships a second, finer layer — **named localities**: `place=suburb`, `place=quarter` and
`place=neighbourhood` names from OpenStreetMap, each joined to the district that **contains** its point
(point-in-polygon against the COD-AB ADM2 geometry, not proximity to a district centre). It is 1 216
rows covering 33 districts — Dhaka 730, Cumilla 108, Khulna 82, Narayanganj 60 — and it is optional by
design: a missing or empty layer changes a name, never whether a location can be chosen or stored.

`LocalityResolver.resolve(point, accuracyMetres, districtId, dataset)` decides whether a position may
be named after one:

1. **The district gate.** Only localities whose *verified* district equals the district the
   administrative resolver attributed the position to are candidates. A place one side of a border is
   therefore never offered for a position on the other side, whatever its distance.
2. **The precision gate.** The reported accuracy must be known and positive, and
   `distance + accuracy <= MATCH_METRES` (**2 000 m**) — the whole accuracy circle has to fit inside
   the match radius. An approximate permission, or a fix with no reported accuracy, produces
   `NotPreciseEnough` and the name is withheld rather than guessed. This is how an approximate
   permission is represented honestly: it is not refused, it is answered with a coarser, still true,
   name.
3. Otherwise the nearest locality in that district inside the radius is matched, with its distance
   carried on the result; beyond it, `Unmatched` with the distance to the nearest one, or `null` when
   that district has no locality rows at all.

Matching is nearest-point, **not** containment: the layer publishes points, not boundaries. What it
claims is therefore "the nearest named place, within two kilometres, in the district this position was
verified to be in, and precise enough to be standing there" — and the matched name is recorded beside
the area, never instead of it. A locality is never presented as an administrative thana or ward; the
district is what anchors it administratively.

### 8.2 How a location's display name is decided

`LocationDisplayName` is the single function that turns a stored location into the name every screen
shows (`HomeUiStateFactory` for Home and Prayer, the Qibla location line, and both save paths):

1. a **manually typed** name is returned unchanged — it is the user's own words and outranks any fact
   the app holds;
2. otherwise the deepest *verified* fact is used — the locality when there is one, else the
   upazila/thana, else the district — qualified by the district name;
3. the district qualifier is **dropped when the deeper name already contains it**, which is the rule
   that turns the twelve `"X City Corporation, X"` labels into one clean name instead of a repeated one;
4. with no facts at all, the stored label is returned unchanged.

It is composed from the record's *parts*, not from the stored label, so a location written by an earlier
build is shown under the current rule with no migration, and the label written at save time is produced
by the same function. The possible results, in the order the policy can produce them:

| What the record holds | Shown |
|---|---|
| locality verified in the resolved district | `Mirpur, Dhaka` |
| upazila/thana inside the district | `Savar, Dhaka` |
| city corporation named after its district | `Dhaka South City Corporation` |
| district only | `Dhaka` |
| locality with no resolved district | `Mirpur` |
| typed coordinates | as typed |
| nothing usable | the stored label, unchanged |

There is no case in which a division alone reaches this function: `BangladeshAreaRef` always carries a
district, because a selection that stops at a division cannot be saved.

## 9. Offline operation and persistence

Nothing in the location system needs a network, and Salah declares no `INTERNET` permission.

- **Stored location:** `DataStoreLocationRepository` keeps the location in the same DataStore as the
  other preferences. The stored value carries its origin, accuracy, acquisition time, country code, the
  area hierarchy (ids and names), the matched locality (name, verified district id and kind) and the
  dataset version. A location written by an earlier build simply lacks the newer keys and reads as a
  manual entry with unknown freshness, with no locality — an existing location is preserved, not
  migrated destructively. Both the hierarchy and the locality are written and removed **as a set**, so a
  name can never be left attached to coordinates it was not matched against.
- **Geography:** `AssetBangladeshAreaCatalog` reads the assets once per process (≈ 142 KB total, a few
  milliseconds to parse) and caches the parsed dataset. The locality file is read *optionally*: missing
  it is logged as a warning and yields an empty layer, because the two files the app cannot work
  without are the hierarchy and the boundary.
- **Failure is a value.** A missing or unreadable asset returns `null` and is logged; a dataset that
  parses into something unusable is returned as it is, and `LocationSetup` decides what to tell the
  user. Nothing throws into the UI, and nothing falls back to a hand-written list of "popular" places.
  An unreadable dataset does not lock out a user whose coordinates are already valid.

## 10. Integration with the rest of the app

- **Prayer times** are computed from the stored coordinates and the location's zone
  (`LocationInfo.zoneId`). A change of location triggers a notification reconciliation so no reminder
  announces the previous place's times. See [`PRAYER_CALCULATION.md`](PRAYER_CALCULATION.md).
- **Qibla** is a closed-form bearing from the stored coordinates. The Qibla screen's "use current
  location" goes through **`DeviceFixAdoptionPolicy`**: it adopts the fix only when it is strictly
  inside the bundled boundary **and** attributable to an area; otherwise the stored location is kept and
  the screen explains why. A device fix adopted there is stored in `Asia/Dhaka` (Bangladesh is a single
  zone), the same as the location screen, and never as a silent Dhaka substitution.
- **Ramadan** and the **Sehri/Iftar reminders** read the same calculated schedule for the stored
  location. See [`CALENDAR_SYSTEM.md`](CALENDAR_SYSTEM.md).
- **Notifications** are scheduled and recovered from the same location, with the reconciliation path
  documented in [`PRAYER_NOTIFICATIONS.md`](PRAYER_NOTIFICATIONS.md).

## 11. Tests

Run with `./gradlew testDebugUnitTest` (the same command CI runs).

- `BangladeshLocationDatasetTest` — the parser: accepted and refused rows, blank Bangla names,
  duplicate ids, boundary ring grouping, a ring too small to enclose being dropped, malformed
  boundary lines being recorded, and the locality layer's own rules — a row naming a district this
  build does not define, an unknown kind, a blank name or a bad coordinate are all refused, and a row
  repeated for the same district is collapsed rather than kept twice.
- `LocalityResolverTest` — the locality rules on synthetic geometry and on the shipped file: a
  precise fix matches its locality, the nearest in the district wins, a locality in *another* district
  is never matched however close, an accuracy circle that does not fit the radius withholds the name
  while still reporting the distance, an unknown or non-positive accuracy is `NotPreciseEnough`, no
  district means no claim, a district without locality rows reports nothing to measure, the resolver is
  deterministic, and the bundled layer names Mirpur, Dhanmondi and Uttara for Dhaka and leaves a
  district whose localities are all far away with its administrative answer.
- `LocationDisplayNameTest` — the naming policy: the locality is paired with its district, the city
  corporation does not repeat its district, an upazila that does not contain the district name keeps
  both, a district-level match is the district, a typed name is never replaced, and a record with no
  facts keeps its stored label.
- `BundledDatasetIntegrityTest` — loads the **real** shipped assets and checks the measured coverage
  (8 / 64 / 507 / 1 216 localities), that **no** line is rejected, that the number of data lines equals
  the number of parsed areas and localities (so no row can silently disappear), that ids are unique and
  parents resolve at the correct level, that every coordinate is plausible, that every representative
  centre **and every locality point** is strictly inside the bundled boundary, that every locality names
  a district that exists, that no locality is duplicated, that Dhaka carries at least 500 localities and
  the neighbourhoods the feature exists for are in the shipped file, that the boundary parses into
  valid rings, and that the repaired Shahrasti row keeps its identity, parent and coordinates. This is
  the strict gate that keeps a malformed shipped file from reaching a release; runtime parsing stays
  lenient by design.
- `BangladeshLocationPolicyTest` — the boundary classification: clearly inside, clearly outside,
  just outside within the tolerance, beyond the tolerance, and unreadable boundary; against both a
  synthetic square and the real boundary, including a regression case for an Indian city inside the
  tolerance not being reported as inside Bangladesh.
- `AreaResolverTest`, `AreaSelectionTest`, `LocationSetupTest` — level selection, the hierarchy guards,
  attribution thresholds, and the setup gate for every reason.
- `AppViewModelTest` — the gate the shell acts on, driven through the real `AppViewModel`: a fresh
  install is asked for a location, a stored usable one goes straight to the app, an outside-Bangladesh
  or unknown-area location is asked for again with its reason, an unreadable dataset is reported and
  does not lock out an already-valid location, a stored location is decided without waiting for the
  dataset read, and the answer moves in **both** directions as the stored location is saved and
  cleared (the property that rules out a `setup_complete` flag).
- `LocationViewModelTest` — what a tap does, on virtual time with the real view model, resolvers and
  coordinator: a fix asked for by the location screen is held rather than saved on sight and only
  stored on confirmation, a fix asked for on first launch is stored on arrival, a fix outside
  Bangladesh is refused and stores nothing on **either** path, a declined permission and a timeout
  each leave their message and the manual option, a second tap during a fix starts one request, the
  cascade drops a child its new parent cannot keep, a confirmed selection stores the chosen
  area's published centre with `AREA_SELECTION` provenance while a partial one is refused, a precise
  fix is stored under the neighbourhood it matched with the hierarchy still intact beside it, and an
  approximate fix is stored by area only — never by a neighbourhood it is not accurate enough to name.
- `DeviceFixAdoptionPolicyTest` — the Qibla write path, including that an accepted fix carries the
  locality its accuracy supports and that the same position at an approximate accuracy is adopted
  without one.
- `DataStoreLocationRepositoryTest` — the locality round-trips, a write without one clears it, and a
  store from a build that predates the layer reads as no locality rather than as a bad one.
- `DeviceFixAdoptionPolicyTest` — the Qibla write-path decision (adopted vs preserved, and why).
- `LocationPermissionPolicyTest` — the permission affordance decision.
- `LocationFreshnessTest`, `GeoMathTest`, `LocationInfoTest`, `DataStoreLocationRepositoryTest` —
  freshness, geometry, the model's validation, and the real DataStore persistence.

## 12. Known limitations

- **The dataset's accuracy is documented provenance, not independent verification.** The source
  (OCHA/HDX COD-AB, CC BY 3.0 IGO) and the join are recorded and the files are tested for internal
  consistency; Salah has not compared the coordinates with any second dataset.
- **The boundary is simplified** (Ramer–Douglas–Peucker, 0.005°), and the country check carries a 5 km
  tolerance. The tolerance accepts both a border village and a few kilometres of a neighbouring country;
  Salah reports the two differently but cannot distinguish them from a bare coordinate.
- **Area resolution is nearest-centre, not point-in-polygon.** An area name can name a neighbouring
  unit when a fix sits near a shared boundary, and a fix far from every centre is left unresolved. The
  UI says the name is approximate.
- **The locality layer is OpenStreetMap data: urban, uneven, and informal.** Coverage is concentrated
  where mappers are — Dhaka carries 730 of the 1 216 rows and 31 of the 64 districts have none — so a
  position in a town without a layer keeps its administrative name, which is the intended fallback.
  The names are what people call those places; they are **not** an official thana, ward or union
  inventory, and Salah never presents one as such. Regenerating the layer re-extracts live OSM data, so
  the row count and the data timestamp in the asset header will change; the integrity test asserts the
  coverage the shipped file actually has and must be updated deliberately with it.
- **Locality matching is nearest-point within 2 km, not containment.** Two kilometres is chosen for the
  scale of the units, and the district gate plus the accuracy gate remove the obvious ways to be wrong,
  but a place just outside the radius is not named and a place just inside it is. The name is stored
  beside the position's own coordinates, never instead of them, so prayer times and the Qibla bearing
  remain computed from the fix itself.
- **A fix with no reported accuracy is never named after a neighbourhood.** `NotPreciseEnough` is the
  honest answer, and it costs the user a finer name rather than risking a false one; the administrative
  area is stored either way.
- **ODbL attribution is carried in the asset header**, which ships in the APK, and the derived file is
  offered under the ODbL. If the app's own distribution terms ever change, the locality layer is the
  one dependency of that change — it is separable from the CC BY 3.0 IGO and MIT data the rest of the
  location system is built on.
- **A selected area stores a representative centre**, so its times can differ from the user's own
  position by a minute or two.
- **The location screens have not been verified on a physical device.** The picker's TalkBack semantics,
  the permission flows and a real GNSS fix need hardware; the pure decisions behind them are
  unit-tested.
- **The setup screen's layout has been reviewed against the design in source, not rendered.** Its
  states are asserted through the view model and the screen is a pure function of `LocationUiState`,
  but no emulator or screenshot comparison was run, so its spacing and proportions on a real device are
  unverified. The dropdown fields in particular have had no TalkBack review.
- **69 of 507 upazilas have no Bangla name** and display their English name; no translation was guessed.
- The Bangla resource file remains partial for the explanatory strings that make accuracy claims (see
  `app/lint.xml`), so those strings appear in English under a Bangla device locale.
