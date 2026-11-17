package com.traceview.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Transparent layer drawn on top of the live camera preview.
 *
 * It has exactly the same size and position as the PreviewView, so both layers share one
 * viewport / coordinate system. The reference bitmap is drawn through a single Matrix:
 *   centre on origin → scale (+flip) → rotate → move to view centre + offset.
 * Offsets are stored as fractions of the view size so saved positions survive size changes.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class State(
        var tx: Float = 0f,      // horizontal offset, fraction of view width
        var ty: Float = 0f,      // vertical offset, fraction of view height
        var scale: Float = 1f,   // 1 = image fitted inside the screen at 90%
        var rot: Float = 0f,     // degrees, -180..180
        var flipH: Boolean = false,
        var flipV: Boolean = false
    )

    private val density = resources.displayMetrics.density

    var state = State()
        private set

    var bitmap: Bitmap? = null
        private set

    /** 0.1 … 1.0 */
    var opacity: Float = 0.5f
        set(v) {
            field = v.coerceIn(MIN_OPACITY, 1f)
            bmpPaint.alpha = (field * 255).roundToInt()
            invalidate()
        }

    /** When locked, gestures no longer move the image (taps still focus the camera). */
    var locked = false

    /** 0 = off, 1 = large squares, 2 = small squares */
    var gridMode = 0
        set(v) {
            field = v
            invalidate()
        }

    var onChanged: (() -> Unit)? = null
    var onTap: ((Float, Float) -> Unit)? = null

    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = 128 }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF
        strokeWidth = density
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFF6B4A.toInt()
        strokeWidth = 1.5f * density
    }
    private val drawMatrix = Matrix()

    // ---- public API -------------------------------------------------------------------------

    fun setImage(b: Bitmap?, reset: Boolean) {
        bitmap = b
        if (reset) state = State()
        changed()
    }

    fun applyState(s: State) {
        state = s.copy()
        invalidate()
    }

    fun zoomBy(f: Float) {
        state.scale = (state.scale * f).coerceIn(MIN_SCALE, MAX_SCALE)
        changed()
    }

    fun rotateBy(deg: Float) {
        state.rot = normalize(state.rot + deg)
        changed()
    }

    fun moveBy(dxPx: Float, dyPx: Float) {
        if (width == 0 || height == 0) return
        state.tx += dxPx / width
        state.ty += dyPx / height
        changed()
    }

    fun flipHorizontal() {
        state.flipH = !state.flipH
        changed()
    }

    fun flipVertical() {
        state.flipV = !state.flipV
        changed()
    }

    fun resetTransform() {
        state = State()
        changed()
    }

    /** Fit the (possibly rotated) image to fill the screen, which is where the paper is. */
    fun fitToScreen() {
        val b = bitmap ?: return
        if (width == 0 || height == 0) return
        val r = Math.toRadians(state.rot.toDouble())
        val bs = baseScale().toDouble()
        val w = b.width * bs
        val h = b.height * bs
        val bw = abs(w * cos(r)) + abs(h * sin(r))
        val bh = abs(w * sin(r)) + abs(h * cos(r))
        if (bw <= 0.0 || bh <= 0.0) return
        state.scale = min(width * 0.96 / bw, height * 0.96 / bh).toFloat().coerceIn(MIN_SCALE, MAX_SCALE)
        state.tx = 0f
        state.ty = 0f
        changed()
    }

    // ---- drawing ----------------------------------------------------------------------------

    private fun baseScale(): Float {
        val b = bitmap ?: return 1f
        if (width == 0 || height == 0) return 1f
        return min(width.toFloat() / b.width, height.toFloat() / b.height) * 0.9f
    }

    private fun buildMatrix(b: Bitmap) {
        val s = baseScale() * state.scale
        drawMatrix.reset()
        drawMatrix.postTranslate(-b.width / 2f, -b.height / 2f)
        drawMatrix.postScale(if (state.flipH) -s else s, if (state.flipV) -s else s)
        drawMatrix.postRotate(state.rot)
        drawMatrix.postTranslate(width / 2f + state.tx * width, height / 2f + state.ty * height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = bitmap
        if (b != null && !b.isRecycled) {
            buildMatrix(b)
            canvas.drawBitmap(b, drawMatrix, bmpPaint)
        }
        if (gridMode > 0) drawGrid(canvas)
    }

    private fun drawGrid(c: Canvas) {
        val step = (if (gridMode == 1) 64f else 32f) * density
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        var x = cx - floor(cx / step) * step
        while (x <= w) {
            c.drawLine(x, 0f, x, h, gridPaint)
            x += step
        }
        var y = cy - floor(cy / step) * step
        while (y <= h) {
            c.drawLine(0f, y, w, y, gridPaint)
            y += step
        }
        c.drawLine(cx, 0f, cx, h, centerPaint)
        c.drawLine(0f, cy, w, cy, centerPaint)
    }

    // ---- gestures: 1 finger = move, 2 fingers = pinch-zoom + twist-rotate + move -------------

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var start = State()
    private var sx0 = 0f
    private var sy0 = 0f
    private var d0 = 1f
    private var a0 = 0f
    private var mx0 = 0f
    private var my0 = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                moved = false
                rebase(e, -1)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                moved = true
                rebase(e, -1)
            }
            MotionEvent.ACTION_POINTER_UP -> rebase(e, e.actionIndex)
            MotionEvent.ACTION_MOVE -> {
                if (!moved && (abs(e.x - downX) > touchSlop || abs(e.y - downY) > touchSlop)) moved = true
                if (!moved || locked || bitmap == null || width == 0 || height == 0) return true
                if (e.pointerCount >= 2) {
                    val x0 = e.getX(0)
                    val y0 = e.getY(0)
                    val x1 = e.getX(1)
                    val y1 = e.getY(1)
                    val d = max(1f, hypot(x1 - x0, y1 - y0))
                    val a = atan2(y1 - y0, x1 - x0)
                    state.scale = (start.scale * d / d0).coerceIn(MIN_SCALE, MAX_SCALE)
                    state.rot = normalize(start.rot + Math.toDegrees((a - a0).toDouble()).toFloat())
                    state.tx = start.tx + ((x0 + x1) / 2f - mx0) / width
                    state.ty = start.ty + ((y0 + y1) / 2f - my0) / height
                } else {
                    state.tx = start.tx + (e.getX(0) - sx0) / width
                    state.ty = start.ty + (e.getY(0) - sy0) / height
                }
                changed()
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) {
                    performClick()
                    onTap?.invoke(e.x, e.y)
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** Snapshot the state and finger positions so gestures continue smoothly as fingers change. */
    private fun rebase(e: MotionEvent, skipIndex: Int) {
        start = state.copy()
        val idx = (0 until e.pointerCount).filter { it != skipIndex }
        if (idx.isEmpty()) return
        sx0 = e.getX(idx[0])
        sy0 = e.getY(idx[0])
        if (idx.size >= 2) {
            val x1 = e.getX(idx[1])
            val y1 = e.getY(idx[1])
            d0 = max(1f, hypot(x1 - sx0, y1 - sy0))
            a0 = atan2(y1 - sy0, x1 - sx0)
            mx0 = (sx0 + x1) / 2f
            my0 = (sy0 + y1) / 2f
        }
    }

    private fun changed() {
        invalidate()
        onChanged?.invoke()
    }

    private fun normalize(a: Float): Float {
        var v = a % 360f
        if (v > 180f) v -= 360f
        if (v < -180f) v += 360f
        return v
    }

    companion object {
        const val MIN_OPACITY = 0.1f
        const val MIN_SCALE = 0.05f
        const val MAX_SCALE = 20f
    }
}
