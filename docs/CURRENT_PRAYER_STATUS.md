# Salah — Current Prayer Status

This document describes the **current-prayer card on the Home screen** as implemented in the
repository: which prayer period is open, which boundary closes it, how the countdown is produced,
and what is shown when the answer cannot be derived. It states what the code does and which parts
have been tested; it does not make claims the code does not support.

The card answers a different question from the next-prayer card beneath it:

| Card | Question |
|---|---|
| **Current prayer** | Which prayer period am I in, when did it open, and what closes it? |
| **Next prayer** | Which prayer begins next, and how long until it does? |

Both stay on the screen. The current period leads; the next prayer is secondary.

---

## 1. What the card shows

For a moment inside a prayer period (for example 15:00, when Dhuhr is open and Asr closes it):

| Element | Value in that example |
|---|---|
| Prayer | Dhuhr |
| Status | Currently active |
| Countdown | the time remaining until Asr begins |
| Started | 11:46 |
| Closing boundary | "Time until next prayer time: Asr at 15:58" |

The countdown is the time until the next boundary in the timetable. It is **not** a ruling about how
long the prayer may be performed, whether a late prayer is valid, or whether a missed one must be
made up. Nothing in Salah answers those questions, and the string resources say "time until next
prayer time" for exactly that reason. Do not relabel them as a deadline.

---

## 2. The period convention

`CurrentPrayerPeriodResolver` (`core/domain/CurrentPrayerPeriodResolver.kt`) is the single source of
truth. It is pure: no astronomy, no Android API, no read of the system clock — the moment is passed
in, so every boundary case is reproducible in a test.

| Period | Opens at | Closes at | Closing boundary kind |
|---|---|---|---|
| Fajr | Fajr time | **sunrise** | solar event |
| *(none)* | sunrise | Dhuhr time | — |
| Dhuhr | Dhuhr time | Asr time | next prayer |
| Asr | Asr time | Maghrib time | next prayer |
| Maghrib | Maghrib time | Isha time | next prayer |
| Isha | Isha time | **the next day's Fajr** | next prayer, next day |

This is the application's configured convention. It follows the engine's own prayer times, so a
method adjustment on Fajr, Dhuhr or Maghrib moves the period boundary with it — deliberately, since
the period is defined by when that prayer's time is. The prohibited intervals in
`docs/FORBIDDEN_PRAYER_TIMES.md` do the opposite and are anchored to the raw solar events instead.

**The gap between sunrise and Dhuhr is real.** With Fajr closing at sunrise and Dhuhr opening at its
own calculated time, the middle of the morning belongs to no obligatory prayer's period. The card
says so ("No prayer period is currently active") and shows the next prayer and the countdown to it,
rather than stretching Fajr or starting Dhuhr early to fill the gap.

### Exact boundaries

Every period is half-open, `[start, end)`. At the exact instant a boundary occurs, the period that
*opens* there is the one reported:

- exactly at Fajr → the Fajr period;
- exactly at sunrise → **between** periods (Fajr has closed, Dhuhr has not opened);
- exactly at Dhuhr → the Dhuhr period;
- exactly at sunset, or at any time in Isha → the Isha period.

`CurrentPrayerPeriodResolverTest` asserts each of these instants.

### Midnight and the Isha-to-Fajr transition

Isha's period runs past midnight, so two different situations arise:

- **After Isha, before midnight.** The period opened at today's Isha and closes at tomorrow's Fajr.
  Both are known, and the countdown is exact.
- **After midnight, before Fajr.** The period opened at *yesterday's* Isha, which is not on today's
  schedule. Salah reports the period with no start time and says "Started the previous day" instead
  of borrowing a time from a timetable it does not have. The closing boundary — today's Fajr — is on
  today's schedule, so the countdown remains exact.

### Sunrise is not a prayer

Sunrise is modelled as `SolarEvent.SUNRISE`, never as a `PrayerName`. It closes the Fajr period and
it anchors the sunrise prohibited interval, and those are two different things: the period ends
*at* sunrise, while the prohibited interval runs *from* sunrise. The card states the closing
boundary by name ("Time until Sunrise: 05:54"), so the two are never confusable, and the timetable
and the solar-events strip keep listing it as an event.

---

## 3. How the countdown is produced and kept current

```
Clock ──► moment (StateFlow, 1 s tick)
             │
             ├──► request(date, location, profile) ──► PrayerScheduleRepository ──► today's schedule
             │                                                                          │
             └──► nextDayRequest(date + 1) ─────────► PrayerScheduleRepository ──► tomorrow's Fajr
                                                                                       │
     CurrentPrayerPeriodResolver.resolve(schedule, moment, tomorrowsFajr) ◄─────────────┘
                                    │
                                    ▼
                          CurrentPrayerSection ──► CurrentPrayerHero
```

