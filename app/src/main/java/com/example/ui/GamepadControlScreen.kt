package com.example.ui

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ButtonConfig
import com.example.ControllerProfile
import com.example.R
import com.example.adb.AdbEndpoint
import com.example.data.ControllerPreferences
import com.example.input.BuiltInEngineState
import com.example.input.InputTelemetry
import com.example.ui.theme.AlertCrimson
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.PrecisionAmber
import com.example.ui.theme.SignalEmerald
import com.example.ui.theme.TelemetryCobalt
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun GamepadControlScreen(
    uiState: MainUiState,
    onOpenDeveloperOptions: () -> Unit,
    onStartNotificationPairing: () -> Unit,
    onPairWirelessDebugging: (port: Int, code: String) -> Unit,
    onConnectWirelessDebugging: (manualPort: Int?) -> Unit,
    onDisconnectBuiltInEngine: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onStartOverlay: () -> Unit,
    onStopOverlay: () -> Unit,
    onToggleEditMode: (Boolean) -> Unit,
    onSelectProfile: (ControllerProfile) -> Unit,
    onAddExtraButton: () -> Unit,
    onUpdateButtonConfig: (ButtonConfig) -> Unit,
    onDeleteExtraButton: (String) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onScaleChange: (Float) -> Unit,
    onHapticsChange: (Boolean) -> Unit,
    onHapticIntensityChange: (Float) -> Unit,
    onPreviewHaptics: () -> Unit,
    onResetLayout: () -> Unit
) {
    var showPairDialog by remember { mutableStateOf(false) }
    var showConnectDialog by remember { mutableStateOf(false) }
    var editingButtonConfig by remember { mutableStateOf<ButtonConfig?>(null) }

    val primaryGlowColor = when {
        uiState.isEditMode -> PrecisionAmber
        uiState.isOverlayRunning -> ElectricCyan
        uiState.isEngineReady -> SignalEmerald
        else -> TelemetryCobalt
    }
    val animatedGlow by animateColorAsState(
        targetValue = primaryGlowColor,
        animationSpec = spring(),
        label = "ambientGlow"
    )

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(color = Color(0xFF0A0D13))
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            animatedGlow.copy(alpha = 0.16f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.82f, size.height * 0.08f),
                        radius = size.maxDimension * 0.58f
                    ),
                    center = Offset(size.width * 0.82f, size.height * 0.08f),
                    radius = size.maxDimension * 0.58f
                )
            },
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val isWideScreen = maxWidth >= 700.dp

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = if (isWideScreen) 960.dp else 600.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        HeroHeader(uiState = uiState)

                        if (isWideScreen) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    ControllerDeckCard(
                                        uiState = uiState,
                                        onStartOverlay = onStartOverlay,
                                        onStopOverlay = onStopOverlay,
                                        onToggleEditMode = onToggleEditMode,
                                        onResetLayout = onResetLayout
                                    )
                                    ControlSettingsCard(
                                        activeProfile = uiState.activeProfile,
                                        buttonConfigs = uiState.buttonConfigs,
                                        isOverlayRunning = uiState.isOverlayRunning,
                                        onSelectProfile = onSelectProfile,
                                        onAddExtraButton = onAddExtraButton,
                                        onEditButton = { editingButtonConfig = it }
                                    )
                                    BuiltInShizukuEngineCard(
                                        uiState = uiState,
                                        onOpenDeveloperOptions = onOpenDeveloperOptions,
                                        onOpenPairDialog = { showPairDialog = true },
                                        onOpenConnectDialog = { showConnectDialog = true },
                                        onDisconnectEngine = onDisconnectBuiltInEngine
                                    )
                                    SystemPermissionsCard(
                                        uiState = uiState,
                                        onRequestOverlayPermission = onRequestOverlayPermission,
                                        onRequestNotificationPermission = onRequestNotificationPermission
                                    )
                                }
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    TuningCard(
                                        uiState = uiState,
                                        onOpacityChange = onOpacityChange,
                                        onScaleChange = onScaleChange,
                                        onHapticsChange = onHapticsChange,
                                        onHapticIntensityChange = onHapticIntensityChange,
                                        onPreviewHaptics = onPreviewHaptics
                                    )
                                    LiveTelemetryCard(telemetry = uiState.telemetry)
                                }
                            }
                        } else {
                            ControllerDeckCard(
                                uiState = uiState,
                                onStartOverlay = onStartOverlay,
                                onStopOverlay = onStopOverlay,
                                onToggleEditMode = onToggleEditMode,
                                onResetLayout = onResetLayout
                            )
                            ControlSettingsCard(
                                activeProfile = uiState.activeProfile,
                                buttonConfigs = uiState.buttonConfigs,
                                isOverlayRunning = uiState.isOverlayRunning,
                                onSelectProfile = onSelectProfile,
                                onAddExtraButton = onAddExtraButton,
                                onEditButton = { editingButtonConfig = it }
                            )
                            BuiltInShizukuEngineCard(
                                uiState = uiState,
                                onOpenDeveloperOptions = onOpenDeveloperOptions,
                                onOpenPairDialog = { showPairDialog = true },
                                onOpenConnectDialog = { showConnectDialog = true },
                                onDisconnectEngine = onDisconnectBuiltInEngine
                            )
                            SystemPermissionsCard(
                                uiState = uiState,
                                onRequestOverlayPermission = onRequestOverlayPermission,
                                onRequestNotificationPermission = onRequestNotificationPermission
                            )
                            TuningCard(
                                uiState = uiState,
                                onOpacityChange = onOpacityChange,
                                onScaleChange = onScaleChange,
                                onHapticsChange = onHapticsChange,
                                onHapticIntensityChange = onHapticIntensityChange,
                                onPreviewHaptics = onPreviewHaptics
                            )
                            LiveTelemetryCard(telemetry = uiState.telemetry)
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }

    if (showPairDialog) {
        WirelessPairDialog(
            discoveredPairingEndpoint = uiState.discoveredPairingEndpoint,
            onStartNotificationPairing = {
                showPairDialog = false
                onStartNotificationPairing()
                onOpenDeveloperOptions()
            },
            onOpenDeveloperOptions = onOpenDeveloperOptions,
            onDismiss = { showPairDialog = false },
            onPair = { port, code ->
                showPairDialog = false
                onPairWirelessDebugging(port, code)
            }
        )
    }

    if (showConnectDialog) {
        WirelessConnectDialog(
            discoveredConnectEndpoint = uiState.discoveredConnectEndpoint,
            onOpenDeveloperOptions = onOpenDeveloperOptions,
            onDismiss = { showConnectDialog = false },
            onConnect = { manualPort ->
                showConnectDialog = false
                onConnectWirelessDebugging(manualPort)
            }
        )
    }

    editingButtonConfig?.let { target ->
        ComposeButtonConfigDialog(
            config = target,
            activeProfile = uiState.activeProfile,
            onDismiss = { editingButtonConfig = null },
            onSave = { updated ->
                editingButtonConfig = null
                onUpdateButtonConfig(updated)
            },
            onDelete = { id ->
                editingButtonConfig = null
                onDeleteExtraButton(id)
            }
        )
    }
}

