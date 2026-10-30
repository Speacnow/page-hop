package com.pagehop.reader

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.pagehop.reader.data.UiPrefs
import com.pagehop.reader.databinding.ActivitySettingsBinding
import kotlin.math.roundToInt

/**
 * 设置页：整页打开（原来是个弹窗，内容一多就得在对话框里滚，很不舒服）。
 * 所有改动即时生效、即时落盘，没有"确定/取消"。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private val dp by lazy { resources.displayMetrics.density }
    private fun px(v: Int) = (v * dp).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        // 跟阅读页一致：真全屏，横屏时内容也铺进挖孔区，不然左边会留一条黑边
        enableImmersiveMode()
        UiPrefs.load(this)

        b.btnBack.setOnClickListener { finish() }
        applyBar()

        setupAlpha()
        setupGridSwitch()
        setupVolumeSwitch()
        b.gridColorRow.addView(
            dotRow(UiPrefs.lineColors.map { it.second }, UiPrefs.gridColor) { picked ->
                UiPrefs.setGridColor(this, picked)
            },
        )
        b.barColorRow.addView(
            dotRow(UiPrefs.barThemes.map { it.second }, UiPrefs.barColor) { picked ->
                // 前景色不再手写，setBarTheme 会按背景亮度自动算深/浅
                UiPrefs.setBarTheme(this, picked)
                applyBar()
            },
        )

        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "-"
        b.tvAbout.text = "PageHop $version"
    }

    /** 真全屏：内容铺到物理边缘，状态栏/导航栏隐藏（下滑可临时唤出） */
    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
        hideSystemBars()
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, b.root)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** 顶栏跟随"界面配色"，改色块时立刻能看到效果 */
    private fun applyBar() {
        val bg = UiPrefs.barColor
        val fg = UiPrefs.barContent
        // 只有顶栏跟着配色走；正文标签在深色底上，永远是浅色，
        // 否则换成浅色配（樱花粉 / 白色）时正文会变成"黑字压黑底"
        b.header.setBackgroundColor(bg)
        b.tvTitle.setTextColor(fg)
        b.btnBack.setColorFilter(fg)
        b.tvAlpha.setTextColor(0xFFFFFFFF.toInt())
        b.swGrid.setTextColor(0xFFFFFFFF.toInt())
        b.swVolume.setTextColor(0xFFFFFFFF.toInt())
    }

    private fun setupAlpha() {
        var alpha = UiPrefs.previewAlpha
        b.tvAlpha.text = "预览透明度：${(((1f - alpha) * 100).roundToInt())}%"
        b.sbAlpha.max = 20
        b.sbAlpha.progress = ((1f - alpha) * 20).toInt().coerceIn(0, 20)
        b.sbAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                alpha = 1f - p / 20f
                b.tvAlpha.text = "预览透明度：${p * 5}%"
                UiPrefs.setPreviewAlpha(this@SettingsActivity, alpha)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun setupGridSwitch() {
        b.swGrid.isChecked = UiPrefs.gridLines
        b.swGrid.thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(0xFF7FE3F5.toInt(), 0xFF9E9E9E.toInt()),
        )
        b.swGrid.trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(0x66005E78, 0x339E9E9E),
        )
        b.swGrid.setOnCheckedChangeListener { _, checked -> UiPrefs.setGridLines(this, checked) }
    }

    private fun setupVolumeSwitch() {
        b.swVolume.isChecked = UiPrefs.volumeKeys
        b.swVolume.thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(0xFF7FE3F5.toInt(), 0xFF9E9E9E.toInt()),
        )
        b.swVolume.trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(0x66005E78, 0x339E9E9E),
        )
        b.swVolume.setOnCheckedChangeListener { _, checked -> UiPrefs.setVolumeKeys(this, checked) }
    }

    /**
     * 圆形色块：按可用宽度自动分列，每格等宽，整行撑满屏幕宽度。
     * 原来写死"每行 4 个"，右边会空出一大块。
     * 列数还会照顾最后一排：宁可少一列，也不让最后只剩一两个孤零零的点。
     */
    private fun dotRow(colors: List<Int>, initial: Int, onPick: (Int) -> Unit): LinearLayout {
        var selected = initial
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val dots = ArrayList<Pair<View, Int>>()

        // 内容区两侧各 20dp padding（见 activity_settings.xml）
        val usable = (resources.displayMetrics.widthPixels - px(40)).coerceAtLeast(px(120))
        var cols = (usable / px(52)).coerceIn(2, 8).coerceAtMost(colors.size.coerceAtLeast(1))
        // 只有"最后一排只剩一个"才减一列，其余都按最大列数排，保证几组色块网格一致
        while (cols > 2 && colors.size % cols == 1) cols--
        val cell = usable / cols

        fun paintDots() {
            for ((view, color) in dots) {
                val picked = color == selected
                view.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    val lum = 0.299f * android.graphics.Color.red(color) +
                        0.587f * android.graphics.Color.green(color) +
                        0.114f * android.graphics.Color.blue(color)
                    val ring = if (lum > 170f) 0xFF202020.toInt() else 0xFFFFFFFF.toInt()
                    setStroke(px(if (picked) 3 else 1), if (picked) ring else 0x33FFFFFF)
                }
            }
        }

        for (chunk in colors.chunked(cols)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in chunk) {
                // 每格等宽，色块在格子里居中 → 整行正好铺满
                val cellView = FrameLayout(this)
                val dot = View(this)
                cellView.addView(
                    dot,
                    FrameLayout.LayoutParams(px(38), px(38)).apply { gravity = Gravity.CENTER },
                )
                row.addView(cellView, LinearLayout.LayoutParams(cell, px(38 + 18)))
                dots.add(dot to c)
                cellView.setOnClickListener {
                    selected = c
                    paintDots()
                    onPick(c)
                }
            }
            wrap.addView(row)
        }
        paintDots()
        return wrap
    }
}
