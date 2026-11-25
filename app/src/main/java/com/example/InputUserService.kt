package com.example

import android.annotation.SuppressLint
import android.hardware.input.InputManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.annotation.Keep
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

/**
 * Shizuku UserService executing inside the privileged shell process (UID 2000).
 * Injects zero-lag ASYNC hardware input events across:
 * - Gamepad single & multi-key combos (`SOURCE_GAMEPAD`)
 * - Full PC Keyboard with live modifier `metaState` (`SOURCE_KEYBOARD`)
 * - Mouse Left / Right / Middle clicks (`SOURCE_MOUSE` + `TOOL_TYPE_MOUSE`)
 * - Analog sticks, D-Pad Hat Switch (`AXIS_HAT_X/Y`), and triggers (`SOURCE_JOYSTICK` + `TOOL_TYPE_UNKNOWN`)
 */
@Keep
class InputUserService : IInputService.Stub() {

    private val inputManagerInstance: Any?
    private val injectInputEventMethod: Method?
    private val setActionButtonMethod: Method?

    private val keyDownTimes = ConcurrentHashMap<Int, Long>()
    private val keyboardDownTimes = ConcurrentHashMap<Int, Long>()
    private val keyboardLock = Any()
    private var activeMetaState: Int = 0

    private val mouseLock = Any()
    private var activeMouseButtons: Int = 0
    private var mouseDownTime: Long = 0L

    private val axisLock = Any()
    private val activeAxes = LinkedHashMap<Int, Float>(10).apply {
        put(MotionEvent.AXIS_X, 0f)
        put(MotionEvent.AXIS_Y, 0f)
        put(MotionEvent.AXIS_Z, 0f)
        put(MotionEvent.AXIS_RZ, 0f)
        put(MotionEvent.AXIS_HAT_X, 0f)
        put(MotionEvent.AXIS_HAT_Y, 0f)
        put(MotionEvent.AXIS_LTRIGGER, 0f)
        put(MotionEvent.AXIS_RTRIGGER, 0f)
    }

