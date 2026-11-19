# Roadmaxxing

Every road you drive turns green on your map.

Roadmaxxing is a drive-tracking app for iOS and Android that turns your city, country and the world into a map you
complete, like the map-completion screen in a driving game. The main output is a single number, for example
"You have explored 12.4 % of Adana", and a shareable card that shows it.

**Core loop**

1. **Record automatically:** the phone detects a drive in the background and records it, no button needed.
2. **Paint the roads:** the GPS trace is matched on the device to OpenStreetMap road segments; driven segments turn green.
3. **Percentage:** completion is computed per province and district (and per road class).
4. **Card:** after a drive, a drive card (route, distance, duration, speed, new km) and a city card (province
   percentage, major / local road split, completed districts, this week's new km) are rendered on the device as a PNG,
   in 9:16 and 4:5.
5. **Share:** the card goes through the system share sheet (to a story or post from there) or is saved to the gallery.
   There is no direct Instagram Story button yet.

The app keeps a "drive safely" message. It has no feature, badge or text that rewards speed.

> **Status: Phase 0 (technical validation, pilot city Adana).** Steps 1–5 are in the code. Steps 1–3 run end to end
> on the Android emulator, the exploration map (four-tab app shell: Map, Drives, Stats, Profile) draws the driven
> roads there, and the drive summary, the drive and city cards and sharing (system share sheet, save to gallery) were
> verified on the emulator too. Everything server- or account-related is **planned**. iOS has automatic drive
> recording code, the map and the card sharing code that compile and pass the shared tests on GitHub Actions, but they
> have **not been run on any physical device**; Android has not been tried on a physical device either (see
> [Known limitations](#known-limitations)).

## MVP features

From [docs/PRD.md](docs/PRD.md) §5. "done (Phase 0)" means the code exists in this repository; "partial" lists what
is in and what is not; everything else is "planned".

| # | Feature | Status | What exists today |
| --- | --- | --- | --- |
| 1 | Automatic drive detection and recording | partial (Phase 0) | Android: low-power idle triggers (Activity Recognition transitions + stationary geofence, no GPS while idle), short GPS probe, location foreground service, drive start/end state machine, walk/train rejection by speed and road-match filters, long traffic stops do not split a drive. Debug-build start/stop buttons. iOS: idle triggers (CoreLocation significant-location-change + 200 m region monitoring + CoreMotion), no continuous GPS while idle; detection and validation rules come from the shared code. The iOS code compiles and its shared tests pass in CI, but it has **not been verified on a physical device**. **Planned:** user-facing manual start/stop, plane filter beyond the 250 km/h speed cap. |
| 2 | Road matching (map matching) | done (Phase 0) | Pure Kotlin matcher in `shared` against the offline Adana region pack; driven segments are stored in SQLite. |
| 3 | Exploration map | partial | Map tab with MapLibre Compose and an app-embedded dark style derived from OpenFreeMap "Dark"; driven roads are thick Volt green, roads opened by the latest drive get a halo; the camera focuses on the driven roads on first open unless the user has moved the map. Verified on the Android emulator; the iOS build compiles in CI; **not tried on a physical device**. App shell with four tabs (Map, Drives, Stats, Profile): bottom bar on compact screens, side bar on medium and wide ones. Burn-in animation: the roads of the latest drive light up as one front travelling in drive order (duration 0.6–2 s by distance), once per drive the first time the map is visible after it; the camera fits the new roads first (unless the user moved the map), and it is skipped when the system "reduce motion" setting is on. Undiscovered-roads layer: pack roads not driven yet are drawn in grey below the driven roads (nothing below zoom 11, major roads at 11–13, all roads from 13; at most 48 tiles; refreshed on camera idle and after a drive); a layers button opens a bottom sheet with two switches (undiscovered roads, privacy zones; both on by default, stored in `app_meta`); privacy zones show as faint circles on the map. The in-app map is never cut by zones. The layer is free for now ([docs/DECISIONS.md](docs/DECISIONS.md) 67). |
| 4 | Completion percentages | partial (Phase 0) | Province and district percentages for Adana, split into major roads and local roads, shown on the Phase 0 screen. **Planned:** country level, other provinces. |
| 5 | Drive summary | done (Phase 0) | Summary screen per drive: distance, duration, average and max speed, new km, simplified route; the route draws in once and the new-km number counts up (same duration as the map burn-in, skipped with "reduce motion"; the exported PNG is not animated). Speed comes from the platform's reported speed when plausible, else it is derived from positions (median + acceleration filter; average = average while moving, never above max). City card data: province percentage, major / local road split, completed districts X/Y (a district counts as complete at 95 %), this week's new km, simplified province outline and driven lines. Verified on the Android emulator; the iOS build compiles in CI; **not tried on a physical device**. |
| 6 | Share card | partial (Phase 0) | Drive card and city card, 9:16 (1080×1920) and 4:5 (1080×1350), rendered on the device as PNG; "Hide speed" switch (speed is shown by default). Share through the system share sheet and save to the gallery (Android FileProvider + MediaStore; iOS `UIActivityViewController` + add-only photo library access). The route on the card omits the first and last 300 m along the road, and is empty for drives shorter than 600 m. Privacy zones (Home / Work / Custom, radius 200–1000 m) are additionally cut out of the drive card route, the new-road lines and the city card lines; percentages, km and stored data are unchanged. The logo on the card is a text wordmark (no real logo yet). Verified on the Android emulator; the iOS build compiles in CI; **not tried on a physical device**. **Planned:** direct Instagram Story sharing, Pro card styles, new city card (see [Known limitations](#known-limitations)). |
| 7 | Onboarding and permissions | done (Phase 0) | First-launch gate: intro (animated Adana roads drawn from the region pack) → safe-driving consent (two checkboxes; stored locally in `app_meta` with a consent version) → one permission card at a time (location, background location with a Google Play prominent disclosure first, physical activity, notifications) → map. Notifications are optional and advance automatically; required permissions stay on their card when denied, and everything can be skipped. Permission states include `Blocked` (only system Settings can grant) and `Approximate` (coarse / reduced location), with an "open settings" route; the Profile tab shows the states and lets the user fix them later. Privacy policy and Terms are readable in the app (Profile) in Turkish and English, but they are **drafts**: contact, data controller and jurisdiction are placeholders and they need owner or lawyer review before launch. No paywall in onboarding (no RevenueCat yet). Not tried on a physical device; the iOS code compiles in CI only. |
| 7b | End-of-drive notification | done (Phase 0) | One local notification per drive that counted, only when the notification permission is granted; text carries distance and new km, never speed. Tapping it opens that drive's summary. Android: own "Drive results" channel. iOS: `UNUserNotificationCenter`. Not tried on a physical device; the iOS code compiles in CI only. |
| 8 | Account and sync | planned | No Firebase. Data stays in the local database. |
| 9 | Subscription and paywall | planned | No RevenueCat. |
| 10 | Settings | partial | Profile → Privacy zones: list, add / edit / delete (delete asks for confirmation), map editor with a centre pin, radius slider and circle preview; one dismissible suggestion ("add a zone here?") after at least 5 counted drives when a start/end place repeats at least 3 times (never creates a zone by itself). Zones live in the new `privacy_zone` table (schema 2→3). Profile → Settings: distance unit (km / mi, speed km/h / mph; default miles for US and GB system regions, else km; applies to the Drives list, summary, drive and city cards including the PNG, and the notification; percentages unchanged); app language (opens the system per-app language page, Android 13+ and iOS; hidden below Android 13); quiet hours (default off, 22:00–07:00, suppresses only the end-of-drive notification, not the recording notification); export of all drives as one GPX 1.1 file through the share sheet (nothing is uploaded); delete all data in the app (two-step confirmation, immediate and irreversible, no backup; an ongoing recording is stopped and discarded first); About with the version. Not tried on a physical device. |

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
| Android host | androidx.core:core-ktx (FileProvider for sharing card images) | 1.18.0 |
| Typeface | Inter (static 400 / 500 / 600 / 700, bundled font asset, SIL OFL 1.1) | 4.1 |
| Concurrency | kotlinx-coroutines | 1.11.0 |
| Serialization / time | kotlinx-serialization-json / kotlinx-datetime | 1.13.0 / 0.8.0 |
| DI | Koin | 4.2.2 |
| Local database | SQLDelight | 2.4.1 |
| Map | MapLibre Compose (pre-1.0, pinned exactly) + Android OpenGL runtime (`maplibre-compose-runtime-opengl-android`) | 0.20.0 |
| Test only | `app.cash.sqldelight:sqlite-driver` (`shared` androidHostTest: real SQLite for the privacy zone and migration tests; not in the app) | 2.4.1 |
| Map tiles | OpenFreeMap vector tiles (no API key); style embedded in the app | – |
| Android location | Google Play services location (fused location, activity transitions, geofencing) | 21.4.0 |
| iOS location / motion | CoreLocation and CoreMotion through Kotlin/Native interop (system frameworks, no extra library) | – |
| Android SDK | compileSdk / targetSdk / minSdk | 37 / 36 / 26 |
| JVM target | Java | 17 |
| Region pack builder | Python, pyosmium (`osmium`), `shapely`, `pytest` | 3.12, 4.0.2, 2.1.2, 8.4.2 |
| GPX tools | Python standard library + `pytest` | 3.12, 8.4.2 |

### Planned (not in the code yet)

Firebase Auth (GitLive `firebase-kotlin-sdk`), Firestore (Spark plan) for user data, RevenueCat (`purchases-kmp`),
PostHog (`posthog-kmp`), Ktor client, region packs hosted on Cloudflare R2
and built by GitHub Actions. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Repository layout

```
.
├── androidApp/            Android app shell: MainActivity, Application, manifest
│   └── src/debug/         debug-only manifest (emulator GPS helper receiver)
├── composeApp/            shared Compose UI, navigation, DI
│   └── src/
│       ├── commonMain/    UI (MVI), theme, strings (composeResources/values, values-tr)
│       │   ├── kotlin/app/roadmaxxing/ui/   card (drive and city cards), components, drives, explore, main, map, profile, shell (tabs), stats, summary
│       │   ├── kotlin/app/roadmaxxing/share/  card sharing (`CardSharer`, expect/actual)
│       │   └── composeResources/   font/ (Inter), files/map/ (embedded map style), files/licenses/ (Inter OFL)
│       ├── androidMain/   tracking: foreground service, idle triggers, debug emulator helper; card sharing (FileProvider, MediaStore)
│       └── iosMain/       iOS entry point, bootstrap, automatic drive tracking (CoreLocation, CoreMotion), card sharing (share sheet, Photos); compiled in CI only
├── shared/                domain, data and the matching engine (pure Kotlin)
│   └── src/
│       ├── commonMain/    drive detection, matching, progress, region pack reader, driven roads for the map (`map/`), drive and city summary data (`summary/`), SQLDelight schema
│       ├── commonTest/    unit tests (fixture region pack)
│       ├── androidHostTest/  tests against the real Adana pack and GPX routes
│       ├── androidMain/
│       └── iosMain/
├── iosApp/                Xcode project (SwiftUI host for the Compose UI; "Copy Region Pack" build phase embeds the Adana pack)
├── .github/workflows/     ios.yml, android.yml (CI)
├── tools/
│   ├── regions/           region pack builder (Python, OSM → test-data/regions/<id>)
│   └── gpx/               GPX route generator and emulator replay
├── test-data/
│   ├── regions/adana/     Adana region pack (embedded in the Android APK and the iOS app at build time)
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
- Windows, Linux or macOS. iOS builds need macOS and are meant to run only on GitHub Actions (see
  [CI and TestFlight](#ci-and-testflight)). The minimum iOS version is **15.5** (required by MapLibre Compose).
- Internet access on the emulator or device: the map tiles are loaded from OpenFreeMap.

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

### Bumping the app version

The single source is [gradle.properties](gradle.properties): `roadmaxxing.versionName` and `roadmaxxing.versionCode`.
`androidApp` reads them, and `composeApp` generates `AppVersion.kt` (shown on the Profile tab) from them. To bump the
version, change `roadmaxxing.versionName` / `versionCode` there and `MARKETING_VERSION` in
`iosApp/iosApp.xcodeproj/project.pbxproj` to the same value. The `checkIosVersion` Gradle task fails the build when
the two differ.

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

iOS simulator tests of `shared` (`:shared:iosSimulatorArm64Test`) run in the CI `build` job of the iOS workflow; they cannot run on Windows/Linux.

## Rebuilding the region pack

The region pack is every drivable OSM road in one province, split into segments at junctions, with each segment
assigned to its province and district, plus per-region road totals. A segment summary index (`segments.bin`) lets the
app compute the first percentage without scanning all tiles. Format: [tools/regions/FORMAT.md](tools/regions/FORMAT.md);
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

Two GitHub Actions workflows live in [.github/workflows/](.github/workflows/). Rules for changing them are in
[docs/ci/README.md](docs/ci/README.md); the iOS one is derived from the reference [docs/ci/ios.yml](docs/ci/ios.yml).
Both ran green on the pull request that introduced them.

| Workflow | Triggers | What it does |
| --- | --- | --- |
| [ios.yml](.github/workflows/ios.yml) | Push to `main` and pull requests that touch `composeApp/`, `shared/`, `iosApp/`, `gradle/`, `*.gradle.kts`, `gradle.properties`, `test-data/regions/` or the workflow file; push of a `v*` tag; manual (`workflow_dispatch`) | `build` job (`macos-15`): runs `:shared:iosSimulatorArm64Test` on the iOS simulator, then an unsigned simulator build with `xcodebuild`. `testflight` job: archive, export and upload to TestFlight. |
| [android.yml](.github/workflows/android.yml) | Push to `main` and pull requests with changes under `composeApp/`, `androidApp/`, `shared/`, `gradle/`, `*.gradle.kts`, `gradle.properties`, `test-data/regions/` or the workflow file; manual | One job (`ubuntu-24.04`): `./gradlew :androidApp:assembleDebug :shared:testAndroidHostTest`. No signing, no Play upload. |

Details:

- **Xcode is pinned** to 26.3 (`DEVELOPER_DIR=/Applications/Xcode_26.3.app/Contents/Developer`) on the pinned
  `macos-15` image, because the image default is Xcode 16.4 and Kotlin 2.4 is tested against the Xcode 26 line. Both
  workflows run Gradle with JDK 21; the code is still compiled to JVM 17 bytecode.
- **`testflight` job:** runs only after `build` succeeds, and only on a `v*` tag or a manual run, in the GitHub
  environment `testflight` (create that environment in the repository settings first). **It has never run yet:**
  on pull requests it is skipped and no tag or manual run has been made, so the signing and upload steps are
  untested.
- **Fork pull requests and unset secrets:** the `build` job reads three secrets into the environment and works when
  they are empty. If `GOOGLE_SERVICE_INFO_PLIST_BASE64` is empty (Firebase is not set up yet, or a fork PR), the
  workflow writes a valid empty placeholder `GoogleService-Info.plist` instead of failing, and removes the file at the
  end of the job. The workflow already contains the Firebase, RevenueCat and PostHog steps so it does not have to be
  touched again when those services are added; the app does not use them yet.
- Failed test runs upload their reports as an artifact (7 days) in both workflows.
- Play internal-track upload for Android is not set up.

GitHub Actions secrets (names only; values never go into the repository or any output):

| Secret | Job | Notes |
| --- | --- | --- |
| `APPSTORE_KEY_ID` | testflight | needed for upload |
| `APPSTORE_ISSUER_ID` | testflight | needed for upload |
| `APPSTORE_API_KEY_P8` | testflight | needed for upload |
| `APPLE_TEAM_ID` | testflight | needed for upload |
| `GOOGLE_SERVICE_INFO_PLIST_BASE64` | build, testflight | may be empty in `build` (placeholder plist is written) |
| `REVENUECAT_IOS_API_KEY` | build, testflight | may be empty in `build` |
| `POSTHOG_API_KEY` | build, testflight | may be empty in `build` |

Locally, configuration files and keys live in git-ignored files (`local.properties`, `*.xcconfig`); see
[.gitignore](.gitignore).

## Known limitations

- **iOS is not verified on a device.** The `iosMain` tracking code and the Xcode project compile and the `shared`
  tests pass on the CI simulator, but nothing has been run on a physical iPhone (no Mac). Android has not been tried
  on a physical device either.
- **iOS background limits.** CoreMotion does not wake a suspended app; wake-ups come only from the location triggers
  (significant location change and 200 m region monitoring), so detection latency depends on them. If the user
  force-quits the app by swiping it away, a relaunch is not guaranteed. While recording, iOS shows its blue
  status-bar location indicator.
- **Background triggers need a real device.** On the emulator the production idle triggers (Activity Recognition,
  Play services geofencing) do not behave like on a phone: GPS is not active without a request and geofence checks are
  throttled by the stationary detector. Emulator tests rely on the debug-only helpers above. Trigger latency, battery
  use and the "app closed" case must be checked on physical Android and iOS devices.
- **Map needs internet.** The base map tiles come from OpenFreeMap; without a connection the base map is not
  shown (apart from tiles MapLibre has cached). The undiscovered-roads layer is computed on the device from the region pack and works offline.
- **Undiscovered layer panning and iOS side not verified on a device.** The layer re-parses about 1.07 MiB of GeoJSON
  at zoom 13 on every refresh; panning smoothness with it on has not been checked on a physical phone. The iOS side of
  the layer, the layers sheet and the privacy zone screens compiles in CI only and has not run on an iPhone.
- **Animations and iOS notification not verified on a device.** The map burn-in and the summary draw-in run, but their
  smoothness could not be measured on the emulator and has not been seen on any physical device. The iOS end-of-drive
  notification (`UNUserNotificationCenter`) compiles in CI only and has not run on an iPhone.
- **Map performance and iOS map not measured on devices.** On the debug build on the emulator the green roads appear
  after about 3 s on a cold start; this was not measured on a physical device. The iOS map has not been run on any
  iPhone.
- **iOS version is edited by hand.** The Android version and the label on the Profile tab come from
  [gradle.properties](gradle.properties), but the iOS `MARKETING_VERSION` lives in the Xcode project, so it is changed
  together with it (see [Bumping the app version](#bumping-the-app-version)); the build fails if they differ.
- **One region.** Only the Adana pack exists and it is embedded in the APK and the iOS app; downloadable packs (Cloudflare R2) are
  planned. No country-level percentage.
- **Cards and sharing not tried on a device.** The drive and city cards, the PNG export, the share sheet and save to
  gallery were verified on the Android emulator only; the iOS code compiles in CI but has not run on an iPhone. The
  PNG export waits 600 ms for the fonts to load before it renders. On a closed Pixel Fold the card is scrolled to be
  seen in full.
- **Text wordmark, no real logo.** The cards show the name as styled text from a single component; a real logo does
  not exist yet.
- **Privacy zones cut shared images only.** The card route still omits the first and last 300 m along the road
  (nothing is drawn for drives shorter than 600 m), and user-defined zones are cut out on top of that. The route and
  roads in the app itself, the percentages and the stored data are never cut. There is no direct Instagram Story share (it needs a Meta app ID); use the system share sheet.
- **iOS settings, export and erase not run on an iPhone.** The iOS paths for units, language settings, GPX export (`UIActivityViewController`) and data erase compile in CI only; they have not run on an iPhone.
- **No account, sync, subscription or analytics.** All data is local.
- **Emulator timing.** The emulator GPS reports at 1 Hz with jitter; repeated replays of the same route can add a few
  extra segments. With the segment summary index the first progress computation on a cold Pixel_8 emulator takes about 0.4–0.8 s.

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

Base map tiles are served by [OpenFreeMap](https://openfreemap.org) and use [OpenMapTiles](https://www.openmaptiles.org/)
data (© OpenMapTiles) from [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors. The attribution is
shown on the map screen. The embedded style
([roadmaxxing_dark.json](composeApp/src/commonMain/composeResources/files/map/roadmaxxing_dark.json)) is derived from
the OpenFreeMap "Dark" style.

## Fonts

The app uses [Inter](https://rsms.me/inter/) v4.1 (static weights 400, 500, 600, 700) by The Inter Project Authors,
under the SIL Open Font License 1.1. License text:
[Inter-OFL.txt](composeApp/src/commonMain/composeResources/files/licenses/Inter-OFL.txt).
