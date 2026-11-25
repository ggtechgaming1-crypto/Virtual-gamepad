package com.example

import android.content.SharedPreferences
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.core.content.edit
import com.example.data.ControlId
import com.example.data.ControllerPreferences
import com.example.overlay.ButtonShape
import org.json.JSONArray
import org.json.JSONObject

/**
 * Configuration model for every interactive virtual gamepad button (default or custom extra).
 * Supports 1 to 4 simultaneous bound keys across Gamepad, Keyboard, Mouse, and Hat Switch axes,
 * plus per-button size scaling.
 */
data class ButtonConfig(
    val id: String,
    val label: String,
    val x: Int,
    val y: Int,
    val boundKeys: List<BoundKey>,
    val sizeScale: Float = 1.0f,
    val isExtra: Boolean = false
) {
    val keyCodes: List<Int>
        get() = boundKeys.map { it.keyCode }
}

data class ButtonVisualSpec(
    val accentHex: String,
    val shape: ButtonShape,
    val baseWidthDp: Float,
    val baseHeightDp: Float
)

/**
 * Pure `org.json` SharedPreferences persistence store supporting multi-profile layouts
 * (Xbox / PS4 / PC), extra custom buttons, per-button size scaling, and mixed-type combos.
 */
object ButtonConfigStore {

    private const val KEY_ACTIVE_PROFILE = "padforge_active_profile_v2"
    private const val KEY_PROFILE_CONFIGS_PREFIX = "padforge_button_configs_v2_"
    private const val LEGACY_KEY_V1 = "padforge_button_configs_json_v1"

    const val MAX_COMBO_KEYS = 4
    const val MIN_BUTTON_SIZE_SCALE = 0.6f
    const val MAX_BUTTON_SIZE_SCALE = 1.8f

    fun getActiveProfile(prefs: SharedPreferences): ControllerProfile {
        return ControllerProfile.fromId(prefs.getString(KEY_ACTIVE_PROFILE, ControllerProfile.XBOX.id))
    }

    fun setActiveProfile(prefs: SharedPreferences, profile: ControllerProfile) {
        prefs.edit { putString(KEY_ACTIVE_PROFILE, profile.id) }
    }

    fun visualSpecFor(config: ButtonConfig): ButtonVisualSpec {
        if (config.isExtra) {
            return ButtonVisualSpec(
                accentHex = "#C084FC",
                shape = ButtonShape.PILL,
                baseWidthDp = 72f,
                baseHeightDp = 46f
            )
        }
        return when (config.id) {
            ControlId.BTN_A.prefKey -> ButtonVisualSpec("#00E5C4", ButtonShape.CIRCLE, 58f, 58f)
            ControlId.BTN_B.prefKey -> ButtonVisualSpec("#FF5C77", ButtonShape.CIRCLE, 58f, 58f)
            ControlId.BTN_X.prefKey -> ButtonVisualSpec("#3B9EFF", ButtonShape.CIRCLE, 58f, 58f)
            ControlId.BTN_Y.prefKey -> ButtonVisualSpec("#FFB84D", ButtonShape.CIRCLE, 58f, 58f)
            ControlId.BTN_L1.prefKey,
            ControlId.BTN_R1.prefKey -> ButtonVisualSpec("#B8C7E0", ButtonShape.PILL, 78f, 44f)
            ControlId.BTN_L2.prefKey,
            ControlId.BTN_R2.prefKey -> ButtonVisualSpec("#8FA8D0", ButtonShape.PILL, 78f, 44f)
            ControlId.BTN_SELECT.prefKey,
            ControlId.BTN_START.prefKey -> ButtonVisualSpec("#94A8C8", ButtonShape.CAPSULE, 66f, 34f)
            else -> ButtonVisualSpec("#C084FC", ButtonShape.CIRCLE, 58f, 58f)
        }
    }

    fun loadConfigs(
        prefs: SharedPreferences,
        controllerPrefs: ControllerPreferences,
        screenW: Int,
        screenH: Int,
        density: Float,
        scale: Float,
        profile: ControllerProfile = getActiveProfile(prefs)
    ): MutableList<ButtonConfig> {
        val profileKey = "$KEY_PROFILE_CONFIGS_PREFIX${profile.id}"
        val rawJson = prefs.getString(profileKey, null)
            ?: if (profile == ControllerProfile.XBOX) prefs.getString(LEGACY_KEY_V1, null) else null

        if (!rawJson.isNullOrBlank()) {
            val parsed = parseJson(rawJson, screenW, screenH)
            if (parsed.isNotEmpty()) {
                return ensureAllDefaultsPresent(parsed, controllerPrefs, screenW, screenH, density, scale, profile)
            }
        }
        val defaults = createDefaultConfigs(controllerPrefs, screenW, screenH, density, scale, profile)
        saveConfigs(prefs, defaults, profile)
        return defaults
    }

