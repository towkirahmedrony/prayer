# Salah — Prayer Notifications and Azan Playback

This documents the notification subsystem **as implemented in this repository**, including what it
deliberately does not do and which behaviour belongs to Android rather than to Salah.

It is not a design proposal. Every mechanism described here exists in the source tree, and every
claim about behaviour is backed by a test named in
[`docs/PRAYER_NOTIFICATIONS_TESTING.md`](PRAYER_NOTIFICATIONS_TESTING.md).

---

## 1. Scope

Implemented:

- Reminders scheduled at the **actual calculated prayer times** of the configured location.
- Per-prayer enable, sound, vibration and advance reminder.
- A global master switch that cancels everything Salah manages.
- A separate sunrise (solar event) reminder, off by default.
- Notification channels whose sound and vibration follow the user's choice.
- `POST_NOTIFICATIONS` and `SCHEDULE_EXACT_ALARM` handled as the two separate permissions they are.
- A documented fallback when exact alarms are not permitted.
- Reboot, clock, time-zone, date and app-update recovery.
- Offline operation with no network call anywhere in the scheduling or delivery path.
- Azan **playback machinery** (lifecycle, single instance, release, failure handling) — implemented
  and tested, but with no recording bundled. See §5.

Deliberately **not** implemented, and reported as such in the UI rather than hidden:

- Bundled Azan audio (licensing — §5).
- A long-running foreground playback service (§5.3).
- A post-prayer reminder. No product behaviour defines one, so inventing one would be guessing at
  religious guidance rather than scheduling a notification.
- Any attempt to bypass Do Not Disturb, battery optimisation or a user's channel settings (§7).

---

## 2. Scheduler architecture

```text
                    trigger
   app start ──┐  settings change ──┐  location change ──┐
   reboot ─────┼─ time/zone/date ───┼─ app update ───────┼─ post-delivery ──┐
               │                    │                    │                  │
               ▼                    ▼                    ▼                  ▼
                        PrayerNotificationCoordinator.reconcile(reason)
                                         │
                     ┌───────────────────┼────────────────────┐
                     │                   │                    │
        PrayerScheduleRepository   PrayerNotification    NotificationPermission
        (existing offline engine)   PreferencesRepository  StatusProvider
                     │              (existing DataStore)         │
                     ▼                   ▼                       ▼
              real PrayerSchedule   PrayerNotificationSettings   permission state
                     └───────────────────┼────────────────────────┘
                                         ▼
                            PrayerNotificationPlanner  (pure)
                       schedules + settings + now  →  List<PlannedPrayerNotification>
                                         │
                    ┌────────────────────┴────────────────────┐
                    ▼                                         ▼
        scheduler.cancel(obsoleteSlots)              scheduler.schedule(planned)
                    │                                         │
                    └──────────────► PrayerNotificationScheduler ◄────────────┘
                                   (AlarmManager implementation)

   fired alarm ──► PrayerNotificationReceiver ──► PrayerNotificationDeliverer
                                                     │
                                    re-checks master switch, target,
                                    permission and staleness
                                                     │
                                    ┌────────────────┴────────────────┐
                                    ▼                                 ▼
                     PrayerNotificationPresenter            AzanPlaybackManager
                     (post / suppress / fail)               (play / unavailable)
```

### 2.1 Responsibilities

