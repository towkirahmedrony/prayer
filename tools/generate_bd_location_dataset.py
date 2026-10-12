#!/usr/bin/env python3
"""Generates the Bangladesh location dataset bundled inside Salah.

Why this exists
---------------
Salah ships one offline dataset for Bangladesh: the division -> district -> upazila/thana hierarchy
with a published representative coordinate for every unit. That dataset is *derived*, not
hand-written, so this script is the derivation: it is committed alongside the generated assets so
that anyone can see exactly which public source the bundled numbers came from, re-run the
extraction, and check the result.

Sources (see docs/LOCATION_SYSTEM.md section 5 for the full licence discussion)
-------------------------------------------------------------------------------
1. **Bangladesh Common Operational Dataset on Administrative Boundaries (COD-AB)**, published by
   OCHA / HDX as `cod-ab-bgd`. Provides the administrative hierarchy (p-codes), English names,
   area, geometry, and a published `center_lat` / `center_lon` per unit. Used for: the hierarchy,
   the English names, the coordinates, and the national boundary polygon.
   Licence: Creative Commons Attribution for Intergovernmental Organisations (CC BY 3.0 IGO).
   http://creativecommons.org/licenses/by/3.0/igo/

2. **nuhil/bangladesh-geocode** (MIT). Used only for the **Bangla** name of a unit, joined by name.
   Licence: MIT. https://github.com/nuhil/bangladesh-geocode

Usage
-----
    python3 tools/generate_bd_location_dataset.py [source_dir] [output_dir]

`source_dir` must already contain the unzipped COD-AB files
(`bgd_admin0.geojson` ... `bgd_admin3.geojson`); the archive is at
https://data.humdata.org/dataset/cod-ab-bgd

Output
------
    app/src/main/assets/location/bd_areas.tsv        hierarchy + names + coordinates
    app/src/main/assets/location/bd_boundary.tsv     simplified national boundary rings

Both files are plain tab-separated text with `#` comments, parsed by
`BangladeshLocationDataset` (pure Kotlin, no JSON or CSV dependency).
"""

import csv
import difflib
import json
import os
import sys
import unicodedata

COD_VERSION_NOTE = "COD-AB BGD v03 (valid_on 2023-05-21)"

# Two sources transliterate Bangla into Latin script differently ("Bogura" vs "Bogra", "chh" vs
# "ch", "kh" vs "k"), so upazila names are joined in three steps and each step is reported:
#   1. exact match on a case/punctuation-insensitive key (after the aliases below);
#   2. a similarity match inside the *same district*, accepted only at or above
#      [FUZZY_SIMILARITY_THRESHOLD] - high enough that a wrong pairing is not plausible; every
#      accepted pair is printed so it can be reviewed;
#   3. otherwise no Bangla name is attached at all, and the app shows the English name.
FUZZY_SIMILARITY_THRESHOLD = 0.90

# Bangla names come from a separate MIT-licensed project and are joined by name. These aliases cover
# the spelling differences between the two sources; anything still unmatched simply keeps no Bangla
# name, and the app then shows the English name. Nothing is guessed.
DIVISION_ALIASES = {
    "chattagram": "chattogram",
    "chittagong": "chattogram",
    "barisal": "barishal",
}

# Both sides of the join are mapped onto one canonical key, so these entries must be symmetric:
# every spelling either source uses maps to the same value.
DISTRICT_ALIASES = {
    "barisal": "barishal",
    "barishal": "barishal",
    "bogra": "bogura",
    "bogura": "bogura",
    "chapainababganj": "chapainawabganj",
    "chapainawabganj": "chapainawabganj",
    "coxsbazar": "coxsbazar",
    "comilla": "cumilla",
    "cumilla": "cumilla",
    "jhalakathi": "jhalokati",
    "jhalokati": "jhalokati",
    "jhalokathi": "jhalokati",
    "jhenaida": "jhenaidah",
    "jhenaidah": "jhenaidah",
    "khagrachari": "khagrachhari",
    "khagrachhari": "khagrachhari",
    "maulavibazar": "moulvibazar",
    "maulvibazar": "moulvibazar",
    "moulvibazar": "moulvibazar",
    "netrakona": "netrakona",
    "netrokona": "netrakona",
    "nawabganj": "chapainawabganj",
}

