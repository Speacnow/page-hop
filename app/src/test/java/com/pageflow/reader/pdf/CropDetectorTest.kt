package com.pageflow.reader.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 裁边算法的纯逻辑测试。用合成像素数组，不依赖 Android 运行时。
 */
class CropDetectorTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    private fun blank(w: Int, h: Int) = IntArray(w * h) { white }

    private fun fill(px: IntArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                px[y * w + x] = color
            }
        }
    }

    @Test
    fun `finds content box inside white margins`() {
        val w = 200
        val h = 300
        val px = blank(w, h)
        fill(px, w, 40, 60, 160, 240, black)

        val crop = CropDetector.detect(px, w, h)
        assertNotNull(crop)
        crop!!
        assertEquals(0.20f, crop.l, 0.03f)
        assertEquals(0.20f, crop.t, 0.03f)
        assertEquals(0.80f, crop.r, 0.03f)
        assertEquals(0.80f, crop.b, 0.03f)
        // 必须留在页面范围内
        assertTrue(crop.l >= 0f && crop.t >= 0f && crop.r <= 1f && crop.b <= 1f)
        // 裁掉后正文应该明显变大
        assertTrue(crop.w > 0.5f && crop.h > 0.5f)
    }

    @Test
    fun `blank page returns null so we do not crop to nothing`() {
        val crop = CropDetector.detect(blank(200, 300), 200, 300)
        assertNull(crop)
    }

    @Test
    fun `isolated specks are ignored`() {
        val w = 200
        val h = 300
        val px = blank(w, h)
        // 正文
        fill(px, w, 50, 80, 150, 220, black)
        // 四个孤立噪点，每个都只占一行/一列 1 个像素
        for ((x, y) in listOf(5 to 5, 190 to 10, 8 to 290, 180 to 280)) {
            px[y * w + x] = 0xFF303030.toInt()
        }

        val crop = CropDetector.detect(px, w, h)
        assertNotNull(crop)
        // 不应该被噪点拉大到整页
        assertTrue("l=${crop!!.l} t=${crop.t}", crop.l > 0.2f && crop.t > 0.2f)
        assertTrue("r=${crop.r} b=${crop.b}", crop.r < 0.8f && crop.b < 0.8f)
    }

    @Test
    fun `content touching page edge is clamped inside range`() {
        val w = 200
        val h = 300
        val px = blank(w, h)
        // 黑边扫描件：四周留一圈黑
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (x < 6 || y < 6 || x >= w - 6 || y >= h - 6) px[y * w + x] = black
            }
        }
        fill(px, w, 20, 20, 180, 280, black)

        val crop = CropDetector.detect(px, w, h)
        if (crop != null) {
            assertTrue(crop.l >= 0f && crop.t >= 0f)
            assertTrue(crop.r <= 1f && crop.b <= 1f)
            assertTrue(crop.r > crop.l && crop.b > crop.t)
        }
    }

    @Test
    fun `too little content is refused`() {
        val w = 200
        val h = 300
        val px = blank(w, h)
        // 只有很小的一个点，裁掉会损失太多
        fill(px, w, 95, 145, 105, 155, black)
        assertNull(CropDetector.detect(px, w, h))
    }

    @Test
    fun `degenerate sizes return null`() {
        assertNull(CropDetector.detect(IntArray(0), 0, 0))
        assertNull(CropDetector.detect(blank(10, 10), 10, 10))
    }
}
