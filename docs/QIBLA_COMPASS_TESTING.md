# Salah — Qibla Compass: Tests and Manual Verification

Two clearly separated things live here:

- **§1–§3: automated tests** that run in CI on every push to `main` and actually pass. These are
  evidence.
- **§4: a manual checklist** for the behaviour that cannot be exercised without a physical Android
  device. **None of it has been performed.** Nothing in §4 is a claim that it passed.

Read that distinction literally. "Verified" below means "an automated test asserts it". It never
means "someone watched it happen on a phone".

---

## 1. How the automated tests run

```bash
./gradlew testDebugUnitTest
```

Run by `.github/workflows/android-apk.yml` on a GitHub-hosted Ubuntu runner with Temurin JDK 17,
before lint and before the debug APK is assembled. A failing test fails the workflow, so a green run
is what makes the remote APK meaningful.

Plain JVM unit tests: no emulator, no device, no Robolectric. That constraint is deliberate — see
§3 — and it is precisely why the sensor and location adapters are thin.

---

## 2. What is covered

112 of the 303 unit tests in the repository cover this feature.

| Test class | Tests | Covers |
|---|---|---|
| `QiblaCalculatorTest` | 23 | The bearing, its refusals, and independent verification |
| `QiblaStateReducerTest` | 19 | The screen's state machine and calibration policy |
| `HeadingMathTest` | 14 | Angle arithmetic and the north-reference discipline |
| `DataStoreLocationRepositoryTest` | 11 | Real persistence of coordinates and their provenance |
| `HeadingSmootherTest` | 11 | Jitter suppression without lag through a real turn |
| `LocationFreshnessTest` | 11 | Declared versus aged, and what a saved position may be called |
| `SensorFramesTest` | 8 | The screen-rotation reference frame |
| `LocationProviderSelectionTest` | 8 | Provider ordering by granted precision |
| `CompassAccuracyGateTest` | 7 | Restraint before the calibration advice |
| **Total** | **112** | |

### 2.1 The bearing — `QiblaCalculatorTest`

The expected values are **not** produced by the production function. Two unrelated formulations are
implemented inside the test file and every assertion is made against those:

- `independentTangentBearing` — projects the chord to the Kaaba onto the local east/north tangent
  plane. 3-D vector algebra sharing no expression with the production `atan2` formula.
- `independentLawOfCosinesBearing` — the classical navigation derivation, with the quadrant taken
  from the sign of the longitude difference.

| Requirement | Test |
|---|---|
| Independently verified values | `agrees with an independent tangent-plane projection across the globe` (a 15° × 30° grid), `agrees with an independent law-of-cosines derivation across the globe` |
| Human-checkable sanity | `due south of the Kaaba on the same meridian reads due north`, `due west of the Kaaba at its own latitude reads slightly north of due east` (the naive "due east" answer is wrong, because a great circle is not a parallel of latitude) |
| A frozen regression table | `cross-checked reference bearings` — 13 cities, each also confirmed by both independent derivations |
| Normalization | `every result is inside the half-open range zero inclusive to 360 exclusive`, `normalizeDegrees folds negatives, multiples of a full turn and negative zero` |
| Longitude wraparound | `the antimeridian is the same place from either side`, `signed longitude delta takes the short way round`, `points either side of the antimeridian are continuous` |
| Invalid latitude and longitude | `latitude outside the valid range is rejected, not clamped`, `longitude outside the valid range is rejected, not clamped` |
| Floating-point edge cases | `non-finite coordinates are rejected` (NaN and ±infinity in both components), `the poles are valid coordinates` |
| Locations near the Kaaba | `standing on the Kaaba reference point has no direction`, `standing a few centimetres away still has a direction`, `the Kaaba test uses distance, so it does not depend on the sign of the offset` |
| Longitude wrapping at the antipode | `the antipode of the Kaaba is reported as undefined rather than as due north`, `a point well away from the antipode still gets a bearing` |
| Distance | `distance to the Kaaba matches the great-circle separation` (checked against a chord-length derivation), `distance to the Kaaba from the Kaaba is zero`, `the reported distance is the one the bearing was accepted for` |

**A defect this caught.** The first draft of the independent tangent-plane implementation was itself
wrong — it built the tangent at the destination using the wrong argument order. It produced answers
exactly 180° off for most of the globe while agreeing with the production code for a few cities,
which is the signature of a real bug rather than a tolerance problem. Because a second, unrelated
formulation was also present and disagreed with it, the test file identified which of the two was
wrong. Keeping one alternative implementation would not have.