    fun saveConfigs(
        prefs: SharedPreferences,
        configs: List<ButtonConfig>,
        profile: ControllerProfile = getActiveProfile(prefs)
    ) {
        val array = JSONArray()
        configs.forEach { cfg ->
            val obj = JSONObject().apply {
                put("id", cfg.id)
                put("label", cfg.label)
                put("x", cfg.x)
                put("y", cfg.y)
                put("sizeScale", cfg.sizeScale.coerceIn(MIN_BUTTON_SIZE_SCALE, MAX_BUTTON_SIZE_SCALE).toDouble())
                put("isExtra", cfg.isExtra)
                val keysArray = JSONArray()
                cfg.boundKeys.take(MAX_COMBO_KEYS).forEach { bk ->
                    keysArray.put(
                        JSONObject().apply {
                            put("keyCode", bk.keyCode)
                            put("inputType", bk.inputType.name)
                        }
                    )
                }
                put("boundKeys", keysArray)
            }
            array.put(obj)
        }
        prefs.edit {
            putString("$KEY_PROFILE_CONFIGS_PREFIX${profile.id}", array.toString())
        }
    }

    fun clearSavedConfigs(prefs: SharedPreferences, profile: ControllerProfile? = null) {
        prefs.edit {
            if (profile != null) {
                remove("$KEY_PROFILE_CONFIGS_PREFIX${profile.id}")
            } else {
                ControllerProfile.entries.forEach { p ->
                    remove("$KEY_PROFILE_CONFIGS_PREFIX${p.id}")
                }
                remove(LEGACY_KEY_V1)
            }
        }
    }

    fun createExtraButton(
        existing: List<ButtonConfig>,
        screenW: Int,
        screenH: Int,
        density: Float,
        scale: Float,
        profile: ControllerProfile = ControllerProfile.XBOX
    ): ButtonConfig {
        val existingIds = existing.map { it.id }.toSet()
        var index = 1
        while (existingIds.contains("extra_$index")) {
            index++
        }
        val widthPx = (72f * scale * density).toInt().coerceAtLeast(1)
        val heightPx = (46f * scale * density).toInt().coerceAtLeast(1)
        val offsetStep = ((index - 1) % 5) * (18f * density).toInt()
        val spawnX = ((screenW - widthPx) / 2 + offsetStep).coerceIn(0, (screenW - widthPx).coerceAtLeast(0))
        val spawnY = ((screenH * 0.30f).toInt() + offsetStep).coerceIn(0, (screenH - heightPx).coerceAtLeast(0))

        val defaultKeys = when (profile) {
            ControllerProfile.PC -> listOf(
                BoundKey(KeyEvent.KEYCODE_SHIFT_LEFT, KeyInputType.KEYBOARD),
                BoundKey(KeyEvent.KEYCODE_W, KeyInputType.KEYBOARD)
            )
            ControllerProfile.XBOX,
            ControllerProfile.PS4 -> listOf(
                BoundKey(KeyEvent.KEYCODE_BUTTON_L1, KeyInputType.GAMEPAD),
                BoundKey(KeyEvent.KEYCODE_BUTTON_R1, KeyInputType.GAMEPAD)
            )
        }

        return ButtonConfig(
            id = "extra_$index",
            label = "M$index",
            x = spawnX,
            y = spawnY,
            boundKeys = defaultKeys,
            sizeScale = 1.0f,
            isExtra = true
        )
    }

