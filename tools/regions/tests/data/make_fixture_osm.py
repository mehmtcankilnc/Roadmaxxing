"""Generate tests/data/fixture.osm: a tiny synthetic province for tests.

Run: python tests/data/make_fixture_osm.py  (rewrites fixture.osm)

Layout (lon 35.000..35.020, lat 37.000..37.010), see test-data/regions/fixture/README.md.
"""
from __future__ import annotations

from pathlib import Path
from xml.sax.saxutils import quoteattr

NODES: dict[int, tuple[float, float]] = {
    # boundary corners
    1: (35.000, 37.000), 2: (35.010, 37.000), 3: (35.020, 37.000),
    4: (35.020, 37.010), 5: (35.010, 37.010), 6: (35.000, 37.010),
    # road grid
    101: (35.002, 37.002), 102: (35.006, 37.002),
    111: (35.002, 37.005), 112: (35.006, 37.005), 113: (35.010, 37.005),
    114: (35.014, 37.005), 115: (35.018, 37.005),
    121: (35.002, 37.008), 123: (35.014, 37.008), 124: (35.018, 37.008),
    # outside the province (east)
    131: (35.024, 37.005), 132: (35.030, 37.005),
    # north spur leaving the province
    141: (35.006, 37.0115),
    # roundabout square
    151: (35.015, 37.0015), 152: (35.017, 37.0015), 153: (35.017, 37.0025), 154: (35.015, 37.0025),
    # excluded roads' extra nodes
    161: (35.014, 37.002), 162: (35.004, 37.0035), 163: (35.004, 37.0065),
    164: (35.012, 37.0065), 165: (35.012, 37.0085),
}

WAYS: list[tuple[int, list[int], dict[str, str]]] = [
    # admin boundary ways
    (911, [1, 2], {}), (912, [2, 3], {}), (902, [3, 4], {}), (913, [4, 5], {}),
    (914, [5, 6], {}), (904, [6, 1], {}), (905, [2, 5], {}),
    # counted roads
    (1001, [111, 112, 113, 114, 115], {"highway": "primary", "name": "Ana Cadde"}),
    (1002, [101, 102], {"highway": "residential"}),
    (1003, [123, 124], {"highway": "residential", "oneway": "yes"}),
    (1005, [115, 131, 132], {"highway": "secondary"}),
    (1006, [112, 141], {"highway": "tertiary"}),
    (1007, [151, 152, 153, 154, 151], {"highway": "tertiary", "junction": "roundabout"}),
    (2001, [101, 111, 121], {"highway": "residential"}),
    (2002, [102, 112], {"highway": "residential"}),
    (2003, [114, 123], {"highway": "residential", "oneway": "-1"}),
    (2004, [115, 124], {"highway": "living_street"}),
    (2005, [121, 123], {"highway": "unclassified"}),
    # excluded roads
    (3001, [102, 161, 114], {"highway": "service"}),
    (3002, [101, 162, 163], {"highway": "footway"}),
    (3003, [113, 164], {"highway": "residential", "access": "private"}),
    (3004, [164, 165], {"highway": "construction"}),
]

RELATIONS: list[tuple[int, list[tuple[int, str]], dict[str, str]]] = [
    (1, [(911, "outer"), (912, "outer"), (902, "outer"), (913, "outer"), (914, "outer"), (904, "outer")],
     {"type": "boundary", "boundary": "administrative", "admin_level": "4", "name": "Testil",
      "name:tr": "Testil", "name:en": "Testprovince", "ISO3166-2": "TR-99"}),
    (11, [(911, "outer"), (905, "outer"), (914, "outer"), (904, "outer")],
     {"type": "boundary", "boundary": "administrative", "admin_level": "6", "name": "Batı",
      "name:tr": "Batı", "name:en": "West"}),
    (12, [(912, "outer"), (902, "outer"), (913, "outer"), (905, "outer")],
     {"type": "boundary", "boundary": "administrative", "admin_level": "6", "name": "Doğu",
      "name:tr": "Doğu", "name:en": "East"}),
]


def render() -> str:
    lines = ['<?xml version="1.0" encoding="UTF-8"?>',
             '<osm version="0.6" generator="make_fixture_osm.py">']
    for nid in sorted(NODES):
        lon, lat = NODES[nid]
        lines.append(f'  <node id="{nid}" version="1" lat="{lat:.7f}" lon="{lon:.7f}"/>')
    for wid, refs, tags in sorted(WAYS):
        lines.append(f'  <way id="{wid}" version="1">')
        lines += [f'    <nd ref="{r}"/>' for r in refs]
        lines += [f'    <tag k={quoteattr(k)} v={quoteattr(v)}/>' for k, v in tags.items()]
        lines.append('  </way>')
    for rid, members, tags in sorted(RELATIONS):
        lines.append(f'  <relation id="{rid}" version="1">')
        lines += [f'    <member type="way" ref="{m}" role="{role}"/>' for m, role in members]
        lines += [f'    <tag k={quoteattr(k)} v={quoteattr(v)}/>' for k, v in tags.items()]
        lines.append('  </relation>')
    lines.append('</osm>')
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    out = Path(__file__).with_name("fixture.osm")
    out.write_text(render(), encoding="utf-8", newline="\n")
    print(f"wrote {out}")