### 2.2 Heading and north reference — `HeadingMathTest`

| Requirement | Test |
|---|---|
| Heading normalization | `normalize folds any angle into zero inclusive to 360 exclusive`, `normalize folds not-a-number to zero rather than propagating it`, `normalize keeps positive and negative infinity inside the range`, `the heading builders normalize what they are given` |
| Signed shortest difference | `the signed difference takes the short way around the circle` (359° → 1° is +2, not −358), `the signed difference is within the half-open interval from minus 180 to 180`, `a half turn is reported as positive 180 whichever way it is approached`, `the signed difference is antisymmetric` |
| Declination applied once | `true heading is the magnetic heading plus declination, applied once`, `applying declination twice is not expressible` |
| **Prevention of double correction** | `the turn to the Qibla is independent of declination once both angles are true` — asserted with a non-zero declination, because at zero the bug would hide |
| The turn itself | `the turn equals the signed difference between the true heading and the bearing`, `the turn wraps correctly when the Qibla is just past north` |
| Dial rotation | `the dial rotates by the negative of the heading` |

### 2.3 Smoothing — `HeadingSmootherTest`

| Requirement | Test |
|---|---|
| No filtering without history | `the first sample is adopted exactly` |
| Steady input is untouched | `an unchanging heading passes through unchanged` |
| **Wraparound** | `smoothing across the wrap stays near the wrap instead of flipping to the opposite side`, `repeated samples either side of the wrap converge to the wrap` |
| No lag through a real turn | `a movement at or beyond the full-adoption threshold is adopted exactly`, `a fast rotation is tracked with essentially no lag` |
| Bounded lag while turning | `a slow steady rotation is tracked with bounded lag` — asserted to be between 0.5° and 3.5°, so the test fails both if smoothing stops working *and* if it starts lagging |
| Partial adoption for small movement | `a movement inside the threshold is partly adopted` |
| Jitter suppression | `sample-to-sample jitter is reduced` |
| Lifecycle | `reset forgets the history so the next sample is adopted unfiltered` |
| Robustness | `a non-finite sample is folded rather than poisoning the filter` |

The numeric behaviour those assertions pin was computed independently before the tests were written:
a steady turn at 18°/s tracks with 2.44° of lag, a steady turn at 100°/s with none, and ±2° of jitter
is reduced to a 0.76° spread.

### 2.4 Screen-rotation frames — `SensorFramesTest`

| Requirement | Test |
|---|---|
| The documented mapping | `a screen rotated 90 degrees reports the axis the documentation specifies` — `AXIS_Y` / `AXIS_MINUS_X`, the mapping Android gives for using a device as a mechanical compass |
| All four rotations | `the natural orientation uses the device axes unchanged`, `a screen rotated 180 degrees mirrors both axes`, `a screen rotated 270 degrees completes the cycle` |
| The cycle, not just the literals | `each quarter turn advances both axes one step round the cycle` — catches an edit that gets one rotation right and the next wrong |
| No invalid frame | `no frame asks for the same axis twice` — `remapCoordinateSystem` returns `false` and leaves the output untouched in that case, which would silently keep the unrotated frame |
| Distinctness and coverage | `all four frames are distinct`, `every in-plane axis is used as the forward axis exactly once` |

### 2.5 State machine and calibration — `QiblaStateReducerTest`

| Requirement | Test |
|---|---|
| Requesting outranks everything | `an in-flight request outranks every other fact` |
| Every phase | `no location and no attempt is the initial state`, `refused permission is reported as such`, `a failed acquisition is reported as unavailable`, `a location with no bearing is a calculation error`, `a device without a compass shows the bearing and says the dial cannot turn`, `a bearing with no heading yet is awaiting the compass`, `everything present and trustworthy is the operational phase` |
| Precedence | `awaiting the heading outranks a calibration suggestion`, `a missing sensor outranks a calibration suggestion`, `a missing location outranks everything except an in-flight request` |
| Every refusal reason reaches the screen | `a location with no bearing is a calculation error` (all three reasons) |
| No invented calibration score | `a sensor that does not report accuracy gets no advice`, `an accuracy that has not arrived yet gets no advice` |
| Advice policy | `unreliable and low accuracy ask for calibration`, `medium and high accuracy need nothing`, `no sensor means there is nothing to calibrate` |
| Exhaustiveness | `every accuracy value produces a defined piece of advice` |

