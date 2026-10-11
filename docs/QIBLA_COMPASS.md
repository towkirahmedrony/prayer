# Salah — Qibla Compass

This documents the Qibla feature **as implemented in this repository**, including the mathematics,
the sensor and location strategy, the north-reference discipline, and the things it deliberately
refuses to do.

It is not a design proposal. Every mechanism described here exists in the source tree, and the
automated coverage is listed in §11 with the notation used elsewhere in this project: **verified**
means an automated test asserts it, and *not verified* means exactly that.

---

## 1. Scope

Implemented:

- The initial great-circle **Qibla bearing** from the stored location, in degrees clockwise from true
  north, with the great-circle distance to the Kaaba.
- A **compass dial** driven by real device sensors, with the bearing, the device heading, the signed
  turn to the Qibla and an alignment indication.
- **Sensor selection** across three families, with a documented order and a working fallback.
- **Magnetic declination** correction from the platform's World Magnetic Model, applied exactly once
  and only to the heading.
- **Location** obtained from the device on request, or entered by hand — validated, stored locally,
  and usable offline with no permission at all.
- **Freshness and provenance** reporting, so a saved position is never described as a current one.
- **Calibration guidance** derived only from the platform's own accuracy signal.
- Ten explicit UI states, each with a sentence rather than a fabricated value.

Deliberately **not** implemented, and reported as such:

- A Qibla map, or any network lookup. Salah declares no `INTERNET` permission.
- Background location. The position is needed while the screen is in front of the user.
- Continuous location updates. One request per user action; the stored position is reused.
- A "compass accuracy score". See §7.3 — the platform does not provide the evidence for one.

---

## 2. The Qibla bearing

### 2.1 What quantity this is

The Qibla is the **initial great-circle bearing** from the observer to the Kaaba: the direction of
the shortest path over the Earth's surface at the moment of departure.

Four different angles get confused with each other, and Salah produces exactly one of them:

| Quantity | What it is | Where it lives |
|---|---|---|
| **Qibla bearing** | Direction of the great circle to the Kaaba, from **true** north. | `QiblaCalculator` |
| Device heading | Direction the top of the device points. | `CompassReading` → `HeadingMath` |
| The turn | Signed shortest rotation from the heading to the bearing. | `HeadingMath.turnFromTrue` |
| Rhumb-line course | Constant-compass bearing; **not** used. | nowhere |

The difference is not cosmetic. On a long route — Dhaka to Mecca is about 5 170 km — the initial
bearing and the constant-compass course differ by tens of degrees, and the initial bearing and the
final bearing differ by more than that. Quoting the wrong one is the most common way a Qibla
implementation is confidently wrong, so this file states which one is used and the tests pin it
against the documented formula.

### 2.2 The formula

With `φ1, λ1` the observer, `φ2, λ2` the Kaaba, and `Δλ = λ2 − λ1` the eastward longitude
difference:

```text
θ = atan2( sin Δλ · cos φ2 ,  cos φ1 · sin φ2 − sin φ1 · cos φ2 · cos Δλ )
```

`atan2` rather than `asin` or `acos` of the arcsine form: it is unambiguous over all four quadrants
and does not lose precision near ±90°. The result is normalized into `[0, 360)`.

### 2.3 The Kaaba reference

| Property | Value |
|---|---|
| Latitude | `21.4225° N` |
| Longitude | `39.82617° E` |
| Source | English Wikipedia, *Kaaba*, coordinate block: `21°25′21″N 39°49′34″E`, GeoHack `21.4225_N_39.82617_E`, region `SA` |
| Agrees with | the commonly published `21.4225, 39.8262` to within `1e-4°` |

**Why one point is enough.** The Kaaba's footprint is about 12.86 m by 11.03 m, so any single
representative point lies within roughly 7 m of the centre. A lateral displacement of `d` metres
changes the bearing by at most `atan(d / r)` for an observer `r` away:

