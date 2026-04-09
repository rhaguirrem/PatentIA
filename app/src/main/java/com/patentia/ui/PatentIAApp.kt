package com.patentia.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ScaleGestureDetector
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage

import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.patentia.BuildConfig
import com.patentia.data.PlateSighting
import com.patentia.data.PlateSyncState
import com.patentia.data.SyncDiagnostics
import com.patentia.services.AppImageStore
import com.patentia.services.GeoPoint
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.delay
import kotlin.math.abs

private enum class AppPanel(val label: String) {
    Camera("Camera"),
    Map("Map"),
    Cloudsync("Cloudsync"),
    History("History"),
    About("About"),
}

private enum class PlateLookupProvider(
    val displayName: String,
    val homeUrl: String,
) {
    VOLANTE_O_MALETA(
        displayName = "Volante o Maleta",
        homeUrl = VOLANTE_O_MALETA_HOME_URL,
    ),
}

private data class PlateLookupRequest(
    val plateNumber: String,
    val provider: PlateLookupProvider,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatentIAApp(viewModel: AppViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedPanel by rememberSaveable { mutableStateOf(AppPanel.Camera) }
    var pendingHistoryImageUri by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var permissionRefreshToken by remember { mutableStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionRefreshToken += 1
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionRefreshToken += 1
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val hasCameraPermission = remember(permissionRefreshToken) {
        isPermissionGranted(context, android.Manifest.permission.CAMERA)
    }
    val hasLocationPermission = remember(permissionRefreshToken) {
        isPermissionGranted(context, android.Manifest.permission.ACCESS_FINE_LOCATION) ||
            isPermissionGranted(context, android.Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    LaunchedEffect(permissionRefreshToken, hasLocationPermission) {
        if (hasLocationPermission) {
            viewModel.refreshCurrentLocation()
        }
    }

    MaterialTheme(
        colorScheme = patentIAColorScheme,
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    CenterAlignedTopAppBar(
                        title = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("PatentIA", fontWeight = FontWeight.Black)
                                Text(
                                    text = syncHeadline(uiState.syncDiagnostics, uiState.statusMessage),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    )
                },
            ) { innerPadding ->
                if (!hasCameraPermission || !hasLocationPermission) {
                    PermissionGate(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        hasCameraPermission = hasCameraPermission,
                        hasLocationPermission = hasLocationPermission,
                        onRequestPermissions = {
                            permissionLauncher.launch(
                                arrayOf(
                                    android.Manifest.permission.CAMERA,
                                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                                )
                            )
                        },
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AppPanel.entries.forEach { panel ->
                                FilterChip(
                                    selected = selectedPanel == panel,
                                    onClick = { selectedPanel = panel },
                                    label = { Text(panel.label) },
                                    colors = patentIAFilterChipColors(),
                                )
                            }
                        }

                        when (selectedPanel) {
                            AppPanel.Camera -> CameraPanel(
                                modifier = Modifier
                                    .weight(1f, fill = true)
                                    .fillMaxWidth(),
                                uiState = uiState,
                                onCaptureModeChange = viewModel::setCaptureMode,
                                onIntervalSecondsChange = viewModel::setIntervalSeconds,
                                onToggleInterval = viewModel::toggleIntervalCapture,
                                onSaveManualPlate = viewModel::saveManualPlate,
                                onDiscardPendingManualCapture = viewModel::discardPendingManualCapture,
                                onImageCaptured = viewModel::processImage,
                            )

                            AppPanel.Map -> MapScreen(
                                modifier = Modifier
                                    .weight(1f, fill = true)
                                    .fillMaxWidth(),
                                uiState = uiState,
                                onSearchChange = viewModel::updateSearchQuery,
                                onRepeatedOnlyChange = viewModel::setRepeatedOnly,
                                onTimeFilterChange = viewModel::setTimeFilter,
                                onSelectPlate = viewModel::selectPlate,
                                onOpenHistoryForPlate = { sighting ->
                                    viewModel.selectPlate(sighting.plateNumber)
                                    pendingHistoryImageUri = sighting.imageUri?.takeIf { it.isNotBlank() }
                                    selectedPanel = AppPanel.History
                                },
                            )

                            AppPanel.Cloudsync -> CloudSyncPanel(
                                modifier = Modifier
                                    .weight(1f, fill = true)
                                    .fillMaxWidth(),
                                uiState = uiState,
                                onSwitchGroup = viewModel::switchActiveGroup,
                                onJoinOrCreateGroup = viewModel::joinOrCreateGroup,
                                onRetryPendingSync = viewModel::retryPendingSync,
                            )

                            AppPanel.History -> HistoryScreen(
                                modifier = Modifier
                                    .weight(1f, fill = true)
                                    .fillMaxWidth(),
                                uiState = uiState,
                                onSearchChange = viewModel::updateSearchQuery,
                                onRepeatedOnlyChange = viewModel::setRepeatedOnly,
                                onTimeFilterChange = viewModel::setTimeFilter,
                                onSharePlate = { plateNumber ->
                                    viewModel.buildPlateSharePayload(plateNumber)?.let { payload ->
                                        shareText(context, payload)
                                    }
                                },
                                onStoreLookup = viewModel::storePlateLookup,
                                onEditSightingPlate = viewModel::updateSightingPlate,
                                onRetrySighting = viewModel::retrySighting,
                                onDeleteSighting = viewModel::deleteSighting,
                                initialImageUri = pendingHistoryImageUri,
                                onInitialImageConsumed = { pendingHistoryImageUri = null },
                            )

                            AppPanel.About -> AboutPanel(
                                modifier = Modifier
                                    .weight(1f, fill = true)
                                    .fillMaxWidth(),
                                uiState = uiState,
                                hasCameraPermission = hasCameraPermission,
                                hasLocationPermission = hasLocationPermission,
                                onSelectPlate = viewModel::selectPlate,
                                onRefreshLocation = viewModel::refreshCurrentLocation,
                                onCheckForUpdates = viewModel::checkForAppUpdate,
                                onInstallUpdate = viewModel::installDownloadedUpdate,
                                onOpenUpdatePage = { openBrowserUrl(context, BuildConfig.APP_UPDATE_PAGE_URL) },
                            )
                        }
                    }
                }
            }

            uiState.pendingImageReview?.let { review ->
                UploadedPhotoReviewDialog(
                    review = review,
                    currentLocation = uiState.currentLocation,
                    isMapsConfigured = BuildConfig.IS_MAPS_API_KEY_CONFIGURED,
                    onDismiss = viewModel::discardPendingImageReview,
                    onLocationChange = viewModel::updatePendingImageReviewLocation,
                    onSave = viewModel::savePendingImageReview,
                )
            }
        }
    }
}

@Composable
private fun PermissionGate(
    modifier: Modifier = Modifier,
    hasCameraPermission: Boolean,
    hasLocationPermission: Boolean,
    onRequestPermissions: () -> Unit,
) {
    Box(
        modifier = modifier
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF071421), Color(0xFF0F2F47), Color(0xFF163E4E))
                )
            )
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xCC0D1E2A)),
            shape = RoundedCornerShape(28.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Permissions required", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Text(
                    text = buildString {
                        if (!hasCameraPermission) append("Camera access is needed for quick plate capture. ")
                        if (!hasLocationPermission) append("Location access is needed to stamp each observation with coordinates.")
                    },
                    color = Color(0xFFE1F1FF),
                )
                OutlinedButton(onClick = onRequestPermissions) {
                    Text("Grant access")
                }
            }
        }
    }
}

@Composable
private fun CameraPanel(
    modifier: Modifier = Modifier,
    uiState: AppUiState,
    onCaptureModeChange: (CaptureMode) -> Unit,
    onIntervalSecondsChange: (Int) -> Unit,
    onToggleInterval: () -> Unit,
    onSaveManualPlate: (String) -> Unit,
    onDiscardPendingManualCapture: () -> Unit,
    onImageCaptured: (Uri, String) -> Unit,
) {
    var manualPlateDialogVisible by rememberSaveable { mutableStateOf(false) }
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            if (uri != null) {
                onImageCaptured(uri, "gallery")
            }
        }
    )

    Column(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CaptureControls(
            uiState = uiState,
            onCaptureModeChange = onCaptureModeChange,
            onIntervalSecondsChange = onIntervalSecondsChange,
            onToggleInterval = onToggleInterval,
            onPickImage = { imagePickerLauncher.launch("image/*") },
            onManualPlateEntry = { manualPlateDialogVisible = true },
            onDiscardPendingManualCapture = onDiscardPendingManualCapture,
        )
        CameraCaptureCard(
            modifier = Modifier.weight(1f, fill = true),
            uiState = uiState,
            onImageCaptured = onImageCaptured,
        )
    }

    if (manualPlateDialogVisible) {
        ManualPlateEntryDialog(
            pendingManualImageUri = uiState.pendingManualImageUri,
            initialPlate = "",
            title = "Write plate",
            saveLabel = "Save",
            onDismiss = { manualPlateDialogVisible = false },
            onSave = { plateNumber ->
                onSaveManualPlate(plateNumber)
                manualPlateDialogVisible = false
            },
            imageActionLabel = "Discard photo",
            onImageAction = {
                onDiscardPendingManualCapture()
                manualPlateDialogVisible = false
            },
        )
    }
}