### 2.6 The warm-up gate — `CompassAccuracyGateTest`

| Requirement | Test |
|---|---|
| No premature nagging | `nothing is reported while the sensor is still warming up` |
| Restraint ends | `the value is passed through once the warm-up completes` |
| Never altered | `the reported accuracy is never altered` |
| Lifecycle | `reset restarts the warm-up`, `the sample count tracks what has been seen` |
| Configuration | `the default warm-up is about a second of the compass sampling rate`, `a gate with no warm-up reports immediately` |

### 2.7 Location acquisition policy — `LocationProviderSelectionTest`

| Requirement | Test |
|---|---|
| Precision-dependent order | `precise location asks the GNSS receiver first`, `approximate location asks the cheapest provider first` |
| All providers reachable | `every precision offers every provider exactly once` |
| Fallback | `the best enabled provider is the first candidate that is switched on`, `a device with only the last resort available still gets a provider` |
| No provider is a state, not a guess | `no enabled provider is reported as nothing rather than guessed at` |
| The decision itself is guarded | `the two orderings prefer different providers` |

### 2.8 Location provenance — `LocationFreshnessTest` and `DataStoreLocationRepositoryTest`

| Requirement | Test |
|---|---|
| A manual entry never goes stale | `a manually entered location is declared, not aged` (one year later), `a manual entry with no timestamp is still declared` |
| A device fix with no time cannot be aged | `a device fix with no recorded time cannot be aged` |
| Both window boundaries | `the fresh window boundary is inclusive`, `the recent window boundary is inclusive` |
| Age buckets | `a device fix from the last ten minutes is fresh`, `a device fix from the last day is recent`, `an older device fix is stale` |
| Clock skew | `a timestamp in the future is treated as just taken, not as an error` |
| Refresh is offered only where honest | `only ages that are not a live reading offer a refresh` |
| Documented thresholds | `the thresholds are the documented ones` |
| Real persistence | `a manually entered location round trips`, `a device fix round trips with its origin, accuracy and time`, `the location survives a repository recreation` |
| Validation | `invalid input is rejected and nothing is written`, `an implausible accuracy is discarded rather than stored`, `a partially written location reads as no location rather than as a bad one` |
| Metadata is not inherited | `a manual entry clears device metadata left by an earlier fix` |
| Existing data is preserved | `a store written before origins were recorded reads as a manual entry`, `an unrecognised stored origin falls back to a manual entry` |
| No second store | `clearing removes the location and leaves other preferences alone` |

---

## 3. Why the tests look the way they do

**Deterministic and hardware-free.** Every test runs on the JVM with no sensor, no location provider
and no display. That is possible because the arithmetic, the state machine, the provider policy and
the frames are all pure, and the Android adapters are thin enough to be reviewed rather than tested.

**No expected values from the code under test.** The bearing is checked against two independently
implemented formulations (§2.1), not against a recorded output of the function being verified. That
is what turned up the defect in the first attempt.

**Behaviour, not call counts.** `DataStoreLocationRepositoryTest` uses the real DataStore on a
temporary file, so it asserts what is actually on disk rather than that a method was called.

**No test was written to match a wrong result.** Where an assertion failed during development, the
production code or the test's own model was fixed and the reason recorded — the smoothing weight
became adaptive because a fixed weight could not satisfy the lag bound, and the tangent-plane test
helper was corrected. Neither change was made to accommodate a failing expectation.

---

## 4. Manual verification checklist

**Status: not performed.** Every item needs a physical Android device (or an emulator with sensors)
and a human observing behaviour. These are instructions, not results. Do not read a
checked-looking item as a verified one.

Install a debug APK from a green workflow run, set a location, and open **Qibla** from the Home
screens' Islamic tools.

### 4.1 The mathematics on a real device

| # | Scenario | Steps | Expected |
|---|---|---|---|
| M1 | Bearing against an independent calculator | Set a location for a city with a published Qibla direction | The displayed bearing matches an independent calculator for those coordinates, to a tenth of a degree. Cross-check the city against the frozen table in `QiblaCalculatorTest` |
| M2 | Bearing follows the location | Change the location to a different continent | Bearing and distance both update; no stale value survives |
| M3 | Manual coordinates | Enter the Kaaba's own coordinates | A calculation-error state with the "you are at the Kaaba" explanation — **not** a bearing of 0° |

### 4.2 The compass