| Observer distance | Bearing error from a 7 m reference offset |
|---|---|
| 5 000 km | 0.00008° |
| 100 km | 0.004° |
| 1 km | 0.4° |
| 100 m | 4° |
| 10 m | 35° |

So the choice of reference point is irrelevant at every realistic distance and only becomes visible
immediately adjacent to the building — which is why `KaabaReference.MINIMUM_SEPARATION_METRES` exists
rather than a more precise coordinate.

### 2.4 Refused inputs

Salah **refuses** rather than guesses in four cases, each returned as
`QiblaBearing.Unavailable(reason)`:

| Reason | Condition | Why refuse |
|---|---|---|
| `INVALID_COORDINATES` | latitude outside ±90, longitude outside ±180, or any non-finite value | A latitude of 91° is a caller bug, not a position near the pole. Clamping it would answer a question nobody asked. |
| `AT_KAABA` | within `KaabaReference.MINIMUM_SEPARATION_METRES` (1 m) of the reference point | The direction from a point to itself does not exist, and is numerically unstable adjacent to it. |
| `ANTIPODAL` | within `ANTIPODAL_SEPARATION_METRES` (1 m of arc) of the exact antipode, detected from the formula's horizontal component | Near the antipode every direction to the Kaaba is an equal-length path, so the bearing is genuinely ill-conditioned. Critically, `atan2(0, 0)` returns `0.0` in IEEE arithmetic — without an explicit guard the app would confidently report **due north** from a point in the South Pacific. |

The two refusals are deliberately measured in **different units**, and that is not an accident of
implementation. The horizontal component of the formula has magnitude `|sin δ|`, where `δ` is the
angular distance to the Kaaba, so it is small near the antipode — *and equally small near the Kaaba
itself*. A single magnitude threshold therefore cannot tell the two apart, and the first version of
this code reported `ANTIPODAL` for anything between 1 m and 6.4 m from the Kaaba. The near-Kaaba case
is settled by haversine distance, where it is well conditioned; the near-antipode case is settled by
the horizontal component, where the haversine loses tens of kilometres of precision through
cancellation but the component does not. `QiblaCalculatorTest` pins both boundaries.

Longitude differences are folded into `(-180, 180]` by
`((λ2 − λ1 + 540) mod 360) − 180`, so `+350°` and `−10°` — the same physical separation — are treated
identically, and a location either side of the antimeridian behaves continuously.

### 2.5 How it is verified

The expected values in `QiblaCalculatorTest` are **not** produced by the production function. Two
genuinely different formulations are implemented inside the test file:

1. **Tangent-plane projection** — builds the chord to the Kaaba in Earth-centred coordinates,
   projects it onto the local east/north basis, and measures the angle there. 3-D vector algebra
   sharing no expression with the `atan2` formula.
2. **Spherical law of cosines** — the classical navigation derivation, with the quadrant taken from
   the sign of the longitude difference.

The production formula, both alternative formulations and a frozen city table agree to better than
`1e-13` across a global grid. Human-checkable sanity cases are asserted exactly: due south of the
Kaaba on the same meridian reads `0°`, and at the Kaaba's own latitude due west of it reads
slightly **north** of due east — because a great circle is not a parallel of latitude.

Worth recording: the first draft of the independent tangent-plane implementation was itself wrong
(built the tangent at the wrong point), which the cross-check caught immediately. That is the reason
the test file contains two alternative derivations rather than one.

---

## 3. Heading: conventions and the north reference

### 3.1 The convention

> **The heading is the direction of the top edge of the device, as currently displayed, measured in
> degrees clockwise from north, in `[0, 360)`.**

This is the "flat" convention, matching the Android documentation's description of using a device as
a mechanical compass, and it is the convention `SensorManager.getOrientation` produces after the
frame is remapped for the screen rotation.

`CompassReading.tiltDegrees` carries how far the device is from horizontal. Past
`isOrientationUsable` (40°) the projection collapses — the azimuth becomes sensitive to fractions of
a degree of movement and swings over tens of degrees — so the screen asks the user to hold the device
flat and **withholds the dial and the turn** rather than animating a number that has stopped meaning
anything.

