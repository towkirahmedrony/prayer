# Salah — Supabase Setup

How Salah's optional remote reads are configured, and the exact steps to publish the first location dataset
or prayer configuration. Everything here is **optional**: with no configuration the app runs entirely on its
bundled data and its bundled calculation defaults, exactly as it did before the remote phases.

> **Status.** The Android build now supplies the Salah Supabase project's public endpoint and publishable key
> by default, so a build made from this repository is **configured** — the configuration gates evaluate true
> and the remote checks are attempted. What that does *not* mean is that anything on the server side works:
> the migrations in `supabase/migrations/` have **not** been applied to the project, so no publication is
> served, and the Phase 1 read-only audit found that the expected `dataset_publications` and
> `prayer_config_publications` surfaces and the expected `metal_prices` contract were **not present**. The
> Android code paths are implemented and unit-tested against fakes; they have **not** been exercised against a
> live, populated project. **Configuration supplied is not database access and is not feature integration** —
> see §7. This document is the runbook for closing that gap.

## 1. What the app needs, and how it reaches the app

Two non-secret values, supplied at build time and compiled into `BuildConfig`:

| Value | Gradle property / environment variable | BuildConfig field |
| --- | --- | --- |
| Supabase project URL | `salah.supabase.url` / `SALAH_SUPABASE_URL` | `SALAH_SUPABASE_URL` |
| Publishable / anon client key | `salah.supabase.anonKey` / `SALAH_SUPABASE_ANON_KEY` | `SALAH_SUPABASE_ANON_KEY` |

`app/build.gradle.kts` resolves each value from the **first** source that supplies a non-blank value:

1. the Gradle property (`-P…` on the command line, an uncommitted `local.properties`, or
   `~/.gradle/gradle.properties`);
2. the environment variable (`SALAH_SUPABASE_URL` / `SALAH_SUPABASE_ANON_KEY`);
3. the committed default, which is the Salah project's own public endpoint and publishable key.

```bash
# The default build needs no configuration at all:
./gradlew assembleDebug

# Override either value for one build:
./gradlew -Psalah.supabase.url=https://<ref>.supabase.co -Psalah.supabase.anonKey=<publishable-key> assembleDebug
```

**Why the environment variable has a different name from the web/admin panel.** The panel reads
`NEXT_PUBLIC_SUPABASE_URL` and `NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY`. Those names are **not** read into the
Android build; the same public pair is supplied here under Android-native names, and the mapping above is the
only path between them. A Next.js variable can therefore never be consumed by the Android build as if it were
an Android one.

**Blank and malformed values are handled safely.** An empty or whitespace-only override falls back to the next
source, so it can never *blank out* a value a build already had. Whatever ends up in `BuildConfig` is then
judged by `SupabaseConfiguration` (shape only: an absolute `http`/`https` URL with a host, and a non-blank key
token with no embedded whitespace) before any request is built. A blank value is the supported offline state —
every remote read reports "not configured" and nothing touches the network — and a malformed value is refused
by the same gate rather than being built into a URL that cannot resolve.

**One source of truth.** The three remote reads — the location-dataset publication, the prayer-configuration
publication and the published reference prices — hold separate configuration types (each carries settings the
others do not), but all three are built in `SalahModules.kt` from these same two `BuildConfig` fields and use
the same `SupabaseConfiguration` rule. There is exactly one endpoint, one key and one definition of
"configured" in the app.

**Which values are public, and which must never be in the APK.**

- **Public, and expected in the APK:** the project URL and the publishable/anon client key. A publishable key
  is designed to be embedded in a client and is constrained by the policies in `DATABASE-SCHEMA.md`.
- **Never in the APK, under any circumstances:** a `service_role` key, a secret key, the JWT signing secret,
  or the database password. Any of those bypasses every policy in `DATABASE-SCHEMA.md` and must only ever be
  used by the admin/SQL workflow that publishes rows and uploads payloads.

## 2. GitHub Actions

**No workflow change is required, and none is made.** The workflow builds with
`./gradlew --no-daemon assembleDebug`, so the committed defaults in `app/build.gradle.kts` apply on the runner
exactly as they do locally: a CI build is configured for the same project as a local one, and the two cannot
disagree about the endpoint. Because the values are public build configuration rather than secrets, they are
**not** placed in repository secrets, and nothing is echoed to the log.