| # | Scenario | Steps | Expected |
|---|---|---|---|
| C1 | Dial tracks the device | Hold the device flat and rotate slowly through a full turn | The dial rotates smoothly in the opposite sense to the device; the turn value decreases toward zero as you come onto the bearing |
| C2 | Alignment | Rotate until the turn reaches zero | "Facing the Qibla" appears within 5° with "Within 5°" under it, and a green ring appears inside the dial's gold band |
| C3 | Wraparound | Approach the bearing from below 0° | The turn reads a few degrees, **not** several hundred — the case the signed shortest difference exists for |
| C4 | Screen rotation | With the device flat, rotate the screen to landscape | The heading must **not** shift by 90°. This is the `SensorFrames` mapping; a 90° jump here means the frame is wrong |
| C5 | Tilt | Tilt the device toward vertical | Past about 40° the turn is replaced by the bearing with its reference frame, a bold hold-flat warning appears under the readings, and the dial's arrow is drawn unrotated rather than following the unusable heading |
| C6 | Metal and magnets | Bring the device near a speaker, a magnet or a car dashboard | The heading deviates. If accuracy drops to low, the calibration note appears |
| C7 | Calibration | Follow the figure-eight instruction | The accuracy improves and the note stops appearing. Dismiss it and confirm it stays dismissed for the session |
| C8 | Sensor absence | Run on a device with no magnetometer, if one is available | The status reads "No compass sensor"; the bearing is still displayed and stated to be exact; the dial does not animate |
| C9 | Sensor lifecycle | Background the app with `adb shell dumpsys sensorservice` open, then foreground it | The sensor is unregistered on stop and re-registered on start |

### 4.3 Location

| # | Scenario | Steps | Expected |
|---|---|---|---|
| L1 | Precise permission | Tap **Allow location access** and choose "Precise" | A fix arrives; the position's label and source appear on the heading line and its coordinates, age and accuracy in the Location card; the sensor card names the chosen family |
| L2 | Approximate permission | Revoke location, then tap and choose "Approximate" | A fix arrives and a bearing is produced; the approximate-location note is shown; the bearing is within a fraction of a degree of the precise result |
| L3 | Permission denied | Revoke location and tap again, denying | The permission-denied state with the manual-entry route; entering coordinates still yields a bearing |
| L4 | Location services off | Turn off device location entirely, then tap | The location-unavailable state with a route to system location settings |
| L5 | Timeout | Request a fix indoors, away from windows, and wait | After about 15 seconds, the timeout message — **not** an indefinite spinner |
| L6 | Freshness | Take a fix, leave the app for several hours, return | The position reads as a saved position with its timestamp, and the refresh prompt appears |
| L7 | Manual entry validation | Enter latitude 91, then a blank name, then an invalid time zone | Each is rejected with the validation message; nothing is stored |
| L8 | Manual entry survives restart | Save coordinates, force-stop, relaunch | The same coordinates and bearing |

### 4.4 Offline and platform limits

| # | Scenario | Steps | Expected |
|---|---|---|---|
| O1 | Offline | Enable aeroplane mode with a stored location | The bearing, dial, turn and calibration guidance all still work. Salah declares no network permission |
| O2 | Offline manual entry | In aeroplane mode with location denied, enter coordinates | A bearing is produced |
| O3 | Nothing is transmitted | Inspect the log with `adb logcat -s Salah` while using the screen | No latitude or longitude appears in any line. Only origin, freshness, accuracy, zone and reason codes |
| O4 | No per-sample logging | Same log inspection while holding the device still | No `qibla_compass_sample` style line; only start, stop and transition events |

---

## 5. What is verified, and what is not

**Verified by automated tests, in CI, on this commit:** the bearing and all four of its refusals,
normalization and wraparound, the great-circle distance, the heading arithmetic, the
no-double-correction property, smoothing timing, the screen-rotation frames, the accuracy gate, the
full state machine, provider ordering, location freshness, and real DataStore persistence.

**Verified by CI only as a build, not as behaviour:** the debug APK compiles, passes lint and uploads
its artifact.

**Not verified at all, and requiring a device:** every item in §4 — in particular `SensorManager`
delivering readings, the three-family fallback selecting on real hardware, `remapCoordinateSystem`
and `getOrientation` producing the expected azimuth under an actual rotation, `GeomagneticField`
declination values, a real location fix, the Android 12+ approximate/precise dialog, and the
lifecycle start/stop of the sensor.
