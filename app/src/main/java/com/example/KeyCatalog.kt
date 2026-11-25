package com.example

import android.view.KeyEvent
import android.view.MotionEvent

enum class KeyInputType {
    GAMEPAD,
    KEYBOARD,
    MOUSE,
    HAT_AXIS
}

enum class KeyCategory(val displayName: String) {
    ALL("All"),
    XBOX("Xbox"),
    PLAYSTATION("PlayStation"),
    KEYBOARD("Keyboard"),
    MOUSE("Mouse")
}

enum class ControllerProfile(val id: String, val displayName: String) {
    XBOX("xbox", "Xbox"),
    PS4("ps4", "PS4"),
    PC("pc", "PC");

    fun next(): ControllerProfile {
        val list = entries
        return list[(ordinal + 1) % list.size]
    }

    companion object {
        fun fromId(id: String?): ControllerProfile =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: XBOX
    }
}

/**
 * Single bound hardware input entry stored inside [ButtonConfig].
 */
data class BoundKey(
    val keyCode: Int,
    val inputType: KeyInputType
)

/**
 * Searchable entry in the master key catalog covering Xbox, PlayStation, full PC Keyboard, and Mouse.
 */
data class CatalogKeyEntry(
    val id: String,
    val displayName: String,
    val shortLabel: String,
    val keyCode: Int,
    val inputType: KeyInputType,
    val category: KeyCategory,
    val aliases: List<String> = emptyList()
) {
    fun toBoundKey(): BoundKey = BoundKey(keyCode = keyCode, inputType = inputType)
}

/**
 * Complete Master Key Catalog for PadForge's Universal Key Picker.
 */
object KeyCatalog {

    // Virtual codes for D-Pad Hat Switch directions (injected via AXIS_HAT_X / AXIS_HAT_Y)
    const val HAT_UP = 10001
    const val HAT_DOWN = 10002
    const val HAT_LEFT = 10003
    const val HAT_RIGHT = 10004

