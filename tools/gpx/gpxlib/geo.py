"""Small geodesy helpers (spherical earth, good enough at city scale)."""
from __future__ import annotations

import math
from typing import Sequence

EARTH_RADIUS_M = 6371008.8

LatLon = tuple[float, float]


def haversine_m(a: LatLon, b: LatLon) -> float:
    lat1, lon1 = map(math.radians, a)
    lat2, lon2 = map(math.radians, b)
    dlat = lat2 - lat1
    dlon = lon2 - lon1
    h = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
    return 2 * EARTH_RADIUS_M * math.asin(min(1.0, math.sqrt(h)))


def bearing_deg(a: LatLon, b: LatLon) -> float:
    lat1, lon1 = map(math.radians, a)
    lat2, lon2 = map(math.radians, b)
    dlon = lon2 - lon1
    x = math.sin(dlon) * math.cos(lat2)
    y = math.cos(lat1) * math.sin(lat2) - math.sin(lat1) * math.cos(lat2) * math.cos(dlon)
    return (math.degrees(math.atan2(x, y)) + 360.0) % 360.0


def angle_diff_deg(a: float, b: float) -> float:
    """Absolute smallest difference between two headings, 0..180."""
    d = abs(a - b) % 360.0
    return 360.0 - d if d > 180.0 else d


def offset_m(p: LatLon, east_m: float, north_m: float) -> LatLon:
    lat, lon = p
    dlat = north_m / EARTH_RADIUS_M
    dlon = east_m / (EARTH_RADIUS_M * math.cos(math.radians(lat)))
    return lat + math.degrees(dlat), lon + math.degrees(dlon)


def cumulative_distances(points: Sequence[LatLon]) -> list[float]:
    out = [0.0]
    for i in range(1, len(points)):
        out.append(out[-1] + haversine_m(points[i - 1], points[i]))
    return out


def to_local_xy(p: LatLon, origin: LatLon) -> tuple[float, float]:
    """Equirectangular projection to metres around origin."""
    x = math.radians(p[1] - origin[1]) * EARTH_RADIUS_M * math.cos(math.radians(origin[0]))
    y = math.radians(p[0] - origin[0]) * EARTH_RADIUS_M
    return x, y


def point_segment_distance_m(p: LatLon, a: LatLon, b: LatLon) -> float:
    px, py = to_local_xy(p, a)
    bx, by = to_local_xy(b, a)
    seg2 = bx * bx + by * by
    if seg2 == 0:
        return math.hypot(px, py)
    t = max(0.0, min(1.0, (px * bx + py * by) / seg2))
    return math.hypot(px - t * bx, py - t * by)


def point_polyline_distance_m(p: LatLon, line: Sequence[LatLon]) -> float:
    if len(line) == 1:
        return haversine_m(p, line[0])
    return min(point_segment_distance_m(p, line[i], line[i + 1]) for i in range(len(line) - 1))


def simplify(points: Sequence[LatLon], tolerance_m: float) -> list[LatLon]:
    """Douglas-Peucker simplification (iterative)."""
    if len(points) < 3:
        return list(points)
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        i, j = stack.pop()
        best, idx = 0.0, -1
        for k in range(i + 1, j):
            d = point_segment_distance_m(points[k], points[i], points[j])
            if d > best:
                best, idx = d, k
        if idx >= 0 and best > tolerance_m:
            keep[idx] = True
            stack.append((i, idx))
            stack.append((idx, j))
    return [p for p, k in zip(points, keep) if k]


def circumradius_m(a: LatLon, b: LatLon, c: LatLon) -> float:
    """Radius of the circle through three points; inf for collinear points."""
    ax, ay = to_local_xy(a, b)
    cx, cy = to_local_xy(c, b)
    ab = math.hypot(ax, ay)
    bc = math.hypot(cx, cy)
    ac = math.hypot(cx - ax, cy - ay)
    cross = abs(ax * cy - ay * cx)
    if cross < 1e-9 or ab == 0 or bc == 0:
        return math.inf
    return ab * bc * ac / (2.0 * cross)


def interpolate(a: LatLon, b: LatLon, t: float) -> LatLon:
    return a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t
