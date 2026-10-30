package com.pagehop.reader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 裁剪留白规则测试 —— 用户反馈"裁得太多"，这里把边界行为钉死。
 */
class CropMathTest {

    @Test
    fun `pad expands content box on all four sides`() {
        val content = CropRect(0.20f, 0.20f, 0.80f, 0.80f)
        val out = CropMath.expand(content, 0.035f)
        assertEquals(0.165f, out.l, 0.001f)
        assertEquals(0.165f, out.t, 0.001f)
        assertEquals(0.835f, out.r, 0.001f)
        assertEquals(0.835f, out.b, 0.001f)
    }

    @Test
    fun `never removes more than the cap from one side`() {
        // 正文只占中间一小块，按理能裁很多，但必须被 18% 上限拦住
        val content = CropRect(0.40f, 0.40f, 0.60f, 0.60f)
        val out = CropMath.expand(content, 0.035f)
        assertEquals(CropMath.MAX_CUT_PER_SIDE, out.l, 0.001f)
        assertEquals(CropMath.MAX_CUT_PER_SIDE, out.t, 0.001f)
        assertEquals(1f - CropMath.MAX_CUT_PER_SIDE, out.r, 0.001f)
        assertEquals(1f - CropMath.MAX_CUT_PER_SIDE, out.b, 0.001f)
        // 至少保留 64% 的页面给你看
        assertTrue(out.w >= 0.64f - 0.001f)
        assertTrue(out.h >= 0.64f - 0.001f)
    }

    @Test
    fun `content near the edge is not expanded outside the page`() {
        val content = CropRect(0.005f, 0.01f, 0.995f, 0.99f)
        val out = CropMath.expand(content, 0.035f)
        assertEquals(0f, out.l, 0.0001f)
        assertEquals(0f, out.t, 0.0001f)
        assertEquals(1f, out.r, 0.0001f)
        assertEquals(1f, out.b, 0.0001f)
    }

    @Test
    fun `zero pad keeps content box but still capped`() {
        val content = CropRect(0.25f, 0.25f, 0.75f, 0.75f)
        val out = CropMath.expand(content, 0f)
        assertEquals(0.18f, out.l, 0.001f)   // 仍然受上限保护
        assertEquals(0.82f, out.r, 0.001f)
    }

    @Test
    fun `huge pad is clamped`() {
        val content = CropRect(0.20f, 0.20f, 0.80f, 0.80f)
        val out = CropMath.expand(content, 5f)
        // 最多 10% 留白
        assertEquals(0.10f, out.l, 0.001f)
    }

    @Test
    fun `tiny content never collapses the visible area`() {
        val content = CropRect(0.49f, 0.49f, 0.51f, 0.51f)
        val out = CropMath.expand(content, 0f)
        assertTrue("width ${out.w}", out.w >= 0.29f)
        assertTrue("height ${out.h}", out.h >= 0.29f)
        assertTrue(out.l >= 0f && out.r <= 1f && out.t >= 0f && out.b <= 1f)
    }
}
