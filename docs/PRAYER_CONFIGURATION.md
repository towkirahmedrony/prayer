# Salah — Remote Prayer Configuration

This document describes Salah's remotely configurable prayer-time calculation settings: what can be
configured, how a published configuration is validated and adopted, how it resolves for a Bangladesh
area, how it interacts with the user's own choices, and what the Supabase schema that publishes it looks
like.

It is the human-readable mirror of the Android contract in
`app/src/main/java/io/github/towkirahmedrony/salah/core/domain/prayerconfig/` and of
`supabase/migrations/20260601000000_prayer_config_publications.sql`.

> **Status.** The migration is authored and reviewable in this repository. It has **not** been applied to
> any Supabase project, so no live database change has been made and no remote configuration has been
> served. The Android code path is implemented and unit-tested against fakes but has **not** been exercised
> against a live project. The build does now carry the project URL and publishable key by default
> (`docs/SUPABASE_SETUP.md` §1), so the check is *attempted* — against an object that does not exist yet it
> fails and the bundled defaults keep working. See `docs/SUPABASE_SETUP.md` for the steps to close that gap.
> Nothing here claims a deployed database.

## 1. What it is, and what it is not

Salah's prayer times are calculated **on the device**, offline, by the existing engine (Adhan Kotlin). This
feature does not replace that engine and does not require a network request to calculate a time. What it
adds is a way for a reviewed, published configuration to supply the *inputs* to that engine — the method,
the Asr convention, the high-latitude rule and per-prayer corrections — so a Bangladesh-wide or
area-specific convention can be applied without a new app release.

A configuration only ever supplies values the current engine can actually apply. Salah does not implement
options the engine cannot represent, and it never fabricates a calculation convention.

## 2. Supported calculation settings

Exactly the inputs the local engine consumes:

| Setting | Supported values |
| --- | --- |
| Calculation method | The eleven methods the engine implements (`KARACHI`, `MUSLIM_WORLD_LEAGUE`, `EGYPTIAN`, `UMM_AL_QURA`, `DUBAI`, `MOON_SIGHTING_COMMITTEE`, `NORTH_AMERICA`, `KUWAIT`, `QATAR`, `SINGAPORE`, `TURKEY`). |
| Asr method (madhhab) | `STANDARD` (majority position) and `HANAFI`. |
| High-latitude rule | `AUTOMATIC`, `MIDDLE_OF_THE_NIGHT`, `SEVENTH_OF_THE_NIGHT`, `TWILIGHT_ANGLE`. |
| Prayer adjustments | Per-prayer corrections in minutes, each within ±`PrayerSettings.MAX_ADJUSTMENT_MINUTES` (60). Applies to the five obligatory prayers only; sunrise, solar noon and sunset are solar events, not prayers, and carry no correction. |

The value of every setting is a `PrayerCalculationProfile` — the same object the engine already takes — so
a remote configuration can never express anything the engine cannot calculate.

## 3. Configuration model

A published configuration is a **complete snapshot**, not a row per area:

```json
{
  "configuration_id": "bd_prayer",
  "revision_id": "rev-2026-10-15-1",
  "revision_number": 4,
  "schema_version": 1,
  "global": {
    "method": "KARACHI",
    "asr_method": "HANAFI",
    "high_latitude_rule": "AUTOMATIC",
    "adjustments": { "FAJR": 0 }
  },
  "areas": [
    {
      "area_id": "BD1001",
      "scope": "DISTRICT",
      "method": "MUSLIM_WORLD_LEAGUE",
      "asr_method": "STANDARD",
      "high_latitude_rule": "AUTOMATIC",
      "adjustments": {}
    }
  ]
}
```

- **`configuration_id`** names the slot (`bd_prayer`). A future country or convention reuses the same schema
  under a different slot.
- **`revision_id`** is the immutable, opaque identifier of one release. It is compared for equality only.
- **`revision_number`** is the monotonic integer used to reject a stale publication.
- **`schema_version`** is the payload layout version. This build supports `{1}`; another version is left
  alone rather than guessed at.
- **`global`** is the Bangladesh-wide default.
- **`areas`** are optional area overrides keyed by the **stable area id** the verified location dataset
  publishes. Editable area names are never used as identity, and coordinates are never a key.

