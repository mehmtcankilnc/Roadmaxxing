# tools/regions — region pack builder

Builds the offline "region pack" for one province from OpenStreetMap: every
drivable road split into segments at junctions, each segment assigned to its
province and district, plus per-region total road lengths. The app's matcher
reads this pack to colour driven roads and compute completion percentages.

- Format specification: [FORMAT.md](FORMAT.md)
- Output: `test-data/regions/<pack-id>/` (`manifest.json`, `regions.json`, `segments.bin`, `tiles/{z}/{x}/{y}.bin`)
- Source: Geofabrik Turkey extract (downloaded once into `tools/regions/.cache/`, git-ignored)

## Setup (once)

Python 3.12. All dependencies are pinned in `requirements.txt`
(`osmium` = pyosmium, `shapely`, `pytest`); pip installs prebuilt wheels on
Windows, Linux and macOS, no compiler or system packages needed.

Windows (PowerShell), from `tools/regions/`:

```
py -3.12 -m venv .venv
.venv\Scripts\python -m pip install -r requirements.txt
```

Linux / macOS / GitHub Actions (`ubuntu-latest`), from `tools/regions/`:

```
python3.12 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
```

## Build the Adana pack (one command)

Windows:

```
.venv\Scripts\python -m roadmaxxing_regions --province Adana
```

Linux / macOS:

```
.venv/bin/python -m roadmaxxing_regions --province Adana
```

What it does:

1. Resolves `turkey-latest.osm.pbf` on Geofabrik to its dated file
   (e.g. `turkey-261115.osm.pbf`) and downloads it into `.cache/` unless that
   file is already there (~650 MB).
2. Reads the province (`admin_level=4`) and its districts (`admin_level=6`).
3. Reads drivable roads, splits them at junctions, assigns regions, sums lengths.
4. Writes `test-data/regions/adana/`.

Takes 4–6 minutes on a laptop (two full passes over the extract) and ~2 GB RAM
for Turkey (libosmium keeps every node
location of the extract in memory during the two way passes).

Useful options (`--help` for all):

| Option | Meaning |
| --- | --- |
| `--province X` | name, `name:tr`, `name:en`, `ISO3166-2` (e.g. `TR-01`) or `r<relation id>` |
| `--input FILE` | use a local `.osm.pbf` / `.osm` file instead of downloading |
| `--out DIR` | output directory (default `test-data/regions/<pack-id>`) |
| `--pack-id ID` | default: ASCII slug of `name:en` / `name` |
| `--zoom N`, `--coord-decimals 6\|7` | tile zoom and coordinate precision (defaults 13 and 6, see FORMAT.md) |
| `--measure` | also print pack size for zoom 12/13/14 × precision 6/7, binary vs JSON |

### Reproducibility

Same input file → byte-identical output. Nothing in the output depends on the
build time or machine; the source file name, its md5 and the OSM replication
timestamp are recorded in `manifest.json`. `-latest` moves daily, so to
rebuild an existing pack exactly, pass the cached dated file with `--input`.

## Tests

```
.venv\Scripts\python -m pytest        # Windows
.venv/bin/python -m pytest            # Linux / macOS
```

Covers tag filtering, junction splitting, segment id stability, geodesic
length (against an independent Vincenty implementation), tile encoding round
trip, region totals, and byte-for-byte equality with the committed fixture
pack in `test-data/regions/fixture/` (see its README).

To change the fixture: edit `tests/data/make_fixture_osm.py`, run it, then
regenerate the pack with the command in `test-data/regions/fixture/README.md`.

## Layout

| Path | Role |
| --- | --- |
| `roadmaxxing_regions/rules.py` | which `highway` tags count, road classes, oneway |
| `roadmaxxing_regions/segments.py` | junction detection, splitting, segment ids |
| `roadmaxxing_regions/geo.py` | ellipsoidal lengths, midpoints, XYZ tile math |
| `roadmaxxing_regions/boundaries.py` | admin polygons from relation members |
| `roadmaxxing_regions/osmread.py` | pyosmium passes over the extract |
| `roadmaxxing_regions/tileformat.py` | binary tile writer + reference reader |
| `roadmaxxing_regions/build.py` | pipeline, region assignment, output files |
| `roadmaxxing_regions/__main__.py` | CLI and cached download |
