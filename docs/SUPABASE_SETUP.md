# Salah — Supabase Setup

How to configure the optional remote dataset publication source, and the exact steps to publish the first
location dataset. Everything here is **optional**: with no configuration the app runs entirely on its
bundled data, exactly as it did before Phase 2.

> **Status of this repository.** No Supabase project is configured for it and no credentials are stored in
> it. Consequently **no database change has been applied and no remote publication has been served**; the
> Android code paths that talk to Supabase are implemented and unit-tested against fakes but have **not**
> been exercised against a live project. This document is the runbook for closing that gap.

## 1. What the app needs

Two non-secret values, supplied at build time and compiled into `BuildConfig`:

| Value | Gradle property | BuildConfig field |
| --- | --- | --- |
| Supabase project URL | `salah.supabase.url` | `SALAH_SUPABASE_URL` |
| Publishable / anon client key | `salah.supabase.anonKey` | `SALAH_SUPABASE_ANON_KEY` |

Both are meant to be embedded in a client. **Do not** supply a `service_role` key, secret key or database
password: those bypass every policy in `DATABASE-SCHEMA.md` and must never reach the APK.

Supply them for a build with any of:

```bash
# Command line
./gradlew -Psalah.supabase.url=https://<ref>.supabase.co -Psalah.supabase.anonKey=<anon-key> assembleDebug

# Or an uncommitted local.properties (already git-ignored) or ~/.gradle/gradle.properties
salah.supabase.url=https://<ref>.supabase.co
salah.supabase.anonKey=<anon-key>
```

With neither property set, both `BuildConfig` fields are empty, `DatasetRemoteConfig.isConfigured` is
`false`, and the synchroniser logs `dataset_sync_not_configured` and returns without touching the network.
That is the default and the supported offline configuration.

**Never commit these values.** They are not secrets in the strict sense, but keeping them out of the
repository keeps the default build deterministic and prevents an accidental production endpoint in a test
build.

## 2. Apply the schema

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

## 3. Publish the first dataset

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
     -H "apikey: <anon-key>" -H "Authorization: Bearer <anon-key>"
   ```

   A one-row JSON array is what the app expects. An empty array means the slot has no current publication
   (the app treats that as "nothing to do").

## 4. Configure the Android build

Set the two Gradle properties from §1 and build. On start-up (and on foreground) the app performs a
throttled manifest check:

- the manifest matches the active dataset → nothing is downloaded;
- it differs → the payload is fetched, verified (checksum and record counts), validated against the stable
  identity contract, and activated through the Phase 1 path;
- anything fails → the active dataset is unchanged and the app keeps working offline.

Every step is logged with `dataset_sync_*` events (see `DiagnosticsLogger`'s event vocabulary). No
credential is ever logged.

## 5. What still needs a live test

These cannot be verified in CI without a real project, and are listed so the gap is explicit:

- the migration applied to a real project, and the RLS/grants behaving as this document claims;
- the Storage read policy serving a payload to the anon key and refusing a write;
- the app's manifest fetch and payload download against the live PostgREST and Storage endpoints;
- an end-to-end "publish a new release, watch an app adopt it" run.

Until those are done, treat the remote path as **implemented and unit-tested, not live-verified**.

## 6. Prayer-configuration publication (Phase 3)

The second published slot is the remote prayer-time calculation configuration. It reuses the same project,
the same publishable key and the same HTTP transport as the dataset path; it adds a slot, not a second
networking client. Its schema is a separate migration,
`supabase/migrations/20260601000000_prayer_config_publications.sql`, and it leaves the dataset tables
untouched. The full reference is `docs/PRAYER_CONFIGURATION.md`.

Apply it the same way as §2 (Supabase CLI `supabase db push`, or paste into the SQL editor). It is written
to be reviewable and re-runnable. Then publish a revision as described in `docs/PRAYER_CONFIGURATION.md`
§10, and verify the read path with the publishable key:

```bash
curl -s "https://<ref>.supabase.co/rest/v1/prayer_config_current_publication?configuration_id=eq.bd_prayer&limit=1&select=configuration_id,revision_id,revision_number,schema_version,status,checksum" \
  -H "apikey: <anon-key>" -H "Authorization: Bearer <anon-key>"
```

The same configuration values from §1 (`salah.supabase.url` and `salah.supabase.anonKey`) enable both
slots. With them set, the app on start-up (and never blocking a screen) checks for a published revision,
validates it, adopts it and reapplies the effective settings; with them unset, both slots report "not
configured" and the app runs entirely offline.

**What still needs a live test here too:** the prayer-config migration applied to a real project, the view's
RLS behaviour, and an end-to-end "publish a revision, watch an app adopt it" run. Until then, treat this
path as **implemented and unit-tested, not live-verified**.
