"""Assemble administrative boundary polygons from relation member ways."""
from __future__ import annotations

from typing import Mapping, Sequence

import shapely
from shapely.geometry import LineString, MultiPolygon, Polygon
from shapely.geometry.base import BaseGeometry

from .geo import OSM_SCALE


def _lines(member_refs: Sequence[Sequence[int]], locations) -> list[LineString] | None:
    lines = []
    for refs in member_refs:
        pts = []
        for r in refs:
            loc = locations.get(r)
            if loc is None:
                return None
            pts.append((loc[0] / OSM_SCALE, loc[1] / OSM_SCALE))
        if len(pts) >= 2:
            lines.append(LineString(pts))
    return lines


def _faces(lines: list[LineString]) -> BaseGeometry | None:
    if not lines:
        return None
    merged = shapely.union_all(lines)  # nodes the lines so polygonize sees every ring
    faces = shapely.polygonize([merged])
    polys = [g for g in shapely.get_parts(faces) if not g.is_empty]
    if not polys:
        return None
    return shapely.union_all(polys)


def assemble_polygon(members: Sequence[tuple[int, str]],
                     way_refs: Mapping[int, Sequence[int]],
                     locations) -> Polygon | MultiPolygon | None:
    """Polygon for a boundary relation, or None if it cannot be closed.

    Outer rings come from members with role 'outer' (or empty role), holes from
    role 'inner'. Missing member ways or node locations give None.
    """
    outer_refs, inner_refs = [], []
    for way_id, role in members:
        refs = way_refs.get(way_id)
        if refs is None:
            return None
        (inner_refs if role == "inner" else outer_refs).append(refs)
    outer_lines = _lines(outer_refs, locations)
    inner_lines = _lines(inner_refs, locations)
    if outer_lines is None or inner_lines is None:
        return None
    outer = _faces(outer_lines)
    if outer is None:
        return None
    inner = _faces(inner_lines)
    geom = outer.difference(inner) if inner is not None else outer
    geom = shapely.make_valid(geom)
    polys = [g for g in shapely.get_parts(geom) if isinstance(g, Polygon) and not g.is_empty]
    if not polys:
        return None
    return polys[0] if len(polys) == 1 else MultiPolygon(polys)


def simplified_rings(geom: Polygon | MultiPolygon, tolerance_deg: float, decimals: int = 5):
    """Simplified boundary as nested lists: polygons -> rings -> [lon, lat] pairs.

    The first ring of each polygon is the outer ring, the rest are holes.
    Coordinates are rounded to `decimals`; output is deterministic.
    """
    simple = shapely.simplify(geom, tolerance_deg, preserve_topology=True)
    out = []
    for poly in sorted(shapely.get_parts(simple), key=lambda p: -p.area):
        if poly.is_empty:
            continue
        rings = [poly.exterior] + list(poly.interiors)
        out.append([[[round(x, decimals), round(y, decimals)] for x, y in r.coords] for r in rings])
    return out
