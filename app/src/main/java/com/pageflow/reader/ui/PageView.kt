package com.pageflow.reader.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.pageflow.reader.data.CropRect
import com.pageflow.reader.data.Highlight
import com.pageflow.reader.data.InkStroke
import com.pageflow.reader.data.TextNote
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 显示一页 PDF。
 *
 * - 位图是"整页渲染"，绘制时按 [src] 裁出正文区域填满控件
 * - 颜色模式通过 ColorFilter 在绘制阶段完成
 * - 普通模式：长按拖动 = 框选高亮
 * - 手写模式（[penMode]）：手指按下即画，松手成一条笔迹
 */
class PageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var pageIndex: Int = -1

    /** 高/宽 */
    var aspect: Float = 1.4142f
        set(value) {
            val v = value.coerceIn(0.2f, 6f)
            if (abs(field - v) > 0.0005f) {
                field = v
                if (isLaidOut) requestLayout() else invalidate()
            }
        }

    var bitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    var src: CropRect = CropRect.FULL
        set(value) {
            field = value
            invalidate()
        }

    var highlights: List<Highlight> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var strokes: List<InkStroke> = emptyList()

    /** 这一页上的文字批注 */
    var textNotes: List<TextNote> = emptyList()

    /** 文字批注模式下的单击：给 Activity 一个落点（页面比例坐标） */
    var onTapAt: ((Int, Float, Float) -> Unit)? = null

    /** 文字批注模式下点到已有批注：交给 Activity 编辑/删除 */
    var onTextNoteClicked: ((TextNote) -> Unit)? = null
        set(value) {
            field = value
            invalidate()
        }

    /** true = 手指即笔；false = 长按框选 */
    var penMode: Boolean = false

    /** 正在画的笔迹颜色 */
    var penColor: Int = 0xFF2196F3.toInt()

    /** 渲染失败时显示的提示 */
    var errorText: String? = null
        set(value) {
            field = value
            invalidate()
        }

    var onTap: ((Int) -> Unit)? = null
    var onHighlightDrawn: ((RectF) -> Unit)? = null
    /** 完成一条笔迹：点序列（页面比例） + 笔宽（页面宽度的比例） */
    var onStrokeDrawn: ((List<Float>, Float) -> Unit)? = null
    var onHighlightClicked: ((Highlight) -> Unit)? = null
    var onStrokeClicked: ((InkStroke) -> Unit)? = null
    /** 手写模式下的双击（用来呼出/收起工具栏） */
    var onDoubleTap: (() -> Unit)? = null

    // 手写模式里没有「长按」这种东西，用双击来切换工具栏
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    /** 页与页之间的分隔线 */
    private val dividerPaint = Paint().apply { color = 0x44808080 }

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    /**
     * 还没渲染好时铺底用的"纸"：白色过一遍和正文相同的色彩滤镜，
     * 白纸主题得到白、夜间得到黑。不这么做就会露出页面之间的深色背景，
     * 拖动进度条时就是一道黑闪（看起来是"白 → 黑 → 白"）。
     */
    private val paperPaint = Paint().apply { color = 0xFFFFFFFF.toInt() }

    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isAntiAlias = true
    }
    private val noteBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val noteBar = Paint(Paint.ANTI_ALIAS_FLAG)

    private val hlFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hlStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val selFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x33 shl 24
    }
    private val selStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xCC shl 24
        strokeWidth = dp(1.5f)
    }
    private val errPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8A8A8A.toInt()
        textAlign = Paint.Align.CENTER
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // 用显式 Handler 而不是 View.postDelayed：后者在视图未附着时会把任务压进静态队列，
    // 页面被回收时任务悬空，行为不可预期。
    private val handler = Handler(Looper.getMainLooper())

    private var downX = 0f
    private var downY = 0f
    private var selecting = false
    private val selStart = PointF()
    private val selEnd = PointF()

    private val longPressRunnable = Runnable { startSelection() }

    // 手写中的原始点（控件坐标）
    private val rawPoints = ArrayList<Float>(256)
    private val inkPath = Path()
    private var lastRawX = 0f
    private var lastRawY = 0f

    /** 手写模式下第二根手指落下：这一笔作废，交还给父容器滚动 */
    private var multiTouch = false

    /** 从左右边缘起手：当滚动用，不画 */
    private var scrollFromEdge = false

    /** 当前这一笔的屏幕粗细（视图坐标），落笔时按缩放倍率算好，预览和成品一致 */
    private var penStrokePx = 0f

    /**
     * 放大后落笔要变细：笔宽存的是"页面宽度的比例"，
     * 若按 2x 屏幕像素换算，缩回 1x 后笔迹会比别处粗一倍。
     */
    private fun hostZoom(): Float {
        var p: android.view.ViewParent? = parent
        while (p != null) {
            if (p is ZoomFrame) return p.scale
            p = p.parent
        }
        return 1f
    }

    private fun penWidthForCurrentZoom(): Float {
        val base = max(dp(1.6f), 0.0038f * width.toFloat() / src.w.coerceAtLeast(0.05f))
        return max(dp(0.9f), base / hostZoom())
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val h = (w * aspect).roundToInt().coerceAtLeast(1)
        setMeasuredDimension(w, h)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(longPressRunnable)
        super.onDetachedFromWindow()
    }

    // ---------------- 绘制 ----------------

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap
        if (bmp == null || bmp.isRecycled) {
            whiteDraws++
            // 先用"纸色"铺满这一页，别让深色背景漏出来闪一下
            val paperFilter = ColorModes.filter(ColorModes.active)
            if (paperPaint.colorFilter !== paperFilter) paperPaint.colorFilter = paperFilter
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paperPaint)
            val err = errorText
            if (err != null) {
                errPaint.textSize = dp(14f)
                val y = height / 2f - (errPaint.descent() + errPaint.ascent()) / 2f
                canvas.drawText(err, width / 2f, y, errPaint)
            }
            return
        }
        errorText = null

        // 主题在绘制时读取当前值，而不是绑定时写死：
        // RecyclerView 会把滚出屏幕的页缓存起来直接复用、不重新绑定，
        // 绑定时写死就会留下旧主题（切主题后"后面几页还是旧色"）。
        val themeFilter = ColorModes.filter(ColorModes.active)
        if (paint.colorFilter !== themeFilter) paint.colorFilter = themeFilter

        val s = src
        val left = (s.l * bmp.width).roundToInt().coerceIn(0, bmp.width - 1)
        val top = (s.t * bmp.height).roundToInt().coerceIn(0, bmp.height - 1)
        val right = ((s.l + s.w) * bmp.width).roundToInt().coerceIn(left + 1, bmp.width)
        val bottom = ((s.t + s.h) * bmp.height).roundToInt().coerceIn(top + 1, bmp.height)
        val srcRect = Rect(left, top, right, bottom)
        val dst = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawBitmap(bmp, srcRect, dst, paint)

        // 页与页之间不留空，用一条细线区分
        val lineH = dp(1f)
        canvas.drawRect(0f, height - lineH, width.toFloat(), height.toFloat(), dividerPaint)

        for (h in highlights) {
            val r = fractionToView(h.l, h.t, h.r, h.b)
            hlFill.color = (h.color and 0x00FFFFFF) or (0x5A shl 24)
            canvas.drawRect(r, hlFill)
            hlStroke.color = (h.color and 0x00FFFFFF) or (0xCC shl 24)
            hlStroke.strokeWidth = dp(1.4f)
            val outer = RectF(r)
            outer.inset(-dp(0.7f), -dp(0.7f))
            canvas.drawRect(outer, hlStroke)
        }

        for (stroke in strokes) {
            drawStroke(canvas, stroke)
        }

        drawTextNotes(canvas)

        if (rawPoints.size >= 2) {
            inkPaint.color = penColor
            inkPaint.strokeWidth = if (penStrokePx > 0f) penStrokePx else dp(1.9f)
            canvas.drawPath(inkPath, inkPaint)
        }

        if (selecting) {
            val r = RectF(
                min(selStart.x, selEnd.x),
                min(selStart.y, selEnd.y),
                max(selStart.x, selEnd.x),
                max(selStart.y, selEnd.y),
            )
            canvas.drawRect(r, selFill)
            canvas.drawRect(r, selStroke)
        }
    }

    private fun drawStroke(canvas: Canvas, stroke: InkStroke) {
        if (stroke.pointCount < 2) return
        val path = Path()
        val x0 = fxToView(stroke.points[0])
        val y0 = fyToView(stroke.points[1])
        path.moveTo(x0, y0)
        var i = 2
        while (i + 1 < stroke.points.size) {
            path.lineTo(fxToView(stroke.points[i]), fyToView(stroke.points[i + 1]))
            i += 2
        }
        // 笔宽按页面宽度换算，缩放/裁边变化时粗细保持一致
        inkPaint.strokeWidth = max(
            dp(0.5f),
            stroke.width * (width.toFloat() / src.w.coerceAtLeast(0.05f)),
        )
        inkPaint.color = (stroke.color and 0x00FFFFFF) or (0xF0 shl 24)
        canvas.drawPath(path, inkPaint)
    }

    private fun rebuildInkPath() {
        inkPath.reset()
        if (rawPoints.size < 2) return
        inkPath.moveTo(rawPoints[0], rawPoints[1])
        var i = 2
        while (i + 1 < rawPoints.size) {
            inkPath.lineTo(rawPoints[i], rawPoints[i + 1])
            i += 2
        }
    }

    private fun fxToView(f: Float): Float = ((f - src.l) / src.w) * width.toFloat()
    private fun fyToView(f: Float): Float = ((f - src.t) / src.h) * height.toFloat()

    private fun fractionToView(l: Float, t: Float, r: Float, b: Float): RectF =
        RectF(fxToView(l), fyToView(t), fxToView(r), fyToView(b))

    private fun viewToFraction(x: Float, y: Float): PointF {
        val s = src
        val fx = s.l + (x / width.toFloat().coerceAtLeast(1f)) * s.w
        val fy = s.t + (y / height.toFloat().coerceAtLeast(1f)) * s.h
        return PointF(fx.coerceIn(0f, 1f), fy.coerceIn(0f, 1f))
    }

    // ---------------- 手势 ----------------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (penMode) return handlePenTouch(e)
        return handleReadTouch(e)
    }

    /** 手写模式：按下即画，不做长按判定 */
    private fun handlePenTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                rawPoints.clear()
                inkPath.reset()
                downX = e.x
                downY = e.y

                // 屏幕左右边缘起手：当成"翻页/滚动"，不画批注
                if (isEdgeDown(e.x)) {
                    scrollFromEdge = true
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
                scrollFromEdge = false
                multiTouch = false
                penStrokePx = penWidthForCurrentZoom()
                addRawPoint(e.x, e.y)
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // 第二根手指落下：撤销这一笔，把手势交还给父容器（双指翻页 / 缩放）
                multiTouch = true
                if (rawPoints.isNotEmpty()) {
                    rawPoints.clear()
                    inkPath.reset()
                    invalidate()
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (multiTouch || scrollFromEdge) return true
                val n = e.historySize
                if (n > 0) {
                    for (i in 0 until n) {
                        addRawPoint(e.getHistoricalX(i), e.getHistoricalY(i))
                    }
                }
                addRawPoint(e.x, e.y)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!multiTouch && !scrollFromEdge) {
                    if (rawPoints.size / 2 < 2) {
                        handlePenTap()
                    } else {
                        finishStroke()
                    }
                }
                multiTouch = false
                scrollFromEdge = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                rawPoints.clear()
                inkPath.reset()
                multiTouch = false
                scrollFromEdge = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return true
    }

    /** 是不是从左右边缘起的笔（留给翻页手势） */
    private fun isEdgeDown(x: Float): Boolean {
        val edge = dp(44f)
        return x < edge || x > width - edge
    }

    /**
     * 手写模式里的"点一下"：
     * 连续两次轻点 = 双击，用来呼出/收起上下工具栏；
     * 单次轻点仍然可以点中已有批注去编辑。
     */
    private fun handlePenTap() {
        val now = android.os.SystemClock.uptimeMillis()
        val isDouble = now - lastTapTime < 320L &&
            abs(downX - lastTapX) < dp(48f) && abs(downY - lastTapY) < dp(48f)

        rawPoints.clear()
        inkPath.reset()

        if (isDouble) {
            lastTapTime = 0L
            onDoubleTap?.invoke()
        } else {
            lastTapTime = now
            lastTapX = downX
            lastTapY = downY
            hitTestAndDispatch(downX, downY)
        }
    }

    private fun addRawPoint(x: Float, y: Float) {
        if (rawPoints.size >= 2) {
            if (abs(x - lastRawX) < 1.2f && abs(y - lastRawY) < 1.2f) return
        }
        rawPoints.add(x.coerceIn(0f, width.toFloat()))
        rawPoints.add(y.coerceIn(0f, height.toFloat()))
        lastRawX = x
        lastRawY = y
        rebuildInkPath()
    }

    private fun finishStroke() {
        val count = rawPoints.size / 2
        if (count < 2) {
            // 只是点了一下：看是不是点在已有批注上
            hitTestAndDispatch(downX, downY)
            rawPoints.clear()
            inkPath.reset()
            return
        }
        val frac = ArrayList<Float>(rawPoints.size)
        var i = 0
        while (i + 1 < rawPoints.size) {
            val p = viewToFraction(rawPoints[i], rawPoints[i + 1])
            frac.add(p.x)
            frac.add(p.y)
            i += 2
        }
        val simplified = InkStroke.simplify(frac, 0.0012f)
        if (simplified.size >= 4) {
            // 视图像素 → "页面宽度的比例"
            val fracWidth = penStrokePx * src.w / width.toFloat().coerceAtLeast(1f)
            onStrokeDrawn?.invoke(simplified, fracWidth.coerceIn(0.0004f, 0.02f))
        }
        rawPoints.clear()
        inkPath.reset()
    }

    /** 普通阅读模式：长按进入框选，单击切换工具栏 */
    private fun handleReadTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                selecting = false
                handler.removeCallbacks(longPressRunnable)
                handler.postDelayed(longPressRunnable, 420)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (selecting) {
                    selEnd.set(
                        e.x.coerceIn(0f, width.toFloat()),
                        e.y.coerceIn(0f, height.toFloat()),
                    )
                    invalidate()
                } else if (abs(e.x - downX) > touchSlop || abs(e.y - downY) > touchSlop) {
                    handler.removeCallbacks(longPressRunnable)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                if (selecting) {
                    finishSelection()
                } else if (abs(e.x - downX) <= touchSlop && abs(e.y - downY) <= touchSlop) {
                    if (!hitTestAndDispatch(e.x, e.y)) performClick()
                }
                selecting = false
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                if (selecting) parent?.requestDisallowInterceptTouchEvent(false)
                selecting = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    /** 单击：交给 Activity 切换工具栏；文字批注模式下改成"在这落一条批注" */
    override fun performClick(): Boolean {
        super.performClick()
        val at = onTapAt
        if (at != null) {
            val hit = textNoteAt(downX, downY)
            if (hit != null) {
                onTextNoteClicked?.invoke(hit)
            } else {
                val p = viewToFraction(downX, downY)
                at(pageIndex, p.x, p.y)
            }
        } else {
            onTap?.invoke(pageIndex)
        }
        return true
    }

    /** 文字批注：半透明底 + 自动换行，位置和字号都随缩放走 */
    /** 一条文字批注排好版后的样子：行 + 外框（视图坐标） */
    private class NoteBox(val lines: List<String>, val rect: RectF, val textSize: Float, val color: Int)

    private fun layoutNote(n: TextNote): NoteBox? {
        if (bitmap == null) return null
        val pad = dp(2f)
        val x = fxToView(n.x)
        val y = fyToView(n.y)
        val textSize = (n.size * width).coerceAtLeast(dp(9f))
        notePaint.textSize = textSize
        val maxWidth = (width - x - pad * 2).coerceAtLeast(textSize * 4)
        val lines = ArrayList<String>()
        for (para in n.text.split("\n")) {
            var rest = para
            while (rest.isNotEmpty()) {
                val count = notePaint.breakText(rest, true, maxWidth, null)
                if (count <= 0) break
                lines.add(rest.substring(0, count))
                rest = rest.substring(count)
            }
            if (lines.isEmpty() || para.isEmpty()) lines.add("")
        }
        val lineH = textSize * 1.25f
        val boxW = (lines.maxOfOrNull { notePaint.measureText(it) } ?: 0f) + pad * 2 + dp(4f)
        val boxH = lineH * lines.size + pad * 2
        return NoteBox(lines, RectF(x, y, x + boxW, y + boxH), textSize, n.color)
    }

    private fun drawTextNotes(canvas: Canvas) {
        if (textNotes.isEmpty() || bitmap == null) return
        val pad = dp(2f)
        for (n in textNotes) {
            val box = layoutNote(n) ?: continue
            notePaint.textSize = box.textSize
            notePaint.color = n.color
            noteBg.color = 0xCC1A1A1A.toInt()
            canvas.drawRoundRect(box.rect, dp(3f), dp(3f), noteBg)
            canvas.drawRect(
                box.rect.left, box.rect.top, box.rect.left + dp(2.5f), box.rect.bottom,
                noteBar.also { it.color = n.color },
            )
            var ty = box.rect.top + pad - notePaint.ascent()
            val lineH = box.textSize * 1.25f
            for (line in box.lines) {
                canvas.drawText(line, box.rect.left + pad + dp(4f), ty, notePaint)
                ty += lineH
            }
        }
    }

    /** 点到哪条文字批注（点空返回 null），加一点容差方便手指点中 */
    fun textNoteAt(x: Float, y: Float): TextNote? {
        val tol = dp(6f)
        for (n in textNotes.asReversed()) {
            val box = layoutNote(n) ?: continue
            val r = box.rect
            if (x >= r.left - tol && x <= r.right + tol && y >= r.top - tol && y <= r.bottom + tol) {
                return n
            }
        }
        return null
    }

    private fun startSelection() {
        if (bitmap == null) return
        selecting = true
        selStart.set(downX, downY)
        selEnd.set(downX, downY)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        parent?.requestDisallowInterceptTouchEvent(true)
        invalidate()
    }

    private fun finishSelection() {
        val x0 = min(selStart.x, selEnd.x)
        val x1 = max(selStart.x, selEnd.x)
        val y0 = min(selStart.y, selEnd.y)
        val y1 = max(selStart.y, selEnd.y)
        if (x1 - x0 > dp(16f) && y1 - y0 > dp(10f)) {
            val a = viewToFraction(x0, y0)
            val b = viewToFraction(x1, y1)
            val rect = RectF(
                min(a.x, b.x), min(a.y, b.y),
                max(a.x, b.x), max(a.y, b.y),
            )
            onHighlightDrawn?.invoke(rect)
        }
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    /** 点中笔迹或高亮就走编辑，返回是否命中 */
    private fun hitTestAndDispatch(x: Float, y: Float): Boolean {
        val p = viewToFraction(x, y)
        val tol = max(0.012f, dp(18f) / width.toFloat() * src.w)
        var bestStroke: InkStroke? = null
        var bestDist = Float.MAX_VALUE
        for (s in strokes) {
            val d = s.distanceTo(p.x, p.y)
            if (d < tol && d < bestDist) {
                bestDist = d
                bestStroke = s
            }
        }
        val hl = highlights.lastOrNull { it.contains(p.x, p.y) }
        if (bestStroke != null && (hl == null || bestDist < tol)) {
            onStrokeClicked?.invoke(bestStroke)
            return true
        }
        if (hl != null) {
            onHighlightClicked?.invoke(hl)
            return true
        }
        return false
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    companion object {
        /** 调试计数：没有位图就绘制的次数（= 白屏帧） */
        @JvmStatic
        var whiteDraws = 0
    }
}
