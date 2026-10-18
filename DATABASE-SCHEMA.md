# Salah — Remote Dataset Database Schema

This document describes the Supabase/PostgreSQL schema that Salah publishes its remotely managed
datasets through, and the access model that keeps it read-only for app clients. It is the human-readable
mirror of `supabase/migrations/20260515000000_dataset_publications.sql` and of the Android contract in
`app/src/main/java/io/github/towkirahmedrony/salah/core/domain/dataset/remote/DatasetPublication.kt`.

> **Status.** The migration is authored and reviewable in this repository. On 2026-10-18 the live Salah
> project answered an `anon` read of `dataset_current_publication` with **HTTP 200 and `[]`**: the view
> exists, exposes every column the client selects, and has **no current publication**. Whether it was
> created by this migration or by another route cannot be determined without SQL access, and no release has
> been published, so no payload has ever been served and no adoption is verified. Configuring the client is
> not the same as publishing a release. See `docs/SUPABASE_SETUP.md` for the steps to apply the migration
> and publish, and `docs/SUPABASE_CONTRACT_MATRIX.md` §5 for exactly what the live read established.
>
> **Phase 3 adds a second published slot.** The remote prayer-time calculation configuration has its own
> migration, `supabase/migrations/20260601000000_prayer_config_publications.sql`, its own tables and view,
> and its own contract in `core/domain/prayerconfig/`. It follows the same publication pattern documented
> here — immutable revisions, a single current-publication pointer, a definer view as the only client
> surface, and read-only RLS for app clients — but the two slots are deliberately independent, so a change
> to one cannot disturb the other. See `docs/PRAYER_CONFIGURATION.md`.
>
> **A third read, with a different shape.** The published gold and silver reference prices the Zakat screen
> reads (`supabase/migrations/20261018000000_metal_prices.sql`) are a *current value* rather than a
> revisioned publication, so that slot uses no pointer table, no revision id and no view: one table with a
> read-only select policy, and an app that selects one active verified rate per metal, purity and currency.
> `docs/SUPABASE_CONTRACT_MATRIX.md` lists all three contracts side by side with what is verified about each;
> `SupabaseContractParityTest` checks each migration's columns against the client that reads them.

## 1. What the schema is for

Salah's Android app keeps a **bundled, verified** dataset and, optionally, an **applied** dataset it
validated and activated locally (Phase 1). Phase 2 adds a *publication source*: a place where a reviewed,
verified dataset can be published once and adopted by apps that opt into checking for it.

The schema exists to answer exactly one question for the app — **"which publication is currently served
for this dataset, and what does it contain?"** — while making it impossible for an app client to publish,
edit or delete anything.

The design is deliberately generic over `dataset_id`. This phase publishes `bd_locations` only, but the
same tables are intended to carry future published data (prayer-time profiles, themes, Ramadan calendars)
in other slots, without a schema change.

## 2. Objects

### 2.1 `public.dataset_publications`

One row per **immutable** release. Keyed by `(dataset_id, publication_id)`.

| Column | Type | Meaning |
| --- | --- | --- |
| `dataset_id` | `text` | The dataset *slot*, e.g. `bd_locations`. Names the kind of data, not a release. |
| `publication_id` | `text` | The opaque, immutable identifier of one release. Compared for equality only. |
| `version` | `text` | The human-readable dataset version (the string the bundled assets carry). Descriptive. |
| `payload_schema_version` | `integer` | The payload layout version. The app supports `{1}`. |
| `status` | `text` | `draft` \| `published` \| `archived`. Only `published` may be served. |
| `checksum` | `text` | SHA-256 (lower-case hex) of the payload's **logical parts** (see §3). |
| `payload_path` | `text` | Storage object path of the immutable archive, relative to the `datasets` bucket. |
| `payload_size_bytes` | `bigint` | Advisory size, so a client can refuse an implausibly large payload. |
| `area_count` | `integer` | Completeness assertion (when recorded). |
| `locality_count` | `integer` | Completeness assertion (when recorded). |
| `boundary_ring_count` | `integer` | Completeness assertion (when recorded). |
| `published_at` | `timestamptz` | When the release was published. |
| `notes` | `text` | Operator-facing notes. Never displayed to the user. |
| `created_at` | `timestamptz` | Row creation. |

Check constraints enforce the non-negotiables at write time: `status in ('draft','published','archived')`,
`payload_schema_version >= 1`, `checksum ~ '^[0-9a-f]{64}$'`, non-blank identifiers and paths, and
non-negative sizes/counts.

### 2.2 `public.dataset_current_publications`

Exactly one row per dataset slot: the **current publication pointer**. Primary key `(dataset_id)`, with a
foreign key to `dataset_publications(dataset_id, publication_id)` `on delete restrict`.

A dedicated pointer table rather than a boolean on the publication row, because a boolean allows two rows
to claim "current" at once, which makes "which publication is active?" ambiguous — the one question this
table exists to answer unambiguously.