### 3.2 Screen rotation

`SensorManager.getOrientation` reports the azimuth of the device's **natural** Y axis. On a phone
held in landscape that is the wrong physical edge by a quarter turn, so the rotation matrix is
re-expressed in a frame whose forward axis follows the display. `SensorFrames` chooses that frame
from four rotations:

```text
NATURAL      → ( X,  Y)
ROTATED_90   → ( Y, −X)
ROTATED_180  → (−X, −Y)
ROTATED_270  → (−Y,  X)
```

The `ROTATED_90` entry is the mapping the Android documentation gives for `remapCoordinateSystem`
when using a device as a mechanical compass; the other three follow from applying the same quarter
turn repeatedly, and `SensorFramesTest` asserts that cycle rather than only the four literals. It
also asserts that no frame names the same axis twice, because `remapCoordinateSystem` returns `false`
and leaves the output untouched in that case — which would silently keep the unrotated frame and a
wrong heading with no error anywhere.

The rotation is read **per sensor sample**, not observed through a listener, because the activity
declares `configChanges="orientation|screenSize|…"` and is therefore not recreated when the screen
rotates. A rotation is reflected in the next heading instead of after a restart that will not happen.

### 3.3 Declination, and why it is applied exactly once

The bearing is referenced to **true** north because it is pure geography. A compass reads
**magnetic** north because that is what a magnetometer measures. The two differ by the local
declination — up to about ±20°, and drifting year by year. Comparing an uncorrected magnetic heading
against a true-north bearing and declaring "aligned" is therefore wrong by up to 20°, far larger than
the compass's own error.

Salah's mechanism is a **type distinction** rather than a convention:

```kotlin
@JvmInline value class MagneticHeading(val degrees: Double)
@JvmInline value class TrueHeading(val degrees: Double)

fun toTrueHeading(magnetic: MagneticHeading, declinationDegrees: Double): TrueHeading
fun turnFromTrue(from: TrueHeading, to: TrueHeading): Double
```

`toTrueHeading` consumes a `MagneticHeading` and produces a `TrueHeading`, and **no function accepts
a `TrueHeading` and adds declination**. The classic double-correction bug — correcting an
already-corrected heading for an error of twice the declination — cannot be expressed in this API.
`HeadingMathTest` pins both the arithmetic and the observable consequence: the turn to the Qibla is
independent of the declination value, which would fail if declination were applied to the bearing as
well as the heading.

**Where the declination comes from.** `android.hardware.GeomagneticField`, which evaluates the World
Magnetic Model inside the platform from the coordinates, the altitude and the date. No network, no
API key, no dependency, and correct for *when* it is asked — whereas a hard-coded lookup table would
be a snapshot that silently ages.

Altitude is not stored; the model is evaluated at sea level. Declination changes by a few hundredths
of a degree per kilometre of altitude, which is immaterial next to the model's own accuracy.

**When declination is unavailable.** It needs the same location the bearing does, so in practice the
two arrive together. If it is unavailable anyway, the heading is displayed as **magnetic** and the
alignment claim is withheld entirely: an uncorrected angle shown beside a true-north bearing invites
exactly the misreading the correction exists to prevent.

### 3.4 Smoothing

`HeadingSmoother` averages on the **unit circle** — the mean of `(cos θ, sin θ)` — because the
arithmetic mean of 359° and 1° is 180°, the exact opposite direction.

The weight is **adaptive**, scaling with how far the sample moved, because a single fixed weight
cannot both kill jitter and track a real movement. A first-order average with weight `w` lags by
`(1 − w)/w` samples; at `w = 0.25` and 10 Hz that is 0.3 s, enough that a user turning to align would
watch the needle trail behind the device at precisely the moment the reading has to be right.

```text
weight = clamp(|sample − previous| / 10°, 0.25, 1)
```