    val XBOX_KEYS: List<CatalogKeyEntry> = listOf(
        CatalogKeyEntry("xbox_a", "Xbox A", "A", KeyEvent.KEYCODE_BUTTON_A, KeyInputType.GAMEPAD, KeyCategory.XBOX, listOf("confirm", "green")),
        CatalogKeyEntry("xbox_b", "Xbox B", "B", KeyEvent.KEYCODE_BUTTON_B, KeyInputType.GAMEPAD, KeyCategory.XBOX, listOf("back", "red")),
        CatalogKeyEntry("xbox_x", "Xbox X", "X", KeyEvent.KEYCODE_BUTTON_X, KeyInputType.GAMEPAD, KeyCategory.XBOX, listOf("blue")),
        CatalogKeyEntry("xbox_y", "Xbox Y", "Y", KeyEvent.KEYCODE_BUTTON_Y, KeyInputType.GAMEPAD, KeyCategory.XBOX, listOf("yellow")),
        CatalogKeyEntry("xbox_lb", "LB (Left Bumper)", "LB", KeyEvent.KEYCODE_BUTTON_L1, KeyInputType.GAMEPAD, KeyCategory.XBOX, listOf("l1", "bumper", "shoulder")),
        CatalogKeyEntry("xbox_rb", "RB (Right Bumper)", "RB", KeyEvent.KEYCODE_BUTTON_R1, KeyInputType.GAMEPAD, KeyCategory.XBOX, listOf("r1", "bumper", "shoulder")),
        CatalogKeyEntry("xbox_lt", "LT (Left Trigger)", "LT", KeyEvent.KEYCODE_BUTTON_L2, KeyInputType.GAMEPAD, KeyCategory.XBOX, listOf("l2", "trigger")),
        CatalogKeyEntry("xbox_rt", "RT (Right Trigger)", "RT", KeyEvent.KEYCODE_BUTTON_R2, KeyInputType.GAMEPAD, KeyCategory.XBOX, listOf("r2", "trigger")),
        CatalogKeyEntry("xbox_l3", "L3 (Left Stick Click)", "L3", KeyEvent.KEYCODE_BUTTON_THUMBL, KeyInputType.GAMEPAD, KeyCategory.XBOX, listOf("ls", "thumbl")),
        CatalogKeyEntry("xbox_r3", "R3 (Right Stick Click)", "R3", KeyEvent.KEYCODE_BUTTON_THUMBR, KeyInputType.GAMEPAD, KeyCategory.XBOX, listOf("rs", "thumbr")),
        CatalogKeyEntry("xbox_view", "View (Select)", "View", KeyEvent.KEYCODE_BUTTON_SELECT, KeyInputType.GAMEPAD, KeyCategory.XBOX, listOf("back", "select")),
        CatalogKeyEntry("xbox_menu", "Menu (Start)", "Menu", KeyEvent.KEYCODE_BUTTON_START, KeyInputType.GAMEPAD, KeyCategory.XBOX, listOf("start", "pause")),
        CatalogKeyEntry("xbox_guide", "Xbox Guide", "Guide", KeyEvent.KEYCODE_BUTTON_MODE, KeyInputType.GAMEPAD, KeyCategory.XBOX, listOf("home", "mode")),
        CatalogKeyEntry("hat_up", "D-Pad Up (Hat)", "↑", HAT_UP, KeyInputType.HAT_AXIS, KeyCategory.XBOX, listOf("dpad", "hat", "up")),
        CatalogKeyEntry("hat_down", "D-Pad Down (Hat)", "↓", HAT_DOWN, KeyInputType.HAT_AXIS, KeyCategory.XBOX, listOf("dpad", "hat", "down")),
        CatalogKeyEntry("hat_left", "D-Pad Left (Hat)", "←", HAT_LEFT, KeyInputType.HAT_AXIS, KeyCategory.XBOX, listOf("dpad", "hat", "left")),
        CatalogKeyEntry("hat_right", "D-Pad Right (Hat)", "→", HAT_RIGHT, KeyInputType.HAT_AXIS, KeyCategory.XBOX, listOf("dpad", "hat", "right"))
    )

    val PLAYSTATION_KEYS: List<CatalogKeyEntry> = listOf(
        CatalogKeyEntry("ps_cross", "Cross (✕)", "✕", KeyEvent.KEYCODE_BUTTON_A, KeyInputType.GAMEPAD, KeyCategory.PLAYSTATION, listOf("x", "cross", "ps4", "ps5")),
        CatalogKeyEntry("ps_circle", "Circle (○)", "○", KeyEvent.KEYCODE_BUTTON_B, KeyInputType.GAMEPAD, KeyCategory.PLAYSTATION, listOf("o", "circle", "ps4", "ps5")),
        CatalogKeyEntry("ps_square", "Square (□)", "□", KeyEvent.KEYCODE_BUTTON_X, KeyInputType.GAMEPAD, KeyCategory.PLAYSTATION, listOf("square", "ps4", "ps5")),
        CatalogKeyEntry("ps_triangle", "Triangle (△)", "△", KeyEvent.KEYCODE_BUTTON_Y, KeyInputType.GAMEPAD, KeyCategory.PLAYSTATION, listOf("triangle", "ps4", "ps5")),
        CatalogKeyEntry("ps_l1", "L1", "L1", KeyEvent.KEYCODE_BUTTON_L1, KeyInputType.GAMEPAD, KeyCategory.PLAYSTATION, listOf("lb", "shoulder", "ps4")),
        CatalogKeyEntry("ps_r1", "R1", "R1", KeyEvent.KEYCODE_BUTTON_R1, KeyInputType.GAMEPAD, KeyCategory.PLAYSTATION, listOf("rb", "shoulder", "ps4")),
        CatalogKeyEntry("ps_l2", "L2", "L2", KeyEvent.KEYCODE_BUTTON_L2, KeyInputType.GAMEPAD, KeyCategory.PLAYSTATION, listOf("lt", "trigger", "ps4")),
        CatalogKeyEntry("ps_r2", "R2", "R2", KeyEvent.KEYCODE_BUTTON_R2, KeyInputType.GAMEPAD, KeyCategory.PLAYSTATION, listOf("rt", "trigger", "ps4")),
        CatalogKeyEntry("ps_l3", "L3", "L3", KeyEvent.KEYCODE_BUTTON_THUMBL, KeyInputType.GAMEPAD, KeyCategory.PLAYSTATION, listOf("left stick", "ps4")),
        CatalogKeyEntry("ps_r3", "R3", "R3", KeyEvent.KEYCODE_BUTTON_THUMBR, KeyInputType.GAMEPAD, KeyCategory.PLAYSTATION, listOf("right stick", "ps4")),
        CatalogKeyEntry("ps_share", "Share", "Share", KeyEvent.KEYCODE_BUTTON_SELECT, KeyInputType.GAMEPAD, KeyCategory.PLAYSTATION, listOf("select", "create", "ps4")),
        CatalogKeyEntry("ps_options", "Options", "Opt", KeyEvent.KEYCODE_BUTTON_START, KeyInputType.GAMEPAD, KeyCategory.PLAYSTATION, listOf("start", "menu", "ps4")),
        CatalogKeyEntry("ps_home", "PS Button", "PS", KeyEvent.KEYCODE_BUTTON_MODE, KeyInputType.GAMEPAD, KeyCategory.PLAYSTATION, listOf("guide", "home", "mode"))
    )

