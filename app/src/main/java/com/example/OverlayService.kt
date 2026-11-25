package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.data.ControlId
import com.example.data.ControllerPreferences
import com.example.haptics.HapticFeedbackEngine
import com.example.haptics.HapticProfile
import com.example.input.ShizukuInputController
import com.example.overlay.DpadView
import com.example.overlay.GamepadButtonView
import com.example.overlay.OverlayButtonConfigDialog
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Foreground Service managing all floating virtual gamepad windows plus a self-contained
 * **In-Overlay Floating Control Bar** so users can customize everything directly over their game
 * without ever leaving to MainActivity:
 * - Hide / Show controller buttons & sticks
 * - Toggle Customize / Edit Mode (drag, resize, tap any button for Universal Key Picker)
 * - Add Extra Custom Button (+) directly on screen
 * - Switch Profile (Xbox / PS4 / PC) live
 * - Adjust Opacity right inside the overlay toolbar
 */
class OverlayService : Service() {

    private data class FloatingStickOrDpad(
        val id: ControlId,
        val view: View,
        val params: WindowManager.LayoutParams,
        val baseWidthDp: Float,
        val baseHeightDp: Float
    )

    private data class FloatingButton(
        var config: ButtonConfig,
        val view: GamepadButtonView,
        val params: WindowManager.LayoutParams,
        val baseWidthDp: Float,
        val baseHeightDp: Float
    )

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: ControllerPreferences
    private lateinit var hapticEngine: HapticFeedbackEngine

    private val analogControls = LinkedHashMap<ControlId, FloatingStickOrDpad>()
    private val buttonControls = LinkedHashMap<String, FloatingButton>()
    private val activePressedKeys = HashMap<String, List<BoundKey>>()

    // In-Overlay Floating Control Orb + Expandable Mini Toolbar
    private var hubOrbView: TextView? = null
    private var hubOrbParams: WindowManager.LayoutParams? = null
    private var miniToolbarView: View? = null
    private var toolbarHideBtn: TextView? = null
    private var toolbarEditBtn: TextView? = null
    private var toolbarProfileBtn: TextView? = null
    private var toolbarOpacityLabel: TextView? = null
    private var toolbarOpacitySeek: SeekBar? = null
    private var toolbarEditHint: TextView? = null

