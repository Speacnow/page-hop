package com.pageflow.reader.ui

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 缩放容器的手势判定：
 * ①捏合（间距明显变化）才缩放
 * ②双指平行拖动不算缩放（要留给"双指翻页"）
 * ③放大后拖动是平移
 * ④子 View 独占手势（手写批注 / 列表滚动）时，捏合仍然要生效
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ZoomFrameTest {

    /**
     * 模拟真实的子 View 链路：PageView 手写时按下就独占手势，
     * RecyclerView 开始拖动后也会一直独占。
     */
    private class PenLikeChild(ctx: Context) : View(ctx) {
        var moves = 0
            private set

        init {
            isClickable = true
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_POINTER_UP,
                MotionEvent.ACTION_MOVE -> {
                    if (e.actionMasked == MotionEvent.ACTION_MOVE) moves++
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            return true
        }
    }

    private fun newFrame(child: View? = null): Pair<ZoomFrame, View> {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val frame = ZoomFrame(ctx)
        // 子 View 必须消费 ACTION_DOWN（真实场景里是 RecyclerView），
        // 否则 ViewGroup 不会为后续事件调用 onInterceptTouchEvent。
        val v = child ?: View(ctx).apply { isClickable = true }
        frame.addView(v, FrameLayout.LayoutParams(1000, 2000))
        frame.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY),
        )
        frame.layout(0, 0, 1000, 2000)
        v.layout(0, 0, 1000, 2000)
        return frame to v
    }

    private fun one(action: Int, x: Float, y: Float): MotionEvent {
        val props = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER
            },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f },
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
            MotionEvent.PointerCoords().apply { x = x0; y = y0; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = x1; y = y1; pressure = 1f; size = 1f },
        )
        return MotionEvent.obtain(
            0L, SystemClock.uptimeMillis(), action, 2, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0,
        )
    }

    private fun pointerDown() =
        MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    private fun pointerUp() =
        MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    @Test
    fun `pinching zooms in`() {
        val (frame, _) = newFrame()
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_DOWN, 300f, 900f))
        frame.dispatchTouchEvent(two(pointerDown(), 300f, 900f, 700f, 900f))
        frame.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 150f, 900f, 850f, 900f))
        frame.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 100f, 900f, 900f, 900f))
        frame.dispatchTouchEvent(two(pointerUp(), 100f, 900f, 900f, 900f))
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_UP, 100f, 900f))

        assertTrue("捏合后应放大，实际 ${frame.scale}", frame.scale > 1.3f)
    }

    @Test
    fun `two finger drag without pinching keeps scale`() {
        val (frame, _) = newFrame()
        // 两指保持间距 400，整体向上平移 300 —— 这是"双指翻页"，不该触发缩放
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_DOWN, 300f, 1200f))
        frame.dispatchTouchEvent(two(pointerDown(), 300f, 1200f, 700f, 1200f))
        frame.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 300f, 1000f, 700f, 1000f))
        frame.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 300f, 900f, 700f, 900f))
        frame.dispatchTouchEvent(two(pointerUp(), 300f, 900f, 700f, 900f))
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_UP, 300f, 900f))

        assertEquals(1f, frame.scale, 0.01f)
    }

    @Test
    fun `single finger drag pans after zooming`() {
        val (frame, child) = newFrame()
        // 先放大
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_DOWN, 300f, 900f))
        frame.dispatchTouchEvent(two(pointerDown(), 300f, 900f, 700f, 900f))
        frame.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 100f, 900f, 900f, 900f))
        frame.dispatchTouchEvent(two(pointerUp(), 100f, 900f, 900f, 900f))
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_UP, 100f, 900f))
        assertTrue(frame.scale > 1.3f)

        val before = child.translationY
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_DOWN, 500f, 1200f))
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_MOVE, 500f, 1000f))
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_MOVE, 500f, 900f))
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_UP, 500f, 900f))

        assertTrue("放大后单指拖动应该平移", child.translationY != before)
    }

    @Test
    fun `pinching back out works from max scale`() {
        val (frame, _) = newFrame()
        // 一次手势放到最大
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_DOWN, 500f, 900f))
        frame.dispatchTouchEvent(two(pointerDown(), 499f, 900f, 501f, 900f))
        frame.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 0f, 900f, 1000f, 900f))
        frame.dispatchTouchEvent(two(pointerUp(), 0f, 900f, 1000f, 900f))
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_UP, 0f, 900f))
        assertEquals("应先到最大倍率", frame.maxScale, frame.scale, 0.01f)

        // 新手势往回收：间距 800 → 300
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_DOWN, 400f, 900f))
        frame.dispatchTouchEvent(two(pointerDown(), 100f, 900f, 900f, 900f))
        frame.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 300f, 900f, 700f, 900f))
        frame.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 350f, 900f, 650f, 900f))
        frame.dispatchTouchEvent(two(pointerUp(), 350f, 900f, 650f, 900f))
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_UP, 350f, 900f))

        assertTrue("从最大倍率必须能捏回去，实际 ${frame.scale}", frame.scale < 2.5f)
    }

    @Test
    fun `pinching back out works right after a pan while zoomed`() {
        val (frame, _) = newFrame()
        // 放到最大
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_DOWN, 500f, 900f))
        frame.dispatchTouchEvent(two(pointerDown(), 499f, 900f, 501f, 900f))
        frame.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 0f, 900f, 1000f, 900f))
        frame.dispatchTouchEvent(two(pointerUp(), 0f, 900f, 1000f, 900f))
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_UP, 0f, 900f))

        // 同一手势里先平行拖一段（被当成平移），再捏合
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_DOWN, 400f, 900f))
        frame.dispatchTouchEvent(two(pointerDown(), 100f, 900f, 900f, 900f))
        frame.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 100f, 800f, 900f, 800f))
        frame.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 300f, 800f, 700f, 800f))
        frame.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 350f, 800f, 650f, 800f))
        frame.dispatchTouchEvent(two(pointerUp(), 350f, 800f, 650f, 800f))
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_UP, 350f, 800f))

        assertTrue("平移过程中开始捏合也要能缩小，实际 ${frame.scale}", frame.scale < 2.5f)
    }

    @Test
    fun `pinching in right after over-pinching past max responds immediately`() {
        val (frame, _) = newFrame()
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_DOWN, 500f, 900f))
        // 起始间距只有 40，然后一把拉开到 1000：倍率早早就被夹在 3x
        frame.dispatchTouchEvent(two(pointerDown(), 480f, 900f, 520f, 900f))
        frame.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 0f, 900f, 1000f, 900f))
        assertEquals("应撞到上限", frame.maxScale, frame.scale, 0.01f)

        // 间距 1000 → 900 → 800 往回收，倍率必须开始下降
        frame.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 50f, 900f, 950f, 900f))
        frame.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 100f, 900f, 900f, 900f))

        assertTrue(
            "超过上限后往回捏要立刻有反应（没有死区），实际 ${frame.scale}",
            frame.scale < 2.99f,
        )
    }

    @Test
    fun `small pinch responds when already zoomed`() {
        val (frame, _) = newFrame()
        // 先放到 2x 左右
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_DOWN, 500f, 900f))
        frame.dispatchTouchEvent(two(pointerDown(), 300f, 900f, 700f, 900f))
        frame.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 100f, 900f, 900f, 900f))
        frame.dispatchTouchEvent(two(pointerUp(), 100f, 900f, 900f, 900f))
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_UP, 100f, 900f))
        val zoomed = frame.scale
        assertTrue(zoomed > 1.3f)

        // 小幅度捏合：间距只变 5%，1x 时这不算捏合，但已经放大就该响应
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_DOWN, 500f, 900f))
        frame.dispatchTouchEvent(two(pointerDown(), 100f, 900f, 900f, 900f))
        frame.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 120f, 900f, 880f, 900f))
        frame.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 140f, 900f, 860f, 900f))
        frame.dispatchTouchEvent(two(pointerUp(), 140f, 900f, 860f, 900f))
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_UP, 140f, 900f))

        assertTrue(
            "放大后小幅捏合要跟手：$zoomed -> ${frame.scale}",
            frame.scale < zoomed - 0.05f,
        )
    }

    @Test
    fun `pinching down to nearly 1x snaps back to 1x`() {
        val (frame, _) = newFrame()
        // 先放到 3x
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_DOWN, 500f, 900f))
        frame.dispatchTouchEvent(two(pointerDown(), 400f, 900f, 800f, 900f))
        frame.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 0f, 900f, 1200f, 900f))
        frame.dispatchTouchEvent(two(pointerUp(), 0f, 900f, 1200f, 900f))
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_UP, 0f, 900f))
        assertEquals(3f, frame.scale, 0.01f)

        // 只会缩到 1.05x（用户觉得"已经回到原尺寸"）→ 必须归位 1x，
        // 否则单指拖动会被当成平移，页面再也滚不动。
        // 3x 缩到 1.05x：间距 800 → 280，比例 0.35
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_DOWN, 500f, 900f))
        frame.dispatchTouchEvent(two(pointerDown(), 100f, 900f, 900f, 900f))
        frame.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 360f, 900f, 640f, 900f))
        frame.dispatchTouchEvent(two(pointerUp(), 360f, 900f, 640f, 900f))
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_UP, 360f, 900f))

        assertEquals("接近 1x 应归位", 1f, frame.scale, 0.001f)
    }

    @Test
    fun `a real zoom is not snapped away`() {
        val (frame, _) = newFrame()
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_DOWN, 500f, 900f))
        frame.dispatchTouchEvent(two(pointerDown(), 300f, 900f, 700f, 900f))
        frame.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 50f, 900f, 950f, 900f))
        frame.dispatchTouchEvent(two(pointerUp(), 50f, 900f, 950f, 950f))
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_UP, 50f, 900f))

        assertTrue("2.25x 不该被归位，实际 ${frame.scale}", frame.scale > 2f)
    }

    @Test
    fun `scale is clamped to the configured range`() {
        val (frame, _) = newFrame()
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_DOWN, 500f, 900f))
        frame.dispatchTouchEvent(two(pointerDown(), 499f, 900f, 501f, 900f))
        // 两指拉开到 1000，比例约 500
        frame.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 0f, 900f, 1000f, 900f))
        frame.dispatchTouchEvent(two(pointerUp(), 0f, 900f, 1000f, 900f))
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_UP, 0f, 900f))

        assertTrue("不应超过 maxScale", frame.scale <= frame.maxScale + 0.001f)
    }

    // ---------------- 手写模式（子 View 独占手势） ----------------

    @Test
    fun `pinch still zooms when the child disallows intercept`() {
        val child = PenLikeChild(ApplicationProvider.getApplicationContext())
        val (frame, _) = newFrame(child)

        frame.dispatchTouchEvent(one(MotionEvent.ACTION_DOWN, 300f, 900f))
        frame.dispatchTouchEvent(two(pointerDown(), 300f, 900f, 700f, 900f))
        frame.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 250f, 900f, 750f, 900f))
        frame.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 100f, 900f, 900f, 900f))
        frame.dispatchTouchEvent(two(pointerUp(), 100f, 900f, 900f, 900f))
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_UP, 100f, 900f))

        assertTrue(
            "批注模式下捏合必须能放大，实际 scale=${frame.scale}",
            frame.scale > 1.3f,
        )
    }

    @Test
    fun `two finger drag at 1x still reaches the child`() {
        val child = PenLikeChild(ApplicationProvider.getApplicationContext())
        val (frame, _) = newFrame(child)

        frame.dispatchTouchEvent(one(MotionEvent.ACTION_DOWN, 300f, 1200f))
        frame.dispatchTouchEvent(two(pointerDown(), 300f, 1200f, 700f, 1200f))
        frame.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 300f, 1100f, 700f, 1100f))
        frame.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 300f, 900f, 700f, 900f))
        frame.dispatchTouchEvent(two(pointerUp(), 300f, 900f, 700f, 900f))
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_UP, 300f, 900f))

        assertEquals("1x 下双指拖动不应改变缩放", 1f, frame.scale, 0.01f)
        assertTrue("1x 下双指拖动要留给翻页（子 View 应该收到 MOVE）", child.moves >= 2)
    }

    @Test
    fun `two finger drag pans after zooming when child disallows intercept`() {
        val child = PenLikeChild(ApplicationProvider.getApplicationContext())
        val (frame, _) = newFrame(child)

        // 先捏合放大
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_DOWN, 300f, 900f))
        frame.dispatchTouchEvent(two(pointerDown(), 300f, 900f, 700f, 900f))
        frame.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 100f, 900f, 900f, 900f))
        frame.dispatchTouchEvent(two(pointerUp(), 100f, 900f, 900f, 900f))
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_UP, 100f, 900f))
        assertTrue(frame.scale > 1.3f)

        val before = child.translationY
        // 放大后双指平行上推 = 平移
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_DOWN, 300f, 1400f))
        frame.dispatchTouchEvent(two(pointerDown(), 300f, 1400f, 700f, 1400f))
        frame.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 300f, 1200f, 700f, 1200f))
        frame.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 300f, 1100f, 700f, 1100f))
        frame.dispatchTouchEvent(two(pointerUp(), 300f, 1100f, 700f, 1100f))
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_UP, 300f, 1100f))

        assertTrue("放大后双指拖动应该平移，实际 ${child.translationY}", child.translationY != before)
    }
}
