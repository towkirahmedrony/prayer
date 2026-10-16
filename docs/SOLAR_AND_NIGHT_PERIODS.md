# Salah — Solar Events and Night Periods

This document describes the day's solar geometry and the night-period calculations Salah derives
from the timetable it already shows: the exact points each value comes from, the definitions used,
how unavailable results are reported, how the results are cached and invalidated, and what remains
unresolved. It is the companion to `docs/FORBIDDEN_PRAYER_TIMES.md` (the prohibited-time intervals)
and `docs/PRAYER_CALCULATION.md` (the engine itself).

It exists to keep four things distinct, in the code as well as in the prose:

1. **an astronomical event** — sunrise, the solar transit, sunset — produced by the engine;
2. **the astronomical night** — the complement of the day length, sunset to sunrise;
3. **the night interval** — effective Maghrib to the following effective Fajr, a *prayer-derived*
   span used for Islamic midnight, the last third and Tahajjud;
4. **a prohibited interval** — a policy around an event, described in its own document.

None of these is interchangeable with civil midnight, and none of them is a ruling.

---

## 1. Where the values come from

Every instant in this feature is read from the schedule the existing engine already produced.
There is **no second astronomical engine** and **no new astronomy**: `AdhanPrayerTimesProvider`
computes each day's offset-free sunrise, solar transit and sunset, and the components below only
read those instants and the **effective** prayer times (Maghrib and Fajr) of the same schedule.

| Component | Input | What it does |
|---|---|---|
| `core/domain/solar/SolarDayResolver` | today's `PrayerSchedule` | reads solar noon, sunrise, sunset; derives solar midnight and the day/night lengths |
| `core/domain/night/NightPeriodCalculator` | today's and the following day's `PrayerSchedule` | reads effective Maghrib and the following day's effective Fajr; derives Islamic midnight, the last third, Tahajjud and the phase |
| `core/domain/prohibited/ProhibitedPeriodStatusResolver` | the day's windows | classifies them and selects the active/next one |

Both resolvers are pure functions of their inputs and read **no clock**. The moment is passed in by
`HomeUiStateFactory`, which is what lets a whole screen be replayed at any instant in a test. Neither
component influences a prayer time: they consume a schedule and never produce one.

Because the values are derived from the schedule, they are covered by the schedule's own cache: the
repository keys an entry by the local date, the resolved location, and the full effective
calculation profile, and any change to those invalidates it. There is therefore no second cache to
keep in step, and no risk of a derived value outliving the timetable it came from.

---

## 2. Solar events

| Value | Definition | Source |
|---|---|---|
| Sunrise | the sun's upper limb crossing the horizon at dawn | engine, offset-free |
| Sunset | the same at dusk | engine, offset-free |
| Solar noon (solar transit) | the instant the sun is highest | engine, offset-free |
| Solar midnight | the anti-transit, i.e. the instant the sun is lowest | derived: solar noon + 12h |
| Day length | sunset − sunrise | measured |
| Night length | 24h − day length | astronomical complement |

### Solar noon is not clock noon, and not Dhuhr

The solar transit is the engine's own value for the coordinates and date, requested with **zero**
method and prayer adjustments. On 11 October 2026 in Dhaka it is 11:45, while the displayed Dhuhr
prayer time is 11:46 — the difference is the calculation method's offset, and it must not feed the
solar geometry. A method change therefore cannot move any solar value here. The same distinction is
what the prohibited windows rely on (`docs/FORBIDDEN_PRAYER_TIMES.md` §3).

### Solar midnight — definition and honest precision

Solar midnight is the **anti-transit**: the instant of lower culmination, half a solar day from the
transit. Salah computes it as the transit shifted by exactly twelve hours. The sun's declination
changes across the half-day, so this is an approximation of the true nadir, accurate to about a
minute at Bangladesh's latitudes; Salah does not solve for the true lower culmination independently,
and this document says so rather than implying more precision than the code has. It is mathematically
meaningful whenever the transit is defined; where the transit is not defined (a polar schedule the
engine refuses) it is `null` like every other derived field.

Solar midnight is **not** Islamic midnight (see §3) and **not** civil midnight. The three can fall
hours apart. The screen keeps them on separate cards and labels each.

### Day length and night length

The day length is measured, not assumed: `sunset − sunrise`, and only where both exist and sunset is
after sunrise. The night length is the complement of the day length within a 24-hour day
(`24h − dayLength`). It is deliberately the **astronomical** night — sunset to sunrise — and is
**not** the Maghrib-to-Fajr night interval of §3, which is longer because both of its ends are prayer
times rather than geometric horizon crossings. The two are shown with different labels and are never
added together.

### Unavailable results

A field is `null` when the event it needs is genuinely absent. A polar schedule that has a transit
but no sunrise still shows solar midnight; a schedule with no transit shows no solar midnight but
still shows the lengths if sunrise and sunset exist. No field is ever approximated to fill a gap, and
the section is only marked "unavailable" when there is no schedule at all.

---

## 3. The night interval and its boundaries

**The interval is effective Maghrib → the following effective Fajr.** It uses the prayer times the
timetable actually shows, including any method offset or manual correction, because that is the
timetable a reader follows. It is not the astronomical night of §2 and is not the civil day.

With `N = endsAt − startsAt`:

