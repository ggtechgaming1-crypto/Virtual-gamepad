package com.example.input

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import com.example.BoundKey
import com.example.BuildConfig
import com.example.ControllerProfile
import com.example.IInputService
import com.example.InputUserService
import com.example.KeyCatalog
import com.example.KeyInputType
import com.example.adb.AdbEndpoint
import com.example.adb.BuiltInAdbManager
import com.example.adb.BuiltInDaemonClient
import com.example.adb.WirelessAdbDiscovery
import com.example.adb.WirelessPairingReceiver
import com.example.data.ControllerPreferences
import com.example.server.PadForgeInputServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class BuiltInEngineState {
    NOT_PAIRED,
    PAIRED_IDLE,
    PAIRING,
    CONNECTING,
    CONNECTED,
    ERROR
}

enum class ShizukuStatus {
    NOT_INSTALLED,
    NOT_RUNNING,
    PERMISSION_REQUIRED,
    BINDING,
    CONNECTED,
    ERROR
}

data class InputTelemetry(
    val leftX: Float = 0f,
    val leftY: Float = 0f,
    val rightX: Float = 0f,
    val rightY: Float = 0f,
    val lastSignal: String = "",
    val injectedPackets: Long = 0L
)

/**
 * Unified zero-lag input bridge prioritizing PadForge's Built-in Wireless ADB Engine
 * (self-contained PadForgeInputServer daemon inside this APK — no external Shizuku app needed),
 * while retaining seamless compatibility if an external Shizuku binder is already active.
 */
object ShizukuInputController {

    private const val TAG = "PadForgeInputBridge"
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    const val SHIZUKU_PERMISSION_REQUEST_CODE = 4096

    @Volatile
    private var inputService: IInputService? = null

    private val builtInClient = BuiltInDaemonClient()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val listenersRegistered = AtomicBoolean(false)
    private val isBound = AtomicBoolean(false)
    private val packetCounter = AtomicLong(0L)
    private val notificationPairingActive = AtomicBoolean(false)

    // Dedicated high-priority dispatch thread so UI touch callbacks never block
    private val dispatchThread = HandlerThread(
        "PadForgeInputDispatch",
        Process.THREAD_PRIORITY_URGENT_DISPLAY
    ).apply { start() }
    private val dispatchHandler = Handler(dispatchThread.looper)

    private val _builtInState = MutableStateFlow(BuiltInEngineState.NOT_PAIRED)
    val builtInState: StateFlow<BuiltInEngineState> = _builtInState.asStateFlow()

    private val _builtInMessage = MutableStateFlow("")
    val builtInMessage: StateFlow<String> = _builtInMessage.asStateFlow()

    private val _discoveredPairingEndpoint = MutableStateFlow<AdbEndpoint?>(null)
    val discoveredPairingEndpoint: StateFlow<AdbEndpoint?> = _discoveredPairingEndpoint.asStateFlow()

    private val _discoveredConnectEndpoint = MutableStateFlow<AdbEndpoint?>(null)
    val discoveredConnectEndpoint: StateFlow<AdbEndpoint?> = _discoveredConnectEndpoint.asStateFlow()

    private val _status = MutableStateFlow(ShizukuStatus.NOT_RUNNING)
    val status: StateFlow<ShizukuStatus> = _status.asStateFlow()

    private val _telemetry = MutableStateFlow(InputTelemetry())
    val telemetry: StateFlow<InputTelemetry> = _telemetry.asStateFlow()

    private val _isOverlayRunning = MutableStateFlow(false)
    val isOverlayRunning: StateFlow<Boolean> = _isOverlayRunning.asStateFlow()

    private val _isEditMode = MutableStateFlow(false)
    val isEditMode: StateFlow<Boolean> = _isEditMode.asStateFlow()

    private val _buttonConfigsVersion = MutableStateFlow(0L)
    val buttonConfigsVersion: StateFlow<Long> = _buttonConfigsVersion.asStateFlow()

    private val _activeProfile = MutableStateFlow(ControllerProfile.XBOX)
    val activeProfile: StateFlow<ControllerProfile> = _activeProfile.asStateFlow()

    private val _opacitySync = MutableStateFlow(ControllerPreferences.DEFAULT_OPACITY)
    val opacitySync: StateFlow<Float> = _opacitySync.asStateFlow()

