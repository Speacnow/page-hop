package com.pageflow.reader.ui

import android.graphics.Canvas
import android.graphics.Paint
import androidx.recyclerview.widget.RecyclerView

/**
 * 长按预览时，在每个缩略图四周描一圈线，形成"棋盘格"参考。
 *
 * 用 ItemDecoration 画在网格内部：它会连着网格一起被 RenderEffect 模糊，
 * 看起来就是"毛玻璃底板上的格线"；半透明的大图盖在这一层上面，
 * 所以透过大图还能看到淡淡的格子，方便判断手指停在哪一格。
 * 相邻格子共用一条边，天然对齐，滚动时也不会错位。
 */
class GridLinesDecoration(density: Float) : RecyclerView.ItemDecoration() {

    var active: Boolean = false

    var color: Int = 0xFFE53935.toInt()
        set(value) {
            field = value
            paint.color = value
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        color = 0xFFE53935.toInt()
    }

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (!active) return
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i) ?: continue
            c.drawRect(
                child.left.toFloat(),
                child.top.toFloat(),
                child.right.toFloat(),
                child.bottom.toFloat(),
                paint,
            )
        }
    }
}
