"""Geodesic lengths, segment midpoints and slippy-map tile math.

Coordinates inside the builder are integer OSM units: degrees * 1e7
(the native OSM precision), as (lon7, lat7) tuples.
"""
from __future__ import annotations

import math
from typing import Sequence

OSM_SCALE = 10_000_000  # degrees * 1e7

# WGS84 ellipsoid
WGS84_A = 6378137.0
WGS84_F = 1.0 / 298.257223563
WGS84_E2 = WGS84_F * (2.0 - WGS84_F)

Coord = tuple[int, int]  # (lon7, lat7)


def edge_length_m(a: Coord, b: Coord) -> float:
    """Ellipsoidal length of one straight edge in metres.

    Uses the local ellipsoid radii (meridional M and prime vertical N) at the
    mid latitude of the edge. For OSM edges (metres to a few km) the error
    against an exact geodesic (Karney) is well below 1 mm per km.
    """
    lat1 = math.radians(a[1] / OSM_SCALE)
    lat2 = math.radians(b[1] / OSM_SCALE)
    dlat = lat2 - lat1
    dlon = math.radians((b[0] - a[0]) / OSM_SCALE)
    phi = (lat1 + lat2) / 2.0
    s = math.sin(phi)
    w = 1.0 - WGS84_E2 * s * s
    m = WGS84_A * (1.0 - WGS84_E2) / (w * math.sqrt(w))
    n = WGS84_A / math.sqrt(w)
    dy = dlat * m
    dx = dlon * n * math.cos(phi)
    return math.sqrt(dx * dx + dy * dy)


def polyline_length_m(coords: Sequence[Coord]) -> float:
    total = 0.0
    for i in range(1, len(coords)):
        total += edge_length_m(coords[i - 1], coords[i])
    return total


def length_dm(coords: Sequence[Coord]) -> int:
    """Length in decimetres, rounded half to even. This is the stored value."""
    return int(round(polyline_length_m(coords) * 10.0))


def midpoint(coords: Sequence[Coord]) -> tuple[float, float]:
    """Point at half of the geodesic length along the polyline, as (lon, lat) degrees."""
    edges = [edge_length_m(coords[i - 1], coords[i]) for i in range(1, len(coords))]
    half = sum(edges) / 2.0
    acc = 0.0
    for i, e in enumerate(edges):
        if acc + e >= half and e > 0.0:
            t = (half - acc) / e
            a, b = coords[i], coords[i + 1]
            lon = (a[0] + (b[0] - a[0]) * t) / OSM_SCALE
            lat = (a[1] + (b[1] - a[1]) * t) / OSM_SCALE
            return lon, lat
        acc += e
    # Zero-length polyline: fall back to the first vertex.
    return coords[0][0] / OSM_SCALE, coords[0][1] / OSM_SCALE


def lonlat_to_tile(lon: float, lat: float, zoom: int) -> tuple[int, int]:
    """Standard Web Mercator (XYZ / slippy map) tile containing the point."""
    n = 1 << zoom
    x = int(math.floor((lon + 180.0) / 360.0 * n))
    lat_r = math.radians(lat)
    y = int(math.floor((1.0 - math.asinh(math.tan(lat_r)) / math.pi) / 2.0 * n))
    return min(max(x, 0), n - 1), min(max(y, 0), n - 1)


def tile_bounds(x: int, y: int, zoom: int) -> tuple[float, float, float, float]:
    """(west, south, east, north) of an XYZ tile in degrees."""
    n = 1 << zoom
    west = x / n * 360.0 - 180.0
    east = (x + 1) / n * 360.0 - 180.0
    north = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * y / n))))
    south = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * (y + 1) / n))))
    return west, south, east, north
