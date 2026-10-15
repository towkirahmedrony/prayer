#!/usr/bin/env python3
"""Builds and validates a Salah dataset publication from the *already verified* bundled data.

What this is
------------
The publication half of Phase 2. It reads the three tab-separated tables that ship inside the APK
(`app/src/main/assets/location/`), validates that they still satisfy the stable-identity contract
against the checked-in baseline, and emits the three artifacts a publication is made of:

    payload.zip     the immutable payload archive (`package/` layout: see DatasetPayloadArchive.kt)
    manifest.json   the publication manifest, matching public.dataset_current_publication
    publication.sql the reviewable SQL an operator runs to stage, publish and point a release

It is committed so the derivation is auditable and repeatable, exactly like the two data generators.
It does **not** publish anything: no network, no database, no credentials. Running it is the step that
prepares a release for review.

Why it reads the bundled data and not an external source
--------------------------------------------------------
The remote dataset must be generated from the data the app already ships and the user has verified, so
that publishing changes *where* the data comes from and nothing about *what* it is. Regenerating from
OCHA/HDX or OpenStreetMap here would introduce a second, unverified copy of the geography and make an
identity change — the exact thing the baseline below exists to catch — likely rather than impossible.

The checksum
------------
`dataset_checksum` reproduces, byte for byte, the algorithm in `DatasetChecksum.kt`:

    for each part (name, content) in fixed order:
        <len(name_bytes)> ':' <name_bytes> ':' <len(content_bytes)> ':' <content_bytes> '|'

so the value this tool publishes is the value the app recomputes from the decoded payload. The order and
the exact part names are the contract; see `DatasetPayload.PART_NAMES`.

Usage
-----
    python3 tools/publish_bd_location_dataset.py [--assets DIR] [--out DIR]
        [--baseline FILE] [--publication-id ID] [--version STR] [--notes TEXT]
        [--dataset-id SLOT] [--status draft|published]

`--status` defaults to `draft` on purpose: staging a release is not publishing it, and the SQL it writes
is the place a human turns a draft into a published release after reviewing the identity diff this tool
prints.
"""

import argparse
import hashlib
import json
import os
import sys
import zipfile
from datetime import date

# The payload layout version this build reads. Must match
# DatasetPublicationParser.SUPPORTED_PAYLOAD_SCHEMA_VERSION.
PAYLOAD_SCHEMA_VERSION = 1

# DatasetPayload.PART_NAMES, in checksum order. The order is load-bearing: it is what the digest is
# taken over.
PART_NAMES = ("dataset.version", "bd_areas.tsv", "bd_boundary.tsv", "bd_localities.tsv")

DEFAULT_DATASET_ID = "bd_locations"
DEFAULT_VERSION = "BGD-COD-AB v03 (2023-05-21)"
DEFAULT_BUCKET = "datasets"

# BangladeshLocationDataset column counts.
AREA_COLUMNS = 7
BOUNDARY_COLUMNS = 3
LOCALITY_COLUMNS = 5

LEVELS = ("DIVISION", "DISTRICT", "UPAZILA")
LOCALITY_KINDS = ("suburb", "quarter", "neighbourhood")

# The level a parent must have, mirroring DatasetIdentity.defects().
EXPECTED_PARENT_LEVEL = {"DISTRICT": "DIVISION", "UPAZILA": "DISTRICT"}


def dataset_checksum(parts):
    """SHA-256 of the payload's logical parts, matching DatasetChecksum.of()."""
    digest = hashlib.sha256()
    for name, content in parts:
        _update_part(digest, name)
        digest.update(b":")
        _update_part(digest, content)
        digest.update(b"|")
    return digest.hexdigest()


def _update_part(digest, value):
    data = value.encode("utf-8")
    digest.update(str(len(data)).encode("ascii"))
    digest.update(b":")
    digest.update(data)


