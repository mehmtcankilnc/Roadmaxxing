"""End-to-end tests on the synthetic fixture (tests/data/fixture.osm).

The committed pack in test-data/regions/fixture/ is the golden output: any
change to it must be deliberate (regenerate with the command in README.md).
"""
import json
from pathlib import Path

import pytest

from roadmaxxing_regions import build
from roadmaxxing_regions.rules import CLASS_CODE, layer_of
from roadmaxxing_regions.segments import segment_id
from roadmaxxing_regions.tileformat import decode_tile

HERE = Path(__file__).resolve().parent
FIXTURE_OSM = HERE / "data" / "fixture.osm"
GOLDEN = HERE.parent.parent.parent / "test-data" / "regions" / "fixture"
WEST, EAST, PROVINCE = 11, 12, 1


def _build(out: Path) -> dict:
    res = build.extract(str(FIXTURE_OSM), "Testil", log=lambda _m: None)
    source = {"file": "fixture.osm", "url": None, "md5": build.file_md5(str(FIXTURE_OSM)),
              "osm_replication_timestamp": ""}
    build.write_pack(res, out, "fixture", source)
    return {p.relative_to(out).as_posix(): p.read_bytes() for p in sorted(out.rglob("*")) if p.is_file()}


@pytest.fixture(scope="module")
def pack(tmp_path_factory):
    out = tmp_path_factory.mktemp("pack")
    files = _build(out)
    regions = json.loads(files["regions.json"])
    manifest = json.loads(files["manifest.json"])
    tiles = [decode_tile(v) for k, v in files.items() if k.startswith("tiles/")]
    segs = {}
    for t in tiles:
        for s in t.segments:
            segs[(s.way_id, s.ordinal)] = s
    return {"files": files, "regions": regions, "manifest": manifest, "tiles": tiles, "segs": segs}


def test_byte_identical_rebuild(pack, tmp_path):
    assert _build(tmp_path) == pack["files"]


def test_matches_committed_golden_pack(pack):
    golden = {p.relative_to(GOLDEN).as_posix(): p.read_bytes()
              for p in sorted(GOLDEN.rglob("*")) if p.is_file() and p.name != "README.md"}
    assert golden == pack["files"]


def test_expected_segments(pack):
    assert sorted(pack["segs"]) == [
        (1001, 0), (1001, 1), (1001, 2), (1002, 0), (1003, 0), (1006, 0), (1007, 0),
        (2001, 0), (2001, 1), (2002, 0), (2003, 0), (2004, 0), (2005, 0),
    ]
    assert pack["manifest"]["segment_count"] == 13


def test_split_at_junctions(pack):
    s = pack["segs"]
    assert s[(1001, 0)].node_ids == (111, 112)
    assert s[(1001, 1)].node_ids == (112, 113, 114)   # 113 touches only an excluded way
    assert s[(1001, 2)].node_ids == (114, 115)
    assert s[(1007, 0)].node_ids == (151, 152, 153, 154, 151)


def test_excluded_ways_absent(pack):
    ways = {w for w, _ in pack["segs"]}
    for excluded in (3001, 3002, 3003, 3004):   # service, footway, access=private, construction
        assert excluded not in ways
    assert 1005 not in ways                       # midpoint outside the province


def test_district_assignment(pack):
    s = pack["segs"]
    west = {k for k, v in s.items() if v.district_id == WEST}
    east = {k for k, v in s.items() if v.district_id == EAST}
    assert east == {(1001, 2), (1003, 0), (1007, 0), (2003, 0), (2004, 0)}
    assert len(west) == 8
    assert (2005, 0) in west          # crosses the district line, midpoint is west
    assert (1006, 0) in west          # leaves the province, midpoint is inside
    assert (1001, 1) in west          # midpoint exactly on the line: tie -> lowest id
    assert all(v.province_id == PROVINCE for v in s.values())
    assert pack["manifest"]["stats"]["district_gap_assigned"] == 1


def test_attributes(pack):
    s = pack["segs"]
    assert s[(1001, 0)].road_class == CLASS_CODE["primary"]
    assert s[(1003, 0)].oneway == 1
    assert s[(2003, 0)].oneway == 2
    assert s[(1007, 0)].oneway == 1     # roundabout implies oneway
    assert s[(2004, 0)].road_class == CLASS_CODE["living_street"]


def test_totals_add_up(pack):
    regions = {r["id"]: r for r in pack["regions"]["regions"]}
    prov, west, east = regions[PROVINCE], regions[WEST], regions[EAST]
    assert prov["level"] == 1 and west["level"] == 2 and west["parent_id"] == PROVINCE
    for layer in ("major", "local", "all"):
        assert prov["length_dm"][layer] == west["length_dm"][layer] + east["length_dm"][layer]
    segs = pack["segs"].values()
    assert prov["length_dm"]["all"] == sum(s.length_dm for s in segs)
    assert prov["length_dm"]["major"] == sum(s.length_dm for s in segs if layer_of(s.road_class) == 0)
    assert prov["segment_count"] == {"major": 5, "local": 8, "all": 13}


def test_lengths_hand_checked(pack):
    # 1002 runs 0.004 deg of longitude along 37.002 N: ~355.8 m.
    assert pack["segs"][(1002, 0)].length_dm == pytest.approx(3558, abs=2)
    # 2002 runs 0.003 deg of latitude along 35.006 E: ~332.9 m.
    assert pack["segs"][(2002, 0)].length_dm == pytest.approx(3329, abs=2)


def test_names(pack):
    regions = {r["id"]: r for r in pack["regions"]["regions"]}
    assert regions[WEST]["name"] == "Batı" and regions[WEST]["name_en"] == "West"
    assert regions[PROVINCE]["iso3166_2"] == "TR-99"


def test_segment_ids_documented_rule():
    assert segment_id(1001, 2) == 1001 * 4096 + 2