If a build ever needs a *different* endpoint (a staging project, say), supply it explicitly rather than
editing the workflow: pass it to Gradle, or export it in the step's `env` — for example
`SALAH_SUPABASE_URL: ${{ vars.SALAH_SUPABASE_URL }}`. A repository variable alone is not automatically part
of a runner's environment, so it has to be mapped like that to take effect. An unset or empty value is
harmless: it falls back to the committed default.

## 3. Diagnosing missing configuration without printing secrets

`BuildConfig.SALAH_SUPABASE_ANON_KEY` **is** the key, so it is never printed, dumped or asserted on. Diagnose
from the outcome instead:

- **Is the app configured at all?** The synchronisers log `dataset_sync_not_configured` /
  `prayer_config_sync_not_configured` when the gate refuses, and `dataset_sync_started` /
  `prayer_config_sync_started` when a check is actually attempted. The first pair means "no usable
  configuration"; the second pair means "a request is being made". The price path reports
  `MetalPriceFailure.NOT_CONFIGURED` rather than a network outcome. The synchronisation events never contain
  the key — a test asserts exactly that (`DatasetSynchronizerTest`, `PrayerConfigSynchronizerTest`).
- **Is a value present in the build environment?** Check the *names*, never the values: the workspace's
  environment tooling lists which keys are set (Settings → Environment, or `freebuff-env list`), and
  `grep -c '^salah.supabase' local.properties` says whether an uncommitted override exists.
- **Is the value well formed?** `SupabaseConfigurationTest` covers the blank, missing and malformed cases for
  all three gates, plus the shape of whatever the Gradle → `BuildConfig` mapping produced (checked as shape,
  never as an exact value, so an overridden build still passes).

## 4. Apply the schema

The migration is `supabase/migrations/20260515000000_dataset_publications.sql`. Apply it to a project you
control, and inspect the target first if it already has objects:

```bash
# With the Supabase CLI, from the repository root
supabase link --project-ref <ref>
supabase db push
```

Or paste the migration into the project's SQL editor. It creates the two tables, the view, the trigger,
the RLS/grants and the private `datasets` Storage bucket with its read policy. It is written to be
reviewable and re-runnable (`create table if not exists`, `create or replace`,
`drop policy if exists`).

**Do not apply it to an unknown or production project.**

## 5. Publish the first dataset

1. **Prepare the release** from the *already verified* bundled data (never from an external source):

   ```bash
   python3 tools/publish_bd_location_dataset.py --notes "Initial publication of the verified bundled dataset"
   ```

   This validates the bundled TSVs against the identity baseline in
   `app/src/test/resources/location/bd_identity.baseline.tsv` and writes three files into
   `build/dataset-publication/`:

   - `payload.zip` — the immutable payload archive
   - `manifest.json` — the publication manifest (`status: draft` by default)
   - `publication.sql` — the reviewable SQL to stage, publish and point the release

   It refuses to prepare anything that would change an identity the baseline already contains, and it
   publishes nothing.

2. **Review** `manifest.json` and `publication.sql`. In particular, check the printed `checksum`, `areas`,
   `localities` and `boundary rings` against the bundled dataset's known inventory.

3. **Upload the payload** to Storage with the service role, at the path the manifest names
   (`bd_locations/<publication_id>/payload.zip` in the `datasets` bucket). Do not overwrite a path an
   existing publication row already references.

4. **Run `publication.sql`** with the service role (or the SQL editor). It stages the row as a draft,
   publishes it, then moves the current-publication pointer — in that order, so the trigger that requires a
   `published` release is satisfied.

5. **Verify the read path** with the publishable key, not the service role:

   ```bash
   curl -s "https://<ref>.supabase.co/rest/v1/dataset_current_publication?dataset_id=eq.bd_locations&limit=1&select=*" \
     -H "apikey: <publishable-key>" -H "Authorization: Bearer <publishable-key>"
   ```

   A one-row JSON array is what the app expects. An empty array means the slot has no current publication
   (the app treats that as "nothing to do").

## 6. What the app does once configured

