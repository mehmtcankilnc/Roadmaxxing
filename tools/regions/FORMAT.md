# Region pack format — version 1

This document is the full specification of a Roadmaxxing region pack. A
Kotlin Multiplatform reader (`commonMain`, standard library +
kotlinx-serialization-json only, no zlib) can be written from it alone.
`roadmaxxing_regions/tileformat.py` contains a reference reader
(`decode_tile`) that the tests run against every tile.

Format version: **1** (`format_version` in both JSON files, version byte in
every tile). Any incompatible change bumps the version; readers must reject
versions they do not know.

## 1. Files

```
<pack-id>/
  manifest.json            pack metadata and tile index
  regions.json             regions (province + districts): names, totals, boundaries
  segments.bin             segment summary index (optional, section 6)
  tiles/<z>/<x>/<y>.bin    road segments, one binary file per map tile
```

All JSON files are UTF-8, no BOM, keys sorted, no insignificant whitespace,
one trailing `\n`. Unknown JSON keys must be ignored by readers (fields may be
added within a version; removing or changing one bumps the version).

## 2. Concepts

### 2.1 Segment

A **segment** is a piece of one OSM way between two junction nodes. It is the
unit that is "driven" (≥ 70 % covered → whole length counts) and the unit
stored in the user's history (`DrivenSegment`).

- **Junction node:** a node that occurs more than once across all *counted*
  ways (two counted ways meet, or one way touches itself), or the first/last
  node of a counted way. Excluded ways (service, footway, ...) never create
  junctions.
- A way is cut at every interior junction node. Consecutive segments of a way
  share their end/start node. A closed way with no other junction is one
  segment whose first and last node are equal.
- Consecutive duplicate node refs are dropped before splitting.

### 2.2 Segment id (persistent)

```
segmentId = wayId * 4096 + ordinal
```

- `wayId`: OSM way id. `ordinal`: 0-based index of the segment within its way,
  counted over the **whole** way in node order (independent of which pieces
  fall inside the province).
- `ordinal < 4096` always (OSM ways have at most 2000 nodes).
- Fits in a Kotlin `Long` and is exact as a JSON/JavaScript number for all
  current and foreseeable OSM way ids (way id < 2^41).
- Stable as long as the way's id and its junctions stay the same. If a new
  road joins an existing way in the middle, that way's later ordinals shift;
  packs of later versions will ship a mapping file for such changes
  (not part of version 1).

### 2.3 Counted roads (denominator)

| Layer | `layer` | OSM `highway` | class codes |
| --- | --- | --- | --- |
| major ("ana yollar") | 0 | motorway, motorway_link, trunk, trunk_link, primary, primary_link, secondary, secondary_link, tertiary, tertiary_link | 0–9 |
| local ("mahalle yolları") | 1 | residential, unclassified, living_street | 10–12 |

Excluded even when `highway` matches: `access=private`, `access=no`,
`area=yes`. All other `highway` values (service, track, path, footway,
cycleway, road, pedestrian, construction, ...) are not counted. Divided
roads mapped as two ways are two separate sets of segments.

Road class codes (frozen):

| code | highway | code | highway |
| --- | --- | --- | --- |
| 0 | motorway | 7 | secondary_link |
| 1 | motorway_link | 8 | tertiary |
| 2 | trunk | 9 | tertiary_link |
| 3 | trunk_link | 10 | residential |
| 4 | primary | 11 | unclassified |
| 5 | primary_link | 12 | living_street |
| 6 | secondary | | |

`layer = if (classCode < 10) 0 else 1`.

### 2.4 Oneway codes

| code | meaning |
| --- | --- |
| 0 | both directions |
| 1 | only in geometry order (`oneway=yes/true/1`; implied for `highway=motorway` and `junction=roundabout/circular` unless `oneway=no`) |
| 2 | only against geometry order (`oneway=-1/reverse`) |

Anything else (`oneway=reversible`, `alternating`, missing) is 0.

### 2.5 Region assignment

- **Province** = `boundary=administrative` relation with `admin_level=4`
  (Turkey: il). **District** = `admin_level=6` (ilçe) whose polygon's
  representative point lies inside the province polygon.
