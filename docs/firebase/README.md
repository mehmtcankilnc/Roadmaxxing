# Firebase setup notes

Never commit project ids, API keys, `google-services.json` or `GoogleService-Info.plist`.

## Auth providers

In the Firebase console, Build → Authentication → Sign-in method, enable:

- **Email/Password**
- **Google** (this stage). The Web client ID of that provider is what the app uses as the Google server client id on Android (provided to the app through `FirebaseBootstrap`, not stored in the repo).
- Apple: planned later, required before the App Store release.

## Firestore rules

**Published:** the owner has published the current `firestore.rules` (with schema validation) in the console, and sync was verified against them on the Android emulator. After any change to `firestore.rules`, publish it again.

`firestore.rules` at the repo root: default deny; a signed-in user may access only `users/{uid}` (profile) and `users/{uid}/drives/{syncId}` (one doc per drive). Writes are schema-checked (field whitelist, int/string types, size limits); no other subcollections. When a document field is added to the sync codec, update the whitelist in the rules and `DocSerializers.kt` in composeApp.

Option A, console: Build -> Firestore Database -> Rules, paste the file contents, press Publish.

Option B, Firebase CLI: from the repo root, with a `firebase.json` that has `{"firestore": {"rules": "firestore.rules"}}` (kept out of the repo if it holds a project id), run:

```bash
firebase deploy --only firestore:rules
```

## Quota notes (Spark)

Listing drive ids reads every drive document (1 read each); the app does it rarely. Deleting an account's cloud data costs 1 read + 1 delete per drive.

## iOS Google sign-in

The iOS flow needs the `REVERSED_CLIENT_ID` from the Firebase iOS config registered as a URL scheme in the app's Info.plist (CI injects it; do not write the value into the repo).
