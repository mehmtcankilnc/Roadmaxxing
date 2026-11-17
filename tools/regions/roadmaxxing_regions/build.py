"""Region pack builder: OSM extract -> segments -> regions.json + binary tiles."""
from __future__ import annotations

import gzip
import hashlib
import json
import shutil
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

import shapely
from shapely.geometry import LineString, box

from . import osmread
from .boundaries import assemble_polygon, simplified_rings
from .geo import OSM_SCALE, length_dm, lonlat_to_tile, midpoint, tile_bounds
from .rules import LAYER_NAMES, layer_of
from .segindex import FILE_NAME as SEGMENT_INDEX_FILE, SegmentSummary, encode_index
from .segments import resolve, split_ways
from .tileformat import Tile, TileSegment, encode_tile, quantize, tile_to_json_obj

PROVINCE_ADMIN_LEVEL = "4"
DISTRICT_ADMIN_LEVEL = "6"
REGION_LEVEL_PROVINCE = 1
REGION_LEVEL_DISTRICT = 2

DEFAULT_ZOOM = 13
DEFAULT_COORD_DECIMALS = 6
BBOX_PAD_DEG = 0.02
BOUNDARY_TOLERANCE_DEG = 0.0005   # ~50 m, for the simplified boundary in regions.json
BOUNDARY_DECIMALS = 5

Log = Callable[[str], None]


def _print(msg: str) -> None:
    print(msg, flush=True)


@dataclass
class District:
    id: int
    tags: dict[str, str]
    geom: object


@dataclass
class PackSegment:
    way_id: int
    ordinal: int
    road_class: int
    oneway: int
    node_ids: tuple[int, ...]
    coords7: list[tuple[int, int]]
    length_dm: int
    province_id: int
    district_id: int = 0


@dataclass
class BuildResult:
    province_id: int
    province_tags: dict[str, str]
    province_geom: object
    districts: list[District]
    segments: list[PackSegment]
    stats: dict = field(default_factory=dict)


def _ascii_slug(name: str) -> str:
    table = str.maketrans("çğıöşüÇĞİÖŞÜâîû", "cgiosuCGIOSUaiu")
    s = name.translate(table).lower()
    return "".join(ch if ch.isalnum() else "-" for ch in s).strip("-")


def _matches(rel: osmread.AdminRelation, query: str) -> bool:
    q = query.strip()
    if q.lower().startswith("r") and q[1:].isdigit():
        return rel.id == int(q[1:])
    keys = ("name", "name:tr", "name:en", "ISO3166-2")
    return any(rel.tags.get(k, "").casefold() == q.casefold() for k in keys)


def extract(path: str, province: str, log: Log = _print) -> BuildResult:
    """Read the OSM file and produce the province's segments and districts."""
    t0 = time.time()
    rels = osmread.read_admin_relations(path, (PROVINCE_ADMIN_LEVEL, DISTRICT_ADMIN_LEVEL))
    matches = [r for r in rels if r.admin_level == PROVINCE_ADMIN_LEVEL and _matches(r, province)]
    if len(matches) != 1:
        raise SystemExit(f"province '{province}' matched {len(matches)} admin_level=4 relations")
    prov = matches[0]
    level6 = [r for r in rels if r.admin_level == DISTRICT_ADMIN_LEVEL]
    log(f"[{time.time()-t0:6.1f}s] relations: province r{prov.id}, {len(level6)} admin_level=6 candidates")

    member_ways = {w for w, _ in prov.members}
    for r in level6:
        member_ways.update(w for w, _ in r.members)
    way_refs, boundary_nodes = osmread.read_ways_with_locations(path, member_ways)
    prov_geom = assemble_polygon(prov.members, way_refs, boundary_nodes)
    if prov_geom is None:
        raise SystemExit(f"could not assemble boundary polygon for r{prov.id}")
    w, s, e, n = prov_geom.bounds
    log(f"[{time.time()-t0:6.1f}s] province boundary assembled, bbox {w:.3f},{s:.3f},{e:.3f},{n:.3f}")

    shapely.prepare(prov_geom)
    districts: list[District] = []
    for r in level6:
        geom = assemble_polygon(r.members, way_refs, boundary_nodes)
        if geom is None:
            continue
        if prov_geom.contains(geom.representative_point()):
            districts.append(District(r.id, r.tags, geom))
    districts.sort(key=lambda d: d.id)
    if not districts:
        raise SystemExit("no districts found inside the province")
    log(f"[{time.time()-t0:6.1f}s] {len(districts)} districts")

    bbox7 = tuple(int(round(v * OSM_SCALE)) for v in
                  (w - BBOX_PAD_DEG, s - BBOX_PAD_DEG, e + BBOX_PAD_DEG, n + BBOX_PAD_DEG))
    ways, nodes = osmread.read_drivable_ways(path, bbox7)
    log(f"[{time.time()-t0:6.1f}s] {len(ways)} drivable ways touching the padded bbox")

    segments, stats = assign(split_ways(ways), nodes, prov.id, prov_geom, districts)
    log(f"[{time.time()-t0:6.1f}s] {len(segments)} segments in province")
    stats["drivable_ways_read"] = len(ways)
    return BuildResult(prov.id, prov.tags, prov_geom, districts, segments, stats)


