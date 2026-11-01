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
        setupExtraSwitches()
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
        b.swFlipHint.setTextColor(0xFFFFFFFF.toInt())
        b.swCorner.setTextColor(0xFFFFFFFF.toInt())
        b.swTapPaging.setTextColor(0xFFFFFFFF.toInt())
        b.swTapHorizontal.setTextColor(0xFFFFFFFF.toInt())
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

    /** 翻页提示线的留白 / 不透明度 / 粗细 / 颜色 */
    private fun setupHintStyle() {
        b.tvHintInset.setTextColor(0xFFFFFFFF.toInt())
        b.tvHintAlpha.setTextColor(0xFFFFFFFF.toInt())
        b.tvHintWidth.setTextColor(0xFFFFFFFF.toInt())

        b.tvHintInset.text = "左右留白：${UiPrefs.flipHintInset}%"
        b.sbHintInset.max = 20
        b.sbHintInset.progress = UiPrefs.flipHintInset
        b.sbHintInset.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                b.tvHintInset.text = "左右留白：$p%"
                UiPrefs.setFlipHintInset(this@SettingsActivity, p)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        b.tvHintAlpha.text = "不透明度：${UiPrefs.flipHintAlpha}%"
        b.sbHintAlpha.max = 100
        b.sbHintAlpha.progress = UiPrefs.flipHintAlpha
        b.sbHintAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val v = p.coerceAtLeast(10)
                b.tvHintAlpha.text = "不透明度：$v%"
                UiPrefs.setFlipHintAlpha(this@SettingsActivity, v)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        b.tvHintWidth.text = "粗细：${UiPrefs.flipHintWidth}dp"
        b.sbHintWidth.max = 11
        b.sbHintWidth.progress = UiPrefs.flipHintWidth - 1
        b.sbHintWidth.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val dp = p + 1
                b.tvHintWidth.text = "粗细：${dp}dp"
                UiPrefs.setFlipHintWidth(this@SettingsActivity, dp)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        b.hintColorRow.addView(
            dotRow(UiPrefs.hintColors.map { it.second }, UiPrefs.flipHintColor) { picked ->
                UiPrefs.setFlipHintColor(this, picked)
                // 换色原地生效：重画一下这一行
                b.hintColorRow.removeAllViews()
                setupHintStyle()
            },
        )
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
        b.swVolume.setOnCheckedChangeListener { _, checked ->
            UiPrefs.setVolumeKeys(this, checked)
            // 重叠只在开了音量翻页时才有意义
            b.boxOverlap.visibility = if (checked) View.VISIBLE else View.GONE
        }
        b.tvOverlap.setTextColor(0xFFFFFFFF.toInt())
        b.boxOverlap.visibility = if (UiPrefs.volumeKeys) View.VISIBLE else View.GONE
        b.sbOverlap.max = 30
        b.sbOverlap.progress = UiPrefs.volumeOverlap.coerceIn(0, 30)
        b.tvOverlap.text = "翻屏重叠：${b.sbOverlap.progress}%"
        b.sbOverlap.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                b.tvOverlap.text = "翻屏重叠：$p%"
                UiPrefs.setVolumeOverlap(this@SettingsActivity, p)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    /** 批注时的左下角翻页按钮 + 非批注模式的触摸翻页 */
    private fun setupExtraSwitches() {
        val thumbs = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(0xFF7FE3F5.toInt(), 0xFF9E9E9E.toInt()),
        )
        val tracks = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(0x66005E78, 0x339E9E9E),
        )
        for (sw in arrayOf(b.swFlipHint, b.swCorner, b.swTapPaging, b.swTapHorizontal)) {
            sw.thumbTintList = thumbs
            sw.trackTintList = tracks
        }

        b.swFlipHint.isChecked = UiPrefs.flipHint
        b.boxHintStyle.visibility = if (UiPrefs.flipHint) View.VISIBLE else View.GONE
        b.swFlipHint.setOnCheckedChangeListener { _, on ->
            UiPrefs.setFlipHint(this, on)
            // 样式只在提示线开着时才有意义
            b.boxHintStyle.visibility = if (on) View.VISIBLE else View.GONE
        }
        setupHintStyle()

        b.swCorner.isChecked = UiPrefs.cornerButton
        b.swCorner.setOnCheckedChangeListener { _, on -> UiPrefs.setCornerButton(this, on) }

        b.swTapPaging.isChecked = UiPrefs.tapPaging
        b.swTapHorizontal.isChecked = UiPrefs.tapPagingHorizontal
        b.swTapPaging.setOnCheckedChangeListener { _, on ->
            UiPrefs.setTapPaging(this, on)
            // 方向只在开了触摸翻页时才有意义
            val visible = if (on) View.VISIBLE else View.GONE
            b.swTapHorizontal.visibility = visible
            b.tvTapHorizontalHint.visibility = visible
        }
        val visible = if (UiPrefs.tapPaging) View.VISIBLE else View.GONE
        b.swTapHorizontal.visibility = visible
        b.tvTapHorizontalHint.visibility = visible
        b.swTapHorizontal.setOnCheckedChangeListener { _, horizontal ->
            UiPrefs.setTapPagingHorizontal(this, horizontal)
        }
    }

    /**
     * 圆形色块：按可用宽度自动分列，每格等宽，整行撑满屏幕宽度。
     * 原来写死"每行 4 个"，右边会空出一大块。
     * 列数还会照顾最后一排：宁可少一列，也不让最后只剩一两个孤零零的点。
     */
    private fun dotRow(colors: List<Int>, initial: Int, onPick: (Int) -> Unit): LinearLayout {
        var selected = initial
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val dots = ArrayList<Triple<View, View, Int>>()

        // 内容区两侧各 20dp padding（见 activity_settings.xml）
        val usable = (resources.displayMetrics.widthPixels - px(40)).coerceAtLeast(px(120))
        var cols = (usable / px(52)).coerceIn(2, 8).coerceAtMost(colors.size.coerceAtLeast(1))
        // 只有"最后一排只剩一个"才减一列，其余都按最大列数排，保证几组色块网格一致
        while (cols > 2 && colors.size % cols == 1) cols--
        val cell = usable / cols

        fun paintDots() {
            for ((dot, ringView, color) in dots) {
                dot.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setStroke(px(1), 0x33FFFFFF)
                }
                ringView.visibility = if (color == selected) View.VISIBLE else View.GONE
            }
        }

        for (chunk in colors.chunked(cols)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in chunk) {
                // 每格等宽，色块在格子里居中 → 整行正好铺满
                val cellView = FrameLayout(this)
                // 选中环画在色块"外面"：白色环压在深色面板上永远看得见，
                // 不会因为选中了浅色（樱花粉 / 白）而和色块糊成一片
                val ringView = View(this).apply {
                    visibility = View.GONE
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0x00000000)
                        setStroke(px(2), 0xFFFFFFFF.toInt())
                    }
                }
                cellView.addView(
                    ringView,
                    FrameLayout.LayoutParams(px(46), px(46)).apply { gravity = Gravity.CENTER },
                )
                val dot = View(this)
                cellView.addView(
                    dot,
                    FrameLayout.LayoutParams(px(38), px(38)).apply { gravity = Gravity.CENTER },
                )
                row.addView(cellView, LinearLayout.LayoutParams(cell, px(46 + 10)))
                dots.add(Triple(dot, ringView, c))
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
