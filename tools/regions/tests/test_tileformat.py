import pytest

from roadmaxxing_regions.tileformat import (
    Cursor, Tile, TileSegment, decode_tile, encode_tile, put_svarint, put_uvarint, quantize,
    unzigzag, zigzag,
)


@pytest.mark.parametrize("v", [0, 1, 127, 128, 300, 2**35, 2**62])
def test_uvarint_roundtrip(v):
    buf = bytearray()
    put_uvarint(buf, v)
    assert Cursor(bytes(buf)).uvarint() == v


@pytest.mark.parametrize("v", [0, 1, -1, 63, -64, 64, -65, 10**9, -(10**9)])
def test_svarint_roundtrip(v):
    buf = bytearray()
    put_svarint(buf, v)
    assert Cursor(bytes(buf)).svarint() == v
    assert unzigzag(zigzag(v)) == v


def test_varint_known_bytes():
    buf = bytearray()
    put_uvarint(buf, 300)
    assert bytes(buf) == b"\xac\x02"
    buf = bytearray()
    put_svarint(buf, -2)
    assert bytes(buf) == b"\x03"


def test_quantize_round_half_up():
    assert quantize(350000004, 6) == 35000000
    assert quantize(350000005, 6) == 35000001
    assert quantize(-350000005, 6) == -35000000
    assert quantize(-350000006, 6) == -35000001
    assert quantize(123, 7) == 123


def test_tile_roundtrip():
    segs = (
        TileSegment(900000000, 3, 10, 2, 223, 5001, 12345, (12000000000, 11999999990, 12000000500),
                    ((35123456, 37000001), (35123400, 37000100), (35124000, 36999000))),
        TileSegment(5, 0, 0, 1, 223, 5002, 1, (1, 2), ((0, 0), (-1, -1))),
    )
    t = Tile(13, 4891, 3187, 6, segs)
    data = encode_tile(t)
    assert data[:4] == b"RMXT" and data[4] == 1
    back = decode_tile(data)
    assert back.zoom == 13 and back.x == 4891 and back.y == 3187 and back.coord_decimals == 6
    # encoder sorts by (way id, ordinal)
    assert back.segments == tuple(sorted(segs, key=lambda s: (s.way_id, s.ordinal)))


def test_decode_rejects_garbage():
    with pytest.raises(ValueError):
        decode_tile(b"XXXX\x01")
    data = encode_tile(Tile(12, 1, 2, 6, ()))
    with pytest.raises(ValueError):
        decode_tile(data + b"\x00")