@Composable
private fun CloudSyncPanel(
    modifier: Modifier = Modifier,
    uiState: AppUiState,
    onSwitchGroup: (String) -> Unit,
    onJoinOrCreateGroup: (String) -> Unit,
    onRetryPendingSync: () -> Unit,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SyncStatusCard(
            syncDiagnostics = uiState.syncDiagnostics,
            onRetryPendingSync = onRetryPendingSync,
            onSwitchGroup = onSwitchGroup,
            onJoinOrCreateGroup = onJoinOrCreateGroup,
        )
    }
}

@Composable
private fun AboutPanel(
    modifier: Modifier = Modifier,
    uiState: AppUiState,
    hasCameraPermission: Boolean,
    hasLocationPermission: Boolean,
    onSelectPlate: (String?) -> Unit,
    onRefreshLocation: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onInstallUpdate: () -> Unit,
    onOpenUpdatePage: () -> Unit,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DriverSummaryCard(uiState = uiState, onSelectPlate = onSelectPlate)
        GpsStatusCard(
            locationStatus = uiState.locationStatus,
            currentLocation = uiState.currentLocation,
            onRefreshLocation = onRefreshLocation,
        )
        AppUpdateCard(
            appUpdate = uiState.appUpdate,
            onCheckForUpdates = onCheckForUpdates,
            onInstallUpdate = onInstallUpdate,
            onOpenUpdatePage = onOpenUpdatePage,
        )
        SetupChecklistCard(
            syncDiagnostics = uiState.syncDiagnostics,
            hasCameraPermission = hasCameraPermission,
            hasLocationPermission = hasLocationPermission,
        )
    }
}