Measured and asserted behaviour: a steady turn at 18°/s tracks with about **2.4°** of lag, and a
steady turn at 100°/s tracks with **none**. There is consequently no separate "snap" rule — full
adoption is the top of the same curve.

---

## 4. Location

### 4.1 Permissions

Salah declares `ACCESS_COARSE_LOCATION` and `ACCESS_FINE_LOCATION`, and **no background location**.

Both are declared together so that the Android 12+ runtime dialog can offer the user the genuine
choice between "Approximate" and "Precise" and have it mean something. Either answer is enough for a
bearing:

| Granted | What changes for the Qibla |
|---|---|
| Approximate only | Essentially nothing. A 2 km position error moves the bearing from Dhaka by about **0.02°**, three orders of magnitude below the compass's own uncertainty. Even 50 km away it moves by only 0.5°. |
| Precise | The better fix, and the better *prayer time* — 2 km can move a prayer time by tens of seconds. |

That asymmetry is the honest reason the Qibla screen does not push for precise location, and the
settings screen says so where the choice is made.

Manual coordinates need **neither** permission. That is what makes manual entry the offline fallback
rather than a convenience.

### 4.2 Acquisition

`AndroidLocationProvider` makes **one** cancellable request through
`androidx.core.location.LocationManagerCompat.getCurrentLocation`, with a 15-second bound.

- **Why `LocationManager` and not the fused provider**: Google Play Services is not a dependency of
  this project and does not need to become one for a single fix. The framework provider needs no
  account, works on devices without Play Services, and is accurate enough that the difference never
  surfaces in a bearing.
- **Why the compat call**: the framework `getCurrentLocation` exists only from API 30, and the pre-30
  alternative — `requestLocationUpdates` plus manual removal — is deprecated. The androidx method
  provides the same one-shot cancellable request from API 14 upwards, so there is a single
  non-deprecated code path with no version branch to get wrong.
- **Why one-shot**: a continuous subscription would keep the GNSS receiver awake to answer a question
  that does not change while the user stands still. The position is stored and reused, which is also
  what keeps the feature working offline.

Provider order depends on the granted precision, because the two situations call for different
trade-offs:

| Precision | Order |
|---|---|
| `PRECISE` | GPS → NETWORK → PASSIVE |
| `APPROXIMATE` | NETWORK → PASSIVE → GPS |

With only approximate location there is no reason to power up the GNSS receiver for a bearing that
barely notices a two-kilometre error, so the high-power provider is the fallback rather than the
first choice. The preference is an *ordering*, not a fixed choice: a device indoors may have no GPS
fix while its network provider works perfectly, and a device with no SIM the reverse.

Every failure is a value with a state and a message — permission denied, location services disabled,
timed out, provider error — so the screen can never be left spinning.

### 4.3 Provenance and freshness

`LocationInfo` records `origin` (`MANUAL_ENTRY` / `DEVICE_GPS`), `accuracyMetres` and `acquiredAt`,
and `LocationFreshness` turns the last of those into a description:

| Freshness | Condition | What the screen says |
|---|---|---|
| `DECLARED` | entered by hand | "Entered by hand" — and **not** stale: an assertion by the user does not decay with time |
| `FRESH` | device fix ≤ 10 min | "Updated just now" |
| `RECENT` | device fix ≤ 24 h | timestamp shown |
| `STALE` | older than 24 h | timestamp shown |
| `UNKNOWN` | device fix with no recorded time | "Saved position, time not recorded" |

`shouldOfferRefresh` is true for the three non-live cases, and the screen adds an explicit note:
*"This is a saved position, not a current reading."*

A timestamp in the future — a device clock that moved backwards, or a provider with a skewed clock —
is treated as `FRESH` rather than as an error: the position is still real, and refusing to use it
would break the feature for a cosmetic reason.

An implausible accuracy (`0`, negative, `NaN`, infinite) is discarded to `null` rather than stored,
because "±0 m" and "±NaN" would both render as a precision claim Salah cannot support.

### 4.4 Storage

