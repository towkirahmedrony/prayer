-- Salah — remote dataset publication schema (Phase 2).
--
-- This is the *central publication source* for Salah's remotely managed data. It is deliberately
-- minimal: two tables, one view, and the policies that make the view the only way an app client can
-- read a publication. Nothing here is specific to the location dataset beyond the `dataset_id` slot it
-- is keyed by, so a future published dataset type (prayer-time profiles, themes, Ramadan calendars)
-- reuses the same tables by using a different `dataset_id`.
--
-- Three rules the schema exists to enforce, and how each is enforced:
--
--   1. Only a *published* publication may be served to a normal client.
--        - `dataset_current_publications` may only point at a row whose status is 'published'
--          (trigger below), and the `dataset_current_publication` view re-filters on status, so a
--          misconfigured pointer still cannot expose a draft.
--   2. A publication is immutable and identified by an opaque id, not by a version string.
--        - The primary key is (dataset_id, publication_id); the app compares ids for equality only and
--          never orders version strings (see DatasetVersion).
--   3. Ordinary clients are read-only.
--        - Row-level security is enabled on both tables with *no* policy for `anon`/`authenticated`,
--          and their privileges on the base tables are revoked. The view is the only granted surface.
--          Publishing is a service-role (or SQL/migration) operation; a service key must never be
--          shipped in the Android client.
--
-- The matching Android contract lives in
-- `core/domain/dataset/remote/DatasetPublication.kt`; `docs/DATABASE-SCHEMA.md` is the human-readable
-- reference, and `tools/publish_bd_location_dataset.py` produces the rows for a publication.

-- Publication metadata ------------------------------------------------------
-- One row per immutable release. The payload contents are *not* stored here: the row records where the
-- immutable payload object lives and the checksum of that payload's logical parts, so the app verifies
-- what it downloaded against a value the local store independently computes.
create table if not exists public.dataset_publications (
    -- The dataset slot, e.g. 'bd_locations'. Names the kind of data, not a release.
    dataset_id text not null,

    -- The immutable, opaque identifier of one release. Never parsed or ordered by the app.
    publication_id text not null,

    -- The human-readable dataset version (the string the bundled assets carry). Descriptive only.
    version text not null,

    -- The payload layout version. The app supports a fixed set (currently {1}); a bump means a new
    -- decoder, not a reinterpretation of the old bytes.
    payload_schema_version integer not null,

    -- 'draft' | 'published' | 'archived'. Only 'published' may be made current.
    status text not null default 'draft',

    -- SHA-256 (lower-case hex) of the payload's logical parts, exactly as
    -- DatasetChecksum.of() computes it in the app.
    checksum text not null,

    -- The Storage object path of the immutable archive, relative to the datasets bucket.
    payload_path text not null,

    -- The archive's size in bytes, when recorded. Advisory, but it lets a client refuse an
    -- implausibly large payload before downloading it.
    payload_size_bytes bigint,

    -- Completeness assertions. When present, the app checks the decoded payload against them and
    -- refuses a mismatch, so a truncated or wrong payload is caught before activation.
    area_count integer,
    locality_count integer,
    boundary_ring_count integer,

    published_at timestamptz,
    notes text,
    created_at timestamptz not null default now(),

    constraint dataset_publications_pkey primary key (dataset_id, publication_id),
    constraint dataset_publications_status_check check (status in ('draft', 'published', 'archived')),
    constraint dataset_publications_schema_check check (payload_schema_version >= 1),
    constraint dataset_publications_checksum_check check (checksum ~ '^[0-9a-f]{64}$'),
    constraint dataset_publications_version_check check (btrim(version) <> ''),
    constraint dataset_publications_path_check check (btrim(payload_path) <> ''),
    constraint dataset_publications_dataset_check check (btrim(dataset_id) <> ''),
    constraint dataset_publications_publication_check check (btrim(publication_id) <> ''),
    constraint dataset_publications_size_check check (payload_size_bytes is null or payload_size_bytes >= 0),
    constraint dataset_publications_counts_check check (
        (area_count is null or area_count >= 0) and
        (locality_count is null or locality_count >= 0) and
        (boundary_ring_count is null or boundary_ring_count >= 0)
    )
);

