package com.example

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Zero-lag hardware-accelerated analog thumbstick overlay View.
 * Computes smooth normalized X and Y coordinates in [-1.0f, 1.0f] with radial deadzone,
 * circular clamping, independent pointer ID tracking, and instant snap-back to (0, 0) on release.
 */
class JoystickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var label: String = "L"
        set(value) {
            field = value
            invalidate()
        }

    var accentColor: Int = Color.parseColor("#00E5C4")
        set(value) {
            field = value
            accentStrokePaint.color = value
            thumbRingPaint.color = value
            vectorPaint.color = value
            invalidate()
        }

    var deadzone: Float = 0.06f
    var hapticsEnabled: Boolean = true

    var editMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                resetStick()
                invalidate()
            }
        }

    var onJoystickMove: ((x: Float, y: Float) -> Unit)? = null
    var onHapticFeedback: (() -> Unit)? = null
    var onDragDelta: ((dx: Int, dy: Int) -> Unit)? = null
    var onDragFinished: (() -> Unit)? = null

    private var activePointerId: Int = MotionEvent.INVALID_POINTER_ID
    private var centerX: Float = 0f
    private var centerY: Float = 0f
    private var baseRadius: Float = 0f
    private var thumbRadius: Float = 0f
    private var maxTravelRadius: Float = 0f

    private var thumbX: Float = 0f
    private var thumbY: Float = 0f
    private var lastSentX: Float = 0f
    private var lastSentY: Float = 0f
    private var isEngaged: Boolean = false

    // Raw-coordinate drag tracking for jitter-free Edit Mode repositioning
    private var dragLastRawX: Float = 0f
    private var dragLastRawY: Float = 0f

    private val outerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(135, 11, 16, 26)
    }

    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.argb(175, 148, 168, 200)
    }

    private val accentStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
        color = accentColor
    }

    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.argb(65, 180, 200, 230)
    }

    private val vectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
        color = accentColor
        alpha = 185
    }

    private val thumbFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val thumbRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.4f)
        color = accentColor
    }

    private val thumbInnerCapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        color = Color.argb(110, 255, 255, 255)
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(215, 236, 244, 255)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val editHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        color = Color.parseColor("#FF8A2B")
        pathEffect = DashPathEffect(floatArrayOf(dp(8f), dp(5f)), 0f)
    }

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w * 0.5f
        centerY = h * 0.5f
        val minDim = min(w, h) * 0.5f
        baseRadius = (minDim - dp(6f)).coerceAtLeast(dp(20f))
        thumbRadius = baseRadius * 0.42f
        maxTravelRadius = (baseRadius - thumbRadius * 0.55f).coerceAtLeast(1f)
        thumbX = centerX
        thumbY = centerY
        labelPaint.textSize = thumbRadius * 0.52f
        updateThumbShader()
    }

    private fun updateThumbShader() {
        val highlight = if (isEngaged) Color.argb(235, 32, 58, 72) else Color.argb(225, 22, 31, 46)
        val shadow = Color.argb(235, 10, 14, 22)
        thumbFillPaint.shader = RadialGradient(
            thumbX,
            thumbY,
            thumbRadius.coerceAtLeast(1f),
            highlight,
            shadow,
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Outer translucent obsidian well
        canvas.drawCircle(centerX, centerY, baseRadius, outerFillPaint)

        // Subtle alignment crosshairs
        val tickInner = baseRadius * 0.25f
        val tickOuter = baseRadius * 0.78f
        canvas.drawLine(centerX - tickOuter, centerY, centerX - tickInner, centerY, crosshairPaint)
        canvas.drawLine(centerX + tickInner, centerY, centerX + tickOuter, centerY, crosshairPaint)
        canvas.drawLine(centerX, centerY - tickOuter, centerX, centerY - tickInner, crosshairPaint)
        canvas.drawLine(centerX, centerY + tickInner, centerX, centerY + tickOuter, crosshairPaint)

        // Outer ring (highlights in accent color while engaged)
        if (isEngaged) {
            accentStrokePaint.alpha = 230
            canvas.drawCircle(centerX, centerY, baseRadius, accentStrokePaint)
            canvas.drawLine(centerX, centerY, thumbX, thumbY, vectorPaint)
        } else {
            canvas.drawCircle(centerX, centerY, baseRadius, outerRingPaint)
        }

        // Thumb cap
        updateThumbShader()
        canvas.drawCircle(thumbX, thumbY, thumbRadius, thumbFillPaint)
        thumbRingPaint.alpha = if (isEngaged) 255 else 155
        canvas.drawCircle(thumbX, thumbY, thumbRadius, thumbRingPaint)
        canvas.drawCircle(thumbX, thumbY, thumbRadius * 0.68f, thumbInnerCapPaint)

        // Center stick label (L / R)
        val baselineY = thumbY - (labelPaint.descent() + labelPaint.ascent()) * 0.5f
        canvas.drawText(label, thumbX, baselineY, labelPaint)

        // Dashed amber halo in Edit Mode
        if (editMode) {
            canvas.drawCircle(centerX, centerY, baseRadius + dp(3f), editHaloPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editMode) {
            return handleEditDrag(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) {
                    val index = event.actionIndex
                    activePointerId = event.getPointerId(index)
                    isEngaged = true
                    if (hapticsEnabled) {
                        onHapticFeedback?.invoke()
                    }
                    updateStickFromCoordinates(event.getX(index), event.getY(index))
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(activePointerId)
                if (index != -1) {
                    updateStickFromCoordinates(event.getX(index), event.getY(index))
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pointerId = event.getPointerId(event.actionIndex)
                if (pointerId == activePointerId) {
                    resetStick()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                resetStick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleEditDrag(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragLastRawX = event.rawX
                dragLastRawY = event.rawY
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - dragLastRawX).toInt()
                val dy = (event.rawY - dragLastRawY).toInt()
                if (dx != 0 || dy != 0) {
                    dragLastRawX += dx
                    dragLastRawY += dy
                    onDragDelta?.invoke(dx, dy)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                onDragFinished?.invoke()
                return true
            }
        }
        return true
    }

    private fun updateStickFromCoordinates(touchX: Float, touchY: Float) {
        val rawDx = touchX - centerX
        val rawDy = touchY - centerY
        val dist = hypot(rawDx.toDouble(), rawDy.toDouble()).toFloat()

        val clampedDist = min(dist, maxTravelRadius)
        val scale = if (dist > 0.0001f) clampedDist / dist else 0f

        thumbX = centerX + rawDx * scale
        thumbY = centerY + rawDy * scale

        val normalizedMagnitude = (clampedDist / maxTravelRadius).coerceIn(0f, 1f)
        val (normX, normY) = if (normalizedMagnitude < deadzone) {
            0f to 0f
        } else {
            val rescaled = ((normalizedMagnitude - deadzone) / (1f - deadzone)).coerceIn(0f, 1f)
            val ux = if (dist > 0.0001f) rawDx / dist else 0f
            val uy = if (dist > 0.0001f) rawDy / dist else 0f
            (ux * rescaled).coerceIn(-1f, 1f) to (uy * rescaled).coerceIn(-1f, 1f)
        }

        if (abs(normX - lastSentX) >= DELTA_THRESHOLD ||
            abs(normY - lastSentY) >= DELTA_THRESHOLD ||
            (normX == 0f && normY == 0f && (lastSentX != 0f || lastSentY != 0f))
        ) {
            lastSentX = normX
            lastSentY = normY
            onJoystickMove?.invoke(normX, normY)
        }
        invalidate()
    }

    fun resetStick() {
        val wasActive = isEngaged || lastSentX != 0f || lastSentY != 0f
        activePointerId = MotionEvent.INVALID_POINTER_ID
        isEngaged = false
        thumbX = centerX
        thumbY = centerY
        lastSentX = 0f
        lastSentY = 0f
        if (wasActive) {
            onJoystickMove?.invoke(0f, 0f)
        }
        invalidate()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        private const val DELTA_THRESHOLD = 0.015f
    }
}