    val KEYBOARD_KEYS: List<CatalogKeyEntry> = buildList {
        // Letters A-Z
        for (offset in 0 until 26) {
            val ch = ('A' + offset).toString()
            add(
                CatalogKeyEntry(
                    id = "kb_${ch.lowercase()}",
                    displayName = "Key $ch",
                    shortLabel = ch,
                    keyCode = KeyEvent.KEYCODE_A + offset,
                    inputType = KeyInputType.KEYBOARD,
                    category = KeyCategory.KEYBOARD,
                    aliases = listOf("letter", "keyboard", ch)
                )
            )
        }
        // Digits 0-9
        for (digit in 0..9) {
            add(
                CatalogKeyEntry(
                    id = "kb_$digit",
                    displayName = "Number $digit",
                    shortLabel = "$digit",
                    keyCode = KeyEvent.KEYCODE_0 + digit,
                    inputType = KeyInputType.KEYBOARD,
                    category = KeyCategory.KEYBOARD,
                    aliases = listOf("digit", "num", "$digit")
                )
            )
        }
        // Function keys F1-F12
        for (fn in 1..12) {
            add(
                CatalogKeyEntry(
                    id = "kb_f$fn",
                    displayName = "F$fn",
                    shortLabel = "F$fn",
                    keyCode = KeyEvent.KEYCODE_F1 + (fn - 1),
                    inputType = KeyInputType.KEYBOARD,
                    category = KeyCategory.KEYBOARD,
                    aliases = listOf("function", "f$fn")
                )
            )
        }
        // Modifiers, Control, Navigation & Editing Keys
        addAll(
            listOf(
                CatalogKeyEntry("kb_space", "Space", "Space", KeyEvent.KEYCODE_SPACE, KeyInputType.KEYBOARD, KeyCategory.KEYBOARD, listOf("spacebar", "jump")),
                CatalogKeyEntry("kb_enter", "Enter", "Enter", KeyEvent.KEYCODE_ENTER, KeyInputType.KEYBOARD, KeyCategory.KEYBOARD, listOf("return")),
                CatalogKeyEntry("kb_lshift", "Left Shift", "LShift", KeyEvent.KEYCODE_SHIFT_LEFT, KeyInputType.KEYBOARD, KeyCategory.KEYBOARD, listOf("shift", "sprint")),
                CatalogKeyEntry("kb_rshift", "Right Shift", "RShift", KeyEvent.KEYCODE_SHIFT_RIGHT, KeyInputType.KEYBOARD, KeyCategory.KEYBOARD, listOf("shift")),
                CatalogKeyEntry("kb_lctrl", "Left Ctrl", "LCtrl", KeyEvent.KEYCODE_CTRL_LEFT, KeyInputType.KEYBOARD, KeyCategory.KEYBOARD, listOf("ctrl", "control", "crouch")),
                CatalogKeyEntry("kb_rctrl", "Right Ctrl", "RCtrl", KeyEvent.KEYCODE_CTRL_RIGHT, KeyInputType.KEYBOARD, KeyCategory.KEYBOARD, listOf("ctrl", "control")),
                CatalogKeyEntry("kb_lalt", "Left Alt", "LAlt", KeyEvent.KEYCODE_ALT_LEFT, KeyInputType.KEYBOARD, KeyCategory.KEYBOARD, listOf("alt")),
                CatalogKeyEntry("kb_ralt", "Right Alt", "RAlt", KeyEvent.KEYCODE_ALT_RIGHT, KeyInputType.KEYBOARD, KeyCategory.KEYBOARD, listOf("alt")),
                CatalogKeyEntry("kb_tab", "Tab", "Tab", KeyEvent.KEYCODE_TAB, KeyInputType.KEYBOARD, KeyCategory.KEYBOARD, listOf("inventory", "map")),
                CatalogKeyEntry("kb_esc", "Esc", "Esc", KeyEvent.KEYCODE_ESCAPE, KeyInputType.KEYBOARD, KeyCategory.KEYBOARD, listOf("escape", "pause")),
                CatalogKeyEntry("kb_backspace", "Backspace", "Bksp", KeyEvent.KEYCODE_DEL, KeyInputType.KEYBOARD, KeyCategory.KEYBOARD, listOf("back", "erase")),
                CatalogKeyEntry("kb_capslock", "Caps Lock", "Caps", KeyEvent.KEYCODE_CAPS_LOCK, KeyInputType.KEYBOARD, KeyCategory.KEYBOARD, listOf("caps")),
                CatalogKeyEntry("kb_arrow_up", "Arrow Up", "Up", KeyEvent.KEYCODE_DPAD_UP, KeyInputType.KEYBOARD, KeyCategory.KEYBOARD, listOf("cursor", "arrow")),
                CatalogKeyEntry("kb_arrow_down", "Arrow Down", "Down", KeyEvent.KEYCODE_DPAD_DOWN, KeyInputType.KEYBOARD, KeyCategory.KEYBOARD, listOf("cursor", "arrow")),
                CatalogKeyEntry("kb_arrow_left", "Arrow Left", "Left", KeyEvent.KEYCODE_DPAD_LEFT, KeyInputType.KEYBOARD, KeyCategory.KEYBOARD, listOf("cursor", "arrow")),
                CatalogKeyEntry("kb_arrow_right", "Arrow Right", "Right", KeyEvent.KEYCODE_DPAD_RIGHT, KeyInputType.KEYBOARD, KeyCategory.KEYBOARD, listOf("cursor", "arrow")),
                CatalogKeyEntry("kb_insert", "Insert", "Ins", KeyEvent.KEYCODE_INSERT, KeyInputType.KEYBOARD, KeyCategory.KEYBOARD, listOf("ins")),
                CatalogKeyEntry("kb_delete", "Delete", "Del", KeyEvent.KEYCODE_FORWARD_DEL, KeyInputType.KEYBOARD, KeyCategory.KEYBOARD, listOf("del", "forward delete")),
                CatalogKeyEntry("kb_home", "Home", "Home", KeyEvent.KEYCODE_MOVE_HOME, KeyInputType.KEYBOARD, KeyCategory.KEYBOARD, listOf("home")),
                CatalogKeyEntry("kb_end", "End", "End", KeyEvent.KEYCODE_MOVE_END, KeyInputType.KEYBOARD, KeyCategory.KEYBOARD, listOf("end")),
                CatalogKeyEntry("kb_pgup", "Page Up", "PgUp", KeyEvent.KEYCODE_PAGE_UP, KeyInputType.KEYBOARD, KeyCategory.KEYBOARD, listOf("pageup")),
                CatalogKeyEntry("kb_pgdn", "Page Down", "PgDn", KeyEvent.KEYCODE_PAGE_DOWN, KeyInputType.KEYBOARD, KeyCategory.KEYBOARD, listOf("pagedown"))
            )
        )
    }