- A segment belongs **entirely** to the region that contains its
  **midpoint** (the point at half of its geodesic length along the line).
  Segments are never cut at region boundaries; a segment crossing a boundary
  counts fully on the side of its midpoint. The same rule in a neighbouring
  province's pack guarantees no segment is counted twice.
- A segment is in the pack iff its midpoint is inside the province polygon.
- District: the district polygon containing the midpoint. If several do
  (overlapping boundaries) the lowest relation id wins; if none does
  (gaps, or midpoint exactly on a boundary line) the nearest district wins
  (planar distance in degrees, ties → lowest relation id). Counts of both
  cases are in `manifest.json` → `stats`.
- Consequently every segment has exactly one district, and
  province total = Σ district totals exactly, per layer.

### 2.6 Length

`length_dm`: segment length in **decimetres**, rounded half to even, from the
full-precision OSM coordinates (1e-7°). Sum of per-edge ellipsoidal lengths
on WGS84 (a = 6378137, f = 1/298.257223563, e² = f(2−f)):

```
φm = (φ1 + φ2) / 2
W  = 1 − e² sin²φm
M  = a (1 − e²) / W^1.5          (meridional radius)
N  = a / √W                      (prime vertical radius)
d  = √( (Δφ·M)² + (Δλ·N·cos φm)² )     (angles in radians)
```

Agrees with exact geodesics (Vincenty/Karney) to better than 1e-6 relative
for OSM edge lengths. Readers use the stored value; they do not recompute.
Region totals are exact integer sums of segment `length_dm` values.

### 2.7 Coordinates

Tiles store WGS84 lon/lat as integers in units of 10^-`coordDecimals` degree.
Version 1 packs are written with `coordDecimals = 6` (1e-6° ≈ 0.11 m north–south,
0.09 m east–west at 37° N), quantised from OSM's 1e-7° as
`q = floorDiv(v7 + 5, 10)` (round half up). Readers must use the value in each
tile header, not assume 6.

## 3. manifest.json

```json
{
  "format": "roadmaxxing-region-pack",
  "format_version": 1,
  "pack_id": "adana",
  "province_id": 167216,
  "source": {
    "file": "turkey-261115.osm.pbf",
    "url": "https://download.geofabrik.de/europe/turkey-261115.osm.pbf",
    "md5": "…",
    "osm_replication_timestamp": "2026-11-15T21:22:28Z"
  },
  "tile_zoom": 13,
  "coord_decimals": 6,
  "tile_path": "tiles/{z}/{x}/{y}.bin",
  "tile_columns": ["x", "y", "segment_count", "bytes"],
  "tiles": [[4887, 3178, 197, 9462], …],
  "segment_count": 92597,
  "segment_index": { "path": "segments.bin", "bytes": 0 },
  "stats": { "district_gap_assigned": 0, "district_overlap": 0, "drivable_ways_read": 0, "segments_unresolved": 0 }
}
```

| key | type | meaning |
| --- | --- | --- |
| `province_id` | Long | OSM relation id of the province (= its region id) |
| `source.url` | string \| null | null when built from a local file |
| `tile_zoom` | Int | all tiles of the pack use this zoom |
| `tiles` | array of `[x, y, segmentCount, bytes]` | sorted by x, then y. `segmentCount` counts segments stored in that tile (a segment may be in several tiles) |
| `segment_count` | Int | distinct segments in the pack |
| `segment_index` | object \| absent | optional (added within version 1): `path` of the segment summary index relative to the pack root, `bytes` its size. Absent in older packs: readers then build the same data by scanning all tiles |
| `stats` | object | build diagnostics, informative only |

## 4. regions.json

```json
{
  "format": "roadmaxxing-regions",
  "format_version": 1,
  "regions": [
    {
      "id": 167216, "level": 1, "parent_id": null, "osm_admin_level": 4,
      "name": "Adana", "name_tr": "Adana", "name_en": "Adana", "iso3166_2": "TR-01",
      "length_dm": {"major": 0, "local": 0, "all": 0},
      "segment_count": {"major": 0, "local": 0, "all": 0},
      "bbox": [34.8, 36.5, 36.4, 38.4],
      "boundary": [[[[35.1, 37.0], …]]]
    }
  ]
}
```

