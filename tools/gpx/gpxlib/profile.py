"""Speed profile and 1 Hz resampling.

The profile is computed on a 1 m distance grid:
  1. speed cap per grid point = min(cruise speed of the way, curve speed)
     - cruise speed is always BELOW the legal limit (factor < 1, hard cap 0.97)
     - curve speed = sqrt(lateral_accel * turn radius), so junction turns slow down
  2. forced zero speed at start, end and planned stops
  3. forward pass limits acceleration, backward pass limits deceleration
Then the distance-domain profile is integrated to time and sampled every second.
"""
from __future__ import annotations

import bisect
import math
import random
from dataclasses import dataclass, field
from typing import Sequence

from .geo import LatLon, bearing_deg, circumradius_m, cumulative_distances, interpolate

KMH = 1 / 3.6

# Turkish general limits for cars (km/h) when the way has no maxspeed tag.
URBAN_DEFAULT_KMH = {"motorway": 120, "motorway_link": 50, "trunk_link": 40, "primary_link": 40,
                     "secondary_link": 40, "tertiary_link": 40}
URBAN_FALLBACK_KMH = 50
RURAL_DEFAULT_KMH = {"motorway": 120, "motorway_link": 60, "trunk": 90, "trunk_link": 50,
                     "primary": 90, "primary_link": 50, "secondary": 90, "secondary_link": 50,
                     "tertiary": 70, "tertiary_link": 50, "unclassified": 50,
                     "residential": 50, "living_street": 20, "service": 20}
RESIDENTIAL_CLASSES = {"residential", "unclassified", "living_street", "service"}
MAX_CRUISE_FACTOR = 0.97


@dataclass(frozen=True)
class Dynamics:
    accel: float  # m/s^2
    decel: float  # m/s^2 (positive number)
    lateral: float  # m/s^2 comfortable lateral acceleration in curves
    min_curve_speed: float  # m/s


DRIVE = Dynamics(accel=1.4, decel=2.0, lateral=2.0, min_curve_speed=2.5)
WALK = Dynamics(accel=0.5, decel=0.7, lateral=50.0, min_curve_speed=0.8)
TRAIN = Dynamics(accel=0.5, decel=0.7, lateral=0.8, min_curve_speed=8.0)


def parse_maxspeed(tag: str | None) -> float | None:
    if not tag:
        return None
    tag = tag.strip().lower()
    try:
        if tag.endswith("mph"):
            return float(tag[:-3].strip()) * 1.609344
        return float(tag.split(";")[0].split()[0])
    except ValueError:
        return None


def speed_limit_kmh(tags: dict, urban: bool) -> tuple[float, str]:
    """Legal limit for a car on this way and where it came from ('tag' or 'default')."""
    tagged = parse_maxspeed(tags.get("maxspeed"))
    if tagged:
        return tagged, "tag"
    hw = tags.get("highway", "")
    if urban:
        return float(URBAN_DEFAULT_KMH.get(hw, URBAN_FALLBACK_KMH)), "default"
    if hw == "trunk" and tags.get("oneway") == "yes":
        return 110.0, "default"  # divided intercity road
    return float(RURAL_DEFAULT_KMH.get(hw, 50)), "default"


@dataclass
class Edge:
    """Constant-property stretch of the route between two consecutive nodes."""
    limit_ms: float  # legal limit, m/s
    cruise_ms: float  # target speed, m/s (< limit)


@dataclass
class Stop:
    at_m: float
    duration_s: float


@dataclass
class Sample:
    t: int
    lat: float
    lon: float
    speed: float  # m/s
    course: float  # degrees


@dataclass
class Profile:
    points: list[LatLon]
    cum: list[float]
    grid_v: list[float]
    grid_cap: list[float]
    grid_limit: list[float]
    stops: list[Stop] = field(default_factory=list)

    @property
    def length_m(self) -> float:
        return self.cum[-1]


def cruise_factor(highway: str | None, mode: str, rng: random.Random) -> float:
    if mode != "drive":
        return rng.uniform(0.92, 1.0)
    if highway in RESIDENTIAL_CLASSES:
        return rng.uniform(0.60, 0.75)
    return rng.uniform(0.86, 0.95)