A `before insert or update` trigger (`dataset_current_publications_require_published`) refuses to point at
a row that does not exist or is not `published`. That is the database-side guarantee that a draft or an
archived release can never be made current, independent of any client.

### 2.3 `public.dataset_current_publication` (view)

The **only** surface an app client reads. It joins the pointer to the publication and re-filters
`status = 'published'`, so a misconfigured pointer still cannot expose a draft. Its columns are exactly
the manifest fields the app parses (§4).

The view is a **definer** view (the PostgreSQL default; *not* `security_invoker`) owned by the migration
role. That owner is not subject to the row-level security of the base tables, which is what lets clients
read through the view while having no direct access to the tables at all.

## 3. The checksum is over the payload's logical parts

`checksum` is not a hash of the archive file. It is the `DatasetChecksum` of the payload's parts — its
version file and the three TSV tables — computed the same way on both sides:

```text
for each part (name, content) in fixed order:
    <len(name_bytes)> ':' <name_bytes> ':' <len(content_bytes)> ':' <content_bytes> '|'
```

The app recomputes this from the *decoded* payload, so compression framing (entry order, timestamps,
level) is not part of what is trusted. `tools/publish_bd_location_dataset.py` reproduces the same
algorithm, and `DatasetChecksumTest` locks it to a golden vector the tool produced — if the two ever
diverged, every legitimate publication would be rejected with a checksum mismatch.

The checksum detects **accidental corruption**; it is not proof of authenticity. Anyone who can alter the
payload can alter the checksum beside it, and this phase makes no signature claim. See
`docs/ARCHITECTURE.md` §12.4.

## 4. The Android manifest contract

The app queries:

```text
GET {SUPABASE_URL}/rest/v1/dataset_current_publication?dataset_id=eq.bd_locations&limit=1&select=*
apikey: {SUPABASE_ANON_KEY}
Authorization: Bearer {SUPABASE_ANON_KEY}
```

and reads the single row. The column names it understands are the view's columns above. Unknown columns
are ignored; a missing **required** field (`dataset_id`, `publication_id`, `version`,
`payload_schema_version`, `status`, `checksum`, `payload_path`) makes the manifest *malformed* and the
app keeps its current dataset.

The app then fetches the payload from Storage:

```text
GET {SUPABASE_URL}/storage/v1/object/datasets/{payload_path}
```

## 5. Access model

**Read-only, and only through the view.**

- Row-level security is enabled on both tables with **no policy** for `anon`/`authenticated`. With RLS on,
  the absence of a policy denies every row — so even a client that somehow held a base-table privilege
  could read nothing and write nothing.
- All privileges on the base tables are explicitly `revoke`d from `anon` and `authenticated`.
- `select` on the view is granted to `anon`, `authenticated` **and** `service_role`.
- Publishing (insert/update/delete on the tables, and reading drafts) is a `service_role` operation and is
  **not** granted to `anon`/`authenticated`.

**Storage.** The `datasets` bucket is created with `public = false`, so there is no unauthenticated public
URL. A single policy grants `select` on `storage.objects` for `bucket_id = 'datasets'` to `anon` and
`authenticated`; uploading and deleting objects is not granted to any client role. Payload objects are
immutable by convention — a new publication writes a new path
(`<dataset_id>/<publication_id>/payload.zip`) and never overwrites a path an existing publication row
references.

**Credentials.** The Android app carries only the **publishable/anon** key (`SUPABASE_ANON_KEY`). That key
is designed to be embedded in a client and is constrained by the policies above; it is *not* a substitute
for them. A `service_role` key, secret key or database password must **never** appear in Android source,
assets, resources, `BuildConfig`, or the APK — it would bypass every policy in this document. The app's
build resolves one endpoint and one publishable key (Gradle property → environment variable → committed
default) into the two `BuildConfig` fields `SALAH_SUPABASE_URL` and `SALAH_SUPABASE_ANON_KEY`, which the three
remote reads share; see `docs/SUPABASE_SETUP.md` §1.

## 6. How a release is published

1. Run `python3 tools/publish_bd_location_dataset.py`. It reads the **already verified** bundled assets,
   validates them against the checked-in identity baseline, and writes `payload.zip`, `manifest.json` and
   a reviewable `publication.sql` into `build/dataset-publication/`. It refuses to prepare anything if the
   candidate has duplicate ids, a malformed line, a broken parent/child relationship, or removes an
   identity the baseline contains. It does **not** publish.
2. Review `manifest.json` and `publication.sql`, especially any identity change.
3. Upload `payload.zip` to Storage with the service role at the manifest's `payload_path`.
4. Run `publication.sql` with the service role. It stages the row (draft), publishes it, and moves the
   pointer — in that order, so the trigger that requires `published` is satisfied.

## 7. Why `draft` is the default status

`tools/publish_bd_location_dataset.py` writes a `draft` manifest by default, and the SQL publishes it as a
separate, explicit step. Staging a release is not publishing it, and the schema — the trigger on the
pointer and the status filter in the view — is built so that a draft that is staged, or even one that is
mistakenly pointed at, is never served to a client.
