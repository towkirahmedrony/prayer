# Salah — Offline-First Data Reliability & Sync Hardening

This document describes the reliability behaviour of Salah's **optional** remote paths — the published
location dataset and the published prayer configuration — as implemented in this repository. It is a
description of what the code does, with the tests that pin each guarantee named where it matters.

Salah is offline-first: **no remote path is ever on the way to a screen**. The two published slots are
inputs that can make the app better when they are reachable and valid, and are ignored entirely when they
are not. A device with no Supabase URL, no key, no network and no successful synchronisation runs on its
bundled assets and bundled calculation defaults exactly as before this feature existed.

---

## 1. Offline-first guarantees

Each of these is enforced by construction, not by convention:

1. **Valid local data is available immediately.** The UI reads the local store (`LocalDatasetStore`,
   `FilePrayerConfigStore`, DataStore preferences) through `Flow`s. Nothing waits on a network call to
   render.
2. **A network failure never erases or corrupts the active dataset.** The synchronisers have no branch that
   writes user data, clears the stored location, or deletes a dataset. Their only mutation is
   `store.activate`, which writes nothing unless the whole candidate passes validation.
3. **Remote data is validated before activation.** Manifest identity, schema version, published status,
   checksum shape, payload checksum, archive structure and record counts are all checked before the local
   store is asked to commit.
4. **A partial or interrupted download never replaces the active dataset.** The payload is held in memory
   and decoded only after the transport has read it in full under a byte ceiling; the local commit itself
   is a single atomic rename (`docs/ARCHITECTURE.md` §12.3).
5. **An older valid dataset stays usable when a newer one is unavailable or invalid.** The record keeps the
   previous payload slot, and the bundled assets are the floor.
6. **Cached prayer settings remain available offline, and prayer times keep calculating.** The engine is
   local and takes no part in synchronisation.
7. **Connectivity does not imply validity.** A connected device can still receive a malformed manifest, an
   unsupported schema or a checksum mismatch; those are handled as values, never as trust.
8. **A sync failure cannot crash the app or block navigation.** The synchronisers return a
   `DatasetSyncOutcome`/`PrayerConfigSyncOutcome`; an unexpected throw becomes `Failed`, and the app-start
   and foreground triggers wrap the call in `runCatching`.

---

## 2. Dataset synchronisation lifecycle

`DatasetSynchronizer.synchronize(trigger, forced)` is the single entry point. It is serialised by a
`Mutex`, so two triggers at once cannot both download and activate:

```text
1  throttle / offline gate        (cheap, local — see §5 and §8)
2  fetch the current-publication manifest
3  parse and validate it           (identity, schema, published, checksum shape)
4  compare with the active dataset (equal checksum → download nothing)
5  fetch the immutable payload under a size ceiling
6  decode the archive into a DatasetPayload
7  verify the payload checksum against the published one
8  verify the asserted record counts
9  hand the payload to LocalDatasetStore.activate (atomic; §4)
```

Every terminal step routes through `finish(...)`, which records the attempt and the freshness fields (§6)
and logs the outcome. A publication that was downloaded and *terminally* declined is not re-fetched for the
same publication id, so a bad release does not cost a download on every launch.

---

## 3. Manifest, payload and checksum validation

- **Manifest structure and required fields** are checked by `DatasetPublicationParser` (a hand-written JSON
  reader — no JSON dependency) and `DatasetPublicationValidator`; a missing or malformed field yields
  `manifest_malformed`, not an exception.
- **Supported schema versions**: a `payload_schema_version` this build cannot read is refused with
  `unsupported_schema_version` **before** any payload download.
- **Version semantics**: `version` is descriptive and is *never* used for ordering. Which release differs is
  decided from `publication_id`/`checksum` (equality only) — so an unchanged publication is a one-request
  no-op and downloads nothing.
- **Published status** is re-checked in the app even though the server view already filters it.
- **Download metadata** (`payload_size_bytes`) is validated against the size ceiling before the payload is
  requested, so an oversized release costs nothing.
- **Payload integrity**: the payload archive is decoded first, then the checksum of its **logical parts**
  (`DatasetChecksum`) is recomputed and compared to the published value. Compression framing is not trusted.
- **Archive structure**: `DatasetPayloadArchive` reads only the known entries, into memory, without ever
  writing an extracted file, so there is no path-traversal surface — a malformed archive yields
  `payload_malformed`.