    // Reference-counted Hat Switch presses so multiple hat-bound buttons never cancel each other out
    private val hatLock = Any()
    private var hatUpCount = 0
    private var hatDownCount = 0
    private var hatLeftCount = 0
    private var hatRightCount = 0

    private var appContext: Context? = null
    private var prefs: ControllerPreferences? = null
    private var discovery: WirelessAdbDiscovery? = null
    private val discoveryStarted = AtomicBoolean(false)

    private val userServiceArgs: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, InputUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("input_daemon")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service != null && service.pingBinder()) {
                inputService = IInputService.Stub.asInterface(service)
                isBound.set(true)
                _status.value = ShizukuStatus.CONNECTED
            } else {
                inputService = null
                isBound.set(false)
                _status.value = ShizukuStatus.ERROR
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            inputService = null
            isBound.set(false)
            _status.value = if (Shizuku.pingBinder()) ShizukuStatus.ERROR else ShizukuStatus.NOT_RUNNING
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refreshExternalShizukuState()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        inputService = null
        isBound.set(false)
        _status.value = ShizukuStatus.NOT_RUNNING
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    bindUserService()
                } else {
                    _status.value = ShizukuStatus.PERMISSION_REQUIRED
                }
            }
        }

    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        if (prefs == null) {
            val loadedPrefs = ControllerPreferences(ctx)
            prefs = loadedPrefs
            _activeProfile.value = com.example.ButtonConfigStore.getActiveProfile(loadedPrefs.sharedPrefs)
            _opacitySync.value = loadedPrefs.opacity
        }
        if (discovery == null) {
            val disc = WirelessAdbDiscovery(ctx)
            discovery = disc
            ioScope.launch {
                disc.pairingEndpoint.collect { endpoint ->
                    _discoveredPairingEndpoint.value = endpoint
                    if (notificationPairingActive.get()) {
                        WirelessPairingReceiver.showPairingNotification(ctx, endpoint)
                    }
                }
            }
            ioScope.launch {
                disc.connectEndpoint.collect { endpoint ->
                    _discoveredConnectEndpoint.value = endpoint
                }
            }
        }

        if (listenersRegistered.compareAndSet(false, true)) {
            runCatching {
                Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
                Shizuku.addBinderDeadListener(binderDeadListener)
                Shizuku.addRequestPermissionResultListener(permissionResultListener)
            }
        }

        refreshState()
    }

    fun startWirelessDiscovery() {
        if (discoveryStarted.compareAndSet(false, true)) {
            discovery?.startDiscovery()
        }
    }

    fun stopWirelessDiscovery(force: Boolean = false) {
        // Keep mDNS discovery active in background while the user is completing Notification Pairing
        if (!force && notificationPairingActive.get()) return
        if (discoveryStarted.compareAndSet(true, false)) {
            discovery?.stopDiscovery()
        }
    }

    fun startNotificationPairing(context: Context) {
        val ctx = context.applicationContext
        init(ctx)
        notificationPairingActive.set(true)
        startWirelessDiscovery()
        WirelessPairingReceiver.showPairingNotification(ctx, _discoveredPairingEndpoint.value)
        _builtInMessage.value = "Pairing notification active — open Wireless debugging -> Pair device with pairing code and enter the 6-digit code in the notification."
    }

    fun cancelNotificationPairing(context: Context) {
        notificationPairingActive.set(false)
        WirelessPairingReceiver.dismiss(context.applicationContext)
    }

    fun pairFromNotification(context: Context, pairingCode: String, onFinished: () -> Unit) {
        val ctx = context.applicationContext
        ioScope.launch {
            try {
                // Briefly wait if mDNS is still resolving the pairing service
                var endpoint = _discoveredPairingEndpoint.value
                if (endpoint == null) {
                    repeat(10) {
                        delay(200L)
                        endpoint = _discoveredPairingEndpoint.value
                        if (endpoint != null) return@repeat
                    }
                }

                if (endpoint == null) {
                    val msg = "Pairing service not detected. Keep 'Pair device with pairing code' dialog open and retry."
                    _builtInState.value = BuiltInEngineState.ERROR
                    _builtInMessage.value = msg
                    WirelessPairingReceiver.showPairingNotification(ctx, null, statusOverride = msg)
                    return@launch
                }

                _builtInState.value = BuiltInEngineState.PAIRING
                _builtInMessage.value = "Pairing with ${endpoint.host}:${endpoint.port}…"

                val result = runCatching {
                    val paired = BuiltInAdbManager.getInstance(ctx).pairWireless(
                        port = endpoint.port,
                        pairingCode = pairingCode,
                        host = endpoint.host
                    )
                    check(paired) { "Pairing code rejected — check the 6-digit code and retry." }
                }

                result.onSuccess {
                    notificationPairingActive.set(false)
                    prefs?.isWirelessPaired = true
                    _builtInState.value = BuiltInEngineState.PAIRED_IDLE
                    val msg = "PadForge is now paired! Return to PadForge and tap Connect."
                    _builtInMessage.value = msg
                    WirelessPairingReceiver.showPairingResultNotification(ctx, true, msg)
                }.onFailure { err ->
                    val msg = err.message ?: "Pairing failed. Re-open pairing code dialog and try again."
                    _builtInState.value = BuiltInEngineState.ERROR
                    _builtInMessage.value = msg
                    WirelessPairingReceiver.showPairingNotification(ctx, endpoint, statusOverride = msg)
                }
            } finally {
                onFinished()
            }
        }
    }

    fun refreshState() {
        if (builtInClient.isConnected) {
            _builtInState.value = BuiltInEngineState.CONNECTED
        } else if (_builtInState.value != BuiltInEngineState.PAIRING &&
            _builtInState.value != BuiltInEngineState.CONNECTING
        ) {
            val paired = prefs?.isWirelessPaired == true
            _builtInState.value = if (paired) {
                BuiltInEngineState.PAIRED_IDLE
            } else {
                BuiltInEngineState.NOT_PAIRED
            }
        }
        refreshExternalShizukuState()
    }

    /**
     * Pairs PadForge's built-in ADB engine with Android 11+ Wireless Debugging using port & 6-digit code.
     */
    fun pairWirelessDebugging(port: Int, pairingCode: String) {
        val ctx = appContext ?: return
        val host = _discoveredPairingEndpoint.value?.host
            ?: _discoveredConnectEndpoint.value?.host
        _builtInState.value = BuiltInEngineState.PAIRING
        _builtInMessage.value = "Pairing with Wireless Debugging on port $port…"

        ioScope.launch {
            val result = runCatching {
                val manager = BuiltInAdbManager.getInstance(ctx)
                val paired = manager.pairWireless(port, pairingCode, host)
                check(paired) { "Pairing rejected — keep Android's pairing code dialog open (use Pair via Notification or Split-Screen)." }
            }

            result.onSuccess {
                notificationPairingActive.set(false)
                WirelessPairingReceiver.dismiss(ctx)
                prefs?.isWirelessPaired = true
                _builtInState.value = BuiltInEngineState.PAIRED_IDLE
                _builtInMessage.value = "Wireless Debugging paired! Tap Connect to start the built-in engine."
            }.onFailure { err ->
                _builtInState.value = BuiltInEngineState.ERROR
                val raw = err.message.orEmpty()
                _builtInMessage.value = if (raw.contains("closed", ignoreCase = true) ||
                    raw.contains("refused", ignoreCase = true)
                ) {
                    "Pairing port closed — use 'Pair via Notification' so Android's pairing code dialog stays open while typing the code."
                } else {
                    raw.ifBlank { "Pairing failed. Keep Wireless Debugging pairing dialog open." }
                }
                Log.w(TAG, "Wireless pairing failed: ${err.message}")
            }
        }
    }

    /**
     * Connects to local Wireless Debugging (using discovered host/port or manual port),
     * launches the built-in PadForgeInputServer inside our APK under UID 2000, and establishes
     * an authenticated loopback binary socket for zero-lag hardware gamepad injection.
     */
    fun connectWirelessAndStartEngine(manualConnectPort: Int? = null) {
        val ctx = appContext ?: return
        val endpoint = _discoveredConnectEndpoint.value
        val targetPort = manualConnectPort ?: endpoint?.port
        val targetHost = endpoint?.host

        _builtInState.value = BuiltInEngineState.CONNECTING
        _builtInMessage.value = if (targetPort != null) {
            "Connecting to Wireless Debugging (${targetHost ?: "127.0.0.1"}:$targetPort)…"
        } else {
            "Auto-discovering Wireless Debugging service…"
        }

        ioScope.launch {
            val sessionToken = BuiltInAdbManager.generateSessionToken()
            val daemonPort = PadForgeInputServer.DEFAULT_PORT

            val launchResult = BuiltInAdbManager.getInstance(ctx).launchBuiltInDaemon(
                context = ctx,
                connectHost = targetHost,
                connectPort = targetPort,
                daemonPort = daemonPort,
                sessionToken = sessionToken
            )

            if (launchResult.isFailure) {
                _builtInState.value = BuiltInEngineState.ERROR
                _builtInMessage.value = launchResult.exceptionOrNull()?.message
                    ?: "Wireless Debugging connection failed. Check Wireless Debugging is ON."
                return@launch
            }

            _builtInMessage.value = "Booting built-in input daemon…"

            // Poll loopback socket while app_process initializes PadForgeInputServer
            var connected = false
            repeat(20) {
                delay(200L)
                if (builtInClient.connect(daemonPort, sessionToken, timeoutMs = 1_200)) {
                    connected = true
                    return@repeat
                }
            }

            if (connected) {
                prefs?.isWirelessPaired = true
                _builtInState.value = BuiltInEngineState.CONNECTED
                _builtInMessage.value = "Built-in Engine Active • UID 2000 ASYNC Injection Ready"
            } else {
                _builtInState.value = BuiltInEngineState.ERROR
                _builtInMessage.value = "Connected via Wireless ADB, but built-in daemon did not respond. Retry Connect."
            }
        }
    }

    fun disconnectBuiltInEngine() {
        ioScope.launch {
            builtInClient.requestShutdown()
            val paired = prefs?.isWirelessPaired == true
            _builtInState.value = if (paired) {
                BuiltInEngineState.PAIRED_IDLE
            } else {
                BuiltInEngineState.NOT_PAIRED
            }
            _builtInMessage.value = "Built-in Engine stopped"
        }
    }

    fun requestPermission() {
        if (!Shizuku.pingBinder()) {
            refreshExternalShizukuState()
            return
        }
        runCatching {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                bindUserService()
            } else {
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
            }
        }.onFailure {
            _status.value = ShizukuStatus.ERROR
        }
    }

    fun bindUserService() {
        if (!Shizuku.pingBinder()) {
            refreshExternalShizukuState()
            return
        }
        if (inputService != null && isBound.get()) {
            _status.value = ShizukuStatus.CONNECTED
            return
        }
        _status.value = ShizukuStatus.BINDING
        runCatching {
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
        }.onFailure {
            _status.value = ShizukuStatus.ERROR
        }
    }

    fun unbindUserService() {
        if (isBound.compareAndSet(true, false)) {
            runCatching {
                Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
            }
        }
        inputService = null
    }

    fun setOverlayRunning(running: Boolean) {
        _isOverlayRunning.value = running
        if (!running) {
            _isEditMode.value = false
        }
    }

    fun setEditMode(editMode: Boolean) {
        _isEditMode.value = editMode
    }

    fun setActiveProfile(profile: ControllerProfile) {
        _activeProfile.value = profile
    }

    fun setOpacitySync(opacity: Float) {
        _opacitySync.value = opacity
    }

    fun notifyButtonConfigsChanged() {
        prefs?.let { p ->
            _activeProfile.value = com.example.ButtonConfigStore.getActiveProfile(p.sharedPrefs)
            _opacitySync.value = p.opacity
        }
        _buttonConfigsVersion.update { it + 1L }
    }

    fun sendBoundKeys(keys: List<BoundKey>, action: Int, label: String) {
        if (keys.isEmpty()) return
        val count = packetCounter.incrementAndGet()
        val actionLabel = if (action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"
        val suffix = if (keys.size > 1) " x${keys.size}" else ""
        _telemetry.update {
            it.copy(
                lastSignal = "$label$suffix [$actionLabel]",
                injectedPackets = count
            )
        }

        val gamepadCodes = keys.filter { it.inputType == KeyInputType.GAMEPAD }.map { it.keyCode }
        val keyboardCodes = keys.filter { it.inputType == KeyInputType.KEYBOARD }.map { it.keyCode }
        val mouseButtons = keys.filter { it.inputType == KeyInputType.MOUSE }.map { it.keyCode }
        val hatCodes = keys.filter { it.inputType == KeyInputType.HAT_AXIS }.map { it.keyCode }

        dispatchHandler.post {
            // 1. Gamepad keys (simultaneous combo + analog triggers for L2/R2)
            if (gamepadCodes.isNotEmpty()) {
                val arr = gamepadCodes.toIntArray()
                val triggerVal = if (action == KeyEvent.ACTION_DOWN) 1.0f else 0.0f
                val hasL2 = gamepadCodes.contains(KeyEvent.KEYCODE_BUTTON_L2)
                val hasR2 = gamepadCodes.contains(KeyEvent.KEYCODE_BUTTON_R2)
                if (builtInClient.isConnected) {
                    var ok = builtInClient.sendMultiKeyEvent(arr, action)
                    if (ok && hasL2) ok = builtInClient.sendTrigger(MotionEvent.AXIS_LTRIGGER, triggerVal)
                    if (ok && hasR2) ok = builtInClient.sendTrigger(MotionEvent.AXIS_RTRIGGER, triggerVal)
                    if (!ok) handleBuiltInDrop()
                } else {
                    runCatching {
                        inputService?.sendMultiKeyEvent(arr, action)
                        if (hasL2) inputService?.sendTrigger(triggerVal, MotionEvent.AXIS_LTRIGGER)
                        if (hasR2) inputService?.sendTrigger(triggerVal, MotionEvent.AXIS_RTRIGGER)
                    }
                }
            }

            // 2. Full PC Keyboard keys (SOURCE_KEYBOARD)
            for (kbCode in keyboardCodes) {
                if (builtInClient.isConnected) {
                    if (!builtInClient.sendKeyboardEvent(kbCode, action)) {
                        handleBuiltInDrop()
                        break
                    }
                } else {
                    runCatching { inputService?.sendKeyboardEvent(kbCode, action) }
                }
            }

            // 3. Mouse buttons (SOURCE_MOUSE)
            for (btn in mouseButtons) {
                if (builtInClient.isConnected) {
                    if (!builtInClient.sendMouseButton(btn, action)) {
                        handleBuiltInDrop()
                        break
                    }
                } else {
                    runCatching { inputService?.sendMouseButton(btn, action) }
                }
            }

            // 4. Hat Switch axes (AXIS_HAT_X / AXIS_HAT_Y)
            if (hatCodes.isNotEmpty()) {
                val (hatX, hatY) = synchronized(hatLock) {
                    val delta = if (action == KeyEvent.ACTION_DOWN) 1 else -1
                    for (code in hatCodes) {
                        when (code) {
                            KeyCatalog.HAT_UP -> hatUpCount = (hatUpCount + delta).coerceAtLeast(0)
                            KeyCatalog.HAT_DOWN -> hatDownCount = (hatDownCount + delta).coerceAtLeast(0)
                            KeyCatalog.HAT_LEFT -> hatLeftCount = (hatLeftCount + delta).coerceAtLeast(0)
                            KeyCatalog.HAT_RIGHT -> hatRightCount = (hatRightCount + delta).coerceAtLeast(0)
                        }
                    }
                    val hx = when {
                        hatLeftCount > 0 && hatRightCount == 0 -> -1.0f
                        hatRightCount > 0 && hatLeftCount == 0 -> 1.0f
                        else -> 0.0f
                    }
                    val hy = when {
                        hatUpCount > 0 && hatDownCount == 0 -> -1.0f
                        hatDownCount > 0 && hatUpCount == 0 -> 1.0f
                        else -> 0.0f
                    }
                    hx to hy
                }
                if (builtInClient.isConnected) {
                    if (!builtInClient.sendJoystick(MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y, hatX, hatY)) {
                        handleBuiltInDrop()
                    }
                } else {
                    runCatching {
                        inputService?.sendJoystick(hatX, hatY, MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y)
                    }
                }
            }
        }
    }

    fun sendKeyEvent(keyCode: Int, action: Int, label: String) {
        val count = packetCounter.incrementAndGet()
        val actionLabel = if (action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"
        _telemetry.update {
            it.copy(
                lastSignal = "$label [$actionLabel]",
                injectedPackets = count
            )
        }
        dispatchHandler.post {
            if (builtInClient.isConnected) {
                val sent = builtInClient.sendKeyEvent(keyCode, action)
                if (!sent) handleBuiltInDrop()
            } else {
                runCatching { inputService?.sendKeyEvent(keyCode, action) }
            }
        }
    }

    fun sendMultiKeyEvent(keyCodes: IntArray, action: Int, label: String) {
        sendBoundKeys(keyCodes.map { BoundKey(it, KeyInputType.GAMEPAD) }, action, label)
    }

    fun sendDpadHat(hatX: Float, hatY: Float, label: String) {
        val count = packetCounter.incrementAndGet()
        _telemetry.update {
            it.copy(
                lastSignal = "HAT ($label)",
                injectedPackets = count
            )
        }
        dispatchHandler.post {
            if (builtInClient.isConnected) {
                val sent = builtInClient.sendJoystick(
                    MotionEvent.AXIS_HAT_X,
                    MotionEvent.AXIS_HAT_Y,
                    hatX,
                    hatY
                )
                if (!sent) handleBuiltInDrop()
            } else {
                runCatching {
                    inputService?.sendJoystick(hatX, hatY, MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y)
                }
            }
        }
    }

    fun sendJoystick(x: Float, y: Float, axisX: Int, axisY: Int) {
        val count = packetCounter.incrementAndGet()
        val isLeft = axisX == MotionEvent.AXIS_X
        _telemetry.update {
            if (isLeft) {
                it.copy(
                    leftX = x,
                    leftY = y,
                    lastSignal = "L_STICK (%.2f, %.2f)".format(x, y),
                    injectedPackets = count
                )
            } else {
                it.copy(
                    rightX = x,
                    rightY = y,
                    lastSignal = "R_STICK (%.2f, %.2f)".format(x, y),
                    injectedPackets = count
                )
            }
        }
        dispatchHandler.post {
            if (builtInClient.isConnected) {
                val sent = builtInClient.sendJoystick(axisX, axisY, x, y)
                if (!sent) handleBuiltInDrop()
            } else {
                runCatching { inputService?.sendJoystick(x, y, axisX, axisY) }
            }
        }
    }

    fun sendTrigger(value: Float, axis: Int, keyCode: Int, isPressed: Boolean, label: String) {
        val count = packetCounter.incrementAndGet()
        val action = if (isPressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        _telemetry.update {
            it.copy(
                lastSignal = "$label [%.0f%%]".format(value * 100f),
                injectedPackets = count
            )
        }
        dispatchHandler.post {
            if (builtInClient.isConnected) {
                val ok1 = builtInClient.sendTrigger(axis, value)
                val ok2 = builtInClient.sendKeyEvent(keyCode, action)
                if (!ok1 || !ok2) handleBuiltInDrop()
            } else {
                runCatching {
                    inputService?.sendTrigger(value, axis)
                    inputService?.sendKeyEvent(keyCode, action)
                }
            }
        }
    }

    private fun handleBuiltInDrop() {
        val paired = prefs?.isWirelessPaired == true
        _builtInState.value = if (paired) BuiltInEngineState.PAIRED_IDLE else BuiltInEngineState.NOT_PAIRED
        _builtInMessage.value = "Built-in daemon connection closed. Tap Connect to restart."
    }

    private fun refreshExternalShizukuState() {
        val ctx = appContext
        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!binderAlive) {
            val installed = ctx?.let { isShizukuInstalled(it) } ?: false
            _status.value = if (installed) ShizukuStatus.NOT_RUNNING else ShizukuStatus.NOT_INSTALLED
            return
        }
        if (Shizuku.isPreV11()) {
            _status.value = ShizukuStatus.ERROR
            return
        }
        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!granted) {
            _status.value = ShizukuStatus.PERMISSION_REQUIRED
            return
        }
        if (inputService != null && isBound.get()) {
            _status.value = ShizukuStatus.CONNECTED
        } else {
            bindUserService()
        }
    }

    private fun isShizukuInstalled(context: Context): Boolean {
        return runCatching {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        }.getOrDefault(false)
    }
}
