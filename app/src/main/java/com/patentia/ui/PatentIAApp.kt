package com.patentia.ui

import android.annotation.SuppressLint
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
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
import com.google.maps.android.compose.MarkerInfoWindowContent
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatentIAApp(viewModel: AppViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedPanel by rememberSaveable { mutableStateOf(AppPanel.Camera) }
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
                                onOpenHistoryForPlate = { plateNumber ->
                                    viewModel.selectPlate(plateNumber)
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
                                onSelectPlate = viewModel::selectPlate,
                                onShareSelected = {
                                    viewModel.buildSelectedPlateSharePayload()?.let { payload ->
                                        shareText(context, payload)
                                    }
                                },
                                onEditSightingPlate = viewModel::updateSightingPlate,
                                onRetrySighting = viewModel::retrySighting,
                                onDeleteSighting = viewModel::deleteSighting,
                                onDeleteLocalImage = viewModel::removeLocalImage,
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
            hasPendingManualImage = uiState.hasPendingManualImage,
            initialPlate = "",
            title = "Write plate",
            saveLabel = "Save",
            onDismiss = { manualPlateDialogVisible = false },
            onSave = { plateNumber ->
                onSaveManualPlate(plateNumber)
                manualPlateDialogVisible = false
            },
            onDiscardPendingImage = {
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

@OptIn(ExperimentalLayoutApi::class)
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
                FilterChip(
                    selected = uiState.captureMode == CaptureMode.SINGLE,
                    onClick = { onCaptureModeChange(CaptureMode.SINGLE) },
                    label = { Text("Single shot") },
                    colors = patentIAFilterChipColors(),
                )
                FilterChip(
                    selected = uiState.captureMode == CaptureMode.INTERVAL,
                    onClick = { onCaptureModeChange(CaptureMode.INTERVAL) },
                    label = { Text("Interval") },
                    colors = patentIAFilterChipColors(),
                )
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onManualPlateEntry) {
                    Text(if (uiState.hasPendingManualImage) "Write plate from photo" else "Write plate")
                }
                OutlinedButton(onClick = onPickImage) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = "Open image")
                }
            }
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
private fun ManualPlateEntryDialog(
    hasPendingManualImage: Boolean,
    initialPlate: String,
    title: String,
    saveLabel: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onDiscardPendingImage: () -> Unit,
) {
    var plateNumber by rememberSaveable(initialPlate) { mutableStateOf(initialPlate) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                Text(
                    text = if (hasPendingManualImage) {
                        "The saved photo will be attached to this manual plate entry."
                    } else {
                        "Use manual input when OCR misses a plate or when you need to correct a saved one."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                    if (hasPendingManualImage) {
                        TextButton(onClick = onDiscardPendingImage) {
                            Text("Discard photo")
                        }
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Close")
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
    onOpenHistoryForPlate: (String) -> Unit,
) {
    val mappedSightings = uiState.filteredSightings.filter { it.latitude != null && it.longitude != null }
    val selectedPath = uiState.selectedPlateHistory.filter { it.latitude != null && it.longitude != null }
    val currentLocation = uiState.currentLocation
    var controlsVisible by rememberSaveable { mutableStateOf(false) }
    var mapLoaded by rememberSaveable { mutableStateOf(false) }
    var showMapTroubleshooting by rememberSaveable { mutableStateOf(false) }
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
        val focus = selectedPath.lastOrNull() ?: mappedSightings.firstOrNull()
        if (focus != null) {
            cameraPositionState.move(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(focus.latitude ?: return@LaunchedEffect, focus.longitude ?: return@LaunchedEffect),
                    12f,
                )
            )
        } else if (currentLocation != null) {
            cameraPositionState.move(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(currentLocation.latitude, currentLocation.longitude),
                    15f,
                )
            )
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
                    onMapLoaded = {
                        mapLoaded = true
                        showMapTroubleshooting = false
                    },
                ) {
                    mappedSightings.forEach { sighting ->
                        val latitude = sighting.latitude ?: return@forEach
                        val longitude = sighting.longitude ?: return@forEach
                        MarkerInfoWindowContent(
                            state = MarkerState(position = LatLng(latitude, longitude)),
                            title = sighting.plateNumber,
                            snippet = formatTimestamp(sighting.capturedAtEpochMillis),
                            onClick = {
                                onSelectPlate(sighting.plateNumber)
                                false
                            },
                            onInfoWindowClick = {
                                onOpenHistoryForPlate(sighting.plateNumber)
                            },
                        ) {
                            MapMarkerInfoWindowContent(sighting = sighting)
                        }
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
private fun MapMarkerInfoWindowContent(
    sighting: PlateSighting,
) {
    val context = LocalContext.current
    val imageAvailable = remember(context, sighting.imageUri) {
        isImageDisplayable(context, sighting.imageUri)
    }

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(
            modifier = Modifier.width(180.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            if (imageAvailable) {
                AsyncImage(
                    model = sighting.imageUri,
                    contentDescription = sighting.plateNumber,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 92.dp),
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
                    color = Color(0xFF10202B),
                )
                Text(
                    text = formatTimestamp(sighting.capturedAtEpochMillis),
                    color = Color(0xFF4A6071),
                )
                Text(
                    text = "Tap to open history details",
                    color = Color(0xFF4A6071),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun HistoryScreen(
    modifier: Modifier = Modifier,
    uiState: AppUiState,
    onSearchChange: (String) -> Unit,
    onRepeatedOnlyChange: (Boolean) -> Unit,
    onTimeFilterChange: (TimeFilter) -> Unit,
    onSelectPlate: (String?) -> Unit,
    onShareSelected: () -> Unit,
    onEditSightingPlate: (String, String?, String) -> Unit,
    onRetrySighting: (String) -> Unit,
    onDeleteSighting: (String, String?) -> Unit,
    onDeleteLocalImage: (String, String?) -> Unit,
) {
    var fullScreenImageUri by rememberSaveable { mutableStateOf<String?>(null) }
    var patenteChileLookupPlate by rememberSaveable { mutableStateOf<String?>(null) }
    var editingSighting by remember { mutableStateOf<PlateSighting?>(null) }
    var pendingDeleteSighting by remember { mutableStateOf<PlateSighting?>(null) }
    var pendingDeleteImage by remember { mutableStateOf<PlateSighting?>(null) }
    var patenteChileLookupCache by remember { mutableStateOf<Map<String, PatenteChileLookup>>(emptyMap()) }
    val selectedPlateLookup = uiState.selectedPlate?.let { patenteChileLookupCache[it] }

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
        if (uiState.selectedPlate != null) {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AssistChip(
                        onClick = { },
                        label = {
                            Text("${uiState.selectedPlate} • ${uiState.selectedPlateHistory.size} obs • ${uiState.theoreticalRadiusMeters.toInt()} m")
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.25f),
                            labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    )
                    selectedPlateLookup?.ownerChipLabel?.let { ownerChip ->
                        AssistChip(
                            onClick = { patenteChileLookupPlate = uiState.selectedPlate },
                            label = { Text(ownerChip) },
                        )
                    }
                    selectedPlateLookup?.vehicleChipLabel?.let { vehicleChip ->
                        AssistChip(
                            onClick = { patenteChileLookupPlate = uiState.selectedPlate },
                            label = { Text(vehicleChip) },
                        )
                    }
                    selectedPlateLookup?.colorChipLabel?.let { colorChip ->
                        AssistChip(
                            onClick = { patenteChileLookupPlate = uiState.selectedPlate },
                            label = { Text("Color $colorChip") },
                        )
                    }
                    AssistChip(
                        onClick = { patenteChileLookupPlate = uiState.selectedPlate },
                        label = { Text("PatenteChile") },
                    )
                    AssistChip(
                        onClick = onShareSelected,
                        label = { Text("Share") },
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                    )
                    AssistChip(
                        onClick = {
                            editingSighting = uiState.selectedPlateHistory.lastOrNull()
                        },
                        label = { Text("Edit plate") },
                    )
                    IconButton(onClick = { onSelectPlate(null) }) {
                        Icon(Icons.Default.Close, contentDescription = "Close plate details")
                    }
                }
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f, fill = true),
            contentPadding = PaddingValues(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(uiState.filteredSightings, key = { it.id }) { sighting ->
                HistoryRow(
                    sighting = sighting,
                    lookup = patenteChileLookupCache[sighting.plateNumber],
                    isSelected = uiState.selectedPlate == sighting.plateNumber,
                    onClick = {
                        onSelectPlate(
                            if (uiState.selectedPlate == sighting.plateNumber) null else sighting.plateNumber
                        )
                    },
                    onOpenPatenteChile = {
                        onSelectPlate(sighting.plateNumber)
                        patenteChileLookupPlate = sighting.plateNumber
                    },
                    onEditPlate = {
                        editingSighting = sighting
                    },
                    onRetry = { onRetrySighting(sighting.clientGeneratedId) },
                    onOpenImage = { imageUri -> fullScreenImageUri = imageUri },
                    onDeleteSighting = {
                        pendingDeleteSighting = sighting
                    },
                    onDeleteImage = {
                        pendingDeleteImage = sighting
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

    patenteChileLookupPlate?.let { plateNumber ->
        PatenteChileLookupDialog(
            plateNumber = plateNumber,
            onDismiss = { patenteChileLookupPlate = null },
            onLookupResolved = { lookup ->
                patenteChileLookupCache = patenteChileLookupCache + (lookup.plateNumber to lookup)
            },
        )
    }

    editingSighting?.let { sighting ->
        ManualPlateEntryDialog(
            hasPendingManualImage = false,
            initialPlate = sighting.plateNumber,
            title = "Correct plate",
            saveLabel = "Update",
            onDismiss = { editingSighting = null },
            onSave = { plateNumber ->
                onEditSightingPlate(sighting.clientGeneratedId, sighting.plateNumber, plateNumber)
                editingSighting = null
            },
            onDiscardPendingImage = { editingSighting = null },
        )
    }

    pendingDeleteImage?.let { sighting ->
        DestructiveConfirmationDialog(
            title = "Delete local image?",
            message = "This will remove the stored photo for ${sighting.plateNumber} from this device. The sighting record will stay in history.",
            confirmLabel = "Delete image",
            onDismiss = { pendingDeleteImage = null },
            onConfirm = {
                onDeleteLocalImage(sighting.clientGeneratedId, sighting.imageUri)
                if (fullScreenImageUri == sighting.imageUri) {
                    fullScreenImageUri = null
                }
                pendingDeleteImage = null
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
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = uiState.searchQuery,
                onValueChange = onSearchChange,
                label = { Text("Search licence") },
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

@Composable
private fun HistoryRow(
    sighting: PlateSighting,
    lookup: PatenteChileLookup?,
    isSelected: Boolean,
    onClick: () -> Unit,
    onOpenPatenteChile: () -> Unit,
    onEditPlate: () -> Unit,
    onRetry: () -> Unit,
    onOpenImage: (String) -> Unit,
    onDeleteSighting: () -> Unit,
    onDeleteImage: () -> Unit,
) {
    val context = LocalContext.current
    val isImageDisplayable = remember(context, sighting.imageUri) {
        isImageDisplayable(context, sighting.imageUri)
    }
    val hasAccessibleLocalImage = remember(context, sighting.imageUri) {
        hasAccessibleLocalImage(context, sighting.imageUri)
    }

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
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
                    Text(sighting.plateNumber, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
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
                    sighting.syncError?.let {
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    lookup?.ownerChipLabel?.let { ownerChip ->
                        AssistChip(
                            onClick = onOpenPatenteChile,
                            label = { Text(ownerChip) },
                        )
                    }
                    lookup?.vehicleChipLabel?.let { vehicleChip ->
                        AssistChip(
                            onClick = onOpenPatenteChile,
                            label = { Text(vehicleChip) },
                        )
                    }
                    lookup?.colorChipLabel?.let { colorChip ->
                        AssistChip(
                            onClick = onOpenPatenteChile,
                            label = { Text("Color $colorChip") },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = onOpenPatenteChile,
                            label = { Text("PatenteChile") },
                        )
                        AssistChip(
                            onClick = onEditPlate,
                            label = { Text("Edit plate") },
                        )
                        if (isSelected) {
                            AssistChip(
                                onClick = onClick,
                                label = { Text("Hide details") },
                            )
                        }
                    }
                    if (hasAccessibleLocalImage) {
                        TextButton(onClick = onDeleteImage) {
                            Text("Delete local image")
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

            IconButton(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
                onClick = onDeleteSighting,
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Delete history entry",
                )
            }
        }
    }
}

@Composable
private fun FullScreenImageDialog(
    imageUri: String,
    onDismiss: () -> Unit,
) {
    var scale by rememberSaveable { mutableStateOf(1f) }
    var offsetX by rememberSaveable { mutableStateOf(0f) }
    var offsetY by rememberSaveable { mutableStateOf(0f) }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .navigationBarsPadding(),
        ) {
            AsyncImage(
                model = imageUri,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
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
            if (scale > 1f) {
                Text(
                    text = "Pinch to zoom, drag to move",
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp),
                    color = Color.White,
                )
            }
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
private fun PatenteChileLookupDialog(
    plateNumber: String,
    onDismiss: () -> Unit,
    onLookupResolved: (PatenteChileLookup) -> Unit,
) {
    val context = LocalContext.current
    var isLoading by rememberSaveable(plateNumber) { mutableStateOf(true) }
    var searchTriggered by rememberSaveable(plateNumber) { mutableStateOf(false) }
    var lookupResolved by rememberSaveable(plateNumber) { mutableStateOf(false) }
    val normalizedPlate = remember(plateNumber) {
        plateNumber.trim().uppercase()
    }
    val searchScript = remember(normalizedPlate) {
        buildPatenteChileAutofillScript(normalizedPlate)
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
                        Text("PatenteChile lookup", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                        Text(
                            text = "Public web search for $normalizedPlate",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { openBrowserUrl(context, PATENTE_CHILE_HOME_URL) }) {
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
                                        if (!searchTriggered && normalizedUrl == PATENTE_CHILE_HOME_URL.removeSuffix("/")) {
                                            searchTriggered = true
                                            view.evaluateJavascript(searchScript, null)
                                            return
                                        }
                                        if (!lookupResolved) {
                                            attemptPatenteChileLookupExtraction(
                                                webView = view,
                                                plateNumber = normalizedPlate,
                                                onLookupResolved = { lookup ->
                                                    lookupResolved = true
                                                    onLookupResolved(lookup)
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
                                loadUrl(PATENTE_CHILE_HOME_URL)
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
                                Text("Running PatenteChile search", color = Color.White)
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
        syncDiagnostics.lastWarning != null -> "Shared without cloud image"
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
        syncDiagnostics.lastWarning != null -> "Shared without cloud image"
        syncDiagnostics.pendingUploadCount > 0 -> "Sync in progress"
        syncDiagnostics.isSignedIn -> "Live shared updates active"
        else -> "Preparing shared session"
    }
}

private fun syncSubtitle(syncDiagnostics: SyncDiagnostics): String {
    return when {
        !syncDiagnostics.isConfigured -> "Add google-services.json to enable Firebase Auth and Firestore."
        syncDiagnostics.lastError != null -> "${syncDiagnostics.lastError} • queued items stay local until retry succeeds."
        syncDiagnostics.lastWarning != null -> "${syncDiagnostics.lastWarning} Firestore sharing still works, but remote devices will not see the photo until Storage is enabled."
        syncDiagnostics.pendingUploadCount > 0 -> "Queued sightings upload automatically when network and Firebase are available."
        syncDiagnostics.isSignedIn -> buildString {
            append("Signed in as ")
            append(syncDiagnostics.providerLabel)
            append(" • group ")
            append(syncDiagnostics.activeGroupId ?: "-")
            syncDiagnostics.lastSyncAtEpochMillis?.let {
                append(" • last sync ")
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
        PlateSyncState.SYNCED.name -> if (sighting.syncError != null) "Shared without image" else "Shared"
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
                .filter((scriptText) => /propiet|dueñ|duen|titular|rut|marca|modelo|año|ano|color|patente|ppu/i.test(scriptText))
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
                if (/propiet|dueñ|duen|titular|rut|marca|modelo|año|ano|color|patente|ppu/i.test(text)) {
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

            const ownerName = findPair(['propietario', 'dueño', 'dueno', 'titular', 'nombre del propietario', 'nombre del titular']) ||
                findByPatterns([
                    /(?:nombre(?: del)?(?: propietario| titular| dueño| dueno)?|propietario|dueño|dueno|titular)\s*:?\s*([^\n]+)/i,
                ]);
            const ownerRut = findPair(['rut propietario', 'rut dueño', 'rut dueno', 'rut titular', 'rut']) ||
                findByPatterns([
                    /(?:rut(?: del)?(?: propietario| dueño| dueno| titular)?)\s*:?\s*([^\n]+)/i,
                    /(\b\d{1,2}\.?\d{3}\.?\d{3}-[\dkK]\b)/,
                ]);
            const vehicleMake = findPair(['marca']) || findByPatterns([/(?:marca)\s*:?\s*([^\n]+)/i]);
            const vehicleModel = findPair(['modelo']) || findByPatterns([/(?:modelo)\s*:?\s*([^\n]+)/i]);
            const vehicleYear = findPair(['año', 'ano']) || findByPatterns([/(?:año|ano)\s*:?\s*([^\n]+)/i]);
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

private fun attemptPatenteChileLookupExtraction(
    webView: WebView,
    plateNumber: String,
    onLookupResolved: (PatenteChileLookup) -> Unit,
    onFinished: () -> Unit,
    remainingAttempts: Int = 14,
) {
    webView.evaluateJavascript(buildPatenteChileExtractionScript(plateNumber)) { rawResult ->
        val lookup = parsePatenteChileLookupResult(rawResult)
        if (lookup != null && lookup.hasMeaningfulData()) {
            onLookupResolved(lookup)
            onFinished()
            return@evaluateJavascript
        }

        if (remainingAttempts > 1) {
            webView.postDelayed(
                {
                    attemptPatenteChileLookupExtraction(
                        webView = webView,
                        plateNumber = plateNumber,
                        onLookupResolved = onLookupResolved,
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
        type = "application/json"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Share plate history"))
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
    return " • ${"%.1f".format(sizeInMegabytes)} MB"
}

@Composable
private fun patentIAFilterChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
    selectedTrailingIconColor = MaterialTheme.colorScheme.onPrimary,
)

private const val PATENTE_CHILE_HOME_URL = "https://www.patentechile.com/"

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