The existing DataStore, the existing `LocationRepository`, the existing validation — extended, not
duplicated. No second location database, and no Room (there is no schema to migrate).

**No schema version is needed.** DataStore Preferences is a key–value store and every key added in
this phase is optional, so a store written by an earlier build simply lacks `location_origin` and the
acquisition keys and reads as a manual entry with unknown freshness — which is exactly what such a
store contains. Existing locations are preserved rather than migrated or discarded.

`saveManualLocation` and `saveDeviceLocation` are separate methods rather than one with extra
parameters, because the two differ in what they *mean*: one records an assertion by the user, the
other an observation the platform made at a moment in time. Collapsing them would lose that
distinction at the call site. Metadata is written in the same `edit` transaction as the coordinates,
so a stored location can never be a device fix carrying the previous reading's timestamp, and saving
a manual entry clears any device metadata left behind.

---

## 5. Sensors

### 5.1 Selection

Probed in descending order of quality; the first usable combination wins.

| Order | Sensor | Why |
|---|---|---|
| 1 | `TYPE_ROTATION_VECTOR` | Fused orientation: north-referenced, already tilt-compensated, gyroscope-assisted, so it tracks movement smoothly instead of jumping when the magnetometer is disturbed. |
| 2 | `TYPE_GEOMAGNETIC_ROTATION_VECTOR` | Magnetometer-only rotation vector, appreciably cheaper to run. Used when the fused sensor is absent. |
| 3 | `TYPE_ACCELEROMETER` + `TYPE_MAGNETIC_FIELD` | The classical pair, combined by `getRotationMatrix`. The noisiest option and the last resort, for devices with no rotation vector at all. |

Sensor complement varies enormously across Android hardware, so availability is **probed and
reported**, never assumed. `android.hardware.sensor.compass` and `…sensor.accelerometer` are declared
`required="false"` in the manifest: marking them required would remove the app from those devices
entirely, which is the opposite of the intent.

For the accelerometer/magnetometer pair, gravity is isolated with a low-pass filter
(`alpha = 0.8`) and the heading is emitted from the magnetometer sample only — both sensors run at
the same period, so emitting from each would double the rate for no extra information.

### 5.2 Lifecycle and cost

`CompassSensorSource.readings()` is a **cold** `callbackFlow`: registration happens on collection and
unregistration in `awaitClose`, so the sensor's lifetime *is* the screen's lifetime. There is no
separate start/stop pair to forget and no way for a listener to outlive the UI that wanted it, which
is the easiest way for a compass to drain a battery. The screen drives collection from
`Lifecycle.Event.ON_START`/`ON_STOP`, so a backgrounded screen stops reading the magnetometer, and
stopping clears the last heading so a frozen value is never presented as live.

The requested period is **100 000 µs (10 Hz)**, not `SENSOR_DELAY_UI`, which is roughly six times
faster and would spend battery to produce a needle that looks no steadier. The platform treats it as
a hint and may deliver faster.

No foreground service, no wake lock, no polling loop.

---

## 6. UI states

`QiblaStateReducer` maps the independent facts — location, bearing, sensor, heading, calibration —
onto one phase and one piece of calibration advice. The precedence is decided in one tested place
rather than implied by a chain of `if`s in the layout.

| Phase | When | What the screen shows |
|---|---|---|
| `REQUESTING_LOCATION` | a request is in flight | progress bar; outranks everything else, because it is what the user just did |
| `LOCATION_PERMISSION_DENIED` | permission refused | manual entry offered |
| `LOCATION_UNAVAILABLE` | services off, timed out, provider error | manual entry offered, plus a route to system location settings when that is the cause |
| `INITIAL` | nothing configured, nothing attempted | prompt to choose a location |
| `CALCULATION_ERROR` | a location but no bearing | the specific reason: invalid coordinates, at the Kaaba, or at the antipode |
| `SENSOR_UNAVAILABLE` | no magnetometer-backed sensor | **the bearing is still shown and stated to be exact**; the dial is withheld |
| `AWAITING_HEADING` | bearing known, first sample pending | merging "location available" and "waiting for the compass", which occur milliseconds apart and look identical |
| `CALIBRATION_RECOMMENDED` | live, accuracy reported low | live dial plus a dismissible inline note |
| `COMPASS_OPERATIONAL` | live, accuracy acceptable | live dial, turn, alignment |

