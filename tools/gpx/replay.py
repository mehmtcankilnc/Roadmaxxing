"""Replay a GPX track into a running Android emulator as live GPS fixes.

Each track point is sent at its own timestamp (1 Hz for the test routes), divided
by --speed. Gaps in the track (dropouts in *.noisy.gpx) are replayed as gaps.

Two transport methods (both through `adb emu`, which handles console auth):
  fix (default):  `geo fix lon lat alt sats velocity` -> position + speed (knots).
                  Verified on the Pixel_8 emulator: position and speed reach the gps
                  provider; bearing does not (bear=0.0).
  nmea:           $GPGGA + $GPRMC sentences (would carry course). On the Pixel_8
                  emulator (2026-11) the console answers OK but the location does not
                  change, even for textbook sentences: kept for other emulator builds.
See test-data/gpx/README.md.

Usage:
    python tools/gpx/replay.py test-data/gpx/main-roads.gpx
    python tools/gpx/replay.py test-data/gpx/main-roads.noisy.gpx --speed 4 -s emulator-5554
"""
from __future__ import annotations

import argparse
import datetime as dt
import os
import shutil
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from gpxlib.gpxio import TrackPoint, read_gpx  # noqa: E402

MS_TO_KNOTS = 1.0 / 0.514444


def _nmea_checksum(body: str) -> str:
    c = 0
    for ch in body:
        c ^= ord(ch)
    return f"{c:02X}"


def _nmea_coord(value: float, is_lat: bool) -> tuple[str, str]:
    hemi = ("N" if value >= 0 else "S") if is_lat else ("E" if value >= 0 else "W")
    v = abs(value)
    deg = int(v)
    minutes = (v - deg) * 60
    text = f"{deg:02d}{minutes:07.4f}" if is_lat else f"{deg:03d}{minutes:07.4f}"
    return text, hemi


def nmea_sentences(p: TrackPoint, when: dt.datetime) -> list[str]:
    """$GPRMC (position, speed, course, date) and $GPGGA (fix quality) for one point."""
    hms = when.strftime("%H%M%S") + ".00"
    dmy = when.strftime("%d%m%y")
    lat, ns = _nmea_coord(p.lat, True)
    lon, ew = _nmea_coord(p.lon, False)
    knots = (p.speed or 0.0) * MS_TO_KNOTS
    course = p.course or 0.0
    rmc = f"GPRMC,{hms},A,{lat},{ns},{lon},{ew},{knots:.2f},{course:.1f},{dmy},,,A"
    gga = f"GPGGA,{hms},{lat},{ns},{lon},{ew},1,10,0.9,30.0,M,0.0,M,,"
    return [f"${s}*{_nmea_checksum(s)}" for s in (gga, rmc)]


def fix_args(p: TrackPoint) -> list[str]:
    knots = (p.speed or 0.0) * MS_TO_KNOTS
    return ["geo", "fix", f"{p.lon:.7f}", f"{p.lat:.7f}", "30", "10", f"{knots:.2f}"]


def adb_base(serial: str | None) -> list[str]:
    adb = shutil.which("adb")
    if not adb:
        raise SystemExit("adb not found on PATH")
    return [adb] + (["-s", serial] if serial else [])


def send(base: list[str], args: list[str], dry_run: bool) -> None:
    cmd = base + ["emu"] + args
    if dry_run:
        print(" ".join(cmd))
        return
    res = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
    out = (res.stdout + res.stderr).strip()
    if res.returncode != 0 or "KO" in out:
        raise RuntimeError(f"adb emu failed ({res.returncode}): {out}")


def replay(points: list[TrackPoint], base: list[str], speed: float, method: str,
           dry_run: bool = False, start_index: int = 0, verbose: bool = True) -> int:
    if not points:
        return 0
    t0 = points[start_index].time
    wall0 = time.monotonic()
    sent = 0
    for i, p in enumerate(points[start_index:], start=start_index):
        due = (p.time - t0).total_seconds() / speed
        if not dry_run:
            wait = due - (time.monotonic() - wall0)
            if wait > 0:
                time.sleep(wait)
        if method == "nmea":
            now = dt.datetime.now(dt.timezone.utc)
            for sentence in nmea_sentences(p, now):
                send(base, ["geo", "nmea", sentence], dry_run)
        else:
            send(base, fix_args(p), dry_run)
        sent += 1
        if verbose and (i % 30 == 0 or i == len(points) - 1):
            kmh = (p.speed or 0) * 3.6
            print(f"[{i + 1}/{len(points)}] t+{(p.time - points[0].time).total_seconds():.0f}s "
                  f"{p.lat:.6f},{p.lon:.6f} {kmh:.0f} km/h", flush=True)
    return sent


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("gpx")
    ap.add_argument("-s", "--serial", help="adb device serial, e.g. emulator-5554")
    ap.add_argument("--speed", type=float, default=1.0, help="time acceleration factor (default 1)")
    ap.add_argument("--method", choices=("fix", "nmea"), default="fix")
    ap.add_argument("--start", type=int, default=0, help="start at this point index")
    ap.add_argument("--limit", type=int, help="replay at most this many points")
    ap.add_argument("--dry-run", action="store_true", help="print adb commands, do not wait")
    args = ap.parse_args(argv)
    if args.speed <= 0:
        ap.error("--speed must be > 0")

    points = read_gpx(args.gpx)
    if args.limit:
        points = points[: args.start + args.limit]
    base = adb_base(args.serial) if not args.dry_run else ["adb"] + (["-s", args.serial] if args.serial else [])
    n = replay(points, base, args.speed, args.method, args.dry_run, args.start)
    print(f"sent {n} fixes from {os.path.basename(args.gpx)} via {args.method} at x{args.speed:g}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
