# Salah — Prayer Notifications: Tests and Manual Verification

Two clearly separated things live here:

- **§1–§3: automated tests** that run in CI on every push to `main` and actually pass. These are
  evidence.
- **§4: a manual checklist** for behaviour that cannot be exercised without a physical Android
  device. **None of it has been performed.** Nothing in §4 is a claim that it passed.

Read the distinction literally. "Verified" below means "an automated test asserts it". It never
means "someone watched it happen on a phone".

---

## 1. How the automated tests run

```bash
./gradlew testDebugUnitTest
```

Run by `.github/workflows/android-apk.yml` on a GitHub-hosted Ubuntu runner with Temurin JDK 17,
before lint and before the debug APK is assembled. **A failing test fails the workflow**, so a green
run is what makes the remote APK meaningful.

The tests are plain JVM unit tests: no emulator, no device, no Robolectric. That is a design
constraint, not an accident — see §3.

---

## 2. What is covered

103 of the 191 unit tests in the repository cover this subsystem.

| Test class | Tests | Covers |
|---|---|---|
| `PrayerNotificationPlannerTest` | 20 | What gets scheduled, and what must not |
| `PrayerNotificationCoordinatorTest` | 25 | Reconciliation, recovery, change handling, failure paths |
| `PrayerNotificationDelivererTest` | 16 | Whether a fired alarm becomes a visible reminder |
| `DataStorePrayerNotificationPreferencesRepositoryTest` | 9 | Real persistence, defaults, bad stored data |
| `PrayerNotificationSettingsTest` | 9 | Defaults, clamping, sparseness |
| `AzanPlaybackManagerTest` | 8 | Playback lifecycle, duplication, failure |
| `PrayerNotificationIdsTest` | 7 | Identity uniqueness, stability, round trip |
| `PrayerNotificationRequestTest` | 5 | Alarm payload contract |
| `PrayerNotificationChannelIdTest` | 4 | Channel set is unique, bounded and stable |
| **Total** | **103** | |

### 2.1 Scheduler — `PrayerNotificationPlannerTest`

| Requirement | Test |
|---|---|
| Enabled prayer notifications are scheduled | `enabled prayers are scheduled at their calculated time` |
| Disabled prayer notifications are not scheduled | `disabled prayers are not scheduled at all` |
| Global master switch suppresses scheduling | `the master switch suppresses every reminder` |
| Past prayer events are not scheduled as future events | `past prayers are not scheduled as future events`, `a prayer exactly at now counts as started and is not scheduled` |
| Advance reminder runs before the prayer | `an advance reminder runs before the prayer and carries its lead time` |
| A passed advance reminder is dropped | `an advance reminder that has already passed is dropped` |
| Missing schedule is handled safely | `no schedule means no reminders rather than invented ones`, `an incomplete schedule is refused` |
| No duplicate event identifiers | `overlapping schedules cannot produce a duplicated slot` |
| Deterministic output | `planning is ordered by trigger instant`, `planning twice with the same inputs is identical` |
| Sunrise is separate from the five prayers | `sunrise is never scheduled unless its own switch is on`, `sunrise never gets an advance reminder`, `sunrise is a solar target and never an obligatory prayer` |
| Sound and vibration preferences are respected | `the chosen sound and vibration travel with the reminder` |
| Two days are planned | `two days of schedules produce slots dated per schedule` |
| The cancellation set is exactly the window minus the plan | `obsolete slots are exactly the window minus the plan` |

### 2.2 Coordination, recovery and change — `PrayerNotificationCoordinatorTest`

