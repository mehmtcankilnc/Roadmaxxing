"""OSM network cache (written by extract_osm.py) and node/way resolution.

The OSRM demo server prints node ids with only 10 significant digits, so ids
above 1e10 come back rounded (off by up to +-5). We recover the exact id from
the OSM extract: candidates in the id window, preferring one that is adjacent
(shares a way edge) with the previous node, then the one nearest to the OSRM
geometry.
"""
from __future__ import annotations

import bisect
import gzip
import heapq
import json
import math
import os
from dataclasses import dataclass, field
from typing import Iterable, Sequence

from .geo import LatLon, haversine_m, point_polyline_distance_m

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_CACHE = os.path.join(HERE, ".cache", "adana-osm.json.gz")


@dataclass
class OsmNetwork:
    nodes: dict[int, LatLon]
    ways: dict[int, dict]
    osm_timestamp: str
    source: str
    sorted_ids: list[int] = field(default_factory=list)
    edge_ways: dict[tuple[int, int], list[int]] = field(default_factory=dict)
    node_ways: dict[int, list[int]] = field(default_factory=dict)

    @classmethod
    def load(cls, path: str = DEFAULT_CACHE) -> "OsmNetwork":
        if not os.path.exists(path):
            raise FileNotFoundError(
                f"{path} missing; run tools/gpx/extract_osm.py first (see test-data/gpx/README.md)")
        with gzip.open(path, "rt", encoding="utf-8") as f:
            raw = json.load(f)
        nodes = {int(k): (v[0], v[1]) for k, v in raw["nodes"].items()}
        ways = {w["id"]: w for w in raw["ways"]}
        net = cls(nodes, ways, raw.get("osm_timestamp", ""), raw.get("source", ""))
        net._index()
        return net

    def _index(self) -> None:
        self.sorted_ids = sorted(self.nodes)
        for wid in sorted(self.ways):
            refs = self.ways[wid]["nodes"]
            for i, r in enumerate(refs):
                self.node_ways.setdefault(r, []).append(wid)
                if i + 1 < len(refs):
                    key = (min(r, refs[i + 1]), max(r, refs[i + 1]))
                    self.edge_ways.setdefault(key, []).append(wid)

    def ways_for_edge(self, a: int, b: int) -> list[int]:
        return self.edge_ways.get((min(a, b), max(a, b)), [])

    def candidates(self, approx: float, window: int) -> list[int]:
        lo = bisect.bisect_left(self.sorted_ids, math.floor(approx) - window)
        hi = bisect.bisect_right(self.sorted_ids, math.ceil(approx) + window)
        return self.sorted_ids[lo:hi]


def id_window(approx: float) -> int:
    """Max error of an id printed with 10 significant digits."""
    if approx < 1e10:
        return 0
    digits = len(str(int(approx)))
    return 5 * 10 ** (digits - 11) + 1


MAX_NODE_OFFSET_M = 2.0


def resolve_osrm_nodes(net: OsmNetwork, legs_nodes: Sequence[Sequence[float]],
                       geometry: Sequence[LatLon]) -> tuple[list[int], int]:
    """Exact node ids for the OSRM annotation ids. Returns (ids, unresolved count).

    With overview=full the route geometry has one coordinate per annotation node,
    legs sharing their joint coordinate, so node i of leg j sits at
    geometry[offset_j + i]. Among the id candidates we take the one at that spot.
    A node is unresolved when no candidate lies within MAX_NODE_OFFSET_M (the OSM
    extract and the OSRM data differ there); it is then left out.
    """
    total = sum(len(n) for n in legs_nodes) - (len(legs_nodes) - 1)
    if total != len(geometry):
        raise RuntimeError(f"geometry/annotation mismatch: {len(geometry)} vs {total}")
    out: list[int] = []
    unresolved = 0
    offset = 0
    for nodes in legs_nodes:
        resolved: list[int | None] = []
        for i, approx in enumerate(nodes):
            target = geometry[offset + i]
            cands = net.candidates(approx, id_window(approx))
            best = min(cands, key=lambda c: haversine_m(net.nodes[c], target), default=None)
            ok = best is not None and haversine_m(net.nodes[best], target) <= MAX_NODE_OFFSET_M
            resolved.append(best if ok else None)
        # A leg's first/last geometry point is the snapped waypoint, not a node: the
        # end node is the far end of the edge the waypoint sits on, so resolve it by
        # adjacency with its resolved neighbour.
        for i, j in ((0, 1), (len(nodes) - 1, len(nodes) - 2)):
            if resolved[i] is not None or j < 0 or j >= len(nodes) or resolved[j] is None:
                continue
            cands = [c for c in net.candidates(nodes[i], id_window(nodes[i]))
                     if net.ways_for_edge(c, resolved[j])]
            if len(cands) == 1:
                resolved[i] = cands[0]
        for r in resolved:
            if r is None:
                unresolved += 1
            else:
                out.append(r)
        offset += len(nodes) - 1
    return out, unresolved


def clean_sequence(ids: Iterable[int]) -> list[int]:
    """Drop repeated nodes and the a-b-a back-steps OSRM produces at leg joins."""
    seq: list[int] = []
    for n in ids:
        if seq and seq[-1] == n:
            continue
        if len(seq) >= 2 and seq[-2] == n:
            seq.pop()
            continue
        seq.append(n)
    return seq


