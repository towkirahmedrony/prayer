# Salah — Tasbih Counter

Phase 10. A digital counting tool: a count, a target, a progress reading, a session that survives the app
being closed, and a local history of finished sessions.

Reached from **Tools** and from nowhere else — `SalahDestination.TASBIH`, route `tasbih`, not a bottom-bar
tab.

---

## 1. Scope, and what this document does *not* contain

**Salah counts. It does not recite.** The feature is a counter: it records how many times the user tapped,
against a target they chose. There is no dhikr text, no transliteration, no translation, no guidance about
what to say or how many times to say it, and no religious ruling of any kind — in the UI, in the resources,
in the tests or in this document.

That is a deliberate boundary and not an omission. Inventing or transcribing devotional content is a claim
about correctness that this project cannot verify, and the app's existing rule for such content applies
unchanged: what cannot be stated honestly is not stated at all (the same reason `tools_unavailable_note`
exists, and the same reason the Azan audio source reports *unavailable* rather than shipping an unlicensed
recording — see `docs/PRAYER_NOTIFICATIONS.md` §5).

The shipped default target is **33**. It is a default value in a settings-like control and nothing more: the
user can change it at any time, the app never suggests a number, and no string in either language explains
why 33 might be a meaningful count.

The feature is **entirely offline**. No account, no network, no synchronisation, no analytics. The only
network code in Salah remains the two optional publication checks described in
`docs/DATA_RELIABILITY.md`; the counter does not touch either of them.

---

## 2. Where the code lives

| File | Role |
|---|---|
| `core/model/TasbihSession.kt` | The session, its invariants and `normalized()` |
| `core/domain/tasbih/TasbihCounter.kt` | Every operation on a session, as pure functions |
| `core/domain/repository/TasbihRepository.kt` | The port: one active session, the history, the haptics preference |
| `data/tasbih/TasbihSessionCodec.kt` | The stored form of a session, and its reader |
| `data/tasbih/DataStoreTasbihRepository.kt` | The implementation, over Salah's one `DataStore` |
| `feature/tasbih/TasbihUiState.kt` | What the screen draws |
| `feature/tasbih/TasbihUiStateFactory.kt` | Stored session + locale → renderable values |
| `feature/tasbih/TasbihViewModel.kt` | State assembly and one repository call per gesture |
| `feature/tasbih/TasbihScreen.kt` | The screen |
| `core/designsystem/components/SalahComponents.kt` | `SalahProgressBar`, added for the progress track |
| `core/designsystem/SalahGlyphs.kt` | `TasbihBeadsGlyph`, the counter's own mark |

The layering is the one `docs/ARCHITECTURE.md` §4 states: `feature` depends on `core`, `data` implements the
ports `core` declares, and nothing in `core` knows about Android. The counter adds no module and no Gradle
dependency.

---

## 3. The session

A `TasbihSession` is the unit of work:

| Field | Meaning |
|---|---|
| `id` | Stable identifier, unique within the stored history |
| `count` | How many times the user has counted. Never negative |
| `target` | What this session is counting towards |
| `startedAtMillis` | Epoch milliseconds; reset when the count is reset |
| `updatedAtMillis` | Epoch milliseconds of the last change to `count` |
| `completedAtMillis` | When the target was *first* reached, or `null` |

Derived: `isComplete`, `progress` (0..1, clamped), `remainingToTarget`, `canCount`.

### Bounds

| Constant | Value | Why |
|---|---|---|
| `DEFAULT_TARGET` | 33 | The shipped default; also the first entry of `TARGET_CHOICES`, so the two cannot drift |
| `MIN_TARGET` | 1 | A target of zero would make progress undefined |
| `MAX_TARGET` | 100 000 | Every target is a value the picker can represent |
| `MAX_COUNT` | 999 999 | Makes integer overflow unrepresentable, and keeps the reading to six digits |
| `HISTORY_LIMIT` | 30 | The retention policy (§6) |

**Integer overflow is prevented structurally, not caught.** `TasbihCounter.counted` checks the ceiling
*before* adding, so `count + 1` is only ever evaluated on a value below `MAX_COUNT` and cannot wrap. A count
at the ceiling is returned unchanged — the count did not move, so `updatedAtMillis` does not move either, and
the caller can skip the write.

### `normalized()`

