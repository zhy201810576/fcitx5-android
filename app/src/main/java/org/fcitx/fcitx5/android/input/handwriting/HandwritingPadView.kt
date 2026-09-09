package org.fcitx.fcitx5.android.input.handwriting

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.createBitmap
import org.fcitx.fcitx5.android.memeboard.MemeBoardPrefs
import java.util.LinkedList
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 手写板：采集笔画坐标点（x,y 成对，每笔以 -1,0 分隔），并实时绘制笔迹。
 *
 * 采用「落笔即点、移动即线」的即时反馈绘制，避免传统贝塞尔拟合在前几个
 * 采样点不落笔的迟滞问题；笔迹宽度随书写速度变化（慢粗快细）。
 * 虚线边框标出可书写范围，空板时在中央显示「在此书写」提示。
 */
class HandwritingPadView(context: Context) : View(context) {

    var onStrokeFinished: ((IntArray) -> Unit)? = null

    var strokeColor: Int = Color.BLACK
        set(value) {
            field = value
            paint.color = value
            borderPaint.color = value
            hintPaint.color = value
            hintPaint.alpha = 96
        }

    private val strokePoints: MutableList<Short> = LinkedList()

    private var lastPoint: TimedPoint? = null
    private var lastVelocity = 0f
    private var lastWidth = 0f
    private var hasInk = false

    private var maxWidth = dp(14f).toFloat()
    private var minWidth = maxWidth / 3f
    private var touchSlop = 0.8f
    private val velocityFilterWeight = 0.6f

    private val paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val borderPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f).toFloat()
        pathEffect = DashPathEffect(floatArrayOf(dp(14f).toFloat(), dp(9f).toFloat()), 0f)
    }

    private val hintPaint = Paint().apply {
        isAntiAlias = true
        textSize = dp(17f).toFloat()
        textAlign = Paint.Align.CENTER
        alpha = 96
    }

    private val borderRect = RectF()

    private var bitmap: Bitmap? = null
    private var bitmapCanvas: Canvas? = null

    init {
        clear()
    }

    /** 清空笔迹与笔画缓存。 */
    fun clear() {
        strokePoints.clear()
        lastPoint = null
        lastVelocity = 0f
        lastWidth = minWidth
        hasInk = false
        bitmap = null
        invalidate()
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        strokePoints.add(x.toInt().toShort())
        strokePoints.add(y.toInt().toShort())
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                beginStroke(x, y)
            }
            MotionEvent.ACTION_MOVE -> extendStroke(x, y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                extendStroke(x, y)
                parent?.requestDisallowInterceptTouchEvent(false)
                strokePoints.add((-1).toShort())
                strokePoints.add(0.toShort())
                val arr = IntArray(strokePoints.size)
                strokePoints.forEachIndexed { i, v -> arr[i] = v.toInt() }
                onStrokeFinished?.invoke(arr)
            }
            else -> return false
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        val inset = borderPaint.strokeWidth / 2f
        val radius = dp(12f).toFloat()
        borderRect.set(inset, inset, width - inset, height - inset)
        canvas.drawRoundRect(borderRect, radius, radius, borderPaint)
        if (!hasInk) {
            val baseline = height / 2f - (hintPaint.ascent() + hintPaint.descent()) / 2f
            canvas.drawText("在此书写", width / 2f, baseline, hintPaint)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && (bitmap == null || bitmap!!.width != w || bitmap!!.height != h)) {
            bitmap = createBitmap(w, h)
            bitmapCanvas = Canvas(bitmap!!)
        }
    }

    private fun beginStroke(x: Float, y: Float) {
        refreshPrefs()
        lastPoint = TimedPoint().set(x, y)
        lastVelocity = 0f
        lastWidth = minWidth
        hasInk = true
        drawDot(x, y, minWidth)
        invalidate()
    }

    private fun extendStroke(x: Float, y: Float) {
        val prev = lastPoint ?: return
        val cur = TimedPoint().set(x, y)
        if (cur.distanceTo(prev) < touchSlop) return // 灵敏度：忽略几乎不动的采样点
        var velocity = cur.velocityFrom(prev)
        if (velocity.isNaN() || velocity.isInfinite()) velocity = 0f
        velocity = velocityFilterWeight * velocity + (1f - velocityFilterWeight) * lastVelocity
        val width = strokeWidth(velocity)
        drawLine(prev.x, prev.y, cur.x, cur.y, width)
        lastVelocity = velocity
        lastWidth = width
        lastPoint = cur
        invalidate()
    }

    private fun refreshPrefs() {
        val strokeDp = MemeBoardPrefs.getStrokeWidth(context)
        maxWidth = dp(strokeDp.toFloat()).toFloat()
        minWidth = maxWidth / 3f
        touchSlop = when (MemeBoardPrefs.getSensitivity(context)) {
            1 -> 1.6f
            2 -> 1.2f
            3 -> 0.8f
            4 -> 0.5f
            else -> 0.3f
        }
    }

    private fun drawDot(x: Float, y: Float, width: Float) {
        ensureBitmap()
        val old = paint.strokeWidth
        paint.strokeWidth = width
        bitmapCanvas?.drawPoint(x, y, paint)
        paint.strokeWidth = old
    }

    private fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float, width: Float) {
        ensureBitmap()
        val old = paint.strokeWidth
        paint.strokeWidth = width
        bitmapCanvas?.drawLine(x1, y1, x2, y2, paint)
        paint.strokeWidth = old
    }

    private fun strokeWidth(velocity: Float): Float {
        return max(maxWidth / (velocity + 1f), minWidth)
    }

    private fun ensureBitmap() {
        if (bitmap == null && width > 0 && height > 0) {
            bitmap = createBitmap(width, height)
            bitmapCanvas = Canvas(bitmap!!)
        }
    }

    private fun dp(value: Float): Int {
        return (context.resources.displayMetrics.density * value).roundToInt()
    }
}

private class TimedPoint {
    var x: Float = 0f
    var y: Float = 0f
    var timestamp: Long = 0L

    fun set(x: Float, y: Float): TimedPoint {
        this.x = x
        this.y = y
        this.timestamp = System.currentTimeMillis()
        return this
    }

    fun velocityFrom(start: TimedPoint): Float {
        var diff = timestamp - start.timestamp
        if (diff <= 0) diff = 1
        var velocity = distanceTo(start) / diff
        if (velocity.isInfinite() || velocity.isNaN()) velocity = 0f
        return velocity
    }

    fun distanceTo(point: TimedPoint): Float {
        val dx = point.x - x
        val dy = point.y - y
        return sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }
}
