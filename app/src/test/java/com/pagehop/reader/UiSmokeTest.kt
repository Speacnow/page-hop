package com.pagehop.reader

import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.pagehop.reader.data.ColorModeKey
import com.pagehop.reader.ui.ColorModes
import com.pagehop.reader.ui.PageView
import com.pagehop.reader.ui.ZoomGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 在 JVM 上真正跑一遍 Android 界面（Robolectric）：
 * 目的是在没有真机的情况下，先排除"一启动就崩"这类低级但致命的问题。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UiSmokeTest {

    @Test
    fun `main activity launches and shows empty state`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertNotNull(activity.findViewById<View>(R.id.btnOpen))
        assertNotNull(activity.findViewById<View>(R.id.recentList))
        val empty = activity.findViewById<TextView>(R.id.tvEmpty)
        assertEquals(View.VISIBLE, empty.visibility)
    }

    @Test
    fun `reader layout inflates under reader theme`() {
        val ctx = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext(),
            R.style.Theme_PageHop_Reader,
        )
        val root = LayoutInflater.from(ctx).inflate(R.layout.activity_reader, null)
        assertNotNull(root.findViewById<View>(R.id.pageList))
        assertNotNull(root.findViewById<View>(R.id.topBar))
        assertNotNull(root.findViewById<View>(R.id.bottomBar))
        assertNotNull(root.findViewById<View>(R.id.btnThumbs))
        assertNotNull(root.findViewById<View>(R.id.btnColor))
        assertNotNull(root.findViewById<View>(R.id.btnCrop))
        assertNotNull(root.findViewById<View>(R.id.btnHighlight))
        // 新版顶栏：设置 + 更多，底栏：进度条
        assertNotNull(root.findViewById<View>(R.id.btnSettings))
        assertNotNull(root.findViewById<View>(R.id.btnMore))
        assertNotNull(root.findViewById<View>(R.id.seekProgress))
        assertNotNull(root.findViewById<View>(R.id.tvPage))
        assertNotNull(root.findViewById<View>(R.id.tvTotal))
    }

    @Test
    fun `thumbnail map and item inflate`() {
        val ctx = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext(),
            R.style.Theme_PageHop_Reader,
        )
        val map = LayoutInflater.from(ctx).inflate(R.layout.dialog_thumbs, null)
        assertNotNull(map.findViewById<View>(R.id.thumbGrid))
        // 双指缩放必须由 ZoomGrid 在子 View 之前拦截，否则会被缩略图吃掉事件
        assertTrue(map.findViewById<View>(R.id.thumbGrid) is ZoomGrid)
        assertNotNull(map.findViewById<View>(R.id.seekBar))
        assertNotNull(map.findViewById<View>(R.id.btnClose))
        // 页码输入框已经从布局里删掉了（一弹键盘就会把面板顶掉、滑条位置丢失），
        // 所以这里连 R.id.etPage 都不存在，属编译期保证。

        val item = LayoutInflater.from(ctx).inflate(R.layout.item_thumb, null)
        assertNotNull(item.findViewById<View>(R.id.imgThumb))
        assertNotNull(item.findViewById<View>(R.id.tvNum))

        val recent = LayoutInflater.from(ctx).inflate(R.layout.item_recent, null)
        assertNotNull(recent.findViewById<View>(R.id.tvName))
    }

    @Test
    fun `pen mode flag can be toggled on a page view`() {
        val ctx = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext(),
            R.style.Theme_PageHop_Reader,
        )
        val pv = PageView(ctx)
        // 默认必须关闭：否则一打开 PDF 就没法滚动（v0.2.0 的 bug）
        assertTrue("批注模式默认不能是开启的", !pv.penMode)
        pv.penMode = true
        assertTrue(pv.penMode)
        pv.penMode = false
        assertTrue(!pv.penMode)
    }

    @Test
    fun `soft night mode exists and builds a filter`() {
        val filter = ColorModes.filter(ColorModeKey.NIGHT_SOFT)
        assertNotNull(filter)
        // 背景要是灰的，不是纯黑
        val bg = ColorModes.background(ColorModeKey.NIGHT_SOFT)
        assertTrue("背景应该偏灰: #${Integer.toHexString(bg)}", bg != 0xFF000000.toInt())
    }

    @Test
    fun `brown theme exists and its background is warm brown`() {
        assertNotNull("棕褐对比应该有滤镜", ColorModes.filter(ColorModeKey.BROWN))
        val bg = ColorModes.background(ColorModeKey.BROWN)
        val r = (bg shr 16) and 0xFF
        val g = (bg shr 8) and 0xFF
        val b = bg and 0xFF
        // 棕色调：红 > 绿 > 蓝
        assertTrue("背景应偏棕: r=$r g=$g b=$b", r > g && g > b)
    }

    @Test
    fun `page view measures its height from aspect`() {
        val ctx = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext(),
            R.style.Theme_PageHop_Reader,
        )
        val pv = PageView(ctx)
        pv.aspect = 1.5f
        pv.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        assertEquals(1000, pv.measuredWidth)
        assertEquals(1500, pv.measuredHeight)
    }

    @Test
    fun `all color modes build a filter`() {
        for (mode in ColorModeKey.values()) {
            val filter = ColorModes.filter(mode)
            if (mode == ColorModeKey.NORMAL) {
                assertNull(filter)
            } else {
                assertNotNull("mode $mode should have a filter", filter)
            }
            ColorModes.background(mode)
        }
    }
}
