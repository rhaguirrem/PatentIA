package com.patentia.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.patentia.BuildConfig
import com.patentia.data.PlateSighting
import com.patentia.data.PlateSyncState
import com.patentia.data.SyncDiagnostics
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.delay

private enum class AppTab {
    Capture,
    Map,
    History,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatentIAApp(viewModel: AppViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.Capture) }
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
        isPermissionGranted(context, android.Manifest.permission.ACCESS_FINE_LOCATION)
            || isPermissionGranted(context, android.Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    MaterialTheme(
        colorScheme = patentIAColorScheme,
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                contentWindowInsets = WindowInsets.statusBars,
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
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(
                            selected = selectedTab == AppTab.Capture,
                            onClick = { selectedTab = AppTab.Capture },
                            icon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                            label = { Text("Capture") },
                        )
                        NavigationBarItem(
                            selected = selectedTab == AppTab.Map,
                            onClick = { selectedTab = AppTab.Map },
                            icon = { Icon(Icons.Default.Route, contentDescription = null) },
                            label = { Text("Map") },
                        )
                        NavigationBarItem(
                            selected = selectedTab == AppTab.History,
                            onClick = { selectedTab = AppTab.History },
                            icon = { Icon(Icons.Default.MyLocation, contentDescription = null) },
                            label = { Text("History") },
                        )
                    }
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
                    when (selectedTab) {
                        AppTab.Capture -> CaptureScreen(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                            uiState = uiState,
                            hasCameraPermission = hasCameraPermission,
                            hasLocationPermission = hasLocationPermission,
                            onCaptureModeChange = viewModel::setCaptureMode,
                            onIntervalSecondsChange = viewModel::setIntervalSeconds,
                            onToggleInterval = viewModel::toggleIntervalCapture,
                            onImageCaptured = viewModel::processImage,
                            onSelectPlate = viewModel::selectPlate,
                            onSwitchGroup = viewModel::switchActiveGroup,
                            onJoinOrCreateGroup = viewModel::joinOrCreateGroup,
                            onRetryPendingSync = viewModel::retryPendingSync,
                        )
                        AppTab.Map -> MapScreen(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                            uiState = uiState,
                            onSearchChange = viewModel::updateSearchQuery,
                            onRepeatedOnlyChange = viewModel::setRepeatedOnly,
                            onTimeFilterChange = viewModel::setTimeFilter,
                            onSelectPlate = viewModel::selectPlate,
                            onSwitchGroup = viewModel::switchActiveGroup,
                            onJoinOrCreateGroup = viewModel::joinOrCreateGroup,
                            onRetryPendingSync = viewModel::retryPendingSync,
                        )
                        AppTab.History -> HistoryScreen(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                            uiState = uiState,
                            onSearchChange = viewModel::updateSearchQuery,
                            onRepeatedOnlyChange = viewModel::setRepeatedOnly,
                            onTimeFilterChange = viewModel::setTimeFilter,
                            onSelectPlate = viewModel::selectPlate,
                            onSwitchGroup = viewModel::switchActiveGroup,
                            onJoinOrCreateGroup = viewModel::joinOrCreateGroup,
                            onRetryPendingSync = viewModel::retryPendingSync,
                            onShareSelected = {
                                viewModel.buildSelectedPlateSharePayload()?.let { payload ->
                                    shareText(context, payload)
                                }
                            },
                            onRetrySighting = viewModel::retrySighting,
                        )
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
private fun CaptureScreen(
    modifier: Modifier = Modifier,
    uiState: AppUiState,
    hasCameraPermission: Boolean,
    hasLocationPermission: Boolean,
    onCaptureModeChange: (CaptureMode) -> Unit,
    onIntervalSecondsChange: (Int) -> Unit,
    onToggleInterval: () -> Unit,
    onImageCaptured: (Uri, String) -> Unit,
    onSelectPlate: (String?) -> Unit,
    onSwitchGroup: (String) -> Unit,
    onJoinOrCreateGroup: (String) -> Unit,
    onRetryPendingSync: () -> Unit,
) {
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
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DriverSummaryCard(uiState = uiState, onSelectPlate = onSelectPlate)
        SyncStatusCard(
            syncDiagnostics = uiState.syncDiagnostics,
            onRetryPendingSync = onRetryPendingSync,
            onSwitchGroup = onSwitchGroup,
            onJoinOrCreateGroup = onJoinOrCreateGroup,
        )
        SetupChecklistCard(
            syncDiagnostics = uiState.syncDiagnostics,
            hasCameraPermission = hasCameraPermission,
            hasLocationPermission = hasLocationPermission,
        )
        CaptureControls(
            uiState = uiState,
            onCaptureModeChange = onCaptureModeChange,
            onIntervalSecondsChange = onIntervalSecondsChange,
            onToggleInterval = onToggleInterval,
            onPickImage = { imagePickerLauncher.launch("image/*") },
        )
        CameraCaptureCard(
            uiState = uiState,
            onImageCaptured = onImageCaptured,
        )
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
                done = !syncDiagnostics.activeGroupId.isNullOrBlank(),
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
            if (syncDiagnostics.availableGroups.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    syncDiagnostics.availableGroups.take(4).forEach { group ->
                        FilterChip(
                            selected = group.id == syncDiagnostics.activeGroupId,
                            onClick = { onSwitchGroup(group.id) },
                            label = { Text(group.name) },
                            leadingIcon = {
                                Icon(Icons.Default.Groups, contentDescription = null)
                            },
                        )
                    }
                }
            }
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
            if (syncDiagnostics.pendingUploadCount > 0 || syncDiagnostics.lastError != null) {
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
) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Capture mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = uiState.captureMode == CaptureMode.SINGLE,
                    onClick = { onCaptureModeChange(CaptureMode.SINGLE) },
                    label = { Text("Single shot") },
                )
                FilterChip(
                    selected = uiState.captureMode == CaptureMode.INTERVAL,
                    onClick = { onCaptureModeChange(CaptureMode.INTERVAL) },
                    label = { Text("Interval") },
                )
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
            OutlinedButton(onClick = onPickImage) {
                Text("Import existing image")
            }
        }
    }
}