@Composable
private fun GpsStatusCard(
    locationStatus: LocationStatus,
    currentLocation: GeoPoint?,
    onRefreshLocation: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "GPS status",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            AssistChip(
                onClick = onRefreshLocation,
                label = { Text(gpsStatusLabel(locationStatus)) },
                leadingIcon = {
                    when (locationStatus) {
                        LocationStatus.READY -> Icon(Icons.Default.CheckCircle, contentDescription = null)
                        LocationStatus.DISABLED -> Icon(Icons.Default.CloudOff, contentDescription = null)
                        LocationStatus.WAITING_FOR_FIX -> Icon(Icons.Default.Route, contentDescription = null)
                        LocationStatus.CHECKING -> CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                },
                trailingIcon = {
                    Icon(Icons.Default.Sync, contentDescription = null)
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = gpsStatusContainerColor(locationStatus),
                    labelColor = gpsStatusContentColor(locationStatus),
                    leadingIconContentColor = gpsStatusContentColor(locationStatus),
                    trailingIconContentColor = gpsStatusContentColor(locationStatus),
                ),
            )
            Text(
                text = gpsStatusDetail(locationStatus, currentLocation),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SetupChecklistCard(
    syncDiagnostics: SyncDiagnostics,
    hasCameraPermission: Boolean,
    hasLocationPermission: Boolean,
) {
    val checklistItems = remember(syncDiagnostics, hasCameraPermission, hasLocationPermission) {
        listOf(
            ChecklistItem(
                label = "Camera and location permissions granted",
                done = hasCameraPermission && hasLocationPermission,
            ),
            ChecklistItem(
                label = "Google Maps API key configured",
                done = BuildConfig.IS_MAPS_API_KEY_CONFIGURED,
                detail = "Required for the map tab to render correctly on device.",
            ),
            ChecklistItem(
                label = "Firebase configured",
                done = syncDiagnostics.isConfigured,
                detail = "Add app/google-services.json and enable Auth, Firestore, and Storage.",
            ),
            ChecklistItem(
                label = "Shared group selected",
                done = syncDiagnostics.isConfigured && !syncDiagnostics.activeGroupId.isNullOrBlank(),
                detail = "Use the sync card to join or create a team group.",
            ),
        )
    }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "First-run checklist",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            checklistItems.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = if (item.done) Icons.Default.CheckCircle else Icons.Default.CloudOff,
                        contentDescription = null,
                        tint = if (item.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(item.label, fontWeight = FontWeight.SemiBold)
                        item.detail?.let {
                            Text(it, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppUpdateCard(
    appUpdate: AppUpdateUiState,
    onCheckForUpdates: () -> Unit,
    onInstallUpdate: () -> Unit,
    onOpenUpdatePage: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "App updates",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Installed ${formatVersionLabel(appUpdate.installedVersionName, appUpdate.installedVersionCode)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = appUpdate.statusMessage,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            appUpdate.availableVersionName?.let { versionName ->
                AssistChip(
                    onClick = { },
                    label = {
                        Text(
                            "Shared APK ${formatVersionLabel(versionName, appUpdate.availableVersionCode)}" +
                                formatByteCountSuffix(appUpdate.downloadSizeBytes)
                        )
                    },
                )
            }
            appUpdate.lastCheckedAtEpochMillis?.let { checkedAt ->
                Text(
                    text = "Last checked ${formatTimestamp(checkedAt)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (
                appUpdate.status == AppUpdateStatus.CHECKING ||
                appUpdate.status == AppUpdateStatus.DOWNLOADING ||
                appUpdate.status == AppUpdateStatus.INSTALLING
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onCheckForUpdates,
                    enabled = appUpdate.status != AppUpdateStatus.CHECKING && appUpdate.status != AppUpdateStatus.DOWNLOADING,
                ) {
                    Text("Check for updates")
                }
                OutlinedButton(
                    onClick = onInstallUpdate,
                    enabled = appUpdate.availableVersionCode != null &&
                        appUpdate.status != AppUpdateStatus.CHECKING &&
                        appUpdate.status != AppUpdateStatus.DOWNLOADING,
                ) {
                    Text("Install update")
                }
            }
            TextButton(onClick = onOpenUpdatePage) {
                Text("Open shared APK link")
            }
        }
    }
}

@Composable
private fun DriverSummaryCard(
    uiState: AppUiState,
    onSelectPlate: (String?) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Rapid roadside capture", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
            Text(
                driverSummaryText(uiState.syncDiagnostics),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (uiState.lastRecognizedPlates.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    uiState.lastRecognizedPlates.take(3).forEach { plate ->
                        AssistChip(
                            onClick = { onSelectPlate(plate) },
                            label = { Text(plate) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncStatusCard(
    syncDiagnostics: SyncDiagnostics,
    onRetryPendingSync: () -> Unit,
    onSwitchGroup: (String) -> Unit,
    onJoinOrCreateGroup: (String) -> Unit,
) {
    val isCloudSyncAvailable = syncDiagnostics.isConfigured
    var groupCode by rememberSaveable(syncDiagnostics.activeGroupId, syncDiagnostics.availableGroups) {
        mutableStateOf(syncDiagnostics.activeGroupId.orEmpty())
    }
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                syncTitle(syncDiagnostics),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                syncSubtitle(syncDiagnostics),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isCloudSyncAvailable && syncDiagnostics.availableGroups.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    syncDiagnostics.availableGroups.take(4).forEach { group ->
                        FilterChip(
                            selected = group.id == syncDiagnostics.activeGroupId,
                            onClick = { onSwitchGroup(group.id) },
                            label = { Text(group.name) },
                            leadingIcon = {
                                Icon(Icons.Default.Groups, contentDescription = null)
                            },
                            colors = patentIAFilterChipColors(),
                        )
                    }
                }
            }
            if (isCloudSyncAvailable) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = groupCode,
                    onValueChange = { groupCode = it },
                    label = { Text("Group code") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onJoinOrCreateGroup(groupCode) },
                        enabled = groupCode.isNotBlank(),
                    ) {
                        Icon(Icons.Default.Groups, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Join or create")
                    }
                    OutlinedButton(
                        onClick = onRetryPendingSync,
                        enabled = syncDiagnostics.pendingUploadCount > 0 || syncDiagnostics.lastError != null,
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Retry now")
                    }
                }
            }
            if (isCloudSyncAvailable && (syncDiagnostics.pendingUploadCount > 0 || syncDiagnostics.lastError != null)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (syncDiagnostics.pendingUploadCount > 0) {
                        AssistChip(
                            onClick = { },
                            label = { Text("Pending ${syncDiagnostics.pendingUploadCount}") },
                        )
                    }
                    syncDiagnostics.lastError?.let {
                        AssistChip(
                            onClick = { },
                            label = { Text("Sync issue") },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CaptureControls(
    uiState: AppUiState,
    onCaptureModeChange: (CaptureMode) -> Unit,
    onIntervalSecondsChange: (Int) -> Unit,
    onToggleInterval: () -> Unit,
    onPickImage: () -> Unit,
    onManualPlateEntry: () -> Unit,
    onDiscardPendingManualCapture: () -> Unit,
) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CaptureActionButton(
                    modifier = Modifier.weight(1f),
                    selected = uiState.captureMode == CaptureMode.SINGLE,
                    onClick = { onCaptureModeChange(CaptureMode.SINGLE) },
                    icon = Icons.Default.CameraAlt,
                    contentDescription = "Single shot",
                )
                CaptureActionButton(
                    modifier = Modifier.weight(1f),
                    selected = uiState.captureMode == CaptureMode.INTERVAL,
                    onClick = { onCaptureModeChange(CaptureMode.INTERVAL) },
                    icon = Icons.Default.Timer,
                    contentDescription = "Interval mode",
                )
                CaptureActionButton(
                    modifier = Modifier.weight(1f),
                    selected = false,
                    onClick = onManualPlateEntry,
                    icon = Icons.Default.Edit,
                    contentDescription = if (uiState.hasPendingManualImage) "Write plate from photo" else "Write plate",
                )
                CaptureActionButton(
                    modifier = Modifier.weight(1f),
                    selected = false,
                    onClick = onPickImage,
                    icon = Icons.Default.PhotoLibrary,
                    contentDescription = "Upload image",
                )
            }
            Text(
                text = "Uploaded photos now open a review step where you can pan the map under the center pin before saving.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            if (uiState.hasPendingManualImage) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "A photo is waiting for manual plate entry.",
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        TextButton(onClick = onDiscardPendingManualCapture) {
                            Text("Discard")
                        }
                    }
                }
            }
            if (uiState.captureMode == CaptureMode.INTERVAL) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Interval: ${uiState.intervalSeconds} s")
                    Slider(
                        value = uiState.intervalSeconds.toFloat(),
                        onValueChange = { onIntervalSecondsChange(it.toInt()) },
                        valueRange = 2f..60f,
                    )
                    OutlinedButton(onClick = onToggleInterval) {
                        Text(if (uiState.intervalRunning) "Stop interval" else "Start interval")
                    }
                }
            }
        }
    }
}

@Composable
private fun CaptureActionButton(
    modifier: Modifier = Modifier,
    selected: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    OutlinedButton(
        modifier = modifier,
        onClick = onClick,
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(containerColor)
                .padding(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = contentColor,
            )
        }
    }
}

@Composable
private fun ManualPlateEntryDialog(
    pendingManualImageUri: String?,
    initialPlate: String,
    title: String,
    saveLabel: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    imageActionLabel: String? = null,
    onImageAction: (() -> Unit)? = null,
) {
    var plateNumber by rememberSaveable(initialPlate) { mutableStateOf(initialPlate) }
    val hasPendingManualImage = !pendingManualImageUri.isNullOrBlank()
    val context = LocalContext.current
    val isPendingImageDisplayable = remember(context, pendingManualImageUri) {
        isImageDisplayable(context, pendingManualImageUri)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                    TextButton(onClick = onDismiss) {
                        Text("Close")
                    }
                }
                Text(
                    text = if (hasPendingManualImage) {
                        "The captured photo stays visible while you add the plate. Pinch to zoom and drag to inspect details."
                    } else {
                        "Use manual input when OCR misses a plate or when you need to correct a saved one."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (hasPendingManualImage) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = true),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (isPendingImageDisplayable) {
                                ZoomableAsyncImage(
                                    imageUri = pendingManualImageUri,
                                    contentDescription = "Pending manual plate photo",
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(12.dp),
                                )
                                Text(
                                    text = "Pinch to zoom, drag to move",
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(16.dp),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            } else {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "The pending photo is no longer available.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = plateNumber,
                            onValueChange = { plateNumber = it.uppercase() },
                            label = { Text("Plate") },
                            placeholder = { Text("ABCD12") },
                            singleLine = true,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        ) {
                            if (hasPendingManualImage && imageActionLabel != null && onImageAction != null) {
                                TextButton(onClick = onImageAction) {
                                    Text(imageActionLabel)
                                }
                            }
                            OutlinedButton(
                                onClick = { onSave(plateNumber) },
                                enabled = plateNumber.isNotBlank(),
                            ) {
                                Text(saveLabel)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UploadedPhotoReviewDialog(
    review: PendingImageReview,
    currentLocation: GeoPoint?,
    isMapsConfigured: Boolean,
    onDismiss: () -> Unit,
    onLocationChange: (GeoPoint) -> Unit,
    onSave: (String) -> Unit,
) {
    val context = LocalContext.current
    val isPendingImageDisplayable = remember(context, review.imageUri) {
        isImageDisplayable(context, review.imageUri)
    }
    var plateInput by rememberSaveable(review.imageUri) {
        mutableStateOf(review.recognizedPlates.joinToString(", "))
    }
    var mapPickerVisible by rememberSaveable(review.imageUri) { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Review uploaded photo", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                        Text(
                            "Pan and zoom the map until the center pin matches the photo location.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Discard")
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.42f, fill = true)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (isPendingImageDisplayable) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(168.dp),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                        ) {
                            ZoomableAsyncImage(
                                imageUri = review.imageUri,
                                contentDescription = "Uploaded photo preview",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(12.dp),
                            )
                        }
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            OutlinedTextField(
                                modifier = Modifier.fillMaxWidth(),
                                value = plateInput,
                                onValueChange = { plateInput = it.uppercase() },
                                label = { Text("Plate") },
                                placeholder = { Text("ABCD12 or ABCD12, XY1234") },
                            )
                            if (review.recognizedPlates.isNotEmpty()) {
                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    review.recognizedPlates.forEach { suggestion ->
                                        AssistChip(
                                            onClick = { plateInput = suggestion },
                                            label = { Text(suggestion) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    OutlinedButton(
                        onClick = { mapPickerVisible = true },
                        enabled = plateInput.isNotBlank() && isMapsConfigured,
                    ) {
                        Icon(Icons.Default.LocationOn, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Set Location")
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    OutlinedButton(
                        onClick = { onSave(plateInput) },
                        enabled = plateInput.isNotBlank(),
                    ) {
                        Text("Save photo")
                    }
                }

                review.selectedLocation?.let { location ->
                    Text(
                        text = "Selected location: ${formatCoordinate(location.latitude)}, ${formatCoordinate(location.longitude)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (!isMapsConfigured) {
                    Text(
                        text = "Map location picker is unavailable in this build because Google Maps is not configured.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (mapPickerVisible && isMapsConfigured) {
        LocationPickerDialog(
            initialLocation = review.selectedLocation ?: currentLocation,
            currentLocation = currentLocation,
            onCancel = { mapPickerVisible = false },
            onConfirm = { location ->
                onLocationChange(location)
                mapPickerVisible = false
            },
        )
    }
}

@Composable
private fun LocationPickerDialog(
    initialLocation: GeoPoint?,
    currentLocation: GeoPoint?,
    onCancel: () -> Unit,
    onConfirm: (GeoPoint) -> Unit,
) {
    val fallbackLocation = initialLocation ?: currentLocation ?: GeoPoint(-33.4489, -70.6693)
    val mapProperties = remember(currentLocation) {
        MapProperties(isMyLocationEnabled = currentLocation != null)
    }
    val mapUiSettings = remember(currentLocation) {
        MapUiSettings(
            myLocationButtonEnabled = currentLocation != null,
            zoomControlsEnabled = false,
        )
    }
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(
            LatLng(fallbackLocation.latitude, fallbackLocation.longitude),
            if (initialLocation != null) 17f else 15f,
        )
    }

    LaunchedEffect(initialLocation?.latitude, initialLocation?.longitude, currentLocation?.latitude, currentLocation?.longitude) {
        val target = initialLocation ?: currentLocation ?: fallbackLocation
        cameraPositionState.move(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(target.latitude, target.longitude),
                if (initialLocation != null) 17f else 15f,
            )
        )
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Set location", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                        Text(
                            "Pan and zoom the map until the center pin matches the location.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onCancel) {
                        Text("Close")
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = true)
                        .clip(RoundedCornerShape(24.dp)),
                ) {
                    GoogleMap(
                        modifier = Modifier.fillMaxSize(),
                        cameraPositionState = cameraPositionState,
                        properties = mapProperties,
                        uiSettings = mapUiSettings,
                    )
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = "Selected location",
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(42.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }

                Text(
                    text = "Selected coordinates: ${formatCoordinate(cameraPositionState.position.target.latitude)}, ${formatCoordinate(cameraPositionState.position.target.longitude)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(onClick = onCancel) {
                        Text("Cancel")
                    }
                    OutlinedButton(
                        onClick = {
                            val target = cameraPositionState.position.target
                            onConfirm(GeoPoint(latitude = target.latitude, longitude = target.longitude))
                        },
                    ) {
                        Text("Confirm")
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomableAsyncImage(
    imageUri: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    var scale by rememberSaveable(imageUri) { mutableStateOf(1f) }
    var offsetX by rememberSaveable(imageUri) { mutableStateOf(0f) }
    var offsetY by rememberSaveable(imageUri) { mutableStateOf(0f) }

    AsyncImage(
        model = imageUri,
        contentDescription = contentDescription,
        modifier = modifier
            .pointerInput(imageUri) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val nextScale = (scale * zoom).coerceIn(1f, 5f)
                    if (abs(nextScale - 1f) < 0.01f) {
                        scale = 1f
                        offsetX = 0f
                        offsetY = 0f
                    } else {
                        scale = nextScale
                        offsetX += pan.x
                        offsetY += pan.y
                    }
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offsetX
                translationY = offsetY
            },
        contentScale = ContentScale.Fit,
    )
}

@Composable
private fun CameraCaptureCard(
    modifier: Modifier = Modifier,
    uiState: AppUiState,
    onImageCaptured: (Uri, String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    var cameraErrorMessage by remember { mutableStateOf<String?>(null) }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val scaleGestureDetector = remember {
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val camera = boundCamera ?: return false
                    val zoomState = camera.cameraInfo.zoomState.value ?: return false
                    val nextZoomRatio = (zoomState.zoomRatio * detector.scaleFactor)
                        .coerceIn(1f, zoomState.maxZoomRatio)
                    camera.cameraControl.setZoomRatio(nextZoomRatio)
                    return true
                }
            },
        )
    }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val listener = Runnable {
            val cameraProvider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture,
                )
                boundCamera = camera
                cameraErrorMessage = null
            } catch (exception: Exception) {
                boundCamera = null
                cameraErrorMessage = exception.message ?: "Unable to open the camera on this device"
            }
        }

        providerFuture.addListener(listener, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { providerFuture.get().unbindAll() }
            cameraExecutor.shutdown()
        }
    }

    LaunchedEffect(uiState.captureMode, uiState.intervalRunning, uiState.intervalSeconds) {
        while (uiState.captureMode == CaptureMode.INTERVAL && uiState.intervalRunning) {
            delay(uiState.intervalSeconds * 1_000L)
            capturePhoto(
                context = context,
                imageCapture = imageCapture,
                executor = cameraExecutor,
                source = "camera_interval",
                onImageCaptured = onImageCaptured,
                onCaptureError = { message -> cameraErrorMessage = message },
            )
        }
    }

    Card(
        shape = RoundedCornerShape(28.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = true)
                    .heightIn(min = 420.dp),
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = {
                        previewView.apply {
                            setOnTouchListener { _, event ->
                                scaleGestureDetector.onTouchEvent(event)
                                true
                            }
                        }
                    },
                )

                FloatingActionButton(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 18.dp)
                        .size(88.dp),
                    shape = CircleShape,
                    onClick = {
                        capturePhoto(
                            context = context,
                            imageCapture = imageCapture,
                            executor = cameraExecutor,
                            source = "camera_manual",
                            onImageCaptured = onImageCaptured,
                            onCaptureError = { message -> cameraErrorMessage = message },
                        )
                    },
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = "Capture",
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "Tip: keep the plate centered, pinch with two fingers to zoom, and reduce glare for better OCR.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                cameraErrorMessage?.let { message ->
                    Text(
                        text = message,
                        color = Color(0xFFFF8A80),
                    )
                }
            }
        }
    }
}

@Composable
private fun MapScreen(
    modifier: Modifier = Modifier,
    uiState: AppUiState,
    onSearchChange: (String) -> Unit,
    onRepeatedOnlyChange: (Boolean) -> Unit,
    onTimeFilterChange: (TimeFilter) -> Unit,
    onSelectPlate: (String?) -> Unit,
    onOpenHistoryForPlate: (PlateSighting) -> Unit,
) {
    val mappedSightings = uiState.filteredSightings.filter { it.latitude != null && it.longitude != null }
    val selectedPath = uiState.selectedPlateHistory.filter { it.latitude != null && it.longitude != null }
    val currentLocation = uiState.currentLocation
    var selectedMapPreview by remember { mutableStateOf<PlateSighting?>(null) }
    var controlsVisible by rememberSaveable { mutableStateOf(false) }
    var mapLoaded by rememberSaveable { mutableStateOf(false) }
    var showMapTroubleshooting by rememberSaveable { mutableStateOf(false) }
    var hasInitializedCamera by rememberSaveable { mutableStateOf(false) }
    val isMapsConfigured = BuildConfig.IS_MAPS_API_KEY_CONFIGURED
    val mapProperties = remember(currentLocation) {
        MapProperties(
            isMyLocationEnabled = currentLocation != null,
        )
    }
    val mapUiSettings = remember {
        MapUiSettings(
            myLocationButtonEnabled = true,
            zoomControlsEnabled = false,
        )
    }
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(48.8566, 2.3522), 10f)
    }

    LaunchedEffect(isMapsConfigured) {
        mapLoaded = false
        showMapTroubleshooting = false
        if (isMapsConfigured) {
            delay(6_000)
            if (!mapLoaded) {
                showMapTroubleshooting = true
            }
        }
    }

    LaunchedEffect(mappedSightings.firstOrNull()?.id, uiState.selectedPlate, currentLocation) {
        selectedMapPreview = selectedMapPreview?.let { preview ->
            mappedSightings.firstOrNull { it.clientGeneratedId == preview.clientGeneratedId }
        }
        val focus = selectedPath.lastOrNull() ?: mappedSightings.firstOrNull()
        if (focus != null) {
            val position = LatLng(
                focus.latitude ?: return@LaunchedEffect,
                focus.longitude ?: return@LaunchedEffect,
            )
            val update = if (hasInitializedCamera) {
                CameraUpdateFactory.newLatLng(position)
            } else {
                CameraUpdateFactory.newLatLngZoom(position, 12f)
            }
            cameraPositionState.move(update)
            hasInitializedCamera = true
        } else if (currentLocation != null) {
            val position = LatLng(currentLocation.latitude, currentLocation.longitude)
            val update = if (hasInitializedCamera) {
                CameraUpdateFactory.newLatLng(position)
            } else {
                CameraUpdateFactory.newLatLngZoom(position, 15f)
            }
            cameraPositionState.move(update)
            hasInitializedCamera = true
        }
    }

    Box(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Card(
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (isMapsConfigured) {
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = cameraPositionState,
                    properties = mapProperties,
                    uiSettings = mapUiSettings,
                    onMapClick = {
                        selectedMapPreview = null
                    },
                    onMapLoaded = {
                        mapLoaded = true
                        showMapTroubleshooting = false
                    },
                ) {
                    mappedSightings.forEach { sighting ->
                        val latitude = sighting.latitude ?: return@forEach
                        val longitude = sighting.longitude ?: return@forEach
                        Marker(
                            state = MarkerState(position = LatLng(latitude, longitude)),
                            title = sighting.plateNumber,
                            snippet = formatTimestamp(sighting.capturedAtEpochMillis),
                            onClick = {
                                onSelectPlate(sighting.plateNumber)
                                selectedMapPreview = sighting
                                true
                            },
                        )
                    }

                    if (selectedPath.size >= 2) {
                        Polyline(
                            points = selectedPath.map { LatLng(it.latitude ?: return@map LatLng(0.0, 0.0), it.longitude ?: return@map LatLng(0.0, 0.0)) },
                            color = MaterialTheme.colorScheme.primary,
                            width = 8f,
                        )
                    }

                    selectedPath.lastOrNull()?.let { last ->
                        val latitude = last.latitude ?: return@let
                        val longitude = last.longitude ?: return@let
                        if (uiState.theoreticalRadiusMeters > 0.0) {
                            Circle(
                                center = LatLng(latitude, longitude),
                                radius = uiState.theoreticalRadiusMeters,
                                fillColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f),
                                strokeColor = MaterialTheme.colorScheme.secondary,
                                strokeWidth = 4f,
                            )
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("Map unavailable", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "The Google Maps API key is not configured in this build, so street tiles cannot load.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (mappedSightings.isEmpty()) {
            Card(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                ),
                shape = RoundedCornerShape(20.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("No mapped sightings yet", fontWeight = FontWeight.Bold)
                    Text(
                        "Capture a plate with GPS, or open controls to adjust filters and group selection.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        selectedMapPreview?.let { sighting ->
            if (!controlsVisible && !mappedSightings.isEmpty()) {
                MapMarkerPreviewCard(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(12.dp),
                    sighting = sighting,
                    onOpenHistory = {
                        selectedMapPreview = null
                        onOpenHistoryForPlate(sighting)
                    },
                    onDismiss = {
                        selectedMapPreview = null
                    },
                )
            }
        }

        if (isMapsConfigured && !mapLoaded && !showMapTroubleshooting) {
            Card(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                ),
                shape = RoundedCornerShape(20.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    Text("Loading map tiles")
                }
            }
        }

        if (showMapTroubleshooting) {
            Card(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
                ),
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Map tiles did not load", fontWeight = FontWeight.Bold)
                    Text(
                        "This build has a Maps key, but Google Maps did not finish loading. Check the key restrictions, package name, SHA certificate, internet access, and whether Maps SDK for Android is enabled.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (controlsVisible) {
            Card(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(12.dp)
                    .heightIn(max = 340.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
                ),
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Map controls", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "${mappedSightings.size} visible markers",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { controlsVisible = false }) {
                            Text("Close")
                        }
                    }

                    FilterPanel(
                        uiState = uiState,
                        onSearchChange = onSearchChange,
                        onRepeatedOnlyChange = onRepeatedOnlyChange,
                        onTimeFilterChange = onTimeFilterChange,
                    )
                }
            }
        }

        AssistChip(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            onClick = { controlsVisible = !controlsVisible },
            label = { Text(if (controlsVisible) "Hide controls" else "Show controls") },
            leadingIcon = { Icon(Icons.Default.Route, contentDescription = null) },
        )
    }
}

@Composable
private fun MapMarkerPreviewCard(
    modifier: Modifier = Modifier,
    sighting: PlateSighting,
    onOpenHistory: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val imageAvailable = remember(context, sighting.imageUri) {
        isImageDisplayable(context, sighting.imageUri)
    }

    Card(
        modifier = modifier,
        onClick = onOpenHistory,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)),
    ) {
        Box(
            modifier = Modifier.width(220.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                if (imageAvailable) {
                    AsyncImage(
                        model = sighting.imageUri,
                        contentDescription = sighting.plateNumber,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(124.dp)
                            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)),
                        contentScale = ContentScale.Crop,
                    )
                }
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = sighting.plateNumber,
                        fontWeight = FontWeight.Black,
                    )
                    Text(
                        text = formatTimestamp(sighting.capturedAtEpochMillis),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Tap to open history details",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            TextButton(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
                onClick = onDismiss,
            ) {
                Text("Close")
            }
        }
    }
}

