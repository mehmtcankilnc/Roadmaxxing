import random

import pytest

from gpxlib import profile
from gpxlib.geo import haversine_m, offset_m
from gpxlib.profile import KMH, Edge, Stop


def straight_line(length_m: float, step_m: float = 50.0):
    origin = (37.0, 35.3)
    n = int(length_m / step_m)
    return [offset_m(origin, 0.0, i * step_m) for i in range(n + 1)]


def l_shape(leg_m: float = 600.0):
    origin = (37.0, 35.3)
    pts = [offset_m(origin, 0.0, i * 50.0) for i in range(int(leg_m / 50) + 1)]
    corner = pts[-1]
    pts += [offset_m(corner, i * 50.0, 0.0) for i in range(1, int(leg_m / 50) + 1)]
    return pts


def edges(points, limit_kmh=50.0, factor=0.9):
    return [Edge(limit_kmh * KMH, limit_kmh * factor * KMH) for _ in points[1:]]


def run(points, eds, dyn=profile.DRIVE, stops=(), zones=()):
    prof = profile.build_profile(points, eds, dyn, stops, zones)
    return prof, profile.sample_1hz(prof)


def test_samples_are_exactly_one_second_apart():
    pts = straight_line(2000)
    _, samples = run(pts, edges(pts))
    assert [s.t for s in samples] == list(range(len(samples)))


def test_starts_and_ends_at_rest_at_route_ends():
    pts = straight_line(1500)
    _, samples = run(pts, edges(pts))
    assert samples[0].speed == 0.0
    assert samples[-1].speed == 0.0
    assert haversine_m((samples[0].lat, samples[0].lon), pts[0]) < 0.5
    assert haversine_m((samples[-1].lat, samples[-1].lon), pts[-1]) < 0.5


@pytest.mark.parametrize("limit", [30.0, 50.0, 90.0, 120.0])
def test_speed_never_exceeds_limit_even_with_high_cruise_factor(limit):
    pts = straight_line(5000)
    eds = edges(pts, limit, factor=1.2)  # a cruise target above the limit must be capped
    prof, samples = run(pts, eds)
    assert max(s.speed for s in samples) <= limit * KMH * profile.MAX_CRUISE_FACTOR + 1e-6
    assert all(v <= lim + 1e-9 for v, lim in zip(prof.grid_v, prof.grid_limit))


def test_acceleration_and_deceleration_are_bounded():
    pts = l_shape()
    _, samples = run(pts, edges(pts, 70.0))
    dv = [b.speed - a.speed for a, b in zip(samples, samples[1:])]
    assert max(dv) <= profile.DRIVE.accel + 0.25
    assert min(dv) >= -(profile.DRIVE.decel + 0.25)


def test_slows_down_for_a_right_angle_turn():
    pts = l_shape()
    prof, samples = run(pts, edges(pts, 70.0))
    corner = pts[len(pts) // 2]
    near = [s.speed for s in samples if haversine_m((s.lat, s.lon), corner) < 8]
    assert near and max(near) < 25 * KMH
    assert max(s.speed for s in samples) > 50 * KMH


def test_planned_stop_holds_position_for_its_duration():
    pts = straight_line(2000)
    _, samples = run(pts, edges(pts), stops=[Stop(1000.0, 240)])
    zero = [s for s in samples[1:-1] if s.speed == 0.0]
    assert 238 <= len(zero) <= 242
    first = (zero[0].lat, zero[0].lon)
    assert all(haversine_m(first, (s.lat, s.lon)) < 0.5 for s in zero)


def test_slow_zone_caps_speed():
    pts = straight_line(3000)
    prof, _ = run(pts, edges(pts, 90.0), zones=[(1000.0, 1500.0, 15 * KMH)])
    zone = [v for s, v in zip(prof.grid_s, prof.grid_v) if 1000 <= s <= 1500]
    assert max(zone) <= 15 * KMH + 1e-9


def test_walk_profile_stays_at_walking_pace():
    pts = l_shape(300)
    eds = [Edge(5.75 * KMH, 5 * KMH) for _ in pts[1:]]
    _, samples = run(pts, eds, dyn=profile.WALK)
    assert max(s.speed for s in samples) <= 5.75 * KMH


def test_speed_limit_defaults_and_tags():
    assert profile.speed_limit_kmh({"highway": "residential"}, urban=True) == (50.0, "default")
    assert profile.speed_limit_kmh({"highway": "trunk", "oneway": "yes"}, urban=False) == (110.0, "default")
    assert profile.speed_limit_kmh({"highway": "primary", "maxspeed": "70"}, urban=True) == (70.0, "tag")
    assert profile.parse_maxspeed("30 mph") == pytest.approx(48.28, abs=0.01)
    assert profile.parse_maxspeed("signals") is None


def test_cruise_factor_is_below_one_for_driving():
    rng = random.Random(1)
    for hw in ("primary", "residential", "motorway", None):
        for _ in range(200):
            assert profile.cruise_factor(hw, "drive", rng) < 1.0