- Order: the province first, then districts by ascending id.
- `id`: OSM relation id (Long). `level`: 1 = province (il), 2 = district (ilçe).
- `parent_id`: province id for districts, null for the province.
- `name`, `name_tr`, `name_en`, `iso3166_2`: OSM tags `name`, `name:tr`,
  `name:en`, `ISO3166-2`; null when the tag is missing.
- `length_dm`: total counted length per layer, decimetres (Long).
  `all = major + local`. Completion % = driven `length_dm` / `length_dm` × 100.
- `bbox`: `[west, south, east, north]` of the full-resolution boundary,
  5 decimals.
- `boundary`: simplified boundary (Douglas–Peucker, tolerance 0.0005° ≈ 50 m,
  topology preserved), 5 decimals, GeoJSON MultiPolygon coordinate order:
  `polygons[ rings[ [lon, lat]… ] ]`; the first ring of each polygon is the
  outer ring, the rest are holes; rings are closed (first = last point).
  Polygons sorted by descending area. For display and coarse "which region
  am I in" checks only; segment membership is already decided in the tiles.

## 5. Tile files

### 5.1 Tiling

- Standard XYZ (Web Mercator / "slippy map") tiles at zoom `tile_zoom`:
  `x = floor((lon + 180) / 360 · 2^z)`,
  `y = floor((1 − asinh(tan φ) / π) / 2 · 2^z)`.
- A segment is stored in **every** tile its polyline intersects (touching a
  tile edge counts; decided on the full-precision OSM coordinates),
  completely (full geometry, not clipped). A reader that loads several tiles must
  de-duplicate by segment id.
- Tiles with no segments are not written. The manifest lists every tile.

### 5.2 Encoding primitives

- `u8`: one byte.
- `uvarint`: unsigned LEB128 (protobuf varint): 7 bits per byte, least
  significant group first, high bit set on all but the last byte. Values fit
  in 64 bits.
- `svarint`: zigzag-encoded signed value as `uvarint`:
  `zz = (v << 1) xor (v >> 63)`; decode `v = (zz ushr 1) xor -(zz and 1)`.

### 5.3 Layout

```
Tile:
  magic          4 bytes   ASCII "RMXT" (0x52 0x4D 0x58 0x54)
  version        u8        1
  zoom           u8
  x              uvarint
  y              uvarint
  coordDecimals  u8        6 in version-1 packs
  regionCount    uvarint
  regionIds      regionCount × uvarint     OSM relation ids, ascending
  segmentCount   uvarint
  segments       segmentCount × Segment    sorted by (wayId, ordinal) ascending

Segment:
  wayIdDelta     uvarint   wayId − previous segment's wayId (first segment: − 0)
  ordinal        uvarint
  classOneway    u8        bits 0–4: road class code, bits 5–6: oneway code, bit 7: 0
  provinceIdx    uvarint   index into regionIds
  districtIdx    uvarint   index into regionIds
  lengthDm       uvarint
  nodeCount      uvarint   n ≥ 2
  firstNodeId    uvarint   OSM node id of the first vertex
  nodeIdDeltas   (n−1) × svarint   nodeId[i] − nodeId[i−1]
  firstLon       svarint   absolute, in 10^-coordDecimals degree
  firstLat       svarint
  coordDeltas    (n−1) × (dLon svarint, dLat svarint)   delta to previous vertex
```

`segmentId = wayId * 4096 + ordinal`. Node ids are given for every vertex, so
a segment can be matched against OSM node/way references (e.g. GPX ground
truth) without coordinates. The file ends exactly after the last segment;
trailing bytes are an error.

### 5.4 Kotlin reading sketch

```kotlin
class Cursor(private val b: ByteArray) {
    var pos = 0
    fun u8(): Int = b[pos++].toInt() and 0xFF
    fun uvarint(): Long {
        var result = 0L; var shift = 0
        while (true) {
            val x = u8()
            result = result or ((x and 0x7F).toLong() shl shift)
            if (x < 0x80) return result
            shift += 7
        }
    }
    fun svarint(): Long { val z = uvarint(); return (z ushr 1) xor -(z and 1) }
}
```

## 6. segments.bin (segment summary index)

Optional file, added within version 1 and announced by `manifest.json` →
`segment_index.path`. It holds, for every distinct segment of the pack, the
facts needed to compute completion percentages (driven `length_dm` per
region and layer) **without opening any tile**. Without it a reader has to
decode all tiles once (Adana: 857 files, seconds on a mid-range phone).
Readers that find no `segment_index` in the manifest build the identical
table from the tiles; the content must equal what the tiles say (tested).

