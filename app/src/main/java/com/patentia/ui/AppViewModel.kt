package com.patentia.ui

import android.content.Context
import android.net.Uri
import com.patentia.BuildConfig
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.patentia.data.ExportedPlateHistory
import com.patentia.data.PatentIADatabase
import com.patentia.data.PlateSighting
import com.patentia.data.SightingRepository
import com.patentia.data.SyncDiagnostics
import com.patentia.data.remote.FirebaseRemoteSyncDataSource
import com.patentia.data.remote.NoOpRemoteSightingSyncDataSource
import com.patentia.data.toExportModel
import com.patentia.services.AppUpdateManager
import com.patentia.services.AppImageStore
import com.patentia.services.CurrentLocationProvider
import com.patentia.services.GeoPoint
import com.patentia.services.PlateRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

enum class CaptureMode {
    SINGLE,
    INTERVAL,
}

private const val INTERVAL_CAPTURE_SOURCE = "camera_interval"

enum class TimeFilter {
    ALL,
    LAST_24_HOURS,
    LAST_7_DAYS,
}

enum class LocationStatus {
    CHECKING,
    READY,
    WAITING_FOR_FIX,
    DISABLED,
}

enum class AppUpdateStatus {
    IDLE,
    CHECKING,
    AVAILABLE,
    DOWNLOADING,
    UP_TO_DATE,
    INSTALLING,
    ERROR,
}

data class AppUpdateUiState(
    val installedVersionName: String = "",
    val installedVersionCode: Long = 0,
    val status: AppUpdateStatus = AppUpdateStatus.IDLE,
    val availableVersionName: String? = null,
    val availableVersionCode: Long? = null,
    val downloadSizeBytes: Long? = null,
    val availableDownloadUrl: String? = null,
    val availablePageUrl: String? = null,
    val downloadedApkPath: String? = null,
    val statusMessage: String = "Check the shared update manifest for updates.",
    val lastCheckedAtEpochMillis: Long? = null,
)

data class PendingImageReview(
    val imageUri: String,
    val source: String,
    val rawText: String,
    val recognizedPlates: List<String> = emptyList(),
    val selectedLocation: GeoPoint? = null,
)

data class AppUiState(
    val allSightings: List<PlateSighting> = emptyList(),
    val filteredSightings: List<PlateSighting> = emptyList(),
    val selectedPlateHistory: List<PlateSighting> = emptyList(),
    val currentLocation: GeoPoint? = null,
    val locationStatus: LocationStatus = LocationStatus.CHECKING,
    val selectedPlate: String? = null,
    val searchQuery: String = "",
    val repeatedOnly: Boolean = false,
    val timeFilter: TimeFilter = TimeFilter.ALL,
    val captureMode: CaptureMode = CaptureMode.SINGLE,
    val intervalSeconds: Int = 5,
    val intervalRunning: Boolean = false,
    val statusMessage: String = "Ready for capture",
    val pendingManualImageUri: String? = null,
    val hasPendingManualImage: Boolean = false,
    val pendingImageReview: PendingImageReview? = null,
    val lastRecognizedPlates: List<String> = emptyList(),
    val theoreticalRadiusMeters: Double = 0.0,
    val syncDiagnostics: SyncDiagnostics = SyncDiagnostics(),
    val appUpdate: AppUpdateUiState = AppUpdateUiState(),
)

private data class FilterInputs(
    val sightings: List<PlateSighting>,
    val syncDiagnostics: SyncDiagnostics,
    val searchQuery: String,
    val repeatedOnly: Boolean,
    val timeFilter: TimeFilter,
)

private data class RuntimeInputs(
    val selectedPlate: String?,
    val captureMode: CaptureMode,
    val intervalSeconds: Int,
    val intervalRunning: Boolean,
    val statusMessage: String,
    val pendingManualImageUri: String?,
    val hasPendingManualImage: Boolean,
    val pendingImageReview: PendingImageReview?,
)