    private var activeConfigDialog: OverlayButtonConfigDialog? = null
    private var activeProfile: ControllerProfile = ControllerProfile.XBOX
    private var isToolbarExpanded: Boolean = false
    private var areControlsHidden: Boolean = false
    private var isEditMode: Boolean = false
    private var isOverlayAttached: Boolean = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = ControllerPreferences(this)
        activeProfile = ButtonConfigStore.getActiveProfile(prefs.sharedPrefs)
        hapticEngine = HapticFeedbackEngine(this).apply {
            isEnabled = prefs.hapticsEnabled
            intensity = prefs.hapticIntensity
        }
        ShizukuInputController.init(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_TOGGLE_EDIT_MODE -> {
                setEditMode(!isEditMode)
                return START_STICKY
            }

            ACTION_SET_EDIT_MODE -> {
                val enabled = intent.getBooleanExtra(EXTRA_EDIT_MODE, false)
                setEditMode(enabled)
                return START_STICKY
            }

            ACTION_UPDATE_SETTINGS -> {
                applyVisualSettings()
                return START_STICKY
            }

            ACTION_ADD_EXTRA_BUTTON -> {
                if (isOverlayAttached) {
                    spawnNewExtraButton()
                }
                return START_STICKY
            }

            ACTION_RELOAD_CONFIGS -> {
                if (isOverlayAttached) {
                    activeProfile = ButtonConfigStore.getActiveProfile(prefs.sharedPrefs)
                    reloadButtonsFromStore()
                    refreshMiniToolbarLabels()
                }
                return START_STICKY
            }

            ACTION_RESET_LAYOUT -> {
                resetAllLayouts()
                return START_STICKY
            }

            ACTION_START, null -> {
                if (!Settings.canDrawOverlays(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundWithNotification()
                if (!isOverlayAttached) {
                    attachAllControls()
                    attachFloatingHubOrb()
                    isOverlayAttached = true
                    ShizukuInputController.setOverlayRunning(true)
                } else {
                    applyVisualSettings()
                }
                if (intent?.hasExtra(EXTRA_EDIT_MODE) == true) {
                    setEditMode(intent.getBooleanExtra(EXTRA_EDIT_MODE, false))
                }
                return START_STICKY
            }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isOverlayAttached) {
            repositionAllControlsForScreen()
        }
    }

    override fun onDestroy() {
        detachAllControls()
        ShizukuInputController.setOverlayRunning(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun attachAllControls() {
        val (screenW, screenH) = currentScreenSize()
        val scale = prefs.scale
        val density = resources.displayMetrics.density
        activeProfile = ButtonConfigStore.getActiveProfile(prefs.sharedPrefs)

        // Dual Analog Thumbsticks (AXIS_X/AXIS_Y and AXIS_Z/AXIS_RZ)
        addJoystickControl(
            id = ControlId.LEFT_STICK,
            label = "L",
            accentHex = "#00E5C4",
            axisX = MotionEvent.AXIS_X,
            axisY = MotionEvent.AXIS_Y,
            baseSizeDp = 128f,
            screenW = screenW,
            screenH = screenH,
            scale = scale
        )
        addJoystickControl(
            id = ControlId.RIGHT_STICK,
            label = "R",
            accentHex = "#3B9EFF",
            axisX = MotionEvent.AXIS_Z,
            axisY = MotionEvent.AXIS_RZ,
            baseSizeDp = 128f,
            screenW = screenW,
            screenH = screenH,
            scale = scale
        )

        // 8-Way Hat Switch D-Pad (AXIS_HAT_X / AXIS_HAT_Y)
        addDpadControl(
            baseSizeDp = 124f,
            screenW = screenW,
            screenH = screenH,
            scale = scale
        )

        val configs = ButtonConfigStore.loadConfigs(
            prefs = prefs.sharedPrefs,
            controllerPrefs = prefs,
            screenW = screenW,
            screenH = screenH,
            density = density,
            scale = scale,
            profile = activeProfile
        )
        configs.forEach { cfg ->
            addConfiguredButton(cfg, screenW, screenH, scale)
        }
        ShizukuInputController.notifyButtonConfigsChanged()
        applyVisualSettings()
    }

    /**
     * Compact draggable floating orb pinned on screen; tapping opens the In-Overlay Mini Toolbar.
     */
    private fun attachFloatingHubOrb() {
        if (hubOrbView != null) return
        val orbSize = dpToPx(42f)
        val (screenW, _) = currentScreenSize()
        val startX = (screenW - orbSize) / 2
        val startY = dpToPx(14f)

        val params = createOverlayParams(orbSize, orbSize, startX, startY)

        var downRawX = 0f
        var downRawY = 0f
        var lastRawX = 0f
        var lastRawY = 0f
        var isDragging = false
        val slopPx = dpToPx(7f).toFloat()

        val orb = TextView(this).apply {
            text = "⚙"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#00E5C4"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(225, 13, 19, 31))
                setStroke(dpToPx(1.8f), Color.parseColor("#00E5C4"))
            }
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        lastRawX = event.rawX
                        lastRawY = event.rawY
                        isDragging = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!isDragging &&
                            hypot((event.rawX - downRawX).toDouble(), (event.rawY - downRawY).toDouble()) >= slopPx
                        ) {
                            isDragging = true
                        }
                        if (isDragging) {
                            val dx = (event.rawX - lastRawX).toInt()
                            val dy = (event.rawY - lastRawY).toInt()
                            if (dx != 0 || dy != 0) {
                                lastRawX += dx
                                lastRawY += dy
                                val (sw, sh) = currentScreenSize()
                                params.x = (params.x + dx).coerceIn(0, (sw - orbSize).coerceAtLeast(0))
                                params.y = (params.y + dy).coerceIn(0, (sh - orbSize).coerceAtLeast(0))
                                runCatching { windowManager.updateViewLayout(this, params) }
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) {
                            setMiniToolbarExpanded(!isToolbarExpanded)
                        }
                        true
                    }
                    else -> true
                }
            }
        }

