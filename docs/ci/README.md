# CI / CD

## iOS (GitHub Actions)

`ios.yml` in this folder is the reference for `.github/workflows/ios.yml`. It is the owner's proven TestFlight workflow from another KMP project, adapted for Roadmaxxing. Create the real workflow from it once, when the project skeleton (`composeApp`, `shared`, `iosApp/iosApp.xcodeproj`, scheme `iosApp`) exists. If the skeleton uses different names, change only those names.

**Rules**

- The workflow file is written once and changed only with the owner's approval. Batch changes into a single edit to avoid commit churn.
- `iosMain` code cannot be compiled on the owner's machine (no Mac). The `build` job (shared tests on the iOS simulator + unsigned simulator build) is where it is checked. Write `iosMain` code carefully and batch iOS changes.
- TestFlight upload runs only on a `v*` tag or a manual run (`workflow_dispatch`).
- The owner tests iOS on a physical device at the end of a feature.

**What changed versus the original workflow**

1. `composeApp/**` added to the path filters (Compose Multiplatform UI module).
2. `macos-latest` pinned to a fixed image so a silent Xcode upgrade does not break builds. Check that the label is still offered by GitHub when the workflow is created.
3. `GoogleService-Info.plist` written from a secret in both jobs and removed at the end (it is not committed).
4. RevenueCat and PostHog keys passed as environment variables (read at build time, e.g. through BuildKonfig).
5. Shared module tests run on the iOS simulator in the `build` job.
6. Archive renamed to `Roadmaxxing.xcarchive`.

**Secrets (names only; values never go into the repo or any output)**

| Secret | Used in | What it is |
| --- | --- | --- |
| `APPSTORE_KEY_ID` | testflight | App Store Connect API key ID |
| `APPSTORE_ISSUER_ID` | testflight | App Store Connect issuer ID |
| `APPSTORE_API_KEY_P8` | testflight | Contents of the `.p8` API key |
| `APPLE_TEAM_ID` | testflight | Apple Developer team ID |
| `GOOGLE_SERVICE_INFO_PLIST_BASE64` | build, testflight | Base64 of `GoogleService-Info.plist` |
| `GOOGLE_SERVICES_JSON_BASE64` | android (`android.yml`) | Base64 of `androidApp/google-services.json`; may be empty |
| `REVENUECAT_IOS_API_KEY` | build, testflight | RevenueCat public iOS SDK key |
| `POSTHOG_API_KEY` | build, testflight | PostHog project API key |

The `testflight` job uses the GitHub environment `testflight`; put the App Store secrets there.

**Requirements in the Xcode project**

- `iosApp/iosApp/iosApp.entitlements` includes Sign in with Apple.
- `Info.plist` has `UIBackgroundModes` → `location`, and location / motion usage descriptions (Turkish and English).
- `GoogleService-Info.plist` is referenced by the project but listed in `.gitignore`.

## Android

`.github/workflows/android.yml` runs build + unit tests on every PR and on `main` (`:androidApp:assembleDebug :shared:testAndroidHostTest`). Upload to the Play internal track is not set up. Local tests run on a visible Android emulator (see CLAUDE.md).

**Firebase step.** Before Gradle runs, the workflow writes `androidApp/google-services.json` from the secret `GOOGLE_SERVICES_JSON_BASE64` (base64 decode; the file is git-ignored and never committed or printed). The `generateFirebaseConfig` Gradle task turns it into generated build code; there is no Google Services Gradle plugin. If the secret is empty (for example on fork pull requests), no file is written, `FirebaseBootstrap.isAvailable` is false and the app builds and runs with accounts hidden. Only the secret name is documented here, never its value.
