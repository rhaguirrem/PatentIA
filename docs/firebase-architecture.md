# Firebase Architecture

PatentIA uses a local-first model:

- Room is the UI source of truth.
- Firestore is the shared multi-user transport and persistence layer.
- Firebase Storage holds shared images.
- Firebase Auth identifies the current user.

## Collection layout

### `users/{uid}`

Stores per-user metadata.

Suggested fields:

- `displayName`
- `createdAtEpochMillis`
- `defaultGroupId`
- `roleSummary`

### `groups/{groupId}`

Stores shared-team metadata.

Suggested fields:

- `name`
- `createdBy`
- `createdAtEpochMillis`
- `status`

### `groups/{groupId}/members/{uid}`

Membership and authorization state.

Suggested fields:

- `role`: `owner`, `admin`, `member`
- `joinedAtEpochMillis`
- `canUploadImages`

### `groups/{groupId}/sightings/{sightingId}`

Primary shared observation stream.

Suggested fields:

- `clientGeneratedId`
- `groupId`
- `createdBy`
- `plateNumber`
- `rawText`
- `imageUri`
- `imageDownloadUrl`
- `imageStoragePath`
- `latitude`
- `longitude`
- `capturedAtEpochMillis`
- `updatedAtEpochMillis`
- `source`
- `serverTimestamp`

## Sync strategy

1. Save sighting to Room first.
2. Mark the local row as `PENDING_UPLOAD`.
3. Upload the image to Firebase Storage under `groups/{groupId}/sightings/{clientGeneratedId}.jpg` when a local image exists.
4. Write metadata to `groups/{groupId}/sightings/{sightingId}`.
5. Mark local row as `SYNCED` after upload success.
6. Listen to Firestore snapshots and merge remote sightings back into Room.

## Why this shape

- Group-scoped collections make access control straightforward.
- `clientGeneratedId` allows the same sighting to be matched between local Room rows and remote Firestore documents.
- Append-only sightings avoid most conflict-resolution complexity.

## Recommended Auth model

- Start with Firebase Anonymous Auth for lowest friction.
- Later allow linking to Google Sign-In for persistent identity across devices.

## Current group management flow

- The app queries `groups/*/members/{uid}` membership indirectly by scanning group documents and checking whether the current user has a member document.
- Users can switch to another known group from the UI.
- Users can type a group code to create a new group or add themselves to an existing one.
- The active group determines which Firestore sightings stream is observed.

## Storage notes

- Firestore stores metadata only.
- Firebase Storage stores the image bytes.
- Shared devices should consume `imageDownloadUrl` from Firestore, not the original local `imageUri`.

## Recommended next Firebase additions

- Group management UI.
- Cloud Functions only if you later need server-side enrichment, moderation, or analytics rollups.