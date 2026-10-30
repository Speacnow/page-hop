package com.pagehop.reader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页高比例的回归测试。
 *
 * 曾经这里算错：用 PDF 原始页比例排版，而显示的是裁边后的区域，
 * 于是页高比实际高约 10%。每次重绑（换主题 / 缩放停手）页面都会
 * 先被拉高、渲染结果回来又被改回，看起来就是闪一下。
 */
class PageMetricsTest {

    @Test
    fun `no crop keeps the raw page aspect`() {
        assertEquals(1.4142f, PageMetrics.displayAspect(1.4142f, CropRect.FULL), 0.0001f)
    }

    @Test
    fun `cropped page is measured by the visible area`() {
        // 正文框：宽 0.784、高 0.719 → 显示比例应比原始比例矮
        val crop = CropRect(0.094f, 0.198f, 0.878f, 0.917f)
        val a = PageMetrics.displayAspect(1.4142f, crop)
        assertEquals(1.4142f * (crop.h / crop.w), a, 0.0001f)
        assertTrue("裁边后不该比原始页更高", a < 1.4142f)
    }

    @Test
    fun `unknown raw aspect falls back to a4`() {
        assertEquals(
            PageMetrics.DEFAULT_ASPECT,
            PageMetrics.displayAspect(0f, CropRect.FULL),
            0.0001f,
        )
    }

    @Test
    fun `degenerate crop falls back to full page`() {
        val broken = CropRect(0.5f, 0.5f, 0.5f, 0.5f)
        assertEquals(1.4f, PageMetrics.displayAspect(1.4f, broken), 0.0001f)
    }
}
