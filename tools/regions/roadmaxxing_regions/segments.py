"""Split drivable ways into segments at junction nodes.

Pure functions, no OSM I/O, so they can be tested with tiny hand-made inputs.
"""
from __future__ import annotations

from collections import Counter
from dataclasses import dataclass
from typing import Iterable, Mapping, Sequence

# Segment id = way_id * SEGMENT_ID_FACTOR + ordinal. OSM ways have at most
# 2000 nodes, so at most 1999 segments per way; 4096 leaves headroom.
SEGMENT_ID_FACTOR = 4096


@dataclass(frozen=True)
class Way:
    id: int
    refs: tuple[int, ...]
    road_class: int
    oneway: int


@dataclass(frozen=True)
class Segment:
    way_id: int
    ordinal: int            # position of the segment inside its way, from 0
    node_ids: tuple[int, ...]
    road_class: int
    oneway: int

    @property
    def id(self) -> int:
        return segment_id(self.way_id, self.ordinal)


def segment_id(way_id: int, ordinal: int) -> int:
    if not 0 <= ordinal < SEGMENT_ID_FACTOR:
        raise ValueError(f"ordinal {ordinal} out of range for way {way_id}")
    return way_id * SEGMENT_ID_FACTOR + ordinal


def dedupe_refs(refs: Sequence[int]) -> tuple[int, ...]:
    """Drop consecutive duplicate node refs (zero-length edges)."""
    out: list[int] = []
    for r in refs:
        if not out or out[-1] != r:
            out.append(r)
    return tuple(out)


def junction_nodes(ways: Iterable[Way]) -> frozenset[int]:
    """Nodes where a segment must end.

    A node is a junction when it occurs more than once over all drivable ways
    (two ways meeting, or one way touching itself), or when it is the first or
    last node of a way. Only drivable ways are considered, so service roads,
    footways etc. do not split counted roads.
    """
    counts: Counter[int] = Counter()
    ends: set[int] = set()
    for w in ways:
        counts.update(w.refs)
        ends.add(w.refs[0])
        ends.add(w.refs[-1])
    return frozenset(ends | {n for n, c in counts.items() if c > 1})


def split_way(way: Way, junctions: frozenset[int] | set[int]) -> list[Segment]:
    """Cut a way at every interior junction node.

    Ordinals count every piece of the way in node order, independent of which
    pieces end up inside a region, so ids do not depend on boundaries.
    """
    refs = way.refs
    if len(refs) < 2:
        return []
    out: list[Segment] = []
    start = 0
    for i in range(1, len(refs)):
        if i == len(refs) - 1 or refs[i] in junctions:
            out.append(Segment(way.id, len(out), tuple(refs[start:i + 1]), way.road_class, way.oneway))
            start = i
    return out


def split_ways(ways: Sequence[Way]) -> list[Segment]:
    """Split all ways; result is sorted by segment id."""
    junctions = junction_nodes(ways)
    segs: list[Segment] = []
    for w in sorted(ways, key=lambda w: w.id):
        segs.extend(split_way(w, junctions))
    return segs


def resolve(node_ids: Sequence[int], locations: Mapping[int, tuple[int, int]]):
    """Coordinates for node ids, or None if any node has no location."""
    out = []
    for n in node_ids:
        loc = locations.get(n)
        if loc is None:
            return None
        out.append(loc)
    return out
