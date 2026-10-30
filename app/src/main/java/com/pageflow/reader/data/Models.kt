package com.pageflow.reader.data

import org.json.JSONObject
import kotlin.math.hypot
import kotlin.math.min

/** 颜色模式 */
enum class ColorModeKey { NORMAL, NIGHT, NIGHT_SOFT, GRAY, SEPIA, BROWN, CONTRAST }

/**
 * 裁剪框：内容区在整页中的位置，全部用 0..1 的比例表示。
 * l/t 是内容区左上角，r/b 是右下角。
 */
data class CropRect(val l: Float, val t: Float, val r: Float, val b: Float) {
    val w: Float get() = (r - l).coerceAtLeast(0.02f)
    val h: Float get() = (b - t).coerceAtLeast(0.02f)

    fun toJson(): JSONObject = JSONObject().apply {
        put("l", l.toDouble()); put("t", t.toDouble())
        put("r", r.toDouble()); put("b", b.toDouble())
    }

    companion object {
        val FULL = CropRect(0f, 0f, 1f, 1f)

        fun fromJson(o: JSONObject?): CropRect? {
            if (o == null) return null
            return try {
                CropRect(
                    o.getDouble("l").toFloat(),
                    o.getDouble("t").toFloat(),
                    o.getDouble("r").toFloat(),
                    o.getDouble("b").toFloat(),
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

/** 一条文字批注：锚在页面上某个位置，字号按页宽比例存，缩放时自动跟着变 */
data class TextNote(
    val id: String,
    val page: Int,
    /** 锚点，页面比例坐标（左上角） */
    val x: Float,
    val y: Float,
    val text: String,
    val color: Int = 0xFFFF5252.toInt(),
    /** 字号：占页宽的比例 */
    val size: Float = 0.030f,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("page", page)
        put("x", x.toDouble()); put("y", y.toDouble())
        put("text", text); put("color", color)
        put("size", size.toDouble()); put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(o: JSONObject?): TextNote? {
            if (o == null) return null
            return try {
                TextNote(
                    id = o.getString("id"),
                    page = o.getInt("page"),
                    x = o.getDouble("x").toFloat(),
                    y = o.getDouble("y").toFloat(),
                    text = o.getString("text"),
                    color = o.optInt("color", 0xFFFF5252.toInt()),
                    size = o.optDouble("size", 0.030).toFloat(),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

/** 一条高亮 / 框注 */
data class Highlight(
    val id: String,
    val page: Int,
    val l: Float,
    val t: Float,
    val r: Float,
    val b: Float,
    val color: Int,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun contains(fx: Float, fy: Float): Boolean =
        fx >= l - 0.005f && fx <= r + 0.005f && fy >= t - 0.005f && fy <= b + 0.005f

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("page", page)
        put("l", l.toDouble()); put("t", t.toDouble())
        put("r", r.toDouble()); put("b", b.toDouble())
        put("color", color); put("note", note); put("ts", createdAt)
    }

    companion object {
        fun fromJson(o: JSONObject): Highlight? = try {
            Highlight(
                id = o.getString("id"),
                page = o.getInt("page"),
                l = o.getDouble("l").toFloat(),
                t = o.getDouble("t").toFloat(),
                r = o.getDouble("r").toFloat(),
                b = o.getDouble("b").toFloat(),
                color = o.getInt("color"),
                note = o.optString("note", ""),
                createdAt = o.optLong("ts", 0L),
            )
        } catch (e: Exception) {
            null
        }
    }
}

/** 一条手写笔迹（手指画出来的折线），坐标是 0..1 的页面比例 */
data class InkStroke(
    val id: String,
    val page: Int,
    /** 扁平点序列：x0,y0,x1,y1,... */
    val points: List<Float>,
    val color: Int,
    /** 笔宽，占页面宽度的比例 */
    val width: Float = 0.0038f,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
) {
    val pointCount: Int get() = points.size / 2

    /** 点到折线的最近距离（页面比例坐标），用于点选 */
    fun distanceTo(px: Float, py: Float): Float {
        val n = points.size
        if (n < 2) return 1f
        if (n == 2) return hypot(px - points[0], py - points[1])
        var best = Float.MAX_VALUE
        var i = 0
        while (i + 3 < n) {
            best = min(best, segDist(px, py, points[i], points[i + 1], points[i + 2], points[i + 3]))
            i += 2
        }
        return best
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("page", page)
        put("points", encodePoints(points))
        put("color", color)
        put("width", width.toDouble())
        put("note", note)
        put("ts", createdAt)
    }

    companion object {
        fun fromJson(o: JSONObject): InkStroke? = try {
            val pts = decodePoints(o.getString("points"))
            if (pts.size < 2) null else InkStroke(
                id = o.getString("id"),
                page = o.getInt("page"),
                points = pts,
                color = o.getInt("color"),
                width = o.optDouble("width", 0.0038).toFloat(),
                note = o.optString("note", ""),
                createdAt = o.optLong("ts", 0L),
            )
        } catch (e: Exception) {
            null
        }

        fun encodePoints(points: List<Float>): String {
            val sb = StringBuilder(points.size * 8)
            var i = 0
            while (i + 1 < points.size) {
                if (sb.isNotEmpty()) sb.append(';')
                sb.append(round4(points[i])).append(',').append(round4(points[i + 1]))
                i += 2
            }
            return sb.toString()
        }

        fun decodePoints(s: String): List<Float> {
            if (s.isBlank()) return emptyList()
            val out = ArrayList<Float>(64)
            for (pair in s.split(';')) {
                val comma = pair.indexOf(',')
                if (comma <= 0) continue
                val x = pair.substring(0, comma).toFloatOrNull() ?: continue
                val y = pair.substring(comma + 1).toFloatOrNull() ?: continue
                out.add(x); out.add(y)
            }
            return out
        }

        private fun round4(v: Float): String =
            ((v * 10000).toInt() / 10000f).toString()

        private fun segDist(
            px: Float, py: Float,
            x1: Float, y1: Float, x2: Float, y2: Float,
        ): Float {
            val dx = x2 - x1
            val dy = y2 - y1
            val len2 = dx * dx + dy * dy
            if (len2 < 1e-9f) return hypot(px - x1, py - y1)
            var t = ((px - x1) * dx + (py - y1) * dy) / len2
            t = t.coerceIn(0f, 1f)
            return hypot(px - (x1 + t * dx), py - (y1 + t * dy))
        }

        /** Ramer–Douglas–Peucker 简化：手写点太多会把 JSON 撑爆，也会拖慢绘制 */
        fun simplify(points: List<Float>, tolerance: Float): List<Float> {
            val n = points.size / 2
            if (n <= 2) return points
            val keep = BooleanArray(n)
            keep[0] = true
            keep[n - 1] = true
            rdp(points, 0, n - 1, tolerance, keep)

            val out = ArrayList<Float>(n * 2)
            for (i in 0 until n) {
                if (keep[i]) {
                    out.add(points[i * 2])
                    out.add(points[i * 2 + 1])
                }
            }
            return out
        }

        private fun rdp(points: List<Float>, start: Int, end: Int, tol: Float, keep: BooleanArray) {
            if (end <= start + 1) return
            var maxD = -1f
            var idx = -1
            val x1 = points[start * 2]; val y1 = points[start * 2 + 1]
            val x2 = points[end * 2]; val y2 = points[end * 2 + 1]
            for (i in start + 1 until end) {
                val d = segDist(points[i * 2], points[i * 2 + 1], x1, y1, x2, y2)
                if (d > maxD) { maxD = d; idx = i }
            }
            if (maxD > tol && idx > 0) {
                keep[idx] = true
                rdp(points, start, idx, tol, keep)
                rdp(points, idx, end, tol, keep)
            }
        }
    }
}

/** 一本书的持久化状态 */
class BookState(
    var uri: String = "",
    var name: String = "",
    var page: Int = 0,
    var total: Int = 0,
    var colorMode: ColorModeKey = ColorModeKey.NORMAL,
    var cropEnabled: Boolean = true,
    /** 自动裁边后保留的留白比例（0.02 = 每边留 2% 页面） */
    var cropPad: Float = 0.02f,
    /** 用户手动设定的全书裁剪（优先于自动检测） */
    var manualCrop: CropRect? = null,
    /** 每页自动检测出来的裁剪 */
    val pageCrops: HashMap<Int, CropRect> = HashMap(),
    val highlights: MutableList<Highlight> = mutableListOf(),
    val strokes: MutableList<InkStroke> = mutableListOf(),
    /** 用户加的书签（页码，从 0 开始，按"添加的先后"排列） */
    val bookmarks: MutableList<Int> = mutableListOf(),
    val textNotes: MutableList<TextNote> = mutableListOf(),
    var updatedAt: Long = 0L,
)

data class RecentEntry(
    val uri: String,
    val name: String,
    val page: Int,
    val total: Int,
    val updatedAt: Long,
)
