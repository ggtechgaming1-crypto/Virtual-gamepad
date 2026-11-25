package com.example.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ButtonConfig
import com.example.ButtonConfigStore
import com.example.ControllerProfile
import com.example.OverlayService
import com.example.adb.AdbEndpoint
import com.example.data.ControllerPreferences
import com.example.haptics.HapticFeedbackEngine
import com.example.input.BuiltInEngineState
import com.example.input.InputTelemetry
import com.example.input.ShizukuInputController
import com.example.input.ShizukuStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class MainUiState(
    val hasOverlayPermission: Boolean = false,
    val hasNotificationPermission: Boolean = true,
    val builtInEngineState: BuiltInEngineState = BuiltInEngineState.NOT_PAIRED,
    val builtInMessage: String = "",
    val discoveredPairingEndpoint: AdbEndpoint? = null,
    val discoveredConnectEndpoint: AdbEndpoint? = null,
    val externalShizukuStatus: ShizukuStatus = ShizukuStatus.NOT_INSTALLED,
    val isOverlayRunning: Boolean = false,
    val isEditMode: Boolean = false,
    val activeProfile: ControllerProfile = ControllerProfile.XBOX,
    val opacity: Float = ControllerPreferences.DEFAULT_OPACITY,
    val scale: Float = ControllerPreferences.DEFAULT_SCALE,
    val hapticsEnabled: Boolean = true,
    val hapticIntensity: Float = ControllerPreferences.DEFAULT_HAPTIC_INTENSITY,
    val buttonConfigs: List<ButtonConfig> = emptyList(),
    val telemetry: InputTelemetry = InputTelemetry()
) {
    val isEngineReady: Boolean
        get() = builtInEngineState == BuiltInEngineState.CONNECTED ||
            externalShizukuStatus == ShizukuStatus.CONNECTED
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = ControllerPreferences(application)
    private val hapticEngine = HapticFeedbackEngine(application)

    private val _hasOverlayPermission = MutableStateFlow(false)
    private val _hasNotificationPermission = MutableStateFlow(true)
    private val _scale = MutableStateFlow(prefs.scale)
    private val _hapticsEnabled = MutableStateFlow(prefs.hapticsEnabled)
    private val _hapticIntensity = MutableStateFlow(prefs.hapticIntensity)

    private val permissionsAndTuning = combine(
        _hasOverlayPermission,
        _hasNotificationPermission,
        ShizukuInputController.opacitySync,
        _scale,
        combine(_hapticsEnabled, _hapticIntensity) { enabled, intensity -> enabled to intensity }
    ) { overlayPerm, notifPerm, opacity, scale, hapticsPair ->
        TuningSnapshot(
            hasOverlayPermission = overlayPerm,
            hasNotificationPermission = notifPerm,
            opacity = opacity,
            scale = scale,
            hapticsEnabled = hapticsPair.first,
            hapticIntensity = hapticsPair.second
        )
    }

    private val builtInSnapshot = combine(
        ShizukuInputController.builtInState,
        ShizukuInputController.builtInMessage,
        ShizukuInputController.discoveredPairingEndpoint,
        ShizukuInputController.discoveredConnectEndpoint,
        ShizukuInputController.status
    ) { engineState, message, pairingEndpoint, connectEndpoint, extStatus ->
        BuiltInSnapshot(
            engineState = engineState,
            message = message,
            pairingEndpoint = pairingEndpoint,
            connectEndpoint = connectEndpoint,
            externalShizukuStatus = extStatus
        )
    }

    private val overlayAndConfigsSnapshot = combine(
        ShizukuInputController.isOverlayRunning,
        ShizukuInputController.isEditMode,
        ShizukuInputController.activeProfile,
        ShizukuInputController.buttonConfigsVersion
    ) { overlayRunning, editMode, profile, _ ->
        OverlayConfigsSnapshot(
            isOverlayRunning = overlayRunning,
            isEditMode = editMode,
            activeProfile = profile,
            buttonConfigs = loadCurrentButtonConfigs(profile)
        )
    }

    val uiState: StateFlow<MainUiState> = combine(
        permissionsAndTuning,
        builtInSnapshot,
        overlayAndConfigsSnapshot,
        ShizukuInputController.telemetry
    ) { tuning, engine, overlaySnap, telemetry ->
        MainUiState(
            hasOverlayPermission = tuning.hasOverlayPermission,
            hasNotificationPermission = tuning.hasNotificationPermission,
            builtInEngineState = engine.engineState,
            builtInMessage = engine.message,
            discoveredPairingEndpoint = engine.pairingEndpoint,
            discoveredConnectEndpoint = engine.connectEndpoint,
            externalShizukuStatus = engine.externalShizukuStatus,
            isOverlayRunning = overlaySnap.isOverlayRunning,
            isEditMode = overlaySnap.isEditMode,
            activeProfile = overlaySnap.activeProfile,
            opacity = tuning.opacity,
            scale = tuning.scale,
            hapticsEnabled = tuning.hapticsEnabled,
            hapticIntensity = tuning.hapticIntensity,
            buttonConfigs = overlaySnap.buttonConfigs,
            telemetry = telemetry
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState(
            activeProfile = ButtonConfigStore.getActiveProfile(prefs.sharedPrefs),
            opacity = prefs.opacity,
            scale = prefs.scale,
            hapticsEnabled = prefs.hapticsEnabled,
            hapticIntensity = prefs.hapticIntensity,
            buttonConfigs = loadCurrentButtonConfigs(ButtonConfigStore.getActiveProfile(prefs.sharedPrefs))
        )
    )

    init {
        ShizukuInputController.init(application)
        ShizukuInputController.setActiveProfile(ButtonConfigStore.getActiveProfile(prefs.sharedPrefs))
        ShizukuInputController.setOpacitySync(prefs.opacity)
        refreshSystemState()
    }

    private fun loadCurrentButtonConfigs(profile: ControllerProfile): List<ButtonConfig> {
        val dm = getApplication<Application>().resources.displayMetrics
        return ButtonConfigStore.loadConfigs(
            prefs = prefs.sharedPrefs,
            controllerPrefs = prefs,
            screenW = dm.widthPixels.coerceAtLeast(1080),
            screenH = dm.heightPixels.coerceAtLeast(1920),
            density = dm.density,
            scale = prefs.scale,
            profile = profile
        )
    }

    fun startWirelessDiscovery() {
        ShizukuInputController.startWirelessDiscovery()
    }

    fun stopWirelessDiscovery() {
        ShizukuInputController.stopWirelessDiscovery()
    }

    fun refreshSystemState() {
        val ctx = getApplication<Application>()
        _hasOverlayPermission.value = Settings.canDrawOverlays(ctx)
        _hasNotificationPermission.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                ctx,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        ShizukuInputController.setActiveProfile(ButtonConfigStore.getActiveProfile(prefs.sharedPrefs))
        ShizukuInputController.setOpacitySync(prefs.opacity)
        ShizukuInputController.refreshState()
        ShizukuInputController.notifyButtonConfigsChanged()
    }

    fun startNotificationPairing() {
        ShizukuInputController.startNotificationPairing(getApplication())
    }

    fun pairWirelessDebugging(port: Int, pairingCode: String) {
        ShizukuInputController.pairWirelessDebugging(port, pairingCode)
    }

    fun connectWirelessDebugging(manualPort: Int?) {
        ShizukuInputController.connectWirelessAndStartEngine(manualPort)
    }

    fun disconnectBuiltInEngine() {
        ShizukuInputController.disconnectBuiltInEngine()
    }

    fun startOverlay() {
        val ctx = getApplication<Application>()
        if (!Settings.canDrawOverlays(ctx)) return
        OverlayService.start(ctx, editMode = false)
    }

    fun stopOverlay() {
        val ctx = getApplication<Application>()
        OverlayService.stop(ctx)
    }

    fun setEditMode(enabled: Boolean) {
        val ctx = getApplication<Application>()
        if (!Settings.canDrawOverlays(ctx)) return
        if (ShizukuInputController.isOverlayRunning.value) {
            OverlayService.setEditMode(ctx, enabled)
        } else if (enabled) {
            OverlayService.start(ctx, editMode = true)
        }
    }

    fun selectProfile(profile: ControllerProfile) {
        ButtonConfigStore.setActiveProfile(prefs.sharedPrefs, profile)
        ShizukuInputController.setActiveProfile(profile)
        ShizukuInputController.notifyButtonConfigsChanged()
        if (ShizukuInputController.isOverlayRunning.value) {
            OverlayService.reloadButtonConfigs(getApplication())
        }
    }

    fun addExtraButton() {
        val ctx = getApplication<Application>()
        if (ShizukuInputController.isOverlayRunning.value) {
            OverlayService.addExtraButton(ctx)
        } else {
            val dm = ctx.resources.displayMetrics
            val profile = ButtonConfigStore.getActiveProfile(prefs.sharedPrefs)
            val current = loadCurrentButtonConfigs(profile).toMutableList()
            val extra = ButtonConfigStore.createExtraButton(
                existing = current,
                screenW = dm.widthPixels.coerceAtLeast(1080),
                screenH = dm.heightPixels.coerceAtLeast(1920),
                density = dm.density,
                scale = prefs.scale,
                profile = profile
            )
            current.add(extra)
            ButtonConfigStore.saveConfigs(prefs.sharedPrefs, current, profile)
            ShizukuInputController.notifyButtonConfigsChanged()
        }
    }

    fun updateButtonConfig(updated: ButtonConfig) {
        val profile = ButtonConfigStore.getActiveProfile(prefs.sharedPrefs)
        val current = loadCurrentButtonConfigs(profile).map { cfg ->
            if (cfg.id == updated.id) updated else cfg
        }
        ButtonConfigStore.saveConfigs(prefs.sharedPrefs, current, profile)
        ShizukuInputController.notifyButtonConfigsChanged()
        if (ShizukuInputController.isOverlayRunning.value) {
            OverlayService.reloadButtonConfigs(getApplication())
        }
    }

    fun deleteExtraButton(configId: String) {
        val profile = ButtonConfigStore.getActiveProfile(prefs.sharedPrefs)
        val current = loadCurrentButtonConfigs(profile).filterNot { it.id == configId && it.isExtra }
        ButtonConfigStore.saveConfigs(prefs.sharedPrefs, current, profile)
        ShizukuInputController.notifyButtonConfigsChanged()
        if (ShizukuInputController.isOverlayRunning.value) {
            OverlayService.reloadButtonConfigs(getApplication())
        }
    }

    fun updateOpacity(newOpacity: Float) {
        val clamped = newOpacity.coerceIn(
            ControllerPreferences.MIN_OPACITY,
            ControllerPreferences.MAX_OPACITY
        )
        prefs.opacity = clamped
        ShizukuInputController.setOpacitySync(clamped)
        if (ShizukuInputController.isOverlayRunning.value) {
            OverlayService.notifySettingsChanged(getApplication())
        }
    }

    fun updateScale(newScale: Float) {
        val clamped = newScale.coerceIn(
            ControllerPreferences.MIN_SCALE,
            ControllerPreferences.MAX_SCALE
        )
        prefs.scale = clamped
        _scale.value = clamped
        if (ShizukuInputController.isOverlayRunning.value) {
            OverlayService.notifySettingsChanged(getApplication())
        }
    }

    fun setHapticsEnabled(enabled: Boolean) {
        prefs.hapticsEnabled = enabled
        _hapticsEnabled.value = enabled
        if (enabled) {
            hapticEngine.triggerPreview(_hapticIntensity.value)
        }
        if (ShizukuInputController.isOverlayRunning.value) {
            OverlayService.notifySettingsChanged(getApplication())
        }
    }

    fun updateHapticIntensity(newIntensity: Float) {
        val clamped = newIntensity.coerceIn(
            ControllerPreferences.MIN_HAPTIC_INTENSITY,
            ControllerPreferences.MAX_HAPTIC_INTENSITY
        )
        prefs.hapticIntensity = clamped
        _hapticIntensity.value = clamped
        if (ShizukuInputController.isOverlayRunning.value) {
            OverlayService.notifySettingsChanged(getApplication())
        }
    }

    fun previewHaptics() {
        if (_hapticsEnabled.value) {
            hapticEngine.triggerPreview(_hapticIntensity.value)
        }
    }

    fun resetLayout() {
        prefs.resetPositions()
        val profile = ButtonConfigStore.getActiveProfile(prefs.sharedPrefs)
        ButtonConfigStore.clearSavedConfigs(prefs.sharedPrefs, profile)
        ShizukuInputController.notifyButtonConfigsChanged()
        if (ShizukuInputController.isOverlayRunning.value) {
            OverlayService.resetLayout(getApplication())
        }
    }

    private data class TuningSnapshot(
        val hasOverlayPermission: Boolean,
        val hasNotificationPermission: Boolean,
        val opacity: Float,
        val scale: Float,
        val hapticsEnabled: Boolean,
        val hapticIntensity: Float
    )

    private data class BuiltInSnapshot(
        val engineState: BuiltInEngineState,
        val message: String,
        val pairingEndpoint: AdbEndpoint?,
        val connectEndpoint: AdbEndpoint?,
        val externalShizukuStatus: ShizukuStatus
    )

    private data class OverlayConfigsSnapshot(
        val isOverlayRunning: Boolean,
        val isEditMode: Boolean,
        val activeProfile: ControllerProfile,
        val buttonConfigs: List<ButtonConfig>
    )
}