| Type | Layer | Responsibility |
|---|---|---|
| `PrayerNotificationPlanner` | `core/domain/notification` | **What** should be scheduled. Pure: no Android, no clock, no coroutine, no repository. |
| `PrayerAlarmSlot` | `core/domain/notification` | The unit of identity: a date, a target and a kind. |
| `PrayerNotificationIds` | `core/domain/notification` | Deterministic `PendingIntent` request code, notification tag/id and alarm URI for a slot. |
| `PrayerNotificationCoordinator` | `core/domain/notification` | The single reconciliation entry point. Decides and applies the alarm set. |
| `PrayerNotificationDeliverer` | `core/domain/notification` | **Whether** a fired alarm becomes a visible reminder, and renders it. |
| `PrayerNotificationScheduler` (port) | `core/domain/notification` | Registers and removes alarms. |
| `AlarmManagerPrayerNotificationScheduler` | `data/notification` | The `AlarmManager` implementation, including the exact-alarm fallback. |
| `PrayerNotificationReceiver` | `data/notification` | The alarm broadcast. Delivers, then advances the window. |
| `SystemEventReconciliationReceiver` | `data/notification` | Boot, clock, time-zone, date and app-update triggers. |
| `AndroidNotificationChannels` | `data/notification` | Creates the channels. |
| `AndroidPrayerNotificationPresenter` | `data/notification` | Renders and posts the notification. |
| `DataStorePrayerNotificationPreferencesRepository` | `data/notification` | Persists the settings in the existing DataStore. |
| `AndroidNotificationPermissionStatusProvider` | `data/notification` | Reads the two platform permissions. |
| `AzanPlaybackManager` | `core/domain/notification` | Playback lifecycle rules. |
| `BundledAzanAudioSource` / `MediaPlayerAudioPlaybackEngine` | `data/notification` | The audio asset source and the framework player. |
| `SalahDiagnosticEvents` / `AndroidDiagnosticsLogger` | `core/domain`, `data/notification` | Structured diagnostics. |

There is exactly **one** scheduler and exactly **one** reconciliation entry point. Nothing else in
the app creates, cancels or posts a prayer reminder.

### 2.2 Dependency direction

`PrayerNotificationCoordinator` and `PrayerNotificationDeliverer` live in `core/domain` because
they are orchestration over ports: they depend only on repository interfaces, the scheduler port,
the presenter port, the permission port, the clock and the logger. They contain no Android import.
The Android-dependent pieces — `AlarmManager`, `NotificationManagerCompat`, `MediaPlayer`,
`DataStore` — stay in `data/notification` and are bound in `di/SalahModules.kt`. This is what lets
the scheduling and delivery rules be unit-tested with fakes instead of a mocked framework, and it
keeps the stated rule that `feature` never imports `data`.

### 2.3 The planning window

Salah keeps a **rolling two-day window**: today and tomorrow, computed in the **prayer location's**
zone, not the device's.

Two days, not one: after today's Isha the next reminder belongs to tomorrow, and a phone that spends
the night in Doze must already have tomorrow's alarms registered. More than two days would widen the
cancellation set and the number of live alarms for no benefit, because a daily reconciliation is
guaranteed by the date-change broadcast, the boot broadcast and app start.

The window is re-derived on every reconciliation. It is never stored, so it cannot go stale.

### 2.4 What gets scheduled

For each date in the window the coordinator loads a schedule from the **existing**
`PrayerScheduleRepository` — the same offline-first repository the Home screen uses — so there is
one calculation path and one cache, and the times the user sees and the times Salah schedules can
never disagree.

For each obligatory prayer that is enabled:

- an `AT_TIME` reminder at the calculated prayer time, when the time is still in the future;
- optionally an `ADVANCE` reminder `advanceMinutes` earlier, when that instant is still in the
  future and the value is greater than zero.

The sunrise reminder, when its own switch is on, produces a single `AT_TIME` reminder at sunrise. It
is never given an advance reminder and is never treated as one of the five prayers.

A reminder exists **only** where a complete schedule supplies the time. A missing, incomplete or
failed schedule produces no reminders — never a guessed one — and the coordinator reports which
dates had no timetable.

### 2.5 Duplicate prevention

Four independent mechanisms, because an alarm that fires twice is worse than an alarm that does not
fire at all:

1. **Deterministic identity.** Each slot's `PendingIntent` request code is
   `floorMod(epochDay, 1_000_000) * 16 + target.ordinal * 2 + kind.ordinal`. Within any window of
   about 2 700 years every `(target, kind)` pair occupies a distinct block, and the planner only
   ever plans two days. `PrayerNotificationIdsTest` asserts uniqueness across 400 consecutive days
   × 6 targets × 2 kinds.
