package com.example.server

import android.annotation.SuppressLint
import android.hardware.input.InputManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.annotation.Keep
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.OutputStream
import java.lang.reflect.Method
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Self-contained privileged input daemon compiled directly into the PadForge APK.
 * Launched via local Wireless ADB (`app_process`) under UID 2000 (shell privilege).
 *
 * Supports Gamepad single/multi-key, full PC Keyboard (`SOURCE_KEYBOARD`),
 * Mouse Left/Right/Middle clicks (`SOURCE_MOUSE`), and composite Joystick/Hat Switch axes.
 */
@Keep
class PadForgeInputServer(
    private val port: Int,
    private val expectedTokenBytes: ByteArray
) {

    private val running = AtomicBoolean(true)
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
            runCatching { HiddenApiBypass.addHiddenApiExemptions("L") }
        }
        val resolved = resolveInputManagerInjector()
        inputManagerInstance = resolved?.first
        injectInputEventMethod = resolved?.second
        setActionButtonMethod = runCatching {
            MotionEvent::class.java.getMethod("setActionButton", Int::class.javaPrimitiveType).apply {
                isAccessible = true
            }
        }.getOrNull()
    }

    fun start() {
        val loopback = InetAddress.getLoopbackAddress()
        ServerSocket(port, 4, loopback).use { serverSocket ->
            serverSocket.reuseAddress = true
            println("PADFORGE_DAEMON_READY:$port")
            while (running.get()) {
                val client = try {
                    serverSocket.accept()
                } catch (_: Throwable) {
                    if (!running.get()) break else continue
                }
                handleClient(client)
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            s.tcpNoDelay = true
            s.soTimeout = AUTH_TIMEOUT_MS

            val input = DataInputStream(BufferedInputStream(s.getInputStream(), 4096))
            val output = s.getOutputStream()

            if (!authenticate(input, output)) {
                return
            }

            s.soTimeout = 0

            while (running.get()) {
                val op = try {
                    input.readByte().toInt() and 0xFF
                } catch (_: EOFException) {
                    break
                } catch (_: Throwable) {
                    break
                }

                val arg1 = input.readInt()
                val arg2 = input.readInt()
                val arg3 = input.readInt()
                val arg4 = input.readInt()

                when (op) {
                    OP_PING -> {
                        output.write(AUTH_OK_BYTE)
                        output.flush()
                    }
                    OP_KEY -> injectGamepadKeyEvent(keyCode = arg1, action = arg2, now = SystemClock.uptimeMillis())
                    OP_MULTI_KEY -> {
                        val action = arg1
                        val count = arg2.coerceIn(0, MAX_MULTI_KEYS)
                        val keys = IntArray(count)
                        for (i in 0 until count) {
                            keys[i] = input.readInt()
                        }
                        injectMultiKeyEvent(keys, action)
                    }
                    OP_KEYBOARD -> injectKeyboardEvent(keyCode = arg1, action = arg2)
                    OP_MOUSE_BUTTON -> injectMouseButton(buttonState = arg1, action = arg2)
                    OP_JOYSTICK -> injectJoystick(
                        axisX = arg1,
                        axisY = arg2,
                        x = Float.fromBits(arg3),
                        y = Float.fromBits(arg4)
                    )
                    OP_TRIGGER -> injectTrigger(
                        axis = arg1,
                        value = Float.fromBits(arg2)
                    )
                    OP_SHUTDOWN -> {
                        running.set(false)
                        break
                    }
                }
            }
        }
    }

    private fun authenticate(input: DataInputStream, output: OutputStream): Boolean {
        return try {
            val received = ByteArray(expectedTokenBytes.size)
            input.readFully(received)
            val valid = MessageDigest.isEqual(expectedTokenBytes, received)
            output.write(if (valid) AUTH_OK_BYTE else AUTH_FAIL_BYTE)
            output.flush()
            valid
        } catch (_: Throwable) {
            false
        }
    }

    private fun injectMultiKeyEvent(keyCodes: IntArray, action: Int) {
        if (keyCodes.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        for (keyCode in keyCodes) {
            injectGamepadKeyEvent(keyCode, action, now)
        }
    }

    private fun injectGamepadKeyEvent(keyCode: Int, action: Int, now: Long) {
        val downTime = when (action) {
            KeyEvent.ACTION_DOWN -> {
                keyDownTimes[keyCode] = now
                now
            }
            KeyEvent.ACTION_UP -> keyDownTimes.remove(keyCode) ?: now
            else -> keyDownTimes[keyCode] ?: now
        }

        val event = KeyEvent(
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
        injectEvent(event)
    }

    private fun injectKeyboardEvent(keyCode: Int, action: Int) {
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

            val event = KeyEvent(
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
            injectEvent(event)
        }
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

    private fun injectMouseButton(buttonState: Int, action: Int) {
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

    private fun injectJoystick(axisX: Int, axisY: Int, x: Float, y: Float) {
        val cx = normalizeZero(x.coerceIn(-1f, 1f))
        val cy = normalizeZero(y.coerceIn(-1f, 1f))
        synchronized(axisLock) {
            activeAxes[axisX] = cx
            activeAxes[axisY] = cy
            injectCompositeJoystickLocked()
        }
    }

    private fun injectTrigger(axis: Int, value: Float) {
        val clamped = normalizeZero(value.coerceIn(0f, 1f))
        synchronized(axisLock) {
            activeAxes[axis] = clamped
            injectCompositeJoystickLocked()
        }
    }

    private fun injectCompositeJoystickLocked() {
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
        val mgr = inputManagerInstance ?: return
        val method = injectInputEventMethod ?: return
        runCatching {
            method.invoke(mgr, event, INJECT_INPUT_EVENT_MODE_ASYNC)
        }
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun resolveInputManagerInjector(): Pair<Any, Method>? {
        val candidateClassNames = listOf(
            "android.hardware.input.InputManagerGlobal",
            InputManager::class.java.name
        )
        for (className in candidateClassNames) {
            val resolved = runCatching {
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
            if (resolved != null) return resolved
        }
        return null
    }

    companion object {
        const val DEFAULT_PORT = 28765
        const val OP_PING = 0x00
        const val OP_KEY = 0x01
        const val OP_JOYSTICK = 0x02
        const val OP_TRIGGER = 0x03
        const val OP_MULTI_KEY = 0x04
        const val OP_KEYBOARD = 0x05
        const val OP_MOUSE_BUTTON = 0x06
        const val OP_SHUTDOWN = 0xFF

        const val AUTH_OK_BYTE = 0x01
        const val AUTH_FAIL_BYTE = 0x00

        private const val MAX_MULTI_KEYS = 16
        private const val AUTH_TIMEOUT_MS = 3_000
        private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0

        @JvmStatic
        fun main(args: Array<String>) {
            var port = DEFAULT_PORT
            var token = ""

            for (arg in args) {
                when {
                    arg.startsWith("--port=") -> {
                        port = arg.substringAfter("--port=").toIntOrNull() ?: DEFAULT_PORT
                    }
                    arg.startsWith("--token=") -> {
                        token = arg.substringAfter("--token=")
                    }
                }
            }

            if (token.length != 64) {
                System.err.println("PadForgeInputServer requires a 64-char hex --token")
                exitProcess(2)
            }

            @Suppress("DEPRECATION")
            runCatching { Looper.prepareMainLooper() }

            try {
                val server = PadForgeInputServer(
                    port = port,
                    expectedTokenBytes = token.toByteArray(StandardCharsets.US_ASCII)
                )
                server.start()
            } catch (t: Throwable) {
                System.err.println("PadForgeInputServer terminated: ${t.message}")
                exitProcess(1)
            }
            exitProcess(0)
        }
    }
}