def read_lines(path):
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def data_rows(text, columns):
    """The rows of a tab-separated file with the documented column count, excluding comments/blanks."""
    rows = []
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        fields = line.split("\t")
        if len(fields) != columns:
            rows.append((None, line))
            continue
        rows.append(([f.strip() for f in fields], line))
    return rows


def parse_areas(text):
    """Returns (areas_by_id, duplicate_ids, rejected_lines). Areas keep id, level, parent."""
    areas = {}
    duplicates = []
    rejected = []
    for fields, line in data_rows(text, AREA_COLUMNS):
        if fields is None:
            rejected.append(line)
            continue
        level = fields[0].upper()
        area_id = fields[1]
        parent_id = fields[2]
        name = fields[3]
        if level not in LEVELS or not area_id or not parent_id or not name:
            rejected.append(line)
            continue
        if area_id in areas:
            duplicates.append(area_id)
            continue
        areas[area_id] = {"level": level, "parent": parent_id, "name": name}
    return areas, duplicates, rejected


def parse_boundary_rings(text):
    """Number of rings with at least three points, matching the parser's discard rule."""
    rings = {}
    rejected = []
    for fields, line in data_rows(text, BOUNDARY_COLUMNS):
        if fields is None:
            rejected.append(line)
            continue
        try:
            index = int(fields[0])
            float(fields[1])
            float(fields[2])
        except ValueError:
            rejected.append(line)
            continue
        rings.setdefault(index, 0)
        rings[index] += 1
    usable = sum(1 for points in rings.values() if points >= 3)
    return usable, rejected


def parse_localities(text, known_district_ids):
    """Number of distinct, usable localities, matching the parser's de-duplication key."""
    seen = set()
    rejected = []
    for fields, line in data_rows(text, LOCALITY_COLUMNS):
        if fields is None:
            rejected.append(line)
            continue
        kind = fields[0]
        district_id = fields[1]
        name = fields[2]
        if kind not in LOCALITY_KINDS or district_id not in known_district_ids or not name:
            rejected.append(line)
            continue
        try:
            float(fields[3])
            float(fields[4])
        except ValueError:
            rejected.append(line)
            continue
        seen.add((district_id, kind, name))
    return len(seen), rejected


def parse_baseline(text):
    """The checked-in identity baseline: level, id, parent id."""
    baseline = {}
    for fields, _ in data_rows(text, 3):
        if fields is None:
            continue
        level = fields[0].upper()
        area_id = fields[1]
        parent_id = fields[2]
        if level in LEVELS and area_id and parent_id:
            baseline[area_id] = {"level": level, "parent": parent_id}
    return baseline


def structural_defects(areas):
    """Duplicate ids, unknown parents, wrong-level parents, a top-level area parented to an area."""
    defects = []
    for area_id, area in areas.items():
        level = area["level"]
        parent_id = area["parent"]
        expected = EXPECTED_PARENT_LEVEL.get(level)
        if expected is None:
            if parent_id in areas:
                defects.append(f"{area_id} is a division but claims area {parent_id} as its parent")
            continue
        parent = areas.get(parent_id)
        if parent is None:
            defects.append(f"{area_id} is parented to {parent_id}, which is not defined")
        elif parent["level"] != expected:
            defects.append(
                f"{area_id} is a {level.lower()} but its parent {parent_id} is a "
                f"{parent['level'].lower()}, not a {expected.lower()}"
            )
    return defects


def identity_changes(baseline, areas):
    """Breaking changes the candidate makes to identities the baseline already had."""
    changes = []
    for area_id, was in baseline.items():
        now = areas.get(area_id)
        if now is None:
            changes.append(f"removed {area_id} (a {was['level'].lower()})")
            continue
        if now["level"] != was["level"]:
            changes.append(f"moved {area_id} from {was['level'].lower()} to {now['level'].lower()}")
        if now["parent"] != was["parent"]:
            changes.append(f"moved {area_id} from parent {was['parent']} to parent {now['parent']}")
    return changes