2. **A stable data URI, moving times in extras.** `PendingIntent` equality ignores extras and
   compares action, data, type, class and categories. Giving every slot a unique data URI makes its
   alarms genuinely distinct; keeping the *times* out of that URI is what makes rescheduling a moved
   prayer time **replace** the alarm rather than orphan it. The times travel in an extra that
   `FLAG_UPDATE_CURRENT` refreshes in place.
3. **A derived cancellation set.** The set of slots that could exist for a date is the fixed
   cross-product of `ReminderTarget × PrayerReminderKind`. For every date whose schedule was
   actually produced, the coordinator cancels the whole cross-product minus the new plan. There is
   no registry that can drift out of date, and a reminder scheduled under settings the user has
   since changed is still found and cancelled.
4. **A stable `(tag, id)` on the notification.** Re-delivering the same slot **replaces** the
   notification instead of adding a second one, so even a duplicated broadcast cannot produce
   duplicate visible reminders.

`PrayerNotificationCoordinatorTest` asserts that repeating a reconcile — including three duplicate
boot broadcasts — leaves the registered alarm set byte-for-byte identical.

### 2.6 Never scheduling the past

An alarm registered for an instant that has already passed fires immediately, which is exactly the
"stale reminder at the wrong moment" failure. It is prevented in two independent places:
`PrayerNotificationPlanner` drops any trigger at or before `now`, and
`AlarmManagerPrayerNotificationScheduler.schedule` refuses a trigger that is not strictly in the
future and logs `notification_alarm_skipped_past`.

---

## 3. Android permissions

Salah declares four permissions. Each one is tied to the feature that needs it, and none is
requested at startup.

| Permission | Exists from | Why Salah needs it | How it is obtained |
|---|---|---|---|
| `POST_NOTIFICATIONS` | API 33 | Without it the platform records a posted notification but never shows it. | Runtime request, from the prayer-notification settings screen only. |
| `RECEIVE_BOOT_COMPLETED` | API 1 | Alarms do not survive a reboot; without it the schedule would be silently lost. | Install-time. |
| `SCHEDULE_EXACT_ALARM` | API 31 | Without it `setExactAndAllowWhileIdle` throws. | Special access, requested separately from the notification settings screen. |
| `VIBRATE` | API 1 | Required for notification vibration, which Salah offers per prayer. | Install-time. |

### 3.1 Why the two permissions are kept apart

`POST_NOTIFICATIONS` and `SCHEDULE_EXACT_ALARM` are modelled as two independent booleans in
`NotificationPermissionStatus` and shown as two separate rows in the UI, because they are two
separate capabilities with four possible combinations:

- **Both granted** — reminders appear at the prayer time, surviving Doze.
- **Notifications only** — reminders appear, but Android may run them late.
- **Exact alarms only** — exact alarms are permitted, but nothing can be shown.
- **Neither** — nothing is delivered.

Granting one does not grant the other, and the UI never implies that it does.

### 3.2 What Salah deliberately does not request

- **No location permission.** The location is entered by hand and stored locally.
- **No `FOREGROUND_SERVICE`.** No long-running service exists — see §5.
- **No `INTERNET`.** The entire feature is offline. Salah declares no network permission at all.
- **No `USE_EXACT_ALARM`.** That permission is reserved for apps whose core function is an alarm
  clock or calendar, it is granted automatically, and it removes the user's ability to decline.
  Salah asks for `SCHEDULE_EXACT_ALARM` instead, so the user keeps the choice, and handles the "no"
  answer properly (§4).

### 3.3 When Salah asks

Only from the prayer-notification settings screen, and only when the user acts:

- The **"Allow notifications"** button appears while notification permission is missing. Pressing it
  launches the runtime request.
- The **"Allow exact alarms"** button appears while exact-alarm access is missing. Pressing it opens
  the system grant screen.
- If notifications are turned off at the *app* level (rather than the runtime permission being
  denied), the button opens the app's notification settings page instead, because that is the only
  place the setting can be changed.

The permission state is re-read when the screen appears and again when the user returns from a
system screen, so a change made outside Salah is reflected immediately. Nothing is cached. Salah
never re-prompts on its own; if the user declines, the UI states plainly that reminders cannot
appear.
---