- **Size ceilings**: the manifest is capped at 64 KiB (`SupabaseRemoteDatasetSource`), the payload at
  `DatasetSynchronizer.MAX_PAYLOAD_BYTES` (8 MiB). A body over the ceiling is reported as `TooLarge` rather
  than truncated or buffered without bound.
- **Record counts** asserted by the publication are compared to the decoded payload before activation; a
  disagreement is `record_count_mismatch`.

The stable-ID and validation rules are **not** weakened to make a sync succeed. A candidate that would break
the user's stored location is refused (`DatasetValidation`), which is why an update can never strand a
signed-in-to-a-place user on the setup screen.

---

## 4. Atomic activation and recovery

Activation is Phase 1's mechanism, unchanged (`docs/ARCHITECTURE.md` §12.3). A candidate is written to a
fresh `.staging-<n>` directory and flushed, the directory is renamed to `payloads/<checksum>`, and the
**single commit point** is an atomic rename of the `active.tsv` record. Before that rename the old record is
in force; after it the new one is; there is no third state. A process death at any earlier step leaves
unreferenced files, never a broken dataset, and `discardStaging()` removes abandoned staging directories on
the next read. Reading never trusts the record: each named payload is re-read, its checksum recomputed and
compared to its directory name, its version cross-checked and its structure checked, and the previous slot
and then the bundled assets are tried in turn.

`LocalDatasetStoreTest` covers successful activation, a failed validation that preserves the active dataset,
recovery from the previous slot, abandoned-staging cleanup, and metadata consistency; the synchroniser tests
cover the network-integrated paths.

---

## 5. Retry, backoff and timeouts

**Bounded, never a loop.** A check runs at application start-up and on foreground, at most once per computed
interval, and never on a timer or a recomposition.

- **Success** suppresses the next check for `successIntervalMillis` (12 h).
- **Transient failure** is retried after `failureIntervalMillis` (15 min), doubled on each **consecutive**
  transient failure (`consecutiveFailures` in the sync state) and capped at `maxFailureIntervalMillis`
  (6 h). This is what stops a device that has been offline for an hour from retrying on every foreground
  trip and producing a retry storm when connectivity returns.
- **Jitter** (up to a fifth of the interval, injected through `SyncThrottlePolicy`/`PrayerConfigThrottlePolicy`)
  spreads retries that would otherwise coincide across devices. It is injected, not drawn from a global RNG,
  so the unit tests pin it to zero and assert exact thresholds.
- **Only transient failures grow the backoff.** A malformed manifest, an unsupported schema, a checksum
  mismatch or a refused candidate is *terminal*: repeating the same fetch could not fix it, so it is not
  retried for that publication and does not inflate the delay before a legitimate new one.
- **A user-initiated (`forced`) check is never suppressed** by the throttle or the backoff — an explicit
  "check now" that silently did nothing would be worse than a redundant request.

**Timeouts and HTTP resources.** `UrlConnectionHttpTransport` sets the connect and read timeout from
`config.requestTimeoutMillis`, closes the stream in a `finally` (and the error stream on a non-success
status), disconnects the connection on every path, and **does not follow redirects** — the URLs are computed
from configured values, so following a `Location` header would let a response redirect the app to another
host. A non-2xx status is reported as its status with an empty body, never parsed as a payload. The read is
bounded and the whole call runs on `Dispatchers.IO`, so the main thread is never blocked. No Retrofit,
OkHttp, Ktor or Supabase SDK is introduced for two GETs.

---

## 6. Cache freshness and last-known-good data

The sync state (`FileDatasetSyncStateStore` / `FilePrayerConfigSyncStateStore`) records, as machine state
outside the backup set:

| Field | Meaning |
|---|---|
| `lastAttemptAtEpochMillis` | When a check last ran, success or failure. |
| `lastSuccessAtEpochMillis` | When a check last confirmed or adopted the data. Moved **only** by a success. |
| `lastAttemptedPublicationId` / `lastAttemptedRevisionId` | Which release the last attempt evaluated. |
| `lastFailureCode` | The failure code of the last attempt, or `null` on success. |
| `consecutiveFailures` | Consecutive transient failures; drives the backoff. |