@Composable
private fun BuiltInShizukuEngineCard(
    uiState: MainUiState,
    onOpenDeveloperOptions: () -> Unit,
    onOpenPairDialog: () -> Unit,
    onOpenConnectDialog: () -> Unit,
    onDisconnectEngine: () -> Unit
) {
    val state = uiState.builtInEngineState
    val isConnected = uiState.isEngineReady
    val isBusy = state == BuiltInEngineState.PAIRING || state == BuiltInEngineState.CONNECTING

    val statusColor = when {
        isConnected -> SignalEmerald
        state == BuiltInEngineState.ERROR -> AlertCrimson
        isBusy -> TelemetryCobalt
        else -> PrecisionAmber
    }

    val statusText = uiState.builtInMessage.ifBlank {
        when (state) {
            BuiltInEngineState.NOT_PAIRED -> stringResource(R.string.builtin_state_not_paired)
            BuiltInEngineState.PAIRED_IDLE -> stringResource(R.string.builtin_state_paired_idle)
            BuiltInEngineState.PAIRING -> stringResource(R.string.builtin_state_pairing)
            BuiltInEngineState.CONNECTING -> stringResource(R.string.builtin_state_connecting)
            BuiltInEngineState.CONNECTED -> stringResource(R.string.builtin_state_connected)
            BuiltInEngineState.ERROR -> stringResource(R.string.builtin_state_error)
        }
    }

    DeckCard(
        title = stringResource(R.string.section_builtin_engine),
        trailingIcon = Icons.Filled.Wifi
    ) {
        Text(
            text = stringResource(R.string.builtin_engine_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Live Engine Status Box
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(statusColor.copy(alpha = 0.11f))
                .border(1.dp, statusColor.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .testTag("builtin_engine_status_box"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.5.dp,
                    color = statusColor
                )
            } else {
                Icon(
                    imageVector = if (isConnected) Icons.Filled.CheckCircle else Icons.Filled.Wifi,
                    contentDescription = stringResource(R.string.cd_status_icon),
                    tint = statusColor,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                val detectedEndpoint = uiState.discoveredConnectEndpoint
                if (detectedEndpoint != null && !isConnected) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(
                            R.string.mdns_detected_badge,
                            detectedEndpoint.host,
                            detectedEndpoint.port
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Primary Wireless Debugging Pair & Connect Controls
        if (isConnected) {
            OutlinedButton(
                onClick = onDisconnectEngine,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("disconnect_engine_button"),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, AlertCrimson.copy(alpha = 0.7f))
            ) {
                Icon(
                    imageVector = Icons.Filled.LinkOff,
                    contentDescription = null,
                    tint = AlertCrimson,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.action_disconnect_wireless),
                    style = MaterialTheme.typography.labelLarge,
                    color = AlertCrimson
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenPairDialog,
                    enabled = !isBusy,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("pair_wireless_button"),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    border = BorderStroke(1.dp, PrecisionAmber.copy(alpha = 0.75f))
                ) {
                    Icon(
                        imageVector = Icons.Filled.Pin,
                        contentDescription = null,
                        tint = PrecisionAmber,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.action_pair_wireless),
                        style = MaterialTheme.typography.labelLarge,
                        color = PrecisionAmber,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Button(
                    onClick = onOpenConnectDialog,
                    enabled = !isBusy,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("connect_wireless_button"),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Link,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.action_connect_wireless),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                onClick = onOpenDeveloperOptions,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("open_dev_options_button"),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Icon(
                    imageVector = Icons.Filled.DeveloperMode,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.action_open_dev_options),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun WirelessPairDialog(
    discoveredPairingEndpoint: AdbEndpoint?,
    onStartNotificationPairing: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    onDismiss: () -> Unit,
    onPair: (port: Int, code: String) -> Unit
) {
    var portInput by remember { mutableStateOf(discoveredPairingEndpoint?.port?.toString().orEmpty()) }
    var codeInput by remember { mutableStateOf("") }

    LaunchedEffect(discoveredPairingEndpoint) {
        if (discoveredPairingEndpoint != null && portInput.isBlank()) {
            portInput = discoveredPairingEndpoint.port.toString()
        }
    }

    val parsedPort = portInput.trim().toIntOrNull()
    val isFormValid = parsedPort != null && parsedPort in 1..65535 &&
        codeInput.trim().length == 6 && codeInput.trim().all { it.isDigit() }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = stringResource(R.string.dialog_pair_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.dialog_pair_guide),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Button(
                    onClick = onStartNotificationPairing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("pair_via_notification_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrecisionAmber,
                        contentColor = MaterialTheme.colorScheme.onSecondary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Outlined.NotificationsActive,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.action_pair_via_notification),
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Text(
                    text = stringResource(R.string.dialog_pair_manual_divider),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (discoveredPairingEndpoint != null) {
                    Text(
                        text = stringResource(
                            R.string.mdns_detected_badge,
                            discoveredPairingEndpoint.host,
                            discoveredPairingEndpoint.port
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = ElectricCyan
                    )
                }

                OutlinedTextField(
                    value = portInput,
                    onValueChange = { value -> portInput = value.filter { it.isDigit() }.take(5) },
                    label = { Text(stringResource(R.string.field_pairing_port)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pair_port_input")
                )

                OutlinedTextField(
                    value = codeInput,
                    onValueChange = { value -> codeInput = value.filter { it.isDigit() }.take(6) },
                    label = { Text(stringResource(R.string.field_pairing_code)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pair_code_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (parsedPort != null) {
                        onPair(parsedPort, codeInput.trim())
                    }
                },
                enabled = isFormValid,
                modifier = Modifier.testTag("confirm_pair_button")
            ) {
                Text(stringResource(R.string.action_pair_now))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun WirelessConnectDialog(
    discoveredConnectEndpoint: AdbEndpoint?,
    onOpenDeveloperOptions: () -> Unit,
    onDismiss: () -> Unit,
    onConnect: (manualPort: Int?) -> Unit
) {
    var portInput by remember { mutableStateOf(discoveredConnectEndpoint?.port?.toString().orEmpty()) }

    LaunchedEffect(discoveredConnectEndpoint) {
        if (discoveredConnectEndpoint != null && portInput.isBlank()) {
            portInput = discoveredConnectEndpoint.port.toString()
        }
    }

    val trimmed = portInput.trim()
    val parsedPort = trimmed.toIntOrNull()
    val isValid = trimmed.isEmpty() || (parsedPort != null && parsedPort in 1..65535)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = stringResource(R.string.dialog_connect_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.dialog_connect_guide),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedButton(
                    onClick = onOpenDeveloperOptions,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeveloperMode,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.action_open_dev_options))
                }

                if (discoveredConnectEndpoint != null) {
                    Text(
                        text = stringResource(
                            R.string.mdns_detected_badge,
                            discoveredConnectEndpoint.host,
                            discoveredConnectEndpoint.port
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = ElectricCyan
                    )
                }

                OutlinedTextField(
                    value = portInput,
                    onValueChange = { value -> portInput = value.filter { it.isDigit() }.take(5) },
                    label = { Text(stringResource(R.string.field_connect_port)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("connect_port_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConnect(parsedPort) },
                enabled = isValid,
                modifier = Modifier.testTag("confirm_connect_button")
            ) {
                Text(stringResource(R.string.action_connect_now))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun HeroHeader(uiState: MainUiState) {
    val badgeColor = when {
        uiState.isOverlayRunning -> SignalEmerald
        uiState.hasOverlayPermission && uiState.isEngineReady -> ElectricCyan
        else -> PrecisionAmber
    }
    val badgeText = when {
        uiState.isOverlayRunning -> stringResource(R.string.status_overlay_active)
        uiState.hasOverlayPermission && uiState.isEngineReady ->
            stringResource(R.string.status_ready)
        else -> stringResource(R.string.status_setup_required)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(14.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.SportsEsports,
                    contentDescription = stringResource(R.string.cd_gamepad_emblem),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
            }
            Column {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = stringResource(R.string.tagline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(badgeColor.copy(alpha = 0.14f))
                .border(1.dp, badgeColor.copy(alpha = 0.45f), RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag("engine_status_pill"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(badgeColor)
            )
            Text(
                text = badgeText,
                style = MaterialTheme.typography.labelMedium,
                color = badgeColor
            )
        }
    }
}

@Composable
private fun ControllerDeckCard(
    uiState: MainUiState,
    onStartOverlay: () -> Unit,
    onStopOverlay: () -> Unit,
    onToggleEditMode: (Boolean) -> Unit,
    onResetLayout: () -> Unit
) {
    val canLaunch = uiState.hasOverlayPermission

    DeckCard(title = stringResource(R.string.section_controller_deck)) {
        Button(
            onClick = {
                if (uiState.isOverlayRunning) onStopOverlay() else onStartOverlay()
            },
            enabled = uiState.isOverlayRunning || canLaunch,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .testTag("toggle_overlay_button"),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (uiState.isOverlayRunning) AlertCrimson else MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            contentPadding = PaddingValues(horizontal = 20.dp)
        ) {
            Icon(
                imageVector = if (uiState.isOverlayRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = if (uiState.isOverlayRunning) {
                    stringResource(R.string.action_stop_overlay)
                } else {
                    stringResource(R.string.action_start_overlay)
                },
                style = MaterialTheme.typography.labelLarge
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        val editBorderColor = if (uiState.isEditMode) {
            PrecisionAmber.copy(alpha = 0.65f)
        } else {
            MaterialTheme.colorScheme.outlineVariant
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (uiState.isEditMode) PrecisionAmber.copy(alpha = 0.1f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                )
                .border(1.dp, editBorderColor, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.OpenWith,
                    contentDescription = null,
                    tint = if (uiState.isEditMode) PrecisionAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
                Column {
                    Text(
                        text = stringResource(R.string.edit_mode_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (uiState.isEditMode) PrecisionAmber else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.edit_mode_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = uiState.isEditMode,
                onCheckedChange = onToggleEditMode,
                enabled = canLaunch,
                modifier = Modifier.testTag("edit_mode_switch"),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onSecondary,
                    checkedTrackColor = PrecisionAmber
                )
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onResetLayout,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("reset_layout_button"),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Icon(
                imageVector = Icons.Filled.RestartAlt,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.action_reset_layout),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun SystemPermissionsCard(
    uiState: MainUiState,
    onRequestOverlayPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit
) {
    DeckCard(title = stringResource(R.string.section_system_checks)) {
        ReadinessRow(
            icon = Icons.Outlined.Layers,
            title = stringResource(R.string.perm_overlay_title),
            subtitle = if (uiState.hasOverlayPermission) {
                stringResource(R.string.perm_overlay_granted)
            } else {
                stringResource(R.string.perm_overlay_denied)
            },
            isOk = uiState.hasOverlayPermission,
            actionLabel = if (!uiState.hasOverlayPermission) {
                stringResource(R.string.perm_overlay_action)
            } else null,
            actionTestTag = "grant_overlay_button",
            onAction = onRequestOverlayPermission
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            ReadinessRow(
                icon = Icons.Outlined.NotificationsActive,
                title = stringResource(R.string.perm_notif_title),
                subtitle = if (uiState.hasNotificationPermission) {
                    stringResource(R.string.perm_notif_granted)
                } else {
                    stringResource(R.string.perm_notif_desc)
                },
                isOk = uiState.hasNotificationPermission,
                actionLabel = if (!uiState.hasNotificationPermission) {
                    stringResource(R.string.perm_overlay_action)
                } else null,
                actionTestTag = "grant_notification_button",
                onAction = onRequestNotificationPermission
            )
        }
    }
}

@Composable
private fun ReadinessRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isOk: Boolean,
    actionLabel: String?,
    actionTestTag: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (isOk) SignalEmerald.copy(alpha = 0.14f)
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isOk) Icons.Filled.CheckCircle else icon,
                contentDescription = stringResource(R.string.cd_status_icon),
                tint = if (isOk) SignalEmerald else PrecisionAmber,
                modifier = Modifier.size(20.dp)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (actionLabel != null) {
            OutlinedButton(
                onClick = onAction,
                modifier = Modifier
                    .height(48.dp)
                    .testTag(actionTestTag),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.65f))
            ) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun TuningCard(
    uiState: MainUiState,
    onOpacityChange: (Float) -> Unit,
    onScaleChange: (Float) -> Unit,
    onHapticsChange: (Boolean) -> Unit,
    onHapticIntensityChange: (Float) -> Unit,
    onPreviewHaptics: () -> Unit
) {
    DeckCard(
        title = stringResource(R.string.section_tuning),
        trailingIcon = Icons.Filled.Tune
    ) {
        val opacityPercent = (uiState.opacity * 100f).roundToInt()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.opacity_label),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "$opacityPercent%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = uiState.opacity,
            onValueChange = onOpacityChange,
            valueRange = ControllerPreferences.MIN_OPACITY..ControllerPreferences.MAX_OPACITY,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("opacity_slider"),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.outline
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        val scalePercent = (uiState.scale * 100f).roundToInt()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.scale_label),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "$scalePercent%",
                style = MaterialTheme.typography.labelMedium,
                color = TelemetryCobalt
            )
        }
        Slider(
            value = uiState.scale,
            onValueChange = onScaleChange,
            valueRange = ControllerPreferences.MIN_SCALE..ControllerPreferences.MAX_SCALE,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("scale_slider"),
            colors = SliderDefaults.colors(
                thumbColor = TelemetryCobalt,
                activeTrackColor = TelemetryCobalt,
                inactiveTrackColor = MaterialTheme.colorScheme.outline
            )
        )

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 10.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Vibration,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Column {
                    Text(
                        text = stringResource(R.string.haptics_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.haptics_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = uiState.hapticsEnabled,
                onCheckedChange = onHapticsChange,
                modifier = Modifier.testTag("haptics_switch"),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary
                )
            )
        }

        AnimatedVisibility(
            visible = uiState.hapticsEnabled,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            val intensityPercent = (uiState.hapticIntensity * 100f).roundToInt()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(14.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.haptic_intensity_label),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "$intensityPercent%",
                        style = MaterialTheme.typography.labelMedium,
                        color = PrecisionAmber
                    )
                }

                Slider(
                    value = uiState.hapticIntensity,
                    onValueChange = onHapticIntensityChange,
                    onValueChangeFinished = onPreviewHaptics,
                    valueRange = ControllerPreferences.MIN_HAPTIC_INTENSITY..ControllerPreferences.MAX_HAPTIC_INTENSITY,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("haptic_intensity_slider"),
                    colors = SliderDefaults.colors(
                        thumbColor = PrecisionAmber,
                        activeTrackColor = PrecisionAmber,
                        inactiveTrackColor = MaterialTheme.colorScheme.outline
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val presets = listOf(
                        stringResource(R.string.haptic_preset_light) to 0.35f,
                        stringResource(R.string.haptic_preset_crisp) to 0.70f,
                        stringResource(R.string.haptic_preset_strong) to 1.00f
                    )
                    presets.forEach { (presetLabel, presetValue) ->
                        val selected = abs(uiState.hapticIntensity - presetValue) < 0.05f
                        FilterChip(
                            selected = selected,
                            onClick = {
                                onHapticIntensityChange(presetValue)
                                onPreviewHaptics()
                            },
                            label = {
                                Text(
                                    text = presetLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("haptic_preset_${presetLabel.lowercase()}"),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = PrecisionAmber.copy(alpha = 0.2f),
                                selectedLabelColor = PrecisionAmber
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onPreviewHaptics,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("test_haptics_button"),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    border = BorderStroke(1.dp, PrecisionAmber.copy(alpha = 0.65f))
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Vibration,
                        contentDescription = null,
                        tint = PrecisionAmber,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.haptic_test_action),
                        style = MaterialTheme.typography.labelLarge,
                        color = PrecisionAmber
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveTelemetryCard(telemetry: InputTelemetry) {
    DeckCard(title = stringResource(R.string.section_telemetry)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StickVectorScope(
                label = stringResource(R.string.telemetry_left_stick),
                x = telemetry.leftX,
                y = telemetry.leftY,
                accent = ElectricCyan
            )
            StickVectorScope(
                label = stringResource(R.string.telemetry_right_stick),
                x = telemetry.rightX,
                y = telemetry.rightY,
                accent = TelemetryCobalt
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.7f))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.telemetry_last_event),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = telemetry.lastSignal.ifBlank { stringResource(R.string.telemetry_idle) },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = stringResource(R.string.telemetry_packets_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = telemetry.injectedPackets.toString(),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun StickVectorScope(
    label: String,
    x: Float,
    y: Float,
    accent: Color
) {
    val outlineColor = MaterialTheme.colorScheme.outline
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            modifier = Modifier
                .size(72.dp)
                .padding(4.dp)
        ) {
            val radius = size.minDimension * 0.5f
            val center = Offset(size.width * 0.5f, size.height * 0.5f)
            drawCircle(color = Color(0xFF0B0F18), radius = radius, center = center)
            drawCircle(
                color = outlineColor,
                radius = radius,
                center = center,
                style = Stroke(width = 1.5.dp.toPx())
            )
            drawLine(
                color = outlineColor.copy(alpha = 0.5f),
                start = Offset(center.x - radius, center.y),
                end = Offset(center.x + radius, center.y),
                strokeWidth = 1.dp.toPx()
            )
            drawLine(
                color = outlineColor.copy(alpha = 0.5f),
                start = Offset(center.x, center.y - radius),
                end = Offset(center.x, center.y + radius),
                strokeWidth = 1.dp.toPx()
            )
            val dot = Offset(
                center.x + x.coerceIn(-1f, 1f) * (radius - 6.dp.toPx()),
                center.y + y.coerceIn(-1f, 1f) * (radius - 6.dp.toPx())
            )
            drawLine(
                color = accent.copy(alpha = 0.7f),
                start = center,
                end = dot,
                strokeWidth = 2.dp.toPx()
            )
            drawCircle(color = accent, radius = 5.dp.toPx(), center = dot)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "%.2f, %.2f".format(x, y),
            style = MaterialTheme.typography.labelSmall,
            color = accent
        )
    }
}

@Composable
private fun DeckCard(
    title: String,
    trailingIcon: ImageVector? = null,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (trailingIcon != null) {
                    Icon(
                        imageVector = trailingIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            content()
        }
    }
}