On start-up (and on foreground) the app performs a throttled manifest check off the main thread:

- the manifest matches the active dataset → nothing is downloaded;
- it differs → the payload is fetched, verified (checksum and record counts), validated against the stable
  identity contract, and activated through the Phase 1 path;
- anything fails → the active dataset is unchanged and the app keeps working offline.

Every step is logged with `dataset_sync_*` events (see `DiagnosticsLogger`'s event vocabulary). No credential
is ever logged. **A successful configuration does not make this path successful:** if the objects in §4 have
not been created, the manifest request returns an HTTP error (typically 404) and the run reports
`dataset_sync_failed` with `manifest_unavailable` while the bundled dataset keeps working.

## 7. What still needs a live test

These cannot be verified in CI without a real, populated project, and are listed so the gap is explicit:

- the migration applied to a real project, and the RLS/grants behaving as this document claims;
- the Storage read policy serving a payload to the publishable key and refusing a write;
- the app's manifest fetch and payload download against the live PostgREST and Storage endpoints;
- an end-to-end "publish a new release, watch an app adopt it" run.

Until those are done, treat the remote path as **implemented and unit-tested, not live-verified**.

The distinct states, which must not be conflated:

| State | What it means | Where it stands |
| --- | --- | --- |
| Configuration supplied | `BuildConfig` holds a well-formed URL and publishable key | **Yes**, from the defaults in §1 |
| Client initialised | The three config holders report `isConfigured` | **Yes** — same values, same rule |
| Network request attempted | A synchroniser passed its gate and called the transport | Yes, at run time, when a check is due |
| Supabase endpoint reachable | The project host answers | **Yes** — the project ref resolves and the REST gateway responds (it answers 401 without a key) |
| Database query successful | A read returned the publication row | **No** — the objects in §4 are not present |
| Feature data flow operational | A published release is adopted by the app | **No** — nothing is published |

## 8. Prayer-configuration publication

The second published slot is the remote prayer-time calculation configuration. It reuses the same project,
the same publishable key, the same configuration rule and the same HTTP transport as the dataset path; it
adds a slot, not a second networking client. Its schema is a separate migration,
`supabase/migrations/20260601000000_prayer_config_publications.sql`, and it leaves the dataset tables
untouched. The full reference is `docs/PRAYER_CONFIGURATION.md`.

Apply it the same way as §4 (Supabase CLI `supabase db push`, or paste into the SQL editor). It is written
to be reviewable and re-runnable. Then publish a revision as described in `docs/PRAYER_CONFIGURATION.md` §10,
and verify the read path with the publishable key:

```bash
curl -s "https://<ref>.supabase.co/rest/v1/prayer_config_current_publication?configuration_id=eq.bd_prayer&limit=1&select=configuration_id,revision_id,revision_number,schema_version,status,checksum" \
  -H "apikey: <publishable-key>" -H "Authorization: Bearer <publishable-key>"
```

The same two configuration values from §1 enable both slots. With them supplied, the app on start-up (and
never blocking a screen) checks for a published revision, validates it, adopts it and reapplies the effective
settings; with them blank, both slots report "not configured" and the app runs entirely offline.

**What still needs a live test here too:** the prayer-config migration applied to a real project, the view's
RLS behaviour, and an end-to-end "publish a revision, watch an app adopt it" run. Until then, treat this
path as **implemented and unit-tested, not live-verified**.

## 9. Known server-side gaps, deliberately left unchanged in this phase

Phase 1 supplies and validates the *client* configuration. It does **not** invent database objects, change
the schema, or rewrite a client to match a table that does not exist. The following were reported by the
read-only audit and remain open:

- the view the dataset client reads (`dataset_current_publication`) was not found;
- the view the prayer-configuration client reads (`prayer_config_current_publication`) was not found;
- the Android prayer-configuration client expects a revisioned JSON payload, while the
  `prayer_calculation_configs` table reportedly stores configuration scope-per-row — a contract mismatch, not
  a configuration problem;
- the `metal_prices` contract the price client expects was not found.

Each of these is a **data-flow** gap. Configuring the URL and key correctly cannot close one of them: a
misconfigured client and an absent server object produce different diagnostics, and these are the latter.
