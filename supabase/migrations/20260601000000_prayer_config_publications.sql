-- Salah — remote prayer-configuration publication schema (Phase 3).
--
-- The *second* published slot. It reuses the publication pattern established by
-- `20260515000000_dataset_publications.sql` — immutable revisions, a single current-publication pointer, a
-- definer view that is the only client-readable surface, and row-level security that leaves ordinary
-- clients read-only — while keeping its own tables. The two slots are deliberately separate: a change to
-- the location dataset must never be able to disturb a prayer configuration, and the revision numbering of
-- one has no meaning for the other.
--
-- The matching Android contract lives in
-- `app/src/main/java/io/github/towkirahmedrony/salah/core/domain/prayerconfig/PrayerConfiguration.kt`, the
-- read path is `.../data/prayerconfig/remote/SupabaseRemotePrayerConfigSource.kt`, and
-- `docs/PRAYER_CONFIGURATION.md` is the human-readable reference.
--
-- Three rules, and how each is enforced:
--
--   1. Only a *published* revision may be served to a normal client.
--        - `prayer_config_current_publications` may only point at a row whose status is 'published'
--          (trigger below), and the `prayer_config_current_publication` view re-filters on status.
--   2. A published revision is immutable and identified by an opaque id.
--        - The primary key is (configuration_id, revision_id); a trigger refuses content changes to a row
--          once published, and refuses deleting a published row. A new release is a new row.
--   3. Ordinary clients are read-only.
--        - Row-level security is enabled on both tables with *no* policy for anon/authenticated, and their
--          privileges on the base tables are revoked. The view is the only granted surface.
--
-- The payload column is `text`, not `jsonb`, and holds the canonical JSON snapshot the app parses. It is
-- text so that a client receives the exact characters the published checksum was computed over; a `jsonb`
-- column would be re-serialised by PostgREST and change those bytes. No Storage bucket is used: the payload
-- travels in the row it belongs to.

-- Publication revisions -------------------------------------------------------
create table if not exists public.prayer_config_publications (
    -- The configuration slot, e.g. 'bd_prayer'. Names the kind of data, not a release.
    configuration_id text not null,

    -- The immutable, opaque identifier of one release. Never parsed or ordered by the app.
    revision_id text not null,

    -- The monotonic revision number. Higher wins; a client refuses a revision that is not newer.
    revision_number bigint not null,

    -- The payload layout version. The app supports a fixed set (currently {1}).
    schema_version integer not null,

    -- 'draft' | 'published' | 'archived'. Only 'published' may be made current.
    status text not null default 'draft',

    -- SHA-256 (lower-case hex) of the payload text, exactly as
    -- PrayerConfigurationChecksum.of() computes it in the app.
    checksum text not null,

    -- The canonical JSON snapshot the app parses and applies. Kept as text so the bytes are stable.
    payload text not null,

    published_at timestamptz,
    notes text,
    created_at timestamptz not null default now(),

    constraint prayer_config_publications_pkey primary key (configuration_id, revision_id),
    constraint prayer_config_publications_status_check check (status in ('draft', 'published', 'archived')),
    constraint prayer_config_publications_schema_check check (schema_version >= 1),
    constraint prayer_config_publications_revision_number_check check (revision_number >= 0),
    constraint prayer_config_publications_checksum_check check (checksum ~ '^[0-9a-f]{64}$'),
    constraint prayer_config_publications_configuration_check check (btrim(configuration_id) <> ''),
    constraint prayer_config_publications_revision_check check (btrim(revision_id) <> ''),
    constraint prayer_config_publications_payload_check check (btrim(payload) <> '')
);

comment on table public.prayer_config_publications is
    'Immutable published prayer-configuration revisions. Read through prayer_config_current_publication only.';