## 4. Notification channels

### 4.1 Why there is more than one

On Android 8.0 (API 26) and later the **channel** — not the notification — owns the sound and the
vibration, and a notification's own `setSound`/`setVibrate` calls are ignored. Salah's `minSdk` is
26, so the channel is the only available mechanism. Supporting a per-prayer sound and vibration
preference therefore *requires* one channel per `(sound, vibration)` pair.

That is a concrete requirement, and it is why the set is a small, **derived** cross-product rather
than a channel per prayer:

| Channel id | Sound | Vibrates | Importance |
|---|---|---|---|
| `salah_prayer_system_default_vibrate` | Device default | yes | `HIGH` |
| `salah_prayer_system_default_quiet` | Device default | no | `HIGH` |
| `salah_prayer_salah_chime_vibrate` | Salah chime | yes | `HIGH` |
| `salah_prayer_salah_chime_quiet` | Salah chime | no | `HIGH` |
| `salah_prayer_silent_vibrate` | none | yes | `DEFAULT` |
| `salah_prayer_silent_quiet` | none | no | `DEFAULT` |
| `salah_status` | none | no | `LOW` |

`3 sounds × 2 vibration states = 6` prayer channels, **regardless of how many prayers exist**. Two
prayers that share a preference share a channel. `PrayerNotificationChannelId.of(sound, vibration)`
is the single source of the mapping, and `PrayerNotificationChannelIdTest` asserts the set is
unique, bounded and stable.

A seventh channel, `salah_status`, carries non-urgent Salah messages. It is deliberately separate
and low-importance, so silencing status messages cannot silence prayer reminders.

Importance follows the choice: a reminder the user asked to hear can reach them with a heads-up,
while a silent one is not given a popup it did not ask for.

### 4.2 Channel ids are a stable interface

Android remembers a channel's settings **by id**. Renaming a channel would silently reset the user's
customisations, so ids are built from the enum names, prefixed with `salah_prayer_`, and only ever
added to. `PrayerNotificationChannelIdTest` pins the exact strings.

### 4.3 What Salah cannot do to a channel

Once a channel exists, Android keeps the user's edits to it — a changed sound, a lowered
importance, notifications turned off for that channel — and an app's `createNotificationChannel`
call cannot override them. Salah therefore **only ever creates** channels and never rewrites them.

The practical consequence, stated in the settings screen rather than hidden: choosing a sound in
Salah routes the reminder to the matching channel, but if the user has already customised that
channel in system settings, **Android's setting wins**. Salah does not claim to override it, and
does not attempt the workarounds that would require.

---

## 5. Exact alarms, and what happens without them

### 5.1 The behaviour

| `SCHEDULE_EXACT_ALARM` | What Salah calls | Result |
|---|---|---|
| Granted | `AlarmManager.setExactAndAllowWhileIdle(RTC_WAKEUP, …)` | Runs at the prayer time and survives Doze. |
| Not granted (API 31+) | `AlarmManager.setWindow(RTC_WAKEUP, …, 10 min, …)` | Still scheduled, but Android may fire anywhere in the window and may coalesce it with other wakeups. |
| API < 31 | `setExactAndAllowWhileIdle` | Exact alarms are unrestricted; there is nothing to request. |

The fallback is **real scheduling**, not a silent no-op and not an exception. Salah asks for the
permission once from the notification settings screen, explains what is lost without it, and — this
matters — **does not repeatedly re-request it**. The current state is visible at all times as its
own row, and the supporting text says reminders may be a few minutes late.

`PrayerNotificationScheduler.EXACT_ALARM_FALLBACK_WINDOW_MILLIS` is the single definition of the
window: **10 minutes**. Ten minutes is a deliberate compromise — wide enough for Android to batch,
narrow enough to still be a prayer reminder rather than a late alert.

### 5.2 Why `AlarmManager` and not `WorkManager`

A prayer reminder is a wall-clock event: the user is told Fajr is at 05:12 and expects to hear about
it then. `WorkManager` is explicitly designed for **deferrable** work and its scheduling is at the
platform's discretion, so using it here would silently trade punctuality for battery — the opposite
of what the feature needs. `WorkManager` is therefore **not used anywhere** in Salah's notification
path, and adding it would be a regression rather than a shrug.

