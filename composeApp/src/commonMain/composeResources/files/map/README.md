# Map style

`roadmaxxing_dark.json` is derived from the OpenFreeMap "Dark" style
(`https://tiles.openfreemap.org/styles/dark`) and recoloured with the design
tokens from `docs/DESIGN.md`. Tiles, glyphs and the sprite are still loaded from
OpenFreeMap (no API key); only the style document is bundled in the app.
It is loaded through `Res.getUri("files/map/roadmaxxing_dark.json")`
(`file:///android_asset/...` on Android, the app bundle on iOS).

## What differs from the upstream Dark style

| Layers | Change |
| --- | --- |
| `background` | `#0B0B0D` (`background` token) |
| `water`, `waterway` | `#111317`, barely lighter than the background |
| `landcover_wood`, `landuse_park` | `#101310`; `wood-pattern` fill removed |
| `landcover_ice_shelf`, `landcover_glacier`, `landuse_residential` | `#0B0B0D` (invisible) |
| `building` | fill `#101114`, outline `#17181C` |
| `highway_minor`, `highway_major_inner`, `highway_major_subtle`, `highway_motorway_inner`, `highway_motorway_subtle` | `#2C2F36` (`roadUnexplored`), opacity override removed |
| `highway_major_casing`, `highway_motorway_casing` | `#16171B`, dash array removed |
| `highway_path` | `#1E2026` |
| `railway*` | `#1E2026`, dash lines `#0B0B0D` |
| `aeroway*`, `road_pier`, `road_area_pier` | very dark greys |
| `boundary_*` | `#2C2F36` |
| all label layers (`symbol` with `text-field`) | text `#8A8D94` (`textSecondary`), halo `rgba(11,11,13,0.92)` 1.2 px |
| one-way arrows | icon opacity lowered |
| `openmaptiles` source | explicit `attribution` (OpenFreeMap, OpenMapTiles, OpenStreetMap) |

Layer ids, filters, sources, sprite and glyph URLs are unchanged. The unused
`ne2_shaded` raster source is kept as in the original.

Driven roads (`primary #C8FF3D`) are not part of this file; they are added at
runtime by `ExplorationMap` as GeoJSON layers above the base style.

## Attribution

Shown permanently by `ExplorationMap`: "OpenFreeMap © OpenMapTiles Data from
OpenStreetMap" (string `map_attribution`).