comment on table public.dataset_publications is
    'Immutable published dataset releases. Read through the dataset_current_publication view only.';

-- Current-publication pointer ------------------------------------------------
-- Exactly one current publication per dataset slot. A dedicated pointer table rather than a boolean on
-- the publication row: a boolean would allow two rows to claim "current" at once, which makes "which
-- publication is active?" ambiguous — the one question this table exists to answer unambiguously.
create table if not exists public.dataset_current_publications (
    dataset_id text not null,
    publication_id text not null,
    updated_at timestamptz not null default now(),

    constraint dataset_current_publications_pkey primary key (dataset_id),
    constraint dataset_current_publications_publication_fkey
        foreign key (dataset_id, publication_id)
        references public.dataset_publications (dataset_id, publication_id)
        on delete restrict
);

comment on table public.dataset_current_publications is
    'The one publication currently served for each dataset slot. May only name a published release.';

-- Only a published release may be made current. Enforced in the database as well as in the app, so a
-- mistake by an operator cannot expose a draft through the view or the API.
create or replace function public.dataset_current_publications_require_published()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    if not exists (
        select 1
        from public.dataset_publications p
        where p.dataset_id = new.dataset_id
          and p.publication_id = new.publication_id
          and p.status = 'published'
    ) then
        raise exception 'publication %.% must exist and be published before it can be current',
            new.dataset_id, new.publication_id;
    end if;
    return new;
end;
$$;

drop trigger if exists dataset_current_publications_require_published
    on public.dataset_current_publications;
create trigger dataset_current_publications_require_published
    before insert or update on public.dataset_current_publications
    for each row execute function public.dataset_current_publications_require_published();

-- The read surface -----------------------------------------------------------
-- A *definer* view (the default; NOT security_invoker) owned by the migration role. That owner is not
-- subject to the row-level security of the base tables, which is what lets clients read through the view
-- while having no direct access to the tables at all. The view exposes only the current, published row.
create or replace view public.dataset_current_publication as
select
    p.dataset_id,
    p.publication_id,
    p.version,
    p.payload_schema_version,
    p.status,
    p.checksum,
    p.payload_path,
    p.payload_size_bytes,
    p.area_count,
    p.locality_count,
    p.boundary_ring_count,
    p.published_at,
    p.notes
from public.dataset_current_publications c
join public.dataset_publications p
    on p.dataset_id = c.dataset_id
   and p.publication_id = c.publication_id
where p.status = 'published';

comment on view public.dataset_current_publication is
    'The single published publication currently served per dataset slot. The only surface app clients read.';

-- Access control -------------------------------------------------------------
-- Read-only, and only through the view.
alter table public.dataset_publications enable row level security;
alter table public.dataset_current_publications enable row level security;

-- No policies are defined for anon/authenticated on either table. With row-level security enabled, the
-- absence of a policy denies every row, so even a client that somehow held a base-table privilege could
-- read nothing and write nothing. The explicit revokes below remove the privileges too, so the intent is
-- visible in the grants as well as the policies.
revoke all on public.dataset_publications from anon, authenticated;
revoke all on public.dataset_current_publications from anon, authenticated;
revoke all on public.dataset_current_publication from anon, authenticated;

grant select on public.dataset_current_publication to anon, authenticated;
grant select on public.dataset_current_publication to service_role;

-- Publishing (insert/update/delete on the tables, and reading drafts) is a service-role operation and is
-- deliberately not granted to anon or authenticated. Writes bypass RLS for service_role by design.

-- Patch storage for the immutable payload objects ----------------------------
-- A private bucket: `public = false` means there is no unauthenticated public URL. Reads go through the
-- Storage API with the publishable key, which is what the policy below authorises.
insert into storage.buckets (id, name, public)
values ('datasets', 'datasets', false)
on conflict (id) do nothing;

-- Read-only for client roles on the objects in this bucket. Uploading and deleting objects is a
-- service-role operation and is not granted here. Payload objects are immutable by convention: a new
-- publication writes a new path (`<dataset_id>/<publication_id>/payload.zip`) and never overwrites a path
-- an existing publication row already references.
drop policy if exists dataset_payloads_read on storage.objects;
create policy dataset_payloads_read
    on storage.objects
    for select
    to anon, authenticated
    using (bucket_id = 'datasets');
