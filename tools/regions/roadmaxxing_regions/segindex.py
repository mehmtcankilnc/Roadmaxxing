"""Segment summary index (segments.bin, see FORMAT.md section 6): writer and reference reader.

One record per distinct segment, geometry-free, sorted by segment id. It lets a
reader compute completion percentages without opening any tile.
Keep in sync with FORMAT.md.
"""
from __future__ import annotations

from dataclasses import dataclass

from .tileformat import Cursor, put_uvarint

MAGIC = b"RMXS"
VERSION = 1
FILE_NAME = "segments.bin"


@dataclass(frozen=True)
class SegmentSummary:
    segment_id: int      # wayId * 4096 + ordinal
    length_dm: int
    road_class: int
    province_id: int
    district_id: int


def encode_index(summaries: list[SegmentSummary]) -> bytes:
    rows = sorted(summaries, key=lambda s: s.segment_id)
    for a, b in zip(rows, rows[1:]):
        if a.segment_id == b.segment_id:
            raise ValueError(f"duplicate segment id {a.segment_id}")
    region_ids = sorted({s.province_id for s in rows} | {s.district_id for s in rows})
    idx = {r: i for i, r in enumerate(region_ids)}
    buf = bytearray(MAGIC)
    buf.append(VERSION)
    put_uvarint(buf, len(region_ids))
    for r in region_ids:
        put_uvarint(buf, r)
    put_uvarint(buf, len(rows))
    prev = 0
    for s in rows:
        put_uvarint(buf, s.segment_id - prev)
        prev = s.segment_id
        put_uvarint(buf, s.length_dm)
        buf.append(s.road_class)
        put_uvarint(buf, idx[s.province_id])
        put_uvarint(buf, idx[s.district_id])
    return bytes(buf)


def decode_index(data: bytes) -> list[SegmentSummary]:
    c = Cursor(data)
    if c.raw(4) != MAGIC:
        raise ValueError("not a Roadmaxxing segment index")
    version = c.u8()
    if version != VERSION:
        raise ValueError(f"unsupported segment index version {version}")
    region_ids = [c.uvarint() for _ in range(c.uvarint())]
    out: list[SegmentSummary] = []
    sid = 0
    for _ in range(c.uvarint()):
        sid += c.uvarint()
        length = c.uvarint()
        cls = c.u8()
        out.append(SegmentSummary(sid, length, cls, region_ids[c.uvarint()], region_ids[c.uvarint()]))
    if c.pos != len(data):
        raise ValueError("trailing bytes in segment index")
    return out
