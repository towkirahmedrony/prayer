# Salah — Mosque Finder

This documents the mosque finder as implemented in this repository: the provider contract, the
filtering rules, the map configuration, the local cache, the location and permission behaviour, and
the things the feature deliberately does not claim.

It is not a design proposal. Every mechanism described here exists in the source tree, and §9 lists
the automated coverage.

---

## 1. Scope

Implemented:

- Discovery of nearby mosques from **Geoapify Places** (`GET /v2/places`), using the mosque-specific
  and broader Islamic place-of-worship categories together, with a conservative local name filter on
  the broader category.
- **Nearest-first list** and **map view** of the same result set, with selection, distances calculated
  locally, and a compact detail card.
- Persistent **Room cache** of previously fetched mosques, usable with no connection, with a visible
  cached/refresh-failed state.
- **Saved-location** default search centre and an explicit one-shot **current-location** action with
  permission, GPS-off, timeout and provider-failure handling.
- Bangla and English interface strings, light and dark map styles, and screen-reader descriptions.
- Pagination, deduplication, malformed-response handling, rate-limit reporting and a bounded response
  size.

Deliberately **not** implemented:

- Place Details lookups, phone calls, website launches or ratings. Every displayed field came in the
  search response itself.
- A routing engine or turn-by-turn navigation. Directions are handed to an installed maps app.
- Background location, continuous location updates or a location listener left running.
- Offline map tiles, tile pre-downloading or any claim that the map works without a connection.
- Fake or hardcoded mosques, and treating every Islamic institution as a mosque.

---

## 2. Where the code lives

| Concern | Location |
|---|---|
| Domain model and contracts | `core/domain/mosque/` |
| Geoapify request and GeoJSON parsing | `data/mosque/GeoapifyMosqueSource.kt`, `GeoapifyMosqueParser.kt` |
| Filtering and ordering rules | `core/domain/mosque/MosqueGeo.kt` |
| Network-first / cache-second policy | `data/mosque/DefaultMosqueRepository.kt` |
| Room entity, DAO, database, cache | `data/mosque/CachedMosqueEntity.kt`, `MosqueDao.kt`, `MosqueDatabase.kt`, `RoomMosqueCache.kt` |
| Map style URL | `data/mosque/MosqueMapStyle.kt` |
| Screen and map | `feature/mosque/` |
| Dependency wiring | `di/SalahModules.kt` |
| Navigation and Tools entry | `navigation/SalahDestination.kt`, `SalahNavHost.kt`, `feature/tools/ToolsEntry.kt` |

The dependency direction is one-way: `feature → domain ← data`. The view model knows only the
`MosqueRepository` and location ports; no composable parses a response or calculates a distance, and no
data class imports Compose.

---

## 3. Geoapify Places

### 3.1 Endpoint and parameters

```
GET https://api.geoapify.com/v2/places
```

| Parameter | Value | Purpose |
|---|---|---|
| `categories` | `tourism.sights.place_of_worship.mosque,religion.place_of_worship.islam` | One comma-separated list in one parameter. |
| `filter` | `circle:<longitude>,<latitude>,<radiusMetres>` | Hard geographic boundary. Longitude first, per Geoapify. |
| `bias` | `proximity:<longitude>,<latitude>` | Orders the provider's own results around the centre. |
| `limit` | `1..500` | Page size; the configured default is 50. |
| `offset` | `0, 50, 100, …` | Pagination. |
| `lang` | the selected interface language tag (`bn`, `en`, …) | Names and addresses in the reader's script where the provider has them. |
| `apiKey` | `BuildConfig.GEOAPIFY_API_KEY` | Authorisation. Never logged or stored in state. |

The default search radius is 5 km, up to 3 pages. Both values are in `MosqueSearchConfig`.

### 3.2 Why two categories

Geoapify publishes a precise mosque category,
`tourism.sights.place_of_worship.mosque`, and a broader Islamic place-of-worship category,
`religion.place_of_worship.islam`.

In live checks around central Dhaka, the precise category returned **no features even at an 8 km
radius**, while the broader category returned real neighbourhood mosques. Requesting only the precise
category would therefore have produced an empty screen in the place this app is most used.

The broader category also contains madrasas, maktabs, dargahs, khankahs, shrines, Eidgah grounds and
orphanages. The search accepts a feature when either:

1. it carries the mosque-specific category; or
2. it carries the Islamic-worship category **and its name contains a mosque word**.

