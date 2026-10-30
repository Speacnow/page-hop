package com.pagehop.reader.data

import android.content.Context
import android.graphics.Color

/**
 * 应用级外观设置（不属于某一本书，所以放 SharedPreferences，而 BookState 是每本书一份）。
 */
object UiPrefs {

    private const val NAME = "ui"
    private const val K_ALPHA = "preview_alpha"
    private const val K_GRID = "grid_lines"
    private const val K_GRID_COLOR = "grid_color"
    private const val K_BAR = "bar_color"
    private const val K_BAR_FG = "bar_content"
    private const val K_SPANS = "thumb_spans"
    private const val K_VOLUME = "volume_keys"

    /** 音量键翻页：默认关闭，开了之后音量上=上一页、音量下=下一页 */
    var volumeKeys: Boolean = false
        private set

    /** 长按预览大图的透明度：越小越透（0 = 全透明，1 = 不透明） */
    var previewAlpha: Float = 0.32f
        private set

    /** 缩略图面板上次用的列数（下次打开沿用） */
    var thumbSpans: Int = 4
        private set

    /** 长按预览时是否画红色棋盘格线 */
    var gridLines: Boolean = true
        private set

    /** 棋盘格线颜色 */
    var gridColor: Int = 0xFFE53935.toInt()
        private set

    /** 顶栏/底栏背景色 */
    var barColor: Int = 0xFF005E78.toInt()
        private set

    /** 顶栏/底栏上前景（图标、文字）颜色 */
    var barContent: Int = 0xFFFFFFFF.toInt()
        private set

    /**
     * 顶栏配色候选：名字 → 背景色。
     * 前景（图标/文字）不在这里写死，由 [contentFor] 按背景亮度自动算。
     */
    val barThemes: List<Pair<String, Int>> = listOf(
        "深青" to 0xFF005E78.toInt(),
        "深蓝" to 0xFF1B2A4A.toInt(),
        "黑色" to 0xFF101010.toInt(),
        "墨绿" to 0xFF1B5E20.toInt(),
        "粉色" to 0xFFE91E63.toInt(),
        "樱花粉" to 0xFFFFB7C5.toInt(),
        "白色" to 0xFFF4F4F4.toInt(),
        "灰色" to 0xFF546E7A.toInt(),
    )

    /** 棋盘格线颜色候选 */
    val lineColors: List<Pair<String, Int>> = listOf(
        "红" to 0xFFE53935.toInt(),
        "橙" to 0xFFFB8C00.toInt(),
        "黄" to 0xFFFDD835.toInt(),
        "绿" to 0xFF43A047.toInt(),
        "蓝" to 0xFF1E88E5.toInt(),
        "白" to 0xFFFFFFFF.toInt(),
    )

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        previewAlpha = p.getFloat(K_ALPHA, 0.32f).coerceIn(0f, 1f)
        thumbSpans = p.getInt(K_SPANS, 4)
        volumeKeys = p.getBoolean(K_VOLUME, false)
        gridLines = p.getBoolean(K_GRID, true)
        gridColor = p.getInt(K_GRID_COLOR, 0xFFE53935.toInt())
        barColor = p.getInt(K_BAR, 0xFF005E78.toInt())
        // 前景色不存了，直接按背景亮度算：老数据里手工配的浅色前景会在这里自动纠正
        barContent = contentFor(barColor)
    }

    private fun edit(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()

    fun setPreviewAlpha(ctx: Context, v: Float) {
        previewAlpha = v.coerceIn(0f, 1f)
        edit(ctx).putFloat(K_ALPHA, previewAlpha).apply()
    }

    fun setGridLines(ctx: Context, on: Boolean) {
        gridLines = on
        edit(ctx).putBoolean(K_GRID, on).apply()
    }

    fun setGridColor(ctx: Context, c: Int) {
        gridColor = c
        edit(ctx).putInt(K_GRID_COLOR, c).apply()
    }

    fun setThumbSpans(ctx: Context, spans: Int) {
        thumbSpans = spans
        edit(ctx).putInt(K_SPANS, spans).apply()
    }

    fun setVolumeKeys(ctx: Context, on: Boolean) {
        volumeKeys = on
        edit(ctx).putBoolean(K_VOLUME, on).apply()
    }

    fun setBarTheme(ctx: Context, bg: Int) {
        barColor = bg
        barContent = contentFor(bg)
        edit(ctx).putInt(K_BAR, bg).putInt(K_BAR_FG, barContent).apply()
    }

    /**
     * 顶栏/底栏上的图标和文字该用什么颜色：**背景亮就用深色，背景暗就用浅色**。
     * 按感知亮度加权（人眼对绿色最敏感），阈值 0.6 大致就是"浅色底/深色底"的分界。
     */
    fun contentFor(bg: Int): Int {
        val lum = (0.2126f * Color.red(bg) + 0.7152f * Color.green(bg) + 0.0722f * Color.blue(bg)) / 255f
        return if (lum > 0.6f) 0xFF141414.toInt() else 0xFFFFFFFF.toInt()
    }
}