The single place the invariants are restored, applied on both sides of the store: `count` and `target` are
clamped, a blank identifier becomes `DEFAULT_SESSION_ID`, `updatedAtMillis` is pulled forward to
`startedAtMillis` if it is earlier, and `completedAtMillis` is made present exactly when the count says the
target was reached — using the session's own last update when a payload contradicts itself. It is idempotent,
so applying it twice is applying it once.

On the **read** side this is what makes a hand-edited or downgraded store harmless. On the **write** side it
is what stops an out-of-range in-memory value from reaching the disk.

---

## 4. The counting rules

Every operation is a pure function of a session and a moment, in `TasbihCounter`. Nothing there reads a
clock, a preference or a device.

| Operation | Rule |
|---|---|
| `counted` | `count + 1`, capped. Stamps `completedAtMillis` the **first** time the target is reached. Counting on past the target is allowed and does not rewrite that stamp |
| `undone` | `count - 1`, never below zero. Dropping back below the target clears `completedAtMillis` |
| `reset` | `count = 0`, keeps `id` and `target`, restarts `startedAtMillis`, clears completion. **Not** a new session, and nothing is archived |
| `retargeted` | Sets the target (clamped) and keeps the count. Completion is re-derived: lowering the target below the count completes the session, raising it above un-completes it |
| `restarted` | The replacement session: a new `id`, `count = 0`, and the **previous target carried forward** |
| `archivable` | The session, or `null` when `count == 0` |
| `retained` | The newest `HISTORY_LIMIT` entries, by taking a prefix rather than sorting — see §6 |

**Timestamps are monotonic.** Every stamp is `max(current, now)`, so a device clock that steps backwards (a
time-zone change, a manual correction, a network-synchronised clock) cannot record an update before the
session it belongs to started.

---

## 5. Persistence

### One DataStore, three keys

The counter stores into the **same** `DataStore<Preferences>` file as every other Salah preference — the
instance bound once in `SalahModule` — under three namespaced keys:

| Key | Type | Contents |
|---|---|---|
| `tasbih_active_session` | String | The session being counted |
| `tasbih_history` | String | The finished sessions, newest first |
| `tasbih_haptics_enabled` | Boolean | The haptics preference (absent means on) |

Clearing the history **removes** `tasbih_history` rather than writing an empty string, so "no history" has
exactly one representation in the store.

A second DataStore file was the alternative and was rejected: it would need its own instance, its own
corruption story and its own place in the backup rules, for three values that are neither large nor hot, and
it would break the rule the container states for this store — one DataStore for every preference in Salah,
because a second store could disagree with the first (`di/SalahModules.kt`). `DataStoreTasbihRepositoryTest` asserts the consequence directly: writing
the counter adds only the counter's own keys, and leaves the theme, the calculation settings and the interface
language exactly where they were.

The counter is *not* folded into `DataStoreUserPreferencesRepository` either. That class is the settings
repository — options a user chooses once — while the active session is the data a screen is built around. The
two share the store, which is what keeps "one file" true; they do not share a class, which is what keeps each
one's responsibilities legible.

### The stored form

One session is six fields separated by `|`; a history is sessions separated by `;`:

```
session-id|count|target|startedAtMillis|updatedAtMillis|completedAtMillis
```

`completedAtMillis` is written as `-` when the session has not completed — a visible token rather than an
empty field, so the format's field count does not depend on how a split treats a trailing separator.

A delimited line rather than JSON: Salah has no JSON library on the classpath (its one hand-written parser
reads published datasets, which are genuinely nested documents), a session is six flat fields, and the Home
dashboard already stores its layout the same way.

**Decoding is total, and history decoding is per entry.** `decode` answers `null` for anything it cannot
read — a wrong field count, a non-numeric field, a blank identifier, an identifier containing a delimiter —
and `decodeHistory` drops the entries it cannot read while keeping the ones it can. A damaged payload
therefore costs at most the entries that were damaged; it cannot take the screen down, and it cannot produce
a session read with the wrong count. Whatever is recovered is passed through `normalized()`.

Identifiers are `UUID`s, which contain neither delimiter by construction; a payload whose identifier does
contain one could not have been written by this codec, so it is rejected rather than guessed at.

### Writes

Every mutation is a **transform applied inside the store's own transaction**, never a value the caller read
earlier:

* `updateActiveSession(expectedId, transform)` reads the stored session, applies the transform, and writes
  the result — all in one `DataStore.edit`. Two counts that interleave therefore both land, in either order.
* The moment passed to the transform comes from the repository's clock *inside* the transaction, so
  `updatedAtMillis` is when the value was written rather than when the tap was seen.
