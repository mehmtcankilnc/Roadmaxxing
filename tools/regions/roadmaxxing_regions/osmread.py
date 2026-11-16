"""Streaming reads from an OSM file (.osm.pbf or .osm XML) with pyosmium.

Three passes: admin relations, then the boundary member ways with their node
locations, then the drivable highway ways around the province.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable

import osmium

from .rules import oneway, road_class
from .segments import Way, dedupe_refs


@dataclass(frozen=True)
class AdminRelation:
    id: int
    tags: dict[str, str]
    members: tuple[tuple[int, str], ...]  # (way id, role)

    @property
    def admin_level(self) -> str:
        return self.tags.get("admin_level", "")


def replication_timestamp(path: str) -> str:
    header = osmium.io.Reader(path, osmium.osm.osm_entity_bits.NOTHING).header()
    return header.get("osmosis_replication_timestamp", "")


def read_admin_relations(path: str, levels: Iterable[str]) -> list[AdminRelation]:
    wanted = set(levels)
    out: list[AdminRelation] = []
    fp = osmium.FileProcessor(path, osmium.osm.RELATION).with_filter(
        osmium.filter.TagFilter(("boundary", "administrative")))
    for rel in fp:
        tags = {t.k: t.v for t in rel.tags}
        if tags.get("admin_level") not in wanted:
            continue
        members = tuple((m.ref, m.role) for m in rel.members if m.type == "w")
        out.append(AdminRelation(rel.id, tags, members))
    out.sort(key=lambda r: r.id)
    return out


def _located_ways(path: str, *filters):
    """Ways with node locations resolved by libosmium's C++ location index.

    The index holds every node location of the file in memory (about 16 bytes
    per node, ~1.5 GB for Turkey); Python only sees the ways that pass the
    filters, so a full country pass takes well under a minute.
    """
    fp = osmium.FileProcessor(path).with_locations("sparse_mem_array")
    fp = fp.with_filter(osmium.filter.EntityFilter(osmium.osm.WAY))
    for f in filters:
        fp = fp.with_filter(f)
    return fp


def _loc(node_ref) -> tuple[int, int] | None:
    loc = node_ref.location
    return (loc.x, loc.y) if loc.valid() else None


def read_ways_with_locations(path: str, way_ids: Iterable[int]):
    """Node refs of the given ways plus the locations of their nodes.

    Returns (way id -> refs, node id -> (lon7, lat7)). Nodes missing from the
    file are absent from the location map.
    """
    ids = set(way_ids)
    refs_out: dict[int, tuple[int, ...]] = {}
    locs: dict[int, tuple[int, int]] = {}
    if not ids:
        return refs_out, locs
    for w in _located_ways(path, osmium.filter.IdFilter(ids)):
        refs_out[w.id] = tuple(n.ref for n in w.nodes)
        for n in w.nodes:
            loc = _loc(n)
            if loc is not None:
                locs[n.ref] = loc
    return refs_out, locs


def read_drivable_ways(path: str, bbox7: tuple[int, int, int, int]):
    """Drivable highway ways with at least one node inside bbox7.

    bbox7 is (west, south, east, north) in OSM 1e-7 degree units. Returns
    (ways sorted by id, node id -> (lon7, lat7) for all their nodes).
    """
    west, south, east, north = bbox7
    ways: list[Way] = []
    locs: dict[int, tuple[int, int]] = {}
    for w in _located_ways(path, osmium.filter.KeyFilter("highway")):
        tags = {t.k: t.v for t in w.tags}
        cls = road_class(tags)
        if cls is None:
            continue
        pts = [(n.ref, _loc(n)) for n in w.nodes]
        if not any(p is not None and west <= p[0] <= east and south <= p[1] <= north for _, p in pts):
            continue
        refs = dedupe_refs([r for r, _ in pts])
        if len(refs) < 2:
            continue
        for r, p in pts:
            if p is not None:
                locs[r] = p
        ways.append(Way(w.id, refs, cls, oneway(tags)))
    ways.sort(key=lambda w: w.id)
    return ways, locs
