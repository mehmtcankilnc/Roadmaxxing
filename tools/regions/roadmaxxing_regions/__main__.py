"""CLI: python -m roadmaxxing_regions --province Adana

Downloads (and caches) the Geofabrik extract, builds the region pack and
writes it to test-data/regions/<pack-id>/ unless --out is given.
"""
from __future__ import annotations

import argparse
import json
import sys
import urllib.request
from pathlib import Path

from . import build

HERE = Path(__file__).resolve().parent.parent          # tools/regions
REPO = HERE.parent.parent                               # repository root
DEFAULT_URL = "https://download.geofabrik.de/europe/turkey-latest.osm.pbf"
DEFAULT_CACHE = HERE / ".cache"


def resolve_latest(url: str) -> str:
    """Geofabrik '-latest' redirects to a dated file name; return the final URL."""
    req = urllib.request.Request(url, method="HEAD", headers={"User-Agent": "roadmaxxing-regions"})
    with urllib.request.urlopen(req) as resp:
        return resp.geturl()


def download(url: str, cache: Path) -> tuple[Path, str]:
    """Download into the cache once, keyed by the (dated) file name.

    Returns (local path, dated source URL). The dated name pins the input, so
    reruns on the same day reuse the same file and produce identical output.
    """
    final = resolve_latest(url)
    target = cache / final.rsplit("/", 1)[-1]
    if target.exists():
        print(f"using cached {target.name}")
        return target, final
    cache.mkdir(parents=True, exist_ok=True)
    part = target.with_suffix(target.suffix + ".part")
    print(f"downloading {final}")
    req = urllib.request.Request(final, headers={"User-Agent": "roadmaxxing-regions"})
    with urllib.request.urlopen(req) as resp, open(part, "wb") as f:
        while chunk := resp.read(1 << 20):
            f.write(chunk)
    part.replace(target)
    return target, final


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="roadmaxxing_regions", description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--province", default="Adana",
                    help="admin_level=4 relation: name, name:tr, name:en, ISO3166-2 or r<relation id>")
    ap.add_argument("--input", type=Path, help="local .osm.pbf/.osm file (skips the download)")
    ap.add_argument("--source-url", help="with --input: dated download URL to record in manifest.json (default null)")
    ap.add_argument("--url", default=DEFAULT_URL, help="extract to download when --input is not given")
    ap.add_argument("--cache", type=Path, default=DEFAULT_CACHE)
    ap.add_argument("--out", type=Path, help="output directory (default test-data/regions/<pack-id>)")
    ap.add_argument("--pack-id", help="pack id (default: ascii slug of the province name)")
    ap.add_argument("--zoom", type=int, default=build.DEFAULT_ZOOM)
    ap.add_argument("--coord-decimals", type=int, default=build.DEFAULT_COORD_DECIMALS, choices=(6, 7))
    ap.add_argument("--measure", action="store_true",
                    help="also print pack size per zoom / precision / encoding")
    args = ap.parse_args(argv)

    if args.input:
        src = args.input
        source_url = args.source_url
    else:
        src, source_url = download(args.url, args.cache)

    res = build.extract(str(src), args.province)
    pack_id = args.pack_id or build._ascii_slug(res.province_tags.get("name:en")
                                                or res.province_tags.get("name", "region"))
    out = args.out or (REPO / "test-data" / "regions" / pack_id)
    source = {
        "file": src.name,
        "url": source_url,
        "md5": build.file_md5(str(src)),
        "osm_replication_timestamp": build.osmread.replication_timestamp(str(src)),
    }
    manifest = build.write_pack(res, out, pack_id, source, args.zoom, args.coord_decimals)
    print(f"wrote {out}: {manifest['segment_count']} segments, {len(manifest['tiles'])} tiles, "
          f"{sum(t[3] for t in manifest['tiles'])} tile bytes")
    if args.measure:
        print(json.dumps(build.measure(res), indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