private fun formatCoordinate(value: Double): String = String.format("%.5f", value)

@Composable
private fun HistoryScreen(
    modifier: Modifier = Modifier,
    uiState: AppUiState,
    onSearchChange: (String) -> Unit,
    onRepeatedOnlyChange: (Boolean) -> Unit,
    onTimeFilterChange: (TimeFilter) -> Unit,
    onSharePlate: (String) -> Unit,
    onStoreLookup: (PatenteChileLookup) -> Unit,
    onEditSightingPlate: (String, String?, String) -> Unit,
    onRetrySighting: (String) -> Unit,
    onDeleteSighting: (String, String?) -> Unit,
    initialImageUri: String?,
    onInitialImageConsumed: () -> Unit,
) {
    var fullScreenImageUri by remember { mutableStateOf<String?>(null) }
    var plateLookupRequest by remember { mutableStateOf<PlateLookupRequest?>(null) }
    var editingSighting by remember { mutableStateOf<PlateSighting?>(null) }
    var pendingDeleteSighting by remember { mutableStateOf<PlateSighting?>(null) }
    val plateHistorySummaries = remember(uiState.allSightings) {
        uiState.allSightings
            .groupBy { it.plateNumber }
            .mapValues { (_, sightings) ->
                val sortedSightings = sightings.sortedBy { it.capturedAtEpochMillis }
                PlateHistorySummary(
                    observationCount = sortedSightings.size,
                    theoreticalRadiusMeters = calculateTheoreticalRadiusMeters(sortedSightings),
                )
            }
    }

    fun openPlateLookup(plateNumber: String?) {
        val resolvedPlate = plateNumber?.takeIf { it.isNotBlank() } ?: return
        plateLookupRequest = PlateLookupRequest(
            plateNumber = resolvedPlate,
            provider = PlateLookupProvider.VOLANTE_O_MALETA,
        )
    }

    LaunchedEffect(initialImageUri) {
        if (!initialImageUri.isNullOrBlank()) {
            fullScreenImageUri = initialImageUri
            onInitialImageConsumed()
        }
    }

    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FilterPanel(
            uiState = uiState,
            onSearchChange = onSearchChange,
            onRepeatedOnlyChange = onRepeatedOnlyChange,
            onTimeFilterChange = onTimeFilterChange,
        )
        LazyColumn(
            modifier = Modifier.weight(1f, fill = true),
            contentPadding = PaddingValues(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(uiState.filteredSightings, key = { it.id }) { sighting ->
                val activeUserId = uiState.syncDiagnostics.activeUserId
                val canDelete = sighting.createdBy == null || sighting.createdBy == activeUserId
                HistoryRow(
                    sighting = sighting,
                    lookup = sighting.toStoredLookup(),
                    historySummary = plateHistorySummaries[sighting.plateNumber] ?: PlateHistorySummary(),
                    canDelete = canDelete,
                    onOpenLookup = {
                        openPlateLookup(sighting.plateNumber)
                    },
                    onShareHistory = { onSharePlate(sighting.plateNumber) },
                    onEditPlate = {
                        editingSighting = sighting
                    },
                    onRetry = { onRetrySighting(sighting.clientGeneratedId) },
                    onOpenImage = { imageUri -> fullScreenImageUri = imageUri },
                    onDeleteSighting = {
                        pendingDeleteSighting = sighting
                    },
                )
            }
        }
    }

    fullScreenImageUri?.let { imageUri: String ->
        FullScreenImageDialog(
            imageUri = imageUri,
            onDismiss = { fullScreenImageUri = null },
        )
    }

    plateLookupRequest?.let { request ->
        PlateLookupDialog(
            plateNumber = request.plateNumber,
            provider = request.provider,
            onDismiss = { plateLookupRequest = null },
            onLookupResolved = { lookup ->
                onStoreLookup(lookup)
                plateLookupRequest = null
            },
        )
    }

    editingSighting?.let { sighting ->
        ManualPlateEntryDialog(
            pendingManualImageUri = sighting.imageUri?.takeIf { it.isNotBlank() },
            initialPlate = sighting.plateNumber,
            title = "Correct plate",
            saveLabel = "Update",
            onDismiss = { editingSighting = null },
            onSave = { plateNumber ->
                onEditSightingPlate(sighting.clientGeneratedId, sighting.plateNumber, plateNumber)
                editingSighting = null
            },
        )
    }

    pendingDeleteSighting?.let { sighting ->
        DestructiveConfirmationDialog(
            title = "Delete history entry?",
            message = "This will remove ${sighting.plateNumber} from history and also delete its local image if one is stored on this device.",
            confirmLabel = "Delete entry",
            onDismiss = { pendingDeleteSighting = null },
            onConfirm = {
                onDeleteSighting(sighting.clientGeneratedId, sighting.imageUri)
                if (fullScreenImageUri == sighting.imageUri) {
                    fullScreenImageUri = null
                }
                pendingDeleteSighting = null
            },
        )
    }
}

