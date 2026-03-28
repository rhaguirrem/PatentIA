package com.patentia.data.remote

import android.content.Context
import android.net.Uri
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.firestore
import com.google.firebase.storage.storage
import com.patentia.BuildConfig
import com.patentia.data.PlateSighting
import com.patentia.data.SharedGroup
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class FirebaseRemoteSyncDataSource(
    private val appContext: Context,
) : RemoteSightingSyncDataSource {

    override val isConfigured: Boolean = isFirebaseConfigured(appContext)

    override suspend fun ensureSession(preferredGroupId: String?): RemoteSyncSession {
        if (!isConfigured) {
            return RemoteSyncSession.disabled(configured = false)
        }

        val auth = Firebase.auth
        val currentUser = auth.currentUser ?: auth.signInAnonymously().await().user
        if (currentUser == null) {
            return RemoteSyncSession(
                isAvailable = false,
                providerLabel = "firebase-auth",
                groupId = BuildConfig.DEFAULT_FIRESTORE_GROUP_ID,
                statusMessage = "Firebase anonymous sign-in failed",
            )
        }

        val providerLabel = if (currentUser.isAnonymous) {
            "firebase-anonymous"
        } else {
            currentUser.providerData.firstOrNull { it.providerId != "firebase" }?.providerId ?: "firebase-auth"
        }

        val groups = listGroupsForUser(currentUser.uid)
        val activeGroupId = preferredGroupId
            ?: groups.firstOrNull()?.id
            ?: BuildConfig.DEFAULT_FIRESTORE_GROUP_ID

        return RemoteSyncSession(
            isAvailable = true,
            providerLabel = providerLabel,
            userId = currentUser.uid,
            groupId = activeGroupId,
            availableGroups = groups,
            statusMessage = "Connected to Firestore",
        )
    }

    override suspend fun listGroups(session: RemoteSyncSession): List<SharedGroup> {
        val userId = session.userId ?: return emptyList()
        return listGroupsForUser(userId)
    }

    override suspend fun joinOrCreateGroup(
        session: RemoteSyncSession,
        groupId: String,
    ): SharedGroup {
        val userId = session.userId ?: throw IllegalStateException("No Firebase user session")
        val sanitizedGroupId = sanitizeGroupId(groupId)
        val groupDocument = Firebase.firestore.collection(GROUPS_COLLECTION).document(sanitizedGroupId)
        val groupSnapshot = groupDocument.get().await()
        val timestamp = System.currentTimeMillis()

        if (!groupSnapshot.exists()) {
            groupDocument.set(
                hashMapOf(
                    "name" to sanitizedGroupId,
                    "createdBy" to userId,
                    "createdAtEpochMillis" to timestamp,
                    "status" to "active",
                )
            ).await()
            groupDocument.collection(MEMBERS_COLLECTION).document(userId).set(
                hashMapOf(
                    "role" to "owner",
                    "joinedAtEpochMillis" to timestamp,
                    "canUploadImages" to true,
                )
            ).await()
            return SharedGroup(sanitizedGroupId, sanitizedGroupId)
        }

        groupDocument.collection(MEMBERS_COLLECTION).document(userId).set(
            hashMapOf(
                "role" to "member",
                "joinedAtEpochMillis" to timestamp,
                "canUploadImages" to true,
            )
        ).await()
        val groupName = groupSnapshot.getString("name") ?: sanitizedGroupId
        return SharedGroup(sanitizedGroupId, groupName)
    }

    override fun observeSharedSightings(groupId: String): Flow<List<RemotePlateSighting>> = callbackFlow {
        if (!isConfigured) {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }

        val registration = Firebase.firestore
            .collection(GROUPS_COLLECTION)
            .document(groupId)
            .collection(SIGHTINGS_COLLECTION)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }

                val sightings = snapshot?.documents.orEmpty().mapNotNull { document ->
                    val plateNumber = document.getString("plateNumber") ?: return@mapNotNull null
                    val clientGeneratedId = document.getString("clientGeneratedId") ?: document.id
                    val createdBy = document.getString("createdBy") ?: return@mapNotNull null
                    val capturedAtEpochMillis = document.getLong("capturedAtEpochMillis") ?: return@mapNotNull null
                    val updatedAtEpochMillis = document.getLong("updatedAtEpochMillis") ?: capturedAtEpochMillis
                    RemotePlateSighting(
                        remoteId = document.id,
                        clientGeneratedId = clientGeneratedId,
                        groupId = groupId,
                        createdBy = createdBy,
                        plateNumber = plateNumber,
                        rawText = document.getString("rawText").orEmpty(),
                        imageUri = document.getString("imageDownloadUrl") ?: document.getString("imageUri"),
                        imageStoragePath = document.getString("imageStoragePath"),
                        latitude = document.getDouble("latitude"),
                        longitude = document.getDouble("longitude"),
                        capturedAtEpochMillis = capturedAtEpochMillis,
                        updatedAtEpochMillis = updatedAtEpochMillis,
                        source = document.getString("source").orEmpty(),
                    )
                }

                trySend(sightings)
            }

        awaitClose {
            registration.remove()
        }
    }

    override suspend fun uploadSightings(
        session: RemoteSyncSession,
        sightings: List<PlateSighting>,
    ): List<RemoteUploadResult> {
        if (!isConfigured || !session.isAvailable || session.groupId == null || session.userId == null) {
            return sightings.map { sighting ->
                RemoteUploadResult(
                    clientGeneratedId = sighting.clientGeneratedId,
                    errorMessage = "Firestore session unavailable",
                )
            }
        }

        val collection = Firebase.firestore
            .collection(GROUPS_COLLECTION)
            .document(session.groupId)
            .collection(SIGHTINGS_COLLECTION)

        return sightings.map { sighting ->
            val remoteDocument = collection.document(sighting.remoteId ?: sighting.clientGeneratedId)
            val updatedAtEpochMillis = System.currentTimeMillis()
            val uploadedImage = uploadImageIfNeeded(
                groupId = session.groupId,
                sighting = sighting,
            )
            if (uploadedImage.errorMessage != null) {
                return@map RemoteUploadResult(
                    clientGeneratedId = sighting.clientGeneratedId,
                    errorMessage = uploadedImage.errorMessage,
                )
            }

            val payload = hashMapOf(
                "clientGeneratedId" to sighting.clientGeneratedId,
                "groupId" to session.groupId,
                "createdBy" to session.userId,
                "plateNumber" to sighting.plateNumber,
                "rawText" to sighting.rawText,
                "imageUri" to sighting.imageUri,
                "imageDownloadUrl" to uploadedImage.downloadUrl,
                "imageStoragePath" to uploadedImage.storagePath,
                "latitude" to sighting.latitude,
                "longitude" to sighting.longitude,
                "capturedAtEpochMillis" to sighting.capturedAtEpochMillis,
                "updatedAtEpochMillis" to updatedAtEpochMillis,
                "source" to sighting.source,
                "serverTimestamp" to FieldValue.serverTimestamp(),
            )

            try {
                remoteDocument.set(payload).await()
                RemoteUploadResult(
                    clientGeneratedId = sighting.clientGeneratedId,
                    remoteId = remoteDocument.id,
                    groupId = session.groupId,
                    createdBy = session.userId,
                    imageUri = uploadedImage.downloadUrl,
                    imageStoragePath = uploadedImage.storagePath,
                    updatedAtEpochMillis = updatedAtEpochMillis,
                )
            } catch (exception: Exception) {
                RemoteUploadResult(
                    clientGeneratedId = sighting.clientGeneratedId,
                    errorMessage = exception.message ?: "Firestore upload failed",
                )
            }
        }
    }

    companion object {
        private const val GROUPS_COLLECTION = "groups"
        private const val MEMBERS_COLLECTION = "members"
        private const val SIGHTINGS_COLLECTION = "sightings"
        private const val STORAGE_ROOT = "groups"

        fun isFirebaseConfigured(context: Context): Boolean {
            val resourceId = context.resources.getIdentifier("google_app_id", "string", context.packageName)
            return resourceId != 0
        }
    }

    private suspend fun listGroupsForUser(userId: String): List<SharedGroup> {
        val groupSnapshots = Firebase.firestore.collection(GROUPS_COLLECTION).get().await()
        return groupSnapshots.documents.mapNotNull { groupDocument ->
            val memberSnapshot = groupDocument.reference
                .collection(MEMBERS_COLLECTION)
                .document(userId)
                .get()
                .await()
            if (!memberSnapshot.exists()) {
                return@mapNotNull null
            }
            SharedGroup(
                id = groupDocument.id,
                name = groupDocument.getString("name") ?: groupDocument.id,
            )
        }.sortedBy { it.name }
    }

    private fun sanitizeGroupId(groupId: String): String {
        return groupId
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9_-]"), "-")
            .take(40)
            .ifBlank { BuildConfig.DEFAULT_FIRESTORE_GROUP_ID }
    }

    private suspend fun uploadImageIfNeeded(
        groupId: String,
        sighting: PlateSighting,
    ): UploadedImageResult {
        val imageUriString = sighting.imageUri ?: return UploadedImageResult.none()
        if (imageUriString.startsWith("https://") || imageUriString.startsWith("gs://")) {
            return UploadedImageResult(
                downloadUrl = imageUriString,
                storagePath = null,
            )
        }

        val imageUri = runCatching { Uri.parse(imageUriString) }.getOrNull()
            ?: return UploadedImageResult(errorMessage = "Invalid local image URI")
        val storagePath = "$STORAGE_ROOT/$groupId/sightings/${sighting.clientGeneratedId}.jpg"
        val storageReference = Firebase.storage.reference.child(storagePath)

        return try {
            appContext.contentResolver.openInputStream(imageUri)?.close()
                ?: return UploadedImageResult(errorMessage = "Local image file is not accessible")

            storageReference.putFile(imageUri).await()
            val downloadUrl = storageReference.downloadUrl.await().toString()
            UploadedImageResult(
                downloadUrl = downloadUrl,
                storagePath = storagePath,
            )
        } catch (exception: Exception) {
            UploadedImageResult(
                errorMessage = exception.message ?: "Firebase Storage upload failed",
            )
        }
    }
}

private data class UploadedImageResult(
    val downloadUrl: String? = null,
    val storagePath: String? = null,
    val errorMessage: String? = null,
) {
    companion object {
        fun none(): UploadedImageResult = UploadedImageResult()
    }
}