def way_sequence(net: OsmNetwork, node_ids: Sequence[int]) -> tuple[list[int | None], int]:
    """Way id for each consecutive node pair; None where no way has that edge."""
    edges: list[int | None] = []
    missing = 0
    prev_way: int | None = None
    for a, b in zip(node_ids, node_ids[1:]):
        ws = net.ways_for_edge(a, b)
        if not ws:
            edges.append(None)
            missing += 1
            continue
        wid = prev_way if prev_way in ws else ws[0]
        edges.append(wid)
        prev_way = wid
    return edges, missing


def collapse(seq: Iterable) -> list:
    out: list = []
    for x in seq:
        if x is None:
            continue
        if not out or out[-1] != x:
            out.append(x)
    return out


def nearest_node(net: OsmNetwork, p: LatLon, node_ids: Iterable[int]) -> int:
    return min(node_ids, key=lambda n: haversine_m(net.nodes[n], p))


def trim_reversals(net: OsmNetwork, path: list[int], max_turn_deg: float = 120.0) -> list[int]:
    """Drop end pieces where the path doubles back (a train cannot reverse at a switch)."""
    from .geo import angle_diff_deg, bearing_deg

    def turn(a: int, b: int, c: int) -> float:
        return angle_diff_deg(bearing_deg(net.nodes[a], net.nodes[b]),
                              bearing_deg(net.nodes[b], net.nodes[c]))

    path = list(path)
    changed = True
    while changed and len(path) >= 3:
        changed = False
        for i in range(1, len(path) - 1):
            if turn(path[i - 1], path[i], path[i + 1]) > max_turn_deg:
                # cut the shorter side of the reversal
                path = path[i:] if i <= len(path) // 2 else path[: i + 1]
                changed = True
                break
    return path


def largest_component(net: OsmNetwork, way_ids: Iterable[int]) -> set[int]:
    """Nodes of the largest connected component formed by the given ways."""
    parent: dict[int, int] = {}

    def find(x: int) -> int:
        while parent.setdefault(x, x) != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    for wid in way_ids:
        refs = net.ways[wid]["nodes"]
        for a, b in zip(refs, refs[1:]):
            ra, rb = find(a), find(b)
            if ra != rb:
                parent[max(ra, rb)] = min(ra, rb)
    groups: dict[int, set[int]] = {}
    for n in list(parent):
        groups.setdefault(find(n), set()).add(n)
    return max(groups.values(), key=len)


def shortest_path(net: OsmNetwork, start: int, goal: int, way_ids: Iterable[int],
                  penalty: dict[int, float] | None = None) -> list[int]:
    """Dijkstra over the given ways (undirected). Penalty multiplies edge cost per way."""
    adj: dict[int, list[tuple[int, float]]] = {}
    for wid in way_ids:
        refs = net.ways[wid]["nodes"]
        mult = (penalty or {}).get(wid, 1.0)
        for a, b in zip(refs, refs[1:]):
            d = haversine_m(net.nodes[a], net.nodes[b]) * mult
            adj.setdefault(a, []).append((b, d))
            adj.setdefault(b, []).append((a, d))
    dist = {start: 0.0}
    prev: dict[int, int] = {}
    heap = [(0.0, start)]
    while heap:
        d, u = heapq.heappop(heap)
        if u == goal:
            break
        if d > dist.get(u, math.inf):
            continue
        for v, w in adj.get(u, []):
            nd = d + w
            if nd < dist.get(v, math.inf):
                dist[v] = nd
                prev[v] = u
                heapq.heappush(heap, (nd, v))
    if goal not in dist:
        raise RuntimeError(f"no path from {start} to {goal}")
    path = [goal]
    while path[-1] != start:
        path.append(prev[path[-1]])
    return path[::-1]


def parallel_ways(net: OsmNetwork, route_nodes: Sequence[int], route_ways: Iterable[int],
                  max_dist_m: float = 60.0, min_len_m: float = 150.0) -> list[dict]:
    """Drivable ways not on the route that run alongside it (e.g. the opposite carriageway)."""
    on_route = set(route_ways)
    line = [net.nodes[n] for n in route_nodes]
    lats = [p[0] for p in line]
    lons = [p[1] for p in line]
    pad = 0.001
    box = (min(lats) - pad, max(lats) + pad, min(lons) - pad, max(lons) + pad)
    route_highways = {net.ways[w]["tags"].get("highway") for w in on_route if w in net.ways}
    out = []
    for wid, w in net.ways.items():
        if wid in on_route:
            continue
        hw = w["tags"].get("highway")
        if hw not in route_highways and hw not in {"service", "unclassified", "residential"}:
            continue
        pts = [net.nodes[n] for n in w["nodes"]]
        if not all(box[0] <= p[0] <= box[1] and box[2] <= p[1] <= box[3] for p in pts):
            continue
        length = sum(haversine_m(a, b) for a, b in zip(pts, pts[1:]))
        if length < min_len_m:
            continue
        near = sum(1 for p in pts if point_polyline_distance_m(p, line) <= max_dist_m)
        if near / len(pts) >= 0.9:
            dists = [point_polyline_distance_m(p, line) for p in pts]
            out.append({"id": wid, "highway": hw, "name": w["tags"].get("name"),
                        "oneway": w["tags"].get("oneway"), "length_m": round(length),
                        "mean_distance_m": round(sum(dists) / len(dists), 1)})
    out.sort(key=lambda d: (-d["length_m"], d["id"]))
    return out
