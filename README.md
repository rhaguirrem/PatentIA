# PatentIA

PatentIA is an Android app scaffold written in Kotlin with Jetpack Compose for rapid vehicle licence capture in the field.

## What is implemented

- CameraX live preview with a large single-tap capture button.
- Interval capture mode for repeated shots every N seconds.
- Import from gallery for existing images.
- OCR pipeline using ML Kit text recognition.
- Plate candidate extraction heuristics from OCR text.
- GPS coordinate lookup using fused location services.
- Local persistence with Room.
- Firebase-ready local-first sync architecture with optional Firestore plus Firebase Storage upload/listen flows.
- Firebase group switching and join/create flow for shared teams.
- Map view with markers for recorded sightings.
- Timeline and filtering by licence, repeated sightings, and time window.
- Shared image thumbnails inside the history list.
- Manual retry controls for failed sync rows.
- Theoretical maximum travel radius from the latest observation of a selected plate.
- JSON sharing of a selected plate history through the Android share sheet.

## Project structure

- `app/src/main/java/com/patentia/ui`: Compose UI and state management.
- `app/src/main/java/com/patentia/services`: OCR and location services.
- `app/src/main/java/com/patentia/data`: Room entities, DAO, database, and repository.

## Main assumptions

- Recognition uses generic OCR, not a specialized ANPR engine. Some number plate formats will require tuning.
- Sharing is implemented as JSON export via the system share sheet. Firestore sync and Firebase Storage image upload wiring are scaffolded but require Firebase project configuration.
- Location is captured at the time of OCR processing, not fused from multiple sensors over a travel path.
- The theoretical radius assumes a maximum speed of 130 km/h from the latest known observation.

## Setup

1. Open the project in Android Studio.
2. Let Android Studio install the missing Android SDK components.
3. Add a Google Maps API key in `local.properties` as `MAPS_API_KEY=...`.
4. Create a Firebase project if you want multi-user sharing.
5. Enable Anonymous Authentication and Cloud Firestore in Firebase. Firebase Storage is optional, and the app will fall back to metadata-only sharing when Storage is unavailable.
6. Add `google-services.json` to `app/google-services.json`. The build is set up to apply the Google services plugin automatically when that file exists.
7. In-app update checks default to GitHub Releases. Override `APP_UPDATE_MANIFEST_URL`, `APP_UPDATE_APK_URL`, or `APP_UPDATE_PAGE_URL` in `local.properties`, environment variables, or signing properties when you want the app to use a different shared endpoint.
8. Every push to `main` runs the `Android Release` workflow and publishes `patentia-installer-release.apk` plus `app-update-manifest.json` as GitHub release assets. You can still run it manually with `workflow_dispatch`.
9. Generate a Gradle wrapper from Android Studio or from a local Gradle installation.
10. Run the app on a physical Android device with camera and GPS access.

## Firebase-ready architecture

- Room stays the source of truth for the UI.
- New sightings are stored locally first.
- If Firebase is configured, those local sightings are marked pending and uploaded to Firestore.
- If Firebase Storage is available, local images are uploaded before the Firestore document is written.
- If Firebase Storage is unavailable, the sighting still syncs to Firestore and the app warns that the shared photo was skipped.
- A Firestore listener streams shared group sightings back into Room.
- If Firebase is not configured, the app remains fully local.

## Current group workflow

- Firebase users are signed in anonymously.
- The app lists groups the current user belongs to.
- Users can switch active groups from the in-app sync card.
- Users can type a group code to join an existing group or create a new one.

## Current retry workflow

- Failed uploads are marked as `SYNC_ERROR`.
- The sync card exposes a `Retry now` action for pending or failed uploads.
- Each failed history row exposes its own `Retry upload` action.

See `docs/firebase-architecture.md` for the collection design, `firebase/firestore.rules` for Firestore access rules, and `firebase/storage.rules` for shared image access rules.

## Notes

- This environment did not have Gradle or Android platform tools installed, so the wrapper and APK build could not be generated here.
- The Room schema now includes sync metadata and currently uses destructive migration because this is still a first-pass scaffold.
- The manifest currently locks the app to portrait for easier one-hand roadside use.
- For production use while driving, you should validate local laws, privacy constraints, and operational safety requirements.

## Recommended next steps

1. Add a real multi-user sync backend, such as Firebase Authentication + Firestore.
2. Tune plate extraction rules for the target country or region.
3. Add background upload, conflict resolution, and inbound shared-history import.
4. Add offline map tiles or a degraded fallback when Google Maps is unavailable.