    val MOUSE_KEYS: List<CatalogKeyEntry> = listOf(
        CatalogKeyEntry("mouse_lmb", "Left Click (LMB)", "LMB", MotionEvent.BUTTON_PRIMARY, KeyInputType.MOUSE, KeyCategory.MOUSE, listOf("mouse", "click", "left", "fire", "shoot")),
        CatalogKeyEntry("mouse_rmb", "Right Click (RMB)", "RMB", MotionEvent.BUTTON_SECONDARY, KeyInputType.MOUSE, KeyCategory.MOUSE, listOf("mouse", "click", "right", "aim", "ads")),
        CatalogKeyEntry("mouse_mmb", "Middle Click (MMB)", "MMB", MotionEvent.BUTTON_TERTIARY, KeyInputType.MOUSE, KeyCategory.MOUSE, listOf("mouse", "click", "middle", "wheel"))
    )

    val ALL_KEYS: List<CatalogKeyEntry> = XBOX_KEYS + PLAYSTATION_KEYS + KEYBOARD_KEYS + MOUSE_KEYS

    fun search(query: String, category: KeyCategory): List<CatalogKeyEntry> {
        val pool = when (category) {
            KeyCategory.ALL -> ALL_KEYS
            KeyCategory.XBOX -> XBOX_KEYS
            KeyCategory.PLAYSTATION -> PLAYSTATION_KEYS
            KeyCategory.KEYBOARD -> KEYBOARD_KEYS
            KeyCategory.MOUSE -> MOUSE_KEYS
        }
        val clean = query.trim().lowercase()
        if (clean.isEmpty()) return pool
        return pool.filter { entry ->
            entry.displayName.lowercase().contains(clean) ||
                entry.shortLabel.lowercase().contains(clean) ||
                entry.inputType.name.lowercase().contains(clean) ||
                entry.aliases.any { it.lowercase().contains(clean) }
        }
    }