def build_payload(assets_dir, version):
    """Reads the bundled tables and returns (parts, counts, problems)."""
    areas_text = read_lines(os.path.join(assets_dir, "bd_areas.tsv"))
    boundary_text = read_lines(os.path.join(assets_dir, "bd_boundary.tsv"))
    localities_path = os.path.join(assets_dir, "bd_localities.tsv")
    localities_text = read_lines(localities_path) if os.path.isfile(localities_path) else ""

    areas, duplicate_ids, area_rejected = parse_areas(areas_text)
    district_ids = {aid for aid, a in areas.items() if a["level"] == "DISTRICT"}
    ring_count, boundary_rejected = parse_boundary_rings(boundary_text)
    locality_count, locality_rejected = parse_localities(localities_text, district_ids)

    problems = []
    if duplicate_ids:
        problems.append(f"duplicate area ids: {', '.join(sorted(set(duplicate_ids))[:10])}")
    if area_rejected:
        problems.append(f"{len(area_rejected)} hierarchy line(s) the parser would refuse")
    if boundary_rejected:
        problems.append(f"{len(boundary_rejected)} boundary line(s) the parser would refuse")
    if locality_rejected:
        problems.append(f"{len(locality_rejected)} locality line(s) the parser would refuse")
    defects = structural_defects(areas)
    problems.extend(defects)
    if not areas or not district_ids or ring_count == 0:
        problems.append("the dataset is not usable (no areas, districts or boundary rings)")

    counts = {
        "area_count": len(areas),
        "locality_count": locality_count,
        "boundary_ring_count": ring_count,
    }
    parts = [
        ("dataset.version", version),
        ("bd_areas.tsv", areas_text),
        ("bd_boundary.tsv", boundary_text),
        ("bd_localities.tsv", localities_text),
    ]
    return parts, counts, problems


def write_payload_zip(path, parts):
    """Writes the immutable payload archive. Entry names are the payload's part names."""
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, content in parts:
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, content)
    return os.path.getsize(path)


def build_manifest(args, checksum, counts, payload_size):
    payload_path = f"{args.dataset_id}/{args.publication_id}/payload.zip"
    return {
        "dataset_id": args.dataset_id,
        "publication_id": args.publication_id,
        "version": args.version,
        "payload_schema_version": PAYLOAD_SCHEMA_VERSION,
        "status": args.status,
        "checksum": checksum,
        "payload_path": payload_path,
        "payload_size_bytes": payload_size,
        "area_count": counts["area_count"],
        "locality_count": counts["locality_count"],
        "boundary_ring_count": counts["boundary_ring_count"],
        "published_at": None,
        "notes": args.notes,
    }


def build_sql(manifest, bucket):
    """The reviewable SQL an operator runs, in order, with the service role."""
    published = "now()" if manifest["status"] == "published" else "null"
    notes = "null" if manifest["notes"] is None else "'" + manifest["notes"].replace("'", "''") + "'"
    return f"""-- Salah dataset publication: {manifest['dataset_id']} / {manifest['publication_id']}
--
-- Generated by tools/publish_bd_location_dataset.py. Review before running.
--
-- Preconditions:
--   1. Upload the payload archive produced alongside this file to Storage, with the service role:
--        bucket: {bucket}
--        path:   {manifest['payload_path']}
--      Payload objects are immutable: never overwrite a path an existing publication row references.
--   2. Run these statements with the service role (or the SQL editor), never from the app.

begin;

-- 1. Stage the release. It is inert until it is published and pointed at.
insert into public.dataset_publications (
    dataset_id, publication_id, version, payload_schema_version, status, checksum,
    payload_path, payload_size_bytes, area_count, locality_count, boundary_ring_count,
    published_at, notes
) values (
    {q(manifest['dataset_id'])}, {q(manifest['publication_id'])}, {q(manifest['version'])},
    {manifest['payload_schema_version']}, {q(manifest['status'])}, {q(manifest['checksum'])},
    {q(manifest['payload_path'])}, {manifest['payload_size_bytes']},
    {manifest['area_count']}, {manifest['locality_count']}, {manifest['boundary_ring_count']},
    {published}, {notes}
)
on conflict (dataset_id, publication_id) do nothing;

-- 2. Publish it. Only a published release may be made current (enforced by a trigger).
update public.dataset_publications
   set status = 'published',
       published_at = coalesce(published_at, now())
 where dataset_id = {q(manifest['dataset_id'])}
   and publication_id = {q(manifest['publication_id'])};

-- 3. Point the slot at it. The trigger refuses this unless step 2 left it published.
insert into public.dataset_current_publications (dataset_id, publication_id, updated_at)
values ({q(manifest['dataset_id'])}, {q(manifest['publication_id'])}, now())
on conflict (dataset_id) do update
    set publication_id = excluded.publication_id,
        updated_at = now();

commit;
"""


