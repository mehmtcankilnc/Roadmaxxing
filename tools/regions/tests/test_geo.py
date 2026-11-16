import math

import pytest

from roadmaxxing_regions.geo import (
    edge_length_m, length_dm, lonlat_to_tile, midpoint, polyline_length_m, tile_bounds,
)


def c(lon, lat):
    return (round(lon * 1e7), round(lat * 1e7))


def vincenty_m(lon1, lat1, lon2, lat2):
    """Independent reference: Vincenty's inverse formula on WGS84 (mm accuracy)."""
    a, f = 6378137.0, 1 / 298.257223563
    b = a * (1 - f)
    L = math.radians(lon2 - lon1)
    U1 = math.atan((1 - f) * math.tan(math.radians(lat1)))
    U2 = math.atan((1 - f) * math.tan(math.radians(lat2)))
    sU1, cU1, sU2, cU2 = math.sin(U1), math.cos(U1), math.sin(U2), math.cos(U2)
    lam = L
    for _ in range(200):
        sl, cl = math.sin(lam), math.cos(lam)
        ss = math.hypot(cU2 * sl, cU1 * sU2 - sU1 * cU2 * cl)
        cs = sU1 * sU2 + cU1 * cU2 * cl
        sig = math.atan2(ss, cs)
        sa = cU1 * cU2 * sl / ss
        c2a = 1 - sa * sa
        c2sm = cs - 2 * sU1 * sU2 / c2a
        C = f / 16 * c2a * (4 + f * (4 - 3 * c2a))
        prev = lam
        lam = L + (1 - C) * f * sa * (sig + C * ss * (c2sm + C * cs * (-1 + 2 * c2sm ** 2)))
        if abs(lam - prev) < 1e-13:
            break
    u2 = c2a * (a * a - b * b) / (b * b)
    A = 1 + u2 / 16384 * (4096 + u2 * (-768 + u2 * (320 - 175 * u2)))
    B = u2 / 1024 * (256 + u2 * (-128 + u2 * (74 - 47 * u2)))
    ds = B * ss * (c2sm + B / 4 * (cs * (-1 + 2 * c2sm ** 2)
                                   - B / 6 * c2sm * (-3 + 4 * ss ** 2) * (-3 + 4 * c2sm ** 2)))
    return b * A * (sig - ds)


@pytest.mark.parametrize("p,q", [
    ((35.0, 36.995), (35.0, 37.005)),       # meridian, ~1.1 km
    ((34.995, 37.0), (35.005, 37.0)),       # parallel, ~0.9 km
    ((35.3, 37.0), (35.33, 37.02)),         # diagonal, ~3.4 km
    ((35.3, 37.0), (35.30005, 37.00003)),   # ~5 m edge
])
def test_edge_length_matches_vincenty(p, q):
    ref = vincenty_m(p[0], p[1], q[0], q[1])
    got = edge_length_m(c(*p), c(*q))
    assert got == pytest.approx(ref, rel=1e-6, abs=1e-3)


def test_symmetric_and_zero():
    a, b = c(35.1, 37.0), c(35.13, 37.02)
    assert edge_length_m(a, b) == pytest.approx(edge_length_m(b, a))
    assert edge_length_m(a, a) == 0.0


def test_polyline_is_sum_of_edges():
    pts = [c(35.0, 37.0), c(35.001, 37.0), c(35.001, 37.001)]
    assert polyline_length_m(pts) == pytest.approx(
        edge_length_m(pts[0], pts[1]) + edge_length_m(pts[1], pts[2]))


def test_length_dm_rounding():
    pts = [c(35.0, 37.0), c(35.004, 37.0)]
    m = polyline_length_m(pts)
    assert length_dm(pts) == round(m * 10)
    assert abs(length_dm(pts) / 10 - m) <= 0.05


def test_midpoint_on_bent_line():
    # L shape with equal legs: midpoint is the corner.
    pts = [c(35.0, 37.0), c(35.0, 37.001), c(35.0 + 0.001 * 1109.6 / 889.6, 37.001)]
    lon, lat = midpoint(pts)
    assert lat == pytest.approx(37.001, abs=2e-6)
    assert lon == pytest.approx(35.0, abs=2e-5)


def test_tiles():
    # Adana city centre at z13
    x, y = lonlat_to_tile(35.3213, 36.9914, 13)
    w, s, e, n = tile_bounds(x, y, 13)
    assert w <= 35.3213 < e and s <= 36.9914 < n
    assert math.isclose(e - w, 360 / 8192)