---

## 6. Azan playback

### 6.1 What is implemented

The playback subsystem is complete and unit-tested:

- **`AzanPlaybackManager`** — the lifecycle rules: at most one playback at a time, stop on request,
  always release, no restart of an interrupted stream, no looping.
- **`AzanAudioSource`** (port) — where a recording comes from; `attribution()` for its credit.
- **`AudioPlaybackEngine`** (port) and **`MediaPlayerAudioPlaybackEngine`** — the framework player,
  with audio focus requested as `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` (so the user's music is ducked
  rather than silenced) and abandoned on stop and completion.
- **The delivery path calls it**: `PrayerNotificationDeliverer` attempts playback for an `AT_TIME`
  prayer reminder — and only for that. A lead-in reminder and the sunrise reminder are
  notifications, not calls to prayer, and never start audio.

No new audio library was added. `MediaPlayer` is part of Android, plays a bundled `res/raw` asset or
a local file without any storage permission, and is the right tool for one local recording.

### 6.2 Why no Azan recording is bundled

**No Azan recording ships with Salah, and the reason is licensing, not effort.** A full Azan
recording is a substantial piece of religious audio; redistributing one requires rights this project
does not hold (see `README.md`). The two alternatives were both worse than shipping nothing:

- Bundling a recording of unknown provenance would put a licensing problem inside the APK.
- Synthesising a stand-in and labelling it "Azan" would misrepresent a generated tone as recited
  scripture — exactly the sort of fabrication the rest of Salah refuses.

So `BundledAzanAudioSource.resolve()` returns `null`, `AzanPlaybackManager.play()` returns
`AzanPlaybackResult.Unavailable(NO_LICENSED_AUDIO_BUNDLED)`, and the settings screen reports the
limitation in plain language. **Because the option cannot work, no "play the Azan" switch is
offered** — offering a control that does nothing would be worse than offering none.

The user requirement "disable Azan playback while keeping ordinary notifications enabled" is
therefore satisfied trivially and by construction: notifications and playback are separate paths,
and turning off one can never affect the other.

### 6.3 The extension point

Adding a licensed recording is **one line**: return its location from
`BundledAzanAudioSource.resolve()`. Nothing else changes — the manager, the engine, the focus
handling, the single-instance rule and the failure paths already exist and already have tests
(`AzanPlaybackManagerTest`). `AzanAudioSource.attribution()` exists so a recording's credit can be
surfaced in the settings screen as soon as one is bundled.

### 6.4 Why long-form background playback is not implemented

Playing a multi-minute recording from a broadcast receiver is not safe: `onReceive` runs on the main
thread with a short budget, and a long-playing component needs a **foreground service** with a
declared `foregroundServiceType`, a `FOREGROUND_SERVICE_*` permission and a persistent, dismissible
notification. That is a significant, user-visible behavioural change — a notification Salah cannot
remove while the Azan is playing — and it would be introduced to serve an asset that does not exist.

Implementing it now would mean shipping and testing an invasive service on the strength of an
assumption. So Salah implements the reliable notification-sound path first and documents the
limitation. When a licensed recording is added, the foreground-service variant is a deliberate,
reviewable piece of work rather than a hidden side effect of returning a non-null file path.

### 6.5 Honest limitation

`MediaPlayerAudioPlaybackEngine` is **not exercised at runtime in this build**, because nothing is
ever resolved for it to play. Its logic is written to be reviewable and the manager above it is
unit-tested, but its **device behaviour has not been verified on hardware** — that is only possible
once a recording exists. This document says so rather than implying the audio path is proven.

---

## 7. Reboot recovery, time changes and offline behaviour

### 7.1 Alarms do not survive a reboot

Neither a reboot, nor an app update, preserves a `PendingIntent` alarm. `SystemEventReconciliationReceiver`
is therefore registered for the broadcasts that can invalidate a schedule, and nothing else:

| Broadcast | Mapped reason | Why it matters |
|---|---|---|
| `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED` | `BOOT_COMPLETED` | All alarms were dropped; without this, reminders stop silently until the app is next opened. |
| `MY_PACKAGE_REPLACED` | `PACKAGE_REPLACED` | An app update drops the app's alarms. |
| `TIME_SET` | `TIME_OR_DATE_CHANGED` | The user corrected the clock; every absolute trigger instant moved. |
| `TIMEZONE_CHANGED` | `TIME_OR_DATE_CHANGED` | The local date may have changed, and with it which schedule applies. |
| `DATE_CHANGED` | `TIME_OR_DATE_CHANGED` | Fired at local midnight: the two-day window must advance. |

All three time-related broadcasts map to **one** reason because the correct response is identical:
discard the derived window and rebuild it from the current instant.

Recovery is **idempotent**: a repeated or duplicated broadcast produces the same alarm set.
`PrayerNotificationCoordinatorTest` asserts this directly, including three consecutive boot
broadcasts.

### 7.2 What recovery does

1. Read the stored notification settings and the stored location.
2. If the master switch is off → cancel everything Salah manages and stop.
3. If no location is configured → cancel everything Salah manages and stop. Times are never invented.
4. Otherwise, load a schedule for each date in the window from the existing offline-first repository.
5. Cancel every slot in the window that the new plan does not cover.
6. Schedule the plan.

**No network access at any point.** The schedule comes from the local astronomical provider, so a
phone rebooted in aeroplane mode recovers exactly as well as one on Wi-Fi.

### 7.3 A date whose schedule is unavailable is left alone

If a date's timetable cannot be produced, the coordinator **does not cancel that date's existing
alarms**. Prayer times for a given date and place do not change on their own, so the alarms already
registered are still the best known times; cancelling them because of a transient calculation
failure would silently drop reminders. Only dates whose desired set is actually known are
reconciled, and the UI reports how many dates had no timetable.

### 7.4 Why the cancellation window is wider than the planning window

The **plan** covers `[today, tomorrow]`. The **cancellation set** spans
`[today - 3, today + 4]` — `CANCELLATION_MARGIN_DAYS = 3` on each side.

This margin is not slack; it is the radius required to reach alarms created by an *earlier* run
whose notion of "today" was different from this one. Two things move that date:

1. **The zone it is derived from.** The plan is calculated in the **prayer location's** zone, but
   when no location is configured the fallback is the **device's** zone. Legal offsets span
   UTC−12:00 to UTC+14:00 — 26 hours — so the same instant can fall on a different calendar date in
   each, and in the worst case two days apart.
2. **A location change.** Moving to another zone shifts the location's local date, and with it
   which days the previous plan covered.

A previous plan therefore covers at most `[today − 2, today + 3]`, which the window contains with
room to spare. `PrayerNotificationCoordinatorTest` asserts the span reaches every one of those
dates, so the bound is checked rather than assumed.

Cancelling a slot that holds no alarm is a harmless no-op, so a superset is the cheap and correct
choice. The alternative — remembering exactly what was scheduled — would add a registry that can
disagree with the platform, which is the failure mode the derived set exists to avoid.

A real consequence, and the kind of thing this margin prevents: turning the master switch off, or
clearing the location, must cancel **everything**. Anchoring the cancel pass only on the device's
own date would leave the later day of the previous plan firing after the user had switched
notifications off. That is a bug this project hit, and the span test is its regression guard.

### 7.5 Receivers do no heavy work

Both receivers return immediately and hand the real work to the application-scoped
`CoroutineScope` under `goAsync()`, which is the supported way to do asynchronous work from a
receiver. No database migration, no calculation loop and no network call runs inline, and no service
is started. `pendingResult.finish()` runs on every path so the platform's receiver budget is
respected.

`SystemEventReconciliationReceiver` is declared `exported="true"` because these are broadcasts the
*system* delivers to manifest receivers — and every action it lists is a **protected broadcast that
only the system may send**, so exporting it does not let another app drive a reconciliation.
`PrayerNotificationReceiver` is `exported="false"`: it is reached only by Salah's own
`PendingIntent`s, which name the component explicitly, so no third-party app can trigger a prayer
notification.

