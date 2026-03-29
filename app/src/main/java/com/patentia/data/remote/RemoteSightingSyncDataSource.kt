package com.patentia.data.remote

import com.patentia.data.PlateSighting
import com.patentia.data.PlateSyncState
import com.patentia.data.SharedGroup
import kotlinx.coroutines.flow.Flow

interface RemoteSightingSyncDataSource {
    val isConfigured: Boolean

    suspend fun ensureSession(preferredGroupId: String? = null): RemoteSyncSession

    suspend fun listGroups(session: RemoteSyncSession): List<SharedGroup>

    suspend fun joinOrCreateGroup(
        session: RemoteSyncSession,
        groupId: String,
    ): SharedGroup

    fun observeSharedSightings(groupId: String): Flow<List<RemotePlateSighting>>

    suspend fun uploadSightings(
        session: RemoteSyncSession,
        sightings: List<PlateSighting>,
    ): List<RemoteUploadResult>

    suspend fun deleteSighting(
        session: RemoteSyncSession,
        sighting: PlateSighting,
    ): String?
}

data class RemoteSyncSession(
    val isAvailable: Boolean,
    val providerLabel: String,
    val userId: String? = null,
    val groupId: String? = null,
    val availableGroups: List<SharedGroup> = emptyList(),
    val statusMessage: String? = null,
) {
    companion object {
        fun disabled(configured: Boolean): RemoteSyncSession = RemoteSyncSession(
            isAvailable = false,
            providerLabel = if (configured) "firebase-pending" else "local-only",
            statusMessage = if (configured) "Firebase session not initialized" else "Firebase not configured",
        )
    }
}

data class RemotePlateSighting(
    val remoteId: String,
    val clientGeneratedId: String,
    val groupId: String,
    val createdBy: String,
    val plateNumber: String,
    val rawText: String,
    val imageUri: String?,
    val imageStoragePath: String? = null,
    val latitude: Double?,
    val longitude: Double?,
    val capturedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val source: String,
)

data class RemoteUploadResult(
    val clientGeneratedId: String,
    val remoteId: String? = null,
    val groupId: String? = null,
    val createdBy: String? = null,
    val imageUri: String? = null,
    val imageStoragePath: String? = null,
    val updatedAtEpochMillis: Long = System.currentTimeMillis(),
    val warningMessage: String? = null,
    val errorMessage: String? = null,
)

fun RemotePlateSighting.toLocalEntity(
    existing: PlateSighting? = null,
): PlateSighting = PlateSighting(
    id = existing?.id ?: 0,
    clientGeneratedId = clientGeneratedId,
    remoteId = remoteId,
    groupId = groupId,
    createdBy = createdBy,
    plateNumber = plateNumber,
    rawText = rawText,
    imageUri = imageUri ?: existing?.imageUri,
    latitude = latitude,
    longitude = longitude,
    capturedAtEpochMillis = capturedAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
    source = source,
    syncState = PlateSyncState.SYNCED.name,
    syncError = existing?.syncError?.takeIf { imageUri == null },
    lastSyncedAtEpochMillis = System.currentTimeMillis(),
)