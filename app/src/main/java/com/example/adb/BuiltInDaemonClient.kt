package com.example.adb

import android.util.Log
import com.example.server.PadForgeInputServer
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * High-speed, zero-blocking loopback binary TCP client talking directly to the
 * built-in PadForgeInputServer daemon (127.0.0.1) with TCP_NODELAY enabled.
 */
class BuiltInDaemonClient {

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var output: DataOutputStream? = null

    private val writeLock = Any()

    val isConnected: Boolean
        get() {
            val s = socket
            return s != null && s.isConnected && !s.isClosed
        }

    fun connect(port: Int, sessionToken: String, timeoutMs: Int = 2_000): Boolean {
        disconnect()
        return runCatching {
            val loopback = InetAddress.getLoopbackAddress()
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(loopback, port), timeoutMs)
            s.soTimeout = timeoutMs

            val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 1024))
            val tokenBytes = sessionToken.toByteArray(StandardCharsets.US_ASCII)
            out.write(tokenBytes)
            out.flush()

            val authResponse = s.getInputStream().read()
            if (authResponse != PadForgeInputServer.AUTH_OK_BYTE) {
                s.close()
                return false
            }

            s.soTimeout = 0
            synchronized(writeLock) {
                socket = s
                output = out
            }
            true
        }.getOrElse {
            Log.w(TAG, "Daemon loopback connect attempt failed: ${it.message}")
            false
        }
    }

    fun sendKeyEvent(keyCode: Int, action: Int): Boolean {
        return sendFrame(PadForgeInputServer.OP_KEY, keyCode, action, 0, 0)
    }

    fun sendMultiKeyEvent(keyCodes: IntArray, action: Int): Boolean {
        if (keyCodes.isEmpty()) return true
        val safeCount = keyCodes.size.coerceAtMost(16)
        synchronized(writeLock) {
            val out = output ?: return false
            return try {
                out.writeByte(PadForgeInputServer.OP_MULTI_KEY)
                out.writeInt(action)
                out.writeInt(safeCount)
                out.writeInt(0)
                out.writeInt(0)
                for (i in 0 until safeCount) {
                    out.writeInt(keyCodes[i])
                }
                out.flush()
                true
            } catch (t: Throwable) {
                Log.w(TAG, "Daemon multi-key write error: ${t.message}")
                disconnect()
                false
            }
        }
    }

    fun sendKeyboardEvent(keyCode: Int, action: Int): Boolean {
        return sendFrame(PadForgeInputServer.OP_KEYBOARD, keyCode, action, 0, 0)
    }

    fun sendMouseButton(buttonState: Int, action: Int): Boolean {
        return sendFrame(PadForgeInputServer.OP_MOUSE_BUTTON, buttonState, action, 0, 0)
    }

    fun sendJoystick(axisX: Int, axisY: Int, x: Float, y: Float): Boolean {
        return sendFrame(
            PadForgeInputServer.OP_JOYSTICK,
            axisX,
            axisY,
            x.toBits(),
            y.toBits()
        )
    }

    fun sendTrigger(axis: Int, value: Float): Boolean {
        return sendFrame(
            PadForgeInputServer.OP_TRIGGER,
            axis,
            value.toBits(),
            0,
            0
        )
    }

    fun requestShutdown() {
        sendFrame(PadForgeInputServer.OP_SHUTDOWN, 0, 0, 0, 0)
        disconnect()
    }

    fun disconnect() {
        synchronized(writeLock) {
            runCatching { output?.close() }
            runCatching { socket?.close() }
            output = null
            socket = null
        }
    }

    private fun sendFrame(op: Int, a1: Int, a2: Int, a3: Int, a4: Int): Boolean {
        synchronized(writeLock) {
            val out = output ?: return false
            return try {
                out.writeByte(op)
                out.writeInt(a1)
                out.writeInt(a2)
                out.writeInt(a3)
                out.writeInt(a4)
                out.flush()
                true
            } catch (t: Throwable) {
                Log.w(TAG, "Daemon write error: ${t.message}")
                disconnect()
                false
            }
        }
    }

    companion object {
        private const val TAG = "BuiltInDaemonClient"
    }
}