- **One tick, one clock reading.** `HomeViewModel` re-reads the clock once a second into a single
  shared `moment` flow. The current-period countdown and the next-prayer countdown are both derived
  from that one value, so the two cannot disagree about what time it is.
- **Only while visible.** The tick is a `StateFlow` started with
  `SharingStarted.WhileSubscribed(5000)`, and the screen collects with
  `collectAsStateWithLifecycle()`. Leaving Home stops the tick; there is no background loop, no
  `WorkManager` job and no alarm behind this card.
- **Recomputation.** A location change, a settings change (method, Asr method, high-latitude rule,
  manual adjustments) or a date change produces a new `PrayerScheduleRequest`. The request is keyed
  on the full set, de-duplicated with `distinctUntilChanged()`, and the repository reuses a cached
  schedule when nothing that matters has changed — so the second-by-second tick does not recalculate
  anything.
- **Tomorrow's Fajr.** It is asked for through the *same* repository, with the same engine, location
  and profile, one day ahead. It exists only to close the Isha period. Reusing the repository is
  what keeps Salah from acquiring a second prayer-time engine that would eventually disagree with
  the timetable shown underneath it.
- **Midnight.** The request date is the local date **at the prayer location**, not on the device, so
  the rollover is driven by the same zone the times are expressed in. At the rollover the request
  changes, the schedule is recalculated for the new day, and the card follows.

---

## 4. When nothing can be shown

The resolver returns `CurrentPrayerStatus.Unavailable` rather than a guess, and the card explains
the gap with the same `UnavailabilityReason` vocabulary the rest of Home uses:

| Situation | What is shown |
|---|---|
| No location configured | `NO_LOCATION_CONFIGURED` — the reason from the schedule state, not a generic one |
| Timetable still loading | the Home skeleton; nothing is shown until a schedule exists |
| Engine could not calculate | the reason carried by `PrayerScheduleState.Unavailable` |
| Provider failed | `UNKNOWN`, with the failure banner and its retry action above |
| Schedule incomplete (fewer than five prayers) | unavailable |
| Prayer times out of chronological order, or duplicated | unavailable — treated as invalid data, not as a timetable |
| Schedule belongs to another local date | unavailable; another day's times are never applied to this moment |
| Sunrise missing while Fajr would be open | unavailable — the period cannot be closed without it |
| Isha open and tomorrow's Fajr not available | unavailable; the boundary is not invented |

Dhaka coordinates are never substituted for a missing location, and a stale countdown is never left
on screen: state is recomputed from the current schedule and the current moment, and a schedule that
no longer matches the day is rejected.

---

## 5. Tests

`app/src/test/java/io/github/towkirahmedrony/salah/core/domain/CurrentPrayerPeriodResolverTest.kt`
covers, with fixed timestamps and no dependency on the real clock:

before Fajr · during Fajr · one second before sunrise · exactly at sunrise · between sunrise and
Dhuhr · exactly at Dhuhr · during Dhuhr · one second before Asr · exactly at Asr · during Asr ·
during Maghrib · during Isha with and without tomorrow's Fajr · after Isha · a boundary that does not
follow Isha · midnight and local-date rollover · a schedule for another date · the location's zone
versus the device's · a second Bangladesh coordinate · missing, incomplete, duplicated and
out-of-order data · a morning without sunrise · remaining-duration arithmetic · the whole day
resolving to exactly one reading per hour.

`HomeUiStateFactoryTest` covers the mapping the card renders: the active period, the sunrise
boundary, the between-periods reading, the carried-over pre-dawn start, Isha closed by the next day's
Fajr, and the unresolved cases.

### Running them in a normal development environment

```
./gradlew :app:testDebugUnitTest --tests "*.CurrentPrayerPeriodResolverTest"
./gradlew :app:testDebugUnitTest --tests "*.HomeUiStateFactoryTest"
```

In this task's environment Gradle is **not** run locally; CI (`.github/workflows/android-apk.yml`)
runs `testDebugUnitTest`, `lintDebug` and `assembleDebug`. See the final report for the actual CI
result rather than assuming one.

---

## 6. What this feature does not do

- It does not decide whether a prayer offered at a particular moment is valid, on time, or missed.
- It does not implement the religious rulings that an Isha performed after midnight might attract.
  The card reports the timetable; it does not rule.
- It does not compute a second set of prayer times. Everything shown comes from the schedule the
  existing engine produced.
