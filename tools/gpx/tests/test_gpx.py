import filecmp
import json
import os
import random
import xml.etree.ElementTree as ET

import pytest

from gpxlib import gpxio, noise, profile
from gpxlib.geo import haversine_m, offset_m
from gpxlib.profile import KMH, Edge, Sample

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA = os.path.join(os.path.dirname(os.path.dirname(HERE)), "test-data", "gpx")
OSM_CACHE = os.path.join(HERE, ".cache", "adana-osm.json.gz")
NS = {"g": gpxio.GPX_NS, "x": gpxio.TPX_NS}
TRKPT_ORDER = ["ele", "time", "magvar", "geoidheight", "name", "cmt", "desc", "src", "link",
               "sym", "type", "fix", "sat", "hdop", "vdop", "pdop", "ageofdgpsdata", "dgpsid",
               "extensions"]

DATA_FILES = sorted(f for f in os.listdir(DATA) if f.endswith(".gpx")) if os.path.isdir(DATA) else []
EXPECTED_FILES = sorted(f for f in os.listdir(DATA) if f.endswith(".expected.json")) \
    if os.path.isdir(DATA) else []


def synthetic_samples(n=600):
    origin = (37.0, 35.3)
    pts = [offset_m(origin, 0.0, i * 50.0) for i in range(61)]
    prof = profile.build_profile(pts, [Edge(50 * KMH, 45 * KMH)] * 60, profile.DRIVE)
    return profile.sample_1hz(prof)


def check_gpx_structure(path):
    root = ET.parse(path).getroot()
    assert root.tag == f"{{{gpxio.GPX_NS}}}gpx"
    assert root.get("version") == "1.1"
    assert root.get("creator")
    children = [c.tag.split("}")[1] for c in root]
    assert children[0] == "metadata" and "trk" in children
    pts = root.findall("g:trk/g:trkseg/g:trkpt", NS)
    assert len(pts) >= 2
    for pt in pts:
        lat, lon = float(pt.get("lat")), float(pt.get("lon"))
        assert -90 <= lat <= 90 and -180 <= lon < 180
        order = [TRKPT_ORDER.index(c.tag.split("}")[1]) for c in pt]
        assert order == sorted(order), "trkpt children out of GPX 1.1 sequence order"
        assert pt.find("g:time", NS) is not None
        speed = pt.find("g:extensions/x:TrackPointExtension/x:speed", NS)
        course = pt.find("g:extensions/x:TrackPointExtension/x:course", NS)
        assert float(speed.text) >= 0
        assert 0 <= float(course.text) < 360
    return pts


def test_write_read_roundtrip(tmp_path):
    samples = synthetic_samples()
    start = gpxio.parse_iso("2026-11-02T08:00:00Z")
    path = tmp_path / "t.gpx"
    gpxio.write_gpx(str(path), samples, start, "t", "synthetic")
    check_gpx_structure(str(path))
    back = gpxio.read_gpx(str(path))
    assert len(back) == len(samples)
    for s, b in zip(samples, back):
        assert (b.time - start).total_seconds() == s.t
        assert abs(b.lat - s.lat) < 1e-6 and abs(b.lon - s.lon) < 1e-6
        assert abs(b.speed - s.speed) <= 0.005


def test_noise_is_bounded_correlated_and_has_short_dropouts():
    samples = synthetic_samples()
    noisy = noise.add_noise(samples, random.Random(7))
    by_t = {s.t: s for s in samples}
    errs = [haversine_m((n.lat, n.lon), (by_t[n.t].lat, by_t[n.t].lon)) for n in noisy]
    rms = (sum(e * e for e in errs) / len(errs)) ** 0.5
    assert 3.0 < rms < 15.0
    assert max(errs) < 40.0
    # neighbouring errors are correlated: the step between fixes is much smaller than the error
    steps = [abs(a - b) for a, b in zip(errs, errs[1:])]
    assert sum(steps) / len(steps) < rms / 2
    gaps = [b.t - a.t for a, b in zip(noisy, noisy[1:])]
    assert all(g == 1 or 3 <= g <= 6 for g in gaps)  # a 2-5 s dropout is a 3-6 s gap
    assert noisy[0].t == samples[0].t and noisy[-1].t == samples[-1].t


