"""Network sources: the public OSRM demo server, with an on-disk cache.

Be polite to the demo server: every response is cached under tools/gpx/.cache/,
requests are spaced out, and only public road coordinates are sent. No personal
data goes into the request (generic User-Agent, no e-mail).
"""
from __future__ import annotations

import hashlib
import json
import os
import time
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CACHE_DIR = os.path.join(HERE, ".cache")
OSRM_BASE = "https://router.project-osrm.org"
USER_AGENT = "gpx-testdata-generator/1.0"
MIN_INTERVAL_S = 1.5

_last_request = 0.0


class OfflineError(RuntimeError):
    pass


def _cache_path(key: str) -> str:
    digest = hashlib.sha1(key.encode("utf-8")).hexdigest()[:20]
    return os.path.join(CACHE_DIR, "http", digest + ".json")


def _log_request(url: str) -> None:
    path = os.path.join(CACHE_DIR, "request-log.json")
    log = {"count": 0, "requests": []}
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            log = json.load(f)
    log["count"] += 1
    log["requests"].append({"url": url, "at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())})
    with open(path, "w", encoding="utf-8") as f:
        json.dump(log, f, indent=1)


def fetch_json(url: str, offline: bool = False) -> dict:
    """GET a JSON document, served from cache when present."""
    global _last_request
    path = _cache_path(url)
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    if offline:
        raise OfflineError(f"not cached and --offline given: {url}")
    wait = MIN_INTERVAL_S - (time.monotonic() - _last_request)
    if wait > 0:
        time.sleep(wait)
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=60) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    _last_request = time.monotonic()
    os.makedirs(os.path.dirname(path), exist_ok=True)
    _log_request(url)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f)
    return data


BEARING_RANGE = 45


def osrm_route(waypoints: list[list[float]], offline: bool = False) -> dict:
    """Car route through [lat, lon] or [lat, lon, heading] waypoints; first OSRM route.

    A heading (degrees) makes OSRM snap the waypoint only to a road travelled in
    that direction (+-BEARING_RANGE), which picks the right carriageway of a
    divided road.
    """
    coords = ";".join(f"{wp[1]:.6f},{wp[0]:.6f}" for wp in waypoints)
    params = {
        "overview": "full",
        "geometries": "geojson",
        "annotations": "nodes",
        "steps": "true",
        "continue_straight": "true",
    }
    if any(len(wp) > 2 for wp in waypoints):
        params["bearings"] = ";".join(
            f"{round(wp[2]) % 360},{BEARING_RANGE}" if len(wp) > 2 else "" for wp in waypoints)
    query = urllib.parse.urlencode(params, safe=",;")
    url = f"{OSRM_BASE}/route/v1/driving/{coords}?{query}"
    data = fetch_json(url, offline=offline)
    if data.get("code") != "Ok" or not data.get("routes"):
        raise RuntimeError(f"OSRM error: {data.get('code')} {data.get('message')}")
    return data["routes"][0]
