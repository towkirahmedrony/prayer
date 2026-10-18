# Salah — Supabase Contract Matrix

What the Salah Android app asks the Supabase project for, what this repository defines for each read, and
**which parts of that are verified**. Every remote read in Salah is a pair: a migration that says what the
server exposes, and a parser that says what the app requires. This document is the one place the pairs are
listed side by side, so a mismatch has somewhere to show up other than a live request.

> **Scope of the verification.** The repository-side facts — endpoints, field names, and the SQL that defines
> the expected read surfaces — are verified by `SupabaseContractParityTest`, which reads the migrations and
> the Kotlin constants and fails if they disagree. The **live half was read on 2026-10-18 with the app's own
> publishable key** (the credential the APK ships with), read-only, with `GET` requests only:
>
> - `dataset_current_publication` and `prayer_config_current_publication` both answer **HTTP 200** and every
>   column this app selects exists; both currently return **`[]`** (no publication has been published).
> - `metal_prices` answers **HTTP 200** for the exact column list the price client selects and holds **five
>   active, verified rows**, including one `K22`/`BDT` rate per metal.
> - The `datasets` Storage bucket answers **404 "Object not found"** for a nonexistent path, which is the
>   bucket being reachable rather than absent.
>
> What that does **not** establish: whether these objects were created by the migrations in this repository
> (there is no service-role or SQL access here, so the DDL cannot be read back), that any row is published
> for the two revisioned slots, or that the app adopts a release end to end. Those remain marked below. No
> credential appears in this document, and no request wrote anything.

## 1. Status vocabulary

Used exactly as written, and never conflated:

| Status | Meaning |
| --- | --- |
| **CONTRACT IMPLEMENTED** | The Android read path and the SQL that defines its expected surface both exist in this repository and agree (checked by `SupabaseContractParityTest`). |
| **MIGRATION PREPARED** | A reviewed migration exists in `supabase/migrations/` and has **not** been applied to any project. |
| **MIGRATION APPLIED AND VERIFIED** | Someone with authorized access applied it and read the change back. **Nothing in this document is in this state.** |
| **ENDPOINT REACHABLE** | The project host answers HTTP. Measured separately from any query. |
| **DATABASE READ VERIFIED** | An authenticated read returned the rows the contract describes. |
| **FEATURE DATA FLOW VERIFIED** | End to end: a published record is adopted/applied by the app. |
| **UNABLE TO VERIFY** | No authorized access, or the object is not known from source. Recorded, not assumed away. |

## 2. The four contracts

### 2.1 Bangladesh location datasets