def test_noise_is_deterministic():
    samples = synthetic_samples()
    a = noise.add_noise(samples, random.Random("x"))
    b = noise.add_noise(samples, random.Random("x"))
    assert a == b


@pytest.mark.parametrize("name", DATA_FILES)
def test_data_file_is_valid_gpx_at_1hz(name):
    path = os.path.join(DATA, name)
    check_gpx_structure(path)
    pts = gpxio.read_gpx(path)
    gaps = [(b.time - a.time).total_seconds() for a, b in zip(pts, pts[1:])]
    if name.endswith(".noisy.gpx"):
        assert all(g == 1 or 3 <= g <= 6 for g in gaps)
    else:
        assert all(g == 1 for g in gaps)


@pytest.mark.parametrize("name", [f for f in DATA_FILES if not f.endswith(".noisy.gpx")])
def test_data_file_speeds_and_accelerations_are_realistic(name):
    pts = gpxio.read_gpx(os.path.join(DATA, name))
    exp = json.load(open(os.path.join(DATA, name.replace(".gpx", ".expected.json")), encoding="utf-8"))
    limit = exp["speed_kmh"]["max_limit_on_route"] / 3.6
    speeds = [p.speed for p in pts]
    assert max(speeds) <= limit + 1e-6
    dv = [b - a for a, b in zip(speeds, speeds[1:])]
    assert max(dv) <= 2.0 and min(dv) >= -2.5
    # the speed field agrees with the distance covered (the straight chord between two
    # fixes may be shorter than the path through a sharp junction turn, never longer)
    for a, b in zip(pts, pts[1:]):
        d = haversine_m((a.lat, a.lon), (b.lat, b.lon))
        travelled = (a.speed + b.speed) / 2
        assert d <= travelled + 0.6
        assert d >= 0.6 * travelled - 0.6
    if exp["mode"] == "walk":
        assert max(speeds) < 7 / 3.6


@pytest.mark.parametrize("name", EXPECTED_FILES)
def test_expected_json_fields(name):
    exp = json.load(open(os.path.join(DATA, name), encoding="utf-8"))
    for key in ("scenario", "expected", "osm_node_ids", "osm_way_ids", "distance_m", "duration_s", "data"):
        assert key in exp
    assert exp["expected"]["result"] in ("drive", "not_drive")
    assert isinstance(exp["expected"]["drive_count"], int)
    assert len(exp["osm_node_ids"]) >= 2 and all(isinstance(n, int) for n in exp["osm_node_ids"])
    assert exp["osm_way_ids"]
    assert exp["data"]["osm_timestamp"]
    for f in exp["files"].values():
        assert os.path.exists(os.path.join(DATA, f))
    if exp["name"].startswith("not-a-drive"):
        assert exp["expected"]["result"] == "not_drive"


def test_all_six_scenarios_present():
    names = {f.split(".")[0] for f in EXPECTED_FILES}
    for required in ("main-roads", "residential", "parallel-roads", "traffic-stop", "intercity",
                     "not-a-drive"):
        assert required in names


@pytest.mark.skipif(not os.path.exists(OSM_CACHE), reason="OSM extract cache not present")
def test_generation_is_deterministic(tmp_path):
    import generate
    out1, out2 = tmp_path / "a", tmp_path / "b"
    for out in (out1, out2):
        generate.main(["--offline", "--out", str(out)])
    names = sorted(os.listdir(out1))
    assert names
    match, mismatch, errors = filecmp.cmpfiles(out1, out2, names, shallow=False)
    assert not mismatch and not errors
    # and the committed files are what the generator produces today
    match, mismatch, errors = filecmp.cmpfiles(out1, DATA, names, shallow=False)
    assert not mismatch and not errors, mismatch