    fun shortLabelFor(
        boundKey: BoundKey,
        profile: ControllerProfile = ControllerProfile.XBOX
    ): String {
        val preferredList = when {
            boundKey.inputType == KeyInputType.KEYBOARD -> KEYBOARD_KEYS
            boundKey.inputType == KeyInputType.MOUSE -> MOUSE_KEYS
            boundKey.inputType == KeyInputType.HAT_AXIS -> XBOX_KEYS
            profile == ControllerProfile.PS4 -> PLAYSTATION_KEYS
            else -> XBOX_KEYS
        }
        val match = preferredList.firstOrNull {
            it.keyCode == boundKey.keyCode && it.inputType == boundKey.inputType
        } ?: ALL_KEYS.firstOrNull {
            it.keyCode == boundKey.keyCode && it.inputType == boundKey.inputType
        }
        return match?.shortLabel
            ?: KeyEvent.keyCodeToString(boundKey.keyCode).removePrefix("KEYCODE_")
    }

    fun formatComboSummary(
        boundKeys: List<BoundKey>,
        profile: ControllerProfile = ControllerProfile.XBOX
    ): String {
        if (boundKeys.isEmpty()) return "Unbound"
        return boundKeys.joinToString("+") { shortLabelFor(it, profile) }
    }
}