def assign(raw_segments, nodes, province_id: int, province_geom, districts: list[District]):
    """Keep segments whose midpoint is in the province; give each one district.

    Rule (FORMAT.md, "Region assignment"): a segment belongs entirely to the
    region containing its midpoint (point at half its length). Segments are
    never cut at region boundaries. If the midpoint is in no district polygon
    (boundary gaps), the nearest district wins; if it is in several
    (overlaps), the lowest relation id wins.
    """
    stats = {"segments_unresolved": 0, "district_gap_assigned": 0, "district_overlap": 0}
    cand: list[PackSegment] = []
    mids_x, mids_y = [], []
    for seg in raw_segments:
        coords = resolve(seg.node_ids, nodes)
        if coords is None:
            stats["segments_unresolved"] += 1
            continue
        mx, my = midpoint(coords)
        cand.append(PackSegment(seg.way_id, seg.ordinal, seg.road_class, seg.oneway,
                                seg.node_ids, coords, length_dm(coords), province_id))
        mids_x.append(mx)
        mids_y.append(my)
    if not cand:
        return [], stats
    import numpy as np  # shipped with shapely
    xs = np.asarray(mids_x)
    ys = np.asarray(mids_y)
    inside = shapely.contains_xy(province_geom, xs, ys)
    keep_idx = np.nonzero(inside)[0]
    xs, ys = xs[keep_idx], ys[keep_idx]
    kept = [cand[i] for i in keep_idx]

    hits = np.zeros((len(districts), len(kept)), dtype=bool)
    for j, d in enumerate(districts):
        shapely.prepare(d.geom)
        hits[j] = shapely.contains_xy(d.geom, xs, ys)
    counts = hits.sum(axis=0)
    first = hits.argmax(axis=0)
    for i, seg in enumerate(kept):
        if counts[i] >= 1:
            if counts[i] > 1:
                stats["district_overlap"] += 1
            seg.district_id = districts[int(first[i])].id
        else:
            stats["district_gap_assigned"] += 1
            p = shapely.Point(float(xs[i]), float(ys[i]))
            best = min(districts, key=lambda d: (d.geom.distance(p), d.id))
            seg.district_id = best.id
    kept.sort(key=lambda s: (s.way_id, s.ordinal))
    return kept, stats


# --- outputs -------------------------------------------------------------------

def _totals(segs) -> dict:
    t = {"major": 0, "local": 0}
    c = {"major": 0, "local": 0}
    for s in segs:
        k = LAYER_NAMES[layer_of(s.road_class)]
        t[k] += s.length_dm
        c[k] += 1
    return {"length_dm": {"major": t["major"], "local": t["local"], "all": t["major"] + t["local"]},
            "segment_count": {"major": c["major"], "local": c["local"], "all": c["major"] + c["local"]}}


def _region_entry(rid, level, parent, tags, geom, segs) -> dict:
    return {
        "id": rid,
        "level": level,
        "parent_id": parent,
        "osm_admin_level": int(tags.get("admin_level", "0")),
        "name": tags.get("name"),
        "name_tr": tags.get("name:tr"),
        "name_en": tags.get("name:en"),
        "iso3166_2": tags.get("ISO3166-2"),
        **_totals(segs),
        "bbox": [round(v, BOUNDARY_DECIMALS) for v in geom.bounds],
        "boundary": simplified_rings(geom, BOUNDARY_TOLERANCE_DEG, BOUNDARY_DECIMALS),
    }


def build_regions_json(res: BuildResult) -> dict:
    by_district: dict[int, list[PackSegment]] = {d.id: [] for d in res.districts}
    for s in res.segments:
        by_district[s.district_id].append(s)
    regions = [_region_entry(res.province_id, REGION_LEVEL_PROVINCE, None, res.province_tags,
                             res.province_geom, res.segments)]
    for d in res.districts:
        regions.append(_region_entry(d.id, REGION_LEVEL_DISTRICT, res.province_id, d.tags, d.geom,
                                     by_district[d.id]))
    return {"format": "roadmaxxing-regions", "format_version": 1, "regions": regions}