    fun createDefaultConfigs(
        controllerPrefs: ControllerPreferences,
        screenW: Int,
        screenH: Int,
        density: Float,
        scale: Float,
        profile: ControllerProfile = ControllerProfile.XBOX
    ): MutableList<ButtonConfig> {
        data class DefaultSeed(
            val controlId: ControlId,
            val label: String,
            val boundKey: BoundKey,
            val baseW: Float,
            val baseH: Float
        )

        val seeds = when (profile) {
            ControllerProfile.XBOX -> listOf(
                DefaultSeed(ControlId.BTN_A, "A", BoundKey(KeyEvent.KEYCODE_BUTTON_A, KeyInputType.GAMEPAD), 58f, 58f),
                DefaultSeed(ControlId.BTN_B, "B", BoundKey(KeyEvent.KEYCODE_BUTTON_B, KeyInputType.GAMEPAD), 58f, 58f),
                DefaultSeed(ControlId.BTN_X, "X", BoundKey(KeyEvent.KEYCODE_BUTTON_X, KeyInputType.GAMEPAD), 58f, 58f),
                DefaultSeed(ControlId.BTN_Y, "Y", BoundKey(KeyEvent.KEYCODE_BUTTON_Y, KeyInputType.GAMEPAD), 58f, 58f),
                DefaultSeed(ControlId.BTN_L1, "LB", BoundKey(KeyEvent.KEYCODE_BUTTON_L1, KeyInputType.GAMEPAD), 78f, 44f),
                DefaultSeed(ControlId.BTN_R1, "RB", BoundKey(KeyEvent.KEYCODE_BUTTON_R1, KeyInputType.GAMEPAD), 78f, 44f),
                DefaultSeed(ControlId.BTN_L2, "LT", BoundKey(KeyEvent.KEYCODE_BUTTON_L2, KeyInputType.GAMEPAD), 78f, 44f),
                DefaultSeed(ControlId.BTN_R2, "RT", BoundKey(KeyEvent.KEYCODE_BUTTON_R2, KeyInputType.GAMEPAD), 78f, 44f),
                DefaultSeed(ControlId.BTN_SELECT, "View", BoundKey(KeyEvent.KEYCODE_BUTTON_SELECT, KeyInputType.GAMEPAD), 66f, 34f),
                DefaultSeed(ControlId.BTN_START, "Menu", BoundKey(KeyEvent.KEYCODE_BUTTON_START, KeyInputType.GAMEPAD), 66f, 34f)
            )
            ControllerProfile.PS4 -> listOf(
                DefaultSeed(ControlId.BTN_A, "✕", BoundKey(KeyEvent.KEYCODE_BUTTON_A, KeyInputType.GAMEPAD), 58f, 58f),
                DefaultSeed(ControlId.BTN_B, "○", BoundKey(KeyEvent.KEYCODE_BUTTON_B, KeyInputType.GAMEPAD), 58f, 58f),
                DefaultSeed(ControlId.BTN_X, "□", BoundKey(KeyEvent.KEYCODE_BUTTON_X, KeyInputType.GAMEPAD), 58f, 58f),
                DefaultSeed(ControlId.BTN_Y, "△", BoundKey(KeyEvent.KEYCODE_BUTTON_Y, KeyInputType.GAMEPAD), 58f, 58f),
                DefaultSeed(ControlId.BTN_L1, "L1", BoundKey(KeyEvent.KEYCODE_BUTTON_L1, KeyInputType.GAMEPAD), 78f, 44f),
                DefaultSeed(ControlId.BTN_R1, "R1", BoundKey(KeyEvent.KEYCODE_BUTTON_R1, KeyInputType.GAMEPAD), 78f, 44f),
                DefaultSeed(ControlId.BTN_L2, "L2", BoundKey(KeyEvent.KEYCODE_BUTTON_L2, KeyInputType.GAMEPAD), 78f, 44f),
                DefaultSeed(ControlId.BTN_R2, "R2", BoundKey(KeyEvent.KEYCODE_BUTTON_R2, KeyInputType.GAMEPAD), 78f, 44f),
                DefaultSeed(ControlId.BTN_SELECT, "Share", BoundKey(KeyEvent.KEYCODE_BUTTON_SELECT, KeyInputType.GAMEPAD), 66f, 34f),
                DefaultSeed(ControlId.BTN_START, "Opt", BoundKey(KeyEvent.KEYCODE_BUTTON_START, KeyInputType.GAMEPAD), 66f, 34f)
            )
            ControllerProfile.PC -> listOf(
                DefaultSeed(ControlId.BTN_A, "Space", BoundKey(KeyEvent.KEYCODE_SPACE, KeyInputType.KEYBOARD), 58f, 58f),
                DefaultSeed(ControlId.BTN_B, "C", BoundKey(KeyEvent.KEYCODE_C, KeyInputType.KEYBOARD), 58f, 58f),
                DefaultSeed(ControlId.BTN_X, "R", BoundKey(KeyEvent.KEYCODE_R, KeyInputType.KEYBOARD), 58f, 58f),
                DefaultSeed(ControlId.BTN_Y, "E", BoundKey(KeyEvent.KEYCODE_E, KeyInputType.KEYBOARD), 58f, 58f),
                DefaultSeed(ControlId.BTN_L1, "Shift", BoundKey(KeyEvent.KEYCODE_SHIFT_LEFT, KeyInputType.KEYBOARD), 78f, 44f),
                DefaultSeed(ControlId.BTN_R1, "F", BoundKey(KeyEvent.KEYCODE_F, KeyInputType.KEYBOARD), 78f, 44f),
                DefaultSeed(ControlId.BTN_L2, "RMB", BoundKey(MotionEvent.BUTTON_SECONDARY, KeyInputType.MOUSE), 78f, 44f),
                DefaultSeed(ControlId.BTN_R2, "LMB", BoundKey(MotionEvent.BUTTON_PRIMARY, KeyInputType.MOUSE), 78f, 44f),
                DefaultSeed(ControlId.BTN_SELECT, "Tab", BoundKey(KeyEvent.KEYCODE_TAB, KeyInputType.KEYBOARD), 66f, 34f),
                DefaultSeed(ControlId.BTN_START, "Esc", BoundKey(KeyEvent.KEYCODE_ESCAPE, KeyInputType.KEYBOARD), 66f, 34f)
            )
        }

        return seeds.map { seed ->
            val wPx = (seed.baseW * scale * density).toInt().coerceAtLeast(1)
            val hPx = (seed.baseH * scale * density).toInt().coerceAtLeast(1)
            val pos = controllerPrefs.getPosition(seed.controlId, screenW, screenH, wPx, hPx)
            ButtonConfig(
                id = seed.controlId.prefKey,
                label = seed.label,
                x = pos.x,
                y = pos.y,
                boundKeys = listOf(seed.boundKey),
                sizeScale = 1.0f,
                isExtra = false
            )
        }.toMutableList()
    }

