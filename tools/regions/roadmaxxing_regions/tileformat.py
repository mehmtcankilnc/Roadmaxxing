"""Binary tile format v1 (see FORMAT.md): writer and reference reader.

The reader exists so tests can round-trip every tile and so FORMAT.md has an
executable reference next to it. Keep both in sync with FORMAT.md.
"""
from __future__ import annotations

from dataclasses import dataclass

MAGIC = b"RMXT"
FORMAT_VERSION = 1


@dataclass(frozen=True)
class TileSegment:
    way_id: int
    ordinal: int
    road_class: int
    oneway: int
    province_id: int
    district_id: int
    length_dm: int
    node_ids: tuple[int, ...]
    coords: tuple[tuple[int, int], ...]  # (lon, lat) in tile coordinate units


@dataclass(frozen=True)
class Tile:
    zoom: int
    x: int
    y: int
    coord_decimals: int
    segments: tuple[TileSegment, ...]


# --- varints -----------------------------------------------------------------

def put_uvarint(buf: bytearray, v: int) -> None:
    if v < 0:
        raise ValueError(f"uvarint must be >= 0, got {v}")
    while v >= 0x80:
        buf.append((v & 0x7F) | 0x80)
        v >>= 7
    buf.append(v)


def zigzag(v: int) -> int:
    return (v << 1) if v >= 0 else ((-v) << 1) - 1


def unzigzag(u: int) -> int:
    return (u >> 1) if (u & 1) == 0 else -((u + 1) >> 1)


def put_svarint(buf: bytearray, v: int) -> None:
    put_uvarint(buf, zigzag(v))


class Cursor:
    def __init__(self, data: bytes):
        self.data = data
        self.pos = 0

    def u8(self) -> int:
        b = self.data[self.pos]
        self.pos += 1
        return b

    def uvarint(self) -> int:
        result = 0
        shift = 0
        while True:
            b = self.data[self.pos]
            self.pos += 1
            result |= (b & 0x7F) << shift
            if b < 0x80:
                return result
            shift += 7
            if shift > 63:
                raise ValueError("varint too long")

    def svarint(self) -> int:
        return unzigzag(self.uvarint())

    def raw(self, n: int) -> bytes:
        out = self.data[self.pos:self.pos + n]
        self.pos += n
        return out


# --- coordinates ---------------------------------------------------------------

def quantize(v7: int, coord_decimals: int) -> int:
    """OSM 1e-7 degree integer -> 10^-coord_decimals degree integer, round half up."""
    if coord_decimals == 7:
        return v7
    div = 10 ** (7 - coord_decimals)
    return (v7 + div // 2) // div


# --- writer ----------------------------------------------------------------------

def encode_tile(tile: Tile) -> bytes:
    segs = sorted(tile.segments, key=lambda s: (s.way_id, s.ordinal))
    region_ids = sorted({s.province_id for s in segs} | {s.district_id for s in segs})
    region_index = {r: i for i, r in enumerate(region_ids)}

    buf = bytearray(MAGIC)
    buf.append(FORMAT_VERSION)
    buf.append(tile.zoom)
    put_uvarint(buf, tile.x)
    put_uvarint(buf, tile.y)
    buf.append(tile.coord_decimals)
    put_uvarint(buf, len(region_ids))
    for r in region_ids:
        put_uvarint(buf, r)
    put_uvarint(buf, len(segs))
    prev_way = 0
    for s in segs:
        if len(s.node_ids) < 2 or len(s.node_ids) != len(s.coords):
            raise ValueError(f"bad segment {s.way_id}/{s.ordinal}")
        put_uvarint(buf, s.way_id - prev_way)
        prev_way = s.way_id
        put_uvarint(buf, s.ordinal)
        buf.append((s.road_class & 0x1F) | ((s.oneway & 0x03) << 5))
        put_uvarint(buf, region_index[s.province_id])
        put_uvarint(buf, region_index[s.district_id])
        put_uvarint(buf, s.length_dm)
        put_uvarint(buf, len(s.node_ids))
        put_uvarint(buf, s.node_ids[0])
        for i in range(1, len(s.node_ids)):
            put_svarint(buf, s.node_ids[i] - s.node_ids[i - 1])
        put_svarint(buf, s.coords[0][0])
        put_svarint(buf, s.coords[0][1])
        for i in range(1, len(s.coords)):
            put_svarint(buf, s.coords[i][0] - s.coords[i - 1][0])
            put_svarint(buf, s.coords[i][1] - s.coords[i - 1][1])
    return bytes(buf)


# --- reference reader --------------------------------------------------------------

def decode_tile(data: bytes) -> Tile:
    c = Cursor(data)
    if c.raw(4) != MAGIC:
        raise ValueError("not a Roadmaxxing tile")
    version = c.u8()
    if version != FORMAT_VERSION:
        raise ValueError(f"unsupported tile version {version}")
    zoom = c.u8()
    x = c.uvarint()
    y = c.uvarint()
    coord_decimals = c.u8()
    region_ids = [c.uvarint() for _ in range(c.uvarint())]
    segs: list[TileSegment] = []
    way_id = 0
    for _ in range(c.uvarint()):
        way_id += c.uvarint()
        ordinal = c.uvarint()
        packed = c.u8()
        province = region_ids[c.uvarint()]
        district = region_ids[c.uvarint()]
        length_dm = c.uvarint()
        n = c.uvarint()
        nodes = [c.uvarint()]
        for _ in range(n - 1):
            nodes.append(nodes[-1] + c.svarint())
        lon = c.svarint()
        lat = c.svarint()
        coords = [(lon, lat)]
        for _ in range(n - 1):
            lon += c.svarint()
            lat += c.svarint()
            coords.append((lon, lat))
        segs.append(TileSegment(way_id, ordinal, packed & 0x1F, (packed >> 5) & 0x03,
                                province, district, length_dm, tuple(nodes), tuple(coords)))
    if c.pos != len(data):
        raise ValueError("trailing bytes in tile")
    return Tile(zoom, x, y, coord_decimals, tuple(segs))


def tile_to_json_obj(tile: Tile) -> dict:
    """Equivalent JSON representation; only used to measure the JSON alternative."""
    return {
        "z": tile.zoom, "x": tile.x, "y": tile.y, "d": tile.coord_decimals,
        "s": [[s.way_id, s.ordinal, s.road_class, s.oneway, s.province_id, s.district_id,
               s.length_dm, list(s.node_ids), [c for xy in s.coords for c in xy]]
              for s in sorted(tile.segments, key=lambda s: (s.way_id, s.ordinal))],
    }
