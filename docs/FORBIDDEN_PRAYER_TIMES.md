# Salah — Prohibited Prayer Times

This document describes the prohibited-time intervals Salah displays: the solar events they are
anchored to, the exact boundaries of each interval, the convention behind those boundaries, how a
window's status is determined, and what is shown when the data needed to calculate one is missing.

It separates three things that are easy to confuse, and keeps them separate in the code as well:

1. **the astronomical event** — sunrise, the solar transit, sunset — calculated by the engine;
2. **the prohibited interval** around that event — a policy of this application, expressed as
   explicit parameters;
3. **the prayer period** that contains the event — a different thing entirely, described in
   `docs/CURRENT_PRAYER_STATUS.md`.

---

## 1. What is displayed

The Home screen's **Prohibited prayer times** card lists one row per configured interval:

| Field | Meaning |
|---|---|
| Interval name | which event the interval belongs to |
| Start – end | the interval's own boundaries, in the prayer location's zone |
| Anchor event | the solar event's own time, shown separately from the interval |
| Status | `Active now`, `Upcoming`, or `Passed today` |
| Countdown | time until it opens (upcoming) or until it closes (active) |
| Boundaries | the configured minutes behind it, read from the rule set that produced it |
| Convention | which policy is in force, and whether anyone has confirmed it |

The anchor event is shown next to the interval rather than merged into it, because for the zenith
interval the two are different instants: the interval is anchored to the **solar transit**, which is
not the displayed Dhuhr prayer time (see §3).

---

## 2. The intervals and their convention

An interval is never calculated from constants hidden in the calculation path. Every boundary is an
explicit field of a `ProhibitedRuleSet`, and the rule set the application ships with is defined in
`core/domain/prohibited/ConfiguredProhibitedRuleSet.kt`:

| Kind | Anchored to | Opens | Closes |
|---|---|---|---|
| `SUNRISE_ASCENT` | sunrise | sunrise | 15 minutes after sunrise |
| `ZENITH_DECLINE` | solar transit (solar noon) | 5 minutes before the transit | 10 minutes after the transit |
| `SUNSET_SETTING` | sunset | 15 minutes before sunset | sunset |

Two of these need stating plainly:

- **The durations are not derived from one another.** They are three independent parameters. The
  sunrise and sunset intervals happen to share a magnitude, which is a coincidence of this policy,
  not a rule; either can be changed without moving the other.
- **The five minutes before the transit are a margin, not a prohibition.** The described prohibition
  concerns the sun standing at its meridian and then declining. The lead-in exists because the
  transit instant is a calculation and the sun is effectively stationary around it; it is not a claim
  that the time before the meridian is itself prohibited.

### Verification state: configured, not reviewed

`RuleVerificationStatus` has three values, and this rule set is **`CONFIGURED`**:

- `UNREVIEWED` — never applied. Filtered out before the calculator sees it.
- `CONFIGURED` — the application's own documented policy. Applied, and disclosed as such: the card
  states that the boundaries are Salah's policy and have **not** been confirmed by a qualified
  reviewer.
- `REVIEWED` — confirmed by a qualified reviewer, with the references recorded on the rule set.

`ConfiguredProhibitedRuleSet.DEFAULT.sources` is **empty**, because no reference for these minute
values has been verified in this repository. The card says so rather than implying a citation, and
`ProhibitedConvention.hasVerifiedSources` carries that fact from the rule set to the screen.

**No religious source text is quoted anywhere in this feature.** The wording describes the
application's convention; it does not present a ruling, and it does not claim the boundaries are
universally agreed. Where scholarly conventions differ, this document records that they differ and
does not pick a winner on their behalf.

### Replacing the policy

Supplying a reviewed rule set is a data change: construct a `ProhibitedRuleSet` with
`RuleVerificationStatus.REVIEWED`, its boundaries and its sources, and put it first in the list
passed to `ProhibitedRuleBook`. Nothing in the calculator, the repository, the view model or the UI
changes, and no prayer time can move as a result — the calculator consumes an already-computed
schedule and never influences one.

---

## 3. Anchors are solar events, not prayers

`AdhanPrayerTimesProvider` makes two calls against the same coordinates and date: one with the
selected method's adjustments for the five prayers, and one with every adjustment zeroed for the
raw solar events. Sunrise, the solar transit and sunset therefore carry no method offset, which is
why the intervals are anchored to them and not to Fajr, Dhuhr or Maghrib.

Concretely, for Dhaka on 11 October 2026:

| Instant | Value |
|---|---|
| Solar transit (`SOLAR_NOON`) | 11:45 |
| Dhuhr prayer time (Karachi method) | 11:46 |

They differ, and treat them as if they did not: the interval is `[11:40, 11:55)`, anchored to 11:45.
Changing the calculation method moves the prayer time and leaves the interval exactly where it was.
`ProhibitedPeriodStatusResolverTest` asserts this with a deliberately shifted Dhuhr.

**How solar noon is obtained.** It is the engine's solar transit for the coordinates and local date,
requested with zero adjustments — not `Dhuhr ± n`. No meridian is computed by Salah itself; there is
one astronomical implementation in the project and this feature consumes it.

---

## 4. Status and boundaries

`ProhibitedPeriodStatusResolver` (`core/domain/prohibited/ProhibitedPeriodStatusResolver.kt`) is pure
arithmetic over the windows the calculator produced. It reads no clock and moves no boundary.