---

## 8. Offline behaviour

The notification subsystem requires **no network access**, and Salah declares no network permission
at all.

- Settings are stored in the existing local DataStore.
- Times come from the existing local astronomical engine, via the existing offline-first
  `PrayerScheduleRepository`.
- Reboot recovery reads the same local store and recalculates locally.
- No time-critical receiver performs a network request; there is nothing to request.
- A missing or unavailable schedule is reported honestly and produces **no** reminders.
- Being offline neither erases settings nor defers reminders. There is no server copy that could
  disagree with the device, and therefore no possibility of duplicate reminders after reconnecting.
- A correction to the device clock or time zone triggers a local rebuild, not a sync.

The one thing that is genuinely external is a licensed Azan recording (§5) — and its absence has a
working offline fallback: the notification sound.

---

## 9. Diagnostics

`DiagnosticsLogger` is a port with a stable event vocabulary (`SalahDiagnosticEvents`), rendered by
`AndroidDiagnosticsLogger` as `event key=value key=value` under the single `Salah` tag, with keys
sorted so a line is stable and greppable.

Events recorded: reconciliation started/finished/unavailable, master switch off, no location,
schedule loaded or unavailable, alarm scheduled/cancelled/skipped-past, exact-alarm fallback in use,
notification permission missing, delivery posted/suppressed/failed, and Azan
started/stopped/unavailable/failed.

Levels are used as documented: `info` for normal operation, `warn` for a degraded-but-correct path
(exact alarms unavailable, notifications not permitted, a date with no schedule), and `error` only
for real failures. **Nothing logs a success that did not happen.**

What is never logged: coordinates, the location label, notification text, or anything else private.
The call sites pass counts, slot identities, reason codes and zone ids.
`PrayerNotificationCoordinatorTest` asserts that a coordinate, a longitude and the location label
appear nowhere in the rendered log.

---

## 10. Known limitations, and what belongs to Android

Salah designs for reliability **within** Android's supported capabilities. It does not promise that
a reminder appears at the exact second on every device, and the settings screen says so.

| Limitation | Cause | What Salah does |
|---|---|---|
| A reminder may be a few minutes late. | Exact alarms not permitted, or the device is in Doze. | Uses `setWindow` with a 10-minute window, reports the state, and explains the delay. |
| A reminder may not appear at all. | Notification permission denied, or app notifications turned off. | Reports it, offers the grant action, and never claims otherwise. |
| A reminder may be silenced or hidden. | A channel the user silenced, or Do Not Disturb. | Does not attempt a bypass. States that channel settings belong to the user. |
| Delivery may be delayed further. | Device-maker power management, battery optimisation, or an app the user force-stopped. | Uses the supported APIs and documents that Salah cannot override them. |
| A reminder may be dropped entirely. | The user force-stopped the app; Android then withholds broadcasts until the app is launched again. | The next app launch reconciles and repairs the schedule. |
| No audible Azan. | No licensed recording is bundled (§5.2). | Plays the notification sound the user chose, and says why. |
| A very late reminder is suppressed. | The device was off, or a Doze window landed hours later. | Suppresses anything more than `DeliveryOutcome.STALE_TOLERANCE_MILLIS` (30 minutes) late and logs the reason, rather than showing a notification the user cannot act on. |

Deliberate non-features, for the avoidance of doubt:

- **No full-screen intent.** A prayer reminder never takes over the screen.
- **No Do Not Disturb bypass.** Salah does not request policy access and does not set a channel to
  bypass DND.
- **No hidden APIs, no root, no battery-optimisation exemption prompts.** Only documented, supported
  Android behaviour is used.

### 10.1 Settings the user controls and Salah cannot override

- Sound, vibration and importance of any **notification channel**, once created (§4.3).
- The app-level notification toggle.
- Do Not Disturb, and any per-app or per-channel exceptions to it.
- Battery optimisation and the device maker's power management.
- Whether the app has been force-stopped.

In each case Salah's UI says what may happen rather than implying a guarantee.