| Boundary | Definition |
|---|---|
| Islamic midnight | `startsAt + N/2` — the exact midpoint of the interval |
| Start of the last third | `endsAt − N/3` |
| Tahajjud begins | the start of the last third under Salah's convention (§4) |
| Fajr | `endsAt`, which closes the interval |

For the reference Dhaka timetable (Maghrib 17:36, next Fajr 04:39, `N` = 11h03m): Islamic midnight is
23:07:30, and the last third begins at 00:58.

**Why Islamic midnight is not solar midnight.** Islamic midnight is a property of the *interval*
Maghrib-to-Fajr; solar midnight is a property of the *sun's* position. In Dhaka they differ by more
than half an hour, and in general they can differ by hours. Salah derives each from its own inputs
and never substitutes one for the other.

**Boundary convention.** Every boundary is half-open, `[start, end)`, exactly as the prayer periods
and the prohibited windows are: a moment exactly at a boundary belongs to the phase that begins
there. A moment exactly at Fajr is outside the interval.

### Phases

| Phase | Opens | Closes |
|---|---|---|
| `EARLY_NIGHT` | Maghrib | Islamic midnight |
| `LATE_NIGHT` | Islamic midnight | the start of the last third |
| `LAST_THIRD` | the start of the last third | Fajr |

A moment outside the interval — between one Fajr and the following Maghrib — has **no** phase, and is
reported as `null` rather than forced into one.

### Countdowns

`UpcomingNightBoundary` is the next of Islamic midnight, the start of the last third and Fajr that
is still ahead, with the remaining time. At or after Fajr there is no upcoming boundary. The instant
is passed in by the caller and re-read on the existing one-second Home tick; the calculation itself
reads no clock and performs no astronomy, so a countdown tick cannot trigger a recalculation.

---

## 4. The convention, stated as a convention

The interval, the midpoint and the last third are arithmetic and need no convention. The only
conventional choice is **where Tahajjud begins**, and Salah's convention is recorded in
`ConfiguredNightConvention`:

- **`tahajjudAnchoredToLastThird = true` (shipped):** Tahajjud runs from the start of the last third
  to Fajr.
- **`false`:** Tahajjud would run from Islamic midnight (the whole second half). Nothing else in the
  code changes; it is one flag on the convention the calculator is given.

`NightPeriodConvention.id` is recorded on the result and travels to the screen, so the interface can
name which convention is in force. Salah does **not** claim the last-third anchoring is the only
position, does not quote a source for it, and states on the card that:

- Tahajjud is **voluntary** night prayer, not one of the five obligatory prayers;
- conventions differ;
- the calculated boundaries are derived times, not an official announcement.

No ruling — what to pray, how many units, whether it may be offered at a different time — is encoded
anywhere in this feature.

---

## 5. Prohibited intervals: current and next

The prohibited engine is described in full in `docs/FORBIDDEN_PRAYER_TIMES.md`. This phase adds two
selections over the windows it already produces:

- `ProhibitedPeriodStatusResolver.active(periods, now)` — the window open right now, or `null`.
- `ProhibitedPeriodStatusResolver.next(periods, now)` — the window with the smallest opening time,
  or `null` when every window has passed.

Both are pure over the windows and the passed-in moment, so they inherit the half-open boundary
convention and the per-window `startsIn`/`endsIn` readings already tested for the day.

---

## 6. Tests

Under `app/src/test/java/io/github/towkirahmedrony/salah/`:

- `core/domain/solar/SolarDayResolverTest` — the measured day/night lengths and their complement
  identity; solar noon versus clock noon; solar midnight as the anti-transit and its next-local-date
  case; a leap day; each field being `null` when its event is absent; the absent-schedule case; the
  schedule's zone; a schedule for another day.
- `core/domain/night/NightPeriodCalculatorTest` — the Maghrib-to-next-Fajr interval and its crossing
  of civil midnight; Islamic midnight as the exact midpoint; the last third; both Tahajjud
  conventions; every phase boundary, half-open; phases by absolute instant across zones; the next
  boundary and its countdown at each stage; and every missing/invalid-input case.
- `core/domain/prohibited/ProhibitedPeriodSelectionTest` — the active and next selections across the
  day, at exact boundaries, and with an empty day.
- `feature/home/HomeNightSectionTest` — the mapping of both new sections into `HomeUiState`, the
  advancing phase and countdown, and the missing-input cases.

The existing prayer, solar-event and prohibited tests are unchanged, which is itself the regression
check: the engine's published times and the three forbidden windows are asserted by their own suites
and were not modified.

Reference values are the engine's own output for Dhaka on 11 October 2026, as elsewhere in the
project; they pin the arithmetic, not any authority comparison.

---

## 7. Known limitations

- **Solar midnight is an approximation** of the true lower culmination (§2), documented rather than
  hidden. A precise nadir solve was judged unnecessary for the latitudes the app targets.
- **The Tahajjud anchoring is a convention**, not a quoted ruling; only the last third is shipped,
  the alternative is representable, and either is disclosed as a convention (§4).
- **Neither value is aligned to any authority's published timetable.** That comparison has not been
  made anywhere in the repository, and nothing here claims it.
- **The night interval spans two schedules**, so Islamic midnight, the last third and Tahajjud need
  the following day's Fajr; until it is calculated the interval is reported unavailable rather than
  closed at a guessed boundary.
- **No ruling is encoded.** This feature reports times and intervals; it does not say what may or may
  not be prayed in them.