@Composable
private fun CameraCaptureCard(
    uiState: AppUiState,
    onImageCaptured: (Uri, String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    var cameraErrorMessage by remember { mutableStateOf<String?>(null) }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val listener = Runnable {
            val cameraProvider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture,
                )
                cameraErrorMessage = null
            } catch (exception: Exception) {
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

    Card(shape = RoundedCornerShape(28.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(modifier = Modifier.height(420.dp)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { previewView },
                )
                FloatingActionButton(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 20.dp)
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
            Text(
                text = "Tip: keep the plate centered and reduce glare for better OCR.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            cameraErrorMessage?.let { message ->
                Text(
                    text = message,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = Color(0xFFFF8A80),
                )
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
    onSwitchGroup: (String) -> Unit,
    onJoinOrCreateGroup: (String) -> Unit,
    onRetryPendingSync: () -> Unit,
) {
    val mappedSightings = uiState.filteredSightings.filter { it.latitude != null && it.longitude != null }
    val selectedPath = uiState.selectedPlateHistory.filter { it.latitude != null && it.longitude != null }
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(48.8566, 2.3522), 10f)
    }

    LaunchedEffect(mappedSightings.firstOrNull()?.id, uiState.selectedPlate) {
        val focus = selectedPath.lastOrNull() ?: mappedSightings.firstOrNull()
        if (focus != null) {
            cameraPositionState.move(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(focus.latitude ?: return@LaunchedEffect, focus.longitude ?: return@LaunchedEffect),
                    12f,
                )
            )
        }
    }

    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SyncStatusCard(
            syncDiagnostics = uiState.syncDiagnostics,
            onRetryPendingSync = onRetryPendingSync,
            onSwitchGroup = onSwitchGroup,
            onJoinOrCreateGroup = onJoinOrCreateGroup,
        )
        FilterPanel(
            uiState = uiState,
            onSearchChange = onSearchChange,
            onRepeatedOnlyChange = onRepeatedOnlyChange,
            onTimeFilterChange = onTimeFilterChange,
        )
        Card(shape = RoundedCornerShape(28.dp), modifier = Modifier.weight(1f, fill = true)) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
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
                            false
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
    onSwitchGroup: (String) -> Unit,
    onJoinOrCreateGroup: (String) -> Unit,
    onRetryPendingSync: () -> Unit,
    onShareSelected: () -> Unit,
    onRetrySighting: (String) -> Unit,
) {
    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SyncStatusCard(
            syncDiagnostics = uiState.syncDiagnostics,
            onRetryPendingSync = onRetryPendingSync,
            onSwitchGroup = onSwitchGroup,
            onJoinOrCreateGroup = onJoinOrCreateGroup,
        )
        FilterPanel(
            uiState = uiState,
            onSearchChange = onSearchChange,
            onRepeatedOnlyChange = onRepeatedOnlyChange,
            onTimeFilterChange = onTimeFilterChange,
        )
        if (uiState.selectedPlate != null) {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(uiState.selectedPlate, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                        Text(
                            "${uiState.selectedPlateHistory.size} observations • max theoretical radius ${uiState.theoreticalRadiusMeters.toInt()} m",
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onShareSelected) {
                            Icon(Icons.Default.Share, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Share")
                        }
                        TextButton(onClick = { onSelectPlate(null) }) {
                            Text("Clear")
                        }
                    }
                }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(uiState.filteredSightings, key = { it.id }) { sighting ->
                HistoryRow(
                    sighting = sighting,
                    onClick = { onSelectPlate(sighting.plateNumber) },
                    onRetry = { onRetrySighting(sighting.clientGeneratedId) },
                )
            }
        }
    }
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
                )
                FilterChip(
                    selected = uiState.timeFilter == TimeFilter.ALL,
                    onClick = { onTimeFilterChange(TimeFilter.ALL) },
                    label = { Text("All") },
                )
                FilterChip(
                    selected = uiState.timeFilter == TimeFilter.LAST_24_HOURS,
                    onClick = { onTimeFilterChange(TimeFilter.LAST_24_HOURS) },
                    label = { Text("24 h") },
                )
                FilterChip(
                    selected = uiState.timeFilter == TimeFilter.LAST_7_DAYS,
                    onClick = { onTimeFilterChange(TimeFilter.LAST_7_DAYS) },
                    label = { Text("7 d") },
                )
            }
        }
    }
}

@Composable
private fun HistoryRow(
    sighting: PlateSighting,
    onClick: () -> Unit,
    onRetry: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (sighting.imageUri != null) {
                AsyncImage(
                    model = sighting.imageUri,
                    contentDescription = sighting.plateNumber,
                    modifier = Modifier
                        .size(92.dp)
                        .heightIn(min = 92.dp),
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
    val outputFile = createImageFile(context)
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

private fun createImageFile(context: Context): File {
    val imagesDirectory = File(context.cacheDir, "images").apply { mkdirs() }
    return File(imagesDirectory, "capture_${System.currentTimeMillis()}.jpg")
}

private fun isPermissionGranted(context: Context, permission: String): Boolean {
    return ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
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