        hubOrbView = orb
        hubOrbParams = params
        runCatching { windowManager.addView(orb, params) }
    }

    private fun setMiniToolbarExpanded(expanded: Boolean) {
        isToolbarExpanded = expanded
        updateOrbStyle()
        if (expanded) {
            showMiniToolbar()
        } else {
            hideMiniToolbar()
        }
    }

    private fun updateOrbStyle() {
        val orb = hubOrbView ?: return
        val accentHex = when {
            isEditMode -> "#FF8A2B"
            areControlsHidden -> "#94A8C8"
            else -> "#00E5C4"
        }
        orb.setTextColor(Color.parseColor(accentHex))
        orb.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(230, 13, 19, 31))
            setStroke(dpToPx(1.8f), Color.parseColor(accentHex))
        }
    }

    private fun showMiniToolbar() {
        if (miniToolbarView != null) {
            refreshMiniToolbarLabels()
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpToPx(10f), dpToPx(8f), dpToPx(10f), dpToPx(8f))
            background = GradientDrawable().apply {
                setColor(Color.argb(242, 13, 19, 31))
                setStroke(dpToPx(1.4f), Color.parseColor(if (isEditMode) "#FF8A2B" else "#00E5C4"))
                cornerRadius = dpToPx(18f).toFloat()
            }
        }

        fun makeActionChip(label: String, bgHex: String, fgHex: String, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = label
                setTextColor(Color.parseColor(fgHex))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                paint.isFakeBoldText = true
                setPadding(dpToPx(10f), dpToPx(7f), dpToPx(10f), dpToPx(7f))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(bgHex))
                    cornerRadius = dpToPx(12f).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dpToPx(3f)
                    marginEnd = dpToPx(3f)
                }
                setOnClickListener { onClick() }
            }
        }

        // Row 1: Quick Action Buttons (Hide/Show | Edit | + Add | Profile | Close)
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val hideBtn = makeActionChip("Hide", "#1F293B", "#EEF5FF") {
            setControlsHidden(!areControlsHidden)
        }
        val editBtn = makeActionChip("Edit", "#1F293B", "#FF8A2B") {
            setEditMode(!isEditMode)
        }
        val addBtn = makeActionChip("+ Add", "#00E5C4", "#0B0E14") {
            if (areControlsHidden) setControlsHidden(false)
            if (!isEditMode) setEditMode(true)
            spawnNewExtraButton()
        }
        val profileBtn = makeActionChip(activeProfile.displayName, "#253449", "#3B9EFF") {
            switchToNextProfile()
        }
        val collapseBtn = makeActionChip("✕", "#1F293B", "#94A8C8") {
            setMiniToolbarExpanded(false)
        }

        actionsRow.addView(hideBtn)
        actionsRow.addView(editBtn)
        actionsRow.addView(addBtn)
        actionsRow.addView(profileBtn)
        actionsRow.addView(collapseBtn)
        container.addView(actionsRow)

        // Row 2: Live In-Overlay Opacity Slider
        val opacityRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(6f)
            }
        }
        val opacityLabel = TextView(this).apply {
            text = "Opacity ${(prefs.opacity * 100f).roundToInt()}%"
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            paint.isFakeBoldText = true
            minWidth = dpToPx(74f)
        }
        val opacitySeek = SeekBar(this).apply {
            max = 85 // 15% .. 100%
            progress = ((prefs.opacity - ControllerPreferences.MIN_OPACITY) * 100f)
                .roundToInt()
                .coerceIn(0, 85)
            progressTintList = ColorStateList.valueOf(Color.parseColor("#00E5C4"))
            thumbTintList = ColorStateList.valueOf(Color.parseColor("#00E5C4"))
            layoutParams = LinearLayout.LayoutParams(dpToPx(170f), LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val newOpacity = (ControllerPreferences.MIN_OPACITY + progress / 100f)
                        .coerceIn(ControllerPreferences.MIN_OPACITY, ControllerPreferences.MAX_OPACITY)
                    prefs.opacity = newOpacity
                    opacityLabel.text = "Opacity ${(newOpacity * 100f).roundToInt()}%"
                    applyVisualSettings()
                    ShizukuInputController.notifyButtonConfigsChanged()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        opacityRow.addView(opacityLabel)
        opacityRow.addView(opacitySeek)
        container.addView(opacityRow)

        // Row 3: Contextual Edit Mode Hint
        val hintView = TextView(this).apply {
            text = getString(R.string.overlay_edit_hint)
            setTextColor(Color.parseColor("#FF8A2B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(4f) }
        }
        container.addView(hintView)

        toolbarHideBtn = hideBtn
        toolbarEditBtn = editBtn
        toolbarProfileBtn = profileBtn
        toolbarOpacityLabel = opacityLabel
        toolbarOpacitySeek = opacitySeek
        toolbarEditHint = hintView
        refreshMiniToolbarLabels()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dpToPx(62f)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        miniToolbarView = container
        runCatching { windowManager.addView(container, params) }
    }

    private fun hideMiniToolbar() {
        miniToolbarView?.let { v ->
            runCatching { windowManager.removeView(v) }
        }
        miniToolbarView = null
        toolbarHideBtn = null
        toolbarEditBtn = null
        toolbarProfileBtn = null
        toolbarOpacityLabel = null
        toolbarOpacitySeek = null
        toolbarEditHint = null
    }

    private fun refreshMiniToolbarLabels() {
        toolbarHideBtn?.apply {
            text = if (areControlsHidden) "Show" else "Hide"
            background = GradientDrawable().apply {
                setColor(Color.parseColor(if (areControlsHidden) "#3B9EFF" else "#1F293B"))
                cornerRadius = dpToPx(12f).toFloat()
            }
            setTextColor(Color.parseColor(if (areControlsHidden) "#0B0E14" else "#EEF5FF"))
        }
        toolbarEditBtn?.apply {
            text = if (isEditMode) "Done" else "Edit"
            background = GradientDrawable().apply {
                setColor(Color.parseColor(if (isEditMode) "#FF8A2B" else "#1F293B"))
                cornerRadius = dpToPx(12f).toFloat()
            }
            setTextColor(Color.parseColor(if (isEditMode) "#0B0E14" else "#FF8A2B"))
        }
        toolbarProfileBtn?.text = activeProfile.displayName
        toolbarOpacityLabel?.text = "Opacity ${(prefs.opacity * 100f).roundToInt()}%"
        toolbarOpacitySeek?.progress = ((prefs.opacity - ControllerPreferences.MIN_OPACITY) * 100f)
            .roundToInt()
            .coerceIn(0, 85)
        toolbarEditHint?.visibility = if (isEditMode) View.VISIBLE else View.GONE
        miniToolbarView?.background = GradientDrawable().apply {
            setColor(Color.argb(242, 13, 19, 31))
            setStroke(dpToPx(1.4f), Color.parseColor(if (isEditMode) "#FF8A2B" else "#00E5C4"))
            cornerRadius = dpToPx(18f).toFloat()
        }
        updateOrbStyle()
    }

    private fun setControlsHidden(hidden: Boolean) {
        areControlsHidden = hidden
        val vis = if (hidden) View.GONE else View.VISIBLE
        if (hidden) {
            releaseAllActiveInputs()
        }
        analogControls.values.forEach { it.view.visibility = vis }
        buttonControls.values.forEach { it.view.visibility = vis }
        refreshMiniToolbarLabels()
    }

    private fun switchToNextProfile() {
        releaseAllActiveInputs()
        activeProfile = activeProfile.next()
        ButtonConfigStore.setActiveProfile(prefs.sharedPrefs, activeProfile)
        reloadButtonsFromStore()
        ShizukuInputController.notifyButtonConfigsChanged()
        refreshMiniToolbarLabels()
    }

    private fun addJoystickControl(
        id: ControlId,
        label: String,
        accentHex: String,
        axisX: Int,
        axisY: Int,
        baseSizeDp: Float,
        screenW: Int,
        screenH: Int,
        scale: Float
    ) {
        val widthPx = dpToPx(baseSizeDp * scale)
        val heightPx = dpToPx(baseSizeDp * scale)
        val pos = prefs.getPosition(id, screenW, screenH, widthPx, heightPx)
        val params = createOverlayParams(widthPx, heightPx, pos.x, pos.y)

        val joystick = JoystickView(this).apply {
            this.label = label
            this.accentColor = Color.parseColor(accentHex)
            this.hapticsEnabled = prefs.hapticsEnabled
            this.editMode = isEditMode
            this.alpha = prefs.opacity
            this.visibility = if (areControlsHidden) View.GONE else View.VISIBLE
            onJoystickMove = { x, y ->
                ShizukuInputController.sendJoystick(x, y, axisX, axisY)
            }
            onHapticFeedback = {
                hapticEngine.trigger(HapticProfile.STICK_ENGAGE)
            }
            onDragDelta = { dx, dy ->
                moveAnalogControlWindow(id, dx, dy)
            }
            onDragFinished = {
                persistAnalogPosition(id)
            }
        }

        windowManager.addView(joystick, params)
        analogControls[id] = FloatingStickOrDpad(id, joystick, params, baseSizeDp, baseSizeDp)
    }

    private fun addDpadControl(
        baseSizeDp: Float,
        screenW: Int,
        screenH: Int,
        scale: Float
    ) {
        val widthPx = dpToPx(baseSizeDp * scale)
        val heightPx = dpToPx(baseSizeDp * scale)
        val pos = prefs.getPosition(ControlId.DPAD, screenW, screenH, widthPx, heightPx)
        val params = createOverlayParams(widthPx, heightPx, pos.x, pos.y)

        val dpad = DpadView(this).apply {
            this.hapticsEnabled = prefs.hapticsEnabled
            this.editMode = isEditMode
            this.alpha = prefs.opacity
            this.visibility = if (areControlsHidden) View.GONE else View.VISIBLE
            onHatChanged = { hatX, hatY, dirLabel ->
                ShizukuInputController.sendDpadHat(hatX, hatY, dirLabel)
            }
            onHapticFeedback = {
                hapticEngine.trigger(HapticProfile.DPAD_CLICK)
            }
            onDragDelta = { dx, dy ->
                moveAnalogControlWindow(ControlId.DPAD, dx, dy)
            }
            onDragFinished = {
                persistAnalogPosition(ControlId.DPAD)
            }
        }

        windowManager.addView(dpad, params)
        analogControls[ControlId.DPAD] = FloatingStickOrDpad(ControlId.DPAD, dpad, params, baseSizeDp, baseSizeDp)
    }

    private fun addConfiguredButton(
        config: ButtonConfig,
        screenW: Int,
        screenH: Int,
        scale: Float
    ) {
        val spec = ButtonConfigStore.visualSpecFor(config)
        val effectiveScale = scale * config.sizeScale
        val widthPx = dpToPx(spec.baseWidthDp * effectiveScale)
        val heightPx = dpToPx(spec.baseHeightDp * effectiveScale)
        val clampedX = config.x.coerceIn(0, (screenW - widthPx).coerceAtLeast(0))
        val clampedY = config.y.coerceIn(0, (screenH - heightPx).coerceAtLeast(0))
        val clampedConfig = config.copy(x = clampedX, y = clampedY)
        val params = createOverlayParams(widthPx, heightPx, clampedX, clampedY)

        val buttonId = clampedConfig.id
        val buttonView = GamepadButtonView(
            context = this,
            initialLabel = clampedConfig.label,
            accentColor = Color.parseColor(spec.accentHex),
            shape = spec.shape
        ).apply {
            this.comboKeyCount = clampedConfig.boundKeys.size
            this.hapticsEnabled = prefs.hapticsEnabled
            this.editMode = isEditMode
            this.alpha = prefs.opacity
            this.visibility = if (areControlsHidden) View.GONE else View.VISIBLE

            onPressStateChanged = { pressed ->
                handleButtonPressChanged(buttonId, pressed)
            }
            onHapticFeedback = {
                val cfg = buttonControls[buttonId]?.config
                val isTrigger = cfg?.boundKeys?.any {
                    it.inputType == KeyInputType.GAMEPAD &&
                        (it.keyCode == KeyEvent.KEYCODE_BUTTON_L2 || it.keyCode == KeyEvent.KEYCODE_BUTTON_R2)
                } == true
                hapticEngine.trigger(
                    if (isTrigger) HapticProfile.TRIGGER_PULL else HapticProfile.BUTTON_PRESS
                )
            }
            onDragDelta = { dx, dy ->
                moveButtonWindow(buttonId, dx, dy)
            }
            onDragFinished = {
                persistAllButtonConfigs()
            }
            onEditTap = {
                openButtonConfigDialog(buttonId)
            }
        }

        windowManager.addView(buttonView, params)
        buttonControls[buttonId] = FloatingButton(
            config = clampedConfig,
            view = buttonView,
            params = params,
            baseWidthDp = spec.baseWidthDp,
            baseHeightDp = spec.baseHeightDp
        )
    }

    private fun handleButtonPressChanged(buttonId: String, pressed: Boolean) {
        val floating = buttonControls[buttonId] ?: return
        if (pressed) {
            val keys = floating.config.boundKeys
            if (keys.isEmpty()) return
            activePressedKeys[buttonId] = keys
            ShizukuInputController.sendBoundKeys(keys, KeyEvent.ACTION_DOWN, floating.config.label)
        } else {
            val keysToRelease = activePressedKeys.remove(buttonId) ?: floating.config.boundKeys
            if (keysToRelease.isNotEmpty()) {
                ShizukuInputController.sendBoundKeys(keysToRelease, KeyEvent.ACTION_UP, floating.config.label)
            }
        }
    }

    private fun openButtonConfigDialog(buttonId: String) {
        val floating = buttonControls[buttonId] ?: return
        activeConfigDialog?.dismiss()
        activeConfigDialog = OverlayButtonConfigDialog.show(
            context = this,
            windowManager = windowManager,
            config = floating.config,
            profile = activeProfile,
            onPreviewSizeChanged = { previewScale ->
                applySingleButtonSize(buttonId, previewScale)
            },
            onSave = { updated ->
                val current = buttonControls[buttonId] ?: return@show
                current.view.releaseIfPressed()
                val synced = updated.copy(x = current.params.x, y = current.params.y)
                current.config = synced
                current.view.label = synced.label
                current.view.comboKeyCount = synced.boundKeys.size
                applySingleButtonSize(buttonId, synced.sizeScale)
                persistAllButtonConfigs()
                activeConfigDialog = null
            },
            onDelete = { idToDelete ->
                deleteExtraButton(idToDelete)
                activeConfigDialog = null
            },
            onCancel = {
                activeConfigDialog = null
            }
        )
    }

    private fun applySingleButtonSize(buttonId: String, sizeScale: Float) {
        val floating = buttonControls[buttonId] ?: return
        val (screenW, screenH) = currentScreenSize()
        val effectiveScale = prefs.scale * sizeScale.coerceIn(
            ButtonConfigStore.MIN_BUTTON_SIZE_SCALE,
            ButtonConfigStore.MAX_BUTTON_SIZE_SCALE
        )
        val newW = dpToPx(floating.baseWidthDp * effectiveScale)
        val newH = dpToPx(floating.baseHeightDp * effectiveScale)
        floating.params.width = newW
        floating.params.height = newH
        floating.params.x = floating.params.x.coerceIn(0, (screenW - newW).coerceAtLeast(0))
        floating.params.y = floating.params.y.coerceIn(0, (screenH - newH).coerceAtLeast(0))
        runCatching { windowManager.updateViewLayout(floating.view, floating.params) }
    }

    private fun spawnNewExtraButton() {
        val (screenW, screenH) = currentScreenSize()
        val scale = prefs.scale
        val density = resources.displayMetrics.density
        val existingConfigs = buttonControls.values.map { it.config }
        val newConfig = ButtonConfigStore.createExtraButton(
            existing = existingConfigs,
            screenW = screenW,
            screenH = screenH,
            density = density,
            scale = scale,
            profile = activeProfile
        )
        addConfiguredButton(newConfig, screenW, screenH, scale)
        persistAllButtonConfigs()
        if (isEditMode) {
            openButtonConfigDialog(newConfig.id)
        }
    }

    private fun deleteExtraButton(buttonId: String) {
        val removed = buttonControls.remove(buttonId) ?: return
        removed.view.releaseIfPressed()
        activePressedKeys.remove(buttonId)?.let { stuckKeys ->
            ShizukuInputController.sendBoundKeys(stuckKeys, KeyEvent.ACTION_UP, removed.config.label)
        }
        runCatching { windowManager.removeView(removed.view) }
        persistAllButtonConfigs()
    }

    private fun reloadButtonsFromStore() {
        activeConfigDialog?.dismiss()
        activeConfigDialog = null

        buttonControls.values.forEach { floating ->
            floating.view.releaseIfPressed()
            runCatching { windowManager.removeView(floating.view) }
        }
        buttonControls.clear()
        activePressedKeys.clear()

        val (screenW, screenH) = currentScreenSize()
        val scale = prefs.scale
        val density = resources.displayMetrics.density
        val configs = ButtonConfigStore.loadConfigs(
            prefs = prefs.sharedPrefs,
            controllerPrefs = prefs,
            screenW = screenW,
            screenH = screenH,
            density = density,
            scale = scale,
            profile = activeProfile
        )
        configs.forEach { cfg ->
            addConfiguredButton(cfg, screenW, screenH, scale)
        }
        applyVisualSettings()
    }

    private fun resetAllLayouts() {
        prefs.resetPositions()
        if (!isOverlayAttached) return

        val (screenW, screenH) = currentScreenSize()
        val scale = prefs.scale
        analogControls.values.forEach { control ->
            val widthPx = dpToPx(control.baseWidthDp * scale)
            val heightPx = dpToPx(control.baseHeightDp * scale)
            val pos = prefs.getPosition(control.id, screenW, screenH, widthPx, heightPx)
            control.params.width = widthPx
            control.params.height = heightPx
            control.params.x = pos.x
            control.params.y = pos.y
            runCatching { windowManager.updateViewLayout(control.view, control.params) }
        }

        reloadButtonsFromStore()
        ShizukuInputController.notifyButtonConfigsChanged()
    }

    private fun persistAllButtonConfigs() {
        val updatedList = buttonControls.values.map { floating ->
            floating.config.copy(x = floating.params.x, y = floating.params.y).also {
                floating.config = it
            }
        }
        ButtonConfigStore.saveConfigs(prefs.sharedPrefs, updatedList, activeProfile)
        ShizukuInputController.notifyButtonConfigsChanged()
    }

    private fun createOverlayParams(
        widthPx: Int,
        heightPx: Int,
        x: Int,
        y: Int
    ): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            widthPx,
            heightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun moveAnalogControlWindow(id: ControlId, dx: Int, dy: Int) {
        val control = analogControls[id] ?: return
        val (screenW, screenH) = currentScreenSize()
        control.params.x = (control.params.x + dx).coerceIn(0, (screenW - control.params.width).coerceAtLeast(0))
        control.params.y = (control.params.y + dy).coerceIn(0, (screenH - control.params.height).coerceAtLeast(0))
        runCatching { windowManager.updateViewLayout(control.view, control.params) }
    }

    private fun persistAnalogPosition(id: ControlId) {
        val control = analogControls[id] ?: return
        prefs.savePosition(id, control.params.x, control.params.y)
    }

    private fun moveButtonWindow(buttonId: String, dx: Int, dy: Int) {
        val floating = buttonControls[buttonId] ?: return
        val (screenW, screenH) = currentScreenSize()
        floating.params.x = (floating.params.x + dx).coerceIn(0, (screenW - floating.params.width).coerceAtLeast(0))
        floating.params.y = (floating.params.y + dy).coerceIn(0, (screenH - floating.params.height).coerceAtLeast(0))
        floating.config = floating.config.copy(x = floating.params.x, y = floating.params.y)
        runCatching { windowManager.updateViewLayout(floating.view, floating.params) }
    }

    private fun setEditMode(enabled: Boolean) {
        isEditMode = enabled
        ShizukuInputController.setEditMode(enabled)
        if (enabled && areControlsHidden) {
            setControlsHidden(false)
        }
        if (!enabled) {
            activeConfigDialog?.dismiss()
            activeConfigDialog = null
        }

        analogControls.values.forEach { control ->
            when (val v = control.view) {
                is JoystickView -> v.editMode = enabled
                is DpadView -> v.editMode = enabled
            }
        }
        buttonControls.values.forEach { floating ->
            floating.view.editMode = enabled
        }

        // Automatically open the In-Overlay Mini Toolbar when entering Edit Mode
        if (enabled && !isToolbarExpanded) {
            setMiniToolbarExpanded(true)
        } else {
            refreshMiniToolbarLabels()
        }

        if (isOverlayAttached) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun applyVisualSettings() {
        val opacity = prefs.opacity
        val scale = prefs.scale
        val haptics = prefs.hapticsEnabled
        hapticEngine.isEnabled = haptics
        hapticEngine.intensity = prefs.hapticIntensity
        val (screenW, screenH) = currentScreenSize()

        analogControls.values.forEach { control ->
            control.view.alpha = opacity
            when (val v = control.view) {
                is JoystickView -> v.hapticsEnabled = haptics
                is DpadView -> v.hapticsEnabled = haptics
            }
            val newW = dpToPx(control.baseWidthDp * scale)
            val newH = dpToPx(control.baseHeightDp * scale)
            if (control.params.width != newW || control.params.height != newH) {
                control.params.width = newW
                control.params.height = newH
                control.params.x = control.params.x.coerceIn(0, (screenW - newW).coerceAtLeast(0))
                control.params.y = control.params.y.coerceIn(0, (screenH - newH).coerceAtLeast(0))
                runCatching { windowManager.updateViewLayout(control.view, control.params) }
            }
        }

        buttonControls.values.forEach { floating ->
            floating.view.alpha = opacity
            floating.view.hapticsEnabled = haptics
            val effectiveScale = scale * floating.config.sizeScale
            val newW = dpToPx(floating.baseWidthDp * effectiveScale)
            val newH = dpToPx(floating.baseHeightDp * effectiveScale)
            if (floating.params.width != newW || floating.params.height != newH) {
                floating.params.width = newW
                floating.params.height = newH
                floating.params.x = floating.params.x.coerceIn(0, (screenW - newW).coerceAtLeast(0))
                floating.params.y = floating.params.y.coerceIn(0, (screenH - newH).coerceAtLeast(0))
                runCatching { windowManager.updateViewLayout(floating.view, floating.params) }
            }
        }

        toolbarOpacityLabel?.text = "Opacity ${(opacity * 100f).roundToInt()}%"
    }

    private fun repositionAllControlsForScreen() {
        val (screenW, screenH) = currentScreenSize()
        val scale = prefs.scale
        analogControls.values.forEach { control ->
            val widthPx = dpToPx(control.baseWidthDp * scale)
            val heightPx = dpToPx(control.baseHeightDp * scale)
            val pos = prefs.getPosition(control.id, screenW, screenH, widthPx, heightPx)
            control.params.width = widthPx
            control.params.height = heightPx
            control.params.x = pos.x
            control.params.y = pos.y
            runCatching { windowManager.updateViewLayout(control.view, control.params) }
        }
        buttonControls.values.forEach { floating ->
            val effectiveScale = scale * floating.config.sizeScale
            val widthPx = dpToPx(floating.baseWidthDp * effectiveScale)
            val heightPx = dpToPx(floating.baseHeightDp * effectiveScale)
            floating.params.width = widthPx
            floating.params.height = heightPx
            floating.params.x = floating.config.x.coerceIn(0, (screenW - widthPx).coerceAtLeast(0))
            floating.params.y = floating.config.y.coerceIn(0, (screenH - heightPx).coerceAtLeast(0))
            runCatching { windowManager.updateViewLayout(floating.view, floating.params) }
        }
        hubOrbParams?.let { params ->
            params.x = params.x.coerceIn(0, (screenW - params.width).coerceAtLeast(0))
            params.y = params.y.coerceIn(0, (screenH - params.height).coerceAtLeast(0))
            hubOrbView?.let { orb -> runCatching { windowManager.updateViewLayout(orb, params) } }
        }
    }

    private fun releaseAllActiveInputs() {
        analogControls.values.forEach { control ->
            when (val v = control.view) {
                is JoystickView -> v.resetStick()
                is DpadView -> v.releaseAllDirections()
            }
        }
        buttonControls.values.forEach { it.view.releaseIfPressed() }
        activePressedKeys.values.forEach { keys ->
            if (keys.isNotEmpty()) {
                ShizukuInputController.sendBoundKeys(keys, KeyEvent.ACTION_UP, "RELEASE")
            }
        }
        activePressedKeys.clear()
    }

    private fun detachAllControls() {
        activeConfigDialog?.dismiss()
        activeConfigDialog = null
        hideMiniToolbar()
        hubOrbView?.let { orb -> runCatching { windowManager.removeView(orb) } }
        hubOrbView = null
        hubOrbParams = null

        releaseAllActiveInputs()
        analogControls.values.forEach { control ->
            runCatching { windowManager.removeView(control.view) }
        }
        analogControls.clear()

        buttonControls.values.forEach { floating ->
            runCatching { windowManager.removeView(floating.view) }
        }
        buttonControls.clear()

        isOverlayAttached = false
        isEditMode = false
        isToolbarExpanded = false
        areControlsHidden = false
    }

    private fun currentScreenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    private fun dpToPx(dp: Float): Int = (dp * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private fun startForegroundWithNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val toggleEditIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayService::class.java).apply { action = ACTION_TOGGLE_EDIT_MODE },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, OverlayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val editLabel = if (isEditMode) {
            getString(R.string.notif_action_lock)
        } else {
            getString(R.string.notif_action_edit)
        }

        val contentText = if (isEditMode) {
            getString(R.string.notif_text_edit)
        } else {
            getString(R.string.notif_text_normal)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(contentText)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openAppIntent)
            .addAction(0, editLabel, toggleEditIntent)
            .addAction(0, getString(R.string.notif_action_stop), stopIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "padforge_overlay_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.action.START_OVERLAY"
        const val ACTION_STOP = "com.example.action.STOP_OVERLAY"
        const val ACTION_UPDATE_SETTINGS = "com.example.action.UPDATE_SETTINGS"
        const val ACTION_TOGGLE_EDIT_MODE = "com.example.action.TOGGLE_EDIT_MODE"
        const val ACTION_SET_EDIT_MODE = "com.example.action.SET_EDIT_MODE"
        const val ACTION_ADD_EXTRA_BUTTON = "com.example.action.ADD_EXTRA_BUTTON"
        const val ACTION_RELOAD_CONFIGS = "com.example.action.RELOAD_CONFIGS"
        const val ACTION_RESET_LAYOUT = "com.example.action.RESET_LAYOUT"
        const val EXTRA_EDIT_MODE = "extra_edit_mode"

        fun start(context: Context, editMode: Boolean = false) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_EDIT_MODE, editMode)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun setEditMode(context: Context, enabled: Boolean) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_SET_EDIT_MODE
                putExtra(EXTRA_EDIT_MODE, enabled)
            }
            context.startService(intent)
        }

        fun addExtraButton(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_ADD_EXTRA_BUTTON
            }
            context.startService(intent)
        }

        fun reloadButtonConfigs(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_RELOAD_CONFIGS
            }
            context.startService(intent)
        }

        fun notifySettingsChanged(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_UPDATE_SETTINGS
            }
            context.startService(intent)
        }

        fun resetLayout(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_RESET_LAYOUT
            }
            context.startService(intent)
        }
    }
}
