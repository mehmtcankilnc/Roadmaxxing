"""Consistency checks on the committed Adana pack (test-data/regions/adana).

Decodes every tile and recomputes region totals from the segments, so a
reader following FORMAT.md sees exactly what regions.json promises.
"""
import json
from pathlib import Path

import pytest

from shapely.geometry import LineString, Point, box

from roadmaxxing_regions.geo import tile_bounds
from roadmaxxing_regions.rules import layer_of
from roadmaxxing_regions.tileformat import decode_tile

PACK = Path(__file__).resolve().parent.parent.parent.parent / "test-data" / "regions" / "adana"

pytestmark = pytest.mark.skipif(not (PACK / "manifest.json").exists(), reason="Adana pack not built")

EXPECTED_DISTRICTS = {
    "Aladağ", "Ceyhan", "Çukurova", "Feke", "İmamoğlu", "Karaisalı", "Karataş", "Kozan",
    "Pozantı", "Saimbeyli", "Sarıçam", "Seyhan", "Tufanbeyli", "Yumurtalık", "Yüreğir",
}


@pytest.fixture(scope="module")
def pack():
    manifest = json.loads((PACK / "manifest.json").read_text(encoding="utf-8"))
    regions = json.loads((PACK / "regions.json").read_text(encoding="utf-8"))["regions"]
    z = manifest["tile_zoom"]
    segs = {}
    tiles = {}
    for x, y, count, size in manifest["tiles"]:
        data = (PACK / "tiles" / str(z) / str(x) / f"{y}.bin").read_bytes()
        assert len(data) == size
        t = decode_tile(data)
        assert (t.zoom, t.x, t.y) == (z, x, y) and len(t.segments) == count
        tiles[(x, y)] = t
        for s in t.segments:
            key = (s.way_id, s.ordinal)
            if key in segs:
                assert segs[key] == s, "duplicate copies of a segment must be identical"
            segs[key] = s
    return manifest, regions, segs, tiles


def test_all_15_districts(pack):
    _, regions, _, _ = pack
    districts = [r for r in regions if r["level"] == 2]
    assert len(districts) == 15
    assert {r["name"] for r in districts} == EXPECTED_DISTRICTS


def test_segment_count(pack):
    manifest, _, segs, _ = pack
    assert len(segs) == manifest["segment_count"]


def test_totals_match_segments(pack):
    _, regions, segs, _ = pack
    province = regions[0]
    assert province["level"] == 1 and province["iso3166_2"] == "TR-01"
    per = {r["id"]: {"major": 0, "local": 0} for r in regions}
    for s in segs.values():
        layer = "major" if layer_of(s.road_class) == 0 else "local"
        per[s.district_id][layer] += s.length_dm
        per[s.province_id][layer] += s.length_dm
        assert s.province_id == province["id"]
    for r in regions:
        assert r["length_dm"]["major"] == per[r["id"]]["major"]
        assert r["length_dm"]["local"] == per[r["id"]]["local"]
        assert r["length_dm"]["all"] == r["length_dm"]["major"] + r["length_dm"]["local"]


def test_province_equals_sum_of_districts(pack):
    _, regions, _, _ = pack
    for layer in ("major", "local", "all"):
        assert regions[0]["length_dm"][layer] == sum(r["length_dm"][layer] for r in regions[1:])


def test_every_segment_touches_its_tile(pack):
    # Tiles are assigned on full-precision coordinates and include edge contact;
    # stored coordinates are quantised, so allow one quantum of slack.
    manifest, _, _, tiles = pack
    z = manifest["tile_zoom"]
    for (x, y), t in tiles.items():
        scale = 10 ** t.coord_decimals
        w, s_, e, n = tile_bounds(x, y, z)
        cell = box(w, s_, e, n).buffer(2.0 / scale)
        for seg in t.segments:
            pts = [(lon / scale, lat / scale) for lon, lat in seg.coords]
            geom = LineString(pts) if len(set(pts)) > 1 else Point(pts[0])
            assert geom.intersects(cell), (seg.way_id, seg.ordinal, x, y)