* When the stored session's identifier is not `expectedId`, the write is **dropped**: an update computed for
  a session that has since been replaced cannot move a count into its successor.
* `startNewSession` archives the old session and installs the new one in **one** transaction, so the counter
  can never be observed with a finished session missing from both places.
* `clearHistory` names the history key and no other. The active session is not read, not rewritten and not
  touched.

### Why one write per tap, and not batching

Counting writes through on every tap: no debounce, no flush-on-pause, no in-memory counter the disk catches
up with later.

This is a decision, not an oversight. The existing architecture offers no *safe* batching here: any scheme
that holds counts in memory and writes them later has a window in which the number the user sees is not the
number on disk, and the window closes only when the process happens to die politely. The taps are
human-paced — a few per second at the very most — so the cost being avoided is one small preference write per
tap, while the guarantee being risked is the number itself.

---

## 6. History and retention

A finished session is archived only when **`startNewSession`** is used. Resetting the count archives nothing.

`TasbihCounter.archivable` excludes a session with `count == 0`: it would record that the user opened the
screen and started a session without counting, which is a row that says nothing. Every session with a count —
complete or not — is archived, because an unfinished count is something the user did.

**Retention policy: the 30 most recently finished sessions** (`TasbihSession.HISTORY_LIMIT`). The rule is
applied in one place (`TasbihCounter.retained`), on the way in *and* on the way out, so a history written
before the limit existed, or restored from a backup, is trimmed before the screen sees it. It bounds both the
size of the one stored string and the length of the list the screen draws, and it is stated on the screen
itself in the interface language, so a user with a thirty-first session can find out why it is not there
without reading this document.

The order is the stored order — newest first — rather than a sort, so entries whose timestamps tie cannot
reorder themselves between two reads of the same stored value.

Clearing is a confirmed action, and it is stated to leave the count in progress alone — which it does,
because the write names one key.

---

## 7. Haptics

A count plays one short haptic when the preference is on and the platform provides one.

* **Optional, and off is a real state.** `tasbih_haptics_enabled` is stored beside the session; the screen
  offers it as a switch, and counting behaves identically with it off.
* **No permission is requested.** Salah already declares `VIBRATE` for its prayer reminders, and a haptic
  performed through the view requires nothing further. Marking haptics as *required* would also make the
  feature depend on hardware the app does not need.
* **The platform's own preference is honoured underneath.** `LocalHapticFeedback` routes to
  `View.performHapticFeedback`, which respects Android's touch-feedback setting and is a no-op where it is
  disabled.
* **Nothing long-running.** One `HapticFeedbackType.LongPress` per accepted tap. No animation callback counts
  anything, so no haptic can produce a second increment.

---

## 8. UI, theming and accessibility

The screen is a single scrolling column, drawn inside the shell's `Scaffold`, which is where the system insets
are applied — the counter adds none of its own.

**Hierarchy.** The reading first: the count in the display typeface, the target beneath it, and a progress
track under both. Then the one action the screen exists for — a full-width count control, at least 96dp tall
— then the small corrections (undo, reset, new session), then the target picker, the haptics switch and the
history last, in the order they are reached.

**The count's type size follows the device's font scale** (`TasbihScreen.countTextStyle`), stepping from
`displayMedium` to `displaySmall` to `headlineLarge`. A six-digit reading at the largest setting would be
clipped by a single fixed style, and a reading that is cut off is worse than a smaller one. The step is chosen
from the *scale* rather than from the digit count, so the number does not change size as it is counted.

**Contrast is never the only signal.** Completion is a worded pill, not only a colour; the progress track has
a described role; the reading is stated in words for a screen reader.

**Accessibility.**

* The label, the number and the target are **one** merged node whose description is
  `tasbih_count_accessibility` — "Current count 33, target 33" — so the whole reading is one focus stop
  rather than three. It is marked as a polite live region: the number is the *result* of the action, so a
  counter that never announces it is a counter a screen-reader user cannot use.
* The progress track carries `tasbih_progress_accessibility`.
* A target option is `selectable` with the radio-button role, described as "target 33", so the selected one
  is announced as selected.
* A history row is one merged node whose description states the date, the count, the target and the outcome
  as a sentence.
* Every control is at least 48dp tall; the count control is 96dp.
* The three confirmations use one `ConfirmationDialog` component, so a cancel that behaved differently in one
  of them is not possible.