@Composable
private fun DestructiveConfirmationDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { Text(message) },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel)
            }
        },
    )
}

@Composable
private fun FilterPanel(
    uiState: AppUiState,
    onSearchChange: (String) -> Unit,
    onRepeatedOnlyChange: (Boolean) -> Unit,
    onTimeFilterChange: (TimeFilter) -> Unit,
) {
    var filtersVisible by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(uiState.searchQuery) {
        if (uiState.searchQuery.isNotBlank()) {
            filtersVisible = true
        }
    }

    Card(shape = RoundedCornerShape(24.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                AssistChip(
                    onClick = { filtersVisible = !filtersVisible },
                    label = { Text(if (filtersVisible) "Hide search" else "Search") },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null)
                    },
                )
            }
            if (filtersVisible) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = uiState.searchQuery,
                    onValueChange = onSearchChange,
                    label = { Text("Search licence") },
                    singleLine = true,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = uiState.repeatedOnly,
                        onClick = { onRepeatedOnlyChange(!uiState.repeatedOnly) },
                        label = { Text("Repeated only") },
                        colors = patentIAFilterChipColors(),
                    )
                    FilterChip(
                        selected = uiState.timeFilter == TimeFilter.ALL,
                        onClick = { onTimeFilterChange(TimeFilter.ALL) },
                        label = { Text("All") },
                        colors = patentIAFilterChipColors(),
                    )
                    FilterChip(
                        selected = uiState.timeFilter == TimeFilter.LAST_24_HOURS,
                        onClick = { onTimeFilterChange(TimeFilter.LAST_24_HOURS) },
                        label = { Text("24 h") },
                        colors = patentIAFilterChipColors(),
                    )
                    FilterChip(
                        selected = uiState.timeFilter == TimeFilter.LAST_7_DAYS,
                        onClick = { onTimeFilterChange(TimeFilter.LAST_7_DAYS) },
                        label = { Text("7 d") },
                        colors = patentIAFilterChipColors(),
                    )
                }
            }
        }
    }
}

private data class PlateHistorySummary(
    val observationCount: Int = 0,
    val theoreticalRadiusMeters: Double = 0.0,
)

