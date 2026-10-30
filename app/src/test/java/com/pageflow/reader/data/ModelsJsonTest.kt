package com.pageflow.reader.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 高亮和裁剪框的 JSON 存取测试 —— 这是"笔记/进度会不会丢"的关键路径。
 */
class ModelsJsonTest {

    @Test
    fun `crop rect round trip`() {
        val crop = CropRect(0.05f, 0.03f, 0.95f, 0.97f)
        val back = CropRect.fromJson(crop.toJson())
        assertEquals(crop, back)
    }

    @Test
    fun `crop rect handles wrong field types`() {
        assertNull(CropRect.fromJson(null))
        // 字段类型不对不应该崩，直接当作无效
        assertNull(CropRect.fromJson(JSONObject().put("l", "abc").put("t", 0).put("r", 1).put("b", 1)))
        // 字段缺失同理
        assertNull(CropRect.fromJson(JSONObject().put("l", 0.1)))
    }

    @Test
    fun `highlight round trip keeps note and color`() {
        val h = Highlight(
            id = "abc-123",
            page = 42,
            l = 0.1f, t = 0.2f, r = 0.6f, b = 0.3f,
            color = 0xFFFFC400.toInt(),
            note = "第三章 这里提到的概念要再看一遍\n第二行笔记",
            createdAt = 1_700_000_000_000L,
        )
        val back = Highlight.fromJson(h.toJson())
        assertEquals(h, back)
        assertEquals(h.note, back?.note)
        assertEquals(h.color, back?.color)
    }

    @Test
    fun `highlight without note still loads`() {
        val o = JSONObject()
            .put("id", "x")
            .put("page", 1)
            .put("l", 0.1).put("t", 0.1).put("r", 0.2).put("b", 0.2)
            .put("color", 0xFFFFC400.toInt())
        val back = Highlight.fromJson(o)
        assertEquals("", back?.note)
    }

    @Test
    fun `highlight with missing fields is rejected instead of crashing`() {
        assertNull(Highlight.fromJson(JSONObject("{}")))
        assertNull(Highlight.fromJson(JSONObject("""{"id":"a"}""")))
    }

    @Test
    fun `hit test matches inside and rejects outside`() {
        val h = Highlight("id", 0, 0.2f, 0.2f, 0.5f, 0.4f, 0xFF000000.toInt())
        assertTrue(h.contains(0.3f, 0.3f))
        assertTrue(h.contains(0.2f, 0.2f))
        assertTrue(!h.contains(0.1f, 0.3f))
        assertTrue(!h.contains(0.3f, 0.9f))
    }
}