def build_tiles(segs: list[PackSegment], zoom: int, coord_decimals: int) -> dict[tuple[int, int], Tile]:
    """Every segment is written to every tile its geometry intersects."""
    per_tile: dict[tuple[int, int], list[TileSegment]] = {}
    for s in segs:
        qcoords = tuple((quantize(x, coord_decimals), quantize(y, coord_decimals)) for x, y in s.coords7)
        ts = TileSegment(s.way_id, s.ordinal, s.road_class, s.oneway, s.province_id, s.district_id,
                         s.length_dm, s.node_ids, qcoords)
        deg = [(x / OSM_SCALE, y / OSM_SCALE) for x, y in s.coords7]
        lons = [p[0] for p in deg]
        lats = [p[1] for p in deg]
        x0, y1 = lonlat_to_tile(min(lons), min(lats), zoom)
        x1, y0 = lonlat_to_tile(max(lons), max(lats), zoom)
        line = LineString(deg) if len(set(deg)) > 1 else None
        for tx in range(x0, x1 + 1):
            for ty in range(y0, y1 + 1):
                if (x0, y0) != (x1, y1) and line is not None:
                    if not line.intersects(box(*tile_bounds(tx, ty, zoom))):
                        continue
                per_tile.setdefault((tx, ty), []).append(ts)
    return {k: Tile(zoom, k[0], k[1], coord_decimals, tuple(v)) for k, v in sorted(per_tile.items())}


def _dump_json(obj, path: Path) -> None:
    text = json.dumps(obj, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    path.write_text(text + "\n", encoding="utf-8", newline="\n")


def file_md5(path: str) -> str:
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def write_pack(res: BuildResult, out_dir: Path, pack_id: str, source: dict,
               zoom: int = DEFAULT_ZOOM, coord_decimals: int = DEFAULT_COORD_DECIMALS) -> dict:
    out_dir.mkdir(parents=True, exist_ok=True)
    tiles_dir = out_dir / "tiles"
    if tiles_dir.exists():
        shutil.rmtree(tiles_dir)
    tiles = build_tiles(res.segments, zoom, coord_decimals)
    tile_rows = []
    for (x, y), tile in tiles.items():
        data = encode_tile(tile)
        p = tiles_dir / str(zoom) / str(x) / f"{y}.bin"
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(data)
        tile_rows.append([x, y, len(tile.segments), len(data)])
    seg_index = encode_index([SegmentSummary(s.way_id * 4096 + s.ordinal, s.length_dm, s.road_class,
                                             s.province_id, s.district_id) for s in res.segments])
    (out_dir / SEGMENT_INDEX_FILE).write_bytes(seg_index)
    regions = build_regions_json(res)
    _dump_json(regions, out_dir / "regions.json")
    manifest = {
        "format": "roadmaxxing-region-pack",
        "format_version": 1,
        "pack_id": pack_id,
        "province_id": res.province_id,
        "source": source,
        "tile_zoom": zoom,
        "coord_decimals": coord_decimals,
        "tile_path": "tiles/{z}/{x}/{y}.bin",
        "tile_columns": ["x", "y", "segment_count", "bytes"],
        "tiles": tile_rows,
        "segment_count": len(res.segments),
        "segment_index": {"path": SEGMENT_INDEX_FILE, "bytes": len(seg_index)},
        "stats": dict(sorted(res.stats.items())),
    }
    _dump_json(manifest, out_dir / "manifest.json")
    return manifest


def measure(res: BuildResult, zooms=(12, 13, 14), decimals=(6, 7)) -> list[dict]:
    """Size of the pack for each zoom / precision, binary vs JSON."""
    rows = []
    for z in zooms:
        for d in decimals:
            tiles = build_tiles(res.segments, z, d)
            sizes = [len(encode_tile(t)) for t in tiles.values()]
            jsizes, gsizes = [], []
            for t in tiles.values():
                raw = json.dumps(tile_to_json_obj(t), separators=(",", ":")).encode()
                jsizes.append(len(raw))
                gsizes.append(len(gzip.compress(raw, 9, mtime=0)))
            rows.append({
                "zoom": z, "coord_decimals": d, "tiles": len(tiles),
                "bin_total": sum(sizes), "bin_max": max(sizes),
                "bin_median": sorted(sizes)[len(sizes) // 2],
                "json_total": sum(jsizes), "json_gzip_total": sum(gsizes),
                "stored_segments": sum(len(t.segments) for t in tiles.values()),
            })
    return rows