Mosque words include the common Latin transliterations (`mosque`, `masjid`, `masjid`, `mosjid`,
`moshjid`, `mashjid`, `musalla`, `jame`, `jama`, `jamia`, `baitul`, `baytul`) and Bengali (`মসজিদ`,
`জামে`). A name that names both a mosque and its attached school — for example
`Sayednogor Mosjid Madrasa` — is accepted, because it names a mosque.

A feature with neither category is rejected regardless of its name. A feature with no name, no finite
coordinate or no stable identity is dropped rather than padded with a guess.

This is intentionally conservative. Showing a madrasa under “mosques nearby” is the wrong answer to the
user's question; showing a smaller list is the honest answer.

### 3.3 Response shape and mapping

The response is a GeoJSON `FeatureCollection`:

- `geometry.coordinates` is `[longitude, latitude]`. The parser performs the swap exactly once.
- `properties.name` is required.
- `properties.place_id` is the cache identity when present. When absent, a stable identity is derived
  from the name and coordinate (`geo:<lat>:<lon>:<normalised-name>`) so pagination and cache updates do
  not duplicate the same feature.

Two different `place_id`s can still describe one building — an OpenStreetMap node and the way drawn
around it. Results are therefore also deduplicated by normalised name (lower case, whitespace
collapsed) within 30 m, the `MosqueGeo.NEAR_DUPLICATE_METRES` bound. The bound is deliberately far
smaller than the search radius: a same-named mosque further away is a different mosque and stays in
the list, and two differently named mosques at one point are two places.
- `properties.formatted` is the address, falling back to `address_line1, address_line2`.
- `properties.categories` is kept so the mosque filter can run again after a cache read.
- `properties.datasource.raw.phone` / `website` are read when present; there is also a top-level
  fallback for flattened responses.

A malformed **feature** is skipped without discarding its siblings. A body that is not a
FeatureCollection with a `features` array is reported as malformed, not as an empty success.

### 3.4 No Place Details call

`place_id` is used only as a stable identity and cache key. No second endpoint is called, so no field
is presented as if it were fetched when it was not. Phone and website appear only when the search
response carried them.

---

## 4. Map

### 4.1 MapLibre renders; Geoapify supplies the map

The app uses **MapLibre Native Android** (`org.maplibre.gl:android-sdk:13.7.1`). MapLibre is a
rendering engine: the SDK contains no world map, no tiles and no fonts. The map data comes from the
Geoapify style document:

```
https://maps.geoapify.com/v1/styles/{positron|dark-matter}/style.json?apiKey=<key>
```

`positron` is used on a light canvas and `dark-matter` on a dark one. The theme is detected from the
active `MaterialTheme` background luminance. A blank Geoapify key produces **no style URL**; the map
area says the map is unavailable and the list remains fully usable.

### 4.2 Interactions

- The search centre is drawn as a circle layer.
- Every returned mosque is a circle marker; the selected mosque gets a larger highlighted ring.
- Tapping a marker selects it through the same callback the list uses, so there is one selection state
  and one detail card in both views.
- The first result set frames the centre and the returned mosques together; selecting a mosque animates
  the camera to it.
- The `MapView` render loop is forwarded from the screen lifecycle and destroyed with the composition.
- A style load failure is reported over the map after the failure callback or a bounded 20-second
  timeout.

Attribution is rendered over the map: **© Geoapify, © OpenStreetMap contributors**.

### 4.3 Offline terms

The map is **not** an offline map. The app does not download tiles in advance, does not bulk-copy tiles
from public OpenStreetMap servers, and makes no claim that tiles remain available without a connection.
Map tiles are governed by the map style provider's own terms, which are separate from the Places API
terms.

The **list** is the offline-capable representation: it is read from the Room cache and includes
locally calculated distances. That is why the list is the default view.

---

## 5. Local cache

### 5.1 Storage

Room is used for one table in one database file (`mosque_cache.db`), schema version 1. The exported
schema lives under `app/schemas/` because `room.schemaLocation` is configured for KSP.

`cached_mosques` is flat:

| Column | Notes |
|---|---|
| `placeId` | Primary key: Geoapify `place_id` or the parser's coordinate-derived identity. |
| `name`, `address` | Display fields. |
| `latitude`, `longitude` | Doubles. |
| `categories` | Newline-separated category tokens, used to re-run the mosque filter after a cache read. |
| `phone`, `website` | Optional contact fields. |
| `fetchedAtEpochMillis` | Indexed write time; also the timestamp shown as the cache age. |

### 5.2 Read and write policy

Write path, after a successful network result:

1. merge all pages by identity (first occurrence wins);
2. apply the mosque filter and unusable-feature check;
3. upsert by primary key, so a refresh from a slightly different centre updates rows instead of
   duplicating them;
