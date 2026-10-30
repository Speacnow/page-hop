package com.pageflow.reader.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手写笔迹：编码/解码、点选距离、抽稀。
 */
class InkStrokeTest {

    private fun stroke(vararg xy: Float) = InkStroke(
        id = "s1",
        page = 3,
        points = xy.toList(),
        color = 0xFFFFC400.toInt(),
    )

    @Test
    fun `points round trip through json`() {
        val s = stroke(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f)
        val back = InkStroke.fromJson(s.toJson())
        assertNotNull(back)
        assertEquals(s.id, back!!.id)
        assertEquals(s.page, back.page)
        assertEquals(s.color, back.color)
        assertEquals(s.pointCount, back.pointCount)
        assertEquals(0.1f, back.points[0], 0.001f)
        assertEquals(0.6f, back.points[5], 0.001f)
    }

    @Test
    fun `empty or broken points are rejected`() {
        val o = JSONObject().put("id", "x").put("page", 0).put("points", "").put("color", 1)
        assertNull(InkStroke.fromJson(o))

        val o2 = JSONObject().put("id", "x").put("page", 0).put("points", "abc").put("color", 1)
        assertNull(InkStroke.fromJson(o2))
    }

    @Test
    fun `distance is zero on the line and grows away from it`() {
        // 从 (0.1,0.1) 到 (0.9,0.1) 的横线
        val s = stroke(0.1f, 0.1f, 0.9f, 0.1f)
        assertTrue(s.distanceTo(0.5f, 0.1f) < 0.001f)
        assertTrue(s.distanceTo(0.5f, 0.1005f) < 0.01f)
        assertTrue(s.distanceTo(0.5f, 0.5f) > 0.3f)
        // 线段两端之外按端点算
        assertTrue(s.distanceTo(0f, 0.1f) > 0.09f)
    }

    @Test
    fun `simplify keeps endpoints and drops redundant middle points`() {
        // 一条直线上的很多点，抽稀后应只剩两端
        val pts = mutableListOf<Float>()
        for (i in 0..50) {
            pts.add(i / 50f)
            pts.add(0.5f)
        }
        val simplified = InkStroke.simplify(pts, 0.001f)
        // points 是扁平数组：2 个点 = 4 个 float
        assertEquals(4, simplified.size)
        assertEquals(pts.first(), simplified.first(), 0.0001f)
        assertEquals(pts[pts.size - 2], simplified[simplified.size - 2], 0.0001f)
    }

    @Test
    fun `simplify preserves a sharp corner`() {
        val pts = listOf(
            0f, 0f,
            0.5f, 0.05f,
            1f, 1f,
        )
        val simplified = InkStroke.simplify(pts, 0.001f)
        // 拐点必须留着，否则字会变形
        assertTrue(simplified.size >= 6)
        assertEquals(3, simplified.size / 2)
    }
}
