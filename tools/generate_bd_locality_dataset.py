#!/usr/bin/env python3
"""Generates the Bangladesh *locality* dataset bundled inside Salah.

Why this exists
---------------
Salah's administrative dataset (`tools/generate_bd_location_dataset.py`) stops at the
division -> district -> upazila/thana hierarchy. That is the right granularity everywhere except
inside the twelve city corporations, where the deepest published unit is a *whole corporation*:
a device fix in Mirpur can therefore only be named "Dhaka North City Corporation", half a city,
because the COD-AB source defines no unit smaller than the corporation there.

This script derives the missing finer layer from OpenStreetMap: the named urban localities
(`place=suburb`, `place=quarter`, `place=neighbourhood`) that people actually use — Mirpur,
Uttara, Dhanmondi, Tejgaon, Khulshi, Uposhohor. It is committed so the derivation is auditable and
repeatable, exactly like the administrative one.

Sources (docs/LOCATION_SYSTEM.md section 5 has the full licence discussion)
---------------------------------------------------------------------------
1. **OpenStreetMap**, via the Overpass API. Provides the locality name (English and Bangla) and the
   locality's own point. Licence: **Open Database License (ODbL) 1.0**,
   https://www.openstreetmap.org/copyright. The derived file below is a Derivative Database and is
   therefore also offered under the ODbL; see the header the script writes into it.

2. **Bangladesh Common Operational Dataset on Administrative Boundaries (COD-AB)**, OCHA / HDX
   `cod-ab-bgd`. Used **only** to join a locality to the district that contains it, by
   point-in-polygon against the authoritative ADM2 geometry — never by proximity to a district
   centre, which would misattribute a locality near a district border. Licence: CC BY 3.0 IGO.

Why the join is done here and not in the app
--------------------------------------------
The app must never attribute a locality to a district by guessing: a point-in-polygon test against
the authoritative geometry is a verification, and it can only be done against the geometry, which is
tens of megabytes and is not shipped. Doing it once, here, turns the verification into a stored
p-code the app can compare against the district it resolved for the user's own position.

Usage
-----
    python3 tools/generate_bd_locality_dataset.py [source_dir] [output_dir] [osm_json]

`source_dir` must contain the unzipped COD-AB files including `bgd_admin2.geojson` (the ADM2
districts) and `bgd_admin0.geojson`; the archive is at https://data.humdata.org/dataset/cod-ab-bgd

`osm_json` is an Overpass API response for the query below. When it is omitted the script queries
Overpass itself, which makes the output a snapshot of OpenStreetMap as of that moment: always
re-check the count and the `timestamp_osm_base` it reports before committing a regenerated file.

    [out:json][timeout:300];
    area["ISO3166-1"="BD"]["admin_level"="2"]->.bd;
    (
      node["place"~"^(suburb|quarter|neighbourhood)$"]["name:en"](area.bd);
      way["place"~"^(suburb|quarter|neighbourhood)$"]["name:en"](area.bd);
    );
    out tags center;

Output
------
    app/src/main/assets/location/bd_localities.tsv   locality -> district join + coordinates

Tab-separated text with `#` comments, five columns per data row:
`kind<TAB>district_id<TAB>name<TAB>latitude<TAB>longitude`, parsed by
`BangladeshLocationDataset` (pure Kotlin, no JSON or CSV dependency).
"""

import json
import os
import sys
import urllib.parse
import urllib.request

OVERPASS_ENDPOINT = "https://overpass-api.de/api/interpreter"

# The Overpass query, committed so the extract is reproducible. Only units that OpenStreetMap tags
# with an English name are taken: the app's administrative names are English, and a label that mixed
# scripts ("কলাবাড়িয়া, Dhaka") would be worse than dropping the row. The script reports how many
# rows that rule excluded.
OVERPASS_QUERY = """[out:json][timeout:300];
area["ISO3166-1"="BD"]["admin_level"="2"]->.bd;
(
  node["place"~"^(suburb|quarter|neighbourhood)$"]["name:en"](area.bd);
  way["place"~"^(suburb|quarter|neighbourhood)$"]["name:en"](area.bd);
);
out tags center;"""

