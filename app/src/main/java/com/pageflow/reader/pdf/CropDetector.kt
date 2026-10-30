package com.pageflow.reader.pdf

import android.graphics.Bitmap
import com.pageflow.reader.data.CropRect

/**
 * 从渲染好的图里找出**正文**的边界（扫描件没有文字层，只能靠像素）。
 *
 * 只返回正文框本身，不带留白 —— 留白由 [com.pageflow.reader.data.CropMath] 统一处理，
 * 这样"裁得紧不紧"可以单独调，不用动检测逻辑。
 */
object CropDetector {

    private const val DARK_THRESHOLD = 205

    fun detect(bmp: Bitmap): CropRect? {
        val w = bmp.width
        val h = bmp.height
        if (w < 16 || h < 16) return null
        val px = IntArray(w * h)
        return try {
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            detect(px, w, h)
        } catch (e: Exception) {
            null
        }
    }

    /** 纯计算版本（不碰 Bitmap），方便单元测试 */
    fun detect(px: IntArray, w: Int, h: Int): CropRect? {
        if (w < 16 || h < 16 || px.size < w * h) return null

        val rowCount = IntArray(h)
        val colCount = IntArray(w)

        var y = 0
        while (y < h) {
            val base = y * w
            var x = 0
            while (x < w) {
                val c = px[base + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val lum = (r * 77 + g * 150 + b * 29) shr 8
                if (lum < DARK_THRESHOLD) {
                    rowCount[y]++
                    colCount[x]++
                }
                x++
            }
            y++
        }

        // 一行/一列至少要这么多暗像素才算"有内容"，否则当成扫描噪点
        val rowMin = maxOf(3, w / 150)
        val colMin = maxOf(3, h / 150)

        var top = -1
        var bottom = -1
        var left = -1
        var right = -1

        for (i in 0 until h) if (rowCount[i] >= rowMin) { top = i; break }
        for (i in h - 1 downTo 0) if (rowCount[i] >= rowMin) { bottom = i; break }
        for (i in 0 until w) if (colCount[i] >= colMin) { left = i; break }
        for (i in w - 1 downTo 0) if (colCount[i] >= colMin) { right = i; break }

        // 整页空白（封面、纯图页）或者异常
        if (top < 0 || bottom < 0 || left < 0 || right < 0) return null

        // 只留一点点安全边，防止把字脚/笔画切掉；真正的留白在 CropMath 里加
        val padX = maxOf(1, (w * 0.005f).toInt())
        val padY = maxOf(1, (h * 0.005f).toInt())

        val l = ((left - padX).coerceAtLeast(0)).toFloat() / w
        val t = ((top - padY).coerceAtLeast(0)).toFloat() / h
        val r = ((right + padX).coerceAtMost(w - 1)).toFloat() / w
        val b = ((bottom + padY).coerceAtMost(h - 1)).toFloat() / h

        // 检测到的正文太小，可能只是页面上一个图案/页码，不可信
        if (r - l < 0.3f || b - t < 0.3f) return null

        return CropRect(l, t, r, b)
    }
}