Two properties worth stating explicitly:

- **A device with no compass still gets the answer.** The bearing is geography; it needs no sensor,
  no permission and no network. Only the live dial is withheld, with the reason.
- **The dial refuses to move without a heading.** When `isLive` is false it is drawn unrotated with
  the marker hidden. A dial that animated on its own would be the most convincing kind of wrong.

### 6.1 Alignment and the turn

The turn is the **signed shortest** difference between the heading and the bearing, so approaching
the Qibla from 359° reads as "turn right 2°" rather than as nearly a full rotation. Alignment is
claimed within `ALIGNMENT_TOLERANCE_DEGREES` (5°) of the bearing, and only when the orientation is
usable and the heading is on the true-north reference. The dial turns under a fixed pointer — the
whole rose is rotated in a single `rotate` block, so the ticks, cardinals and marker cannot disagree
with each other about the heading.

### 6.2 Calibration

Guidance is derived **only** from the platform's own accuracy signal
(`SENSOR_STATUS_ACCURACY_*`). Salah does not measure field strength against an expected model and
does not compute a "calibration score": a number derived from heading variance would be a guess
presented as a measurement.

- `UNRELIABLE` / `LOW` → calibration suggested, with the figure-eight instruction and a warning about
  magnets, speakers and metal.
- `MEDIUM` / `HIGH` → nothing.
- Accuracy not reported by the sensor → **nothing**. No evidence either way means the screen stays
  quiet.
- No sensor → nothing to calibrate.

`CompassAccuracyGate` discards the reported accuracy until `DEFAULT_WARM_UP_SAMPLES` (10, about one
second at 10 Hz) samples have arrived, because a magnetometer reports `UNRELIABLE` while it is still
settling and advice in that first tenth of a second would be both wrong and irritating.

The note is **inline and dismissible**, never a dialog, and dismissing it stops it driving the phase
so the screen cannot keep nagging. On a device whose sensor reports `UNRELIABLE` permanently without
being calibratable, Salah shows the sensor's own claim rather than second-guessing it — and the
dismissal is the escape hatch.

---

## 7. Accuracy, honestly

### 7.1 What is exact

The **bearing**. It is closed-form spherical trigonometry over stored coordinates, evaluated in
`double` precision, verified against two independent formulations (§2.5). Its error comes only from
the coordinates it is given, and the sensitivity is `positionError / distance` — see the table in
§4.1.

### 7.2 What is not exact

The **dial**, and therefore the alignment claim. Its error is the sum of:

| Source | Typical effect |
|---|---|
| Magnetometer noise and local calibration | 1–5° |
| Nearby magnets, speakers, metal, car interiors | **10–90°**, and the dominant term indoors |
| Device tilt (past 40° the reading is withheld rather than quantified) | unbounded |
| Magnetic-declination model | ≤ 0.5° in most inhabited places; much larger near the magnetic poles |
| Position error | negligible (§4.1) |

So the honest statement, which the screen repeats in its own words, is: **treat the dial as good to
a few degrees outdoors, and much less near large metal objects.** Salah does not claim better.

### 7.3 What is refused

- Publishing a compass accuracy score the platform does not support.
- Presenting a magnetic heading beside a true-north bearing.
- Claiming alignment while the device is tilted past the point where the convention holds.
- Describing a stored position as a current one.
- Any bypass of Do Not Disturb, battery optimisation or a user's settings. Salah has none to bypass:
  the feature is a screen.

---

## 8. Offline operation

- The bearing is computed locally from a locally stored position. No network call exists anywhere in
  the feature, and Salah declares no `INTERNET` permission.