| | |
| --- | --- |
| **Read surface** | `public.dataset_current_publication` (view) |
| **Defined by** | `supabase/migrations/20260515000000_dataset_publications.sql` |
| **Endpoint** | `GET {url}/rest/v1/dataset_current_publication?dataset_id=eq.bd_locations&limit=1&select=*`, then `GET {url}/storage/v1/object/datasets/{payload_path}` for the immutable archive |
| **Required fields** | `dataset_id`, `publication_id`, `version`, `payload_schema_version`, `status`, `checksum`, `payload_path` (`payload_size_bytes`, `area_count`, `locality_count`, `boundary_ring_count`, `published_at`, `notes` are used when present) |
| **Types** | `text` except `payload_schema_version`/`*_count` (`integer`) and `payload_size_bytes` (`bigint`); `published_at` is `timestamptz` |
| **Revision / version mechanism** | One immutable row per `(dataset_id, publication_id)`; `payload_schema_version` is the payload *layout* version (the app supports `{1}`); `version` is descriptive only and never used for ordering; the app compares `checksum` (SHA-256 over the payload's logical parts) to decide "same release, nothing to do" |
| **Publication rules** | Only `status = 'published'` may be served. A `dataset_current_publications` pointer row may only name a published release (trigger), and the view re-filters on status |
| **Required read permission** | `select` on the view for `anon`/`authenticated` (plus the `storage.objects` read policy for the `datasets` bucket). The base tables are RLS-enabled with **no** client policy and have their privileges revoked |
| **Local caching** | The accepted payload is activated locally under `filesDir` (`datasets/bd_locations`), checksum- and count-verified before activation; sync state (throttle, last attempt, failures) is kept beside it |
| **Fallback** | Bundled Bangladesh assets remain the permanent floor. Any failure leaves the active dataset exactly as it was; the synchroniser never writes user data |
| **Verification status** | CONTRACT IMPLEMENTED · **ENDPOINT REACHABLE** and **DATABASE READ VERIFIED** (200 on every selected column) · no publication row exists, so activation and the payload read are **UNABLE TO VERIFY** · whether the migration created the live object is unverified |

### 2.2 Prayer calculation configuration

| | |
| --- | --- |
| **Read surface** | `public.prayer_config_current_publication` (view) |
| **Defined by** | `supabase/migrations/20260601000000_prayer_config_publications.sql` |
| **Endpoint** | manifest: `GET {url}/rest/v1/prayer_config_current_publication?configuration_id=eq.bd_prayer&limit=1&select=configuration_id,revision_id,revision_number,schema_version,status,checksum,published_at,notes`; payload (only when the revision changed): the same view with `&revision_id=eq.{revision}&select=payload` |
| **Required fields** | Manifest: the eight selected columns above. Payload (the `payload` text column, parsed as JSON): `configuration_id`, `revision_id`, `revision_number`, `schema_version`, `global`, optional `areas[]` with `area_id` and `scope`, and within a scope `method`, `asr_method`, `high_latitude_rule`, `adjustments` |
| **Types** | Manifest columns `text`/`bigint`/`integer` as declared; the payload column is **`text`, not `jsonb`**, so the client receives the exact characters the published checksum was computed over. `adjustments` is an object of per-prayer integer minutes |
| **Revision / version mechanism** | `revision_id` (opaque, compared for equality), `revision_number` (monotonic; a client refuses a revision that is not *newer*), `schema_version` (payload layout, app supports `{1}`), `checksum` (SHA-256 of the payload text). A `revision_id` whose checksum changed is rejected as a reused revision |
| **Publication rules** | Only `status = 'published'` may be served; the pointer may only name a published revision (trigger); published rows are immutable and cannot be deleted (trigger); a new release is a new row |
| **Required read permission** | `select` on the view for `anon`/`authenticated`; base tables RLS-enabled with no client policy and revoked privileges |
| **Local caching** | The accepted snapshot is stored under `filesDir` (`prayer_config`) with its own sync state; the applier writes only the non-user-overridden fields into the one preference store |
| **Fallback** | Bundled calculation defaults. A rejected/stale/unparsed revision leaves the active configuration untouched, and a user's own override of a field always wins over a published value |
| **Verification status** | CONTRACT IMPLEMENTED · **ENDPOINT REACHABLE** and **DATABASE READ VERIFIED** (200 on the manifest columns *and* on `payload`) · no revision exists, so the payload download, checksum check and activation are **UNABLE TO VERIFY** |

**The competing scope-per-row design.** The audit reported a `prayer_calculation_configs` table that stores
one configuration per scope. That object is **not** read by this app and is **not defined in this
repository** — nothing here describes its columns, its key or its lifecycle, so no adapter can be written
against it without inventing schema. What can be said from source is why the revisioned contract cannot be
replaced by it: the client's guarantees (refuse a non-newer revision, reject a reused revision id, verify a
checksum over exact bytes, download nothing when the revision is unchanged, never re-activate what is already
active) all require an immutable revision row and a checksum. See §3 for the decision that follows.

### 2.3 Gold and silver reference prices

| | |
| --- | --- |
| **Read surface** | `public.metal_prices` (table) |
| **Defined by** | `supabase/migrations/20261018000000_metal_prices.sql` — **new in this phase**; before it, this contract existed only as Kotlin constants and prose |
| **Endpoint** | `GET {url}/rest/v1/metal_prices?is_active=eq.true&select=metal_type,purity,price_per_gram,currency,source,source_url,override_active,is_active,verification_status,effective_at,provider_last_update,fetched_at,updated_at` |
| **Required fields** | `metal_type`, `purity`, `price_per_gram`, `currency`, `is_active`, `verification_status`. Optional: `source`, `source_url`, `override_active` (absent reads as `false`), and the four timestamps |
| **Types** | `metal_type`/`purity`/`currency`/`verification_status`/`source`/`source_url` `text`; `price_per_gram` **`numeric`** (exact, read into a `BigDecimal`, at most six decimal places accepted); `is_active`/`override_active` `boolean`; the four timestamps `timestamptz`. `currency` is `BDT` for the automatic basis; another currency is reported, not substituted |
| **Revision / version mechanism** | None. The rate is a *current* value, not a revision: the app selects exactly one active, `VERIFIED` row per metal in purity `K22` and `BDT`, and treats two usable rows as a conflict to report rather than a choice to make. A partial unique index on `(metal_type, purity, currency) where is_active and verification_status = 'VERIFIED'` makes that ambiguity unrepresentable |
| **Publication rules** | A row is used only when `is_active` is true **and** `verification_status = 'VERIFIED'`; both are re-checked client-side. Rows are published by an administrator workflow outside this app — Salah never contacts the price provider and never writes a price |
| **Required read permission** | `select` on the table for `anon`/`authenticated` through a row-level-security select policy. No write privilege is granted to any client role |
| **Local caching** | None. Nothing is persisted: the Zakat screen reads once on creation and on an explicit refresh, and a user's typed price is an in-memory session value that a published rate never overwrites |
| **Fallback** | Manual entry. Manual entry stays available, a missing or unusable rate never becomes zero, one metal's failure never stops the other metal's rate, and a request failure leaves the calculator exactly as usable as it was |
| **Verification status** | CONTRACT IMPLEMENTED · **ENDPOINT REACHABLE** and **DATABASE READ VERIFIED**: the live table answers 200 for the selected column list and holds five active verified rows, including `K22`/`BDT` gold (19550) and silver (360). **Publishing and the app's display are unverified** — nothing here wrote a price or ran the screen. |