A device already resolves exactly one effective profile for its location, so one downloaded document is
enough: the snapshot is the unit of publication and the unit of activation. It is adopted whole or not at
all.

## 4. Revision rules

1. **Published revisions are immutable.** The database refuses a content change to a row once its status is
   `published`, and refuses deleting it; the app refuses to reuse a revision id whose content differs from
   what it already accepted.
2. **Updating a published configuration creates a new revision** — a new row with a higher
   `revision_number`.
3. **Unsupported schema versions are rejected safely.**
4. **Invalid parameter values are rejected** at parse time: an unknown method, Asr convention, high-latitude
   rule or prayer, or an out-of-range correction, is refused rather than dropped.
5. **Reusing a revision identifier with different content is rejected.**
6. **An older revision cannot silently replace a newer accepted one:** a candidate whose `revision_number`
   is not greater than the accepted one is refused.
7. **Draft or unpublished configurations never become active.** The current-publication pointer may only
   name a `published` row, the view re-filters on status, and the app checks the status itself.
8. **Failed downloads or validation preserve the last known-good configuration.** No branch of the sync
   path writes user data or clears anything.

## 5. Configuration resolution for a Bangladesh area

Resolution uses the **stable area ids** the verified location dataset publishes. The precedence, deepest
first:

```text
explicit user override
  → applicable thana/upazila configuration
  → district configuration
  → division configuration
  → global default
  → bundled default
```

- An override only applies at the scope its own id implies: an upazila override for `BD1001001` never
  applies to a user whose location is only resolved to the district `BD1001`.
- A configuration that references an **unknown area id**, or assigns an override at the wrong level, has
  that assignment **dropped** and falls back safely; the rest of the snapshot still applies. The dropped
  assignment is reported in diagnostics and is never silently applied.
- GPS coordinates are never the key: manual location selection works without the location permission, and
  the resolution reads the area reference the user's saved location already carries.
- The verified location dataset is never regenerated and no location id is ever computed from a name.

If a device has no saved area, the global default (or the bundled default) applies.

## 6. Local preferences and user overrides

A remote publication must never silently overwrite a choice the user made. Salah therefore tracks, per
calculation field, whether the user has explicitly chosen it (`PrayerConfigOverrides`), in the one DataStore
beside the settings:

- When the user changes a calculation control, that field is recorded as user-owned.
- A field the user owns keeps the user's value even when a remote configuration supplies that field; every
  other field comes from the resolved remote chain.
- **Reset to applicable defaults** releases the owned fields, after which the applicable published or
  bundled defaults apply again. Resetting never touches the location, the reminders, the theme or the
  calendar settings.
- The settings screen shows where the effective configuration came from: bundled default, published
  Bangladesh default, published area configuration, or the user's own settings.

For a store written before this feature existed, the owned fields are inferred once (from values that differ
from the bundled default) and then frozen, so a value the user had changed is preserved while a value still
at the default may be supplied by a remote configuration.

The resolution writes the effective profile back into the one settings store, so every existing reader —
Home, the notification coordinator, the startup warm-up — needs no change and the engine keeps consuming the
same `PrayerCalculationProfile` it always did.

## 7. Offline caching and fallback

The remote path is strictly optional and never blocks anything:

1. The stored configuration is read first; the effective settings are available immediately.
2. Prayer times are calculated without waiting for the network.
3. A missing URL, a missing public client key, an offline device, a backend error or an invalid remote
   response all leave the existing configuration and settings in force.
4. The bundled default is the permanent floor: a fresh install with no network and no stored configuration
   calculates exactly as Salah always has.

The stored configuration lives under `filesDir/prayer_config/` and is re-parsed and re-checksummed on every
read, so a truncated or corrupt file is a *detected* condition, not a corrupted configuration — the app
simply has no remote configuration. The synchronisation state (last attempt, last evaluated revision) lives
beside it and is machine state only, never backed up.

## 8. Synchronization sequence

```text
1  fetch the current-publication manifest          (identity, schema, status, checksum shape)
2  validate it
3  apply the revision rules                        (unchanged → download nothing; reused/stale → reject)
4  fetch the payload for the named revision        (only when the revision changed)
5  verify the payload checksum against the manifest's
6  parse the snapshot                              (explicit supported-value mapping)
7  validate against the manifest and the dataset   (drop unknown area assignments, reject the rest)
8  activate it atomically through the local store
9  reapply the effective settings                  (the app recalculates and resigns reminders)
```