def build_profile(points: Sequence[LatLon], edges: Sequence[Edge], dyn: Dynamics,
                  stops: Sequence[Stop] = (), slow_zones: Sequence[tuple[float, float, float]] = ()
                  ) -> Profile:
    """points: node coordinates; edges[i] covers points[i]..points[i+1].

    slow_zones: (from_m, to_m, cap_ms) stretches with an extra speed cap (traffic).
    """
    if len(points) < 2 or len(edges) != len(points) - 1:
        raise ValueError("need >= 2 points and one edge per segment")
    cum = cumulative_distances(points)
    total = cum[-1]
    n = int(math.floor(total)) + 1
    grid_s = [float(i) for i in range(n)]
    if grid_s[-1] < total:
        grid_s.append(total)
    m = len(grid_s)

    cap = [0.0] * m
    limit = [0.0] * m
    for i, s in enumerate(grid_s):
        k = min(bisect.bisect_right(cum, s) - 1, len(edges) - 1)
        cap[i] = min(edges[k].cruise_ms, edges[k].limit_ms * MAX_CRUISE_FACTOR)
        limit[i] = edges[k].limit_ms

    # curve speed, measured over +-15 m so node spacing does not matter
    for i in range(0, m, 2):
        s = grid_s[i]
        if s < 1 or s > total - 1:
            continue
        a = position_at(points, cum, max(0.0, s - 15))
        b = position_at(points, cum, s)
        c = position_at(points, cum, min(total, s + 15))
        r = circumradius_m(a, b, c)
        if math.isfinite(r):
            v = max(dyn.min_curve_speed, math.sqrt(dyn.lateral * r))
            for j in (i, i + 1):
                if j < m:
                    cap[j] = min(cap[j], v)

    for lo, hi, v in slow_zones:
        for i in range(bisect.bisect_left(grid_s, lo), bisect.bisect_right(grid_s, hi)):
            cap[i] = min(cap[i], v)

    zero = {0, m - 1}
    stop_idx: list[Stop] = []
    for st in stops:
        i = min(max(bisect.bisect_left(grid_s, st.at_m), 1), m - 2)
        zero.add(i)
        stop_idx.append(Stop(grid_s[i], st.duration_s))
    for i in zero:
        cap[i] = 0.0

    v = cap[:]
    for i in range(1, m):
        ds = grid_s[i] - grid_s[i - 1]
        v[i] = min(v[i], math.sqrt(v[i - 1] ** 2 + 2 * dyn.accel * ds))
    for i in range(m - 2, -1, -1):
        ds = grid_s[i + 1] - grid_s[i]
        v[i] = min(v[i], math.sqrt(v[i + 1] ** 2 + 2 * dyn.decel * ds))

    prof = Profile(list(points), cum, v, cap, limit, sorted(stop_idx, key=lambda s: s.at_m))
    prof.grid_s = grid_s  # type: ignore[attr-defined]
    return prof


def position_at(points: Sequence[LatLon], cum: Sequence[float], s: float) -> LatLon:
    if s <= 0:
        return points[0]
    if s >= cum[-1]:
        return points[-1]
    k = bisect.bisect_right(cum, s) - 1
    seg = cum[k + 1] - cum[k]
    t = 0.0 if seg == 0 else (s - cum[k]) / seg
    return interpolate(points[k], points[k + 1], t)


def course_at(points: Sequence[LatLon], cum: Sequence[float], s: float) -> float:
    a = position_at(points, cum, max(0.0, s - 5))
    b = position_at(points, cum, min(cum[-1], s + 5))
    return bearing_deg(a, b)


def sample_1hz(prof: Profile) -> list[Sample]:
    """Integrate the distance-domain profile and sample it every second."""
    grid_s: list[float] = prof.grid_s  # type: ignore[attr-defined]
    v = prof.grid_v
    dwell = {st.at_m: st.duration_s for st in prof.stops}
    ts: list[float] = [0.0]
    ss: list[float] = [grid_s[0]]
    vs: list[float] = [v[0]]
    t = 0.0
    for i in range(1, len(grid_s)):
        ds = grid_s[i] - grid_s[i - 1]
        vavg = (v[i] + v[i - 1]) / 2
        if vavg <= 1e-6:
            vavg = 1e-3
        t += ds / vavg
        ts.append(t)
        ss.append(grid_s[i])
        vs.append(v[i])
        if grid_s[i] in dwell and i != len(grid_s) - 1:
            t += dwell[grid_s[i]]
            ts.append(t)
            ss.append(grid_s[i])
            vs.append(0.0)
    total_t = ts[-1]
    n = int(math.ceil(total_t - 1e-9))
    out: list[Sample] = []
    last_course = course_at(prof.points, prof.cum, 0.0)
    for sec in range(n + 1):
        tt = min(float(sec), total_t)
        j = bisect.bisect_right(ts, tt) - 1
        j = min(max(j, 0), len(ts) - 2)
        span = ts[j + 1] - ts[j]
        f = 0.0 if span <= 0 else (tt - ts[j]) / span
        s = ss[j] + (ss[j + 1] - ss[j]) * f
        speed = vs[j] + (vs[j + 1] - vs[j]) * f
        if speed > 0.5:
            last_course = course_at(prof.points, prof.cum, s)
        lat, lon = position_at(prof.points, prof.cum, s)
        out.append(Sample(sec, lat, lon, max(0.0, speed), last_course))
    out[-1].speed = 0.0
    return out