| Requirement | Test |
|---|---|
| Enabled prayers for both days are scheduled | `enabled prayers for today and tomorrow are scheduled` |
| Only relevant future events are scheduled | `only relevant future events are scheduled` |
| Duplicate broadcasts do not duplicate schedules | `repeating a reconcile does not duplicate alarms`, `a duplicated boot broadcast does not duplicate alarms` |
| Global master switch cancels managed schedules | `the master switch cancels every managed alarm and schedules nothing` |
| Changing a prayer's setting replaces the old schedule | `disabling a prayer cancels the alarm that was already scheduled for it` |
| Changing location invalidates the old schedule | `a location change reschedules against the new times` |
| Changing location/time zone reconciles scheduled events | `the window advances when the local date rolls over`, `a time change rebuilds the window against the new instant` |
| Reboot recovery reconstructs future schedules | `reboot recovery reconstructs the schedule from stored settings` |
| Missing location is handled safely | `without a location nothing is scheduled and everything managed is cancelled` |
| Missing notification permission is handled safely | `missing notification permission is reported but does not stop scheduling` |
| Missing exact-alarm access follows the documented fallback | `missing exact-alarm access follows the documented fallback` |
| A date with no schedule is left alone | `a day with no schedule keeps the alarms already stored for it`, `a past date is always reconciled even when its schedule cannot be produced` |
| A provider failure never invents times | `a provider failure is reported and never invents times` |
| The cancellation window reaches every date a previous plan could have used | `the cancellation window covers every local date a previous plan could have used`, `cancelling everything reaches alarms planned relative to a different local date` |
| Stale alarms for a previous date are cancelled | `alarms for a previous local date are cancelled` |
| One failure does not abort the rest | `one rejected alarm does not abort the rest of the plan`, `a scheduler that refuses everything still reports instead of throwing` |
| Diagnostics are structured and private | `every reconcile is logged with its reason and its counts`, `no coordinate is written to the diagnostics log` |

The two cancellation-window tests are **regression guards for a real defect found by CI**. The
original window was anchored on the device's local date, so on a UTC runner (whose date differs
from the Dhaka location's) cancelling everything left the later day of the previous plan alive —
meaning reminders could still fire after the user had switched notifications off. §7.4 of
`PRAYER_NOTIFICATIONS.md` records the fix.

### 2.3 Delivery — `PrayerNotificationDelivererTest`