- Declination is evaluated on device from the platform's World Magnetic Model.
- Location acquisition from the device's own provider works without a network (GPS), and manual
  coordinates work with neither network nor permission.
- Bluetooth is not used. No account is required.
- Nothing about the user's position is transmitted anywhere, and **no coordinates are written to the
  diagnostics log** — see §9.

---

## 9. Diagnostics

`DiagnosticsLogger` events added for this feature:

| Event | Level | Contents |
|---|---|---|
| `qibla_location_requested` | info | — |
| `qibla_location_acquired` | info | provider, precision, accuracy in metres |
| `qibla_location_failed` | warn | reason code |
| `qibla_location_source_changed` | info | origin, freshness, accuracy, time-zone id |
| `qibla_sensor_availability` | info | available, family |
| `qibla_sensor_registration_failed` | warn | family |
| `qibla_compass_started` / `_stopped` | info | family, sampling period |
| `qibla_calibration_recommended` | info | accuracy — logged **on transition only** |
| `qibla_bearing_calculated` | info | bearing in degrees, distance in km, origin, whether declination was available |
| `qibla_bearing_failed` | warn | reason, origin |

Never logged: **latitude, longitude**, the location label, or any user content. `qibla_location_source_changed`
deliberately records the origin, freshness and zone rather than the coordinates — enough to diagnose
"it used the wrong location" without recording where the user is.

There is also **no per-sample log**. A sensor sample at 10 Hz is not a diagnostic event; logging one
would be ten lines a second, which is noise rather than evidence. Only transitions are recorded.

---

## 10. Repository layout

```text
core/domain/qibla/
  KaabaReference.kt            coordinates, source, and the minimum separation
  QiblaBearing.kt              Available / Unavailable + the three refusal reasons
  QiblaCalculator.kt           the bearing and the great-circle distance
  HeadingMath.kt               MagneticHeading / TrueHeading, normalization, signed turn
  HeadingSmoother.kt           circular averaging with an adaptive weight
  SensorFrames.kt              screen rotation → reference frame
  CompassSensor.kt             CompassSensorSource / CompassReading / CompassAvailability
  CompassAccuracyGate.kt       warm-up in front of the calibration advice
  GeomagneticDeclination.kt    declination port
  LocationAcquisition.kt       LocationProvider / permission / precision ports
  LocationProviderSelection.kt provider ordering by precision
  QiblaState.kt                phases, calibration advice, the reducer
core/domain/location/
  LocationFreshness.kt         DECLARED / FRESH / RECENT / STALE / UNKNOWN

data/qibla/
  AndroidCompassSensorSource.kt        SensorManager, three families, remap, tilt
  AndroidGeomagneticDeclinationProvider.kt  GeomagneticField
  AndroidLocationProvider.kt           LocationManagerCompat, one-shot
  AndroidLocationPermissionChecker.kt  permission state

feature/qibla/
  QiblaViewModel.kt            three state holders: location, heading, screen
  QiblaUiState.kt              what the screen renders
  QiblaScreen.kt               layout
  QiblaLabels.kt               domain → resource mappings
  components/QiblaCompassDial.kt  the Canvas dial

feature/common/LocationFormState.kt   shared with Settings — one set of validation rules
```

The sensors and the location service sit behind ports, so the state machine, the arithmetic and the
screen's decisions are all unit-tested with fakes and only the thin adapters need a device.

---

## 11. Automated test coverage

112 tests across 9 classes:

