# Salah — Interactive Map Location Picker

This document describes the **map-based location picker as implemented in this repository**: which mapping
technology was chosen and why, which tile and geocoding providers it uses and on what terms, how a chosen
point is validated against the Bangladesh-only policy, how it is stored and how it reaches the prayer-time
engine, and what has and has not been verified. Where a fact is a measured property of the shipped code it
is stated as such; where a fact is a provider's documented term it is attributed.

Related documents: [`LOCATION_SYSTEM.md`](LOCATION_SYSTEM.md) (the location foundation this extends),
[`TECH_STACK.md`](TECH_STACK.md) (the dependency and the build-time configuration),
[`PRAYER_CALCULATION.md`](PRAYER_CALCULATION.md) (what happens to the coordinates afterwards).

---

## 1. What this is

Salah already let a user choose a location in two ways: a device fix, and a manual administrative selection
(division → district → upazila/thana) or typed coordinates. This feature adds a third: an **interactive
map**.

The user opens the picker, sees a real map centred on Bangladesh (or on their stored location), pans and
zooms with the platform's own gestures, taps or drags so the marker is where they are, reads the
coordinates and whatever address is known, and confirms. The confirmed point is stored through the same
`LocationRepository`, with the same country policy and the same notification reconciliation as every other
way of choosing a location, and it becomes the input to the prayer-time engine.

Nothing about the existing two paths changes. GPS selection, the administrative picker, typed coordinates,
the stored location and every calculation built on it keep working exactly as before.

## 2. Technology decision

### 2.1 What was chosen

