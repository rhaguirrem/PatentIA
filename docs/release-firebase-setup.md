# Release And Firebase Setup

This project is prepared for two setup modes:

1. Local Android Studio or command-line builds using `local.properties` and `keystore.properties`
2. GitHub Actions builds using repository secrets

## App Identity

- Android package name: `com.patentia`
- Default Firebase group id: `patentia-demo`

## Local Release Signing

Create a release keystore and a local `keystore.properties` file.

Expected `keystore.properties` keys:

```properties
ANDROID_KEYSTORE_PATH=.secrets/PatentIA-release.jks
ANDROID_KEYSTORE_PASSWORD=your-keystore-password
ANDROID_KEY_ALIAS=patentia-release
ANDROID_KEY_PASSWORD=your-key-password
```

The release build reads these values automatically when present.

## GitHub Secrets

Add these repository secrets in GitHub Actions settings:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`
- `MAPS_API_KEY`
- `GOOGLE_SERVICES_JSON_BASE64` if Firebase is enabled

## Firebase Setup

1. Open Firebase Console.
2. Create a Firebase project for PatentIA.
3. Add an Android app with package name `com.patentia`.
4. Register SHA-1 and SHA-256 from the release keystore.
5. Download `google-services.json`.
6. Put it at `app/google-services.json` for local development.
7. Base64-encode the same file and save it in GitHub as `GOOGLE_SERVICES_JSON_BASE64`.
8. Enable Anonymous Authentication.
9. Create Cloud Firestore in production or test mode.
10. Create Firebase Storage.
11. Deploy the rules from the `firebase/` directory.

## Local Firebase Files

- Firestore rules: `firebase/firestore.rules`
- Firestore indexes: `firebase/firestore.indexes.json`
- Storage rules: `firebase/storage.rules`

## GitHub Workflows

- `Android CI`: builds debug APK and lint report on push and pull request
- `Android Release`: manual workflow that creates signed release APK and AAB using GitHub secrets

## Notes

- If `google-services.json` is absent, the app still builds and runs in local-only mode.
- If release signing secrets are absent, debug CI still works but the release workflow will fail.