| Class | Tests | Covers |
|---|---|---|
| `QiblaCalculatorTest` | 23 | Sanity cases, two independent formulations, the frozen city table, range and wrap, invalid input, the poles, the Kaaba point, the antipode, distances |
| `HeadingMathTest` | 14 | Normalization and non-finite folding, signed shortest difference and its antisymmetry, the ±180 convention, declination applied once, the turn being declination-independent, dial rotation |
| `HeadingSmootherTest` | 11 | Exact first sample, wrap behaviour, adoption threshold, partial adoption, steady-turn lag bounds, reset, jitter reduction |
| `QiblaStateReducerTest` | 19 | Every phase, the precedence between them, every calibration advice, exhaustiveness over the accuracy values |
| `DataStoreLocationRepositoryTest` | 11 | Manual and device round trips, metadata cleared on manual save, implausible accuracy discarded, recreation, clearing, pre-origin stores, foreign origins, partial writes |
| `LocationFreshnessTest` | 11 | Declared versus aged, both window boundaries, future timestamps, refresh eligibility |
| `SensorFramesTest` | 8 | All four rotations, the quarter-turn cycle, no repeated axis, distinctness, coverage of the in-plane axes |
| `CompassAccuracyGateTest` | 7 | Warm-up suppression, pass-through unaltered, reset, sample counting |
| `LocationProviderSelectionTest` | 8 | Both orderings, full provider coverage, first-enabled selection, no-provider case, the orderings differing |

Run by `.github/workflows/android-apk.yml` on every push to `main`, before lint and before the debug
APK is assembled.

### 11.1 What is verified, and what is not

**Verified by automated tests, in CI:** the bearing and its refusals, the heading arithmetic and the
no-double-correction property, the rotation frames, the smoothing timing, the accuracy gate, the
state machine, provider selection, location freshness, and real DataStore persistence.

**Not verified by any automated test — device behaviour:**

| Behaviour | Why it needs a device |
|---|---|
| `SensorManager` delivering real readings, and the chosen family | Hardware sensors are not present on the JVM, and `SensorManager` is stubbed in unit tests |
| The three-family fallback actually selecting on a device lacking a rotation vector | Requires that hardware |
| `remapCoordinateSystem` + `getOrientation` producing the expected azimuth at each physical rotation | The mapping is unit-tested; the platform's behaviour under it is not |
| `GeomagneticField` declination values | The model lives in the framework |
| `LocationManagerCompat.getCurrentLocation` returning a fix, and the timeout path | Requires a location provider |
| The permission dialog offering Approximate / Precise on Android 12+ | Requires the platform UI |
| `LifecycleEventEffect` starting and stopping the sensor | Requires a real lifecycle |

The manual checklist for these is in `docs/QIBLA_COMPASS_TESTING.md`. **It has not been performed**,
and nothing in this document should be read as a claim that it has been.

---

## 12. Manual device-testing instructions

See `docs/QIBLA_COMPASS_TESTING.md`. In outline:

1. Set a location, confirm the bearing matches an independent calculator for that city.
2. Confirm the dial rotates with the device and that the turn is correct on all four sides.
3. Rotate the screen to landscape and confirm the heading does not shift by 90°.
4. Tilt the device past 40° and confirm the dial and turn are withheld with the hold-flat message.
5. Grant only approximate location and confirm a bearing is still produced.
6. Deny location permission and confirm manual entry still yields a bearing.
7. Hold the device near a magnet and confirm the low-accuracy note appears, and that dismissing it
   sticks.
8. Verify in aeroplane mode that everything above still works from the stored location.

---

## 13. Known limitations

- **Indoor accuracy.** The dominant error is local magnetic interference, which no software can
  correct. The screen says so rather than implying otherwise.
- **The flat convention.** Holding the device upright makes the projected heading unstable; Salah
  withholds the reading past 40° of tilt rather than switching convention, because silently changing
  convention mid-reading would be worse than asking the user to hold it flat.
- **Declination near the poles.** The World Magnetic Model's absolute error grows steeply there, so
  a corrected heading is less certain than the same calculation at lower latitudes. The bearing
  itself is unaffected — it is geographic.
- **A permanently-unreliable accuracy report.** A device whose sensor reports `UNRELIABLE` without
  being calibratable will show the calibration note until the user dismisses it. Salah reports the
  sensor's own claim rather than overriding it.
- **No Qibla map.** Deliberately: it would require a network or a large bundled asset, and the
  bearing and dial together answer the question.
