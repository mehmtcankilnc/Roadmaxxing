"""Generate the GPX test routes in test-data/gpx/ from tools/gpx/routes.json.

Pipeline per route:
  waypoints --OSRM demo server (car profile)--> approximate OSM node sequence
            --OSM extract cache--> exact node ids, way ids, node coordinates
            --speed profile--> 1 Hz track --> <name>.gpx, <name>.noisy.gpx, <name>.expected.json
Rail routes skip OSRM and use a shortest path over railway=rail ways from the extract.

Standard library only. Needs tools/gpx/.cache/adana-osm.json.gz (see extract_osm.py).
Same config + same caches => byte-identical output.

Usage:
    python tools/gpx/generate.py                # all routes
    python tools/gpx/generate.py --only main-roads --offline
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import random
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from gpxlib import gpxio, noise, osmdata, profile, sources  # noqa: E402
from gpxlib.geo import haversine_m  # noqa: E402
from gpxlib.profile import KMH, Edge, Stop  # noqa: E402

REPO = os.path.dirname(os.path.dirname(HERE))
DEFAULT_CONFIG = os.path.join(HERE, "routes.json")
DEFAULT_OUT = os.path.join(REPO, "test-data", "gpx")
DYNAMICS = {"drive": profile.DRIVE, "walk": profile.WALK, "train": profile.TRAIN}


def route_nodes(cfg: dict, net: osmdata.OsmNetwork, offline: bool) -> tuple[list[int], dict]:
    info: dict = {}
    if cfg["source"] == "osrm":
        route = sources.osrm_route(cfg["waypoints"], offline=offline)
        legs_nodes = [leg["annotation"]["nodes"] for leg in route["legs"]]
        geometry = [(lat, lon) for lon, lat in route["geometry"]["coordinates"]]
        ids, unresolved = osmdata.resolve_osrm_nodes(net, legs_nodes, geometry)
        ids = osmdata.clean_sequence(ids)
        names = []
        for leg in route["legs"]:
            for step in leg["steps"]:
                label = step.get("name") or ""
                if step.get("ref"):
                    label = f"{label} ({step['ref']})" if label else step["ref"]
                if label and (not names or names[-1] != label) and step["distance"] >= 150:
                    names.append(label)
        info.update({"osrm_distance_m": round(route["distance"], 1),
                     "osrm_unresolved_nodes": unresolved, "road_names": names})
        return ids, info
    if cfg["source"] == "osm_rail":
        # The Mersin-Adana main line is tagged railway=construction in OSM (2026-11)
        # while it is being upgraded; its alignment is the operating corridor.
        rail = [w for w, d in net.ways.items() if d["tags"].get("railway") in ("rail", "construction")]
        penalty = {w: 4.0 for w in rail if net.ways[w]["tags"].get("service")}
        rail_nodes = osmdata.largest_component(net, rail)
        a = osmdata.nearest_node(net, tuple(cfg["from"]), rail_nodes)
        b = osmdata.nearest_node(net, tuple(cfg["to"]), rail_nodes)
        path = osmdata.shortest_path(net, a, b, rail, penalty)
        return osmdata.trim_reversals(net, path), info
    raise ValueError(f"unknown source {cfg['source']}")


def edges_for(cfg: dict, net: osmdata.OsmNetwork, way_per_edge: list, seed: int
              ) -> tuple[list[Edge], list[dict]]:
    mode = cfg["mode"]
    urban = cfg.get("urban", True)
    edges: list[Edge] = []
    used: dict[int, dict] = {}
    for wid in way_per_edge:
        tags = net.ways[wid]["tags"] if wid is not None else {}
        rng = random.Random(f"{seed}:{cfg['name']}:way:{wid}")
        if mode == "walk":
            limit_kmh, src = cfg["walk_speed_kmh"] * 1.15, "walking pace"
            cruise = cfg["walk_speed_kmh"] * KMH * rng.uniform(0.9, 1.0)
        elif mode == "train":
            tagged = profile.parse_maxspeed(tags.get("maxspeed"))
            limit_kmh = min(cfg["train_max_kmh"], tagged) if tagged else cfg["train_max_kmh"]
            src = "tag+config" if tagged else "config"
            cruise = limit_kmh * KMH * profile.cruise_factor(None, mode, rng)
        else:
            limit_kmh, src = profile.speed_limit_kmh(tags, urban)
            cruise = limit_kmh * KMH * profile.cruise_factor(tags.get("highway"), mode, rng)
        edges.append(Edge(limit_ms=limit_kmh * KMH, cruise_ms=cruise))
        if wid is not None and wid not in used:
            used[wid] = {"id": wid, "highway": tags.get("highway"), "railway": tags.get("railway"),
                         "name": tags.get("name"), "ref": tags.get("ref"),
                         "oneway": tags.get("oneway"), "limit_kmh": round(limit_kmh),
                         "limit_source": src}
    for d in used.values():
        for k in [k for k, v in d.items() if v is None]:
            del d[k]
    return edges, list(used.values())


def plan_stops(cfg: dict, net: osmdata.OsmNetwork, ids: list[int], cum: list[float],
               rng: random.Random) -> tuple[list[Stop], list[tuple[float, float, float]], list[dict]]:
    total = cum[-1]
    stops: list[Stop] = []
    zones: list[tuple[float, float, float]] = []
    report: list[dict] = []
    for st in cfg.get("stops", []):
        at = st["at_fraction"] * total
        stops.append(Stop(at, st["duration_s"]))
        report.append({"kind": st.get("kind", "stop"), "at_m": round(at), "duration_s": st["duration_s"]})
    sg = cfg.get("stop_and_go")
    if sg:
        lo = sg["from_fraction"] * total
        hi = lo + sg["length_m"]
        zones.append((lo, hi, sg["cap_kmh"] * KMH))
        s = lo + rng.uniform(*sg["stop_every_m"])
        n = 0
        while s < hi:
            d = rng.randint(*sg["stop_s"])
            stops.append(Stop(s, d))
            n += 1
            s += rng.uniform(*sg["stop_every_m"])
        report.append({"kind": "stop_and_go", "from_m": round(lo), "to_m": round(hi),
                       "cap_kmh": sg["cap_kmh"], "short_stops": n})
    sig = cfg.get("signal_stops")
    if sig:
        junctions = [cum[i] for i, n in enumerate(ids)
                     if len(set(net.node_ways.get(n, []))) >= 2
                     and sig["margin_m"] < cum[i] < total - sig["margin_m"]]
        blocked = [(s.at_m - sig["spacing_m"], s.at_m + sig["spacing_m"]) for s in stops] + \
                  [(z[0] - sig["spacing_m"], z[1] + sig["spacing_m"]) for z in zones]
        chosen: list[float] = []
        rng.shuffle(junctions)
        for j in junctions:
            if len(chosen) >= sig["count"]:
                break
            if any(lo <= j <= hi for lo, hi in blocked) or \
                    any(abs(j - c) < sig["spacing_m"] for c in chosen):
                continue
            chosen.append(j)
        for j in sorted(chosen):
            d = rng.randint(*sig["duration_s"])
            stops.append(Stop(j, d))
            report.append({"kind": "traffic_light", "at_m": round(j), "duration_s": d})
    report.sort(key=lambda r: r.get("at_m", r.get("from_m", 0)))
    return stops, zones, report


def generate_route(cfg: dict, defaults: dict, net: osmdata.OsmNetwork, out_dir: str,
                   offline: bool) -> dict:
    name = cfg["name"]
    seed = defaults["seed"]
    rng = random.Random(f"{seed}:{name}")
    ids, info = route_nodes(cfg, net, offline)
    way_per_edge, missing = osmdata.way_sequence(net, ids)
    points = [net.nodes[n] for n in ids]
    edges, ways_info = edges_for(cfg, net, way_per_edge, seed)
    cum = [0.0]
    for a, b in zip(points, points[1:]):
        cum.append(cum[-1] + haversine_m(a, b))
    stops, zones, stop_report = plan_stops(cfg, net, ids, cum, rng)
    prof = profile.build_profile(points, edges, DYNAMICS[cfg["mode"]], stops, zones)
    samples = profile.sample_1hz(prof)
    noisy = noise.add_noise(samples, random.Random(f"{seed}:{name}:noise"))

    start = gpxio.parse_iso(cfg.get("start_time", defaults["start_time"]))
    desc = cfg["scenario"]
    gpxio.write_gpx(os.path.join(out_dir, f"{name}.gpx"), samples, start, name, desc)
    gpxio.write_gpx(os.path.join(out_dir, f"{name}.noisy.gpx"), noisy, start, f"{name} (noisy)", desc)

    duration = samples[-1].t - samples[0].t
    speeds = [s.speed for s in samples]
    moving = [s for s in speeds if s > 0.5]
    expected = {
        "name": name,
        "scenario": cfg["scenario"],
        "mode": cfg["mode"],
        "files": {"clean": f"{name}.gpx", "noisy": f"{name}.noisy.gpx"},
        "expected": cfg["expected"],
        "distance_m": round(prof.length_m, 1),
        "duration_s": duration,
        "points": {"clean": len(samples), "noisy": len(noisy),
                   "noisy_dropped": len(samples) - len(noisy)},
        "speed_kmh": {
            "avg": round(prof.length_m / duration * 3.6, 1),
            "avg_moving": round(sum(moving) / len(moving) * 3.6, 1),
            "max": round(max(speeds) * 3.6, 1),
            "max_limit_on_route": round(max(e.limit_ms for e in edges) * 3.6),
        },
        "stops": stop_report,
        "start_time": gpxio.iso(start),
        "osm_node_ids": ids,
        "osm_way_ids": osmdata.collapse(way_per_edge),
        "ways": ways_info,
        "edges_without_way": missing,
        "data": {
            "osm_extract": net.source,
            "osm_timestamp": net.osm_timestamp,
            "routing": "OSRM demo server router.project-osrm.org, car profile"
            if cfg["source"] == "osrm" else "shortest path over railway=rail ways in the OSM extract",
            "seed": seed,
        },
    }
    if cfg["source"] == "osrm":
        expected["waypoints"] = cfg["waypoints"]
        expected["road_names"] = info["road_names"]
        expected["data"]["osrm_distance_m"] = info["osrm_distance_m"]
        expected["data"]["osrm_unresolved_nodes"] = info["osrm_unresolved_nodes"]
    if cfg.get("report_parallel_ways"):
        expected["parallel_ways_not_driven"] = osmdata.parallel_ways(
            net, ids, expected["osm_way_ids"])
    if cfg.get("notes"):
        expected["notes"] = cfg["notes"]
    with open(os.path.join(out_dir, f"{name}.expected.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(expected, f, ensure_ascii=False, indent=2)
        f.write("\n")
    return expected


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--config", default=DEFAULT_CONFIG)
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--osm-cache", default=osmdata.DEFAULT_CACHE)
    ap.add_argument("--only", nargs="*", help="route names to generate")
    ap.add_argument("--offline", action="store_true", help="fail instead of calling OSRM")
    args = ap.parse_args(argv)

    with open(args.config, encoding="utf-8") as f:
        config = json.load(f)
    net = osmdata.OsmNetwork.load(args.osm_cache)
    os.makedirs(args.out, exist_ok=True)
    for cfg in config["routes"]:
        if args.only and cfg["name"] not in args.only:
            continue
        e = generate_route(cfg, config["defaults"], net, args.out, args.offline)
        print(f"{e['name']:<22} {e['distance_m'] / 1000:7.2f} km {e['duration_s']:6d} s "
              f"avg {e['speed_kmh']['avg']:5.1f} max {e['speed_kmh']['max']:5.1f} km/h "
              f"nodes {len(e['osm_node_ids']):5d} ways {len(e['osm_way_ids']):4d} "
              f"no-way edges {e['edges_without_way']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