4. prune rows older than 30 days.

Cache writes and pruning are best-effort. A storage failure does not turn a successful search into a
failure, because the in-memory list is still correct.

Read path:

1. compute the smallest latitude/longitude rectangle containing the search circle;
2. query that rectangle in SQL;
3. map rows back to domain mosques, dropping rows that cannot describe one;
4. apply the **same** mosque filter and the **same** haversine distance/radius rule as live results;
5. sort nearest-first with a name tie-break.

The rectangle is only a pre-filter. A diagonal point can sit inside the rectangle but outside the
circle; the exact haversine check removes it. Near a pole the longitude span widens to the whole world
rather than dividing by a cosine that approaches zero.

### 5.3 Offline behaviour

If the request fails and the cache has a result for the current circle, the repository returns that
result as `Success(origin = CACHE, refreshFailure = <reason>)`. The screen shows the saved list, a
“Saved” pill and a notice naming the refresh failure **and when the cache was written**, so a saved
list cannot pass for a live one. It never replaces a real cached list with a bare error panel.

If no request can be made at all (blank key) the same rule applies: cached results are shown with
“not configured” as the refresh reason; only when there is no cache does the screen show the
configuration failure.

### 5.4 Migrations and downgrade

Version 1 has no migration because no earlier version shipped. `MosqueDatabase.MIGRATIONS` is declared
empty rather than omitted, so the next schema change has an obvious place to add its migration.

A downgrade cannot be migrated by an older build. The builder explicitly uses
`fallbackToDestructiveMigrationOnDowngrade()` so an unreadable older schema is discarded rather than
producing an opaque exception. The cache contains public places only, never user data, so discarding
it loses nothing the user cannot re-fetch.

---

## 6. Location and permissions

### 6.1 The saved location is the default

The first search uses Salah's one saved location — the same location prayer times use — and reads it
without asking for permission. The screen label says which place is being searched around.

The saved location is **not** modified by this screen. A mosque search is a view of that location; a
map interaction must not silently change the user's prayer times.

### 6.2 Current location is a deliberate action

The location control on the right requests a one-shot fix:

- permission is requested only when the user taps it and no grant exists;
- both coarse and fine location are requested together so Android 12+ can offer approximate/precise;
- coarse is accepted; the feature needs a neighbourhood, not centimetres;
- no background location permission is declared or requested;
- no continuous updates or listeners are registered; `LocationProvider.currentLocation` is one-shot
  and bounded by a 15-second timeout.

A fix obtained this way replaces the search centre for this screen only. The saved location remains
unchanged.

### 6.3 Outcomes

| Condition | What the screen says | What it keeps |
|---|---|---|
| Permission denied | Location access is not allowed; showing the saved location | Existing list |
| GPS/location switched off | Location is switched off | Existing list |
| Timeout | A current position could not be obtained in time | Existing list |
| Provider failure / unusable fix | A current position could not be obtained | Existing list |
| Acquired | Searching around “Current location” | Old distances cleared, new search starts |

A failed current-location request never clears a real list that is already on screen. If there is no
saved location and no fix, the screen offers the current-location action rather than searching an
invented centre.

### 6.4 Permission gate already upstream

Salah's location gate normally establishes a saved location before this screen is reachable. This
feature therefore does not request permission on entry and does not add a second location setup flow.

---

## 7. Interface and architecture

### 7.1 Why the list is the default

The list survives every partial failure: a missing key, a style that cannot load or a slow connection
leaves names, distances and directions usable. It is also the accessible representation — a screen
reader can read the list in order, whereas a rendered map is a single canvas. The map is a second
presentation of the same state, not a second source.

### 7.2 Composition

- `MosqueFinderViewModel` owns every transition: initial saved-location search, refresh, current
  location, permission result, view mode and selection. State is exposed as `StateFlow` and collected
  with `collectAsStateWithLifecycle`.
- A new search cancels the previous one through a tracked `Job`. Cancellation is rethrown rather than
  converted into a failure.
- A current-location request is ignored while one is already in flight.
- The Places language is read per search, so changing the interface language and returning refreshes
  into that language.
- Composables render state and forward gestures; no response parsing, distance calculation, cache
  access or permission decision lives in a composable.
- Requests, database work and the location provider run in coroutine contexts; nothing blocks the main
  thread.

### 7.3 Presentation details

- Compact subpage header, one search-context line, a two-option view selector and two icon actions.
- The result area takes the remaining height; the list is not nested in a scrolling page.
- The expanded list card shows address, phone and website only when present, plus one external
  directions action.
