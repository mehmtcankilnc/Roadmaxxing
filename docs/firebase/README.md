# Firebase setup notes

Never commit project ids, API keys, `google-services.json` or `GoogleService-Info.plist`.

## Auth providers

In the Firebase console, Build → Authentication → Sign-in method, enable:

- **Email/Password**
- **Google** (this stage). The Web client ID of that provider is what the app uses as the Google server client id on Android (provided to the app through `FirebaseBootstrap`, not stored in the repo).
- Apple: planned later, required before the App Store release.

## Firestore rules

`firestore.rules` at the repo root denies everything except `users/{uid}/**` for the signed-in user with that uid.

Option A, console: Build → Firestore Database → Rules, paste the file contents, press Publish.

Option B, Firebase CLI: from the repo root, with a `firebase.json` that has `{"firestore": {"rules": "firestore.rules"}}` (kept out of the repo if it holds a project id), run:

```bash
firebase deploy --only firestore:rules
```

## iOS Google sign-in

The iOS flow needs the `REVERSED_CLIENT_ID` from the Firebase iOS config registered as a URL scheme in the app's Info.plist (CI injects it; do not write the value into the repo).
