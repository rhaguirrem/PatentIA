package com.patentia.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(
    tableName = "plate_sightings",
    indices = [
        Index(value = ["clientGeneratedId"], unique = true),
        Index(value = ["remoteId"], unique = true),
    ],
)
data class PlateSighting(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientGeneratedId: String,
    val remoteId: String? = null,
    val groupId: String? = null,
    val createdBy: String? = null,
    val plateNumber: String,
    val rawText: String,
    val imageUri: String?,
    val latitude: Double?,
    val longitude: Double?,
    val capturedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val source: String,
    val syncState: String = PlateSyncState.LOCAL_ONLY.name,
    val syncError: String? = null,
    val lastSyncedAtEpochMillis: Long? = null,
)

@Serializable
data class ExportedPlateHistory(
    val plateNumber: String,
    val sharedAtEpochMillis: Long,
    val sightings: List<ExportedPlateSighting>,
)

@Serializable
data class ExportedPlateSighting(
    val plateNumber: String,
    val capturedAtEpochMillis: Long,
    val latitude: Double?,
    val longitude: Double?,
    val source: String,
    val rawText: String,
)

enum class PlateSyncState {
    LOCAL_ONLY,
    PENDING_UPLOAD,
    SYNCED,
    SYNC_ERROR,
}

data class SyncDiagnostics(
    val isConfigured: Boolean = false,
    val isSignedIn: Boolean = false,
    val providerLabel: String = "local-only",
    val activeUserId: String? = null,
    val activeGroupId: String? = null,
    val availableGroups: List<SharedGroup> = emptyList(),
    val pendingUploadCount: Int = 0,
    val lastSyncAtEpochMillis: Long? = null,
    val lastError: String? = null,
    val lastWarning: String? = null,
)

data class SharedGroup(
    val id: String,
    val name: String,
)

fun PlateSighting.toExportModel(): ExportedPlateSighting = ExportedPlateSighting(
    plateNumber = plateNumber,
    capturedAtEpochMillis = capturedAtEpochMillis,
    latitude = latitude,
    longitude = longitude,
    source = source,
    rawText = rawText,
)