- The map view puts the selected mosque's compact card beneath the map.
- Distances are written in the interface language's numerals: metres below 1 km, then one decimal of a
  kilometre.
- Empty, loading, failure and no-location states are separate panels with honest copy.
- A notice about something only the system can change — a permission refused for good, or GPS switched
  off — offers the matching settings page, because Android will not show the permission dialog again.
  Transient notices (timeout, no fix, no maps app) can be dismissed instead of outliving the moment.
- All touch targets are at least 48 dp; the selector uses `selectable` with the radio-button role; the
  view group, icon actions and directions action carry content descriptions.
- Light and dark themes follow the app; the map style follows the same theme.

### 7.4 Directions

Directions are handed to an installed maps application through a `geo:` URI, with a Google Maps web
URL fallback. Salah has no routing engine and does not resolve packages in advance — package visibility
rules make a pre-flight check unreliable, so the attempt is the check. If both fail, the screen says no
app can open directions.

---

## 8. Configuration

The key is the existing `BuildConfig.GEOAPIFY_API_KEY`. It is resolved at build time from, in order:

1. a Gradle property (`-PGEOAPIFY_API_KEY=...` or `~/.gradle/gradle.properties`);
2. the `GEOAPIFY_API_KEY` environment variable;
3. the git-ignored root `local.properties`.

No new key configuration was added. A blank value is a normal “not configured” state: no request is
made, no URL is logged, the map is unavailable and the screen explains it. The key is never logged or
included in a failure value or UI state.

For a local build, add to the root `local.properties` (never commit it):

```properties
GEOAPIFY_API_KEY=your-key
```

The feature still works without a key for cached results.

---

## 9. Tests

The feature is covered by JVM unit tests:

| File | What it asserts |
|---|---|
| `MosqueGeoTest` | Category and name filtering, rejection of madrasas/shrines, dedupe, unusable-feature rejection, local distance, radius filtering, nearest-first ordering and deterministic tie-breaks. |
| `GeoapifyMosqueParserTest` | Coordinate swap, field mapping, address fallback, optional fields, stable fallback identity, malformed features not discarding siblings, malformed roots, empty page. |
| `GeoapifyMosqueSourceTest` | Both categories in one request, circle/bias/paging/language/key parameters, blank-key short-circuit, status mapping (401/403/429/5xx), network failures, oversized body, page-size bound. |
| `DefaultMosqueRepositoryTest` | Page merge/dedupe, short-page stop, page ceiling, failure-after-usable-page, cache fallback with refresh reason, failure mapping, unconfigured key, cache read/write exceptions, empty network result, radius filtering, `cachedNearby`. |
| `RoomMosqueCacheTest` | Rectangle pre-filter plus exact circle, nearest-first sort, upsert by identity, duplicate collapse, non-mosque rejection, unreadable row skipping, age pruning, newest timestamp, invalid query short-circuit, polar bounding box. |
| `MosqueFinderViewModelTest` | Saved-location first search, no-location state, loading, empty vs failure, cached origin, failed refresh keeping the list, per-search language, selection toggle, view switch without re-query, permission/GPS/timeout/provider failures, acquired fix replacing the centre without writing the saved location, refresh-without-centre, map style configuration. |
| `MosqueMapStyleTest` | Blank key → no style, light/dark style selection, key encoding and trimming. |

The existing `LocalizationResourcesTest` also covers the new strings: every `mosque_` key has a Bengali
value, keeps its format arguments and is written in Bengali script unless it is a placeholder or a
proper attribution. Its hardcoded-sentence audit includes the new mosque composables.

### 9.1 Verification

CI is the authoritative build environment. The GitHub Actions workflow runs the JVM unit tests,
Android lint and the signed release assembly. The APK is never built locally as part of validation.

---

## 10. Known limitations

- **Provider coverage varies.** Some neighbourhood mosques are missing from Geoapify/OpenStreetMap, and
  some are named in a way no name filter can classify. The list is the provider's answer, not a
  survey.
- **The name filter is heuristic.** It is deliberately biased toward rejecting non-mosques. A mosque
  with an unorthodox name that carries only the broad category will not appear.
- **No Place Details.** Phone and website are present only when the search response carried them.
- **The map needs a connection and a key.** No offline tiles are bundled or pre-downloaded.
- **No routing.** Directions leave the app.
- **No background location.** A current-location search requires the screen to be open.
- **Cache age is 30 days.** Older rows are pruned on the next successful write; a stale row can still
  be read before that write happens.
- **Geoapify quota applies.** Rate limiting is reported as a refresh failure and cached results, when
  available, remain visible.
