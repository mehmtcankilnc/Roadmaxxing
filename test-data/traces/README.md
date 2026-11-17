# Recorded traces

Location traces recorded by the app's real recording pipeline. Tests replay them through the matcher
as regression fixtures. Unlike `test-data/gpx/` they are not generated, so do not regenerate them;
add a new file instead.

## main-roads.emulator-pixel8.csv

- **Source:** `test-data/gpx/main-roads.gpx` (clean) replayed with `tools/gpx/replay.py` on the
  `Pixel_8` Android emulator (`adb emu geo fix`, real time, 2026-11-16). It was recorded by the
  Android background recording pipeline (Phase 0 package B2) and exported from its local database.
- **Content:** only the replayed synthetic GPX positions. There is no real person's location, device
  id or account data. Every point lies on the `main-roads.gpx` track (the test checks <= 5 m).
  Timestamps are the emulator's wall clock at recording time.
- **Columns:** `time_ms` (epoch ms), `lat`, `lon` (WGS84 degrees), `speed_mps`,
  `bearing_deg` (empty when the platform reported none), `accuracy_m`. These are the fields of
  `TracePoint`.
- **Why it exists:**
  1. The emulator reports bogus course values (0, stale or unrelated to the motion). An earlier
     matcher trusted them and found only 180 of 205 segments. The matcher now ignores a reported
     course that disagrees with the motion by more than 45 deg.
  2. At the D400 interchange, two same-direction oneway roads ~7 m apart split. With a high
     per-junction penalty the matcher stayed on the parallel road with fewer junctions, losing 16
     segments even on exact positions. This happened when accuracy was absent.
  3. The emulator GPS HAL drops ~18 % of the 1 Hz fixes (gaps of 1-4 s).
- **Expected:** all 205 segments required by `main-roads.expected.json` are found and there are 0
  false segments, both with the recorded accuracy and with accuracy removed
  (`EmulatorTraceRegressionTest`).
