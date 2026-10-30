package com.pagehop.reader

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.pagehop.reader.ui.PageView
import com.pagehop.reader.ui.ZoomFrame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * 手势链路测试：真实派发 MotionEvent，验证
 * ①手写模式能把一段拖动变成一条笔迹
 * ②阅读模式长按后拖动会产出框选矩形
 * 这是"手指画出来到底有没有东西"的最直接验证。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GestureTest {

    private fun newPageView(): PageView {
        val ctx = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext(),
            R.style.Theme_PageHop_Reader,
        )
        val pv = PageView(ctx)
        pv.aspect = 1.4f
        pv.bitmap = Bitmap.createBitmap(400, 560, Bitmap.Config.ARGB_8888)
        pv.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
        )
        pv.layout(0, 0, 1000, 1400)
        return pv
    }

    private fun event(action: Int, x: Float, y: Float): MotionEvent {
        val t = SystemClock.uptimeMillis()
        return MotionEvent.obtain(t, t, action, x, y, 0)
    }

    @Test
    fun `pen mode turns a drag into a stroke`() {
        val pv = newPageView()
        pv.penMode = true

        var received: List<Float>? = null
        pv.onStrokeDrawn = { pts, _ -> received = pts }

        pv.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 100f, 200f))
        var i = 1
        while (i <= 20) {
            pv.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, 100f + i * 12f, 200f + i * 8f))
            i++
        }
        pv.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 340f, 360f))

        val pts = received
        assertNotNull("拖动后应该产生一条笔迹", pts)
        assertTrue("至少要有两个点", pts!!.size >= 4)
        // 全部应该是 0..1 的页面比例
        assertTrue(pts.all { it >= 0f && it <= 1f })
        // 抽稀后不应该保留所有原始点
        assertTrue("点太多说明抽稀没生效: ${pts.size}", pts.size < 21 * 2)
    }

    @Test
    fun `pen mode does not fire the rect highlight callback`() {
        val pv = newPageView()
        pv.penMode = true
        var rect: RectF? = null
        pv.onHighlightDrawn = { rect = it }

        pv.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 100f, 200f))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        pv.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, 400f, 600f))
        pv.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 400f, 600f))

        assertTrue("手写模式不应产出矩形高亮", rect == null)
    }

    @Test
    fun `long press then drag produces a highlighted rect`() {
        val pv = newPageView()
        pv.penMode = false
        var rect: RectF? = null
        pv.onHighlightDrawn = { rect = it }

        pv.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 100f, 200f))
        // 让 postDelayed 的长按任务真正跑起来
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        pv.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, 420f, 620f))
        pv.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 420f, 620f))

        val r = rect
        assertNotNull("长按拖动后应该有框选结果", r)
        assertTrue(r!!.right > r.left && r.bottom > r.top)
        assertTrue(r.left >= 0f && r.right <= 1f)
    }

    @Test
    fun `quick tap is treated as tap not as selection`() {
        val pv = newPageView()
        pv.penMode = false
        var tapped = false
        var rect: RectF? = null
        pv.onTap = { tapped = true }
        pv.onHighlightDrawn = { rect = it }

        pv.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 500f, 700f))
        pv.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 500f, 700f))

        assertTrue("轻点应触发 onTap", tapped)
        assertTrue("轻点不应产生框选", rect == null)
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
            MotionEvent.PointerCoords().apply { x = x0; this.y = y0; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = x1; this.y = y1; pressure = 1f; size = 1f },
        )
        return MotionEvent.obtain(
            0L, SystemClock.uptimeMillis(), action, 2, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0,
        )
    }

    /** 画一条斜线，返回这条笔迹记录的页面比例笔宽 */
    private fun drawAndCaptureWidth(pv: PageView): Float {
        var w = 0f
        pv.onStrokeDrawn = { _, width -> w = width }
        pv.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 100f, 200f))
        var i = 1
        while (i <= 10) {
            pv.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, 100f + i * 15f, 200f + i * 10f))
            i++
        }
        pv.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 250f, 300f))
        return w
    }

    @Test
    fun `pen width shrinks when the page is zoomed`() {
        // 1x 时的笔宽
        val plain = newPageView()
        plain.penMode = true
        val w1 = drawAndCaptureWidth(plain)
        assertTrue("应记录笔宽", w1 > 0.0004f)

        // 套进缩放容器，捏合到 2x 再画
        val frame = ZoomFrame(plain.context)
        val zoomed = newPageView()
        zoomed.penMode = true
        frame.addView(
            zoomed,
            FrameLayout.LayoutParams(1000, 1400),
        )
        frame.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(1400, android.view.View.MeasureSpec.EXACTLY),
        )
        frame.layout(0, 0, 1000, 1400)
        zoomed.layout(0, 0, 1000, 1400)

        val pDown = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val pUp = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        frame.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 300f, 700f))
        frame.dispatchTouchEvent(two(pDown, 300f, 700f, 700f, 700f))
        frame.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 100f, 700f, 900f, 700f))
        frame.dispatchTouchEvent(two(pUp, 100f, 700f, 900f, 700f))
        frame.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 100f, 700f))
        assertTrue("应先放大到 1.5x 以上，实际 ${frame.scale}", frame.scale > 1.5f)

        val w2 = drawAndCaptureWidth(zoomed)
        assertTrue(
            "放大后笔迹在页面坐标里应更细：1x=$w1  放大后=$w2",
            w2 < w1 * 0.9f,
        )
    }
}
