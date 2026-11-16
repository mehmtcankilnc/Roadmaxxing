"""GPX 1.1 writing and reading.

Speed and course go into the Garmin TrackPointExtension v2 (gpxtpx:speed in m/s,
gpxtpx:course in degrees clockwise from true north), because GPX 1.1 itself has no
speed/course elements.
"""
from __future__ import annotations

import datetime as dt
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from typing import Sequence
from xml.sax.saxutils import escape

from .profile import Sample

GPX_NS = "http://www.topografix.com/GPX/1/1"
TPX_NS = "http://www.garmin.com/xmlschemas/TrackPointExtension/v2"
XSI_NS = "http://www.w3.org/2001/XMLSchema-instance"
SCHEMA_LOCATION = (f"{GPX_NS} http://www.topografix.com/GPX/1/1/gpx.xsd "
                   f"{TPX_NS} https://www8.garmin.com/xmlschemas/TrackPointExtensionv2.xsd")


def iso(t: dt.datetime) -> str:
    return t.astimezone(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_iso(s: str) -> dt.datetime:
    return dt.datetime.strptime(s, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=dt.timezone.utc)


def write_gpx(path: str, samples: Sequence[Sample], start: dt.datetime, name: str,
              desc: str) -> None:
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        f'<gpx version="1.1" creator="tools/gpx/generate.py" xmlns="{GPX_NS}" '
        f'xmlns:gpxtpx="{TPX_NS}" xmlns:xsi="{XSI_NS}" xsi:schemaLocation="{SCHEMA_LOCATION}">',
        "  <metadata>",
        f"    <name>{escape(name)}</name>",
        f"    <desc>{escape(desc)}</desc>",
        "    <copyright author=\"OpenStreetMap contributors\"><license>"
        "https://opendatacommons.org/licenses/odbl/</license></copyright>",
        f"    <time>{iso(start)}</time>",
        "  </metadata>",
        "  <trk>",
        f"    <name>{escape(name)}</name>",
        "    <trkseg>",
    ]
    for s in samples:
        t = start + dt.timedelta(seconds=s.t)
        lines.append(
            f'      <trkpt lat="{s.lat:.7f}" lon="{s.lon:.7f}"><time>{iso(t)}</time>'
            f"<extensions><gpxtpx:TrackPointExtension><gpxtpx:speed>{s.speed:.2f}</gpxtpx:speed>"
            f"<gpxtpx:course>{s.course:.1f}</gpxtpx:course></gpxtpx:TrackPointExtension>"
            f"</extensions></trkpt>")
    lines += ["    </trkseg>", "  </trk>", "</gpx>", ""]
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines))


@dataclass
class TrackPoint:
    time: dt.datetime
    lat: float
    lon: float
    speed: float | None
    course: float | None


def read_gpx(path: str) -> list[TrackPoint]:
    root = ET.parse(path).getroot()
    ns = {"g": GPX_NS, "x": TPX_NS}
    out: list[TrackPoint] = []
    for pt in root.iterfind("g:trk/g:trkseg/g:trkpt", ns):
        sp = pt.find("g:extensions/x:TrackPointExtension/x:speed", ns)
        co = pt.find("g:extensions/x:TrackPointExtension/x:course", ns)
        out.append(TrackPoint(
            parse_iso(pt.findtext("g:time", namespaces=ns)),
            float(pt.get("lat")), float(pt.get("lon")),
            float(sp.text) if sp is not None else None,
            float(co.text) if co is not None else None))
    return out
