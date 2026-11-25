package com.example.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.example.BoundKey
import com.example.ButtonConfig
import com.example.ButtonConfigStore
import com.example.CatalogKeyEntry
import com.example.ControllerProfile
import com.example.KeyCatalog
import com.example.KeyCategory
import com.example.KeyInputType
import kotlin.math.roundToInt

/**
 * Overlay-safe Universal Key Picker & Button Customization Dialog rendered directly via
 * [WindowManager] (`TYPE_APPLICATION_OVERLAY`).
 *
 * Focus handling:
 * - While open, the overlay window omits `FLAG_NOT_FOCUSABLE` so the soft keyboard works smoothly
 *   for both the Label field and the real-time Key Search Bar, and Back key dismisses it.
 * - On Save, Cancel, or Delete, the view is immediately removed from [WindowManager], instantly
 *   returning input focus to the game underneath.
 */
class OverlayButtonConfigDialog private constructor(
    private val windowManager: WindowManager,
    private val rootOverlayView: View,
    private val onDismissed: () -> Unit
) {

    private var isDismissed = false

    fun dismiss() {
        if (isDismissed) return
        isDismissed = true
        runCatching { windowManager.removeView(rootOverlayView) }
        onDismissed()
    }

    companion object {
        fun show(
            context: Context,
            windowManager: WindowManager,
            config: ButtonConfig,
            profile: ControllerProfile,
            onPreviewSizeChanged: (sizeScale: Float) -> Unit,
            onSave: (updated: ButtonConfig) -> Unit,
            onDelete: ((configId: String) -> Unit)? = null,
            onCancel: () -> Unit = {}
        ): OverlayButtonConfigDialog {
            val density = context.resources.displayMetrics.density
            fun dp(v: Float): Int = (v * density).toInt()

            val selectedKeys = LinkedHashSet<BoundKey>(config.boundKeys.take(ButtonConfigStore.MAX_COMBO_KEYS))
            if (selectedKeys.isEmpty()) {
                selectedKeys.add(BoundKey(KeyEvent.KEYCODE_BUTTON_A, KeyInputType.GAMEPAD))
            }
            var currentSizeScale = config.sizeScale.coerceIn(
                ButtonConfigStore.MIN_BUTTON_SIZE_SCALE,
                ButtonConfigStore.MAX_BUTTON_SIZE_SCALE
            )
            var currentCategory = KeyCategory.ALL
            var currentQuery = ""
            var handle: OverlayButtonConfigDialog? = null

            // Full-screen translucent scrim intercepting Back key and outside tap
            val scrim = object : FrameLayout(context) {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                        onPreviewSizeChanged(config.sizeScale)
                        onCancel()
                        handle?.dismiss()
                        return true
                    }
                    return super.dispatchKeyEvent(event)
                }
            }.apply {
                setBackgroundColor(Color.argb(170, 5, 8, 14))
                isFocusable = true
                isFocusableInTouchMode = true
                setPadding(dp(16f), dp(24f), dp(16f), dp(24f))
            }

            // Main dark obsidian dialog card
            val screenWidthPx = context.resources.displayMetrics.widthPixels
            val maxCardWidth = minOf((screenWidthPx * 0.94f).toInt(), dp(480f))

            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#111722"))
                    setStroke(dp(1.2f), Color.parseColor("#2A3950"))
                    cornerRadius = dp(20f).toFloat()
                }
                isClickable = true
                layoutParams = FrameLayout.LayoutParams(
                    maxCardWidth,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            }

            // --- Header Row ---
            val headerRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val titleView = TextView(context).apply {
                text = if (config.isExtra) "Customize Extra Button" else "Customize Button (${config.label})"
                setTextColor(Color.parseColor("#EEF5FF"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                paint.isFakeBoldText = true
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val closeBtn = TextView(context).apply {
                text = "✕"
                setTextColor(Color.parseColor("#94A8C8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setPadding(dp(10f), dp(4f), dp(6f), dp(4f))
                setOnClickListener {
                    onPreviewSizeChanged(config.sizeScale)
                    onCancel()
                    handle?.dismiss()
                }
            }
            headerRow.addView(titleView)
            headerRow.addView(closeBtn)
            card.addView(headerRow)

            // --- Label Input Row ---
            val labelRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10f) }
            }
            val labelInput = EditText(context).apply {
                setText(config.label)
                hint = "Label (e.g. L1+R1, Shift)"
                setTextColor(Color.parseColor("#EEF5FF"))
                setHintTextColor(Color.parseColor("#64748B"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setSingleLine(true)
                filters = arrayOf(InputFilter.LengthFilter(10))
                setPadding(dp(12f), dp(9f), dp(12f), dp(9f))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1A2233"))
                    setStroke(dp(1f), Color.parseColor("#334155"))
                    cornerRadius = dp(10f).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val autoFillBtn = TextView(context).apply {
                text = "Auto-Fill"
                setTextColor(Color.parseColor("#00E5C4"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                paint.isFakeBoldText = true
                setPadding(dp(10f), dp(9f), dp(10f), dp(9f))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#172634"))
                    setStroke(dp(1f), Color.parseColor("#00E5C4"))
                    cornerRadius = dp(10f).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(8f) }
                setOnClickListener {
                    val textVal = KeyCatalog.formatComboSummary(selectedKeys.toList(), profile).take(10)
                    labelInput.setText(textVal)
                    labelInput.setSelection(textVal.length)
                }
            }
            labelRow.addView(labelInput)
            labelRow.addView(autoFillBtn)
            card.addView(labelRow)

            // --- Per-Button Size Slider ---
            val sizeRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10f) }
            }
            val sizeLabel = TextView(context).apply {
                text = "Size: ${(currentSizeScale * 100f).roundToInt()}%"
                setTextColor(Color.parseColor("#94A8C8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                paint.isFakeBoldText = true
                minWidth = dp(78f)
            }
            val sizeSeekBar = SeekBar(context).apply {
                max = 120 // 60% .. 180%
                progress = ((currentSizeScale - ButtonConfigStore.MIN_BUTTON_SIZE_SCALE) * 100f)
                    .roundToInt()
                    .coerceIn(0, 120)
                progressTintList = ColorStateList.valueOf(Color.parseColor("#00E5C4"))
                thumbTintList = ColorStateList.valueOf(Color.parseColor("#00E5C4"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        currentSizeScale = (ButtonConfigStore.MIN_BUTTON_SIZE_SCALE + progress / 100f)
                            .coerceIn(
                                ButtonConfigStore.MIN_BUTTON_SIZE_SCALE,
                                ButtonConfigStore.MAX_BUTTON_SIZE_SCALE
                            )
                        sizeLabel.text = "Size: ${(currentSizeScale * 100f).roundToInt()}%"
                        if (fromUser) {
                            onPreviewSizeChanged(currentSizeScale)
                        }
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                })
            }
            sizeRow.addView(sizeLabel)
            sizeRow.addView(sizeSeekBar)
            card.addView(sizeRow)

            // --- Selected Keys Live Preview Row ---
            val selectedHeader = TextView(context).apply {
                setTextColor(Color.parseColor("#00E5C4"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                paint.isFakeBoldText = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8f) }
            }
            card.addView(selectedHeader)

            val selectedChipsRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val selectedChipsScroll = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(selectedChipsRow)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4f) }
            }
            card.addView(selectedChipsScroll)

            // --- Real-Time Search Bar ---
            val searchRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10f) }
            }
            val searchInput = EditText(context).apply {
                hint = "Search keys (L1, Shift, Space, Triangle, F1, Click)…"
                setTextColor(Color.parseColor("#EEF5FF"))
                setHintTextColor(Color.parseColor("#64748B"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setSingleLine(true)
                setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1A2233"))
                    setStroke(dp(1f), Color.parseColor("#334155"))
                    cornerRadius = dp(10f).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val clearSearchBtn = TextView(context).apply {
                text = "Clear"
                setTextColor(Color.parseColor("#94A8C8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setPadding(dp(8f), dp(8f), dp(6f), dp(8f))
                visibility = View.GONE
                setOnClickListener { searchInput.setText("") }
            }
            searchRow.addView(searchInput)
            searchRow.addView(clearSearchBtn)
            card.addView(searchRow)

            // --- Category Filter Tabs (All | Xbox | PlayStation | Keyboard | Mouse) ---
            val tabsRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val tabsScroll = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(tabsRow)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8f) }
            }
            card.addView(tabsScroll)

            // --- Scrollable Key List ---
            val keyListContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            val screenHeightPx = context.resources.displayMetrics.heightPixels
            val listMaxHeight = minOf((screenHeightPx * 0.30f).toInt(), dp(230f)).coerceAtLeast(dp(135f))
            val keyListScroll = ScrollView(context).apply {
                addView(keyListContainer)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    listMaxHeight
                ).apply { topMargin = dp(8f) }
            }
            card.addView(keyListScroll)

            val checkBoxTint = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.parseColor("#00E5C4"), Color.parseColor("#94A8C8"))
            )

            lateinit var refreshSelectedPreview: () -> Unit
            lateinit var refreshKeyList: () -> Unit

            fun toggleBoundKey(boundKey: BoundKey) {
                if (selectedKeys.contains(boundKey)) {
                    if (selectedKeys.size <= 1) {
                        Toast.makeText(context, "Select at least 1 key", Toast.LENGTH_SHORT).show()
                        return
                    }
                    selectedKeys.remove(boundKey)
                } else {
                    if (selectedKeys.size >= ButtonConfigStore.MAX_COMBO_KEYS) {
                        Toast.makeText(
                            context,
                            "Maximum ${ButtonConfigStore.MAX_COMBO_KEYS} keys per combo",
                            Toast.LENGTH_SHORT
                        ).show()
                        return
                    }
                    selectedKeys.add(boundKey)
                }
                refreshSelectedPreview()
                refreshKeyList()
            }

            refreshSelectedPreview = {
                val list = selectedKeys.toList()
                selectedHeader.text =
                    "BOUND KEYS (${list.size}/${ButtonConfigStore.MAX_COMBO_KEYS}): ${KeyCatalog.formatComboSummary(list, profile)}"
                selectedChipsRow.removeAllViews()
                list.forEach { bk ->
                    val chipText = "${KeyCatalog.shortLabelFor(bk, profile)} (${bk.inputType.name.take(3)}) ×"
                    val chip = TextView(context).apply {
                        text = chipText
                        setTextColor(Color.parseColor("#0B0E14"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        paint.isFakeBoldText = true
                        setPadding(dp(9f), dp(4f), dp(9f), dp(4f))
                        background = GradientDrawable().apply {
                            setColor(
                                when (bk.inputType) {
                                    KeyInputType.GAMEPAD -> Color.parseColor("#00E5C4")
                                    KeyInputType.KEYBOARD -> Color.parseColor("#3B9EFF")
                                    KeyInputType.MOUSE -> Color.parseColor("#FF8A2B")
                                    KeyInputType.HAT_AXIS -> Color.parseColor("#C084FC")
                                }
                            )
                            cornerRadius = dp(12f).toFloat()
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { marginEnd = dp(6f) }
                        setOnClickListener { toggleBoundKey(bk) }
                    }
                    selectedChipsRow.addView(chip)
                }
            }

            val tabViews = LinkedHashMap<KeyCategory, TextView>()
            fun refreshTabStyles() {
                tabViews.forEach { (cat, tv) ->
                    val active = cat == currentCategory
                    tv.setTextColor(Color.parseColor(if (active) "#0B0E14" else "#CBD5E1"))
                    tv.background = GradientDrawable().apply {
                        setColor(Color.parseColor(if (active) "#00E5C4" else "#1A2233"))
                        setStroke(dp(1f), Color.parseColor(if (active) "#00E5C4" else "#2D3B50"))
                        cornerRadius = dp(14f).toFloat()
                    }
                }
            }

            KeyCategory.entries.forEach { category ->
                val tab = TextView(context).apply {
                    text = category.displayName
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    paint.isFakeBoldText = true
                    setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = dp(6f) }
                    setOnClickListener {
                        currentCategory = category
                        refreshTabStyles()
                        refreshKeyList()
                    }
                }
                tabViews[category] = tab
                tabsRow.addView(tab)
            }

            fun bindKeyRow(entry: CatalogKeyEntry): View {
                val bound = entry.toBoundKey()
                val checked = selectedKeys.contains(bound)

                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
                    background = GradientDrawable().apply {
                        setColor(if (checked) Color.parseColor("#182B36") else Color.TRANSPARENT)
                        cornerRadius = dp(8f).toFloat()
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(2f) }
                }

                val cb = CheckBox(context).apply {
                    isChecked = checked
                    isClickable = false
                    isFocusable = false
                    buttonTintList = checkBoxTint
                }

                val nameTv = TextView(context).apply {
                    text = entry.displayName
                    setTextColor(Color.parseColor("#EEF5FF"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp(4f)
                    }
                }

                val typeTag = TextView(context).apply {
                    text = when (entry.inputType) {
                        KeyInputType.GAMEPAD -> entry.category.displayName.uppercase()
                        KeyInputType.KEYBOARD -> "KEY"
                        KeyInputType.MOUSE -> "MOUSE"
                        KeyInputType.HAT_AXIS -> "HAT"
                    }
                    setTextColor(
                        when (entry.inputType) {
                            KeyInputType.GAMEPAD -> Color.parseColor("#00E5C4")
                            KeyInputType.KEYBOARD -> Color.parseColor("#3B9EFF")
                            KeyInputType.MOUSE -> Color.parseColor("#FF8A2B")
                            KeyInputType.HAT_AXIS -> Color.parseColor("#C084FC")
                        }
                    )
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    paint.isFakeBoldText = true
                    setPadding(dp(7f), dp(2f), dp(7f), dp(2f))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#1A2233"))
                        cornerRadius = dp(8f).toFloat()
                    }
                }

                row.addView(cb)
                row.addView(nameTv)
                row.addView(typeTag)
                row.setOnClickListener { toggleBoundKey(bound) }
                return row
            }

            refreshKeyList = {
                keyListContainer.removeAllViews()
                val matches = KeyCatalog.search(currentQuery, currentCategory)
                if (matches.isEmpty()) {
                    val emptyTv = TextView(context).apply {
                        text = "No matching keys for \"$currentQuery\""
                        setTextColor(Color.parseColor("#94A8C8"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        gravity = Gravity.CENTER
                        setPadding(dp(12f), dp(24f), dp(12f), dp(24f))
                    }
                    keyListContainer.addView(emptyTv)
                } else {
                    matches.forEach { entry ->
                        keyListContainer.addView(bindKeyRow(entry))
                    }
                }
            }

            searchInput.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    currentQuery = s?.toString().orEmpty()
                    clearSearchBtn.visibility = if (currentQuery.isNotEmpty()) View.VISIBLE else View.GONE
                    refreshKeyList()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })

            refreshTabStyles()
            refreshSelectedPreview()
            refreshKeyList()

            // --- Footer Buttons Row (Delete? | Cancel | Save) ---
            val footerRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(12f) }
            }

            fun makeFooterButton(textLabel: String, bgHex: String, fgHex: String, onClick: () -> Unit): TextView {
                return TextView(context).apply {
                    text = textLabel
                    setTextColor(Color.parseColor(fgHex))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    paint.isFakeBoldText = true
                    gravity = Gravity.CENTER
                    setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor(bgHex))
                        cornerRadius = dp(12f).toFloat()
                    }
                    setOnClickListener { onClick() }
                }
            }

            if (config.isExtra && onDelete != null) {
                val deleteBtn = makeFooterButton("Delete", "#3A1822", "#FF5C77") {
                    onDelete(config.id)
                    handle?.dismiss()
                }
                footerRow.addView(deleteBtn)
            }

            val spacer = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            }
            footerRow.addView(spacer)

            val cancelBtn = makeFooterButton("Cancel", "#1F293B", "#CBD5E1") {
                onPreviewSizeChanged(config.sizeScale)
                onCancel()
                handle?.dismiss()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8f) }
            }

            val saveBtn = makeFooterButton("Save", "#00E5C4", "#0B0E14") {
                val keys = selectedKeys.toList().ifEmpty {
                    listOf(BoundKey(KeyEvent.KEYCODE_BUTTON_A, KeyInputType.GAMEPAD))
                }
                val finalLabel = labelInput.text.toString().trim()
                    .ifBlank { KeyCatalog.formatComboSummary(keys, profile).take(10) }
                onSave(
                    config.copy(
                        label = finalLabel,
                        boundKeys = keys,
                        sizeScale = currentSizeScale
                    )
                )
                handle?.dismiss()
            }

            footerRow.addView(cancelBtn)
            footerRow.addView(saveBtn)
            card.addView(footerRow)

            scrim.addView(card)
            scrim.setOnClickListener {
                onPreviewSizeChanged(config.sizeScale)
                onCancel()
                handle?.dismiss()
            }

            // CRITICAL: Omit FLAG_NOT_FOCUSABLE so EditText search & label receive IME keyboard focus.
            // Removing this view on dismiss immediately returns focus back to the game.
            val overlayParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
                dimAmount = 0.55f
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            val created = OverlayButtonConfigDialog(
                windowManager = windowManager,
                rootOverlayView = scrim,
                onDismissed = {}
            )
            handle = created
            windowManager.addView(scrim, overlayParams)
            return created
        }
    }
}