    private fun ensureAllDefaultsPresent(
        existing: MutableList<ButtonConfig>,
        controllerPrefs: ControllerPreferences,
        screenW: Int,
        screenH: Int,
        density: Float,
        scale: Float,
        profile: ControllerProfile
    ): MutableList<ButtonConfig> {
        val existingIds = existing.map { it.id }.toSet()
        val defaults = createDefaultConfigs(controllerPrefs, screenW, screenH, density, scale, profile)
        defaults.forEach { defaultCfg ->
            if (!existingIds.contains(defaultCfg.id)) {
                existing.add(defaultCfg)
            }
        }
        return existing
    }

    private fun parseJson(rawJson: String, screenW: Int, screenH: Int): MutableList<ButtonConfig> {
        val result = mutableListOf<ButtonConfig>()
        runCatching {
            val array = JSONArray(rawJson)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim()
                if (id.isEmpty()) continue
                val label = obj.optString("label").ifBlank { id.uppercase() }.take(12)
                val x = obj.optInt("x", 0).coerceIn(0, (screenW - 20).coerceAtLeast(0))
                val y = obj.optInt("y", 0).coerceIn(0, (screenH - 20).coerceAtLeast(0))
                val sizeScale = obj.optDouble("sizeScale", 1.0).toFloat()
                    .coerceIn(MIN_BUTTON_SIZE_SCALE, MAX_BUTTON_SIZE_SCALE)
                val isExtra = obj.optBoolean("isExtra", false)

                val keys = mutableListOf<BoundKey>()
                val boundKeysArr = obj.optJSONArray("boundKeys")
                if (boundKeysArr != null) {
                    for (k in 0 until boundKeysArr.length().coerceAtMost(MAX_COMBO_KEYS)) {
                        val item = boundKeysArr.optJSONObject(k) ?: continue
                        val code = item.optInt("keyCode", KeyEvent.KEYCODE_UNKNOWN)
                        val typeName = item.optString("inputType", KeyInputType.GAMEPAD.name)
                        val type = runCatching { KeyInputType.valueOf(typeName) }
                            .getOrDefault(KeyInputType.GAMEPAD)
                        if (code != KeyEvent.KEYCODE_UNKNOWN) {
                            val bk = BoundKey(code, type)
                            if (!keys.contains(bk)) keys.add(bk)
                        }
                    }
                } else {
                    // Migrate legacy v1 integer keyCodes array cleanly
                    val legacyArr = obj.optJSONArray("keyCodes")
                    if (legacyArr != null) {
                        for (k in 0 until legacyArr.length().coerceAtMost(MAX_COMBO_KEYS)) {
                            val code = legacyArr.optInt(k, KeyEvent.KEYCODE_UNKNOWN)
                            if (code != KeyEvent.KEYCODE_UNKNOWN) {
                                val bk = BoundKey(code, KeyInputType.GAMEPAD)
                                if (!keys.contains(bk)) keys.add(bk)
                            }
                        }
                    }
                }

                if (keys.isEmpty()) {
                    keys.add(BoundKey(KeyEvent.KEYCODE_BUTTON_A, KeyInputType.GAMEPAD))
                }

                result.add(
                    ButtonConfig(
                        id = id,
                        label = label,
                        x = x,
                        y = y,
                        boundKeys = keys,
                        sizeScale = sizeScale,
                        isExtra = isExtra
                    )
                )
            }
        }
        return result
    }
}
