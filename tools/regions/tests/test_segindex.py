"""segments.bin (segment summary index): format, stability, consistency with the tiles."""
import json
from pathlib import Path

import pytest

from roadmaxxing_regions import build
from roadmaxxing_regions.segindex import MAGIC, SegmentSummary, decode_index, encode_index
from roadmaxxing_regions.tileformat import decode_tile

ROOT = Path(__file__).resolve().parent.parent.parent.parent / "test-data" / "regions"
FIXTURE_OSM = Path(__file__).resolve().parent / "data" / "fixture.osm"


def _tile_summaries(pack: Path) -> list[SegmentSummary]:
    manifest = json.loads((pack / "manifest.json").read_text(encoding="utf-8"))
    z = manifest["tile_zoom"]
    seen: dict[int, SegmentSummary] = {}
    for x, y, _count, _size in manifest["tiles"]:
        tile = decode_tile((pack / "tiles" / str(z) / str(x) / f"{y}.bin").read_bytes())
        for s in tile.segments:
            sid = s.way_id * 4096 + s.ordinal
            seen[sid] = SegmentSummary(sid, s.length_dm, s.road_class, s.province_id, s.district_id)
    return [seen[k] for k in sorted(seen)]


def test_roundtrip_and_layout():
    rows = [SegmentSummary(5 * 4096 + 1, 100, 4, 1, 12), SegmentSummary(5 * 4096, 7, 10, 1, 11),
            SegmentSummary(900 * 4096, 123456, 0, 1, 11)]
    data = encode_index(rows)
    assert data[:4] == MAGIC and data[4] == 1
    assert decode_index(data) == sorted(rows, key=lambda r: r.segment_id)
    # region table: 3 ids ascending (1, 11, 12), then count 3
    assert data[5:10] == bytes([3, 1, 11, 12, 3])


def test_rejects_garbage():
    good = encode_index([SegmentSummary(4096, 1, 4, 1, 11)])
    with pytest.raises(ValueError):
        decode_index(b"XXXX" + good[4:])
    with pytest.raises(ValueError):
        decode_index(good + b"\0")
    with pytest.raises(ValueError):
        encode_index([SegmentSummary(1, 1, 4, 1, 11), SegmentSummary(1, 2, 4, 1, 11)])


def test_fixture_build_is_stable_and_matches_tiles(tmp_path):
    res = build.extract(str(FIXTURE_OSM), "Testil", log=lambda _m: None)
    source = {"file": "fixture.osm", "url": None, "md5": build.file_md5(str(FIXTURE_OSM)),
              "osm_replication_timestamp": ""}
    build.write_pack(res, tmp_path / "a", "fixture", source)
    build.write_pack(res, tmp_path / "b", "fixture", source)
    a = (tmp_path / "a" / "segments.bin").read_bytes()
    assert a == (tmp_path / "b" / "segments.bin").read_bytes()
    manifest = json.loads((tmp_path / "a" / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["segment_index"] == {"path": "segments.bin", "bytes": len(a)}
    assert decode_index(a) == _tile_summaries(tmp_path / "a")
    assert len(decode_index(a)) == manifest["segment_count"]


@pytest.mark.parametrize("name", ["fixture", "adana"])
def test_committed_index_matches_tiles(name):
    pack = ROOT / name
    if not (pack / "manifest.json").exists():
        pytest.skip(f"{name} pack not built")
    manifest = json.loads((pack / "manifest.json").read_text(encoding="utf-8"))
    data = (pack / "segments.bin").read_bytes()
    assert manifest["segment_index"] == {"path": "segments.bin", "bytes": len(data)}
    rows = decode_index(data)
    assert len(rows) == manifest["segment_count"]
    assert rows == _tile_summaries(pack)
    assert all(a.segment_id < b.segment_id for a, b in zip(rows, rows[1:]))