Two components were added to the design system for this screen and are documented there: `SalahProgressBar`
(two nested rounded boxes — deliberately unanimated, because a bar that eases towards its new position spends
part of every count disagreeing with the number beside it) and `TasbihBeadsGlyph` (the counter's own mark,
distinct from the Tools tab's `TasbihGlyph`, so a row does not carry the icon of the tab it is under).

---

## 9. Localization

Bengali and English, through the existing resource architecture: `values/strings.xml` and
`values-bn/strings.xml`, with `MissingTranslation` an error (see `app/lint.xml`).

Every string the feature renders is a resource. No composable under `feature/tasbih` renders a literal
sentence, and `LocalizationResourcesTest` asserts it — the `tasbih` package and the `tasbih_` key prefix were
added to that test's scope, so a Tasbih string added without a Bengali value now fails a test rather than
silently falling back to English.

**Numerals are localised, and the two halves of a screen cannot disagree.** Counts, targets, what remains,
the retention limit and the digits of a history timestamp all go through `CalendarFormatting` — the same rule
the calendar, the Ramadan table and the Home timetable use. A Bengali interface therefore reads `৩৩` where an
English one reads `33`. Neither a second digit table nor a platform formatter is used: the first would be a
second answer that could disagree with the dates beside it, and the second renders `bn` with Western digits
on the JVM (see `docs/LOCALIZATION.md`).

A history timestamp is rendered in the **device's own** zone, not the configured location's. Every other time
in Salah is a *prayer* time and belongs to the place it was calculated for; a counting session belongs to the
person holding the phone, and "when did I count this" is a question about the wall clock they were looking at.
The clock format follows the app's 12/24-hour preference.

---

## 10. Testing

Deterministic JVM unit tests — no device, no network, no APK. `./gradlew testDebugUnitTest` locally, and the
same task plus lint runs in `.github/workflows/android-apk.yml` on every push to `main` (the APK itself is
built on the runner, never locally).

| Test | Covers |
|---|---|
| `TasbihCounterTest` | Increment, decrement, no negative counts, completion, counting past the target, the first completion time, reset vs. new session, retargeting both ways, the ceiling, clock steps backwards, archiving, retention |
| `TasbihSessionTest` | Every invariant `normalized()` restores, progress clamping, a target that cannot be divided by, the bounds of the offered targets |
| `TasbihSessionCodecTest` | Round trips, the documented field layout, normalisation on encode, and every way a payload can be unreadable — wrong arity, non-numeric fields, blank or delimiter-carrying identifiers — plus per-entry recovery of a damaged history |
| `DataStoreTasbihRepositoryTest` | The real store over a real file: write-through, survival across a restart, ten unwaiting increments all landing, the stale-identifier drop, atomic archive-and-replace, retention, clearing without touching the session in progress, an unreadable payload, and that no other preference moves |
| `TasbihUiStateFactoryTest` | Western and Bengali numerals, the progress fraction, completion and "counted past", the target list with a non-offered target added, an English and a Bengali history row, the 12/24-hour preference, normalisation before rendering |
| `TasbihViewModelTest` | Loading → Ready, the reading following the store, each action making its one call, rapid counts, an action taken before the store has been read being dropped, and a failed write being reported instead of swallowed |
| `LocalizationResourcesTest` | The Tasbih keys in both files, Bengali script where words are expected, identical format arguments, and that no composable renders a hardcoded sentence |
| `SalahBottomNavigationTest` | That the counter is offered exactly once from Tools and is not a bottom-bar tab |

The store's tests use a fixed clock, so every timestamp asserted is an exact value rather than a range.

---

## 11. Deliberate boundaries

* **No religious content.** §1. This is the most important entry here.
* **No accounts, no sync, no cloud.** The counter is local; a history is not a thing to synchronise.
* **No Room, no new module, no new dependency.** Three preference keys in the store Salah already has.
* **No custom target entry.** The picker offers five values plus whichever one the user already has. A text
  field would add parsing, validation, an input-method surface and a second way for a target to be wrong —
  for a control whose whole content is a number.
* **No animation, and no counting from an animation.** Every counted increment is a gesture on a single
  `Button` with one `onClick`.
* **No Home daily-tools tile.** The counter is reached from Tools. Home's row is a shortcut list for the four
  destinations it already names, and adding a fifth tile is a change to Home this phase did not make.
* **No test that requires a device.** Haptics, insets and large-font rendering are reasoned about and
  documented here; the CI job builds the APK and runs the JVM tests, and nothing in this document claims a
  device test that has not been run.