**Gold and silver are one contract, not two.** The client issues one query and resolves the two metals
independently from one response, so the table, endpoint, permission and fallback behaviour are identical;
only the `metal_type` value differs. The two metals are kept as separate *results* in the domain
(`ReferencePrices.gold` / `.silver`) precisely so a conflict or an unusable row for one never affects the
other.

**Staleness is disclosed, not gated.** The contract carries four timestamps and the client distinguishes them
by meaning: `effective_at` is the only one the screen may present as an effective/reviewed date,
`provider_last_update` is the provider's own update, and `fetched_at`/`updated_at` describe retrieval and the
row's own last change. The app never invents a date for a row that recorded none, and it never replaces a
published date with a fresher one. It also applies **no age threshold**: no source in this repository states
one, and `MetalPriceRepository` is documented as refusing to infer a current price from the newest timestamp,
so inventing a cutoff would be inventing policy. See §4.

**The live table is not the migration's table, and the migration must not be applied blindly.** The live
`metal_prices` answers the client's column list, but refuses `select=*` with `42501` ("permission denied for
table metal_prices"): the publishable key holds **column-level** `select` on exactly the contract's columns,
not table-level `select`. The prepared migration's `grant select on public.metal_prices to anon` would grant
every column of the existing table — including the surrogate key and audit timestamps the app never reads.
It also carries check constraints written for upper-case values, while the live rows store `gold`, `silver`
and `verified` in lower case (the client accepts both, case-insensitively). Applying it to this project would
therefore not be additive in the way its header claims. It remains a definition of the contract for a fresh
deployment; reconciling it with this table belongs to the table's owner.

## 3. The integration strategy, and the options rejected

**Chosen: keep the Android read contracts as they are, and close the server-side gap with additive SQL.**

1. **No Android DTO or endpoint change.** The clients already ask for exactly what the migrations define.
   `SupabaseContractParityTest` now proves it column by column, so this is a checked claim rather than a
   reading. Renaming a client field to match a speculation about the live schema would have broken a working
   contract on the strength of an assumption.
2. **The dataset and prayer-config migrations stay as they are.** They define complete, read-only,
   RLS-consistent surfaces for their clients, and the live project already answers both views with every
   column the clients select. What both slots still lack is a **published row**, which is a publishing step,
   not a schema redesign.
3. **One new migration, for the one contract with no SQL at all.** The price slot is the only read whose
   surface existed solely as Kotlin constants and prose, so it is the only one where a reviewer could not see
   what the server was supposed to expose. `20261018000000_metal_prices.sql` pins it, additively.

Options considered and rejected:

| Option | Why not |
| --- | --- |
| Make the price client read a differently-named admin table | Its name and columns are not known from source. Writing a view over an object nobody here has described is inventing schema, and the audit's "not found" is evidence about one name, not proof about the project. |
| Rewrite the prayer-config client to read a scope-per-row table | It would drop revision ids, revision numbers and checksums — the app's idempotence ("unchanged revision downloads nothing") and its refusal of reused/stale revisions both depend on them. §6 of the phase brief forbids weakening revision and checksum validation, and an empty or unscoped response must never read as valid configuration. |
| Introduce a generic translation/sync framework | Two published slots and one price read do not justify a framework, and the three contracts have genuinely different semantics (revisioned immutable payload vs. current value). |
| Disable RLS, or grant write/`service_role` access, to make reads succeed | RLS is what makes a publishable key safe to embed. Every migration keeps it enabled with a narrow select grant, and no write privilege is granted to a client role. |
| Duplicate price data into a second table for the app's convenience | Two copies of the same market data would be two things to keep correct. The app reads the published table it named. |