**Last attempt and last success are deliberately different facts.** A failed request moves the former and
leaves the latter, so stale-but-valid data is reported as stale rather than as freshly synchronised. The
file store is forward/backward-compatible: a file written before the freshness fields existed reads them as
defaults, and a malformed or absent file degrades to the default state rather than throwing.

There is **no arbitrary expiry** that would make essential offline data unusable: valid data stays usable
indefinitely, and the freshness fields describe when it was last confirmed, not when it stops being valid.

---

## 7. Remote prayer configuration

`PrayerConfigSynchronizer` follows the dataset lifecycle for a different payload and does **not** touch the
calculation engine. It:

- validates the manifest and its schema before accepting it;
- refuses a revision that reuses an accepted id with different content, or that is not newer, without
  downloading it;
- verifies the payload checksum before interpreting the snapshot;
- validates the snapshot against the manifest and the active dataset (dropping unknown area assignments and
  rejecting the rest);
- activates through the local store and then invokes `onActivated` so the **existing** applier recomputes
  effective settings;
- preserves the established precedence — **user override → upazila → district → division → global → bundled**
  — and the bundled calculation defaults are never changed silently.

A failed sync leaves the accepted configuration exactly as it was; an invalid response never replaces a valid
cached one. Updates become effective through the existing `PrayerConfigApplier` flow, so a calculation or a
reminder reconciliation already in flight uses a consistent profile.

---

## 8. Connectivity-aware synchronisation

`NetworkStatusProvider` answers one cheap, local question — "is there *obviously* no network?" — and the
synchronisers consult it **after** the throttle and **before** recording an attempt. If it reports offline,
the attempt returns `Offline`, no request is made, and **no attempt is recorded**: an offline skip is not a
failure, it leaves the last-success timestamp untouched, and returning to connectivity allows the next check
immediately rather than after a backoff window.

The Android implementation (`AndroidNetworkStatusProvider`, over `ConnectivityManager`, needing the normal
`ACCESS_NETWORK_STATE` permission) fails **open**: a missing service, no active network or a missing internet
capability is the only thing that returns `false`, so a connected-but-captive portal passes the gate and is
caught by the transport's own failure handling. There is **no continuous connectivity observer** — it is a
single synchronous lookup, not a registered callback — and the first Home render never waits on it.

---

## 9. Concurrency and race-condition protection

- **One sync at a time.** Both synchronisers wrap `attempt(...)` in a `Mutex`, so a manual refresh
  overlapping a background sync, two triggers close together, or a restart-during-download all serialise. The
  second observes the first's result (the active checksum now matches, or the state says it was declined) and
  does no redundant work.
- **Active data stays readable during staging and validation.** The mutex is held across the candidate's
  validation and commit, but the active dataset is only *read* while staging; a reader therefore never sees a
  half-written payload, because the payload is only referenced after the atomic record rename.
- **Cancellation is respected.** Both synchronisers rethrow `CancellationException` rather than converting it
  to a failure, so a cancelled check does not record a spurious outcome.
- **Cleanup never removes files in use.** Housekeeping deletes only unreferenced `payloads/<checksum>`
  directories and `.staging-*` directories; the active and previous slots are always kept, and unknown files
  are left alone.
- **Activation is idempotent.** Re-activating the same content is `AlreadyActive` and writes nothing.

---

## 10. Diagnostics

Outcomes of interest are recorded through the existing `DiagnosticsLogger` port, under the stable events in
`SalahDiagnosticEvents`:

| Event | Meaning |
|---|---|
| `dataset_sync_started` / `prayer_config_sync_started` | An attempt began, with its trigger. |
| `dataset_sync_up_to_date` / `prayer_config_sync_up_to_date` | Nothing to change; no payload downloaded. |
| `dataset_sync_activated` / `prayer_config_sync_activated` | A validated release became active. |
| `dataset_sync_rejected` / `prayer_config_sync_rejected` | A release or candidate was refused, with codes. |
| `dataset_sync_failed` / `prayer_config_sync_failed` | The attempt failed, with its reason code. |
| `dataset_sync_throttled` / `prayer_config_sync_throttled` | Suppressed by the throttle/backoff. |
| `dataset_sync_offline` / `prayer_config_sync_offline` | Clearly offline; no request attempted. |
| `dataset_sync_retry_scheduled` / `prayer_config_sync_retry_scheduled` | A transient failure scheduled a backed-off retry. |
| `dataset_sync_cached_data_used` / `prayer_config_sync_cached_data_used` | A check failed or was refused; logged with the reason code and what remains in force (dataset source and version, or configuration revision / `none`). |

