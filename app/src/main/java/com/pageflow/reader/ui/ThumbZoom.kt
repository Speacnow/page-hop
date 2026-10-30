package com.pageflow.reader.ui

import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * 缩略图地图的密度换算：把双指缩放比例换算成网格列数。
 *
 * 用**对数步进**而不是直接按比例相除：按比例相除时，2 列往 3 列要 33% 的距离变化，
 * 而 9 列往 10 列只要 11%，手感忽快忽慢；而且从 2 列稍微一捏就直接跳到 4、5 列。
 * 现在是"每变化 25% 的距离 = 挪一列"，无论当前几列，一格的手感都一样。
 */
object ThumbZoom {

    const val MIN_SPANS = 2
    const val MAX_SPANS = 10
    const val DEFAULT_SPANS = 4

    /** 双指距离每变化这个比例，列数挪一格 */
    const val STEP_FACTOR = 1.25f

    /**
     * @param startSpan 缩放开始时的列数
     * @param accumulated 从手势开始累计的缩放比例（>1 表示双指张开）
     */
    fun spanFor(startSpan: Int, accumulated: Float): Int {
        val safeStart = startSpan.coerceIn(MIN_SPANS, MAX_SPANS)
        if (accumulated <= 0f || accumulated.isNaN()) return safeStart

        // 张开手指 -> 格子变大 -> 列数变少
        val steps = (ln(accumulated.toDouble()) / ln(STEP_FACTOR.toDouble())).roundToInt()
        return (safeStart - steps).coerceIn(MIN_SPANS, MAX_SPANS)
    }
}
