import pytest

from roadmaxxing_regions.segments import (
    SEGMENT_ID_FACTOR, Way, dedupe_refs, junction_nodes, segment_id, split_way, split_ways,
)


def way(wid, refs, cls=10, ow=0):
    return Way(wid, tuple(refs), cls, ow)


def test_dedupe_refs():
    assert dedupe_refs([1, 1, 2, 3, 3, 3, 4]) == (1, 2, 3, 4)
    assert dedupe_refs([1, 2, 1]) == (1, 2, 1)


def test_no_junction_single_segment():
    w = way(10, [1, 2, 3, 4])
    segs = split_way(w, junction_nodes([w]))
    assert [s.node_ids for s in segs] == [(1, 2, 3, 4)]
    assert segs[0].ordinal == 0


def test_split_at_crossing():
    a = way(10, [1, 2, 3, 4, 5])
    b = way(20, [6, 3, 7])          # crosses a at node 3
    segs = split_ways([a, b])
    assert [(s.way_id, s.ordinal, s.node_ids) for s in segs] == [
        (10, 0, (1, 2, 3)), (10, 1, (3, 4, 5)),
        (20, 0, (6, 3)), (20, 1, (3, 7)),
    ]


def test_t_junction_splits_only_the_through_way():
    a = way(10, [1, 2, 3])
    b = way(20, [2, 9])             # starts on a's interior node
    segs = split_ways([a, b])
    assert [s.node_ids for s in segs if s.way_id == 10] == [(1, 2), (2, 3)]
    assert [s.node_ids for s in segs if s.way_id == 20] == [(2, 9)]


def test_self_touching_loop():
    loop = way(10, [1, 2, 3, 4, 1])  # closed way, no other junction
    segs = split_ways([loop])
    assert [s.node_ids for s in segs] == [(1, 2, 3, 4, 1)]


def test_loop_with_branch():
    loop = way(10, [1, 2, 3, 4, 1])
    branch = way(20, [3, 8])
    segs = split_ways([loop, branch])
    assert [s.node_ids for s in segs if s.way_id == 10] == [(1, 2, 3), (3, 4, 1)]


def test_excluded_ways_do_not_split():
    # Only ways passed in (drivable ones) create junctions.
    a = way(10, [1, 2, 3])
    assert [s.node_ids for s in split_ways([a])] == [(1, 2, 3)]


def test_segment_id_rule():
    assert segment_id(123, 0) == 123 * 4096
    assert segment_id(123, 5) == 123 * 4096 + 5
    assert SEGMENT_ID_FACTOR == 4096
    with pytest.raises(ValueError):
        segment_id(1, 4096)


def test_ids_stable_regardless_of_input_order_and_unrelated_ways():
    a = way(10, [1, 2, 3, 4, 5])
    b = way(20, [6, 3, 7])
    far = way(30, [100, 101, 102])
    ids1 = [s.id for s in split_ways([a, b])]
    ids2 = [s.id for s in split_ways([far, b, a]) if s.way_id != 30]
    assert ids1 == ids2


def test_ordinals_cover_whole_way():
    a = way(10, [1, 2, 3, 4, 5, 6])
    others = [way(20, [2, 50]), way(21, [4, 51])]
    segs = [s for s in split_ways([a] + others) if s.way_id == 10]
    assert [s.ordinal for s in segs] == [0, 1, 2]
    # consecutive segments share their end/start node
    for p, q in zip(segs, segs[1:]):
        assert p.node_ids[-1] == q.node_ids[0]