@Composable
private fun HistoryRow(
    sighting: PlateSighting,
    lookup: PatenteChileLookup?,
    historySummary: PlateHistorySummary,
    onOpenLookup: () -> Unit,
    onShareHistory: () -> Unit,
    onEditPlate: () -> Unit,
    onRetry: () -> Unit,
    onOpenImage: (String) -> Unit,
    canDelete: Boolean = true,
    onDeleteSighting: () -> Unit,
) {
    val context = LocalContext.current
    val isImageDisplayable = remember(context, sighting.imageUri) {
        isImageDisplayable(context, sighting.imageUri)
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (isImageDisplayable) {
                AsyncImage(
                    model = sighting.imageUri,
                    contentDescription = sighting.plateNumber,
                    modifier = Modifier
                        .size(92.dp)
                        .heightIn(min = 92.dp)
                        .clickable { sighting.imageUri?.let(onOpenImage) },
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(92.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.CloudOff, contentDescription = null)
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = sighting.plateNumber,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                    )
                    IconButton(
                        modifier = Modifier.size(36.dp),
                        onClick = onEditPlate,
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit plate")
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    if (canDelete) {
                        IconButton(
                            modifier = Modifier.size(36.dp),
                            onClick = onDeleteSighting,
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete history entry")
                        }
                    }
                }
                Text(
                    text = "${historySummary.observationCount} obs | ${historySummary.theoreticalRadiusMeters.toInt()} m",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(formatTimestamp(sighting.capturedAtEpochMillis), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    when {
                        sighting.latitude != null && sighting.longitude != null -> "${sighting.latitude}, ${sighting.longitude}"
                        else -> "No GPS fix"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Source: ${sighting.source}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(syncStateLabel(sighting), color = syncStateColor(sighting), fontWeight = FontWeight.SemiBold)
                lookup?.ownerChipLabel?.let { ownerChip ->
                    AssistChip(
                        onClick = onOpenLookup,
                        label = { Text(ownerChip) },
                    )
                }
                lookup?.vehicleChipLabel?.let { vehicleChip ->
                    AssistChip(
                        onClick = onOpenLookup,
                        label = { Text(vehicleChip) },
                    )
                }
                lookup?.colorChipLabel?.let { colorChip ->
                    AssistChip(
                        onClick = onOpenLookup,
                        label = { Text("Color $colorChip") },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AssistChip(
                        onClick = onOpenLookup,
                        label = { Text("Volante o Maleta") },
                    )
                    IconButton(
                        modifier = Modifier.size(40.dp),
                        onClick = onShareHistory,
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Share plate history")
                    }
                }
                if (sighting.syncState == PlateSyncState.SYNC_ERROR.name) {
                    OutlinedButton(onClick = onRetry) {
                        Icon(Icons.Default.Sync, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Retry upload")
                    }
                }
            }
        }
    }
}

@Composable
private fun FullScreenImageDialog(
    imageUri: String,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .navigationBarsPadding(),
        ) {
            ZoomableAsyncImage(
                imageUri = imageUri,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 56.dp, start = 16.dp, end = 16.dp, bottom = 16.dp),
            )
            Text(
                text = "Pinch to zoom, drag to move",
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
                color = Color.White,
            )
            TextButton(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
                onClick = onDismiss,
            ) {
                Text("Close")
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PlateLookupDialog(
    plateNumber: String,
    provider: PlateLookupProvider,
    onDismiss: () -> Unit,
    onLookupResolved: (PatenteChileLookup) -> Unit,
) {
    val context = LocalContext.current
    var isLoading by rememberSaveable(plateNumber, provider) { mutableStateOf(true) }
    var searchTriggered by rememberSaveable(plateNumber, provider) { mutableStateOf(false) }
    var lookupResolved by rememberSaveable(plateNumber, provider) { mutableStateOf(false) }
    var webViewReference by remember { mutableStateOf<WebView?>(null) }
    val normalizedPlate = remember(plateNumber) {
        plateNumber.trim().uppercase()
    }
    val searchScript = remember(normalizedPlate, provider) {
        buildPlateLookupAutofillScript(provider, normalizedPlate)
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewReference?.apply {
                stopLoading()
                destroy()
            }
            webViewReference = null
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text("${provider.displayName} lookup", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                        Text(
                            text = "Public web search for $normalizedPlate",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { openBrowserUrl(context, provider.homeUrl) }) {
                            Text("Browser")
                        }
                        TextButton(onClick = onDismiss) {
                            Text("Close")
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { webContext ->
                            WebView(webContext).apply {
                                webViewReference = this
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.javaScriptCanOpenWindowsAutomatically = false
                                settings.setSupportMultipleWindows(false)
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                        return false
                                    }

                                    override fun onPageFinished(view: WebView, url: String) {
                                        val normalizedUrl = url.substringBefore('#').removeSuffix("/")
                                        if (!searchTriggered && normalizedUrl == provider.homeUrl.removeSuffix("/")) {
                                            searchTriggered = true
                                            view.evaluateJavascript(searchScript, null)
                                            return
                                        }
                                        if (!lookupResolved) {
                                            attemptPlateLookupExtraction(
                                                webView = view,
                                                plateNumber = normalizedPlate,
                                                provider = provider,
                                                onLookupResolved = { lookup ->
                                                    lookupResolved = true
                                                    onLookupResolved(lookup)
                                                    onDismiss()
                                                },
                                                onNoResult = {
                                                    lookupResolved = true
                                                    isLoading = false
                                                    onDismiss()
                                                },
                                                onFinished = {
                                                    isLoading = false
                                                },
                                            )
                                        } else {
                                            isLoading = false
                                        }
                                    }
                                }
                                loadUrl(provider.homeUrl)
                            }
                        },
                    )

                    if (isLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xA0000000)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CircularProgressIndicator()
                                Text("Running ${provider.displayName} search", color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun isLocalImageUri(imageUri: String?): Boolean {
    if (imageUri.isNullOrBlank()) {
        return false
    }
    return !isRemoteImageUri(imageUri)
}

private fun isRemoteImageUri(imageUri: String?): Boolean {
    if (imageUri.isNullOrBlank()) {
        return false
    }
    return imageUri.startsWith("https://") || imageUri.startsWith("gs://")
}

private fun isImageDisplayable(context: Context, imageUri: String?): Boolean {
    if (imageUri.isNullOrBlank()) {
        return false
    }
    if (isRemoteImageUri(imageUri)) {
        return true
    }

    val parsedUri = runCatching { Uri.parse(imageUri) }.getOrNull()
    return when (parsedUri?.scheme) {
        null, "" -> File(imageUri).exists()
        "file" -> parsedUri.path?.let(::File)?.exists() == true
        "content" -> runCatching {
            context.contentResolver.openAssetFileDescriptor(parsedUri, "r")?.use { true } ?: false
        }.getOrDefault(false)
        else -> false
    }
}

private fun hasAccessibleLocalImage(context: Context, imageUri: String?): Boolean {
    return isLocalImageUri(imageUri) && isImageDisplayable(context, imageUri)
}

private fun syncHeadline(syncDiagnostics: SyncDiagnostics, fallbackStatus: String): String {
    return when {
        !syncDiagnostics.isConfigured -> "Local-only mode"
        syncDiagnostics.lastError != null -> "Cloud sync delayed"
        syncDiagnostics.lastWarning != null -> syncDiagnostics.lastWarning
        syncDiagnostics.pendingUploadCount > 0 -> "Syncing ${syncDiagnostics.pendingUploadCount} pending"
        syncDiagnostics.isSignedIn -> "Shared group ${syncDiagnostics.activeGroupId ?: "-"}"
        else -> fallbackStatus
    }
}

private fun gpsStatusLabel(locationStatus: LocationStatus): String = when (locationStatus) {
    LocationStatus.CHECKING -> "Checking GPS"
    LocationStatus.READY -> "GPS ready"
    LocationStatus.WAITING_FOR_FIX -> "Waiting for GPS fix"
    LocationStatus.DISABLED -> "GPS disabled"
}

private fun gpsStatusDetail(
    locationStatus: LocationStatus,
    currentLocation: GeoPoint?,
): String = when (locationStatus) {
    LocationStatus.CHECKING -> "Refreshing location status now. Tap again if you want to retry."
    LocationStatus.READY -> currentLocation?.let {
        "Current fix ${"%.5f".format(it.latitude)}, ${"%.5f".format(it.longitude)}. Tap the chip to refresh."
    } ?: "Location services are available. Tap the chip to refresh."
    LocationStatus.WAITING_FOR_FIX -> "Location services are enabled, but no GPS fix is available yet. Tap the chip to retry."
    LocationStatus.DISABLED -> "Android location services are off or unavailable on this device. Tap the chip after enabling them."
}

@Composable
private fun gpsStatusContainerColor(locationStatus: LocationStatus): Color = when (locationStatus) {
    LocationStatus.READY -> Color(0xFFDDF5E8)
    LocationStatus.WAITING_FOR_FIX -> Color(0xFFFFF2D9)
    LocationStatus.DISABLED -> Color(0xFFFFE1E1)
    LocationStatus.CHECKING -> MaterialTheme.colorScheme.surfaceVariant
}

@Composable
private fun gpsStatusContentColor(locationStatus: LocationStatus): Color = when (locationStatus) {
    LocationStatus.READY -> Color(0xFF0C6B3D)
    LocationStatus.WAITING_FOR_FIX -> Color(0xFF8A5A00)
    LocationStatus.DISABLED -> Color(0xFF9B1C1C)
    LocationStatus.CHECKING -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun driverSummaryText(syncDiagnostics: SyncDiagnostics): String {
    return if (!syncDiagnostics.isConfigured) {
        "Large controls, single-tap capture, OCR extraction, GPS, and local timeline storage. Firebase can be enabled later for team sharing."
    } else {
        "Large controls, single-tap capture, OCR extraction, GPS, local persistence, and shared team sync through Firestore."
    }
}

private fun syncTitle(syncDiagnostics: SyncDiagnostics): String {
    return when {
        !syncDiagnostics.isConfigured -> "Stored on this device only"
        syncDiagnostics.lastError != null -> "Cloud sync needs retry"
        syncDiagnostics.lastWarning != null -> syncDiagnostics.lastWarning
        syncDiagnostics.pendingUploadCount > 0 -> "Sync in progress"
        syncDiagnostics.isSignedIn -> "Live shared updates active"
        else -> "Preparing shared session"
    }
}

private fun syncSubtitle(syncDiagnostics: SyncDiagnostics): String {
    return when {
        !syncDiagnostics.isConfigured -> "Add google-services.json to enable Firebase Auth and Firestore."
        syncDiagnostics.lastError != null -> "${syncDiagnostics.lastError} - queued items stay local until retry succeeds."
        syncDiagnostics.lastWarning != null -> "${syncDiagnostics.lastWarning} Firestore sharing still works, but remote devices will not see the photo until the storage issue is fixed."
        syncDiagnostics.pendingUploadCount > 0 -> "Queued sightings upload automatically when network and Firebase are available."
        syncDiagnostics.isSignedIn -> buildString {
            append("Signed in as ")
            append(syncDiagnostics.providerLabel)
            append(" - group ")
            append(syncDiagnostics.activeGroupId ?: "-")
            syncDiagnostics.lastSyncAtEpochMillis?.let {
                append(" - last sync ")
                append(formatTimestamp(it))
            }
        }
        else -> "Waiting for Firebase session initialization."
    }
}

private data class ChecklistItem(
    val label: String,
    val done: Boolean,
    val detail: String? = null,
)

private fun syncStateLabel(sighting: PlateSighting): String {
    return when (sighting.syncState) {
        PlateSyncState.LOCAL_ONLY.name -> "Local only"
        PlateSyncState.PENDING_UPLOAD.name -> "Pending cloud upload"
        PlateSyncState.SYNCED.name -> sighting.syncError ?: "Shared"
        PlateSyncState.SYNC_ERROR.name -> "Sync error"
        else -> sighting.syncState
    }
}

@Composable
private fun syncStateColor(sighting: PlateSighting): Color {
    return when (sighting.syncState) {
        PlateSyncState.LOCAL_ONLY.name -> MaterialTheme.colorScheme.secondary
        PlateSyncState.PENDING_UPLOAD.name -> MaterialTheme.colorScheme.primary
        PlateSyncState.SYNCED.name -> if (sighting.syncError != null) Color(0xFFFFB74D) else MaterialTheme.colorScheme.primary
        PlateSyncState.SYNC_ERROR.name -> Color(0xFFFF8A80)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun capturePhoto(
    context: Context,
    imageCapture: ImageCapture,
    executor: ExecutorService,
    source: String,
    onImageCaptured: (Uri, String) -> Unit,
    onCaptureError: (String) -> Unit,
) {
    val outputFile = AppImageStore(context).createImageFile()
    val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
    val mainExecutor = ContextCompat.getMainExecutor(context)

    imageCapture.takePicture(
        outputOptions,
        executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                val savedUri = outputFileResults.savedUri ?: Uri.fromFile(outputFile)
                mainExecutor.execute {
                    onImageCaptured(savedUri, source)
                }
            }

            override fun onError(exception: ImageCaptureException) {
                mainExecutor.execute {
                    onCaptureError(exception.message ?: "Image capture failed")
                }
            }
        }
    )
}

private fun isPermissionGranted(context: Context, permission: String): Boolean {
    return ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
}

private fun buildPlateLookupAutofillScript(
    provider: PlateLookupProvider,
    plateNumber: String,
): String = when (provider) {
    PlateLookupProvider.VOLANTE_O_MALETA -> buildVolanteOMaletaAutofillScript(plateNumber)
}

private fun buildPatenteChileAutofillScript(plateNumber: String): String {
    val escapedPlate = plateNumber
        .replace("\\", "\\\\")
        .replace("'", "\\'")

    return """
        (function() {
            const plate = '$escapedPlate';
            const normalizeInput = () => {
                const input = document.getElementById('inputTerm');
                const searchType = document.getElementById('searchType');
                if (!input) {
                    return null;
                }
                if (searchType) {
                    searchType.value = 'vehiculo';
                }
                input.value = plate;
                input.dispatchEvent(new Event('input', { bubbles: true }));
                input.dispatchEvent(new Event('change', { bubbles: true }));
                input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', bubbles: true }));
                input.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', code: 'Enter', bubbles: true }));
                return input;
            };

            const tryNativeSearch = () => {
                if (typeof window.a0_0x50ec1e === 'function') {
                    window.a0_0x50ec1e();
                    return true;
                }
                return false;
            };

            const tryButtonSearch = () => {
                const button = document.getElementById('searchBtn');
                if (!button) {
                    return false;
                }
                button.focus();
                button.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, view: window }));
                button.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, cancelable: true, view: window }));
                button.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
                button.click();
                return true;
            };

            const triggerSearch = () => {
                const input = normalizeInput();
                if (!input) {
                    return false;
                }
                if (tryNativeSearch()) {
                    return true;
                }
                if (tryButtonSearch()) {
                    return true;
                }
                input.focus();
                return true;
            };

            if (triggerSearch()) {
                return true;
            }

            let attempts = 0;
            const intervalId = setInterval(() => {
                attempts += 1;
                if (triggerSearch() || attempts >= 80) {
                    clearInterval(intervalId);
                }
            }, 250);
            return true;
        })();
    """.trimIndent()
}

private fun buildVolanteOMaletaAutofillScript(plateNumber: String): String {
    val escapedPlate = plateNumber
        .replace("\\", "\\\\")
        .replace("'", "\\'")

    return """
        (function() {
            const plate = '$escapedPlate';

            const triggerSearch = () => {
                const form = document.querySelector("form[action='patente']");
                const input = form ? form.querySelector("input[name='term']") : document.querySelector("input[name='term']");
                const button = form ? form.querySelector("button[type='submit'], input[type='submit']") : null;
                if (!form || !input) {
                    return false;
                }
                input.focus();
                input.value = plate;
                input.dispatchEvent(new Event('input', { bubbles: true }));
                input.dispatchEvent(new Event('change', { bubbles: true }));
                if (typeof form.requestSubmit === 'function') {
                    form.requestSubmit(button || undefined);
                    return true;
                }
                if (button) {
                    button.click();
                    return true;
                }
                form.submit();
                return true;
            };

            if (triggerSearch()) {
                return true;
            }

            let attempts = 0;
            const intervalId = setInterval(() => {
                attempts += 1;
                if (triggerSearch() || attempts >= 80) {
                    clearInterval(intervalId);
                }
            }, 250);
            return true;
        })();
    """.trimIndent()
}

private fun buildPatenteChileExtractionScript(plateNumber: String): String {
    val escapedPlate = plateNumber
        .replace("\\", "\\\\")
        .replace("'", "\\'")

    return """
        (function() {
            const expectedPlate = '$escapedPlate';
            const normalize = (value) => (value || '')
                .replace(/\u00a0/g, ' ')
                .replace(/\s+/g, ' ')
                .trim();
            const rawText = document.body ? (document.body.innerText || document.body.textContent || '') : '';
            const rawHtml = document.documentElement ? (document.documentElement.outerHTML || '') : '';
            const lines = rawText.split(/\n+/).map(normalize).filter(Boolean);
            const pairs = [];
            const scriptPayloads = Array.from(document.querySelectorAll('script'))
                .map((script) => normalize(script.textContent || ''))
                .filter((scriptText) => /propiet|duen|titular|rut|marca|modelo|ano|color|patente|ppu/i.test(scriptText))
                .slice(0, 12)
                .map((scriptText) => scriptText.slice(0, 4000));

            document.querySelectorAll('table tr').forEach((row) => {
                const cells = Array.from(row.querySelectorAll('th, td'))
                    .map((cell) => normalize(cell.innerText || cell.textContent || ''))
                    .filter(Boolean);
                if (cells.length >= 2) {
                    pairs.push([cells[0], normalize(cells.slice(1).join(' '))]);
                }
            });

            document.querySelectorAll('dl').forEach((list) => {
                const terms = Array.from(list.querySelectorAll('dt'));
                terms.forEach((term) => {
                    const valueNode = term.nextElementSibling;
                    if (!valueNode) {
                        return;
                    }
                    const label = normalize(term.innerText || term.textContent || '');
                    const value = normalize(valueNode.innerText || valueNode.textContent || '');
                    if (label && value) {
                        pairs.push([label, value]);
                    }
                });
            });

            lines.forEach((line) => {
                const match = line.match(/^([^:]{2,60}):\s*(.+)$/);
                if (match) {
                    pairs.push([normalize(match[1]), normalize(match[2])]);
                }
            });

            document.querySelectorAll('li, p, div, span, strong, b, h1, h2, h3, h4').forEach((node) => {
                const text = normalize(node.innerText || node.textContent || '');
                if (!text || text.length > 180) {
                    return;
                }
                const nextText = normalize(node.nextElementSibling?.innerText || node.nextElementSibling?.textContent || '');
                if (!nextText || nextText.length > 180) {
                    return;
                }
                if (/propiet|duen|titular|rut|marca|modelo|ano|color|patente|ppu/i.test(text)) {
                    pairs.push([text, nextText]);
                }
            });

            const findPair = (keywords) => {
                for (const [label, value] of pairs) {
                    const normalizedLabel = normalize(label).toLowerCase();
                    if (!value) {
                        continue;
                    }
                    if (keywords.some((keyword) => normalizedLabel.includes(keyword))) {
                        return value;
                    }
                }
                return '';
            };

            const findByPatterns = (patterns) => {
                for (const pattern of patterns) {
                    for (const line of lines) {
                        const match = line.match(pattern);
                        if (match && match[1]) {
                            return normalize(match[1]);
                        }
                    }
                    const block = rawText.match(pattern);
                    if (block && block[1]) {
                        return normalize(block[1]);
                    }
                }
                return '';
            };

            const ownerName = findPair(['propietario', 'dueno', 'titular', 'nombre del propietario', 'nombre del titular']) ||
                findByPatterns([
                    /(?:nombre(?: del)?(?: propietario| titular| dueno)?|propietario|dueno|titular)\s*:?\s*([^\n]+)/i,
                ]);
            const ownerRut = findPair(['rut propietario', 'rut dueno', 'rut titular', 'rut']) ||
                findByPatterns([
                    /(?:rut(?: del)?(?: propietario| dueno| titular)?)\s*:?\s*([^\n]+)/i,
                    /(\b\d{1,2}\.?\d{3}\.?\d{3}-[\dkK]\b)/,
                ]);
            const vehicleMake = findPair(['marca']) || findByPatterns([/(?:marca)\s*:?\s*([^\n]+)/i]);
            const vehicleModel = findPair(['modelo']) || findByPatterns([/(?:modelo)\s*:?\s*([^\n]+)/i]);
            const vehicleYear = findPair(['ano']) || findByPatterns([/(?:ano)\s*:?\s*([^\n]+)/i]);
            const vehicleColor = findPair(['color']) || findByPatterns([/(?:color)\s*:?\s*([^\n]+)/i]);
            const resolvedPlate = findPair(['patente', 'ppu']) ||
                findByPatterns([/(?:patente|ppu)\s*:?\s*([^\n]+)/i]) ||
                expectedPlate;

            return JSON.stringify({
                plateNumber: normalize(resolvedPlate || expectedPlate).toUpperCase(),
                ownerName: normalize(ownerName),
                ownerRut: normalize(ownerRut).toUpperCase(),
                vehicleMake: normalize(vehicleMake),
                vehicleModel: normalize(vehicleModel),
                vehicleYear: normalize(vehicleYear),
                vehicleColor: normalize(vehicleColor),
                rawText: rawText.slice(0, 50000),
                rawHtml: rawHtml.slice(0, 75000),
                scriptPayloads,
                labeledPairs: pairs,
            });
        })();
    """.trimIndent()
}

private fun buildVolanteOMaletaExtractionScript(plateNumber: String): String {
    val escapedPlate = plateNumber
        .replace("\\", "\\\\")
        .replace("'", "\\'")

    return """
        (function() {
            const expectedPlate = '$escapedPlate';
            const normalize = (value) => (value || '')
                .replace(/\u00a0/g, ' ')
                .replace(/\s+/g, ' ')
                .trim();
            const normalizeKey = (value) => normalize(value)
                .toLowerCase()
                .normalize('NFD')
                .replace(/[\u0300-\u036f]/g, '');
            const rawText = document.body ? (document.body.innerText || document.body.textContent || '') : '';
            const rawHtml = document.documentElement ? (document.documentElement.outerHTML || '') : '';
            const pairs = [];
            const resultTable = Array.from(document.querySelectorAll('table')).find((table) => {
                const headers = Array.from(table.querySelectorAll('tr:first-child th, thead th'))
                    .map((cell) => normalizeKey(cell.innerText || cell.textContent || ''));
                return headers.some((header) => header.includes('patente')) &&
                    headers.some((header) => header.includes('marca')) &&
                    headers.some((header) => header.includes('modelo')) &&
                    headers.some((header) => header.includes('rut'));
            });

            let resolvedPlate = expectedPlate;
            let ownerName = '';
            let ownerRut = '';
            let vehicleMake = '';
            let vehicleModel = '';
            let vehicleYear = '';
            let noResult = true;

            if (resultTable) {
                const rows = Array.from(resultTable.querySelectorAll('tr'));
                const headerRow = rows.find((row) => row.querySelectorAll('th').length > 0) || rows[0];
                const headers = Array.from(headerRow.querySelectorAll('th, td')).map((cell) => normalizeKey(cell.innerText || cell.textContent || ''));
                const structuredRows = rows
                    .filter((row) => row !== headerRow && row.querySelectorAll('td').length > 0)
                    .map((row) => {
                        const cells = Array.from(row.querySelectorAll('td')).map((cell) => normalize(cell.innerText || cell.textContent || ''));
                        const mapped = {};
                        headers.forEach((header, index) => {
                            mapped[header] = cells[index] || '';
                        });
                        return mapped;
                    });
                const matchingRow = structuredRows.find((row) => normalize(row['patente']).toUpperCase() === expectedPlate) ||
                    structuredRows.find((row) => Object.values(row).some((value) => {
                        const normalizedValue = normalize(value);
                        return normalizedValue && normalizedValue !== '-';
                    })) ||
                    null;

                if (matchingRow) {
                    noResult = false;
                    resolvedPlate = normalize(matchingRow['patente'] || expectedPlate).toUpperCase();
                    ownerName = normalize(matchingRow['nombre a rutificador'] || matchingRow['nombre']);
                    ownerRut = normalize(matchingRow['rut']).toUpperCase();
                    vehicleMake = normalize(matchingRow['marca']);
                    vehicleModel = normalize(matchingRow['modelo']);
                    vehicleYear = normalize(matchingRow['ano'] || matchingRow['año']);
                    Object.entries(matchingRow).forEach(([label, value]) => {
                        const normalizedValue = normalize(value);
                        if (label && normalizedValue && normalizedValue !== '-') {
                            pairs.push([label, normalizedValue]);
                        }
                    });
                }
            }

            return JSON.stringify({
                plateNumber: normalize(resolvedPlate || expectedPlate).toUpperCase(),
                ownerName,
                ownerRut,
                vehicleMake,
                vehicleModel,
                vehicleYear,
                vehicleColor: '',
                noResult,
                rawText: rawText.slice(0, 50000),
                rawHtml: '',
                scriptPayloads: [],
                labeledPairs: pairs,
            });
        })();
    """.trimIndent()
}

private fun attemptPlateLookupExtraction(
    webView: WebView,
    plateNumber: String,
    provider: PlateLookupProvider,
    onLookupResolved: (PatenteChileLookup) -> Unit,
    onNoResult: () -> Unit,
    onFinished: () -> Unit,
    remainingAttempts: Int = 14,
) {
    val extractionScript = when (provider) {
        PlateLookupProvider.VOLANTE_O_MALETA -> buildVolanteOMaletaExtractionScript(plateNumber)
    }
    webView.evaluateJavascript(extractionScript) { rawResult ->
        val lookup = parsePatenteChileLookupResult(rawResult)
        if (lookup != null && lookup.hasMeaningfulData()) {
            onLookupResolved(lookup)
            onFinished()
            return@evaluateJavascript
        }

        if (isPatenteChileLookupNoResult(rawResult)) {
            onNoResult()
            onFinished()
            return@evaluateJavascript
        }

        if (remainingAttempts > 1) {
            webView.postDelayed(
                {
                    attemptPlateLookupExtraction(
                        webView = webView,
                        plateNumber = plateNumber,
                        provider = provider,
                        onLookupResolved = onLookupResolved,
                        onNoResult = onNoResult,
                        onFinished = onFinished,
                        remainingAttempts = remainingAttempts - 1,
                    )
                },
                1000,
            )
        } else {
            onFinished()
        }
    }
}

private fun openBrowserUrl(context: Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "PatentIA plate history")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val chooser = Intent.createChooser(intent, "Share plate history")
    if (context !is Activity) {
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching {
        context.startActivity(chooser)
    }
}

private fun formatTimestamp(epochMillis: Long): String {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    return formatter.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
}

private fun formatVersionLabel(versionName: String?, versionCode: Long?): String {
    val safeVersionName = versionName?.takeIf { it.isNotBlank() } ?: "unknown"
    val safeVersionCode = versionCode?.takeIf { it > 0 }?.toString() ?: "-"
    return "$safeVersionName ($safeVersionCode)"
}

private fun formatByteCountSuffix(bytes: Long?): String {
    val safeBytes = bytes?.takeIf { it > 0 } ?: return ""
    val sizeInMegabytes = safeBytes.toDouble() / (1024.0 * 1024.0)
    return " - ${"%.1f".format(sizeInMegabytes)} MB"
}

@Composable
private fun patentIAFilterChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
    selectedTrailingIconColor = MaterialTheme.colorScheme.onPrimary,
)

private const val VOLANTE_O_MALETA_HOME_URL = "https://www.volanteomaleta.com/"
internal const val VOLANTE_O_MALETA_LOOKUP_SOURCE = "volante_o_maleta"

private fun PlateSighting.toStoredLookup(): PatenteChileLookup? {
    val lookup = PatenteChileLookup(
        plateNumber = plateNumber,
        ownerName = lookupOwnerName,
        ownerRut = lookupOwnerRut,
        vehicleMake = lookupVehicleMake,
        vehicleModel = lookupVehicleModel,
        vehicleYear = lookupVehicleYear,
        vehicleColor = lookupVehicleColor,
    )
    return lookup.takeIf { it.hasMeaningfulData() }
}

private val patentIAColorScheme = androidx.compose.material3.darkColorScheme(
    primary = Color(0xFF5AD1B2),
    onPrimary = Color(0xFF04241D),
    primaryContainer = Color(0xFF0B4E43),
    onPrimaryContainer = Color(0xFFD9FFF6),
    secondary = Color(0xFFFFC857),
    onSecondary = Color(0xFF2B1A00),
    secondaryContainer = Color(0xFF664700),
    onSecondaryContainer = Color(0xFFFFE7B3),
    surface = Color(0xFF09141B),
    surfaceContainerHigh = Color(0xFF13232D),
    onSurface = Color(0xFFF2FAFF),
    onSurfaceVariant = Color(0xFFAFD0DD),
)
