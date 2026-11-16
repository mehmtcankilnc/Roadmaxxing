"""Extract the Adana-area road and rail network from an OSM .pbf into a small cache.

This is the only step that needs a third-party package: pyosmium (osmium==4.0.2,
the same pinned version as tools/regions/requirements.txt). The generator itself
(generate.py) is standard-library only and reads the cache this script writes.

Using the same Geofabrik extract as tools/regions guarantees that the node and
way ids in the expected.json files match the ids in the region package.

Usage:
    python tools/gpx/extract_osm.py --pbf tools/regions/.cache/turkey-261115.osm.pbf
"""
from __future__ import annotations

import argparse
import bisect
import gzip
import json
import os
import sys
from array import array

import osmium

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_OUT = os.path.join(HERE, ".cache", "adana-osm.json.gz")
# west, south, east, north - covers Adana city, Ceyhan and the rail line to Yenice.
DEFAULT_BBOX = (35.00, 36.85, 35.90, 37.15)
KEEP_TAGS = ("highway", "railway", "name", "ref", "oneway", "maxspeed", "junction",
             "access", "motor_vehicle", "service", "usage", "lanes", "bridge", "tunnel")


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--pbf", required=True, help="OSM .pbf extract (Geofabrik)")
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--bbox", type=float, nargs=4, default=DEFAULT_BBOX,
                    metavar=("WEST", "SOUTH", "EAST", "NORTH"))
    args = ap.parse_args(argv)

    west, south, east, north = (round(v * 1e7) for v in args.bbox)
    header = osmium.io.Reader(args.pbf, osmium.osm.osm_entity_bits.NOTHING).header()
    timestamp = header.get("osmosis_replication_timestamp", "")

    ids, lats, lons = array("q"), array("i"), array("i")
    for n in osmium.FileProcessor(args.pbf, osmium.osm.NODE):
        loc = n.location
        x, y = loc.x, loc.y
        if west <= x <= east and south <= y <= north:
            ids.append(n.id)
            lats.append(y)
            lons.append(x)
    print(f"nodes in bbox: {len(ids)}", file=sys.stderr)

    def lookup(node_id: int) -> int:
        i = bisect.bisect_left(ids, node_id)
        return i if i < len(ids) and ids[i] == node_id else -1

    ways = []
    used: dict[int, list[float]] = {}
    fp = osmium.FileProcessor(args.pbf, osmium.osm.WAY).with_filter(
        osmium.filter.KeyFilter("highway", "railway"))
    for w in fp:
        refs = [n.ref for n in w.nodes]
        idx = [lookup(r) for r in refs]
        if all(i < 0 for i in idx):
            continue
        tags = {t.k: t.v for t in w.tags if t.k in KEEP_TAGS}
        # keep the longest run of consecutive in-bbox nodes
        runs, cur = [], []
        for r, i in zip(refs, idx):
            if i >= 0:
                cur.append((r, i))
            elif cur:
                runs.append(cur)
                cur = []
        if cur:
            runs.append(cur)
        run = max(runs, key=len)
        if len(run) < 2:
            continue
        for r, i in run:
            used[r] = [lats[i] / 1e7, lons[i] / 1e7]
        ways.append({"id": w.id, "tags": tags, "nodes": [r for r, _ in run]})
    ways.sort(key=lambda w: w["id"])
    print(f"ways kept: {len(ways)}, nodes used: {len(used)}", file=sys.stderr)

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    payload = {
        "source": os.path.basename(args.pbf),
        "osm_timestamp": timestamp,
        "bbox": list(args.bbox),
        "nodes": {str(k): used[k] for k in sorted(used)},
        "ways": ways,
    }
    with gzip.open(args.out, "wt", encoding="utf-8") as f:
        json.dump(payload, f, separators=(",", ":"))
    print(f"wrote {args.out}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
