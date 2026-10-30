package com.pagehop.reader.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** 缩略图地图的双指缩放 → 列数换算 */
class ThumbZoomTest {

    @Test
    fun `spreading fingers makes cells bigger`() {
        assertEquals(2, ThumbZoom.spanFor(4, 2f))
    }

    @Test
    fun `moderate spread already reaches two columns`() {
        assertEquals(2, ThumbZoom.spanFor(4, 1.5f))
    }

    @Test
    fun `every step needs roughly the same pinch`() {
        // 回归用例：从 2 列开始，捏 25% 只该挪一列（旧实现会直接跳到 4、5）
        assertEquals(3, ThumbZoom.spanFor(2, 0.8f))
        assertEquals(4, ThumbZoom.spanFor(2, 0.64f))
        // 从 8 列开始，同样的 25% 也只挪一列，手感一致
        assertEquals(9, ThumbZoom.spanFor(8, 0.8f))
        assertEquals(7, ThumbZoom.spanFor(8, 1.25f))
    }

    @Test
    fun `pinching makes cells denser`() {
        assertEquals(7, ThumbZoom.spanFor(4, 0.5f))
        assertEquals(10, ThumbZoom.spanFor(9, 0.6f))
    }

    @Test
    fun `clamped to the allowed range`() {
        assertEquals(ThumbZoom.MIN_SPANS, ThumbZoom.spanFor(4, 100f))
        assertEquals(ThumbZoom.MAX_SPANS, ThumbZoom.spanFor(8, 0.05f))
        assertEquals(ThumbZoom.MIN_SPANS, ThumbZoom.spanFor(ThumbZoom.MIN_SPANS, 1f))
        assertEquals(ThumbZoom.MAX_SPANS, ThumbZoom.spanFor(ThumbZoom.MAX_SPANS, 1f))
    }

    @Test
    fun `invalid scale factor keeps current span`() {
        assertEquals(4, ThumbZoom.spanFor(4, 0f))
        assertEquals(4, ThumbZoom.spanFor(4, Float.NaN))
        // 越界的起始值也要被夹住
        assertEquals(ThumbZoom.MAX_SPANS, ThumbZoom.spanFor(99, Float.NaN))
    }

    @Test
    fun `tiny nudge does not change span`() {
        // 5% 的抖动应当被忽略
        assertEquals(4, ThumbZoom.spanFor(4, 0.95f))
        assertEquals(4, ThumbZoom.spanFor(4, 1.05f))
    }
}