-- Guard immutability of published rows. A draft may be promoted to published (old.status = 'draft'), and a
-- published row's status may be moved to archived, but the content of a published row can never change and
-- a published row can never be deleted. Publishing a change therefore means inserting a new revision.
create or replace function public.prayer_config_publications_guard()
returns trigger
language plpgsql
set search_path = public
as $$
begin
    if tg_op = 'UPDATE' then
        if old.status = 'published' and (
            new.configuration_id <> old.configuration_id or
            new.revision_id <> old.revision_id or
            new.revision_number <> old.revision_number or
            new.schema_version <> old.schema_version or
            new.checksum <> old.checksum or
            new.payload <> old.payload
        ) then
            raise exception 'published prayer-configuration revisions are immutable';
        end if;
        return new;
    elsif tg_op = 'DELETE' then
        if old.status = 'published' then
            raise exception 'published prayer-configuration revisions cannot be deleted';
        end if;
        return old;
    end if;
    return new;
end;
$$;

drop trigger if exists prayer_config_publications_guard on public.prayer_config_publications;
create trigger prayer_config_publications_guard
    before update or delete on public.prayer_config_publications
    for each row execute function public.prayer_config_publications_guard();

-- Current-publication pointer ------------------------------------------------
-- Exactly one current revision per configuration slot. A dedicated pointer table rather than a boolean on
-- the revision row, because a boolean would allow two rows to claim "current" at once.
create table if not exists public.prayer_config_current_publications (
    configuration_id text not null,
    revision_id text not null,
    updated_at timestamptz not null default now(),

    constraint prayer_config_current_publications_pkey primary key (configuration_id),
    constraint prayer_config_current_publications_revision_fkey
        foreign key (configuration_id, revision_id)
        references public.prayer_config_publications (configuration_id, revision_id)
        on delete restrict
);

comment on table public.prayer_config_current_publications is
    'The one prayer-configuration revision currently served for each slot. May only name a published release.';

-- Only a published revision may become current. Enforced in the database as well as in the app.
create or replace function public.prayer_config_current_require_published()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    if not exists (
        select 1
        from public.prayer_config_publications p
        where p.configuration_id = new.configuration_id
          and p.revision_id = new.revision_id
          and p.status = 'published'
    ) then
        raise exception 'revision %.% must exist and be published before it can be current',
            new.configuration_id, new.revision_id;
    end if;
    return new;
end;
$$;

drop trigger if exists prayer_config_current_require_published
    on public.prayer_config_current_publications;
create trigger prayer_config_current_require_published
    before insert or update on public.prayer_config_current_publications
    for each row execute function public.prayer_config_current_require_published();

-- The read surface -----------------------------------------------------------
-- A definer view (the default; NOT security_invoker) owned by the migration role, which is not subject to
-- the row-level security of the base tables. It exposes only the current, published row.
create or replace view public.prayer_config_current_publication as
select
    p.configuration_id,
    p.revision_id,
    p.revision_number,
    p.schema_version,
    p.status,
    p.checksum,
    p.payload,
    p.published_at,
    p.notes
from public.prayer_config_current_publications c
join public.prayer_config_publications p
    on p.configuration_id = c.configuration_id
   and p.revision_id = c.revision_id
where p.status = 'published';

comment on view public.prayer_config_current_publication is
    'The single published prayer-configuration revision currently served per slot. The only surface app clients read.';

-- Access control -------------------------------------------------------------
alter table public.prayer_config_publications enable row level security;
alter table public.prayer_config_current_publications enable row level security;

-- No policies for anon/authenticated on either table, and their privileges are revoked: with RLS enabled
-- and no policy, every row is denied, and the revoke makes the intent visible in the grants as well.
revoke all on public.prayer_config_publications from anon, authenticated;
revoke all on public.prayer_config_current_publications from anon, authenticated;
revoke all on public.prayer_config_current_publication from anon, authenticated;

grant select on public.prayer_config_current_publication to anon, authenticated;
grant select on public.prayer_config_current_publication to service_role;

-- Publishing (insert/update/delete on the tables, and reading drafts) is a service-role or migration
-- operation and is deliberately not granted to anon or authenticated. A service-role key must never be
-- shipped in the Android client; see docs/SUPABASE_SETUP.md.