The manifest query deliberately omits the payload column, so an unchanged revision costs one small request
and no configuration content. It reuses the same `HttpTransport` as the location-dataset path — this phase
adds a second published slot, not a second networking client. Synchronisation is throttled with the same
conventions as the dataset path (a longer interval after success, a short one after failure, and no
suppression of an explicit user check), runs off the main thread at app start, and never delays the location
gate or the Home screen.

## 9. Supabase schema and security

`supabase/migrations/20260601000000_prayer_config_publications.sql` adds:

- **`prayer_config_publications`** — one row per immutable revision, keyed by
  `(configuration_id, revision_id)`, with `revision_number`, `schema_version`, `status`, `checksum`,
  `payload` (text), `published_at` and `notes`. Check constraints enforce the status set, a positive schema
  version, a non-negative revision number, a SHA-256 checksum shape and non-blank identifiers/payload. A
  trigger refuses content changes to a row once published and refuses deleting a published row.
- **`prayer_config_current_publications`** — the deterministic active-publication pointer, one row per slot,
  with a foreign key `on delete restrict`. A trigger requires the named revision to exist and be `published`.
- **`prayer_config_current_publication`** — a definer view exposing only the current, published row. It is
  the **only** surface an app client reads, and it re-filters on status.
- **Row-level security** is enabled on both tables with **no** policy for `anon`/`authenticated`, and their
  privileges on the base tables are revoked. The view is granted to `anon`/`authenticated` (read) and to
  `service_role`. No Storage bucket is used: the payload travels in the row it belongs to, as `text`, so the
  exact bytes the checksum covers are preserved.

Ordinary anonymous/authenticated Android clients can **only read** the current published row. Creating,
editing, publishing or deleting a configuration is a service-role or migration operation. No service-role
key, secret key or database password ever belongs in the Android client; only the project URL and the
publishable (anon) key are compiled in.

The existing location-dataset tables are untouched. The two slots are deliberately separate: a change to the
location dataset can never disturb a prayer configuration, and the revision numbering of one has no meaning
for the other.

## 10. Required configuration values and publishing

The app needs the same two non-secret values the dataset source uses, supplied at build time (see
`docs/SUPABASE_SETUP.md` §1): the Supabase project URL and the publishable key. With neither set,
`PrayerConfigRemoteConfig.isConfigured` is false and the synchronizer reports "not configured" without
touching the network; with them set but the schema in §9 not deployed, the check is attempted and fails,
which is a different diagnostic from "not configured".

To publish a revision (service role or SQL editor):

1. Produce the canonical payload JSON (the same shape as §3) and compute its SHA-256 lower-case hex
   checksum.
2. Insert a row in `prayer_config_publications` with `status = 'draft'`.
3. Update the row's status to `'published'` (this is the last content change that is allowed).
4. Insert or update `prayer_config_current_publications` to point at `(configuration_id, revision_id)` —
   the trigger requires the revision to be published first.
5. Verify the read path with the publishable key:

   ```bash
   curl -s "https://<ref>.supabase.co/rest/v1/prayer_config_current_publication?configuration_id=eq.bd_prayer&limit=1&select=configuration_id,revision_id,revision_number,schema_version,status,checksum" \
     -H "apikey: <anon-key>" -H "Authorization: Bearer <anon-key>"
   ```

   A one-row JSON array is what the app expects. An empty array means the slot has no current publication.

## 11. Known limitations

- **Not live-verified.** The migration is not applied to any project and the remote path has not been
  exercised against a live endpoint; it is implemented and unit-tested against fakes.
- **No authenticity claim.** The checksum detects accidental corruption, not a malicious publisher. There is
  no signature and no key management.
- **No admin panel.** Publishing is a service-role or SQL operation, as documented above.
- **Bangladesh only.** International locations, Ramadan calendars, Sehri/Iftar scheduling, remote themes and
  timetable downloads are out of scope for this phase.
- **Resolution is by administrative area only.** A device without a saved area resolves to the global
  default; there is no coordinate-level configuration.
