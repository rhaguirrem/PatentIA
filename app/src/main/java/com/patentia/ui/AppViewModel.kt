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
import com.patentia.services.CurrentLocationProvider
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

enum class TimeFilter {
    ALL,
    LAST_24_HOURS,
    LAST_7_DAYS,
}

data class AppUiState(
    val allSightings: List<PlateSighting> = emptyList(),
    val filteredSightings: List<PlateSighting> = emptyList(),
    val selectedPlateHistory: List<PlateSighting> = emptyList(),
    val selectedPlate: String? = null,
    val searchQuery: String = "",
    val repeatedOnly: Boolean = false,
    val timeFilter: TimeFilter = TimeFilter.ALL,
    val captureMode: CaptureMode = CaptureMode.SINGLE,
    val intervalSeconds: Int = 5,
    val intervalRunning: Boolean = false,
    val statusMessage: String = "Ready for capture",
    val lastRecognizedPlates: List<String> = emptyList(),
    val theoreticalRadiusMeters: Double = 0.0,
    val syncDiagnostics: SyncDiagnostics = SyncDiagnostics(),
)

class AppViewModel(
    private val appContext: Context,
    private val repository: SightingRepository,
    private val plateRecognizer: PlateRecognizer,
    private val currentLocationProvider: CurrentLocationProvider,
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")
    private val repeatedOnly = MutableStateFlow(false)
    private val timeFilter = MutableStateFlow(TimeFilter.ALL)
    private val selectedPlate = MutableStateFlow<String?>(null)
    private val captureMode = MutableStateFlow(CaptureMode.SINGLE)
    private val intervalSeconds = MutableStateFlow(5)
    private val intervalRunning = MutableStateFlow(false)
    private val statusMessage = MutableStateFlow("Ready for capture")
    private val lastRecognizedPlates = MutableStateFlow<List<String>>(emptyList())

    init {
        repository.startRealtimeSync(viewModelScope)
    }

    val uiState: StateFlow<AppUiState> = combine(
        repository.observeSightings(),
        repository.observeSyncDiagnostics(),
        searchQuery,
        repeatedOnly,
        timeFilter,
        selectedPlate,
        captureMode,
        intervalSeconds,
        intervalRunning,
        statusMessage,
        lastRecognizedPlates,
    ) { values ->
        val sightings = values[0] as List<PlateSighting>
        val syncDiagnostics = values[1] as SyncDiagnostics
        val query = values[2] as String
        val repeated = values[3] as Boolean
        val window = values[4] as TimeFilter
        val selected = values[5] as String?
        val mode = values[6] as CaptureMode
        val interval = values[7] as Int
        val running = values[8] as Boolean
        val status = values[9] as String
        val recognized = values[10] as List<String>

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

        val latestWithPosition = selectedHistory.lastOrNull {
            it.latitude != null && it.longitude != null
        }

        val theoreticalRadius = latestWithPosition?.let {
            val elapsedMillis = (System.currentTimeMillis() - it.capturedAtEpochMillis).coerceAtLeast(0)
            val maxMetersPerSecond = 130.0 * 1000.0 / 3600.0
            maxMetersPerSecond * (elapsedMillis / 1000.0)
        } ?: 0.0

        AppUiState(
            allSightings = sightings,
            filteredSightings = filtered,
            selectedPlateHistory = selectedHistory,
            selectedPlate = selected,
            searchQuery = query,
            repeatedOnly = repeated,
            timeFilter = window,
            captureMode = mode,
            intervalSeconds = interval,
            intervalRunning = running,
            statusMessage = status,
            lastRecognizedPlates = recognized,
            theoreticalRadiusMeters = theoreticalRadius,
            syncDiagnostics = syncDiagnostics,
        )
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

    fun processImage(imageUri: Uri, source: String) {
        viewModelScope.launch {
            statusMessage.value = "Recognizing licence plate"
            val recognition = plateRecognizer.recognize(appContext, imageUri)
            lastRecognizedPlates.value = recognition.plates

            if (recognition.plates.isEmpty()) {
                statusMessage.value = "No plate pattern recognized"
                return@launch
            }

            val primaryPlate = recognition.plates.first()

            statusMessage.value = "Getting GPS position"
            val location = currentLocationProvider.getCurrentLocation()
            val savedPlates = repository.saveSightings(
                recognizedPlates = listOf(primaryPlate),
                rawText = recognition.rawText,
                imageUri = imageUri.toString(),
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
        }
    }

    fun buildSelectedPlateSharePayload(): String? {
        val plateNumber = uiState.value.selectedPlate ?: return null
        val history = uiState.value.selectedPlateHistory
        if (history.isEmpty()) {
            return null
        }

        val payload = ExportedPlateHistory(
            plateNumber = plateNumber,
            sharedAtEpochMillis = System.currentTimeMillis(),
            sightings = history.map { it.toExportModel() },
        )

        return Json {
            prettyPrint = true
        }.encodeToString(payload)
    }

    fun retrySighting(clientGeneratedId: String) {
        viewModelScope.launch {
            statusMessage.value = "Retrying cloud sync"
            repository.retrySighting(clientGeneratedId)
            statusMessage.value = "Retry finished"
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

                @Suppress("UNCHECKED_CAST")
                return AppViewModel(
                    appContext = appContext,
                    repository = repository,
                    plateRecognizer = recognizer,
                    currentLocationProvider = locationProvider,
                ) as T
            }
        }
    }
}