internal fun calculateTheoreticalRadiusMeters(
    sightings: List<PlateSighting>,
    nowEpochMillis: Long = System.currentTimeMillis(),
): Double {
    val latestWithPosition = sightings.lastOrNull {
        it.latitude != null && it.longitude != null
    } ?: return 0.0

    val elapsedMillis = (nowEpochMillis - latestWithPosition.capturedAtEpochMillis).coerceAtLeast(0)
    val maxMetersPerSecond = 130.0 * 1000.0 / 3600.0
    return maxMetersPerSecond * (elapsedMillis / 1000.0)
}

class AppViewModel(
    private val appContext: Context,
    private val repository: SightingRepository,
    private val plateRecognizer: PlateRecognizer,
    private val currentLocationProvider: CurrentLocationProvider,
    private val imageStore: AppImageStore,
    private val appUpdateManager: AppUpdateManager,
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")
    private val repeatedOnly = MutableStateFlow(false)
    private val timeFilter = MutableStateFlow(TimeFilter.ALL)
    private val selectedPlate = MutableStateFlow<String?>(null)
    private val captureMode = MutableStateFlow(CaptureMode.SINGLE)
    private val intervalSeconds = MutableStateFlow(5)
    private val intervalRunning = MutableStateFlow(false)
    private val statusMessage = MutableStateFlow("Ready for capture")
    private val pendingManualImageUri = MutableStateFlow<String?>(null)
    private val pendingManualSource = MutableStateFlow<String?>(null)
    private val pendingImageReview = MutableStateFlow<PendingImageReview?>(null)
    private val lastRecognizedPlates = MutableStateFlow<List<String>>(emptyList())
    private val currentLocation = MutableStateFlow<GeoPoint?>(null)
    private val locationStatus = MutableStateFlow(LocationStatus.CHECKING)
    private val installedAppVersion = appUpdateManager.getInstalledVersionInfo()
    private val appUpdateState = MutableStateFlow(
        AppUpdateUiState(
            installedVersionName = installedAppVersion.versionName,
            installedVersionCode = installedAppVersion.versionCode,
        )
    )

    init {
        repository.startRealtimeSync(viewModelScope)
    }

    private val filterInputs = combine(
        repository.observeSightings(),
        repository.observeSyncDiagnostics(),
        searchQuery,
        repeatedOnly,
        timeFilter,
    ) { sightings, syncDiagnostics, query, repeated, window ->
        FilterInputs(
            sightings = sightings,
            syncDiagnostics = syncDiagnostics,
            searchQuery = query,
            repeatedOnly = repeated,
            timeFilter = window,
        )
    }

    private val captureStatus = combine(
        statusMessage,
        pendingManualImageUri,
        pendingImageReview,
    ) { status, manualImageUri, imageReview ->
        Triple(status, manualImageUri, manualImageUri != null) to imageReview
    }

    private val runtimeInputs = combine(
        selectedPlate,
        captureMode,
        intervalSeconds,
        intervalRunning,
        captureStatus,
    ) { selected, mode, interval, running, captureStatus ->
        RuntimeInputs(
            selectedPlate = selected,
            captureMode = mode,
            intervalSeconds = interval,
            intervalRunning = running,
            statusMessage = captureStatus.first.first,
            pendingManualImageUri = captureStatus.first.second,
            hasPendingManualImage = captureStatus.first.third,
            pendingImageReview = captureStatus.second,
        )
    }

    private val baseUiState = combine(
        filterInputs,
        runtimeInputs,
        lastRecognizedPlates,
        currentLocation,
        locationStatus,
    ) { filters, runtime, recognized, location, gpsStatus ->
        val sightings = filters.sightings
        val syncDiagnostics = filters.syncDiagnostics
        val query = filters.searchQuery
        val repeated = filters.repeatedOnly
        val window = filters.timeFilter
        val selected = runtime.selectedPlate
        val mode = runtime.captureMode
        val interval = runtime.intervalSeconds
        val running = runtime.intervalRunning
        val status = runtime.statusMessage

        val cutoff = when (window) {
            TimeFilter.ALL -> 0L
            TimeFilter.LAST_24_HOURS -> System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24)
            TimeFilter.LAST_7_DAYS -> System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
        }

        val timeFiltered = sightings.filter { it.capturedAtEpochMillis >= cutoff }
        val repeatedPlates = timeFiltered.groupBy { it.plateNumber }
            .filterValues { it.size > 1 }
            .keys

        val filtered = timeFiltered.filter { sighting ->
            val matchesQuery = query.isBlank() || sighting.plateNumber.contains(query.trim().uppercase())
            val matchesRepeated = !repeated || sighting.plateNumber in repeatedPlates
            matchesQuery && matchesRepeated
        }

        val selectedHistory = sightings
            .filter { it.plateNumber == selected }
            .sortedBy { it.capturedAtEpochMillis }

        val theoreticalRadius = calculateTheoreticalRadiusMeters(selectedHistory)

        AppUiState(
            allSightings = sightings,
            filteredSightings = filtered,
            selectedPlateHistory = selectedHistory,
            currentLocation = location,
            locationStatus = gpsStatus,
            selectedPlate = selected,
            searchQuery = query,
            repeatedOnly = repeated,
            timeFilter = window,
            captureMode = mode,
            intervalSeconds = interval,
            intervalRunning = running,
            statusMessage = status,
            pendingManualImageUri = runtime.pendingManualImageUri,
            hasPendingManualImage = runtime.hasPendingManualImage,
            pendingImageReview = runtime.pendingImageReview,
            lastRecognizedPlates = recognized,
            theoreticalRadiusMeters = theoreticalRadius,
            syncDiagnostics = syncDiagnostics,
        )
    }

    val uiState: StateFlow<AppUiState> = combine(
        baseUiState,
        appUpdateState,
    ) { baseUiState, updateUiState ->
        baseUiState.copy(appUpdate = updateUiState)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppUiState(),
    )

    fun updateSearchQuery(value: String) {
        searchQuery.value = value
    }

    fun setRepeatedOnly(value: Boolean) {
        repeatedOnly.value = value
    }

    fun setTimeFilter(value: TimeFilter) {
        timeFilter.value = value
    }

    fun refreshCurrentLocation() {
        viewModelScope.launch {
            locationStatus.value = LocationStatus.CHECKING
            if (!currentLocationProvider.isLocationEnabled()) {
                currentLocation.value = null
                locationStatus.value = LocationStatus.DISABLED
                return@launch
            }

            val location = currentLocationProvider.getCurrentLocation()
            currentLocation.value = location
            locationStatus.value = if (location != null) {
                LocationStatus.READY
            } else {
                LocationStatus.WAITING_FOR_FIX
            }
        }
    }

    fun selectPlate(plateNumber: String?) {
        selectedPlate.value = plateNumber
    }

    fun setCaptureMode(mode: CaptureMode) {
        captureMode.value = mode
        if (mode == CaptureMode.SINGLE) {
            intervalRunning.value = false
        }
    }

    fun setIntervalSeconds(value: Int) {
        intervalSeconds.value = value.coerceIn(2, 60)
    }

    fun toggleIntervalCapture() {
        intervalRunning.update { !it }
        statusMessage.value = if (intervalRunning.value) {
            "Interval capture running"
        } else {
            "Interval capture stopped"
        }
    }

    fun saveManualPlate(plateNumber: String) {
        viewModelScope.launch {
            val normalizedPlates = parsePlateInput(plateNumber)

            if (normalizedPlates.isEmpty()) {
                statusMessage.value = "Enter at least one valid plate with 5 to 10 letters or numbers"
                return@launch
            }

            lastRecognizedPlates.value = normalizedPlates
            val imageUri = pendingManualImageUri.value
            val source = pendingManualSource.value?.let { "${it}_manual" } ?: "manual_entry"

            saveSightings(
                recognizedPlates = normalizedPlates,
                rawText = normalizedPlates.joinToString(", "),
                imageUri = imageUri,
                source = source,
            )
            clearPendingManualImage(deleteImage = false)
        }
    }

    fun discardPendingManualCapture() {
        val hadPendingImage = pendingManualImageUri.value != null
        clearPendingManualImage(deleteImage = true)
        if (hadPendingImage) {
            statusMessage.value = "Unsaved photo discarded"
        }
    }

    fun updatePendingImageReviewLocation(location: GeoPoint) {
        pendingImageReview.update { review ->
            review?.copy(selectedLocation = location)
        }
    }

    fun savePendingImageReview(plateInput: String) {
        viewModelScope.launch {
            val review = pendingImageReview.value ?: return@launch
            val normalizedPlates = parsePlateInput(plateInput)
            if (normalizedPlates.isEmpty()) {
                statusMessage.value = "Enter at least one valid plate with 5 to 10 letters or numbers"
                return@launch
            }

            lastRecognizedPlates.value = normalizedPlates
            saveSightings(
                recognizedPlates = normalizedPlates,
                rawText = review.rawText.ifBlank { normalizedPlates.joinToString(", ") },
                imageUri = review.imageUri,
                source = review.source,
                locationOverride = review.selectedLocation,
            )
            clearPendingImageReview(deleteImage = false)
        }
    }

    fun discardPendingImageReview() {
        val hadPendingReview = pendingImageReview.value != null
        clearPendingImageReview(deleteImage = true)
        if (hadPendingReview) {
            statusMessage.value = "Uploaded photo discarded"
        }
    }

    fun processImage(imageUri: Uri, source: String) {
        viewModelScope.launch {
            statusMessage.value = "Recognizing licence plate"
            val persistedImageUri = runCatching { imageStore.persist(imageUri) }
                .getOrElse {
                    statusMessage.value = it.message ?: "Could not store image"
                    return@launch
                }

            val recognition = runCatching {
                plateRecognizer.recognize(appContext, persistedImageUri)
            }.getOrElse {
                lastRecognizedPlates.value = emptyList()
                if (source == "gallery") {
                    openPendingImageReview(
                        imageUri = persistedImageUri.toString(),
                        source = source,
                        rawText = "",
                        recognizedPlates = emptyList(),
                    )
                    statusMessage.value = "Pan the map to set the uploaded photo location, then enter the plate to save"
                    return@launch
                }
                if (source == INTERVAL_CAPTURE_SOURCE) {
                    discardIntervalCapture(
                        imageUri = persistedImageUri,
                        message = (it.message ?: "Plate recognition failed") + ". Interval capture discarded the photo"
                    )
                    return@launch
                }
                replacePendingManualImage(persistedImageUri.toString(), source)
                statusMessage.value = (it.message ?: "Plate recognition failed") + ". Tap Write plate to save it manually"
                return@launch
            }
            lastRecognizedPlates.value = recognition.plates

            if (source == "gallery") {
                openPendingImageReview(
                    imageUri = persistedImageUri.toString(),
                    source = source,
                    rawText = recognition.rawText,
                    recognizedPlates = recognition.plates,
                )
                statusMessage.value = "Pan the map to set the uploaded photo location, then confirm the plate"
                return@launch
            }

            if (recognition.plates.isEmpty()) {
                if (source == INTERVAL_CAPTURE_SOURCE) {
                    discardIntervalCapture(
                        imageUri = persistedImageUri,
                        message = "No plate pattern recognized. Interval capture discarded the photo"
                    )
                    return@launch
                }
                replacePendingManualImage(persistedImageUri.toString(), source)
                statusMessage.value = "No plate pattern recognized. Tap Write plate to save it manually"
                return@launch
            }

            clearPendingManualImage(deleteImage = true)
            saveSightings(
                recognizedPlates = recognition.plates,
                rawText = recognition.rawText,
                imageUri = persistedImageUri.toString(),
                source = source,
            )
        }
    }

    private fun discardIntervalCapture(imageUri: Uri, message: String) {
        imageStore.delete(imageUri)
        statusMessage.value = message
    }

    private suspend fun saveSightings(
        recognizedPlates: List<String>,
        rawText: String,
        imageUri: String?,
        source: String,
        locationOverride: GeoPoint? = null,
    ): List<String> {
        statusMessage.value = "Getting GPS position"
        val location = locationOverride ?: currentLocationProvider.getCurrentLocation()
        val savedPlates = repository.saveSightings(
            recognizedPlates = recognizedPlates,
            rawText = rawText,
            imageUri = imageUri,
            latitude = location?.latitude,
            longitude = location?.longitude,
            capturedAtEpochMillis = System.currentTimeMillis(),
            source = source,
        )
        repository.syncPendingSightings()

        selectedPlate.value = savedPlates.firstOrNull()
        val syncDiagnostics = repository.observeSyncDiagnostics().value
        statusMessage.value = buildString {
            append("Saved ")
            append(savedPlates.joinToString())
            if (location == null) {
                append(" without GPS fix")
            }
            when {
                !syncDiagnostics.isConfigured -> append(" locally only")
                syncDiagnostics.lastError != null -> append(" with cloud sync pending")
                syncDiagnostics.lastWarning != null -> append(" with shared metadata only")
                else -> append(" to group ${syncDiagnostics.activeGroupId ?: BuildConfig.DEFAULT_FIRESTORE_GROUP_ID}")
            }
        }
        return savedPlates
    }

    private fun replacePendingManualImage(imageUri: String, source: String) {
        val previousImageUri = pendingManualImageUri.value
        if (previousImageUri != null && previousImageUri != imageUri) {
            imageStore.delete(Uri.parse(previousImageUri))
        }
        pendingManualImageUri.value = imageUri
        pendingManualSource.value = source
    }

    private fun clearPendingManualImage(deleteImage: Boolean) {
        val imageUri = pendingManualImageUri.value
        if (deleteImage && imageUri != null) {
            imageStore.delete(Uri.parse(imageUri))
        }
        pendingManualImageUri.value = null
        pendingManualSource.value = null
    }

    private suspend fun openPendingImageReview(
        imageUri: String,
        source: String,
        rawText: String,
        recognizedPlates: List<String>,
    ) {
        val fallbackLocation = currentLocation.value ?: currentLocationProvider.getCurrentLocation()
        pendingImageReview.value = PendingImageReview(
            imageUri = imageUri,
            source = source,
            rawText = rawText,
            recognizedPlates = recognizedPlates,
            selectedLocation = fallbackLocation,
        )
    }

    private fun clearPendingImageReview(deleteImage: Boolean) {
        val review = pendingImageReview.value
        if (deleteImage && review != null) {
            imageStore.delete(Uri.parse(review.imageUri))
        }
        pendingImageReview.value = null
    }

    private fun parsePlateInput(plateInput: String): List<String> = plateInput
        .split(',', '\n', '\t', ' ')
        .map { candidate -> candidate.uppercase().filter(Char::isLetterOrDigit) }
        .filter { it.length in 5..10 }
        .distinct()

    fun buildPlateSharePayload(plateNumber: String): String? {
        val normalizedPlate = plateNumber.trim().uppercase()
        if (normalizedPlate.isBlank()) {
            return null
        }

        val history = uiState.value.allSightings
            .filter { it.plateNumber == normalizedPlate }
            .sortedBy { it.capturedAtEpochMillis }
        if (history.isEmpty()) {
            return null
        }

        val payload = ExportedPlateHistory(
            plateNumber = normalizedPlate,
            sharedAtEpochMillis = System.currentTimeMillis(),
            sightings = history.map { it.toExportModel() },
        )

        return Json {
            prettyPrint = true
        }.encodeToString(payload)
    }

    fun buildSelectedPlateSharePayload(): String? {
        val plateNumber = uiState.value.selectedPlate ?: return null
        return buildPlateSharePayload(plateNumber)
    }

    internal fun storePlateLookup(lookup: PatenteChileLookup) {
        viewModelScope.launch {
            repository.updatePlateLookup(
                plateNumber = lookup.plateNumber,
                lookupSource = VOLANTE_O_MALETA_LOOKUP_SOURCE,
                ownerName = lookup.ownerName,
                ownerRut = lookup.ownerRut,
                vehicleMake = lookup.vehicleMake,
                vehicleModel = lookup.vehicleModel,
                vehicleYear = lookup.vehicleYear,
                vehicleColor = lookup.vehicleColor,
            )
            statusMessage.value = "Stored Volante o Maleta data for ${lookup.plateNumber}"
        }
    }

    fun retrySighting(clientGeneratedId: String) {
        viewModelScope.launch {
            statusMessage.value = "Retrying cloud sync"
            repository.retrySighting(clientGeneratedId)
            statusMessage.value = "Retry finished"
        }
    }

    fun updateSightingPlate(clientGeneratedId: String, currentPlateNumber: String?, correctedPlateNumber: String) {
        viewModelScope.launch {
            statusMessage.value = "Updating plate"
            val errorMessage = repository.updateSightingPlate(clientGeneratedId, correctedPlateNumber)
            if (errorMessage != null) {
                statusMessage.value = errorMessage
                return@launch
            }

            val normalizedPlate = correctedPlateNumber.uppercase().filter(Char::isLetterOrDigit)
            lastRecognizedPlates.value = listOf(normalizedPlate)
            if (uiState.value.selectedPlate == currentPlateNumber) {
                selectedPlate.value = normalizedPlate
            }
            statusMessage.value = "Plate updated to $normalizedPlate"
        }
    }

    fun retryPendingSync() {
        viewModelScope.launch {
            statusMessage.value = "Retrying pending uploads"
            repository.syncPendingSightings()
            statusMessage.value = "Sync refresh finished"
        }
    }

    fun switchActiveGroup(groupId: String) {
        viewModelScope.launch {
            statusMessage.value = "Switching shared group"
            repository.switchActiveGroup(groupId)
            statusMessage.value = "Active group: $groupId"
        }
    }

    fun joinOrCreateGroup(groupId: String) {
        viewModelScope.launch {
            statusMessage.value = "Joining shared group"
            repository.joinOrCreateGroup(groupId)
            statusMessage.value = "Group ready: $groupId"
        }
    }

    fun removeLocalImage(clientGeneratedId: String, imageUri: String?) {
        viewModelScope.launch {
            statusMessage.value = "Removing local image"
            repository.removeLocalImage(clientGeneratedId, imageUri)
            statusMessage.value = "Local image removed"
        }
    }

    fun deleteSighting(clientGeneratedId: String, imageUri: String?) {
        viewModelScope.launch {
            statusMessage.value = "Deleting history entry"
            val errorMessage = repository.deleteSighting(clientGeneratedId, imageUri)
            statusMessage.value = errorMessage ?: "History entry deleted"
        }
    }

    fun checkForAppUpdate() {
        viewModelScope.launch {
            statusMessage.value = "Checking shared APK for updates"
            appUpdateState.update {
                it.copy(
                    status = AppUpdateStatus.CHECKING,
                    statusMessage = "Fetching the remote update manifest.",
                    lastCheckedAtEpochMillis = System.currentTimeMillis(),
                    downloadedApkPath = null,
                )
            }

            when (val result = appUpdateManager.checkForUpdate()) {
                is AppUpdateManager.UpdateCheckResult.UpdateAvailable -> {
                    appUpdateState.value = appUpdateState.value.copy(
                        status = AppUpdateStatus.AVAILABLE,
                        availableVersionName = result.remoteVersion.versionName,
                        availableVersionCode = result.remoteVersion.versionCode,
                        downloadSizeBytes = result.remoteVersion.fileSizeBytes,
                        availableDownloadUrl = result.downloadUrl,
                        availablePageUrl = result.pageUrl,
                        downloadedApkPath = null,
                        statusMessage = "Update found. Tap Install update to download it and open the Android package installer.",
                        lastCheckedAtEpochMillis = System.currentTimeMillis(),
                    )
                    statusMessage.value = "Update ${result.remoteVersion.versionName} is available"
                }

                is AppUpdateManager.UpdateCheckResult.UpToDate -> {
                    appUpdateState.value = appUpdateState.value.copy(
                        status = AppUpdateStatus.UP_TO_DATE,
                        availableVersionName = result.remoteVersionName,
                        availableVersionCode = result.remoteVersionCode,
                        downloadSizeBytes = null,
                        availableDownloadUrl = null,
                        availablePageUrl = result.pageUrl,
                        downloadedApkPath = null,
                        statusMessage = "This device is already on the newest published build.",
                        lastCheckedAtEpochMillis = System.currentTimeMillis(),
                    )
                    statusMessage.value = "PatentIA is already up to date"
                }

                is AppUpdateManager.UpdateCheckResult.Failed -> {
                    appUpdateState.value = appUpdateState.value.copy(
                        status = AppUpdateStatus.ERROR,
                        availableVersionName = null,
                        availableVersionCode = null,
                        downloadSizeBytes = null,
                        availableDownloadUrl = null,
                        availablePageUrl = null,
                        downloadedApkPath = null,
                        statusMessage = result.message,
                        lastCheckedAtEpochMillis = System.currentTimeMillis(),
                    )
                    statusMessage.value = "Update check failed"
                }
            }
        }
    }

    fun installDownloadedUpdate() {
        viewModelScope.launch {
            val currentUpdateState = appUpdateState.value
            val downloadedApkPath = currentUpdateState.downloadedApkPath
            val apkPathToInstall = if (downloadedApkPath.isNullOrBlank()) {
                val downloadUrl = currentUpdateState.availableDownloadUrl
                if (downloadUrl.isNullOrBlank()) {
                    appUpdateState.update {
                        it.copy(
                            status = AppUpdateStatus.ERROR,
                            statusMessage = "Run Check for updates first so the app can resolve the latest download URL.",
                        )
                    }
                    return@launch
                }

                statusMessage.value = "Downloading update package"
                appUpdateState.update {
                    it.copy(
                        status = AppUpdateStatus.DOWNLOADING,
                        statusMessage = "Downloading the update package.",
                    )
                }

                when (val downloadResult = appUpdateManager.downloadUpdateApk(downloadUrl)) {
                    is AppUpdateManager.DownloadUpdateResult.Downloaded -> {
                        appUpdateState.update {
                            it.copy(
                                status = AppUpdateStatus.AVAILABLE,
                                downloadedApkPath = downloadResult.downloadedApkPath,
                                downloadSizeBytes = downloadResult.fileSizeBytes,
                                statusMessage = "Update downloaded. Opening the Android package installer next.",
                            )
                        }
                        downloadResult.downloadedApkPath
                    }

                    is AppUpdateManager.DownloadUpdateResult.Failed -> {
                        appUpdateState.update {
                            it.copy(
                                status = AppUpdateStatus.ERROR,
                                statusMessage = downloadResult.message,
                            )
                        }
                        statusMessage.value = "Update download failed"
                        return@launch
                    }
                }
            } else {
                downloadedApkPath
            }

            statusMessage.value = "Opening Android installer"
            appUpdateState.update {
                it.copy(
                    status = AppUpdateStatus.INSTALLING,
                    statusMessage = "Opening the Android package installer.",
                )
            }

            when (val result = appUpdateManager.installDownloadedApk(apkPathToInstall)) {
                is AppUpdateManager.InstallUpdateResult.InstallerOpened -> {
                    appUpdateState.update {
                        it.copy(
                            status = AppUpdateStatus.INSTALLING,
                            statusMessage = result.message,
                        )
                    }
                    statusMessage.value = "Installer opened"
                }

                is AppUpdateManager.InstallUpdateResult.PermissionRequired -> {
                    appUpdateState.update {
                        it.copy(
                            status = AppUpdateStatus.AVAILABLE,
                            statusMessage = result.message,
                        )
                    }
                    statusMessage.value = "Enable unknown app installs, then retry"
                }

                is AppUpdateManager.InstallUpdateResult.Failed -> {
                    appUpdateState.update {
                        it.copy(
                            status = AppUpdateStatus.ERROR,
                            statusMessage = result.message,
                        )
                    }
                    statusMessage.value = "Installer could not be opened"
                }
            }
        }
    }

    companion object {
        fun factory(appContext: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val database = PatentIADatabase.getInstance(appContext)
                val remoteSyncDataSource = if (FirebaseRemoteSyncDataSource.isFirebaseConfigured(appContext)) {
                    FirebaseRemoteSyncDataSource(appContext)
                } else {
                    NoOpRemoteSightingSyncDataSource(defaultGroupId = BuildConfig.DEFAULT_FIRESTORE_GROUP_ID)
                }
                val repository = SightingRepository(
                    dao = database.plateSightingDao(),
                    remoteSyncDataSource = remoteSyncDataSource,
                )
                val recognizer = PlateRecognizer()
                val locationProvider = CurrentLocationProvider(appContext)
                val imageStore = AppImageStore(appContext)
                val appUpdateManager = AppUpdateManager(appContext)

                @Suppress("UNCHECKED_CAST")
                return AppViewModel(
                    appContext = appContext,
                    repository = repository,
                    plateRecognizer = recognizer,
                    currentLocationProvider = locationProvider,
                    imageStore = imageStore,
                    appUpdateManager = appUpdateManager,
                ) as T
            }
        }
    }
}