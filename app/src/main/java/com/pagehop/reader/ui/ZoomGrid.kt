package com.pagehop.reader.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * 支持双指缩放（改列数）的网格。
 *
 * 为什么不用 `setOnTouchListener`：缩略图格子的根布局是 clickable 的，
 * 双指起始点落在格子上时事件先被格子吃掉，RecyclerView 的 OnTouchListener 根本收不到，
 * 于是"只能往一个方向缩"。必须在自己这一层、子 View 之前拦截。
 */
class ZoomGrid @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : RecyclerView(context, attrs) {

    /** 当前列数，由外部同步进来，用来计算缩放起点 */
    var currentSpan: Int = 4

    /** 缩放要求改变列数时回调 */
    var onSpanRequested: ((Int) -> Unit)? = null

    /** 开始/结束缩放，用来显示和收起居中的“N 列”提示 */
    var onScaleBeginCallback: (() -> Unit)? = null
    var onScaleEndCallback: (() -> Unit)? = null

    /** true = 锁住滚动与缩放（长按预览时用，防止误触） */
    var scrollLocked = false

    /**
     * 在分发给子视图之前截获触摸事件。
     * 返回 true 表示这个事件被外部消费（子视图收不到），false 则照常分发。
     * 长按预览就靠它：按下时先不抢，等长按成立后再接管后续事件。
     */
    var touchInterceptor: ((MotionEvent) -> Boolean)? = null

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        val interceptor = touchInterceptor
        if (interceptor != null && interceptor(e)) return true
        return super.dispatchTouchEvent(e)
    }

    private var startSpan = 4
    private var accumulated = 1f
    private var handling = false

    private companion object {
        /** 缩放比例变化小于这个值就不处理，防止误触 */
        const val MIN_SCALE_TO_ACT = 0.15f
    }

    private val detector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                startSpan = currentSpan
                accumulated = 1f
                onScaleBeginCallback?.invoke()
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // scaleFactor 是"相对上一个事件"的比例，必须累乘。
                // 直接拿它去除起点列数，列数会一会变大一会变小，手感就是"缩不动"。
                accumulated *= detector.scaleFactor
                // 阈值：握手机时第二根手指蹭到、或者轻微抖动，都会给出很小的比例，
                // 不设阈值就会在滑动缩略图时"突然变成另一种列数"。
                if (abs(accumulated - 1f) < MIN_SCALE_TO_ACT) return true
                val target = ThumbZoom.spanFor(startSpan, accumulated)
                if (target != currentSpan) onSpanRequested?.invoke(target)
                return true
            }
        },
    )

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (scrollLocked) return true
        // 只喂给双指以上的事件：单指滑动属于滚动，不该被缩放识别器看到
        if (e.pointerCount >= 2) {
            detector.onTouchEvent(e)
            if (detector.isInProgress) handling = true
        }
        if (handling) return true
        return super.onInterceptTouchEvent(e)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (scrollLocked) return true
        if (handling) {
            detector.onTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                handling = false
                onScaleEndCallback?.invoke()
            }
            // 吃掉整个缩放手势，避免松手时被当成点击/滚动
            return true
        }
        detector.onTouchEvent(e)
        return super.onTouchEvent(e)
    }
}