def q(value):
    return "'" + str(value).replace("'", "''") + "'"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--assets", default="app/src/main/assets/location")
    parser.add_argument("--baseline", default="app/src/test/resources/location/bd_identity.baseline.tsv")
    parser.add_argument("--out", default="build/dataset-publication")
    parser.add_argument("--dataset-id", default=DEFAULT_DATASET_ID)
    parser.add_argument("--bucket", default=DEFAULT_BUCKET)
    parser.add_argument("--version", default=DEFAULT_VERSION)
    parser.add_argument("--publication-id", default=None)
    parser.add_argument("--notes", default=None)
    parser.add_argument("--status", default="draft", choices=("draft", "published"))
    args = parser.parse_args()

    if not os.path.isdir(args.assets):
        print(f"error: assets directory not found: {args.assets}", file=sys.stderr)
        return 2

    if args.publication_id is None:
        # A publication id derived from the version and the date, not from a counter: it is opaque but
        # still readable, and two runs on different days produce different ids without coordination.
        args.publication_id = f"{args.dataset_id}-{date.today().isoformat()}"

    parts, counts, problems = build_payload(args.assets, args.version)

    # Compare against the checked-in identity baseline: an unexpected removal must stop a publication.
    if os.path.isfile(args.baseline):
        baseline = parse_baseline(read_lines(args.baseline))
        areas, _, _ = parse_areas(read_lines(os.path.join(args.assets, "bd_areas.tsv")))
        for change in identity_changes(baseline, areas):
            problems.append(f"identity change vs baseline: {change}")
    else:
        problems.append(f"baseline file not found: {args.baseline}")

    if problems:
        print("REFUSING TO PREPARE A PUBLICATION:", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1

    checksum = dataset_checksum(parts)
    os.makedirs(args.out, exist_ok=True)

    payload_path = os.path.join(args.out, "payload.zip")
    payload_size = write_payload_zip(payload_path, parts)

    manifest = build_manifest(args, checksum, counts, payload_size)
    with open(os.path.join(args.out, "manifest.json"), "w", encoding="utf-8") as handle:
        json.dump(manifest, handle, indent=2, ensure_ascii=False)
        handle.write("\n")

    with open(os.path.join(args.out, "publication.sql"), "w", encoding="utf-8") as handle:
        handle.write(build_sql(manifest, args.bucket))

    print(f"prepared a {manifest['status']} publication in {args.out}")
    print(f"  dataset_id       {manifest['dataset_id']}")
    print(f"  publication_id   {manifest['publication_id']}")
    print(f"  version          {manifest['version']}")
    print(f"  checksum         {manifest['checksum']}")
    print(f"  payload          {manifest['payload_path']} ({payload_size} bytes)")
    print(f"  areas            {counts['area_count']}")
    print(f"  localities       {counts['locality_count']}")
    print(f"  boundary rings   {counts['boundary_ring_count']}")
    print()
    print("Nothing was published. Upload payload.zip to Storage, review publication.sql, then run it")
    print("with the service role. See docs/SUPABASE_SETUP.md.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
