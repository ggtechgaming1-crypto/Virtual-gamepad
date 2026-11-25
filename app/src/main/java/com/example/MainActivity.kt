package com.example

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.GamepadControlScreen
import com.example.ui.MainViewModel
import com.example.ui.theme.PadForgeTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        setContent {
            PadForgeTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val lifecycleOwner = LocalLifecycleOwner.current

                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) {
                    viewModel.refreshSystemState()
                }

                val overlaySettingsLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) {
                    viewModel.refreshSystemState()
                }

                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_RESUME -> {
                                viewModel.refreshSystemState()
                                viewModel.startWirelessDiscovery()
                            }
                            Lifecycle.Event.ON_PAUSE -> {
                                viewModel.stopWirelessDiscovery()
                            }
                            else -> Unit
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                        viewModel.stopWirelessDiscovery()
                    }
                }

                GamepadControlScreen(
                    uiState = uiState,
                    onOpenDeveloperOptions = {
                        val devIntent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                        runCatching {
                            startActivity(devIntent)
                        }.onFailure {
                            startActivity(Intent(Settings.ACTION_SETTINGS))
                        }
                    },
                    onStartNotificationPairing = viewModel::startNotificationPairing,
                    onPairWirelessDebugging = viewModel::pairWirelessDebugging,
                    onConnectWirelessDebugging = viewModel::connectWirelessDebugging,
                    onDisconnectBuiltInEngine = viewModel::disconnectBuiltInEngine,
                    onRequestOverlayPermission = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        overlaySettingsLauncher.launch(intent)
                    },
                    onRequestNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onStartOverlay = viewModel::startOverlay,
                    onStopOverlay = viewModel::stopOverlay,
                    onToggleEditMode = viewModel::setEditMode,
                    onSelectProfile = viewModel::selectProfile,
                    onAddExtraButton = viewModel::addExtraButton,
                    onUpdateButtonConfig = viewModel::updateButtonConfig,
                    onDeleteExtraButton = viewModel::deleteExtraButton,
                    onOpacityChange = viewModel::updateOpacity,
                    onScaleChange = viewModel::updateScale,
                    onHapticsChange = viewModel::setHapticsEnabled,
                    onHapticIntensityChange = viewModel::updateHapticIntensity,
                    onPreviewHaptics = viewModel::previewHaptics,
                    onResetLayout = viewModel::resetLayout
                )
            }
        }
    }
}
