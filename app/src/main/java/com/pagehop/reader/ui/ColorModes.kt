package com.pagehop.reader.ui

import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import com.pagehop.reader.data.ColorModeKey

/**
 * 颜色模式全部做成绘制时的 ColorFilter：位图只渲染一次，
 * 切换模式不重新解码、不占额外内存，扫描件也一样生效。
 *
 * [active] 是"当前主题"的唯一来源。页面在 onDraw 时读它，而不是在绑定时被写死，
 * 这样滚出屏幕、被 RecyclerView 缓存起来又复用的页面也不会残留旧主题
 * （之前切主题后"下一页的下一页"还是旧色，就是这个原因）。
 */
object ColorModes {

    @Volatile
    var active: ColorModeKey = ColorModeKey.NORMAL

    private val NIGHT = ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        )
    )

    /**
     * 夜间柔和：先反色，再把亮度压回中间灰。
     * 纸（255）→ 57（深灰底），墨（0）→ 198（浅灰字），对比度从 1:∞ 降到约 1:3.4。
     */
    private val NIGHT_SOFT = ColorMatrix(
        floatArrayOf(
            -0.55f, 0f, 0f, 0f, 197.85f,
            0f, -0.55f, 0f, 0f, 197.85f,
            0f, 0f, -0.55f, 0f, 197.85f,
            0f, 0f, 0f, 1f, 0f,
        )
    )

    /**
     * 护眼米黄：白色落到 #F4E8D0（米黄纸），黑色抬到 #2D2D2D（暖灰墨），
     * 对比度从原来的约 13:1 压到 5:1 左右。
     *
     * 旧版是交叉混色的矩阵，白（255）算出来是 (255,255,245) —— 几乎还是纯白，
     * 所以看着刺眼。现在改成每通道一条独立的线性映射：out = a * in + b，
     * a 由"纸色 - 墨色"反算，扫描件偏灰的纸面（亮度 230~240）也落在米黄区间。
     */
    private val SEPIA = ColorMatrix(
        floatArrayOf(
            0.7804f, 0f, 0f, 0f, 45f,
            0f, 0.7333f, 0f, 0f, 45f,
            0f, 0f, 0.6392f, 0f, 45f,
            0f, 0f, 0f, 1f, 0f,
        )
    )

    /**
     * 棕褐对比：按参考图取色 —— 纸面 #FFE9CF，字色 #0E0300。
     * 增益按实测纸面灰度 254 反算：in=254 正好落到纸色，in=0 落到字色，灰阶按比例过渡。
     */
    private val BROWN = ColorMatrix(
        floatArrayOf(
            0.9488f, 0f, 0f, 0f, 14f,
            0f, 0.9055f, 0f, 0f, 3f,
            0f, 0f, 0.8150f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    )

    private val GRAY = ColorMatrix().apply { setSaturation(0f) }

    private val CONTRAST = ColorMatrix(
        floatArrayOf(
            1.32f, 0f, 0f, 0f, -41f,
            0f, 1.32f, 0f, 0f, -41f,
            0f, 0f, 1.32f, 0f, -41f,
            0f, 0f, 0f, 1f, 0f,
        )
    )

    private fun build(mode: ColorModeKey): ColorFilter? = when (mode) {
        ColorModeKey.NORMAL -> null
        ColorModeKey.NIGHT -> ColorMatrixColorFilter(NIGHT)
        ColorModeKey.NIGHT_SOFT -> ColorMatrixColorFilter(NIGHT_SOFT)
        ColorModeKey.GRAY -> ColorMatrixColorFilter(GRAY)
        ColorModeKey.SEPIA -> ColorMatrixColorFilter(SEPIA)
        ColorModeKey.BROWN -> ColorMatrixColorFilter(BROWN)
        ColorModeKey.CONTRAST -> ColorMatrixColorFilter(CONTRAST)
    }

    // 预先建好，绘制时直接取，避免每帧 new 一个 ColorFilter
    private val cache: Map<ColorModeKey, ColorFilter?> =
        ColorModeKey.values().associateWith { build(it) }

    fun filter(mode: ColorModeKey): ColorFilter? = cache[mode]

    /** 页面之外的背景色，跟模式匹配，避免露出突兀的边 */
    fun background(mode: ColorModeKey): Int = when (mode) {
        ColorModeKey.NORMAL -> 0xFF1B1B1B.toInt()
        ColorModeKey.NIGHT -> 0xFF000000.toInt()
        ColorModeKey.NIGHT_SOFT -> 0xFF393939.toInt()
        ColorModeKey.GRAY -> 0xFF000000.toInt()
        ColorModeKey.SEPIA -> 0xFF26201A.toInt()
        ColorModeKey.BROWN -> 0xFF3A2817.toInt()
        ColorModeKey.CONTRAST -> 0xFF000000.toInt()
    }
}
