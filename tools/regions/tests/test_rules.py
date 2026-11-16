import pytest

from roadmaxxing_regions.rules import (
    CLASS_CODE, LAYER_LOCAL, LAYER_MAJOR, ONEWAY_BACKWARD, ONEWAY_FORWARD, ONEWAY_NONE,
    layer_of, oneway, road_class,
)

COUNTED_MAJOR = ["motorway", "trunk", "primary", "secondary", "tertiary",
                 "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link"]
COUNTED_LOCAL = ["residential", "unclassified", "living_street"]
EXCLUDED = ["service", "track", "path", "footway", "cycleway", "road", "pedestrian",
            "construction", "steps", "bridleway", "proposed", "bus_guideway", "raceway"]


@pytest.mark.parametrize("hw", COUNTED_MAJOR)
def test_major_roads_counted(hw):
    cls = road_class({"highway": hw})
    assert cls == CLASS_CODE[hw]
    assert layer_of(cls) == LAYER_MAJOR


@pytest.mark.parametrize("hw", COUNTED_LOCAL)
def test_local_roads_counted(hw):
    cls = road_class({"highway": hw})
    assert cls is not None
    assert layer_of(cls) == LAYER_LOCAL


@pytest.mark.parametrize("hw", EXCLUDED)
def test_excluded_highways(hw):
    assert road_class({"highway": hw}) is None


def test_no_highway_tag():
    assert road_class({"railway": "rail"}) is None
    assert road_class({}) is None


@pytest.mark.parametrize("access", ["private", "no"])
def test_access_excluded(access):
    assert road_class({"highway": "residential", "access": access}) is None


@pytest.mark.parametrize("access", ["yes", "destination", "permissive"])
def test_access_allowed(access):
    assert road_class({"highway": "residential", "access": access}) is not None


def test_highway_area_excluded():
    assert road_class({"highway": "residential", "area": "yes"}) is None


def test_class_codes_are_frozen():
    # Part of the binary format; renumbering breaks every published pack.
    assert CLASS_CODE["motorway"] == 0
    assert CLASS_CODE["tertiary_link"] == 9
    assert CLASS_CODE["residential"] == 10
    assert CLASS_CODE["living_street"] == 12


@pytest.mark.parametrize("tags,expected", [
    ({"highway": "residential"}, ONEWAY_NONE),
    ({"highway": "residential", "oneway": "yes"}, ONEWAY_FORWARD),
    ({"highway": "residential", "oneway": "1"}, ONEWAY_FORWARD),
    ({"highway": "residential", "oneway": "-1"}, ONEWAY_BACKWARD),
    ({"highway": "motorway"}, ONEWAY_FORWARD),
    ({"highway": "motorway", "oneway": "no"}, ONEWAY_NONE),
    ({"highway": "tertiary", "junction": "roundabout"}, ONEWAY_FORWARD),
    ({"highway": "primary", "oneway": "reversible"}, ONEWAY_NONE),
])
def test_oneway(tags, expected):
    assert oneway(tags) == expected
