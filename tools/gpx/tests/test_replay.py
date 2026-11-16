import datetime as dt

import replay
from gpxlib.gpxio import TrackPoint

T = dt.datetime(2026, 11, 2, 8, 0, 0, tzinfo=dt.timezone.utc)


def test_nmea_sentences_have_valid_checksums_and_fields():
    p = TrackPoint(T, 37.0012345, 35.3254321, 10.0, 123.4)
    gga, rmc = replay.nmea_sentences(p, T)
    for s in (gga, rmc):
        body, check = s[1:].split("*")
        c = 0
        for ch in body:
            c ^= ord(ch)
        assert f"{c:02X}" == check
    f = rmc[1:].split("*")[0].split(",")
    assert f[0] == "GPRMC" and f[2] == "A"
    assert f[3] == "3700.0741" and f[4] == "N"
    assert f[5] == "03519.5259" and f[6] == "E"
    assert abs(float(f[7]) - 10.0 / 0.514444) < 0.01  # knots
    assert f[8] == "123.4" and f[9] == "021126"


def test_fix_args_send_speed_in_knots():
    p = TrackPoint(T, 37.0, 35.3, 5.144444, 0.0)
    assert replay.fix_args(p) == ["geo", "fix", "35.3000000", "37.0000000", "30", "10", "10.00"]


def test_dry_run_sends_one_command_per_point(capsys):
    pts = [TrackPoint(T + dt.timedelta(seconds=i), 37.0, 35.3 + i * 1e-4, 8.0, 90.0) for i in range(5)]
    n = replay.replay(pts, ["adb", "-s", "emulator-5554"], speed=4, method="fix", dry_run=True,
                      verbose=False)
    out = capsys.readouterr().out.strip().splitlines()
    assert n == 5 and len(out) == 5
    assert out[0].startswith("adb -s emulator-5554 emu geo fix 35.3000000 37.0000000")
