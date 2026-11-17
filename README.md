# Roadmaxxing

Every road you drive turns green on your map.

Roadmaxxing is a drive-tracking app for iOS and Android that turns your city, country and the world into a map you
complete, like the map-completion screen in a driving game. The main output is a single number, for example
"You have explored 12.4 % of Adana", and a shareable card that shows it.

**Core loop**

1. **Record automatically:** the phone detects a drive in the background and records it, no button needed.
2. **Paint the roads:** the GPS trace is matched on the device to OpenStreetMap road segments; driven segments turn green.
3. **Percentage:** completion is computed per province and district (and per road class).
4. **Card:** a card shows the percentage and the drive.
5. **Share:** the card goes to a story or post.

The app keeps a "drive safely" message. It has no feature, badge or text that rewards speed.

> **Status: Phase 0 (technical validation, pilot city Adana).** Steps 1–3 run end to end on the Android emulator.
> Steps 4–5, the map screen and everything server- or account-related are **planned**. iOS code has **never been
> compiled** (see [Known limitations](#known-limitations)).

## MVP features

From [docs/PRD.md](docs/PRD.md) §5. "done (Phase 0)" means the code exists in this repository; "partial" lists what
is in and what is not; everything else is "planned".

| # | Feature | Status | What exists today |
| --- | --- | --- | --- |
| 1 | Automatic drive detection and recording | partial (Phase 0, Android only) | Android: low-power idle triggers (Activity Recognition transitions + stationary geofence, no GPS while idle), short GPS probe, location foreground service, drive start/end state machine, walk/train rejection by speed and road-match filters, long traffic stops do not split a drive. Debug-build start/stop buttons. **Planned:** iOS recording (only a placeholder that does nothing), user-facing manual start/stop, plane filter beyond the 250 km/h speed cap. |
| 2 | Road matching (map matching) | done (Phase 0) | Pure Kotlin matcher in `shared` against the offline Adana region pack; driven segments are stored in SQLite. |
| 3 | Exploration map | planned | No map screen yet (MapLibre Compose / OpenFreeMap not added). |
| 4 | Completion percentages | partial (Phase 0) | Province and district percentages for Adana, split into major roads and local roads, shown on the Phase 0 screen. **Planned:** country level, other provinces. |
| 5 | Drive summary | partial (Phase 0) | Per drive: distance, duration, new km, drive / not-a-drive verdict. **Planned:** average and max speed display, route view. |
| 6 | Share card | planned | – |
| 7 | Onboarding and permissions | partial (Phase 0) | Permission rows (location, background location, physical activity, notifications) on the Phase 0 screen. **Planned:** value onboarding, safe-driving consent, card-by-card permission flow. |
| 8 | Account and sync | planned | No Firebase. Data stays in the local database. |
| 9 | Subscription and paywall | planned | No RevenueCat. |
| 10 | Settings | planned | – |

## Tech stack

### In use

Versions from [gradle/libs.versions.toml](gradle/libs.versions.toml), [tools/regions/requirements.txt](tools/regions/requirements.txt)
and [tools/gpx/requirements.txt](tools/gpx/requirements.txt). All versions are pinned exactly.

| Area | Library / tool | Version |
| --- | --- | --- |
| Language | Kotlin Multiplatform | 2.4.21 |
| Build | Gradle (wrapper) / Android Gradle Plugin | 9.7.1 / 9.3.3 |
| UI | Compose Multiplatform | 1.12.2 |
| UI | Compose Material 3 / Material 3 Adaptive | 1.12.0-alpha03 / 1.3.0 |
| Architecture | JetBrains Lifecycle (ViewModel, MVI with StateFlow) | 2.11.0 |
| Android host | androidx.activity:activity-compose | 1.13.0 |
| Concurrency | kotlinx-coroutines | 1.11.0 |
| Serialization / time | kotlinx-serialization-json / kotlinx-datetime | 1.13.0 / 0.8.0 |
| DI | Koin | 4.2.2 |
| Local database | SQLDelight | 2.4.1 |
| Android location | Google Play services location (fused location, activity transitions, geofencing) | 21.4.0 |
| Android SDK | compileSdk / targetSdk / minSdk | 37 / 36 / 26 |
| JVM target | Java | 17 |
| Region pack builder | Python, pyosmium (`osmium`), `shapely`, `pytest` | 3.12, 4.0.2, 2.1.2, 8.4.2 |
| GPX tools | Python standard library + `pytest` | 3.12, 8.4.2 |

### Planned (not in the code yet)

Firebase Auth (GitLive `firebase-kotlin-sdk`), Firestore (Spark plan) for user data, RevenueCat (`purchases-kmp`),
PostHog (`posthog-kmp`), MapLibre Compose with OpenFreeMap tiles, Ktor client, region packs hosted on Cloudflare R2
and built by GitHub Actions. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Repository layout

```
.
├── androidApp/            Android app shell: MainActivity, Application, manifest
│   └── src/debug/         debug-only manifest (emulator GPS helper receiver)
├── composeApp/            shared Compose UI, navigation, DI
│   └── src/
│       ├── commonMain/    UI (MVI), theme, strings (composeResources/values, values-tr)
│       ├── androidMain/   tracking: foreground service, idle triggers, debug emulator helper
│       └── iosMain/       iOS entry point and placeholder platform code (never compiled)
├── shared/                domain, data and the matching engine (pure Kotlin)
│   └── src/
│       ├── commonMain/    drive detection, matching, progress, region pack reader, SQLDelight schema
│       ├── commonTest/    unit tests (fixture region pack)
│       ├── androidHostTest/  tests against the real Adana pack and GPX routes
│       ├── androidMain/
│       └── iosMain/
├── iosApp/                Xcode project (SwiftUI host for the Compose UI)
├── tools/
│   ├── regions/           region pack builder (Python, OSM → test-data/regions/<id>)
│   └── gpx/               GPX route generator and emulator replay
├── test-data/
│   ├── regions/adana/     Adana region pack (embedded in the Android APK at build time)
│   ├── regions/fixture/   small fixture pack for unit tests
│   ├── gpx/               Adana test routes (+ noisy variants, expected results)
│   └── traces/            emulator-recorded trace used as a matcher regression fixture
├── docs/                  product, architecture, design, brand, research, decisions, CI reference
└── prompts/               working prompts for the development sessions
```

## Development setup

You need:

- **JDK 17** (the modules target JVM 17).
- **Android SDK** with platform **37** (`compileSdk = 37`), platform-tools (`adb`) and the emulator. Point Gradle at
  the SDK with the `ANDROID_HOME` environment variable or `sdk.dir` in `local.properties` (git-ignored).
- **Python 3.12** for `tools/` (only needed for the tools and their tests).
- Windows, Linux or macOS. iOS builds need macOS and are meant to run only on GitHub Actions (planned, see
  [CI and TestFlight](#ci-and-testflight)).

Commands below use `./gradlew` (Git Bash, Linux, macOS). In PowerShell or cmd use `.\gradlew.bat` instead.

### Run the app on an Android emulator

```bash
emulator -list-avds
```

```bash
emulator -avd Pixel_8
```

```bash
./gradlew :androidApp:assembleDebug
```

```bash
./gradlew :androidApp:installDebug
```

`installDebug` installs on every connected device; use `adb -s <serial> install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk`
to target one emulator. The Adana region pack is copied from `test-data/regions/adana` into the APK assets at build
time (`copyRegionPack` task in [androidApp/build.gradle.kts](androidApp/build.gradle.kts)).

In the app, grant location, then "Allow all the time", physical activity and (optionally) notifications. Interface
changes are checked on a phone (Pixel), a tablet (Pixel Tablet) and a foldable (Pixel Fold) emulator.

### Replay a GPX route on the emulator

[tools/gpx/replay.py](tools/gpx/replay.py) sends each track point as a live GPS fix (`adb emu geo fix`), with
position and speed (course is not carried). Routes and their scenarios are described in
[test-data/gpx/README.md](test-data/gpx/README.md).

```bash
adb devices
```

```bash
python tools/gpx/replay.py test-data/gpx/main-roads.gpx -s emulator-5554
```

```bash
python tools/gpx/replay.py test-data/gpx/main-roads.noisy.gpx -s emulator-5554 --speed 4
```

```bash
python tools/gpx/replay.py test-data/gpx/residential.gpx -s emulator-5554 --limit 3 --dry-run
```

Options: `--speed N` (time acceleration), `--start` / `--limit` (slice of the track), `--method fix|nmea`
(`fix` is the default and the one that works on the Pixel_8 emulator), `--dry-run` (print the `adb` commands only).
Use `--speed 1` for `traffic-stop` and `traffic-stop-long`: the drive-end timeouts are wall-clock based.

### Emulator helpers (debug only)

> **Debug builds only, emulator test workaround.** These do nothing in release builds: the receiver is declared only
> in [androidApp/src/debug/AndroidManifest.xml](androidApp/src/debug/AndroidManifest.xml) and checks that the app is
> debuggable. It is off by default. Source and full explanation:
> [DebugEmulatorGpsHolder.kt](composeApp/src/androidMain/kotlin/app/roadmaxxing/tracking/DebugEmulatorGpsHolder.kt).

Why it exists: the emulator has no Wi-Fi/cell positioning, and its GPS only reports `geo fix` positions while some
app requests GPS. The production idle setup requests no GPS, so Play services never sees the replayed track and the
geofence trigger does not fire. Even with GPS held, Play services throttles geofence checks while its stationary
detector (static sensors on the emulator) reports "stationary".

Hold a platform GPS request (2 s) so Play services sees replayed positions; the production geofence path still
triggers the drive:

```bash
adb shell am broadcast -n app.roadmaxxing/app.roadmaxxing.tracking.DebugGpsHolderReceiver --ez enabled true
```

Fast trigger: a fix at vehicle speed (≥ 6 m/s) while idle starts the probe directly, standing in for the throttled
geofence exit. This needs the app exempt from battery optimisation:

```bash
adb shell am broadcast -n app.roadmaxxing/app.roadmaxxing.tracking.DebugGpsHolderReceiver --ez enabled true --ez fastTrigger true
```

```bash
adb shell dumpsys deviceidle whitelist +app.roadmaxxing
```

Turn the helper off:

```bash
adb shell am broadcast -n app.roadmaxxing/app.roadmaxxing.tracking.DebugGpsHolderReceiver --ez enabled false
```

Also needed on the emulator (lifts background location throttling for the app):

```bash
adb shell settings put global location_background_throttle_package_whitelist app.roadmaxxing
```

The debug build also shows **Start (debug)** / **Stop (debug)** buttons for manual recording.

## Running tests

Kotlin tests of the `shared` module on the JVM (unit tests with the fixture pack, plus accuracy and pipeline tests
against the real Adana pack and the GPX routes; matching targets: recall ≥ 90 %, wrong segments ≤ 5 % per run; a
regression test replays the emulator-recorded trace in `test-data/traces/` through the matcher):

```bash
./gradlew :shared:testAndroidHostTest
```

GPX tool tests (from the repository root):

```bash
python -m pip install -r tools/gpx/requirements.txt
```

```bash
python -m pytest tools/gpx/tests
```

Region pack builder tests (from `tools/regions/`, after the setup in the next section):

```bash
.venv/Scripts/python -m pytest
```

On Linux/macOS the interpreter is `.venv/bin/python`.

iOS simulator tests of `shared` are planned for the CI `build` job; they cannot run on Windows/Linux.

## Rebuilding the region pack

The region pack is every drivable OSM road in one province, split into segments at junctions, with each segment
assigned to its province and district, plus per-region road totals. Format: [tools/regions/FORMAT.md](tools/regions/FORMAT.md);
full guide: [tools/regions/README.md](tools/regions/README.md).

Setup (once, from `tools/regions/`; Windows shown, use `python3.12` and `.venv/bin/python` on Linux/macOS):

```bash
py -3.12 -m venv .venv
```

```bash
.venv/Scripts/python -m pip install -r requirements.txt
```

Build the Adana pack (downloads the Geofabrik Turkey extract into `tools/regions/.cache/` once, ~650 MB; takes a few
minutes and ~2 GB RAM; writes `test-data/regions/adana/`):

```bash
.venv/Scripts/python -m roadmaxxing_regions --province Adana
```

All options:

```bash
.venv/Scripts/python -m roadmaxxing_regions --help
```

The build is reproducible: the same input file gives byte-identical tiles and `regions.json`. `turkey-latest` changes
daily, so to rebuild the committed pack's data exactly, pass the cached dated extract recorded in
[test-data/regions/adana/manifest.json](test-data/regions/adana/manifest.json) with `--input` (the only difference is
that `source.url` in `manifest.json` is then `null`), for example:

```bash
.venv/Scripts/python -m roadmaxxing_regions --province Adana --input .cache/turkey-261115.osm.pbf
```

If you rebuild the pack from a newer extract, regenerate the GPX routes from the same extract (OSM ids can change);
see [test-data/gpx/README.md](test-data/gpx/README.md).

## CI and TestFlight

**Planned. There is no `.github/workflows/` directory yet.**

- The iOS workflow will be written once from the reference [docs/ci/ios.yml](docs/ci/ios.yml); rules are in
  [docs/ci/README.md](docs/ci/README.md).
- `build` job: `shared` tests on the iOS simulator and an unsigned simulator build.
- `testflight` job: runs only on a `v*` tag or a manual run (`workflow_dispatch`), in the GitHub environment
  `testflight`.
- Android CI (build and unit tests on every PR, Play internal track from `main`) is not defined yet.

Required GitHub Actions secrets (names only; values never go into the repository or any output):

| Secret | Job |
| --- | --- |
| `APPSTORE_KEY_ID` | testflight |
| `APPSTORE_ISSUER_ID` | testflight |
| `APPSTORE_API_KEY_P8` | testflight |
| `APPLE_TEAM_ID` | testflight |
| `GOOGLE_SERVICE_INFO_PLIST_BASE64` | build, testflight |
| `REVENUECAT_IOS_API_KEY` | build, testflight |
| `POSTHOG_API_KEY` | build, testflight |

Locally, configuration files and keys live in git-ignored files (`local.properties`, `*.xcconfig`); see
[.gitignore](.gitignore).

## Known limitations

- **iOS is not verified.** `iosMain` code and the Xcode project have never been compiled (no Mac; CI not set up). iOS
  drive recording is a placeholder that reports every permission as missing and does nothing.
- **Background triggers need a real device.** On the emulator the production idle triggers (Activity Recognition,
  Play services geofencing) do not behave like on a phone: GPS is not active without a request and geofence checks are
  throttled by the stationary detector. Emulator tests rely on the debug-only helpers above. Trigger latency, battery
  use and the "app closed" case must be checked on physical Android and iOS devices.
- **No map screen.** Driven roads are not drawn yet; progress is shown as numbers on a Phase 0 status screen.
- **One region.** Only the Adana pack exists and it is embedded in the APK; downloadable packs (Cloudflare R2) are
  planned. No country-level percentage.
- **No account, sync, subscription, analytics or share card.** All data is local.
- **Emulator timing.** The emulator GPS reports at 1 Hz with jitter; repeated replays of the same route can add a few
  extra segments. The first progress computation on a cold emulator takes several seconds.

## Documentation

| Document | Content |
| --- | --- |
| [CLAUDE.md](CLAUDE.md) | Project entry point, fixed decisions, working rules |
| [docs/PRD.md](docs/PRD.md) | Product requirements, MVP scope, Phase 0 acceptance criteria |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Technical architecture, data model, drive processing |
| [docs/DESIGN.md](docs/DESIGN.md) | Design language, tokens, components, screens |
| [docs/BRAND.md](docs/BRAND.md) | Name, store title, domain |
| [docs/RESEARCH.md](docs/RESEARCH.md) | Market validation, competitors |
| [docs/DECISIONS.md](docs/DECISIONS.md) | Decision log (most up to date when documents disagree) |
| [docs/ci/README.md](docs/ci/README.md) | CI rules and secret names |
| [tools/regions/README.md](tools/regions/README.md) | Region pack builder |
| [tools/regions/FORMAT.md](tools/regions/FORMAT.md) | Region pack format |
| [test-data/gpx/README.md](test-data/gpx/README.md) | GPX test routes, generation and replay |
| [test-data/traces/README.md](test-data/traces/README.md) | Recorded traces used as matcher regression fixtures |

Most documents under `docs/` are written in Turkish.

## Map data

Road data © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright), available under the
[Open Database License (ODbL)](https://opendatacommons.org/licenses/odbl/). The region pack in
`test-data/regions/adana/` and the GPX routes are derived from the Geofabrik Turkey extract of OpenStreetMap.
