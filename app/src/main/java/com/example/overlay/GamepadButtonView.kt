package com.example.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

enum class ButtonShape {
    CIRCLE,
    PILL,
    CAPSULE
}

/**
 * Independent multi-touch virtual hardware gamepad button View (default or custom extra).
 * Tracks its own pointer ID for simultaneous chords, displays a subtle multi-key badge when
 * bound to a combo, and distinguishes tap (configure) vs drag (reposition) in Edit Mode.
 */
class GamepadButtonView(
    context: Context,
    initialLabel: String,
    val accentColor: Int,
    val shape: ButtonShape = ButtonShape.CIRCLE
) : View(context) {

    var label: String = initialLabel
        set(value) {
            if (field != value) {
                field = value
                recalculateTextSize(width, height)
                invalidate()
            }
        }

    var comboKeyCount: Int = 1
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var hapticsEnabled: Boolean = true

    var editMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                releaseIfPressed()
                invalidate()
            }
        }

    var onPressStateChanged: ((pressed: Boolean) -> Unit)? = null
    var onHapticFeedback: (() -> Unit)? = null
    var onDragDelta: ((dx: Int, dy: Int) -> Unit)? = null
    var onDragFinished: (() -> Unit)? = null
    var onEditTap: (() -> Unit)? = null

    private var activePointerId: Int = MotionEvent.INVALID_POINTER_ID
    private var isButtonPressed: Boolean = false

    private var dragStartRawX: Float = 0f
    private var dragStartRawY: Float = 0f
    private var dragLastRawX: Float = 0f
    private var dragLastRawY: Float = 0f
    private var hasExceededDragSlop: Boolean = false

    private val boundsRect = RectF()
    private val editRect = RectF()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val badgeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FF8A2B")
    }

    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        color = Color.parseColor("#0B0E14")
        textSize = dp(8.5f)
    }

    private val editHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
        color = Color.parseColor("#FF8A2B")
        pathEffect = DashPathEffect(floatArrayOf(dp(7f), dp(4f)), 0f)
    }

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = dp(5f)
        boundsRect.set(inset, inset, w - inset, h - inset)
        val editInset = dp(2f)
        editRect.set(editInset, editInset, w - editInset, h - editInset)
        recalculateTextSize(w, h)
    }

    private fun recalculateTextSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val baseSize = when (shape) {
            ButtonShape.CIRCLE -> min(w, h) * 0.36f
            ButtonShape.PILL -> h * 0.38f
            ButtonShape.CAPSULE -> h * 0.34f
        }
        textPaint.textSize = baseSize
        val maxTextWidth = (w - dp(18f)).coerceAtLeast(dp(14f))
        val measured = textPaint.measureText(label)
        if (measured > maxTextWidth && measured > 0f) {
            textPaint.textSize = (baseSize * (maxTextWidth / measured)).coerceAtLeast(dp(9f))
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val scaleFactor = if (isButtonPressed) 0.93f else 1.0f
        canvas.save()
        canvas.scale(scaleFactor, scaleFactor, width * 0.5f, height * 0.5f)

        fillPaint.color = if (isButtonPressed) {
            Color.argb(
                210,
                Color.red(accentColor),
                Color.green(accentColor),
                Color.blue(accentColor)
            )
        } else {
            Color.argb(165, 13, 19, 31)
        }

        strokePaint.color = accentColor
        strokePaint.alpha = if (isButtonPressed) 255 else 190

        textPaint.color = if (isButtonPressed) {
            Color.parseColor("#0B0E14")
        } else {
            Color.argb(240, 238, 245, 255)
        }

        when (shape) {
            ButtonShape.CIRCLE -> {
                val radius = min(boundsRect.width(), boundsRect.height()) * 0.5f
                canvas.drawCircle(width * 0.5f, height * 0.5f, radius, fillPaint)
                canvas.drawCircle(width * 0.5f, height * 0.5f, radius, strokePaint)
            }
            ButtonShape.PILL -> {
                val corner = boundsRect.height() * 0.38f
                canvas.drawRoundRect(boundsRect, corner, corner, fillPaint)
                canvas.drawRoundRect(boundsRect, corner, corner, strokePaint)
            }
            ButtonShape.CAPSULE -> {
                val corner = boundsRect.height() * 0.5f
                canvas.drawRoundRect(boundsRect, corner, corner, fillPaint)
                canvas.drawRoundRect(boundsRect, corner, corner, strokePaint)
            }
        }

        val textY = height * 0.5f - (textPaint.descent() + textPaint.ascent()) * 0.5f
        canvas.drawText(label, width * 0.5f, textY, textPaint)

        // Subtle multi-key combo badge ("x2".."x4") in top-right corner
        if (comboKeyCount > 1) {
            val badgeR = dp(7.5f)
            val bx = width - badgeR - dp(3f)
            val by = badgeR + dp(3f)
            canvas.drawCircle(bx, by, badgeR, badgeFillPaint)
            val bTextY = by - (badgeTextPaint.descent() + badgeTextPaint.ascent()) * 0.5f
            canvas.drawText("x$comboKeyCount", bx, bTextY, badgeTextPaint)
        }

        canvas.restore()

        if (editMode) {
            val corner = when (shape) {
                ButtonShape.CIRCLE -> min(editRect.width(), editRect.height()) * 0.5f
                ButtonShape.PILL -> editRect.height() * 0.42f
                ButtonShape.CAPSULE -> editRect.height() * 0.5f
            }
            canvas.drawRoundRect(editRect, corner, corner, editHaloPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editMode) {
            return handleEditTouch(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) {
                    activePointerId = event.getPointerId(event.actionIndex)
                    setPressedState(true)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    setPressedState(false)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
                setPressedState(false)
                return true
            }
        }
        return true
    }

    private fun handleEditTouch(event: MotionEvent): Boolean {
        val touchSlopPx = dp(8f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                dragLastRawX = event.rawX
                dragLastRawY = event.rawY
                hasExceededDragSlop = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!hasExceededDragSlop) {
                    val dist = hypot(
                        (event.rawX - dragStartRawX).toDouble(),
                        (event.rawY - dragStartRawY).toDouble()
                    ).toFloat()
                    if (dist >= touchSlopPx) {
                        hasExceededDragSlop = true
                    }
                }
                if (hasExceededDragSlop) {
                    val dx = (event.rawX - dragLastRawX).toInt()
                    val dy = (event.rawY - dragLastRawY).toInt()
                    if (dx != 0 || dy != 0) {
                        dragLastRawX += dx
                        dragLastRawY += dy
                        onDragDelta?.invoke(dx, dy)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (hasExceededDragSlop) {
                    onDragFinished?.invoke()
                } else {
                    onEditTap?.invoke()
                }
                hasExceededDragSlop = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (hasExceededDragSlop) {
                    onDragFinished?.invoke()
                }
                hasExceededDragSlop = false
                return true
            }
        }
        return true
    }

    private fun setPressedState(pressed: Boolean) {
        if (isButtonPressed != pressed) {
            isButtonPressed = pressed
            if (pressed && hapticsEnabled) {
                onHapticFeedback?.invoke()
            }
            onPressStateChanged?.invoke(pressed)
            invalidate()
        }
    }

    fun releaseIfPressed() {
        activePointerId = MotionEvent.INVALID_POINTER_ID
        setPressedState(false)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
