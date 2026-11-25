package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Point
import androidx.core.content.edit

enum class ControlId(val prefKey: String) {
    LEFT_STICK("left_stick"),
    RIGHT_STICK("right_stick"),
    DPAD("dpad"),
    BTN_A("btn_a"),
    BTN_B("btn_b"),
    BTN_X("btn_x"),
    BTN_Y("btn_y"),
    BTN_L1("btn_l1"),
    BTN_L2("btn_l2"),
    BTN_R1("btn_r1"),
    BTN_R2("btn_r2"),
    BTN_SELECT("btn_select"),
    BTN_START("btn_start")
}

/**
 * Persists virtual gamepad button positions, opacity, control scale, and haptics
 * using SharedPreferences with orientation-aware ergonomic defaults.
 */
class ControllerPreferences(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    val sharedPrefs: SharedPreferences
        get() = prefs

    var opacity: Float
        get() = prefs.getFloat(KEY_OPACITY, DEFAULT_OPACITY).coerceIn(MIN_OPACITY, MAX_OPACITY)
        set(value) = prefs.edit { putFloat(KEY_OPACITY, value.coerceIn(MIN_OPACITY, MAX_OPACITY)) }

    var scale: Float
        get() = prefs.getFloat(KEY_SCALE, DEFAULT_SCALE).coerceIn(MIN_SCALE, MAX_SCALE)
        set(value) = prefs.edit { putFloat(KEY_SCALE, value.coerceIn(MIN_SCALE, MAX_SCALE)) }

    var hapticsEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(value) = prefs.edit { putBoolean(KEY_HAPTICS, value) }

    var hapticIntensity: Float
        get() = prefs.getFloat(KEY_HAPTIC_INTENSITY, DEFAULT_HAPTIC_INTENSITY)
            .coerceIn(MIN_HAPTIC_INTENSITY, MAX_HAPTIC_INTENSITY)
        set(value) = prefs.edit {
            putFloat(KEY_HAPTIC_INTENSITY, value.coerceIn(MIN_HAPTIC_INTENSITY, MAX_HAPTIC_INTENSITY))
        }

    var isWirelessPaired: Boolean
        get() = prefs.getBoolean(KEY_WIRELESS_PAIRED, false)
        set(value) = prefs.edit { putBoolean(KEY_WIRELESS_PAIRED, value) }

    fun savePosition(id: ControlId, x: Int, y: Int) {
        prefs.edit {
            putInt("${id.prefKey}_x", x)
            putInt("${id.prefKey}_y", y)
            putBoolean("${id.prefKey}_saved", true)
        }
    }

    fun getPosition(id: ControlId, screenWidth: Int, screenHeight: Int, viewWidth: Int, viewHeight: Int): Point {
        if (prefs.getBoolean("${id.prefKey}_saved", false)) {
            val savedX = prefs.getInt("${id.prefKey}_x", 0)
            val savedY = prefs.getInt("${id.prefKey}_y", 0)
            val clampedX = savedX.coerceIn(0, (screenWidth - viewWidth).coerceAtLeast(0))
            val clampedY = savedY.coerceIn(0, (screenHeight - viewHeight).coerceAtLeast(0))
            return Point(clampedX, clampedY)
        }
        return defaultPosition(id, screenWidth, screenHeight, viewWidth, viewHeight)
    }

    fun resetPositions() {
        prefs.edit {
            ControlId.entries.forEach { id ->
                remove("${id.prefKey}_x")
                remove("${id.prefKey}_y")
                remove("${id.prefKey}_saved")
            }
        }
        com.example.ButtonConfigStore.clearSavedConfigs(prefs)
    }

    private fun defaultPosition(
        id: ControlId,
        w: Int,
        h: Int,
        vw: Int,
        vh: Int
    ): Point {
        val isLandscape = w > h
        val edgeMarginX = (w * 0.04f).toInt()
        val topShoulderY = if (isLandscape) (h * 0.06f).toInt() else (h * 0.36f).toInt()
        val bottomClusterY = if (isLandscape) (h * 0.62f).toInt() else (h * 0.66f).toInt()

        val raw = when (id) {
            // Left Triggers
            ControlId.BTN_L2 -> Point(edgeMarginX, topShoulderY)
            ControlId.BTN_L1 -> Point(edgeMarginX + (vw * 1.15f).toInt(), topShoulderY)

            // Right Triggers
            ControlId.BTN_R2 -> Point(w - edgeMarginX - vw, topShoulderY)
            ControlId.BTN_R1 -> Point(w - edgeMarginX - (vw * 2.15f).toInt(), topShoulderY)

            // Left Stick & D-Pad
            ControlId.LEFT_STICK -> if (isLandscape) {
                Point(edgeMarginX + (w * 0.02f).toInt(), (h * 0.44f).toInt())
            } else {
                Point(edgeMarginX, (h * 0.56f).toInt())
            }
            ControlId.DPAD -> if (isLandscape) {
                Point(edgeMarginX + (w * 0.19f).toInt(), (h * 0.64f).toInt())
            } else {
                Point(edgeMarginX + (w * 0.04f).toInt(), (h * 0.78f).toInt())
            }

            // Right Stick & ABXY Diamond
            ControlId.RIGHT_STICK -> if (isLandscape) {
                Point(w - edgeMarginX - (w * 0.21f).toInt() - vw, (h * 0.64f).toInt())
            } else {
                Point(w - edgeMarginX - vw - (w * 0.04f).toInt(), (h * 0.78f).toInt())
            }

            ControlId.BTN_Y -> {
                val centerX = w - edgeMarginX - (vw * 2.1f).toInt()
                val centerY = if (isLandscape) (h * 0.46f).toInt() else bottomClusterY - (vh * 1.05f).toInt()
                Point(centerX, centerY - (vh * 1.05f).toInt())
            }
            ControlId.BTN_A -> {
                val centerX = w - edgeMarginX - (vw * 2.1f).toInt()
                val centerY = if (isLandscape) (h * 0.46f).toInt() else bottomClusterY - (vh * 1.05f).toInt()
                Point(centerX, centerY + (vh * 1.05f).toInt())
            }
            ControlId.BTN_X -> {
                val centerX = w - edgeMarginX - (vw * 2.1f).toInt()
                val centerY = if (isLandscape) (h * 0.46f).toInt() else bottomClusterY - (vh * 1.05f).toInt()
                Point(centerX - (vw * 1.05f).toInt(), centerY)
            }
            ControlId.BTN_B -> {
                val centerX = w - edgeMarginX - (vw * 2.1f).toInt()
                val centerY = if (isLandscape) (h * 0.46f).toInt() else bottomClusterY - (vh * 1.05f).toInt()
                Point(centerX + (vw * 1.05f).toInt(), centerY)
            }

            // Select / Start center controls
            ControlId.BTN_SELECT -> Point(
                (w / 2) - vw - (w * 0.025f).toInt(),
                if (isLandscape) (h * 0.84f).toInt() else (h * 0.48f).toInt()
            )
            ControlId.BTN_START -> Point(
                (w / 2) + (w * 0.025f).toInt(),
                if (isLandscape) (h * 0.84f).toInt() else (h * 0.48f).toInt()
            )
        }

        return Point(
            raw.x.coerceIn(0, (w - vw).coerceAtLeast(0)),
            raw.y.coerceIn(0, (h - vh).coerceAtLeast(0))
        )
    }

    companion object {
        private const val PREFS_NAME = "padforge_controller_prefs"
        private const val KEY_OPACITY = "overlay_opacity"
        private const val KEY_SCALE = "overlay_scale"
        private const val KEY_HAPTICS = "overlay_haptics"
        private const val KEY_HAPTIC_INTENSITY = "overlay_haptic_intensity"
        private const val KEY_WIRELESS_PAIRED = "builtin_wireless_adb_paired"

        const val DEFAULT_OPACITY = 0.72f
        const val MIN_OPACITY = 0.15f
        const val MAX_OPACITY = 1.0f

        const val DEFAULT_SCALE = 1.0f
        const val MIN_SCALE = 0.75f
        const val MAX_SCALE = 1.35f

        const val DEFAULT_HAPTIC_INTENSITY = 0.70f
        const val MIN_HAPTIC_INTENSITY = 0.10f
        const val MAX_HAPTIC_INTENSITY = 1.0f
    }
}