**MapLibre Native for Android**, `org.maplibre.gl:android-sdk:13.5.3` (Maven Central), embedded in Compose
with `AndroidView`. Map data and styles are OpenStreetMap-derived vector tiles served from a build-time
style URL ([§5](#5-providers) and [§8](#8-configuration-and-keys)); reverse geocoding is an optional HTTPS
lookup against a Nominatim-compatible endpoint.

The map is a *View*, not a Compose API. MapLibre's own documentation does not prescribe a Compose wrapper,
and the separate "MapLibre Compose" project states that its API is not yet stable, so the stable View API
is embedded with Android's documented `AndroidView` interop, and the map's lifecycle (`onCreate`, `onStart`,
`onResume`, `onPause`, `onStop`, `onDestroy`) is forwarded from a `DisposableEffect` so the native map is
released when the screen goes away.

### 2.2 Options considered

| Option | Why it was or was not chosen |
|---|---|
| **Google Maps SDK for Android** | Capable, but rendering requires an API key and its terms meter map loads. That is a recurring cost and a per-user credential for a feature in an app whose other features are all offline and free. The key would also have to be managed as a build secret for a plain debug build to render a map at all. |
| **MapLibre Native + OpenStreetMap-derived tiles** | **Chosen.** No key for rendering, no per-load cost, a maintained SDK, good South Asian coverage in the underlying OSM data (including roads and smaller populated places where they are mapped), and the tile source is a configuration value rather than part of the SDK. Its licence is permissive (BSD-2-Clause for the SDK; the data and tiles carry their own attribution requirements, shown on screen). |
| **Another Android mapping solution** | None evaluated offered a clear advantage over MapLibre for this app's stated constraints (no recurring cost, no key for rendering, a path to self-hosting, a maintainable Compose story). |

### 2.3 The honest caveats of that choice

- **The map needs the network.** No tiles are bundled, so street-level detail is unavailable offline. The
  **coordinates, their validation and the save do not** — see [§7](#7-offline-behaviour).
- **The default tile provider has no SLA.** OpenFreeMap publishes its service as-is, with no availability
  guarantee. That is why the style URL is a build-time value ([§8](#8-configuration-and-keys)): a release
  that cannot tolerate the public instance can point at a self-hosted or paid provider without a code
  change.
- **The map is not the country check.** The camera is limited to a rectangle around Bangladesh, and the
  rectangle is deliberately wider than the country. Geographic validation is the bundled polygon
  ([§4](#4-bangladesh-only-validation)).

## 3. What the picker does

- Opens on the stored location when one exists (so "modify my location" is a small adjustment) and on
  Bangladesh's centre otherwise.
- Holds the selection **at the map's centre**, drawn as a fixed pin. A drag, a tap and a programmatic camera
  move therefore all end the same way: the map reports its new centre when it comes to rest, and that centre
  becomes the selection. There is exactly one authoritative selection.
- Shows the coordinates, the country verdict and whatever address is known, in a panel that is capped in
  height so it cannot cover the map's controls on a small screen.
- Provides zoom controls and a "my location" button, and shows map, address-lookup and save states
  explicitly, including a map that failed to load.
- Follows the app's theme: the dark style is loaded when the app's own colour scheme is dark, not when the
  device's is.
- **Does not write anything until the user confirms.** Looking around the map cannot overwrite the stored
  location, and pressing back restores exactly what was there.

## 4. Bangladesh-only validation

The rule is the existing one, unchanged: `BangladeshLocationPolicy.check(point, dataset.boundaryRings)`
against the bundled national boundary (`bd_boundary.tsv`, 13 rings, 1 676 points, generated from OCHA/HDX
COD-AB — see `LOCATION_SYSTEM.md` §5). It returns one of four outcomes:

- `Inside` — strictly inside the polygon. **The only outcome reported as confirmed containment.**
- `WithinBoundaryTolerance(distanceMetres)` — outside the polygon but within 5 km of it. Accepted, so a
  point on a simplified border line is not thrown away, and never described as containment.
- `Outside(distanceMetres)` — beyond the tolerance. **Refused**: the confirm action is disabled and the
  point cannot be written, and the user is told it is outside the country and that nothing was saved.
- `BoundaryUnavailable` — the boundary could not be read. The point is then accepted only if it falls inside
  the country's coarse rectangle, which can reject with certainty but never establishes containment.

Consequences that are deliberate:

- **A refused point is never silently replaced.** The selection stays where the user put it; only the
  message changes.
- **A tolerance-accepted point is stored with no country code.** `BD` is written only when the polygon
  strictly contained the point, so a stored `BD` remains a statement that the check ran and passed.
- **Coastal and island geography is the polygon's problem, not a rectangle's.** The Sundarbans, the chars
  and the islands are why the polygon is used rather than the bounding box; it is a simplification (~0.005°
  Ramer–Douglas–Peucker) with a stated 5 km tolerance, and the two outcomes are reported with different
  wording so the tolerance is never presented as containment.
- The **camera** rectangle (`BangladeshMapViewport`) exists only to keep the view over the country. It is
  never used to claim that a point is inside Bangladesh, and `BangladeshMapViewportTest` asserts it is
  wider than the real country so the polygon check is always the thing that decides.

## 5. Providers

### 5.1 Map tiles and style — OpenFreeMap (default)

- **Style URLs:** `https://tiles.openfreemap.org/styles/liberty` (light) and `…/styles/dark` (dark).
- **Terms:** no API key, no account, no registration; commercial use permitted; no published limit on
  ordinary map views or tile requests. Attribution is required (OpenStreetMap / OpenMapTiles); MapLibre's
  own attribution control stays enabled and an explicit attribution line is drawn on the map.
- **Operational caveat:** provided **as-is, with no warranty and no availability guarantee**, and the
  project states the service may be discontinued. This is a real limitation, which is why the style URL is
  configurable rather than hard-coded.

Other providers were examined and rejected **as defaults** for this app's constraints:

| Provider | Key | Notes |
|---|---|---|
| MapTiler | Required | Free tier is for personal/non-commercial use and R&D; relief from a MapLibre Native client is metered **per tile request**, not per session. A paid plan is needed for commercial production. Usable here by overriding the style URL with a keyed one. |
| Stadia Maps | Required | Free tier **does not permit commercial use**; a paid plan is needed for that. |
| Protomaps hosted | Required | Free for non-commercial use; commercial hosted use requires sponsorship. Its open-source build can be self-hosted. |

None of these is committed to the repository, and switching to one is a build property, not a code change.

### 5.2 Reverse geocoding — OSM Nominatim (default)

`https://nominatim.openstreetmap.org` (override with `salah.geocoder.baseUrl`).

- **The app identifies itself.** Nominatim's usage policy requires a valid `User-Agent` identifying the
  application; this app sends `BuildConfig.GEOCODER_USER_AGENT`, default
  `Salah/0.1.0 (https://github.com/towkirahmedrony/prayer)`.
- **Rate and caching.** The policy states an absolute maximum of **one request per second per application**
  (aggregate across all users, not per device) and expects caching. The picker therefore debounces a map
  movement by 500 ms, cancels a superseded lookup, and caches results per rounded coordinate for the session
  ([§6](#6-address-resolution)), so a drag produces one request rather than dozens.
- **Scope.** Only reverse lookups are made; no search, no autocomplete, and no request is made except in
  direct response to the user moving the map. This keeps the app on the permitted side of the policy, which
  forbids autocomplete, systematic queries and bulk geocoding.
- **Switching providers.** The policy expects an app to be able to change provider without a software update
  at the provider's request; here that is a build-time URL change. MapTiler's keyed geocoding API and
  self-hosted Photon/Nominatim are drop-in alternatives.

**No key is required by either default**, which is why the app builds, runs and maps with no configuration.

## 6. Address resolution

`NominatimAddressParser` (pure, unit-tested) maps the provider's response to `GeocodedAddress`, and
`HttpReverseGeocoder` performs the request. Design rules:

- **Everything is optional.** A place name, a locality, a road, a district, a division and the country are
  each `null` when the provider does not publish them. **No component is invented**, and no missing field is
  filled from another source.
- **No upazila is read from the provider.** OpenStreetMap has no consistent administrative key for a
  Bangladeshi upazila/thana, so the panel's upazila, district and division rows come from the **offline**
  resolution against the bundled dataset (`AreaResolver`), which is the same geography the rest of the app
  uses. The provider supplies the *informational* place, locality and road.
- **No locality is stored with the coordinates.** A stored `locality` in Salah means "verified against the
  district this position resolved to", which a picked point has no accuracy to support. The geocoded name
  travels in the location's `label` instead.
- **Debounce, cancel, cache.** Movements are debounced (500 ms); the previous lookup is cancelled when the
  point changes; results (including a "no address here") are cached per coordinate rounded to four decimals
  (~11 m), bounded to 32 entries, in memory only.
- **A failure never blocks the save.** Timeout, no connection, HTTP error, rate limit and malformed body are
  all reported states, and the coordinates are still confirmable. The panel says which case it is — "no
  address is published for this place" and "the address could not be looked up" are different sentences.
- **A parse failure is a value, not a crash.** The JSON reader (`MiniJson`) returns `null` on malformed
  input, and the geocoder maps that to an unavailable result.

## 7. Offline behaviour

| Capability | Offline? |
|---|---|
| Selecting a point on the map | No — tiles are not bundled |
| Validating a point against the country boundary | **Yes** — bundled polygon |
| Resolving the upazila/district/division | **Yes** — bundled dataset |
| Confirming and storing the point | **Yes** — DataStore |
| Prayer times, Qibla, calendars, reminders for the stored point | **Yes** — unchanged |
| Address text | No — an optional lookup |

A map that cannot load says so, and the coordinates remain confirmable. No offline-map claim is made
anywhere in the UI; adding offline tiles later (MapLibre supports offline regions and PMTiles) is a change
to the map layer alone and does not touch the location architecture.

## 8. Configuration and keys

All four values are Gradle properties compiled into `BuildConfig`; none is written into the source, and no
key is committed. The defaults need no key.

| Property | `BuildConfig` field | Default |
|---|---|---|
| `salah.map.styleUrl` | `MAP_STYLE_URL` | `https://tiles.openfreemap.org/styles/liberty` |
| `salah.map.darkStyleUrl` | `MAP_DARK_STYLE_URL` | `https://tiles.openfreemap.org/styles/dark` |
| `salah.geocoder.baseUrl` | `GEOCODER_BASE_URL` | `https://nominatim.openstreetmap.org` |
| `salah.geocoder.userAgent` | `GEOCODER_USER_AGENT` | `Salah/0.1.0 (https://github.com/towkirahmedrony/prayer)` |

Override per build, e.g.:

```
./gradlew assembleDebug -Psalah.map.styleUrl="https://api.maptiler.com/maps/streets/style.json?key=…"
```

For a keyed provider, the key belongs in the style/geocoder URL supplied by the build (a CI secret), never
in the source. **No GitHub Actions workflow change is required for the defaults**: the workflow runs
`testDebugUnitTest`, `lintDebug` and `assembleDebug`, and the default build needs no credential. A build that
overrides the URLs with a key must supply that property, e.g. from a repository secret.

## 9. Integration

- **One repository, one policy, one reconciliation.** `MapPickerViewModel` writes through the same
  `LocationRepository` (`saveMapLocation`, origin `MAP_SELECTION`), applies the same
  `BangladeshLocationPolicy`, attributes the area with the same `AreaResolver`, names the location with the
  same `LocationDisplayName`, and reconciles the notification schedule with the same
  `PrayerNotificationCoordinator` as the device-fix and area paths. The screen never talks to a map or a
  geocoder directly; it calls the view model, and the view model calls the `ReverseGeocoder` and
  `LocationProvider` ports.
- **Prayer times follow automatically.** Times are computed from the stored coordinates and zone, so once
  the confirmed point is stored the engine uses it; the differing-times problem the task calls out is
  handled by that storage path plus the reconciliation — no calculation rule, method, madhhab or offset was
  changed. A selected map point does **not** establish an official Hijri date or a religious timetable, and
  no wording implies it does.
- **Entry points.** The picker is reachable from the first-launch setup screen (as a full-screen overlay,
  because while setup is required the shell shows no navigation graph) and from Settings → Location (as a
  navigation destination). The area picker and type-the-coordinates form remain in place beside it.
- **Explicit GPS permission.** The permission is requested only from the "my location" button, never on
  opening the screen. A denial is reported and the entire manual flow stays usable; there is no background
  location permission.

## 10. Performance

- The address lookup is debounced, cancelled when superseded, cached per point, and executed on
  `Dispatchers.IO`; it is never on the main thread and never per frame.
- Camera-idle is what triggers a selection change, so a continuous gesture does not produce intermediate
  work.
- The map's lifecycle is forwarded to MapLibre and the native view is destroyed when the screen leaves, so
  no map resources outlive the screen.
- The map is a separate View with the platform's own gesture handling; it does not share a scroll container
  with the panel, so map gestures and page scrolling do not fight.

## 11. Tests

Run with `./gradlew testDebugUnitTest` (the same command CI runs).

- `MapPickerViewModelTest` — on virtual time with the real view model, policy, arithmetic and coordinator:
  a confirmed point inside the boundary is stored with `MAP_SELECTION` provenance, the resolved name, `BD`
  and the point's own coordinates; a point outside the boundary is refused and nothing is written; an
  address lookup is debounced and a superseded point is never requested; leaving without confirming leaves
  the stored location untouched; a declined permission is reported and the map stays usable; and a failed
  lookup still allows the save, which then falls back to the administrative name.
- `NominatimAddressParserTest` — the provider mapping: components read, components the provider does not
  publish left absent, a `village`-only response still yielding a place name, a malformed body reported as
  unavailable rather than partially read, and an address-less response reported as not found.
- `MiniJsonTest` — the JSON reader: objects, nested objects, escapes, numbers/booleans/nulls/arrays, and the
  refusals that matter (truncated, trailing content, raw control characters, bad literals).
- `BangladeshMapViewportTest` — the camera rectangle contains the whole real country, clamping behaves, and
  the rectangle is genuinely wider than the country (so a rectangle corner is not classified `Inside`).
- `BundledDatasetIntegrityTest`, `BangladeshLocationPolicyTest`, `AreaResolverTest`, `DataStoreLocationRepositoryTest`
  — unchanged, and still the gate on the boundary polygon and the area resolution this feature reuses.

## 12. Known limitations

- **The map has not been verified on a physical device.** The Compose/`AndroidView` wrapper, the gesture
  behaviour alongside the panel, the dark/light style switch and the permission flow need hardware; the
  decisions behind them are unit-tested, and the CI build compiles and lints the whole thing, but no
  emulator or screenshot comparison was run here.
- **The default tile provider has no SLA** ([§5.1](#51-map-tiles-and-style--openfreemap-default)).
- **The default geocoder is a public instance.** Its policy permits user-initiated app lookups within the
  aggregate rate limit, and the picker is built to respect that, but a large user base should self-host or
  move to a keyed provider — which is a configuration change.
- **Offline maps are not implemented.** The architecture allows adding them (MapLibre supports offline
  regions and PMTiles) without changing the location model; the UI makes no offline-map claim.
- **Address quality in Bangladesh is provider-dependent.** OSM coverage is uneven, and a rural point may
  return nothing — in which case the coordinates and the administrative area are shown instead, which is the
  intended fallback.
- **The camera rectangle is a viewport convenience, not a country boundary.** It is wider than the country,
  and validation never uses it.
- **A map selection stores no accuracy**, because a picked point is not an observation, and it stores no
  `locality` for the same reason; both are recorded here rather than papered over.
