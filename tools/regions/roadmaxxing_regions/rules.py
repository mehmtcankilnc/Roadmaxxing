"""Tag rules: which OSM ways count as drivable roads, their class and direction.

These rules decide the denominator of every completion percentage, so they are
kept small, explicit and unit tested (see tests/test_rules.py).
"""
from __future__ import annotations

from typing import Mapping

# Road class codes are part of the binary format (FORMAT.md). Never renumber.
ROAD_CLASSES: tuple[str, ...] = (
    "motorway",        # 0
    "motorway_link",   # 1
    "trunk",           # 2
    "trunk_link",      # 3
    "primary",         # 4
    "primary_link",    # 5
    "secondary",       # 6
    "secondary_link",  # 7
    "tertiary",        # 8
    "tertiary_link",   # 9
    "residential",     # 10
    "unclassified",    # 11
    "living_street",   # 12
)
CLASS_CODE: dict[str, int] = {name: i for i, name in enumerate(ROAD_CLASSES)}

LAYER_MAJOR = 0  # "ana yollar"
LAYER_LOCAL = 1  # "mahalle yollari"
LAYER_NAMES = ("major", "local")

MAJOR_CLASSES = frozenset(ROAD_CLASSES[:10])
LOCAL_CLASSES = frozenset(ROAD_CLASSES[10:])

EXCLUDED_ACCESS = frozenset({"private", "no"})

ONEWAY_NONE = 0
ONEWAY_FORWARD = 1    # traffic flows in geometry order
ONEWAY_BACKWARD = 2   # traffic flows against geometry order (oneway=-1)


def layer_of(class_code: int) -> int:
    return LAYER_MAJOR if class_code < 10 else LAYER_LOCAL


def road_class(tags: Mapping[str, str]) -> int | None:
    """Return the road class code if the way counts as a drivable road, else None.

    Counted: motorway..tertiary (+ _link) and residential, unclassified,
    living_street. Everything else (service, track, path, footway, cycleway,
    road, pedestrian, construction, ...) is excluded, as are ways with
    access=private|no and highway areas (area=yes, a square, not a road line).
    """
    highway = tags.get("highway")
    if highway is None or highway not in CLASS_CODE:
        return None
    if tags.get("access") in EXCLUDED_ACCESS:
        return None
    if tags.get("area") == "yes":
        return None
    return CLASS_CODE[highway]


def oneway(tags: Mapping[str, str]) -> int:
    """Direction of travel relative to the way's node order."""
    value = tags.get("oneway")
    if value in ("yes", "true", "1"):
        return ONEWAY_FORWARD
    if value in ("-1", "reverse"):
        return ONEWAY_BACKWARD
    if value in ("no", "false", "0"):
        return ONEWAY_NONE
    # Implied oneway (OSM convention).
    if tags.get("highway") == "motorway":
        return ONEWAY_FORWARD
    if tags.get("junction") in ("roundabout", "circular"):
        return ONEWAY_FORWARD
    return ONEWAY_NONE
