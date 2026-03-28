package com.patentia.data

import android.net.Uri
import com.patentia.data.remote.RemoteSightingSyncDataSource
import com.patentia.data.remote.RemoteSyncSession
import com.patentia.data.remote.toLocalEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class SightingRepository(
    private val dao: PlateSightingDao,
    private val remoteSyncDataSource: RemoteSightingSyncDataSource,
) {

    private val syncDiagnostics = MutableStateFlow(
        SyncDiagnostics(
            isConfigured = remoteSyncDataSource.isConfigured,
            providerLabel = if (remoteSyncDataSource.isConfigured) "firebase-pending" else "local-only",
        )
    )
    private var activeSession: RemoteSyncSession = RemoteSyncSession.disabled(
        configured = remoteSyncDataSource.isConfigured,
    )
    private var remoteObservationJob: Job? = null
    private var syncScope: CoroutineScope? = null

    fun observeSightings(): Flow<List<PlateSighting>> = dao.observeAll()

    fun observeSyncDiagnostics(): StateFlow<SyncDiagnostics> = syncDiagnostics.asStateFlow()

    fun startRealtimeSync(scope: CoroutineScope) {
        if (syncScope == null) {
            syncScope = scope
        }
        if (remoteObservationJob == null) {
            restartRealtimeSync()
        }
    }

    suspend fun saveSightings(
        recognizedPlates: List<String>,
        rawText: String,
        imageUri: String?,
        latitude: Double?,
        longitude: Double?,
        capturedAtEpochMillis: Long,
        source: String,
    ): List<String> {
        val normalizedPlates = recognizedPlates
            .map { it.trim().uppercase() }
            .filter { it.length in 5..10 }
            .distinct()

        if (normalizedPlates.isEmpty()) {
            return emptyList()
        }

        val now = System.currentTimeMillis()
        val syncState = if (remoteSyncDataSource.isConfigured) {
            PlateSyncState.PENDING_UPLOAD.name
        } else {
            PlateSyncState.LOCAL_ONLY.name
        }

        dao.insertAll(
            normalizedPlates.map { plate ->
                PlateSighting(
                    clientGeneratedId = UUID.randomUUID().toString(),
                    plateNumber = plate,
                    rawText = rawText,
                    imageUri = imageUri,
                    latitude = latitude,
                    longitude = longitude,
                    capturedAtEpochMillis = capturedAtEpochMillis,
                    updatedAtEpochMillis = now,
                    source = source,
                    syncState = syncState,
                )
            }
        )

        refreshDiagnostics()
        return normalizedPlates
    }

    suspend fun syncPendingSightings(clientGeneratedIds: List<String>? = null) {
        if (!remoteSyncDataSource.isConfigured) {
            refreshDiagnostics()
            return
        }

        if (!activeSession.isAvailable) {
            activeSession = remoteSyncDataSource.ensureSession(activeSession.groupId)
            if (!activeSession.isAvailable || activeSession.groupId == null) {
                refreshDiagnostics(lastError = activeSession.statusMessage)
                return
            }
        }

        val pendingSightings = if (clientGeneratedIds.isNullOrEmpty()) {
            dao.getBySyncStates(
                syncStates = listOf(
                    PlateSyncState.PENDING_UPLOAD.name,
                    PlateSyncState.SYNC_ERROR.name,
                )
            )
        } else {
            dao.getByClientGeneratedIds(clientGeneratedIds).filter {
                it.syncState == PlateSyncState.PENDING_UPLOAD.name || it.syncState == PlateSyncState.SYNC_ERROR.name
            }
        }

        if (pendingSightings.isEmpty()) {
            refreshDiagnostics(lastSyncAtEpochMillis = syncDiagnostics.value.lastSyncAtEpochMillis)
            return
        }

        val uploadResults = remoteSyncDataSource.uploadSightings(
            session = activeSession,
            sightings = pendingSightings,
        )

        val syncTime = System.currentTimeMillis()
        uploadResults.forEach { result ->
            if (result.errorMessage == null && result.remoteId != null && result.groupId != null && result.createdBy != null) {
                dao.markAsSynced(
                    clientGeneratedId = result.clientGeneratedId,
                    imageUri = result.imageUri,
                    remoteId = result.remoteId,
                    groupId = result.groupId,
                    createdBy = result.createdBy,
                    syncState = PlateSyncState.SYNCED.name,
                    syncMessage = result.warningMessage,
                    lastSyncedAtEpochMillis = syncTime,
                    updatedAtEpochMillis = result.updatedAtEpochMillis,
                )
            } else {
                dao.markSyncError(
                    clientGeneratedId = result.clientGeneratedId,
                    syncState = PlateSyncState.SYNC_ERROR.name,
                    syncError = result.errorMessage ?: "Unknown Firestore sync error",
                )
            }
        }

        val lastError = uploadResults.firstOrNull { it.errorMessage != null }?.errorMessage
        val lastWarning = uploadResults.firstOrNull { it.warningMessage != null }?.warningMessage
        refreshDiagnostics(
            lastSyncAtEpochMillis = syncTime,
            lastError = lastError,
            lastWarning = lastWarning,
        )
    }

    suspend fun retrySighting(clientGeneratedId: String) {
        dao.markPendingUpload(
            clientGeneratedId = clientGeneratedId,
            syncState = PlateSyncState.PENDING_UPLOAD.name,
            updatedAtEpochMillis = System.currentTimeMillis(),
        )
        refreshDiagnostics(lastError = null, lastWarning = null)
        syncPendingSightings(clientGeneratedIds = listOf(clientGeneratedId))
    }

    suspend fun switchActiveGroup(groupId: String) {
        val nextSession = remoteSyncDataSource.ensureSession(groupId)
        activeSession = nextSession.copy(
            availableGroups = remoteSyncDataSource.listGroups(nextSession),
        )
        refreshDiagnostics(lastError = nextSession.statusMessage.takeIf { !nextSession.isAvailable })
        restartRealtimeSync()
    }

    suspend fun joinOrCreateGroup(groupId: String) {
        if (!remoteSyncDataSource.isConfigured) {
            refreshDiagnostics(lastError = "Firebase not configured in this build")
            return
        }
        val session = if (activeSession.isAvailable) activeSession else remoteSyncDataSource.ensureSession(activeSession.groupId)
        val joinedGroup = remoteSyncDataSource.joinOrCreateGroup(session, groupId)
        switchActiveGroup(joinedGroup.id)
    }

    suspend fun removeLocalImage(clientGeneratedId: String, imageUri: String?) {
        imageUri?.let { uriString ->
            val uri = runCatching { Uri.parse(uriString) }.getOrNull()
            if (uri != null && uri.scheme == "file") {
                runCatching {
                    val path = uri.path ?: return@runCatching
                    java.io.File(path).takeIf { it.exists() }?.delete()
                }
            }
        }

        dao.clearImageUri(
            clientGeneratedId = clientGeneratedId,
            updatedAtEpochMillis = System.currentTimeMillis(),
        )
    }

    private fun restartRealtimeSync() {
        val scope = syncScope ?: return
        remoteObservationJob?.cancel()
        remoteObservationJob = scope.launch(Dispatchers.IO) {
            activeSession = remoteSyncDataSource.ensureSession(activeSession.groupId)
            activeSession = activeSession.copy(
                availableGroups = remoteSyncDataSource.listGroups(activeSession),
            )
            refreshDiagnostics(
                lastError = activeSession.statusMessage.takeIf { !activeSession.isAvailable },
            )

            val activeGroupId = activeSession.groupId
            if (!activeSession.isAvailable || activeGroupId == null) {
                return@launch
            }

            syncPendingSightings()
            remoteSyncDataSource.observeSharedSightings(activeGroupId)
                .collect { remoteSightings ->
                    dao.insertAll(remoteSightings.map { it.toLocalEntity() })
                    refreshDiagnostics(lastSyncAtEpochMillis = System.currentTimeMillis())
                }
        }
    }

    private suspend fun refreshDiagnostics(
        lastSyncAtEpochMillis: Long? = syncDiagnostics.value.lastSyncAtEpochMillis,
        lastError: String? = syncDiagnostics.value.lastError,
        lastWarning: String? = syncDiagnostics.value.lastWarning,
    ) {
        val pendingUploadCount = dao.countBySyncStates(
            syncStates = listOf(
                PlateSyncState.PENDING_UPLOAD.name,
                PlateSyncState.SYNC_ERROR.name,
            )
        )
        syncDiagnostics.update {
            SyncDiagnostics(
                isConfigured = remoteSyncDataSource.isConfigured,
                isSignedIn = activeSession.isAvailable,
                providerLabel = activeSession.providerLabel,
                activeUserId = activeSession.userId,
                activeGroupId = activeSession.groupId,
                availableGroups = activeSession.availableGroups,
                pendingUploadCount = pendingUploadCount,
                lastSyncAtEpochMillis = lastSyncAtEpochMillis,
                lastError = lastError,
                lastWarning = lastWarning,
            )
        }
    }
}