# The OpenStreetMap `place` values taken as a locality. A `place=city` or `place=town` is already an
# administrative unit in the other dataset, and `place=village` exists tens of thousands of times:
# neither is a *locality inside* a district in the sense this layer means.
ACCEPTED_KINDS = ("suburb", "quarter", "neighbourhood")

COORDINATE_DECIMALS = 6
MAX_NAME_LENGTH = 64


def fetch_overpass():
    request = urllib.request.Request(
        OVERPASS_ENDPOINT,
        data=urllib.parse.urlencode({"data": OVERPASS_QUERY}).encode("utf-8"),
        headers={"User-Agent": "Salah-dataset-generator/1.0 (https://github.com/towkirahmedrony/prayer)"},
    )
    with urllib.request.urlopen(request, timeout=600) as response:
        return json.loads(response.read().decode("utf-8"))


def load_polygons(source_dir, filename, pcode_field):
    """`{pcode: [ring, ...]}`, flattened, from one COD-AB GeoJSON file."""
    with open(os.path.join(source_dir, filename), encoding="utf-8") as handle:
        features = json.load(handle)["features"]

    polygons = {}
    for feature in features:
        geometry = feature["geometry"]
        if geometry is None:
            continue
        coordinates = geometry["coordinates"]
        if geometry["type"] == "Polygon":
            rings = coordinates
        elif geometry["type"] == "MultiPolygon":
            rings = [ring for polygon in coordinates for ring in polygon]
        else:
            continue
        polygons[feature["properties"][pcode_field]] = rings
    return polygons


def point_in_ring(x, y, ring):
    """Ray casting. Returns True when (x, y) is inside the ring."""
    inside = False
    count = len(ring)
    for index in range(count):
        x1, y1 = ring[index][0], ring[index][1]
        x2, y2 = ring[(index + 1) % count][0], ring[(index + 1) % count][1]
        if (y1 > y) != (y2 > y):
            intersection = x1 + (y - y1) * (x2 - x1) / (y2 - y1)
            if x < intersection:
                inside = not inside
    return inside


def contains(polygons, pcode, x, y):
    return any(point_in_ring(x, y, ring) for ring in polygons.get(pcode, []))


def format_number(value):
    return f"{value:.{COORDINATE_DECIMALS}f}"


def collect_localities(osm, districts, district_ids):
    """Returns `(rows, statistics)`. A locality that is in no district is reported, never guessed."""
    rows = []
    stats = {
        "elements": 0,
        "not_a_locality_kind": 0,
        "no_english_name": 0,
        "unusable_name": 0,
        "no_position": 0,
        "outside_every_district": 0,
        "duplicates": 0,
        "without_bangla_name": 0,
    }
    seen = set()

    for element in osm.get("elements", []):
        stats["elements"] += 1
        tags = element.get("tags", {})
        kind = tags.get("place")
        if kind not in ACCEPTED_KINDS:
            stats["not_a_locality_kind"] += 1
            continue

        english = (tags.get("name:en") or "").strip()
        if not english:
            stats["no_english_name"] += 1
            continue
        # A name that is only a number, only punctuation, or implausibly long is a tagging artefact
        # rather than a place anyone would recognise as where they are.
        if len(english) > MAX_NAME_LENGTH or not any(character.isalpha() for character in english):
            stats["unusable_name"] += 1
            continue
        if not (tags.get("name:bn") or "").strip():
            stats["without_bangla_name"] += 1

        latitude = element.get("lat")
        longitude = element.get("lon")
        if latitude is None or longitude is None:
            centre = element.get("center") or {}
            latitude = centre.get("lat")
            longitude = centre.get("lon")
        if latitude is None or longitude is None:
            stats["no_position"] += 1
            continue

        district = next(
            (pcode for pcode in district_ids if contains(districts, pcode, longitude, latitude)),
            None,
        )
        if district is None:
            stats["outside_every_district"] += 1
            continue

        key = (district, kind, english.casefold())
        if key in seen:
            stats["duplicates"] += 1
            continue
        seen.add(key)

        rows.append(
            {
                "kind": kind,
                "district": district,
                "name": english,
                "lat": float(latitude),
                "lon": float(longitude),
            }
        )

    rows.sort(key=lambda row: (row["district"], row["name"]))
    return rows, stats


