package com.pageflow.reader.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 给阅读区套一层缩放：双指捏合放大、放大后拖动平移。
 *
 * 关键取舍：
 * - **双指平行拖动不当作缩放**（缩放比例变化小于 10% 就不接管），
 *   这样 1x 时"双指上下滑 = 翻页"仍然有效，只有真的在捏才进入缩放。
 * - 已经放大时双指拖动改成平移：手写模式下单指必须留给笔画，
 *   不然放大之后就没法挪到想批注的位置。
 * - 子 View 的 requestDisallowInterceptTouchEvent(true) 在多指期间一律忽略，
 *   否则 PageView（手写）和 RecyclerView（滚动）会把缩放整个吞掉。
 */
class ZoomFrame @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    var minScale: Float = 1f
    var maxScale: Float = 3f

    /** 缩放比例变化（实时） */
    var onScaleChanged: ((Float) -> Unit)? = null
    /** 手势结束，可以按新比例重新渲染 */
    var onScaleSettled: ((Float) -> Unit)? = null

    /** 未放大时从左侧往右滑：交给 Activity 拉出目录 */
    var onSwipeToOpen: (() -> Unit)? = null
    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private var swipeFired = false

    var scale: Float = 1f
        private set

    private var tx = 0f
    private var ty = 0f

    private var handling = false
    private var zooming = false

    private var span0 = 0f
    private var baseScale = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f

    /** 当前按下的手指数 */
    private var downPointers = 0

    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private val child: android.view.View? get() = if (childCount > 0) getChildAt(0) else null

    private fun span(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 0f
        return hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
    }

    private fun focusX(e: MotionEvent): Float =
        if (e.pointerCount >= 2) (e.getX(0) + e.getX(1)) / 2f else e.x

    private fun focusY(e: MotionEvent): Float =
        if (e.pointerCount >= 2) (e.getY(0) + e.getY(1)) / 2f else e.y

    /**
     * 子 View 会在按下时"独占"手势：PageView 写字、RecyclerView 滚动。
     * 但手指加到两根时，这个独占会让本容器收不到 onInterceptTouchEvent，
     * 捏合就永远识别不到（span0 一直是 0）。所以多指期间不接受独占。
     */
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        if (disallowIntercept && (downPointers >= 2 || zooming)) return
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    /**
     * 事件最先到这里：先数手指，再交给子 View。
     * 第二根手指落下时主动把上一轮的"独占"清掉，让后续 MOVE 重新回到
     * onInterceptTouchEvent 里判断是不是在捏。
     */
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downPointers = 1
                handling = false
                zooming = false
                span0 = 0f
                lastX = e.x
                lastY = e.y
                swipeDownX = e.x
                swipeDownY = e.y
                swipeFired = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!swipeFired && scale <= 1.05f && e.pointerCount == 1) {
                    val dx = e.x - swipeDownX
                    val dy = e.y - swipeDownY
                    val threshold = 80f * resources.displayMetrics.density
                    // 从左 1/4 屏内开始，明显是横向拖动
                    if (swipeDownX < width * 0.25f && dx > threshold && dx > kotlin.math.abs(dy) * 1.5f) {
                        swipeFired = true
                        onSwipeToOpen?.invoke()
                    }
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                downPointers = e.pointerCount
                if (e.pointerCount >= 2) {
                    if (span0 <= 1f) {
                        span0 = span(e)
                        baseScale = scale
                    }
                    lastFocusX = focusX(e)
                    lastFocusY = focusY(e)
                    super.requestDisallowInterceptTouchEvent(false)
                }
            }

            MotionEvent.ACTION_POINTER_UP ->
                downPointers = (e.pointerCount - 1).coerceAtLeast(1)

            MotionEvent.ACTION_MOVE -> downPointers = e.pointerCount

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> downPointers = 0
        }
        return super.dispatchTouchEvent(e)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handling = false
                zooming = false
                span0 = 0f
                lastX = e.x
                lastY = e.y
                return false
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount == 2) {
                    span0 = span(e)
                    baseScale = scale
                    lastFocusX = focusX(e)
                    lastFocusY = focusY(e)
                }
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    handling = handleTwoFingerMove(e)
                } else if (!zooming && scale > PAN_MIN_SCALE && e.pointerCount == 1) {
                    // 真正放大之后单指拖动 = 平移；
                    // 只是"略微放大"时不要抢单指，否则页面滚不动
                    if (abs(e.x - lastX) > slop || abs(e.y - lastY) > slop) handling = true
                }
                return handling
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount == 2) {
                    span0 = span(e)
                    baseScale = scale
                }
                lastFocusX = focusX(e)
                lastFocusY = focusY(e)
                lastX = e.getX(0)
                lastY = e.getY(0)
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // 少了一根手指：用剩下那根重新起算，避免坐标跳变
                val idx = if (e.actionIndex == 0) 1 else 0
                if (idx < e.pointerCount) {
                    lastX = e.getX(idx)
                    lastY = e.getY(idx)
                }
                span0 = 0f
                baseScale = scale
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    handleTwoFingerMove(e)
                } else if (e.pointerCount == 1) {
                    panBy(e.x - lastX, e.y - lastY)
                    lastX = e.x
                    lastY = e.y
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handling = false
                zooming = false
                span0 = 0f
                downPointers = 0
                // 缩到"差不多 1x"就归位。残留 1.03~1.14 倍的两种下场都很糟：
                // ①单指拖动被当成"平移"（余量只有几十像素），页面看起来是 1x 却再也滚不动；
                // ②用户以为滚不动，按住再拖，反而触发了长按框选高亮。
                if (scale > 1.001f && scale < PAN_MIN_SCALE) {
                    scale = 1f
                    tx = 0f
                    ty = 0f
                    applyTransform()
                    onScaleChanged?.invoke(scale)
                }
                onScaleSettled?.invoke(scale)
                return true
            }
        }
        return handling
    }

    /**
     * 双指 MOVE 的统一判定。拦截阶段和接管之后走的都是这里——
     * 否则一旦被"放大后双指拖动=平移"接管，捏合就再也识别不到了。
     *
     * @return true 表示这次手势归本容器处理
     */
    private fun handleTwoFingerMove(e: MotionEvent): Boolean {
        if (span0 <= 1f) {
            // POINTER_DOWN 万一被吞了，用第一次双指 MOVE 重新起算
            span0 = span(e)
            baseScale = scale
            lastFocusX = focusX(e)
            lastFocusY = focusY(e)
            return handling
        }
        val f = span(e) / span0
        // 1x 时门槛要高一些，好把"双指上下滑=翻页"留给 RecyclerView；
        // 已经放大了就没有翻页这回事了，门槛压低，捏合才跟手。
        val trigger = if (scale > 1.001f) ZOOMED_PINCH_TRIGGER else PINCH_TRIGGER
        if (!zooming && abs(f - 1f) > trigger) zooming = true

        if (zooming) {
            val target = baseScale * f
            val clamped = target.coerceIn(minScale, maxScale)
            if (abs(target - clamped) > 0.001f) {
                // 撞到 1x / 3x 边界：把基准挪到边界。
                // 不挪的话，手指张开过头之后那段比例仍然 > 1，
                // 往回收时目标值一直被夹回上限，看起来就是"到最大就缩不回去"。
                span0 = span(e)
                baseScale = clamped
            }
            applyScale(clamped, focusX(e), focusY(e))
            // 捏的同时两指整体在移动 → 一起平移，否则捏到一半挪不动位置
            panBy(focusX(e) - lastFocusX, focusY(e) - lastFocusY)
        } else if (scale > 1.001f) {
            // 已经放大：双指平行拖动 = 平移（批注模式单指要留给笔画）
            panBy(focusX(e) - lastFocusX, focusY(e) - lastFocusY)
        }
        lastFocusX = focusX(e)
        lastFocusY = focusY(e)
        return zooming || scale > 1.001f
    }

    private fun applyScale(target: Float, fx: Float, fy: Float) {
        val clamped = target.coerceIn(minScale, maxScale)
        if (abs(clamped - scale) < 0.001f) return

        val ratio = clamped / scale
        tx = fx - (fx - tx) * ratio
        ty = fy - (fy - ty) * ratio
        scale = clamped
        if (scale <= 1.001f) {
            tx = 0f
            ty = 0f
        }
        applyTransform()
        onScaleChanged?.invoke(scale)
    }

    private fun panBy(dx: Float, dy: Float) {
        if (scale <= 1.001f) return
        tx += dx
        ty += dy
        applyTransform()
    }

    private companion object {
        /** 1x 时两指间距变化超过 10% 才算捏合，否则当成平行拖动（留给双指翻页） */
        const val PINCH_TRIGGER = 0.10f
        /** 已经放大时没有翻页顾虑，门槛压低，捏合更跟手 */
        const val ZOOMED_PINCH_TRIGGER = 0.03f
        /**
         * 低于这个倍率就"当作没放大"：单指拖动仍然是滚动/翻页，
         * 松手时直接归位到 1x。否则会停在 1.05x 这种"看着像原尺寸、
         * 实际上单指拖动被拿去平移"的死角里，页面再也滚不动。
         */
        const val PAN_MIN_SCALE = 1.15f
    }

    private fun applyTransform() {
        val c = child ?: return
        c.pivotX = 0f
        c.pivotY = 0f
        c.scaleX = scale
        c.scaleY = scale

        val cw = c.width * scale
        val ch = c.height * scale
        tx = if (cw <= width) 0f else tx.coerceIn(width - cw, 0f)
        ty = if (ch <= height) 0f else ty.coerceIn(height - ch, 0f)

        c.translationX = tx
        c.translationY = ty
    }

    /** 复位到 1x */
    fun resetZoom() {
        scale = 1f
        tx = 0f
        ty = 0f
        handling = false
        zooming = false
        span0 = 0f
        applyTransform()
        onScaleChanged?.invoke(scale)
        onScaleSettled?.invoke(scale)
    }
}