## 4. Deliberate non-changes, and the reasoning

- **`prayer_calculation_configs` is not touched, renamed, migrated or dropped.** It is not this repository's
  object. If it is the administrator's source of truth for settings, the adapter that materialises it into a
  revisioned, checksummed publication belongs to the workflow that publishes — outside both this repository
  and the app.
- **No staleness threshold was added to prices.** See §2.3. The phase brief requires stale prices not be
  *silently* presented as current; the contract satisfies that by carrying the record's own dates through to
  the screen and never fabricating one, which `SupabaseMetalPriceRepositoryTest` pins in both directions (an
  old date survives unchanged; a missing date stays missing). Choosing an age limit is a product decision.
- **No authentication, Realtime, Storage writes or any write path was added.** Every read stays a single
  read-only GET with the publishable key, through the one existing transport.
- **No new configuration, HTTP client or credential.** All three reads use the two `BuildConfig` values and
  the shared `SupabaseConfiguration` rule from Phase 1 and the one `HttpTransport`.

## 5. What was verified live, and what remains

Read on 2026-10-18 with the publishable key, `GET` only, no writes:

| Check | Result |
| --- | --- |
| Project host reachable | **Yes** — REST and Storage both answer |
| Publishable key accepted for the app's reads | **Yes** — 200 on all three read surfaces (the bare `/rest/v1/` root refusing a publishable key is expected and is not one of the app's reads) |
| `dataset_current_publication` exists with every selected column | **Yes** — 200 |
| `prayer_config_current_publication` exists with the manifest columns and `payload` | **Yes** — 200 for both |
| A publication exists for either revisioned slot | **No** — both answer `[]` |
| `metal_prices` exists with every selected column and readable by `anon` | **Yes** — 200, five active verified rows |
| A `K22`/`BDT` rate is published for each metal | **Yes** — gold 19550, silver 360 BDT per gram |
| `datasets` Storage bucket exists and is reachable | **Yes** — a nonexistent object answers `404 Object not found` |
| The live table grants match the prepared migration | **No** — column-level grants; `select=*` is refused (`42501`). See §2.3 |
| The live objects were created by these migrations | **Unknown** — no SQL/service-role access, so the DDL cannot be read back |
| A dataset payload downloads and activates | **Not verified** — no publication to download |
| A prayer revision downloads, checks and activates | **Not verified** — no revision to download |
| The app displays a price or adopts a release on a device | **Not verified** — no device/emulator in this environment |
| An administrator can publish (writes succeed under RLS) | **Not verified** — writes were neither attempted nor authorized |

Status of the whole remote path today: **CONTRACT IMPLEMENTED**, **ENDPOINT REACHABLE**, **DATABASE READ
VERIFIED** (the empty publication reads and the price rows). **FEATURE DATA FLOW VERIFIED** is still
**UNABLE TO VERIFY** for the two publication slots, because nothing is published for them; for prices the
read and selection are verified but the on-device display is not.

## 6. Where each contract is defined

| Contract | SQL | Kotlin client | Kotlin contract | Tests |
| --- | --- | --- | --- | --- |
| Location dataset | `supabase/migrations/20260515000000_dataset_publications.sql` | `data/dataset/remote/SupabaseRemoteDatasetSource.kt` | `core/domain/dataset/remote/DatasetPublication.kt` | `DatasetPublicationTest`, `DatasetSynchronizerTest`, `SupabaseContractParityTest` |
| Prayer config | `supabase/migrations/20260601000000_prayer_config_publications.sql` | `data/prayerconfig/remote/SupabaseRemotePrayerConfigSource.kt` | `core/domain/prayerconfig/PrayerConfiguration.kt` | `PrayerConfigurationParserTest`, `PrayerConfigSynchronizerTest`, `SupabaseContractParityTest` |
| Gold and silver prices | `supabase/migrations/20261018000000_metal_prices.sql` | `data/metalprice/SupabaseRemoteMetalPriceSource.kt` | `core/domain/metalprice/MetalPriceParser.kt` | `MetalPriceParserTest`, `SupabaseMetalPriceRepositoryTest`, `SupabaseRemoteMetalPriceSourceTest`, `SupabaseContractParityTest` |

Related: `docs/SUPABASE_SETUP.md` (how to apply a migration), `DATABASE-SCHEMA.md` (the dataset schema
in full), `docs/PRAYER_CONFIGURATION.md` (the prayer-configuration contract in full),
`docs/ZAKAT_METHODOLOGY.md` §8.5 (the price contract from the calculator's side).