Every interval is half-open, `[startsAt, endsAt)` — the same convention the prayer periods use, so a
boundary instant means the same thing in both features:

| Moment | Status |
|---|---|
| before `startsAt` | `UPCOMING`, with `startsIn` = time until it opens |
| at `startsAt` | `ACTIVE`, with `endsIn` = time until it closes |
| inside the interval | `ACTIVE`, with `endsIn` |
| at `endsAt` | `PASSED` |
| after `endsAt` | `PASSED` — for the rest of that local date |

The three intervals never overlap, so at most one can be `ACTIVE` at any moment; the test suite
checks every minute of the local day for both overlap and for any minute left unclassified.

**Crossing midnight.** None of the three intervals crosses midnight: each is anchored to a daytime
solar event and is bounded by minutes around it. The arithmetic is written against absolute instants
anyway, so a rule set that produced such a window would still be classified correctly.

**A new local date.** Windows are recalculated from that date's schedule. The previous day's windows
are never reused: read against today's moments they are simply `PASSED`, and the test suite asserts
that rather than trusting it.

### Unavailable

A prohibited interval is never shown with invented times. The calculator **refuses** when it cannot
anchor a window — a schedule that is missing a solar event, or that does not contain all five
prayers — because returning a shorter list would silently under-report a prohibition.
`RuleBookProhibitedPeriodsRepository` converts that refusal into
`ProhibitedPeriodsState.NotAvailable`, which the card renders as an explicit explanation.

Unavailability is therefore reported **for the section, not per interval**. This is deliberate: the
anchor data exists or it does not, and reporting "this one interval is unavailable while the other
two happened to calculate" would misrepresent a partial calculation as a complete one. The status
list required by the feature — active, upcoming, passed, unavailable — is complete across the two
levels: three per-interval phases plus this explicit section-level state.

Also reported as unavailable: no location configured, a schedule that has not been produced yet, and
a rule book with nothing applicable.

---

## 5. Location, date and time zone

- Windows are calculated from the **saved location** — the same `LocationInfo` the prayer times come
  from, whether it was read from the device or selected as an administrative area. The Bangladesh
  location policy and its validation are untouched.
- Windows are expressed as `ZonedDateTime` in the **schedule's zone** (`Asia/Dhaka` for a Bangladesh
  location), and the status resolver converts the moment into that zone before comparing. A device
  in another time zone therefore reads the same status for the same absolute instant.
- The local date used is the one **at the location**, matching the date the timetable was calculated
  for.
- Nothing in this feature requests location permission, performs I/O, or reaches the network. It is
  offline-first like the rest of the timetable.
- Changing the location re-derives the windows, because they are derived from the schedule, and the
  schedule is recalculated for the new location.

---

## 6. Tests

`app/src/test/java/io/github/towkirahmedrony/salah/core/domain/prohibited/` contains:

- `ProhibitedPeriodCalculatorTest` — the structure of the calculation: anchors, ordering, the
  schedule's zone, refusal on an inapplicable rule set, and a refusal to drop a window whose anchor
  is missing. Its rule set is synthetic and is not a religious source.
- `ProhibitedPeriodStatusResolverTest` — the boundaries the application actually ships, before/at/
  during/after each interval, the status of all three at one moment, the transit-versus-Dhuhr
  distinction, the whole-day no-overlap sweep, that no interval crosses midnight, that yesterday's
  windows do not classify as today's, a second Bangladesh coordinate, and every missing-input case.
- `RuleBookProhibitedPeriodsRepositoryTest` — the rule book's behaviour: nothing applicable →
  `RULES_NOT_VERIFIED`; the shipped policy → three windows plus the disclosure that they are not
  reviewed and carry no verified sources; a reviewed set taking precedence; and a schedule missing an
  anchor → `NotAvailable` rather than two windows.

### Running them in a normal development environment

```
./gradlew :app:testDebugUnitTest --tests "*.ProhibitedPeriodStatusResolverTest"
./gradlew :app:testDebugUnitTest --tests "*.ProhibitedPeriodCalculatorTest"
./gradlew :app:testDebugUnitTest --tests "*.RuleBookProhibitedPeriodsRepositoryTest"
```

In this task's environment Gradle is **not** run locally; CI
(`.github/workflows/android-apk.yml`) runs `testDebugUnitTest`, `lintDebug` and `assembleDebug`.

---

## 7. Known limitations and unresolved policy

- **The minute values are not verified against a cited source.** They are the application's
  configured policy. Rationale for the values is given in §2; no reference supporting them has been
  checked in this repository, and `sources` is empty to say so.
- **Scholarly conventions differ**, both in the length of each interval and in which prayer
  categories are affected. Salah applies one documented convention and displays it as its own; it
  does not adjudicate between conventions and must not be read as doing so.
- **No ruling is encoded.** The feature reports intervals; it does not say which prayers may or may
  not be offered in them, and it does not model the exceptions that some conventions allow.
- **The lead-in before the transit is a margin** (§2). If a reviewer rejects it, set
  `zenithBeforeMinutes = 0`; the window then opens exactly at the transit and the arithmetic is
  unchanged.
- **Intervals are not the prayer periods they sit near.** The sunrise interval is not the Fajr
  period, and the sunset interval is not the Maghrib-to-Isha period. The card states this, and
  `ProhibitedPeriodStatusResolverTest` asserts it.
- **No religious source text** — Quran, dua or hadith — has been added to this feature, by design.