| Requirement | Test |
|---|---|
| Global disablement is respected | `the master switch suppresses delivery` |
| Per-prayer disablement is respected | `a disabled prayer is not delivered` |
| The sunrise reminder needs its own switch | `a sunrise reminder is suppressed unless its own switch is on` |
| Missing permission is handled | `missing notification permission suppresses delivery and says so` |
| A very late reminder is not shown | `a reminder that fires far too late is suppressed`, `a reminder inside the staleness window is still delivered` |
| Sound and vibration preferences select the channel | `an enabled prayer is delivered on the channel its sound and vibration select` |
| A silent choice still produces a reminder | `a silent choice still produces a reminder` |
| A sound changed after scheduling is honoured | `a sound changed after scheduling is honoured at delivery time` |
| The reminder is rendered in the location's zone and format | `the reminder is rendered in the prayer location's zone and time format` |
| Azan starts only when enabled and available | `azan is attempted for an at-time prayer reminder` |
| Azan being unavailable never blocks the reminder | `azan playback being unavailable never prevents the reminder` |
| Azan is not attempted for a lead-in or a solar event | `azan is not attempted for a lead-in reminder`, `azan is not attempted for the solar target` |
| Failures are reported, never swallowed | `a presenter that refuses is reported as a failure, not a success`, `a presenter that throws is reported instead of crashing the receiver` |

### 2.4 Settings persistence — `DataStorePrayerNotificationPreferencesRepositoryTest`

Real `DataStore`, on a temporary file — not a fake.

| Requirement | Test |
|---|---|
| Defaults before anything is stored | `defaults are the documented settings before anything is stored` |
| Persist across repository recreation | `per-prayer settings survive a repository recreation` |
| Per-prayer settings are restored | same, plus `updates are applied to the stored value rather than to a stale copy` |
| Master switch and sunrise switch persist | `the master switch and the sunrise reminder are persisted` |
| Returning to defaults leaves no keys behind | `returning a prayer to its default removes the stored keys` |
| Bad stored data does not break or mislead | `an unknown sound name falls back to the default instead of failing`, `an out-of-range stored advance value is clamped on read`, `a stored negative advance value is treated as off` |
| One store is shared safely | `notification settings and the other preferences do not overwrite each other` |

### 2.5 Identity, payload and channels

| Requirement | Test class |
|---|---|
| No duplicate event identifiers | `PrayerNotificationIdsTest.every slot in a long range gets a distinct request code` (400 days × 6 targets × 2 kinds) |
| Identities are stable | `identity is stable across repeated calls` |
| Malformed input is rejected, not guessed | `a malformed uri is rejected instead of guessed` |
| The payload round-trips and fails closed | `PrayerNotificationRequestTest` (5 tests) |
| The channel set is bounded, unique and stable | `PrayerNotificationChannelIdTest` (4 tests) |

### 2.6 Audio — `AzanPlaybackManagerTest`

| Requirement | Test |
|---|---|
| Playback starts only when enabled | `playback starts exactly once when a recording is available` |
| Duplicate playback is prevented | `a second play request never stacks a second stream` |
| Playback can be stopped | `stop releases playback and is safe to repeat` |
| Audio resources are released | `release is forwarded to the engine` |
| Missing audio resources do not crash the app | `playback reports unavailable when no licensed recording is bundled` |
| Playback failures are logged | `an engine that refuses to start is reported as a failure, not a crash`, `an engine that throws is reported as a failure with the cause logged` |

Because no recording ships (`PRAYER_NOTIFICATIONS.md` §5.2), these tests exercise the manager
against a fake engine. They are real tests of the lifecycle rules, but they say **nothing** about
`MediaPlayer` on a real device — see §4.

---

## 3. Why the tests look the way they do

**Deterministic, never time-dependent.** Time comes from an injected `java.time.Clock`, and tests
use `MutableClock` to move it. No test waits, sleeps, or depends on a real prayer time. Times in
`testSchedule` are deliberately synthetic (05:00, 12:00, …) so a failure is never confused with a
real astronomical result.

**Ports, not mocks of the framework.** `AlarmManager`, `NotificationManager`, `DataStore` and
`MediaPlayer` sit behind interfaces. Tests substitute seam-appropriate stand-ins that model real
behaviour — `FakePrayerNotificationScheduler` is keyed by slot, so registering twice *replaces*,
exactly as `FLAG_UPDATE_CURRENT` does, and "no duplicate alarms" is asserted on the live alarm
**set** rather than on a call count.

**The tests are not over-mocked.** What is asserted is the code under test: the planner's output
from real schedules, the coordinator's effect on an alarm registry, the deliverer's decision from
real settings objects, and `DataStore`'s actual persisted bytes.

**No test was changed to hide a defect.** The CI failure in §2.2 was fixed in the production code
(the cancellation window), and a new test was added that fails without the fix. The previously
failing assertion was left exactly as written.

---

## 4. Manual verification checklist

**Status: not performed.** Every item below needs a physical Android device (or an emulator) and a
human observing behaviour. They are instructions, not results. Do not read a checked-looking item
as a verified one.

Prepare a debug APK from a green workflow run (the artifact described in `README.md`), install it,
set a location, and open **Settings → Prayer notifications**.

### 4.1 Permissions

| # | Scenario | Steps | Expected |
|---|---|---|---|
| P1 | Android 13+ notification permission granted | Deny on first ask, then press **Allow notifications** and accept | Both permission rows read "Allowed"; a reminder appears at the next prayer time |
| P2 | Notification permission denied | Press **Allow notifications**, then deny | Status row reads "Not allowed"; the banner says reminders cannot appear; **nothing** is posted at the prayer time; no crash |
| P3 | Exact-alarm access available | Press **Allow exact alarms**, grant it | "Exact alarms: Allowed"; no fallback note; reminders arrive close to the prayer time |
| P4 | Exact-alarm access unavailable | Press **Allow exact alarms** and decline (or revoke it in Settings → Apps → Special access → Alarms & reminders) | "Exact alarms: Not allowed"; the fallback note explains "up to ten minutes late"; reminders still arrive |
| P5 | Permission changed outside Salah | Grant, revoke, or toggle off app notifications in system settings while the screen is closed, then reopen it | Status rows reflect the change immediately, without restarting the app |

### 4.2 Settings behaviour

| # | Scenario | Steps | Expected |
|---|---|---|---|
| S1 | Individual prayer disabled | Turn off Dhuhr | Dhuhr produces no reminder; the other prayers are unaffected; the "Scheduled reminders" count drops |
| S2 | Global notifications disabled | Turn off **All prayer notifications** | The count drops to zero; **no** reminder fires afterwards, including for the following day (see `PRAYER_NOTIFICATIONS.md` §7.4) |
| S3 | Global notifications re-enabled | Turn it back on | Future prayers for today and tomorrow are scheduled again |
| S4 | Silent reminder | Set one prayer to **Silent** | Its reminder appears in the shade with no sound and no vibration; other prayers still sound |
| S5 | Vibrate off | Turn **Vibrate** off for one prayer | That reminder does not vibrate; a prayer left on still does |
| S6 | Advance reminder | Set **Remind me before** to 10 min | A reminder arrives ~10 minutes before the prayer, and a second at the prayer time |
| S7 | Sound preview | Press **Play this sound** for each of the three sounds | Device default plays the device's sound; Salah chime plays the bundled tone; Silent plays nothing and the status message confirms |
| S8 | Sunrise reminder | Turn on **Sunrise reminder** and disable every prayer | Exactly one reminder, at sunrise, worded as sunrise — never as a prayer |
| S9 | Azan availability is stated | Open the **Azan playback** card | Reads "Not available in this build" with the licensing explanation; **no** switch is offered that could be turned on |

### 4.3 Recovery and change (needs a device; the reboot case needs patience)

| # | Scenario | Steps | Expected |
|---|---|---|---|
| R1 | Device reboot | Note the next prayer time, reboot, do **not** open Salah | The reminder still fires. (Automated coverage: §2.2; this confirms the platform actually delivered the broadcast) |
| R2 | Time-zone change | Change the device time zone to one where the location's local date differs | The "Scheduled reminders" count and the next reminder reconcile; no reminder fires twice, and none from the previous date remains |
| R3 | Date change | Leave the app closed overnight | After midnight the window advances, so tomorrow's reminders exist without opening the app |
| R4 | Manual clock change | Move the device clock forward past a prayer, then back | The schedule reconciles each time; no reminder fires for a prayer already passed |
| R5 | Location change | Change the location in Settings to a different city/zone | Times and the reminder count follow the new location; no reminder from the old location's schedule fires |
| R6 | Calculation method change | Change the calculation method | The schedule reconciles; the next reminder matches the new timetable |
| R7 | Force-stop then relaunch | Force-stop Salah from system settings, then relaunch it | Reminders resume; Android withholds broadcasts to a force-stopped app until it is launched again, so nothing is expected in between |
| R8 | App update | Install a newer build over the old one | Reminders survive the update (`MY_PACKAGE_REPLACED`) |

### 4.4 Offline and platform limits

| # | Scenario | Steps | Expected |
|---|---|---|---|
| O1 | Offline operation | Enable aeroplane mode, then change a setting and reboot | Settings persist, the count updates, reminders still fire. Salah has no network permission at all |
| O2 | Notification channel changed by the user | In Android's app notification settings, change a prayer channel's sound, or silence it | Android keeps the user's choice; Salah does **not** override it, and does not claim to. Changing the sound in Salah routes to the matching channel |
| O3 | Battery optimisation | Put the device in its most aggressive power-saving mode | Reminders may be delayed. Record what actually happened; this is device-specific and cannot be generalised |
| O4 | Do Not Disturb | Enable DND with notifications blocked | Reminders are silenced or withheld by the system. Salah does not bypass DND and shows no error |
| O5 | Doze / screen off overnight | Leave the device unplugged and still overnight with an early Fajr | Fajr fires at or near its time, or is visibly late. Record the observed delay and the device model |
| O6 | Azan playback on device | Not testable in this build — no recording is bundled (`PRAYER_NOTIFICATIONS.md` §5.2) | n/a |

---

## 5. What is verified, and what is not

**Verified by automated tests, in CI, on this commit:** planning, identity, persistence, delivery
decisions, permission and stale-alarm handling, the exact-alarm fallback, the cancellation window
and its bound, and the Azan playback lifecycle.

**Verified by CI only as a build, not as behaviour:** the debug APK compiles, passes lint and
uploads its artifact.

**Not verified at all, and requiring a device:** every item in §4 — Android's actual alarm delivery,
Doze and battery-optimisation behaviour, the system's permission and channel surfaces, `AlarmManager`
surviving a real reboot, and `MediaPlayer` playing real audio.

**Assumed, but not verified:** that a `MediaPlayerAudioPlaybackEngine` would behave as written on a
real device. It is unreachable in this build because no recording is bundled.
