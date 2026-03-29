package com.patentia.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlateSightingDao {

    @Query("SELECT * FROM plate_sightings ORDER BY capturedAtEpochMillis DESC")
    fun observeAll(): Flow<List<PlateSighting>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sightings: List<PlateSighting>)

    @Query("SELECT * FROM plate_sightings WHERE syncState IN (:syncStates) ORDER BY capturedAtEpochMillis ASC")
    suspend fun getBySyncStates(syncStates: List<String>): List<PlateSighting>

    @Query("SELECT * FROM plate_sightings WHERE clientGeneratedId IN (:clientGeneratedIds) ORDER BY capturedAtEpochMillis ASC")
    suspend fun getByClientGeneratedIds(clientGeneratedIds: List<String>): List<PlateSighting>

    @Query("SELECT COUNT(*) FROM plate_sightings WHERE syncState IN (:syncStates)")
    suspend fun countBySyncStates(syncStates: List<String>): Int

    @Query(
        """
        UPDATE plate_sightings
        SET imageUri = COALESCE(:imageUri, imageUri),
            remoteId = :remoteId,
            groupId = :groupId,
            createdBy = :createdBy,
            syncState = :syncState,
            syncError = :syncMessage,
            lastSyncedAtEpochMillis = :lastSyncedAtEpochMillis,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE clientGeneratedId = :clientGeneratedId
        """
    )
    suspend fun markAsSynced(
        clientGeneratedId: String,
        imageUri: String?,
        remoteId: String,
        groupId: String,
        createdBy: String,
        syncState: String,
        syncMessage: String?,
        lastSyncedAtEpochMillis: Long,
        updatedAtEpochMillis: Long,
    )

    @Query(
        """
        UPDATE plate_sightings
        SET syncState = :syncState,
            syncError = :syncError
        WHERE clientGeneratedId = :clientGeneratedId
        """
    )
    suspend fun markSyncError(
        clientGeneratedId: String,
        syncState: String,
        syncError: String,
    )

    @Query(
        """
        UPDATE plate_sightings
        SET syncState = :syncState,
            syncError = NULL,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE clientGeneratedId = :clientGeneratedId
        """
    )
    suspend fun markPendingUpload(
        clientGeneratedId: String,
        syncState: String,
        updatedAtEpochMillis: Long,
    )

    @Query(
        """
        UPDATE plate_sightings
        SET imageUri = NULL,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE clientGeneratedId = :clientGeneratedId
        """
    )
    suspend fun clearImageUri(
        clientGeneratedId: String,
        updatedAtEpochMillis: Long,
    )

    @Query("DELETE FROM plate_sightings WHERE clientGeneratedId = :clientGeneratedId")
    suspend fun deleteByClientGeneratedId(clientGeneratedId: String)
}