BOUNDARY_TOLERANCE_DEGREES = 0.005
COORDINATE_DECIMALS = 6


def normalise(name):
    """Case-, punctuation- and space-insensitive key for joining two name lists."""
    if not name:
        return ""
    folded = unicodedata.normalize("NFKD", name).lower()
    return "".join(ch for ch in folded if ch.isalnum())


def load_geojson(source_dir, filename):
    with open(os.path.join(source_dir, filename), encoding="utf-8") as handle:
        return json.load(handle)["features"]


def read_csv_rows(source_dir, filename, columns):
    """Reads one of the MIT-licensed name CSVs. Missing files are not fatal: the join is optional."""
    path = os.path.join(source_dir, filename)
    if not os.path.exists(path):
        return []
    rows = []
    with open(path, encoding="utf-8") as handle:
        for line in csv.reader(handle):
            if len(line) >= len(columns):
                rows.append(dict(zip(columns, line)))
    return rows


def district_id_by_canonical_key(source_dir):
    """`{canonical_district_key: nuhil_district_id}`, for looking up a district's upazila list."""
    mapping = {}
    for row in read_csv_rows(source_dir, "districts.csv", ["id", "division_id", "name", "bn"]):
        key = normalise(row["name"])
        mapping[DISTRICT_ALIASES.get(key, key)] = row["id"]
    return mapping


def load_upazila_names_by_district(source_dir):
    """`{district_id: [(nuhil_key, bangla_name), ...]}`, for the within-district similarity match."""
    by_district = {}
    for row in read_csv_rows(source_dir, "upazilas.csv", ["id", "district_id", "name", "bn"]):
        by_district.setdefault(row["district_id"], []).append((normalise(row["name"]), row["bn"]))
    return by_district


def build_bangla_index(source_dir):
    """Returns `(division_bn, district_bn, upazila_bn)` keyed by normalised names."""
    division_bn = {}
    for row in read_csv_rows(source_dir, "divisions.csv", ["id", "name", "bn"]):
        key = DIVISION_ALIASES.get(normalise(row["name"]), normalise(row["name"]))
        division_bn[key] = row["bn"]

    district_by_id = {}
    district_bn = {}
    for row in read_csv_rows(source_dir, "districts.csv", ["id", "division_id", "name", "bn"]):
        key = DISTRICT_ALIASES.get(normalise(row["name"]), normalise(row["name"]))
        district_bn[key] = row["bn"]
        district_by_id[row["id"]] = key

    upazila_bn = {}
    for row in read_csv_rows(source_dir, "upazilas.csv", ["id", "district_id", "name", "bn"]):
        district_key = district_by_id.get(row["district_id"])
        if district_key is None:
            continue
        upazila_bn[(district_key, normalise(row["name"]))] = row["bn"]

    return division_bn, district_bn, upazila_bn


def simplify(points, tolerance):
    """Ramer-Douglas-Peucker, iterative, so a long coastline cannot blow the Python stack."""
    if len(points) < 3:
        return points

    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        first, last = stack.pop()
        if last <= first + 1:
            continue
        x1, y1 = points[first]
        x2, y2 = points[last]
        dx, dy = x2 - x1, y2 - y1
        segment = (dx * dx + dy * dy) ** 0.5
        worst, worst_index = -1.0, None
        for index in range(first + 1, last):
            x, y = points[index]
            if segment == 0:
                distance = ((x - x1) ** 2 + (y - y1) ** 2) ** 0.5
            else:
                distance = abs(dy * (x - x1) - dx * (y - y1)) / segment
            if distance > worst:
                worst, worst_index = distance, index
        if worst > tolerance:
            keep[worst_index] = True
            stack.append((first, worst_index))
            stack.append((worst_index, last))

    return [point for point, kept in zip(points, keep) if kept]


def format_number(value):
    return f"{value:.{COORDINATE_DECIMALS}f}"


