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

## 3. GPS and manual selection

**Device fix.** `LocationProvider` is a one-shot port. The Android implementation
(`AndroidLocationProvider`) uses `LocationManagerCompat.getCurrentLocation` — the framework provider,
not Google Play Services — with a bounded wait, and registers no listener after returning. A fix is
never saved on sight: it is checked against the country policy, attributed to an area where possible,
and shown as a *pending* fix the user confirms. Nothing replaces the stored location until the user
taps **Use this location**.

**Manual selection.** The picker lists the divisions, then the districts of the chosen division, then
the upazilas/thanas of the chosen district. `AreaSelection` is the guard: choosing a level clears the
levels below it, and an id that does not belong under the chosen parent is ignored rather than stored,
so a half-valid hierarchy cannot exist. A complete selection saves the **upazila's published centre**
and the division/district/upazila names.

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
- `tools/generate_bd_location_dataset.py` — the generator.

**Sources.**

1. **Bangladesh Common Operational Dataset on Administrative Boundaries (COD-AB)**, published by
   OCHA / HDX as `cod-ab-bgd`. Provides the administrative hierarchy (p-codes), the English names, the
   geometry, and a published `center_lat` / `center_lon` per unit. Used for the hierarchy, the English
   names, the coordinates and the national boundary. Licence: **Creative Commons Attribution for
   Intergovernmental Organisations (CC BY 3.0 IGO)**, http://creativecommons.org/licenses/by/3.0/igo/.
2. **`nuhil/bangladesh-geocode`** (MIT). Used **only** for the Bangla name of a unit, joined by name.
   Licence: MIT, https://github.com/nuhil/bangladesh-geocode.

**Version.** The dataset identifies itself as `BGD-COD-AB v03 (2023-05-21)`, the value of
`AssetBangladeshAreaCatalog.VERSION` shown in the UI and stored with a selected area. The generator
labels the same release `COD_VERSION_NOTE = "COD-AB BGD v03 (valid_on 2023-05-21)"`; the two strings
are not byte-identical (one is written for display, the other for the file header), but both name the
same source release, COD-AB v03 with a validity date of 2023-05-21. The version is a constant, not read
from the file, so it identifies the build's dataset rather than whatever a particular copy of the file
happens to contain.

**Format.** Tab-separated text with `#` comments, seven columns per data row
(`level, id, parent_id, name, bn_name, latitude, longitude`) and three for the boundary
(`ring_index, latitude, longitude`). The parser is `BangladeshLocationDataset` (pure Kotlin, no JSON or
CSV dependency).

**Size and coverage (measured from the shipped files).**

| Quantity | Value |
|---|---|
| Divisions | 8 |
| Districts | 64 |
| Upazilas / thanas | 507 |
| Upazilas with no Bangla name (English name shown instead) | 69 |
| Boundary rings | 13 |
| Boundary points | 1 676 |
| `bd_areas.tsv` / `bd_boundary.tsv` size | ≈ 45 KB / ≈ 37 KB |

**Bangla names.** The Bangla join is best-effort and never guessed: an exact match first, then a
similarity match **within the same district** at a high threshold, and otherwise **no Bangla name at
all** (the English name is shown). 69 of the 507 upazilas keep `null` rather than a transliteration
Salah invented.

**What has and has not been verified.** The generator performs a data-quality check against the
source's *own* geometry (every published centre must fall inside its own boundary) and reports
failures rather than dropping them. That is a check of the source against itself. Salah has **not**
compared these coordinates against any independent dataset, and makes no claim that they are
externally verified beyond what OCHA publishes. See §6 and §12.

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

## 9. Offline operation and persistence

Nothing in the location system needs a network, and Salah declares no `INTERNET` permission.

- **Stored location:** `DataStoreLocationRepository` keeps the location in the same DataStore as the
  other preferences. The stored value carries its origin, accuracy, acquisition time, country code, the
  area hierarchy (ids and names) and the dataset version. A location written by an earlier build simply
  lacks the newer keys and reads as a manual entry with unknown freshness — an existing location is
  preserved, not migrated destructively.
- **Geography:** `AssetBangladeshAreaCatalog` reads the two assets once per process (≈ 83 KB total,
  a few milliseconds to parse) and caches the parsed dataset.
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
  duplicate ids, boundary ring grouping, a ring too small to enclose being dropped, and malformed
  boundary lines being recorded.
- `BundledDatasetIntegrityTest` — loads the **real** shipped assets and checks the measured coverage
  (8 / 64 / 507), that **no** line is rejected, that the number of data lines equals the number of
  parsed areas (so no row can silently disappear), that ids are unique and parents resolve at the
  correct level, that every coordinate is plausible and every representative centre is strictly inside
  the bundled boundary, that the boundary parses into valid rings, and that the repaired Shahrasti row
  keeps its identity, parent and coordinates. This is the strict gate that keeps a malformed shipped
  file from reaching a release; runtime parsing stays lenient by design.
- `BangladeshLocationPolicyTest` — the boundary classification: clearly inside, clearly outside,
  just outside within the tolerance, beyond the tolerance, and unreadable boundary; against both a
  synthetic square and the real boundary, including a regression case for an Indian city inside the
  tolerance not being reported as inside Bangladesh.
- `AreaResolverTest`, `AreaSelectionTest`, `LocationSetupTest` — level selection, the hierarchy guards,
  attribution thresholds, and the setup gate for every reason.
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
- **A selected area stores a representative centre**, so its times can differ from the user's own
  position by a minute or two.
- **The location screens have not been verified on a physical device.** The picker's TalkBack semantics,
  the permission flows and a real GNSS fix need hardware; the pure decisions behind them are
  unit-tested.
- **69 of 507 upazilas have no Bangla name** and display their English name; no translation was guessed.
- The Bangla resource file remains partial for the explanatory strings that make accuracy claims (see
  `app/lint.xml`), so those strings appear in English under a Bangla device locale.
