# Fixture region pack

A tiny synthetic pack for unit tests (Python here, Kotlin reader and matcher
later). Every value can be checked by hand. Format: `tools/regions/FORMAT.md`.

Generated from `tools/regions/tests/data/fixture.osm` (written by
`tests/data/make_fixture_osm.py`). Regenerate from `tools/regions/`:

```
.venv/Scripts/python -m roadmaxxing_regions --input tests/data/fixture.osm --province Testil --pack-id fixture --out ../../test-data/regions/fixture
```

(Linux/macOS: `.venv/bin/python`.) `tests/test_pack.py` fails if this folder
no longer matches the builder's output byte for byte.

## Regions

| id | level | name | name_en | area |
| --- | --- | --- | --- | --- |
| 1 | 1 (province) | Testil | Testprovince | lon 35.000–35.020, lat 37.000–37.010 |
| 11 | 2 (district) | Batı | West | lon 35.000–35.010 |
| 12 | 2 (district) | Doğu | East | lon 35.010–35.020 |

## Map

```
lat
37.0115            141
                    |  (1006 tertiary, leaves the province)
37.010  6-----------+------5-----------------4        boundary: 6-1-3-4 province,
        |           |      |                 |        2-5 district line (lon 35.010)
37.008  |  121------+-----(2005)--------123--1003->--124
        |   |       |      |             |            |
37.005  |  111-----112----113-----------114----------115---131---132  (1005, outside)
        |   |  1001 |  primary           |  1001      |
37.002  |  101-----102     |           (1007 roundabout 151-154, ~35.016)
        |                  |                          |
37.000  1------------------2-------------------------3
      35.000 35.002 35.006 35.010       35.014      35.018  35.024 35.030
```

Vertical roads: 2001 (101-111-121), 2002 (102-112), 2003 (114-123, `oneway=-1`),
2004 (115-124, living_street).

## Segments (13)

| segment (way:ordinal) | nodes | class | oneway | district | why |
| --- | --- | --- | --- | --- | --- |
| 1001:0 | 111-112 | primary | 0 | 11 | |
| 1001:1 | 112-113-114 | primary | 0 | 11 | 113 is not a junction (only an excluded way touches it); midpoint lies exactly on the district line → tie → lowest relation id |
| 1001:2 | 114-115 | primary | 0 | 12 | |
| 1002:0 | 101-102 | residential | 0 | 11 | ≈ 355.8 m (0.004° lon at 37.002 N) |
| 1003:0 | 123-124 | residential | 1 | 12 | `oneway=yes` |
| 1006:0 | 112-141 | tertiary | 0 | 11 | leaves the province, midpoint (lat 37.00825) is inside |
| 1007:0 | 151-152-153-154-151 | tertiary | 1 | 12 | closed roundabout, implied oneway, one segment |
| 2001:0 | 101-111 | residential | 0 | 11 | |
| 2001:1 | 111-121 | residential | 0 | 11 | |
| 2002:0 | 102-112 | residential | 0 | 11 | ≈ 332.9 m (0.003° lat) |
| 2003:0 | 114-123 | residential | 2 | 12 | `oneway=-1` |
| 2004:0 | 115-124 | living_street | 0 | 12 | |
| 2005:0 | 121-123 | unclassified | 0 | 11 | crosses the district line, midpoint lon 35.008 is west |

Not in the pack: 1005 (secondary, midpoint lon 35.024 is outside the
province), 3001 `service`, 3002 `footway`, 3003 `access=private`,
3004 `construction`.

Totals: province = West + East for each layer; segment counts major 5
(1001×3, 1006, 1007), local 8. Exact decimetre values are in `regions.json`.
