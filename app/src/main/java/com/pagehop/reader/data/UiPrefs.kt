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
    private const val K_VOLUME_OVERLAP = "volume_overlap"
    private const val K_CORNER_BTN = "corner_button"
    private const val K_TAP_PAGING = "tap_paging"
    private const val K_TAP_HORIZ = "tap_paging_horizontal"
    private const val K_FLIP_HINT = "flip_hint"
    private const val K_HINT_INSET = "flip_hint_inset"
    private const val K_HINT_ALPHA = "flip_hint_alpha"
    private const val K_HINT_WIDTH = "flip_hint_width"
    private const val K_HINT_COLOR = "flip_hint_color"
    private const val K_ROTATE_AUTO = "rotate_auto"
    private const val K_ROTATE_LAND = "rotate_lock_landscape"

    /**
     * 旋转模式：true = 跟随设备重力（SCREEN_ORIENTATION_SENSOR），
     * false = 锁定方向（[rotateLockLandscape] 决定锁横还是锁竖）。
     *
     * 注意这里"锁定"是**锁死在某个方向**，不是把控制权交还给系统——
     * 系统那个"自动旋转"开关对本 App 完全无效（符合"旋转独立于系统"的要求）。
     */
    var rotateAuto: Boolean = true
        private set

    /** 锁定时是不是横屏：默认 false（竖屏），和之前"系统锁竖屏时 App 也是竖屏"一致 */
    var rotateLockLandscape: Boolean = false
        private set

    /** 音量键翻页：默认开启（音量上 = 往前一屏、音量下 = 往后一屏） */
    var volumeKeys: Boolean = true
        private set

    /**
     * 音量键翻屏时上下两屏重叠多少（百分比）。
     * 默认 10%：上一屏最后两三行留在下一屏顶上，接得上、又不会白读太多。
     */
    var volumeOverlap: Int = 10
        private set

    /**
     * 批注模式下左下角的翻页按钮（短按往后、长按/双击往前）。
     * 只在"笔"或"文字批注"开着时出现，所以默认开也不会碍事。
     */
    var cornerButton: Boolean = true
        private set

    /**
     * 非批注模式下点屏幕翻页：屏幕中间一半还是"开关工具栏"，
     * 两侧/上下四分之一是翻页。
     */
    var tapPaging: Boolean = true
        private set

    /** 触摸翻页方向：true = 左右（左往前、右往后），false = 上下（上往前、下往后，默认） */
    var tapPagingHorizontal: Boolean = false
        private set

    /** 翻完一屏画一条分界线，标出上一屏到哪儿结束（默认开） */
    var flipHint: Boolean = true
        private set

    /** 提示线左右各留多少白（屏幕宽度的百分比，默认 3%） */
    var flipHintInset: Int = 3
        private set

    /** 提示线的不透明度（百分比，100 = 不透明；默认 40，淡一点不抢字） */
    var flipHintAlpha: Int = 40
        private set

    /** 提示线粗细（dp，默认 2） */
    var flipHintWidth: Int = 2
        private set

    /** 提示线颜色 */
    var flipHintColor: Int = 0xFFFFB7C5.toInt() // 樱花粉
        private set

    /** 长按预览大图的透明度：越小越透（0 = 全透明，1 = 不透明） */
    var previewAlpha: Float = 0.7f
        private set

    /** 缩略图面板上次用的列数（下次打开沿用） */
    var thumbSpans: Int = 5
        private set

    /** 长按预览时是否画红色棋盘格线 */
    var gridLines: Boolean = true
        private set

    /** 棋盘格线颜色 */
    var gridColor: Int = 0xFFFFB7C5.toInt() // 樱花粉
        private set

    /** 顶栏/底栏背景色 */
    var barColor: Int = 0xFFFFB7C5.toInt() // 樱花粉
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

    /** 翻页提示线颜色候选 */
    val hintColors: List<Pair<String, Int>> = listOf(
        "红" to 0xFFFF3B30.toInt(),
        "橙" to 0xFFFF9500.toInt(),
        "黄" to 0xFFFFD60A.toInt(),
        "绿" to 0xFF34C759.toInt(),
        "蓝" to 0xFF0A84FF.toInt(),
        "紫" to 0xFFBF5AF2.toInt(),
        "黑" to 0xFF1C1C1E.toInt(),
        "白" to 0xFFFFFFFF.toInt(),
        "樱花粉" to 0xFFFFB7C5.toInt(),
    )

    /** 棋盘格线颜色候选 */
    val lineColors: List<Pair<String, Int>> = listOf(
        "红" to 0xFFE53935.toInt(),
        "橙" to 0xFFFB8C00.toInt(),
        "黄" to 0xFFFDD835.toInt(),
        "绿" to 0xFF43A047.toInt(),
        "蓝" to 0xFF1E88E5.toInt(),
        "白" to 0xFFFFFFFF.toInt(),
        "樱花粉" to 0xFFFFB7C5.toInt(),
    )

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        previewAlpha = p.getFloat(K_ALPHA, 0.7f).coerceIn(0f, 1f)
        thumbSpans = p.getInt(K_SPANS, 5)
        volumeKeys = p.getBoolean(K_VOLUME, true)
        volumeOverlap = p.getInt(K_VOLUME_OVERLAP, 10).coerceIn(0, 40)
        cornerButton = p.getBoolean(K_CORNER_BTN, true)
        tapPaging = p.getBoolean(K_TAP_PAGING, true)
        tapPagingHorizontal = p.getBoolean(K_TAP_HORIZ, false)
        flipHint = p.getBoolean(K_FLIP_HINT, true)
        flipHintInset = p.getInt(K_HINT_INSET, 3).coerceIn(0, 20)
        flipHintAlpha = p.getInt(K_HINT_ALPHA, 40).coerceIn(10, 100)
        flipHintWidth = p.getInt(K_HINT_WIDTH, 2).coerceIn(1, 12)
        flipHintColor = p.getInt(K_HINT_COLOR, 0xFFFFB7C5.toInt())
        rotateAuto = p.getBoolean(K_ROTATE_AUTO, true)
        rotateLockLandscape = p.getBoolean(K_ROTATE_LAND, false)
        gridLines = p.getBoolean(K_GRID, true)
        gridColor = p.getInt(K_GRID_COLOR, 0xFFFFB7C5.toInt())
        barColor = p.getInt(K_BAR, 0xFFFFB7C5.toInt())
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

    /** 翻屏重叠百分比（0~40） */
    fun setVolumeOverlap(ctx: Context, percent: Int) {
        volumeOverlap = percent.coerceIn(0, 40)
        edit(ctx).putInt(K_VOLUME_OVERLAP, volumeOverlap).apply()
    }

    fun setCornerButton(ctx: Context, on: Boolean) {
        cornerButton = on
        edit(ctx).putBoolean(K_CORNER_BTN, on).apply()
    }

    fun setTapPaging(ctx: Context, on: Boolean) {
        tapPaging = on
        edit(ctx).putBoolean(K_TAP_PAGING, on).apply()
    }

    fun setTapPagingHorizontal(ctx: Context, horizontal: Boolean) {
        tapPagingHorizontal = horizontal
        edit(ctx).putBoolean(K_TAP_HORIZ, horizontal).apply()
    }

    fun setFlipHint(ctx: Context, on: Boolean) {
        flipHint = on
        edit(ctx).putBoolean(K_FLIP_HINT, on).apply()
    }

    fun setFlipHintInset(ctx: Context, percent: Int) {
        flipHintInset = percent.coerceIn(0, 20)
        edit(ctx).putInt(K_HINT_INSET, flipHintInset).apply()
    }

    fun setFlipHintAlpha(ctx: Context, percent: Int) {
        flipHintAlpha = percent.coerceIn(10, 100)
        edit(ctx).putInt(K_HINT_ALPHA, flipHintAlpha).apply()
    }

    fun setFlipHintWidth(ctx: Context, dp: Int) {
        flipHintWidth = dp.coerceIn(1, 12)
        edit(ctx).putInt(K_HINT_WIDTH, flipHintWidth).apply()
    }

    fun setFlipHintColor(ctx: Context, color: Int) {
        flipHintColor = color
        edit(ctx).putInt(K_HINT_COLOR, color).apply()
    }

    /** 切换旋转模式：auto=true 跟随重力；auto=false 锁死在 lockLandscape 指定的方向 */
    fun setRotateMode(ctx: Context, auto: Boolean, lockLandscape: Boolean) {
        rotateAuto = auto
        rotateLockLandscape = lockLandscape
        edit(ctx).putBoolean(K_ROTATE_AUTO, auto).putBoolean(K_ROTATE_LAND, lockLandscape).apply()
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