Encoding primitives as in section 5.2.

```
Index:
  magic          4 bytes   ASCII "RMXS" (0x52 0x4D 0x58 0x53)
  version        u8        1
  regionCount    uvarint
  regionIds      regionCount × uvarint     OSM relation ids, ascending
  segmentCount   uvarint                   = manifest segment_count
  segments       segmentCount × Record     sorted by segmentId, strictly ascending

Record:
  idDelta        uvarint   segmentId − previous record's segmentId (first record: − 0)
  lengthDm       uvarint   same value as in the tile
  roadClass      u8        road class code (section 2.3); layer = 0 if < 10 else 1
  provinceIdx    uvarint   index into regionIds
  districtIdx    uvarint   index into regionIds
```

`segmentId = wayId * 4096 + ordinal` (section 2.2). Because records are
sorted by id, a reader decodes the file once into parallel arrays and then
looks segments up by binary search. The file ends exactly after the last
record; trailing bytes are an error. A reader rejects an index whose
`segmentCount` differs from the manifest's `segment_count`.

Size on Adana: 604 543 bytes (92 597 records, about 6.5 bytes each; +13 % on the 4.66 MB pack). Decoding it takes
about 4 ms on a desktop JVM versus about 450 ms for the tile scan. The index has no geometry and no tile
reference; reading geometry still goes through the tiles.

## 7. Why this format and zoom

Measured on the Adana pack (Geofabrik `turkey-261115.osm.pbf`, OSM data of
2026-11-15T21:22:28Z, 92 597 segments, 14 210 km). Reproduce with
`--measure`. "JSON" is the same data as compact JSON arrays (`tile_to_json_obj`).

| zoom | coord decimals | tiles | binary total | largest tile | median tile | JSON raw | JSON gzip | stored segment copies |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 12 | 6 | 256 | 4.20 MB | 610 KB | 6.8 KB | 18.8 MB | 5.15 MB | 94 533 |
| 12 | 7 | 256 | 4.63 MB | 674 KB | 7.5 KB | 19.9 MB | 5.66 MB | 94 533 |
| **13** | **6** | **857** | **4.55 MB** | **226 KB** | **2.2 KB** | 20.6 MB | 5.67 MB | 96 799 |
| 13 | 7 | 857 | 5.02 MB | 250 KB | 2.4 KB | 21.8 MB | 6.25 MB | 96 799 |
| 14 | 6 | 2 590 | 5.29 MB | 77 KB | 0.9 KB | 24.4 MB | 6.83 MB | 101 226 |
| 14 | 7 | 2 590 | 5.82 MB | 86 KB | 1.0 KB | 25.8 MB | 7.55 MB | 101 226 |

(MB = 10^6 bytes.)

- **Binary, not JSON.** The varint binary is 4.5× smaller than plain JSON and
  still ~20 % smaller than gzip-compressed JSON, which the app could not even
  open without a zlib decoder. Parsing it is a ~60-line loop over a
  `ByteArray` (section 5.4), no allocation of intermediate strings, no
  serialization library. Region metadata stays JSON (`regions.json`, 93 KB)
  because it is small, read once and handy to inspect.
- **Zoom 13.** z12 is 8 % smaller in total but its city-centre tile is
  610 KB, so matching any drive in central Seyhan would parse 0.6 MB per
  tile. z14 cuts the largest tile to 77 KB but triples the file count
  (2 590) and adds 16 % for segments duplicated across tile edges. z13 keeps
  the largest tile at 226 KB (one z13 tile ≈ 4.9 km × 3.9 km at 37° N),
  857 files, 4.6 MB for the province.
- **1e-6 degree.** ~0.1 m is far below GPS noise (3–10 m); 1e-7 would add
  10 % for nothing. OSM node ids are kept for every vertex, so exact
  topology is never lost to rounding.

## 8. Changing the format

- Additive JSON fields and additive optional files (like `segments.bin`, announced by a manifest field): allowed within version 1.
- Anything else (tile layout, class or oneway codes, id rule, length unit,
  assignment rule): bump `format_version` and the tile version byte, update
  this document, the reference reader and the fixture pack together.
