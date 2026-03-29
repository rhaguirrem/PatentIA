package com.patentia.data.remote

import com.patentia.data.PlateSighting
import com.patentia.data.SharedGroup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class NoOpRemoteSightingSyncDataSource(
    private val defaultGroupId: String,
) : RemoteSightingSyncDataSource {
    override val isConfigured: Boolean = false

    override suspend fun ensureSession(preferredGroupId: String?): RemoteSyncSession = RemoteSyncSession(
        isAvailable = false,
        providerLabel = "local-only",
        groupId = preferredGroupId ?: defaultGroupId,
        availableGroups = listOf(SharedGroup(preferredGroupId ?: defaultGroupId, "Local device")),
        statusMessage = "Firebase not configured in this build",
    )

    override suspend fun listGroups(session: RemoteSyncSession): List<SharedGroup> = session.availableGroups

    override suspend fun joinOrCreateGroup(
        session: RemoteSyncSession,
        groupId: String,
    ): SharedGroup = SharedGroup(groupId, groupId)

    override fun observeSharedSightings(groupId: String): Flow<List<RemotePlateSighting>> = flowOf(emptyList())

    override suspend fun uploadSightings(
        session: RemoteSyncSession,
        sightings: List<PlateSighting>,
    ): List<RemoteUploadResult> = sightings.map { sighting ->
        RemoteUploadResult(
            clientGeneratedId = sighting.clientGeneratedId,
            errorMessage = "Firebase not configured in this build",
        )
    }

    override suspend fun deleteSighting(
        session: RemoteSyncSession,
        sighting: PlateSighting,
    ): String? = "Firebase not configured in this build"
}