def write_areas(path, divisions, districts, upazilas):
    lines = [
        "# Salah location dataset - Bangladesh administrative areas.",
        f"# Source: {COD_VERSION_NOTE}, OCHA / HDX COD-AB (CC BY 3.0 IGO).",
        "# Bangla names: nuhil/bangladesh-geocode (MIT), joined by name; a blank field means no",
        "# name could be matched, and the app then shows the English name.",
        "# Generated by tools/generate_bd_location_dataset.py - do not edit by hand.",
        "#",
        "# level<TAB>id<TAB>parent_id<TAB>name<TAB>bn_name<TAB>latitude<TAB>longitude",
    ]
    for level, rows in (("DIVISION", divisions), ("DISTRICT", districts), ("UPAZILA", upazilas)):
        for row in rows:
            lines.append(
                "\t".join(
                    [
                        level,
                        row["id"],
                        row["parent"],
                        row["name"],
                        row["bn"] or "",
                        format_number(row["lat"]),
                        format_number(row["lon"]),
                    ]
                )
            )
    with open(path, "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines) + "\n")
    return len(lines)


def write_boundary(path, rings, tolerance):
    lines = [
        "# Salah location dataset - simplified national boundary of Bangladesh (adm0).",
        f"# Source: {COD_VERSION_NOTE}, OCHA / HDX COD-AB (CC BY 3.0 IGO).",
        f"# Simplified with Ramer-Douglas-Peucker, tolerance {tolerance} degrees, all rings kept.",
        "# Generated by tools/generate_bd_location_dataset.py - do not edit by hand.",
        "#",
        "# ring_index<TAB>latitude<TAB>longitude",
    ]
    total = 0
    for index, ring in enumerate(rings):
        for x, y in ring:
            lines.append(f"{index}\t{format_number(y)}\t{format_number(x)}")
            total += 1
    with open(path, "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines) + "\n")
    return total


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

def verify_centers_inside(divisions, districts, upazilas, polygons):
    """
    A data-quality check on the source itself: every published center must fall inside its own
    boundary. This catches a coordinate that was written against the wrong row, which no amount of
    app-side validation could detect. Failures are reported, never silently dropped.
    """
    failures = []
    for level, rows in (("division", divisions), ("district", districts), ("upazila", upazilas)):
        for row in rows:
            polygon = polygons.get(row["id"])
            if polygon is None:
                failures.append(f"{level} {row['id']} {row['name']}: no boundary in the source")
                continue
            if not any(point_in_ring(row["lon"], row["lat"], ring) for ring in polygon):
                failures.append(f"{level} {row['id']} {row['name']}: center outside its own boundary")
    return failures


def main():
    source_dir = sys.argv[1] if len(sys.argv) > 1 else "/tmp/bdgeo"
    output_dir = sys.argv[2] if len(sys.argv) > 2 else "app/src/main/assets/location"
    os.makedirs(output_dir, exist_ok=True)

    division_bn, district_bn, upazila_bn = build_bangla_index(source_dir)
    upazila_names = load_upazila_names_by_district(source_dir)
    fuzzy_matches = []

    divisions = []
    for feature in load_geojson(source_dir, "bgd_admin1.geojson"):
        props = feature["properties"]
        key = normalise(props["adm1_name"])
        key = DIVISION_ALIASES.get(key, key)
        divisions.append(
            {
                "id": props["adm1_pcode"],
                "parent": props["adm0_pcode"],
                "name": props["adm1_name"],
                "bn": division_bn.get(key),
                "lat": props["center_lat"],
                "lon": props["center_lon"],
                "key": key,
            }
        )

    districts = []
    for feature in load_geojson(source_dir, "bgd_admin2.geojson"):
        props = feature["properties"]
        key = normalise(props["adm2_name"])
        key = DISTRICT_ALIASES.get(key, key)
        districts.append(
            {
                "id": props["adm2_pcode"],
                "parent": props["adm1_pcode"],
                "name": props["adm2_name"],
                "bn": district_bn.get(key),
                "lat": props["center_lat"],
                "lon": props["center_lon"],
                "key": key,
            }
        )

    district_key_by_pcode = {row["id"]: row["key"] for row in districts}

    district_id_by_key = district_id_by_canonical_key(source_dir)

    upazilas = []
    for feature in load_geojson(source_dir, "bgd_admin3.geojson"):
        props = feature["properties"]
        key = normalise(props["adm3_name"])
        parent_key = district_key_by_pcode.get(props["adm2_pcode"])
        bangla = upazila_bn.get((parent_key, key))
        if bangla is None:
            # Same district only: a name that resembles an upazila in a *different* district is a
            # coincidence, not a match.
            candidates = upazila_names.get(district_id_by_key.get(parent_key), [])
            close = difflib.get_close_matches(
                key, [candidate for candidate, _ in candidates], n=1, cutoff=FUZZY_SIMILARITY_THRESHOLD
            )
            if close:
                bangla = next(name for candidate, name in candidates if candidate == close[0])
                fuzzy_matches.append(
                    (
                        props["adm3_name"],
                        close[0],
                        round(difflib.SequenceMatcher(None, key, close[0]).ratio(), 3),
                    )
                )
        upazilas.append(
            {
                "id": props["adm3_pcode"],
                "parent": props["adm2_pcode"],
                "name": props["adm3_name"],
                "bn": bangla,
                "lat": props["center_lat"],
                "lon": props["center_lon"],
                "key": key,
            }
        )

    # Data-quality verification against the source's own geometry, before anything is written.
    def collect_polygons(filename, pcode_field):
        polygons = {}
        for feature in load_geojson(source_dir, filename):
            geometry = feature["geometry"]
            if geometry["type"] == "Polygon":
                polygons[feature["properties"][pcode_field]] = geometry["coordinates"]
            else:
                polygons[feature["properties"][pcode_field]] = [
                    ring for polygon in geometry["coordinates"] for ring in polygon
                ]
        return polygons

    source_polygons = {}
    source_polygons.update(collect_polygons("bgd_admin1.geojson", "adm1_pcode"))
    source_polygons.update(collect_polygons("bgd_admin2.geojson", "adm2_pcode"))
    source_polygons.update(collect_polygons("bgd_admin3.geojson", "adm3_pcode"))
    verification_failures = verify_centers_inside(divisions, districts, upazilas, source_polygons)

    boundary_feature = load_geojson(source_dir, "bgd_admin0.geojson")[0]
    geometry = boundary_feature["geometry"]
    polygons = geometry["coordinates"] if geometry["type"] == "MultiPolygon" else [geometry["coordinates"]]
    rings = []
    for polygon in polygons:
        for ring in polygon:
            simplified = simplify([(point[0], point[1]) for point in ring], BOUNDARY_TOLERANCE_DEGREES)
            if len(simplified) >= 3:
                rings.append(simplified)

    area_rows = write_areas(os.path.join(output_dir, "bd_areas.tsv"), divisions, districts, upazilas)
    boundary_rows = write_boundary(
        os.path.join(output_dir, "bd_boundary.tsv"), rings, BOUNDARY_TOLERANCE_DEGREES
    )

    def report(label, rows):
        matched = sum(1 for row in rows if row["bn"])
        print(f"{label}: {len(rows)} units, Bangla names for {matched}")
        missing = [row["name"] for row in rows if not row["bn"]]
        if missing:
            print(f"  no Bangla name ({len(missing)}): {', '.join(sorted(missing)[:40])}")

    report("divisions", divisions)
    report("districts", districts)
    print(f"upazilas joined to a Bangla name by similarity ({len(fuzzy_matches)}):")
    for cod_name, source_name, ratio in sorted(fuzzy_matches):
        print(f"    {cod_name} ~ {source_name} ({ratio})")
    report("upazilas", upazilas)
    print(f"center-inside-own-boundary failures: {len(verification_failures)}")
    for failure in verification_failures[:20]:
        print(f"    {failure}")
    print(
        f"areas: {area_rows} lines -> {os.path.getsize(os.path.join(output_dir, 'bd_areas.tsv'))} bytes"
    )
    print(
        f"boundary: {len(rings)} rings, {boundary_rows} points -> "
        f"{os.path.getsize(os.path.join(output_dir, 'bd_boundary.tsv'))} bytes"
    )


if __name__ == "__main__":
    main()
