package com.example.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * 8-way tactile floating D-Pad injecting hardware Hat Switch axes
 * (`MotionEvent.AXIS_HAT_X` and `MotionEvent.AXIS_HAT_Y` with `SOURCE_JOYSTICK`)
 * instead of `KEYCODE_DPAD_*`, preventing Android UI focus movement during emulator button mapping.
 */
class DpadView(context: Context) : View(context) {

    var hapticsEnabled: Boolean = true

    var editMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                releaseAllDirections()
                invalidate()
            }
        }

    var onHatChanged: ((hatX: Float, hatY: Float, label: String) -> Unit)? = null
    var onHapticFeedback: (() -> Unit)? = null
    var onDragDelta: ((dx: Int, dy: Int) -> Unit)? = null
    var onDragFinished: (() -> Unit)? = null

    private var activePointerId: Int = MotionEvent.INVALID_POINTER_ID
    private var upPressed = false
    private var downPressed = false
    private var leftPressed = false
    private var rightPressed = false
    private var lastHatX = 0f
    private var lastHatY = 0f

    private var dragLastRawX = 0f
    private var dragLastRawY = 0f

    private val upRect = RectF()
    private val downRect = RectF()
    private val leftRect = RectF()
    private val rightRect = RectF()
    private val centerRect = RectF()
    private val editRect = RectF()

    private val accentColor = Color.parseColor("#00E5C4")

    private val baseFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(165, 13, 19, 31)
    }

    private val activeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(215, 0, 229, 196)
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.8f)
        color = Color.argb(185, 148, 168, 200)
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val editHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
        color = Color.parseColor("#FF8A2B")
        pathEffect = DashPathEffect(floatArrayOf(dp(7f), dp(4f)), 0f)
    }

    private val arrowPath = Path()

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val pad = dp(6f)
        val size = min(w, h) - pad * 2f
        val left = (w - size) * 0.5f
        val top = (h - size) * 0.5f
        val arm = size / 3f

        upRect.set(left + arm, top, left + arm * 2f, top + arm)
        downRect.set(left + arm, top + arm * 2f, left + arm * 2f, top + size)
        leftRect.set(left, top + arm, left + arm, top + arm * 2f)
        rightRect.set(left + arm * 2f, top + arm, left + size, top + arm * 2f)
        centerRect.set(left + arm, top + arm, left + arm * 2f, top + arm * 2f)

        val ePad = dp(2f)
        editRect.set(ePad, ePad, w - ePad, h - ePad)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val corner = dp(6f)

        canvas.drawRect(centerRect, baseFillPaint)

        drawArm(canvas, upRect, corner, upPressed, 0f)
        drawArm(canvas, rightRect, corner, rightPressed, 90f)
        drawArm(canvas, downRect, corner, downPressed, 180f)
        drawArm(canvas, leftRect, corner, leftPressed, 270f)

        strokePaint.alpha = 110
        canvas.drawCircle(
            centerRect.centerX(),
            centerRect.centerY(),
            centerRect.width() * 0.22f,
            strokePaint
        )
        strokePaint.alpha = 185

        if (editMode) {
            canvas.drawRoundRect(editRect, dp(12f), dp(12f), editHaloPaint)
        }
    }

    private fun drawArm(canvas: Canvas, rect: RectF, corner: Float, active: Boolean, rotationDeg: Float) {
        canvas.drawRoundRect(rect, corner, corner, if (active) activeFillPaint else baseFillPaint)
        strokePaint.color = if (active) accentColor else Color.argb(185, 148, 168, 200)
        canvas.drawRoundRect(rect, corner, corner, strokePaint)

        arrowPaint.color = if (active) Color.parseColor("#0B0E14") else Color.argb(215, 224, 236, 255)
        val cx = rect.centerX()
        val cy = rect.centerY()
        val r = min(rect.width(), rect.height()) * 0.22f

        canvas.save()
        canvas.rotate(rotationDeg, cx, cy)
        arrowPath.reset()
        arrowPath.moveTo(cx, cy - r)
        arrowPath.lineTo(cx + r, cy + r * 0.7f)
        arrowPath.lineTo(cx - r, cy + r * 0.7f)
        arrowPath.close()
        canvas.drawPath(arrowPath, arrowPaint)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editMode) {
            return handleEditDrag(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) {
                    val idx = event.actionIndex
                    activePointerId = event.getPointerId(idx)
                    updateDirectionsFromPoint(event.getX(idx), event.getY(idx))
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val idx = event.findPointerIndex(activePointerId)
                if (idx != -1) {
                    updateDirectionsFromPoint(event.getX(idx), event.getY(idx))
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    releaseAllDirections()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                releaseAllDirections()
                return true
            }
        }
        return true
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

    private fun updateDirectionsFromPoint(x: Float, y: Float) {
        val cx = width * 0.5f
        val cy = height * 0.5f
        val dx = x - cx
        val dy = y - cy
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val deadRadius = min(width, height) * 0.11f

        if (dist < deadRadius) {
            applyHatDirections(up = false, down = false, left = false, right = false)
            return
        }

        // Angle in degrees [-180, 180], 0 = right, -90 = up, 90 = down
        val angleDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))

        val newRight = angleDeg in -67.5..67.5
        val newDown = angleDeg in 22.5..157.5
        val newLeft = angleDeg >= 112.5 || angleDeg <= -112.5
        val newUp = angleDeg in -157.5..-22.5

        applyHatDirections(up = newUp, down = newDown, left = newLeft, right = newRight)
    }

    private fun applyHatDirections(up: Boolean, down: Boolean, left: Boolean, right: Boolean) {
        val anyNewPress = (up && !upPressed) ||
            (down && !downPressed) ||
            (left && !leftPressed) ||
            (right && !rightPressed)

        val visualChanged = up != upPressed || down != downPressed || left != leftPressed || right != rightPressed

        upPressed = up
        downPressed = down
        leftPressed = left
        rightPressed = right

        val hatX = when {
            left -> -1.0f
            right -> 1.0f
            else -> 0.0f
        }
        val hatY = when {
            up -> -1.0f
            down -> 1.0f
            else -> 0.0f
        }

        if (hatX != lastHatX || hatY != lastHatY) {
            lastHatX = hatX
            lastHatY = hatY
            val dirLabel = buildString {
                if (up) append("UP")
                if (down) append("DOWN")
                if (left) {
                    if (isNotEmpty()) append("+")
                    append("LEFT")
                }
                if (right) {
                    if (isNotEmpty()) append("+")
                    append("RIGHT")
                }
                if (isEmpty()) append("CENTER")
            }
            onHatChanged?.invoke(hatX, hatY, dirLabel)
        }

        if (anyNewPress && hapticsEnabled) {
            onHapticFeedback?.invoke()
        }
        if (visualChanged) {
            invalidate()
        }
    }

    fun releaseAllDirections() {
        val hadActive = upPressed || downPressed || leftPressed || rightPressed || lastHatX != 0f || lastHatY != 0f
        activePointerId = MotionEvent.INVALID_POINTER_ID
        upPressed = false
        downPressed = false
        leftPressed = false
        rightPressed = false
        if (hadActive) {
            lastHatX = 0f
            lastHatY = 0f
            onHatChanged?.invoke(0.0f, 0.0f, "CENTER")
            invalidate()
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