    private val joystickPointerProps = arrayOf(
        MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_UNKNOWN
        }
    )
    private val joystickPointerCoords = arrayOf(MotionEvent.PointerCoords())

    private val mousePointerProps = arrayOf(
        MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        }
    )
    private val mousePointerCoords = arrayOf(MotionEvent.PointerCoords())

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                HiddenApiBypass.addHiddenApiExemptions("L")
            }.onFailure {
                Log.w(TAG, "HiddenApiBypass exemption warning: ${it.message}")
            }
        }

        val resolved = resolveInputManagerInjector()
        inputManagerInstance = resolved?.first
        injectInputEventMethod = resolved?.second
        setActionButtonMethod = runCatching {
            MotionEvent::class.java.getMethod("setActionButton", Int::class.javaPrimitiveType).apply {
                isAccessible = true
            }
        }.getOrNull()

        if (inputManagerInstance == null || injectInputEventMethod == null) {
            Log.e(TAG, "Failed to resolve InputManager.injectInputEvent handle")
        } else {
            Log.i(TAG, "Privileged InputUserService initialized cleanly")
        }
    }

    override fun sendKeyEvent(keyCode: Int, action: Int) {
        injectSingleGamepadKey(keyCode, action, SystemClock.uptimeMillis())
    }

    override fun sendMultiKeyEvent(keyCodes: IntArray?, action: Int) {
        if (keyCodes == null || keyCodes.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        for (keyCode in keyCodes) {
            injectSingleGamepadKey(keyCode, action, now)
        }
    }

    override fun sendKeyboardEvent(keyCode: Int, action: Int) {
        val now = SystemClock.uptimeMillis()
        synchronized(keyboardLock) {
            updateModifierMetaState(keyCode, action)
            val downTime = when (action) {
                KeyEvent.ACTION_DOWN -> {
                    keyboardDownTimes[keyCode] = now
                    now
                }
                KeyEvent.ACTION_UP -> keyboardDownTimes.remove(keyCode) ?: now
                else -> keyboardDownTimes[keyCode] ?: now
            }

            val keyEvent = KeyEvent(
                downTime,
                now,
                action,
                keyCode,
                0,
                activeMetaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                KeyEvent.FLAG_FROM_SYSTEM,
                InputDevice.SOURCE_KEYBOARD
            )
            injectEvent(keyEvent)
        }
    }

    override fun sendMouseButton(buttonState: Int, action: Int) {
        if (buttonState == 0) return
        val now = SystemClock.uptimeMillis()
        synchronized(mouseLock) {
            if (action == KeyEvent.ACTION_DOWN) {
                val wasEmpty = activeMouseButtons == 0
                if (wasEmpty) mouseDownTime = now
                activeMouseButtons = activeMouseButtons or buttonState
                if (wasEmpty) {
                    injectMouseMotionLocked(mouseDownTime, now, MotionEvent.ACTION_DOWN, activeMouseButtons, 0)
                }
                injectMouseMotionLocked(mouseDownTime, now, MotionEvent.ACTION_BUTTON_PRESS, activeMouseButtons, buttonState)
            } else {
                activeMouseButtons = activeMouseButtons and buttonState.inv()
                val downTime = if (mouseDownTime > 0L) mouseDownTime else now
                injectMouseMotionLocked(downTime, now, MotionEvent.ACTION_BUTTON_RELEASE, activeMouseButtons, buttonState)
                if (activeMouseButtons == 0) {
                    injectMouseMotionLocked(downTime, now, MotionEvent.ACTION_UP, 0, 0)
                    mouseDownTime = 0L
                }
            }
        }
    }

    override fun sendJoystick(x: Float, y: Float, axisX: Int, axisY: Int) {
        val clampedX = normalizeZero(x.coerceIn(-1f, 1f))
        val clampedY = normalizeZero(y.coerceIn(-1f, 1f))
        synchronized(axisLock) {
            activeAxes[axisX] = clampedX
            activeAxes[axisY] = clampedY
            injectCompositeJoystickMotionLocked()
        }
    }

    override fun sendTrigger(value: Float, axis: Int) {
        val clamped = normalizeZero(value.coerceIn(0f, 1f))
        synchronized(axisLock) {
            activeAxes[axis] = clamped
            injectCompositeJoystickMotionLocked()
        }
    }

    override fun destroy() {
        Log.i(TAG, "InputUserService destroy() invoked; exiting privileged daemon")
        exitProcess(0)
    }

    private fun updateModifierMetaState(keyCode: Int, action: Int) {
        val flag = when (keyCode) {
            KeyEvent.KEYCODE_SHIFT_LEFT -> KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
            KeyEvent.KEYCODE_SHIFT_RIGHT -> KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_RIGHT_ON
            KeyEvent.KEYCODE_CTRL_LEFT -> KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            KeyEvent.KEYCODE_CTRL_RIGHT -> KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_RIGHT_ON
            KeyEvent.KEYCODE_ALT_LEFT -> KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
            KeyEvent.KEYCODE_ALT_RIGHT -> KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON
            KeyEvent.KEYCODE_CAPS_LOCK -> KeyEvent.META_CAPS_LOCK_ON
            else -> 0
        }
        if (flag == 0) return
        activeMetaState = if (action == KeyEvent.ACTION_DOWN) {
            activeMetaState or flag
        } else {
            activeMetaState and flag.inv()
        }
    }

    private fun injectSingleGamepadKey(keyCode: Int, action: Int, now: Long) {
        val downTime = when (action) {
            KeyEvent.ACTION_DOWN -> {
                keyDownTimes[keyCode] = now
                now
            }
            KeyEvent.ACTION_UP -> keyDownTimes.remove(keyCode) ?: now
            else -> keyDownTimes[keyCode] ?: now
        }

        val keyEvent = KeyEvent(
            downTime,
            now,
            action,
            keyCode,
            0,
            0,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            KeyEvent.FLAG_FROM_SYSTEM,
            InputDevice.SOURCE_GAMEPAD
        )
        injectEvent(keyEvent)
    }

    private fun injectMouseMotionLocked(
        downTime: Long,
        eventTime: Long,
        motionAction: Int,
        buttonMask: Int,
        actionButton: Int
    ) {
        mousePointerProps[0].id = 0
        mousePointerProps[0].toolType = MotionEvent.TOOL_TYPE_MOUSE
        val coords = mousePointerCoords[0]
        coords.clear()
        coords.pressure = if (buttonMask != 0) 1.0f else 0.0f
        coords.size = 1.0f

        val motionEvent = MotionEvent.obtain(
            downTime,
            eventTime,
            motionAction,
            1,
            mousePointerProps,
            mousePointerCoords,
            0,
            buttonMask,
            1.0f,
            1.0f,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            InputDevice.SOURCE_MOUSE,
            0
        )
        try {
            if (actionButton != 0) {
                runCatching { setActionButtonMethod?.invoke(motionEvent, actionButton) }
            }
            injectEvent(motionEvent)
        } finally {
            motionEvent.recycle()
        }
    }

    private fun injectCompositeJoystickMotionLocked() {
        val now = SystemClock.uptimeMillis()
        joystickPointerProps[0].id = 0
        joystickPointerProps[0].toolType = MotionEvent.TOOL_TYPE_UNKNOWN

        val coords = joystickPointerCoords[0]
        coords.clear()
        for ((axis, axisValue) in activeAxes) {
            coords.setAxisValue(axis, axisValue)
        }

        val motionEvent = MotionEvent.obtain(
            now,
            now,
            MotionEvent.ACTION_MOVE,
            1,
            joystickPointerProps,
            joystickPointerCoords,
            0,
            0,
            1.0f,
            1.0f,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            InputDevice.SOURCE_JOYSTICK,
            0
        )
        try {
            injectEvent(motionEvent)
        } finally {
            motionEvent.recycle()
        }
    }

    private fun normalizeZero(value: Float): Float = if (value == 0f) 0f else value

    private fun injectEvent(event: InputEvent) {
        val manager = inputManagerInstance ?: return
        val method = injectInputEventMethod ?: return
        try {
            method.invoke(manager, event, INJECT_INPUT_EVENT_MODE_ASYNC)
        } catch (t: Throwable) {
            Log.e(TAG, "injectInputEvent failed: ${t.message}")
        }
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun resolveInputManagerInjector(): Pair<Any, Method>? {
        val candidateClassNames = listOf(
            "android.hardware.input.InputManagerGlobal",
            InputManager::class.java.name
        )
        for (className in candidateClassNames) {
            val pair = runCatching {
                val clazz = Class.forName(className)
                val getInstance = clazz.getDeclaredMethod("getInstance").apply {
                    isAccessible = true
                }
                val instance = getInstance.invoke(null) ?: return@runCatching null
                val injectMethod = clazz.getMethod(
                    "injectInputEvent",
                    InputEvent::class.java,
                    Int::class.javaPrimitiveType
                ).apply {
                    isAccessible = true
                }
                instance to injectMethod
            }.getOrNull()
            if (pair != null) return pair
        }
        return null
    }

    companion object {
        private const val TAG = "PadForgeInputDaemon"
        private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0
    }
}
