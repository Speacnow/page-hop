package com.pagehop.reader.ui

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 验证缩略图网格的缩放方向 —— 用户反馈"只能把列从大缩到小，反方向没反应"，
 * 根因是双指落在缩略图（clickable）上时事件被子 View 吃掉，
 * 必须在 ZoomGrid 这一层拦截。这里用真实的双指 MotionEvent 打通这条链路。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ZoomGridTest {

    private fun newGrid(): ZoomGrid {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val grid = ZoomGrid(ctx)
        grid.layout(0, 0, 1200, 2000)
        return grid
    }

    private fun one(action: Int, x: Float, y: Float): MotionEvent {
        val props = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER
            },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x; this.y = y; pressure = 1f; size = 1f
            },
        )
        return MotionEvent.obtain(
            0L, SystemClock.uptimeMillis(), action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0,
        )
    }

    private fun two(action: Int, x0: Float, y0: Float, x1: Float, y1: Float): MotionEvent {
        val props = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER
            },
            MotionEvent.PointerProperties().apply {
                id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER
            },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                x = x0; y = y0; pressure = 1f; size = 1f
            },
            MotionEvent.PointerCoords().apply {
                x = x1; y = y1; pressure = 1f; size = 1f
            },
        )
        return MotionEvent.obtain(
            0L, SystemClock.uptimeMillis(), action, 2, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0,
        )
    }

    private fun pointerDown(): Int = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
    private fun pointerUp(): Int = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    @Test
    fun `tiny accidental pinch is ignored`() {
        val grid = newGrid()
        var changes = 0
        grid.currentSpan = ThumbZoom.DEFAULT_SPANS
        grid.onSpanRequested = {
            changes++
            grid.currentSpan = it
        }

        // 模拟握持手机时第二根手指蹭到：两指间距只变化约 2%
        grid.dispatchTouchEvent(one(MotionEvent.ACTION_DOWN, 400f, 900f))
        grid.dispatchTouchEvent(two(pointerDown(), 400f, 900f, 800f, 900f))
        grid.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 402f, 900f, 798f, 900f))
        grid.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 404f, 900f, 796f, 900f))
        grid.dispatchTouchEvent(two(pointerUp(), 404f, 900f, 796f, 900f))
        grid.dispatchTouchEvent(one(MotionEvent.ACTION_UP, 404f, 900f))

        assertTrue("轻微的误触不应该改变列数，实际改了 $changes 次", changes == 0)
    }

    @Test
    fun `pinching in makes columns denser`() {
        val grid = newGrid()
        var span = ThumbZoom.DEFAULT_SPANS
        grid.currentSpan = span
        grid.onSpanRequested = {
            span = it
            grid.currentSpan = it
        }

        // 两指起始相距 400px，捏到 200px
        grid.dispatchTouchEvent(one(MotionEvent.ACTION_DOWN, 300f, 900f))
        grid.dispatchTouchEvent(two(pointerDown(), 300f, 900f, 700f, 900f))
        grid.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 340f, 900f, 660f, 900f))
        grid.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 380f, 900f, 620f, 900f))
        grid.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 400f, 900f, 600f, 900f))
        grid.dispatchTouchEvent(two(pointerUp(), 400f, 900f, 600f, 900f))
        grid.dispatchTouchEvent(one(MotionEvent.ACTION_UP, 400f, 900f))

        assertTrue("捏合应当变密（列数变大），实际=$span", span > ThumbZoom.DEFAULT_SPANS)
    }

    @Test
    fun `spreading fingers makes columns fewer`() {
        val grid = newGrid()
        var span = ThumbZoom.DEFAULT_SPANS
        grid.currentSpan = span
        grid.onSpanRequested = {
            span = it
            grid.currentSpan = it
        }

        // 两指起始相距 200px，张开到 440px（超过 1.5 倍，应到 2 列）
        grid.dispatchTouchEvent(one(MotionEvent.ACTION_DOWN, 500f, 900f))
        grid.dispatchTouchEvent(two(pointerDown(), 500f, 900f, 700f, 900f))
        grid.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 470f, 900f, 730f, 900f))
        grid.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 440f, 900f, 760f, 900f))
        grid.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 410f, 900f, 790f, 900f))
        grid.dispatchTouchEvent(two(pointerUp(), 410f, 900f, 790f, 900f))
        grid.dispatchTouchEvent(one(MotionEvent.ACTION_UP, 410f, 900f))

        assertTrue("张开应当变疏（列数变小），实际=$span", span < ThumbZoom.DEFAULT_SPANS)
    }
}