def write_localities(path, rows, source_timestamp, stats):
    lines = [
        "# Salah location dataset - Bangladesh urban localities (neighbourhoods).",
        "# Source: OpenStreetMap, via the Overpass API. (c) OpenStreetMap contributors.",
        "# Licence: Open Database License (ODbL) 1.0 - https://www.openstreetmap.org/copyright",
        "#   This file is a Derivative Database of OpenStreetMap data and is offered under the ODbL.",
        "# Extracted with the Overpass query committed in tools/generate_bd_locality_dataset.py,",
        f"# from OpenStreetMap data dated {source_timestamp}.",
        "# The district each locality belongs to was verified by point-in-polygon against the",
        "# administrative boundaries of the OCHA/HDX COD-AB for Bangladesh (CC BY 3.0 IGO), not by",
        "# proximity to a district centre.",
        "#",
        "# A locality is an informal, locally used place name. It is NOT an administrative unit and is",
        "# never presented as one: it says where someone is, the district says which district to file it",
        "# under, and no thana identity is claimed for it.",
        "#",
        "# Rows excluded while building this file, kept so the loss is visible:",
        f"#   elements read {stats['elements']}, not a locality kind {stats['not_a_locality_kind']},",
        f"#   no English name {stats['no_english_name']}, unusable name {stats['unusable_name']},",
        f"#   no position {stats['no_position']},",
        f"#   inside no district of the source {stats['outside_every_district']},",
        f"#   duplicate of an earlier row {stats['duplicates']},",
        f"#   written without a Bangla name {stats['without_bangla_name']}.",
        "# Generated by tools/generate_bd_locality_dataset.py - do not edit by hand.",
        "#",
        "# kind<TAB>district_id<TAB>name<TAB>latitude<TAB>longitude",
    ]
    for row in rows:
        lines.append(
            "\t".join(
                [
                    row["kind"],
                    row["district"],
                    row["name"],
                    format_number(row["lat"]),
                    format_number(row["lon"]),
                ]
            )
        )
    with open(path, "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines) + "\n")
    return len(lines)


def main():
    source_dir = sys.argv[1] if len(sys.argv) > 1 else "/tmp/bdgeo"
    output_dir = sys.argv[2] if len(sys.argv) > 2 else "app/src/main/assets/location"
    osm_path = sys.argv[3] if len(sys.argv) > 3 else None
    os.makedirs(output_dir, exist_ok=True)

    if osm_path:
        with open(osm_path, encoding="utf-8") as handle:
            osm = json.load(handle)
        print(f"reading the Overpass response in {osm_path}")
    else:
        osm = fetch_overpass()
        print("queried Overpass directly")

    source_timestamp = (osm.get("osm3s") or {}).get("timestamp_osm_base", "unknown")

    districts = load_polygons(source_dir, "bgd_admin2.geojson", "adm2_pcode")
    district_ids = sorted(districts)

    rows, stats = collect_localities(osm, districts, district_ids)
    path = os.path.join(output_dir, "bd_localities.tsv")
    line_count = write_localities(path, rows, source_timestamp, stats)

    by_district = {}
    for row in rows:
        by_district[row["district"]] = by_district.get(row["district"], 0) + 1
    by_kind = {}
    for row in rows:
        by_kind[row["kind"]] = by_kind.get(row["kind"], 0) + 1

    print(f"localities written: {len(rows)} ({line_count} lines including the header)")
    print(f"  kinds: {by_kind}")
    print(f"  districts covered: {len(by_district)} of {len(district_ids)}")
    print(f"  elements read: {stats['elements']}")
    print(f"  excluded - not a locality kind: {stats['not_a_locality_kind']}")
    print(f"  excluded - no English name: {stats['no_english_name']}")
    print(f"  excluded - unusable name: {stats['unusable_name']}")
    print(f"  excluded - no position: {stats['no_position']}")
    print(f"  excluded - in no district: {stats['outside_every_district']}")
    print(f"  excluded - duplicate: {stats['duplicates']}")
    print(f"  written without a Bangla name: {stats['without_bangla_name']}")
    top = sorted(by_district.items(), key=lambda item: -item[1])[:5]
    print(f"  most covered districts: {top}")
    print(f"  file size: {os.path.getsize(path)} bytes")


if __name__ == "__main__":
    main()
