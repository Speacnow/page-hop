package com.pagehop.reader

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.pagehop.reader.ui.PageView
import com.pagehop.reader.ui.ZoomFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 手势链路集成测试：ZoomFrame → RecyclerView → PageView，全部用真实控件。
 *
 * 这是对"批注模式下捏合没反应"最贴近现场的复现：
 * PageView 按下时独占手势、RecyclerView 拖动时也独占，
 * 两者叠加曾经让 ZoomFrame 收不到任何多指事件。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PenZoomIntegrationTest {

    private class VH(val pv: PageView) : RecyclerView.ViewHolder(pv)

    private val bmp: Bitmap = Bitmap.createBitmap(200, 280, Bitmap.Config.ARGB_8888)

    private fun newReader(penMode: Boolean): Pair<ZoomFrame, RecyclerView> {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val frame = ZoomFrame(ctx)
        val rv = RecyclerView(ctx)
        rv.layoutManager = LinearLayoutManager(ctx, RecyclerView.VERTICAL, false)
        rv.adapter = object : RecyclerView.Adapter<VH>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
                val pv = PageView(parent.context)
                pv.layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                return VH(pv)
            }

            override fun onBindViewHolder(holder: VH, position: Int) {
                holder.pv.pageIndex = position
                holder.pv.aspect = 1.4f
                holder.pv.bitmap = bmp
                holder.pv.penMode = penMode
            }

            override fun getItemCount(): Int = 4
        }
        frame.addView(
            rv,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        frame.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY),
        )
        frame.layout(0, 0, 1000, 2000)
        rv.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY),
        )
        rv.layout(0, 0, 1000, 2000)
        return frame to rv
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

    private fun pinch(frame: ZoomFrame) {
        val pDown = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val pUp = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_DOWN, 300f, 900f))
        frame.dispatchTouchEvent(two(pDown, 300f, 900f, 700f, 900f))
        frame.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 250f, 900f, 750f, 900f))
        frame.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 100f, 900f, 900f, 900f))
        frame.dispatchTouchEvent(two(pUp, 100f, 900f, 900f, 900f))
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_UP, 100f, 900f))
    }

    @Test
    fun `pinch zooms inside a real recycler view in pen mode`() {
        val (frame, _) = newReader(penMode = true)
        pinch(frame)
        assertTrue(
            "批注模式下捏合必须能放大，实际 scale=${frame.scale}",
            frame.scale > 1.3f,
        )
    }

    @Test
    fun `pinch zooms inside a real recycler view in read mode`() {
        val (frame, _) = newReader(penMode = false)
        pinch(frame)
        assertTrue("阅读模式下捏合必须能放大，实际 scale=${frame.scale}", frame.scale > 1.3f)
    }

    @Test
    fun `drawing still works after the pinch handoff`() {
        val (frame, rv) = newReader(penMode = true)
        pinch(frame)
        assertTrue(frame.scale > 1.3f)

        val pv = rv.getChildAt(0) as PageView
        var stroke: List<Float>? = null
        pv.onStrokeDrawn = { pts, _ -> stroke = pts }

        // 直接对页面下笔：捏合结束后单指仍然是笔
        pv.dispatchTouchEvent(one(MotionEvent.ACTION_DOWN, 200f, 400f))
        var i = 1
        while (i <= 6) {
            pv.dispatchTouchEvent(one(MotionEvent.ACTION_MOVE, 200f + i * 18f, 400f + i * 12f))
            i++
        }
        pv.dispatchTouchEvent(one(MotionEvent.ACTION_UP, 308f, 472f))

        assertTrue("放大后仍然要能写字，实际 ${stroke?.size}", (stroke?.size ?: 0) >= 4)
    }

    @Test
    fun `pinch out to max then pinch back in inside a real recycler view`() {
        val (frame, _) = newReader(penMode = true)
        val pDown = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val pUp = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

        // 一次手势放到最大
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_DOWN, 500f, 900f))
        frame.dispatchTouchEvent(two(pDown, 480f, 900f, 520f, 900f))
        frame.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 0f, 900f, 1000f, 900f))
        frame.dispatchTouchEvent(two(pUp, 0f, 900f, 1000f, 900f))
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_UP, 0f, 900f))
        assertEquals("应先到最大倍率", frame.maxScale, frame.scale, 0.01f)

        // 新手势往回收
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_DOWN, 400f, 900f))
        frame.dispatchTouchEvent(two(pDown, 100f, 900f, 900f, 900f))
        frame.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 300f, 900f, 700f, 900f))
        frame.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 350f, 900f, 650f, 900f))
        frame.dispatchTouchEvent(two(pUp, 350f, 900f, 650f, 900f))
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_UP, 350f, 900f))

        assertTrue("最大倍率下必须能捏回去，实际 ${frame.scale}", frame.scale < 2.5f)
    }

    @Test
    fun `after pinching back near 1x the list scrolls again`() {
        val (frame, rv) = newReader(penMode = false)
        val pDown = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val pUp = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

        // 放到 3x
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_DOWN, 500f, 900f))
        frame.dispatchTouchEvent(two(pDown, 400f, 900f, 800f, 900f))
        frame.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 0f, 900f, 1200f, 900f))
        frame.dispatchTouchEvent(two(pUp, 0f, 900f, 1200f, 900f))
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_UP, 0f, 900f))

        // 只缩到 1.05x 就松手：应该归位 1x（间距 800 → 280）
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_DOWN, 500f, 900f))
        frame.dispatchTouchEvent(two(pDown, 100f, 900f, 900f, 900f))
        frame.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 360f, 900f, 640f, 900f))
        frame.dispatchTouchEvent(two(pUp, 360f, 900f, 640f, 900f))
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_UP, 360f, 900f))
        assertEquals("应回到 1x", 1f, frame.scale, 0.001f)

        // 现在单指拖动必须能滚动列表
        val before = rv.computeVerticalScrollOffset()
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_DOWN, 500f, 1500f))
        var i = 1
        while (i <= 10) {
            frame.dispatchTouchEvent(one(MotionEvent.ACTION_MOVE, 500f, 1500f - i * 60f))
            i++
        }
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_UP, 500f, 900f))

        assertTrue(
            "缩回 1x 后单指拖动要能滚动，$before -> ${rv.computeVerticalScrollOffset()}",
            rv.computeVerticalScrollOffset() != before,
        )
    }

    @Test
    fun `two finger drag at 1x does not zoom`() {
        val (frame, _) = newReader(penMode = true)
        val pDown = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val pUp = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_DOWN, 300f, 1400f))
        frame.dispatchTouchEvent(two(pDown, 300f, 1400f, 700f, 1400f))
        frame.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 300f, 1150f, 700f, 1150f))
        frame.dispatchTouchEvent(two(MotionEvent.ACTION_MOVE, 300f, 950f, 700f, 950f))
        frame.dispatchTouchEvent(two(pUp, 300f, 950f, 700f, 950f))
        frame.dispatchTouchEvent(one(MotionEvent.ACTION_UP, 300f, 950f))

        assertEquals("双指平移不该被当成缩放", 1f, frame.scale, 0.01f)
    }
}