**Failure vocabulary.** Each outcome names the category rather than collapsing them into "unavailable":
`not_configured` (no usable endpoint/key), `offline` (no request attempted), `timeout`, `access_denied`
(HTTP 401/403), `endpoint_not_found` (the view/table is missing — *not* an empty slot, which is `200 []`
and `up_to_date`), `manifest_malformed` / `content_malformed` (unreadable JSON), `checksum_mismatch`,
`revision_reused_with_different_content` / `stale_revision`, and `payload_not_found` / `content_not_found`
(a publication row naming a payload that is not served). A failed check never writes `last_success`, so a
later `up_to_date` is the only thing that reports a fresh success.

**Never logged:** the anon key (it is a request header and is never passed to the logger — asserted by
`the publishable key is never written to the log` in both synchroniser tests), any payload content, or any
location coordinates. Offline operation is distinguished from a data-integrity failure: `offline` is an
info-level expected state, while an integrity failure is a warning with a code.

---

## 11. Resource and storage safety

- Payloads are decoded in memory and never extracted to disk, so there is no unbounded temp-file
  accumulation from archives.
- The only temporary files are the sibling `.tmp` files used for atomic record/state writes (removed by the
  rename, or deleted on failure) and `.staging-*` directories (removed by `discardStaging()`).
- Housekeeping runs on read and after a commit, and deletes only directories that are provably Salah's own
  (a 64-hex checksum name, or the staging prefix). A file belonging to another feature is left untouched.
- A storage failure to write or rename a staged file is reported as `activation_failed`/`storage_failed`; it
  never deletes user data to recover. Insufficient storage is such a failure: the active dataset and the
  recovery slot are preserved.

---

## 12. Tests, and how to run them

The reliability behaviour is covered by JVM unit tests — no live Supabase, no device, no network:

- `DatasetSynchronizerTest` — the full sequence against fakes: every validation failure, the unchanged-
  publication no-download rule, the terminal-decline rule, transient retry, **offline skip**, **last-success
  vs. last-attempt**, **backoff counting**, and **two concurrent triggers downloading once**.
- `PrayerConfigSynchronizerTest` — the mirror, plus the revision rules and override precedence.
- `SyncThrottlePolicyTest` / `PrayerConfigThrottlePolicyTest` — fixed intervals, **exponential backoff**,
  the cap, injected jitter and the forced-check override.
- `FileDatasetSyncStateStoreTest` / `FilePrayerConfigSyncStateStoreTest` — round-trip, corrupt-file tolerance,
  atomic write, and **backward compatibility** with a file written before the freshness fields existed.
- `DatasetPayloadArchiveTest`, `DatasetValidationTest`, `DatasetPublicationTest`, `JsonReaderTest` — archive
  safety, malformed payloads, manifest parsing and validation.
- `LocalDatasetStoreTest` — atomic activation and recovery.

Run them with the repository's own workflow (tests + lint + debug APK):
`.github/workflows/android-apk.yml` runs `./gradlew testDebugUnitTest lintDebug assembleDebug` on every push
to `main`. The APK is not built locally.

---

## 13. Known limitations

- **No live Supabase project is configured for this repository**, so neither migration has been applied and
  no publication has been served or downloaded. The remote path is implemented and unit-tested against
  fakes; it is **not** live-verified. See `docs/SUPABASE_SETUP.md`.
- **The backoff is per-attempt, not persisted across uninstalls**, and it is bounded by the throttle rather
  than driven by an OS scheduler — there is deliberately no `WorkManager` job for it, because the app already
  reconciles at start-up and on foreground and a background scheduler would duplicate that.
- **Connectivity detection is a hint.** A captive portal or a flaky network passes the gate and is caught by
  the transport; a false "offline" is avoided by failing open, at the cost of occasionally attempting a
  request that cannot succeed.
- **Power-loss durability is best-effort**, as described in `docs/ARCHITECTURE.md` §12.3: the commit point is
  a rename, and the directory flush narrows the window rather than defining the guarantee.
