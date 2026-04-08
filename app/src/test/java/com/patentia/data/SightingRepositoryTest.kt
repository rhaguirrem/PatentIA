package com.patentia.data

import com.patentia.data.remote.RemotePlateSighting
import com.patentia.data.remote.RemoteSightingSyncDataSource
import com.patentia.data.remote.RemoteSyncSession
import com.patentia.data.remote.RemoteUploadResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SightingRepositoryTest {

    @Test
    fun `removeLocalImage keeps shared file when another sighting still references it`() = runBlocking {
        val sharedFile = createTempImageFile()
        val sharedUri = sharedFile.toURI().toString()
        val dao = FakePlateSightingDao(
            listOf(
                sighting(clientGeneratedId = "one", imageUri = sharedUri),
                sighting(clientGeneratedId = "two", imageUri = sharedUri),
            )
        )
        val repository = SightingRepository(dao, FakeRemoteSightingSyncDataSource())

        repository.removeLocalImage(clientGeneratedId = "one", imageUri = sharedUri)

        assertTrue(sharedFile.exists())
        assertNull(dao.findSighting("one"))
        assertEquals(sharedUri, dao.requireSighting("two").imageUri)
    }

    @Test
    fun `deleteSighting keeps shared file when another sighting still references it`() = runBlocking {
        val sharedFile = createTempImageFile()
        val sharedUri = sharedFile.toURI().toString()
        val dao = FakePlateSightingDao(
            listOf(
                sighting(clientGeneratedId = "one", imageUri = sharedUri),
                sighting(clientGeneratedId = "two", imageUri = sharedUri),
            )
        )
        val repository = SightingRepository(dao, FakeRemoteSightingSyncDataSource())

        val error = repository.deleteSighting(clientGeneratedId = "one", imageUri = sharedUri)

        assertNull(error)
        assertTrue(sharedFile.exists())
        assertNull(dao.findSighting("one"))
        assertNotNull(dao.findSighting("two"))
    }

    @Test
    fun `deleteSighting does not remove file when remote delete fails`() = runBlocking {
        val sharedFile = createTempImageFile()
        val sharedUri = sharedFile.toURI().toString()
        val dao = FakePlateSightingDao(
            listOf(
                sighting(
                    clientGeneratedId = "remote",
                    imageUri = sharedUri,
                    remoteId = "remote-id",
                    groupId = "group-a",
                    syncState = PlateSyncState.SYNCED.name,
                )
            )
        )
        val repository = SightingRepository(
            dao,
            FakeRemoteSightingSyncDataSource(deleteError = "remote failed"),
        )

        val error = repository.deleteSighting(clientGeneratedId = "remote", imageUri = sharedUri)

        assertEquals("remote failed", error)
        assertTrue(sharedFile.exists())
        assertNotNull(dao.findSighting("remote"))
    }

    @Test
    fun `updatePlateLookup stores lookup fields for every matching sighting`() = runBlocking {
        val dao = FakePlateSightingDao(
            listOf(
                sighting(clientGeneratedId = "one"),
                sighting(clientGeneratedId = "two"),
                sighting(clientGeneratedId = "other", plateNumber = "ZZZZ99"),
            )
        )
        val repository = SightingRepository(dao, FakeRemoteSightingSyncDataSource())

        repository.updatePlateLookup(
            plateNumber = "ABCD12",
            lookupSource = "volante_o_maleta",
            ownerName = "Jane Roe",
            ownerRut = "12.345.678-9",
            vehicleMake = "Toyota",
            vehicleModel = "Yaris",
            vehicleYear = "2020",
            vehicleColor = "Rojo",
        )

        assertEquals("Jane Roe", dao.requireSighting("one").lookupOwnerName)
        assertEquals("Toyota", dao.requireSighting("two").lookupVehicleMake)
        assertNull(dao.requireSighting("other").lookupOwnerName)
    }

    private fun createTempImageFile(): File {
        val file = File.createTempFile("shared-image", ".jpg")
        file.writeText("test")
        file.deleteOnExit()
        return file
    }

    private fun sighting(
        clientGeneratedId: String,
        plateNumber: String = "ABCD12",
        imageUri: String? = null,
        remoteId: String? = null,
        groupId: String? = null,
        syncState: String = PlateSyncState.LOCAL_ONLY.name,
    ): PlateSighting = PlateSighting(
        clientGeneratedId = clientGeneratedId,
        remoteId = remoteId,
        groupId = groupId,
        createdBy = null,
        plateNumber = plateNumber,
        rawText = "ABCD12",
        imageUri = imageUri,
        latitude = null,
        longitude = null,
        capturedAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        source = "test",
        syncState = syncState,
    )

    private class FakeRemoteSightingSyncDataSource(
        private val deleteError: String? = null,
    ) : RemoteSightingSyncDataSource {
        override val isConfigured: Boolean = deleteError != null

        override suspend fun ensureSession(preferredGroupId: String?): RemoteSyncSession = RemoteSyncSession(
            isAvailable = true,
            providerLabel = "test",
            userId = "user",
            groupId = preferredGroupId ?: "group-a",
        )

        override suspend fun listGroups(session: RemoteSyncSession): List<SharedGroup> = emptyList()

        override suspend fun joinOrCreateGroup(
            session: RemoteSyncSession,
            groupId: String,
        ): SharedGroup = SharedGroup(id = groupId, name = groupId)

        override fun observeSharedSightings(groupId: String): Flow<List<RemotePlateSighting>> = emptyFlow()

        override suspend fun uploadSightings(
            session: RemoteSyncSession,
            sightings: List<PlateSighting>,
        ): List<RemoteUploadResult> = emptyList()

        override suspend fun deleteSighting(
            session: RemoteSyncSession,
            sighting: PlateSighting,
        ): String? = deleteError
    }

    private class FakePlateSightingDao(initialSightings: List<PlateSighting>) : PlateSightingDao {
        private val sightings = initialSightings.toMutableList()
        private val state = MutableStateFlow(sightings.sortedByDescending { it.capturedAtEpochMillis })

        override fun observeAll(): Flow<List<PlateSighting>> = state

        override suspend fun insertAll(sightings: List<PlateSighting>) {
            sightings.forEach { sighting ->
                val existingIndex = this.sightings.indexOfFirst {
                    it.clientGeneratedId == sighting.clientGeneratedId
                }
                if (existingIndex >= 0) {
                    this.sightings[existingIndex] = sighting
                } else {
                    this.sightings += sighting
                }
            }
            emitState()
        }

        override suspend fun getBySyncStates(syncStates: List<String>): List<PlateSighting> = sightings
            .filter { it.syncState in syncStates }
            .sortedBy { it.capturedAtEpochMillis }

        override suspend fun getByClientGeneratedIds(clientGeneratedIds: List<String>): List<PlateSighting> = sightings
            .filter { it.clientGeneratedId in clientGeneratedIds }
            .sortedBy { it.capturedAtEpochMillis }

        override suspend fun getByPlateNumber(plateNumber: String): List<PlateSighting> = sightings
            .filter { it.plateNumber == plateNumber }
            .sortedBy { it.capturedAtEpochMillis }

        override suspend fun countBySyncStates(syncStates: List<String>): Int =
            sightings.count { it.syncState in syncStates }

        override suspend fun countByImageUri(imageUri: String): Int =
            sightings.count { it.imageUri == imageUri }

        override suspend fun markAsSynced(
            clientGeneratedId: String,
            imageUri: String?,
            remoteId: String,
            groupId: String,
            createdBy: String,
            syncState: String,
            syncMessage: String?,
            lastSyncedAtEpochMillis: Long,
            updatedAtEpochMillis: Long,
        ) {
            replace(clientGeneratedId) {
                it.copy(
                    imageUri = imageUri ?: it.imageUri,
                    remoteId = remoteId,
                    groupId = groupId,
                    createdBy = createdBy,
                    syncState = syncState,
                    syncError = syncMessage,
                    lastSyncedAtEpochMillis = lastSyncedAtEpochMillis,
                    updatedAtEpochMillis = updatedAtEpochMillis,
                )
            }
        }

        override suspend fun markSyncError(
            clientGeneratedId: String,
            syncState: String,
            syncError: String,
        ) {
            replace(clientGeneratedId) {
                it.copy(syncState = syncState, syncError = syncError)
            }
        }

        override suspend fun markPendingUpload(
            clientGeneratedId: String,
            syncState: String,
            updatedAtEpochMillis: Long,
        ) {
            replace(clientGeneratedId) {
                it.copy(syncState = syncState, syncError = null, updatedAtEpochMillis = updatedAtEpochMillis)
            }
        }

        override suspend fun clearImageUri(
            clientGeneratedId: String,
            updatedAtEpochMillis: Long,
        ) {
            replace(clientGeneratedId) {
                it.copy(imageUri = null, updatedAtEpochMillis = updatedAtEpochMillis)
            }
        }

        override suspend fun updatePlateNumber(
            clientGeneratedId: String,
            plateNumber: String,
            syncState: String,
            updatedAtEpochMillis: Long,
        ) {
            replace(clientGeneratedId) {
                it.copy(
                    plateNumber = plateNumber,
                    syncState = syncState,
                    syncError = null,
                    updatedAtEpochMillis = updatedAtEpochMillis,
                )
            }
        }

        override suspend fun deleteByClientGeneratedId(clientGeneratedId: String) {
            sightings.removeAll { it.clientGeneratedId == clientGeneratedId }
            emitState()
        }

        fun findSighting(clientGeneratedId: String): PlateSighting? =
            sightings.firstOrNull { it.clientGeneratedId == clientGeneratedId }

        fun requireSighting(clientGeneratedId: String): PlateSighting =
            requireNotNull(findSighting(clientGeneratedId))

        private suspend fun replace(
            clientGeneratedId: String,
            transform: (PlateSighting) -> PlateSighting,
        ) {
            val index = sightings.indexOfFirst { it.clientGeneratedId == clientGeneratedId }
            check(index >= 0) { "Missing sighting $clientGeneratedId" }
            sightings[index] = transform(sightings[index])
            emitState()
        }

        private fun emitState() {
            state.value = sightings.sortedByDescending { it.capturedAtEpochMillis }
        }
    }
}