package com.pagehop.reader

import android.graphics.RectF

import android.graphics.Path

import android.graphics.Paint

import android.graphics.Canvas

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import com.pagehop.reader.data.BookState
import com.pagehop.reader.data.ColorModeKey
import com.pagehop.reader.data.CropRect
import com.pagehop.reader.data.Highlight
import com.pagehop.reader.data.InkStroke
import com.pagehop.reader.data.PageMetrics
import com.pagehop.reader.data.PageHistory
import com.pagehop.reader.data.Store
import com.pagehop.reader.data.TextNote
import com.pagehop.reader.data.UiPrefs
import com.pagehop.reader.databinding.ActivityReaderBinding
import com.pagehop.reader.pdf.PdfDoc
import com.pagehop.reader.pdf.RenderEngine
import com.pagehop.reader.ui.ColorModes
import com.pagehop.reader.ui.PageAdapter
import com.pagehop.reader.ui.PageRowAdapter
import com.pagehop.reader.ui.PageView
import com.pagehop.reader.ui.GridLinesDecoration
import com.pagehop.reader.ui.ThumbAdapter
import com.pagehop.reader.ui.TocAdapter
import com.pagehop.reader.ui.ThumbZoom
import com.pagehop.reader.ui.ZoomGrid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

class ReaderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NAME = "extra_name"

        /** 长按历史按钮时弹出的缩略图条数（最近访问列表上限） */
        const val HISTORY_MAX = 6

        /**
         * 音量键翻屏时上下两屏重叠多少（占一屏的比例）。
         * 4% 就是"竖屏一屏 1.4 页 → 翻 1.344 页"，刚好让上一屏最后一行留在下一屏顶上。
         */
        const val VOLUME_SCROLL_OVERLAP = 0.04f

        // 目录面板的三个页签，顺序就是界面上从左到右的顺序
        const val MODE_BOOKMARKS = 0
        const val MODE_TOC = 1
        const val MODE_THUMBS = 2
    }

    private lateinit var b: ActivityReaderBinding
    private lateinit var doc: PdfDoc
    private lateinit var state: BookState
    private lateinit var adapter: PageAdapter

    private var docId = ""
    private var aspects = FloatArray(0)
    private var currentPage = 0
    private var barsVisible = true

    /**
     * 恢复上次阅读位置期间为 true。
     * 初始布局时 RecyclerView 会先发出一次"停在第 0 项"的滚动事件，
     * 若不挡住，它会把 currentPage 冲成 0，防抖持久化再把 0 写回磁盘，
     * 下次打开就跳回第一页了。
     */
    private var restoring = true

    private val main = Handler(Looper.getMainLooper())
    private val persistRunnable = Runnable { persistNow() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val hlColors = intArrayOf(
        0xFFFFC400.toInt(), 0xFF4CAF50.toInt(), 0xFF2196F3.toInt(), 0xFFE91E63.toInt(),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 先把方向定下来再搭界面：否则会先按系统方向铺一遍、再翻过来，看得见闪一下
        UiPrefs.load(this)
        applyRotation()
        b = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(b.root)
        enableImmersiveMode()
        applyBarLayout(
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
        )

        val uri = intent?.data
        if (uri == null) {
            finish()
            return
        }
        val name = intent.getStringExtra(EXTRA_NAME) ?: uri.lastPathSegment ?: "PDF"

        val opened = try {
            PdfDoc.open(this, uri)
        } catch (e: Throwable) {
            Toast.makeText(
                this,
                getString(R.string.error_open, e.message ?: e.javaClass.simpleName),
                Toast.LENGTH_LONG,
            ).show()
            finish()
            return
        }
        doc = opened

        if (doc.pageCount <= 0) {
            Toast.makeText(this, getString(R.string.error_open, "没有页面"), Toast.LENGTH_LONG).show()
            runCatching { doc.close() }
            finish()
            return
        }

        docId = Store.idFor(uri.toString())
        state = Store.loadState(this, uri.toString(), name, doc.pageCount)
        if (state.total != doc.pageCount) state.total = doc.pageCount
        currentPage = state.page.coerceIn(0, doc.pageCount - 1)
        history.reset(currentPage)

        b.btnBack.setOnClickListener { finish() }
        b.btnThumbs.setOnClickListener { showThumbMap() }
        b.btnColor.setOnClickListener { showColorMenu() }
        b.btnCrop.setOnClickListener { showCropMenu() }
        b.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        b.btnMore.setOnClickListener { showMoreMenu() }
        // 点页码直接输入跳转
        b.btnToc.setOnClickListener { showTocPanel() }
        // 未放大时，从屏幕左侧 1/4 往右滑也能拉出目录（避开了系统返回手势的边缘区）
        b.zoomFrame.onSwipeToOpen = { if (tocView == null) showTocPanel() }
        // 左边缘往右滑 = 拉出目录（未放大时才生效，放大后左滑应该是平移）
        var edgeDownX = 0f
        var edgeSwiped = false
        b.tocEdge.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    edgeDownX = e.rawX
                    edgeSwiped = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - edgeDownX
                    if (!edgeSwiped && b.zoomFrame.scale <= 1.05f && dx > 60 * resources.displayMetrics.density) {
                        edgeSwiped = true
                        showTocPanel()
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        b.bookmarkHotspot.setOnClickListener { toggleBookmark() }
        b.btnPageJump.setOnClickListener { showJumpDialog() }
        b.btnPageBack.setOnClickListener { goBack() }
        b.btnPageBack.setOnLongClickListener { showHistoryDialog(); true }
        // 前进 = 撤销刚才的后退
        b.btnPageForward.setOnClickListener { goForward() }
        b.btnPageForward.setOnLongClickListener { showHistoryDialog(); true }
        // 自动旋转：手机锁了竖屏也能跟着重力转
        b.btnRotate.setOnClickListener { toggleRotateLock() }
        // 单击 = 开关手写批注；长按 = 弹出批注菜单（颜色 / 列表 / 清除本页）
        b.btnHighlight.setOnClickListener { if (penMode) exitPenMode() else enterPenMode() }
        b.btnHighlight.setOnLongClickListener {
            showAnnotationMenu()
            true
        }
        b.btnText.setOnClickListener { if (textMode) exitTextMode() else enterTextMode() }
        b.btnText.setOnLongClickListener {
            showAnnotationMenu()
            true
        }

        setupList()
        setupProgressBar()
        applyColorMode()
        updateIndicator()
        // 应用级外观：顶栏/底栏配色
        UiPrefs.load(this)
        applyBarTheme()
        // 顶/底栏量出高度后再给正文让位，这样"显示/隐藏工具栏"能真正全屏
        b.topBar.post {
            if (b.topBar.height > 0) topBarH = b.topBar.height
            if (b.bottomBar.height > 0) bottomBarH = b.bottomBar.height
            applyPagePadding()
        }

        // 停手后按当前缩放倍率重画可见页：放大 2 倍仍按 1x 渲染会糊成一片
        b.zoomFrame.onScaleSettled = { s ->
            val want = s.coerceIn(1f, 1.5f)
            if (kotlin.math.abs(want - adapter.renderScale) > 0.02f) {
                adapter.renderScale = want
                rebindVisiblePages()
            }
        }

        val savedPage = currentPage
        b.pageList.post {
            (b.pageList.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(savedPage, 0)
            currentPage = savedPage
            updateIndicator()
            // 兜底：1.2s 后就算没有触摸也结束恢复期（用户一碰屏幕会立刻结束）
            b.pageList.postDelayed({ restoring = false }, 1200)
        }

        // 用户一碰屏幕就认为恢复完成，之后滚动才算数
        b.pageList.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) restoring = false
            false
        }

        // 页高比例要开一遍页面才知道。这里**不能**用 notifyDataSetChanged：
        // 它会把 RecyclerView 重置回第 1 页，连"恢复上次阅读位置"都会被冲掉。
        // 只更新当前可见页的高度，滚动位置不受影响。
        lifecycleScope.launch {
            val a = RenderEngine.pageAspects(doc)
            aspects = a
            for (i in 0 until b.pageList.childCount) {
                val child = b.pageList.getChildAt(i)
                if (child is PageView) {
                    val idx = child.pageIndex
                    if (idx in a.indices && a[idx] > 0.1f) {
                        // 和 PageAdapter 里保持一致：页高按"裁边后"的比例算，
                        // 否则这里会把已经排好的页高改回未裁边的比例，又是一次跳动。
                        child.aspect = PageMetrics.displayAspect(a[idx], child.src)
                    }
                }
            }
        }

        showFirstRunHint()

        // 调试探针：合成多点手势（adb 注入不了双指）
        val probe = intent?.getStringExtra("probe")
        if (probe != null) {
            b.zoomFrame.postDelayed({
                GestureProbe.run(
                    target = b.zoomFrame,
                    script = probe,
                    onPen = { if (penMode) exitPenMode() else enterPenMode() },
                ) {
                    val first = (b.pageList.layoutManager as? LinearLayoutManager)
                        ?.findFirstVisibleItemPosition() ?: -1
                    "scale=${b.zoomFrame.scale} firstVisible=$first current=$currentPage pen=$penMode " +
                        "offset=${b.pageList.computeVerticalScrollOffset()} " +
                        "whiteDraws=${PageView.whiteDraws} clearedBinds=${PageAdapter.clearedBinds}"
                }
            }, 1500L)
        }
    }

    /** 第一次打开文档时提示两个核心手势，之后不再打扰 */
    private fun showFirstRunHint() {
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        if (prefs.getBoolean("hint_shown", false)) return
        prefs.edit().putBoolean("hint_shown", true).apply()
        Toast.makeText(
            this,
            "单击页面：显示 / 隐藏工具栏\n长按页面后拖动：框选高亮",
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun setupList() {
        val lm = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        b.pageList.layoutManager = lm
        b.pageList.itemAnimator = null

        adapter = PageAdapter(
            doc = doc,
            docId = docId,
            state = state,
            screenWidth = { resources.displayMetrics.widthPixels },
            aspects = { aspects },
            onTapPage = { toggleBars() },
            onTapAt = { page, fx, fy ->
                if (textMode) showTextNoteDialog(page, fx, fy) else toggleBars()
            },
            onTextNoteClicked = { note ->
                if (textMode) {
                    showTextNoteDialog(note.page, note.x, note.y, existing = note)
                } else {
                    toggleBars()
                }
            },
            onDoubleTapPage = { toggleBars() },
            onHighlightAdded = { h ->
                state.highlights.add(h)
                refreshAnnotations(h.page)
                persistNow()
            },
            onStrokeAdded = { s ->
                state.strokes.add(s)
                refreshAnnotations(s.page)
                persistNow()
            },
            onHighlightClicked = { showHighlightOptions(it) },
            onStrokeClicked = { showStrokeOptions(it) },
            onCropFound = { page, crop ->
                state.pageCrops[page] = crop
                schedulePersist()
            },
        )
        b.pageList.adapter = adapter

        b.pageList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                // 退出过程中窗口会收缩重排，可能冒出一个 p=0 的滚动事件，
                // 若不挡掉会把"第 1 页"当成当前页存下来。
                if (isFinishing || isDestroyed) return
                val p = (rv.layoutManager as? LinearLayoutManager)
                    ?.findFirstVisibleItemPosition() ?: return
                if (restoring) return
                // 逐页滚动不算历史：只更新当前页
                if (p >= 0 && p != currentPage) setPage(p)
                schedulePersist()
            }
        })
    }

    private fun updateIndicator() {
        b.tvPage.text = "${currentPage + 1}"
        b.tvTotal.text = getString(R.string.page_slash, doc.pageCount)
        if (!draggingSeek && b.seekProgress.max != doc.pageCount - 1) {
            b.seekProgress.max = (doc.pageCount - 1).coerceAtLeast(0)
        }
        if (!draggingSeek && b.seekProgress.progress != currentPage) {
            b.seekProgress.progress = currentPage
        }
        updateHistoryButtons()
    }

    // ---------------- 底部进度条 ----------------

    /** 用户正在拖进度条：这时不要拿列表的滚动位置去覆盖它 */
    private var draggingSeek = false
    private var seekTarget = -1
    private var seekJumpPosted = false

    /** 开始拖进度条时所在的页：松手后它就是这次"主动跳转"的来源页 */
    private var seekFromPage = -1

    private val seekJump = Runnable {
        seekJumpPosted = false
        val p = seekTarget
        if (p >= 0) scrollToPage(p)
    }

    private fun setupProgressBar() {
        b.seekProgress.max = (doc.pageCount - 1).coerceAtLeast(0)
        b.seekProgress.progress = currentPage
        b.seekProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                b.tvPage.text = "${p + 1}"
                seekTarget = p
                // 拖动时按 90ms 节流跳页：既跟手，又不会每一帧都重排整个列表
                if (!seekJumpPosted) {
                    seekJumpPosted = true
                    main.postDelayed(seekJump, 90)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                draggingSeek = true
                // 拖动前先记下来源页，否则拖动途中每跳一步都会把来源改成上一站
                seekFromPage = currentPage
                // 扫页时页面一帧一换：先用低分辨率占位，画面立刻有内容
                if (::adapter.isInitialized) adapter.quick = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                draggingSeek = false
                main.removeCallbacks(seekJump)
                seekJumpPosted = false
                if (::adapter.isInitialized) adapter.quick = false
                val p = sb?.progress ?: return
                // 拖进度条也是一次主动定点跳转，停够时间就进历史
                val from = seekFromPage
                seekFromPage = -1
                scrollToPage(p, from)
                // 扫页时是低分辨率的，松手后把当前可见页重画成清晰的
                rebindVisiblePages()
            }
        })
    }

    /** 拖动进度条时的跳页：不弹"回到第 M 页"提示条，避免拖动过程中一直闪 */
    private fun scrollToPage(page: Int, from: Int = -1) {
        val p = page.coerceIn(0, doc.pageCount - 1)
        if (p == currentPage && from < 0) return
        asJump {
            if (p != currentPage) {
                (b.pageList.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(p, 0)
                setPage(p)
                b.tvPage.text = "${p + 1}"
                schedulePersist()
            }
        }
        // 松手才算一次主动跳转：拖动过程中 from < 0，不记账
        if (from >= 0) armJumpCommit(from, p)
    }

    private fun applyColorMode() {
        // 主题是全局状态，页面在 onDraw 里读它。
        // 这样连"滚出去又被缓存复用、没有重新绑定"的页面也会立刻跟着变，
        // 不会再出现"下一页的下一页还是旧主题"。
        ColorModes.active = state.colorMode
        b.root.setBackgroundColor(ColorModes.background(state.colorMode))
        for (i in 0 until b.pageList.childCount) {
            b.pageList.getChildAt(i).invalidate()
        }
    }

    /** 只重绑当前可见的页，不整表刷新（整表刷新会重置滚动位置） */
    private fun rebindVisiblePages() {
        val lm = b.pageList.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first >= 0 && last >= first) {
            adapter.notifyItemRangeChanged(first, last - first + 1)
        }
    }

    /** 需要整表刷新（改裁边等）时，刷完把位置放回原处，否则会跳回第 1 页 */
    private fun refreshKeepingPosition() {
        val keep = currentPage
        adapter.refresh()
        b.pageList.post {
            (b.pageList.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(keep, 0)
            currentPage = keep
            updateIndicator()
        }
    }

    /** 只替换那一页上的批注，避免整页重新渲染造成的闪烁 */
    private fun refreshAnnotations(page: Int) {
        val hls = state.highlights.filter { it.page == page }
        val strokes = state.strokes.filter { it.page == page }
        val notes = state.textNotes.filter { it.page == page }
        for (i in 0 until b.pageList.childCount) {
            val child = b.pageList.getChildAt(i)
            if (child is PageView && child.pageIndex == page) {
                child.highlights = hls
                child.strokes = strokes
                child.textNotes = notes
                child.penColor = adapter.annotationColor
                child.invalidate()
            }
        }
    }

    // ---------------- 顶栏/底栏 ----------------

    private fun toggleBars() = setBarsVisible(!barsVisible)

    /** 缩略图面板打开时的重算格子回调，转屏时用 */
    private var thumbMapRelayout: (() -> Unit)? = null

    // ---- 目录 / 书签面板 ----
    private var tocView: View? = null
    private var tocAdapter: TocAdapter? = null
    private var tocItems: List<PdfDoc.TocItem> = emptyList()
    private var tocMode = MODE_BOOKMARKS
    /** 列表画法：false = 页码文本，true = 缩略图 */
    private var tocThumb = false
    /** 书签排序：false = 按添加的先后，true = 最近添加的在前 */
    private var tocRecentFirst = false
    private var tocLoading = false
    private var pageRows: PageRowAdapter? = null
    private var historyDialog: android.app.Dialog? = null

    private var topBarH = 0
    private var bottomBarH = 0

    private fun setBarsVisible(visible: Boolean) {
        if (barsVisible == visible) return
        barsVisible = visible
        if (visible) {
            if (b.topBarScroll.height > 0) topBarH = b.topBarScroll.height
            if (b.bottomBar.height > 0) bottomBarH = b.bottomBar.height
        }
        // 注意：顶栏真正的容器是 topBarScroll（里面才是 topBar），
        // 隐藏时必须动外层，否则会留下一条顶栏颜色的细条。
        for (v in arrayOf<View>(b.topBarScroll, b.bottomBar)) {
            // 连点时会重启动画，end action 必须按当前状态判断，否则栏会莫名其妙消失
            v.animate().cancel()
            if (visible) {
                v.visibility = View.VISIBLE
                v.animate().alpha(1f).translationY(0f).setDuration(160).start()
            } else {
                val target = if (v === b.topBarScroll) -v.height.toFloat() else v.height.toFloat()
                v.animate().alpha(0f).translationY(target).setDuration(160)
                    .withEndAction { if (!barsVisible) v.visibility = View.INVISIBLE }
                    .start()
            }
        }
        applyPagePadding()
    }

    /**
     * 正文给上下栏让位。栏是新版的高栏（图标行 + 书名行 / 页码 + 进度条），
     * 收起时必须把 padding 清零，否则"全屏阅读"会在上下各留一条空白。
     */
    private fun applyPagePadding() {
        val top = if (barsVisible) topBarH else 0
        val bottom = if (barsVisible) bottomBarH else 0
        if (b.pageList.paddingTop == top && b.pageList.paddingBottom == bottom) return
        b.pageList.setPadding(0, top, 0, bottom)
    }

    // ---------------- 横屏紧凑栏 ----------------

    /** 当前是"横屏紧凑栏"还是"竖屏原样"；null = 还没应用过 */
    private var compactBars: Boolean? = null

    /**
     * 横屏才压窄上下栏：
     *  - 顶栏 48dp → 40dp，图标按钮 52×44 → 46×40
     *  - 底栏两行 → 一行：[缩略图] [进度条] [‹ 页/总页 ›] [旋转]
     *
     * 竖屏保持原样。ReaderActivity 自己吃掉了转屏（不重建），所以这里靠挪动
     * 现成的视图实现，不重新 inflate。
     */
    private fun applyBarLayout(compact: Boolean) {
        if (compactBars == compact) return
        compactBars = compact

        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        val wc = ViewGroup.LayoutParams.WRAP_CONTENT

        // ---- 顶栏 ----
        b.topBarScroll.layoutParams = b.topBarScroll.layoutParams.apply {
            height = px(if (compact) 40 else 48)
        }
        val btnW = px(if (compact) 46 else 52)
        val btnH = px(if (compact) 40 else 44)
        for (i in 0 until b.topBar.childCount) {
            val v = b.topBar.getChildAt(i)
            if (v is android.widget.ImageButton || v is android.widget.FrameLayout) {
                v.layoutParams = v.layoutParams.apply {
                    width = btnW
                    height = btnH
                }
            }
        }

        // ---- 底栏 ----
        val bar = b.bottomBar
        val jump = b.btnPageJump
        val tools = b.btnTools
        (jump.parent as? ViewGroup)?.removeView(jump)
        if (compact) {
            bar.orientation = LinearLayout.HORIZONTAL
            bar.gravity = Gravity.CENTER_VERTICAL
            bar.layoutParams = bar.layoutParams.apply { height = px(44) }
            bar.setPadding(px(2), 0, px(2), 0)

            tools.layoutParams = tools.layoutParams.apply {
                width = mp
                height = mp
            }
            tools.setPadding(0, 0, 0, 0)

            jump.layoutParams = LinearLayout.LayoutParams(wc, mp).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            jump.setPadding(0, 0, 0, 0)
            // 插到旋转按钮前面：[缩略图] [进度条] [页码组] [旋转]
            tools.addView(jump, (tools.childCount - 1).coerceAtLeast(0), jump.layoutParams)
        } else {
            bar.orientation = LinearLayout.VERTICAL
            bar.gravity = Gravity.NO_GRAVITY
            bar.layoutParams = bar.layoutParams.apply { height = wc }
            bar.setPadding(0, 0, 0, 0)

            tools.layoutParams = tools.layoutParams.apply {
                width = mp
                height = wc
            }
            tools.setPadding(px(2), 0, px(2), px(2))

            jump.layoutParams = LinearLayout.LayoutParams(mp, wc)
            jump.setPadding(0, px(4), 0, 0)
            bar.addView(jump, 0, jump.layoutParams)
        }
        bar.requestLayout()
        b.topBarScroll.requestLayout()

        // 栏高变了，正文让位要跟着重算（等这一帧量完再算）
        bar.post {
            if (b.topBarScroll.height > 0) topBarH = b.topBarScroll.height
            if (b.bottomBar.height > 0) bottomBarH = b.bottomBar.height
            applyPagePadding()
        }
    }

    // ---------------- 全屏 ----------------

    /**
     * 真·全屏：内容铺到屏幕物理边缘，状态栏和导航栏都隐藏（下滑可临时唤出）。
     * 横屏时还要让内容进入挖孔区，否则左侧会留一条黑边。
     */
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

        // 不给挖孔让位：真机上挖孔只是左缘中段一小块，按 insets 整条让开反而会
        // 在左侧留一条黑带（用户能看到）。正文有留白，孔落在留白里，不影响阅读。
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

    // ---------------- 持久化 ----------------

    private fun schedulePersist() {
        main.removeCallbacks(persistRunnable)
        main.postDelayed(persistRunnable, 900)
    }

    private fun persistNow() {
        main.removeCallbacks(persistRunnable)
        if (!::state.isInitialized) return
        state.page = currentPage
        Store.saveState(this, state)
        Store.touchRecent(this, state.uri, state.name, currentPage, doc.pageCount)
    }

    /**
     * 进度要在 onPause 就落盘：等到 onStop 时窗口已经收缩，
     * 列表会发出一次"回到第 0 项"的滚动事件，存下来的页码就变成 1 了。
     */
    override fun onPause() {
        if (::state.isInitialized) persistNow()
        super.onPause()
    }

    /**
     * 设置改成了独立页面，回来时把外观重新读一遍，
     * 否则改了界面配色要重进阅读器才生效。
     */
    override fun onResume() {
        super.onResume()
        UiPrefs.load(this)
        if (::b.isInitialized) applyBarTheme()
    }

    override fun onDestroy() {
        main.removeCallbacks(persistRunnable)
        cancelPendingJump()
        if (::adapter.isInitialized) adapter.release()
        // onCreate 提前 finish 的路径（没有 uri、打不开文件）也会走到这里，
        // 那时 doc 还没赋值，直接读会抛 UninitializedPropertyAccessException，
        // 把"打不开文件"变成"一开就崩"
        if (::doc.isInitialized) {
            val d = doc
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                withContext(RenderEngine.dispatcher) { runCatching { d.close() } }
            }
        }
        scope.cancel()
        super.onDestroy()
    }

    // ---------------- 颜色 ----------------

    private fun showColorMenu() {
        val popup = PopupMenu(this, b.btnColor)
        val items = listOf(
            ColorModeKey.NORMAL to R.string.color_normal,
            ColorModeKey.NIGHT to R.string.color_night,
            ColorModeKey.NIGHT_SOFT to R.string.color_night_soft,
            ColorModeKey.GRAY to R.string.color_gray,
            ColorModeKey.SEPIA to R.string.color_sepia,
            ColorModeKey.BROWN to R.string.color_brown,
            ColorModeKey.CONTRAST to R.string.color_contrast,
        )
        items.forEachIndexed { i, pair ->
            popup.menu.add(0, i, i, pair.second)
        }
        popup.setOnMenuItemClickListener { item ->
            val mode = items[item.itemId].first
            if (mode != state.colorMode) {
                state.colorMode = mode
                applyColorMode()
                persistNow()
            }
            true
        }
        popup.show()
    }

    // ---------------- 自动旋转 / 旋转锁定 ----------------

    /** 当前这台设备的"物理方向"（用窗口宽高判断，比 configuration 更直接） */
    private fun isLandscapeNow(): Boolean {
        val dm = resources.displayMetrics
        return dm.widthPixels > dm.heightPixels
    }

    /**
     * 把 App 的旋转模式落到窗口上。
     *
     * 三条规则都靠这里实现：
     * - 自动：SCREEN_ORIENTATION_SENSOR —— 跟着重力走，**忽略系统的"自动旋转"开关**
     * - 锁定：SCREEN_ORIENTATION_PORTRAIT / LANDSCAPE —— 钉死在某个方向，系统转动也不理
     * - 从不使用 UNSPECIFIED —— 那会把控制权交回系统，也就受系统开关影响了
     */
    private fun applyRotation() {
        requestedOrientation = when {
            UiPrefs.rotateAuto -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
            UiPrefs.rotateLockLandscape -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        if (::b.isInitialized) updateRotateStyle()
    }

    /**
     * 一个按钮两种状态：
     * - 自动中 → 点一下 = **锁定当前方向**（横着就锁横、竖着就锁竖）
     * - 锁定中 → 点一下 = 解除锁定，恢复跟随重力
     */
    private fun toggleRotateLock() {
        if (UiPrefs.rotateAuto) {
            val landscape = isLandscapeNow()
            UiPrefs.setRotateMode(this, auto = false, lockLandscape = landscape)
            applyRotation()
            Toast.makeText(
                this,
                if (landscape) "已锁定横屏（不受系统旋转影响）" else "已锁定竖屏（不受系统旋转影响）",
                Toast.LENGTH_SHORT,
            ).show()
        } else {
            UiPrefs.setRotateMode(this, auto = true, lockLandscape = UiPrefs.rotateLockLandscape)
            applyRotation()
            Toast.makeText(this, "自动旋转已开启：跟随手机姿态", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- 震动 ----------------

    /**
     * 长按出预览时振一下。
     * 用的是 Vibrator 而不是 performHapticFeedback —— 后者要系统里"触摸震动"开着才响，
     * 很多人是关的，等于没反馈。
     */
    private fun vibrateLongPress() {
        runCatching {
            val effect = VibrationEffect.createOneShot(45L, VibrationEffect.DEFAULT_AMPLITUDE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = getSystemService(VibratorManager::class.java)
                manager?.defaultVibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(effect)
            }
        }
    }

    /**
     * 自动旋转按钮只换图标：自动是"旋转箭头"，锁定是"钉子"。
     * 不做高亮底色——之前那个青色方块看着像"选中项"，不像开关。
     */
    private fun updateRotateStyle() {
        b.ivRotate.setImageResource(if (UiPrefs.rotateAuto) R.drawable.ic_rotate else R.drawable.ic_pin)
        b.ivRotate.setColorFilter(UiPrefs.barContent)
        b.btnRotate.setBackgroundResource(0)
    }

    /** 把"界面配色"应用到顶栏/底栏（图标、文字、滑条一起跟着变） */
    private fun applyBarTheme() {
        val bg = UiPrefs.barColor
        val fg = UiPrefs.barContent
        b.topBarScroll.setBackgroundColor(bg)
        b.bottomBar.setBackgroundColor(bg)

        updateHistoryButtons()
        for (v in arrayOf<View>(b.btnBack, b.btnToc, b.btnColor, b.btnCrop, b.btnText, b.btnSettings, b.btnMore, b.btnThumbs)) {
            (v as? android.widget.ImageButton)?.setColorFilter(fg)
        }
        b.tvPage.setTextColor(fg)
        // 当前页 / 总页数：同一个字重、同一个字号、同一个颜色
        b.tvTotal.setTextColor(fg)

        val solid = android.content.res.ColorStateList.valueOf(fg)
        val faint = android.content.res.ColorStateList.valueOf((fg and 0x00FFFFFF) or 0x4D000000)
        b.seekProgress.progressTintList = solid
        b.seekProgress.thumbTintList = solid
        b.seekProgress.progressBackgroundTintList = faint

        updatePenButtonStyle()
        updateRotateStyle()
    }

    /** 转屏后宽度变了：按新宽度重画可见页，否则正文会糊 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 横屏才把上下栏压窄、底栏并成一行；竖屏保持原样
        applyBarLayout(
            (if (newConfig.orientation != Configuration.ORIENTATION_UNDEFINED) {
                newConfig.orientation
            } else {
                resources.configuration.orientation
            }) == Configuration.ORIENTATION_LANDSCAPE,
        )
        // 缩略图面板是按"屏宽 / 列数"算格子尺寸的，转屏后不重算就会一边大一边小
        thumbMapRelayout?.invoke()
        if (!::adapter.isInitialized) return
        b.topBar.post {
            if (b.topBar.height > 0) topBarH = b.topBar.height
            if (b.bottomBar.height > 0) bottomBarH = b.bottomBar.height
            applyPagePadding()
            rebindVisiblePages()
        }
    }
    /** 顶栏右上角三个点：放不常用的入口 */
    private fun showMoreMenu() {
        val popup = PopupMenu(this, b.btnMore)
        popup.menu.add(0, 0, 0, getString(R.string.jump_to_page))
        popup.menu.add(0, 1, 1, getString(R.string.back_to_library))
        popup.menu.add(0, 3, 2, "导出带批注的 PDF")
        popup.menu.add(0, 2, 3, getString(R.string.about))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                0 -> showJumpDialog()
                1 -> finish()
                3 -> startExport()
                2 -> showAbout()
            }
            true
        }
        popup.show()
    }

    /** 精确跳页：数字键盘只在需要时才弹，不再常驻在缩略图面板里 */
    private fun showJumpDialog() {
        val dp = resources.displayMetrics.density
        val et = EditText(this).apply {
            hint = getString(R.string.page_input_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("${currentPage + 1}")
            setSelection(text.length)
            setPadding((18 * dp).toInt(), (12 * dp).toInt(), (18 * dp).toInt(), (12 * dp).toInt())
        }
        AlertDialog.Builder(this)
            .setTitle("跳转到页码（共 ${doc.pageCount} 页）")
            .setView(et)
            .setPositiveButton(R.string.go) { _, _ ->
                val p = et.text.toString().toIntOrNull()
                if (p == null || p < 1 || p > doc.pageCount) {
                    Toast.makeText(this, "请输入 1 到 ${doc.pageCount}", Toast.LENGTH_SHORT).show()
                } else {
                    jumpTo(p - 1)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------- 目录 / 书签 ----------------

    private fun showTocPanel() {
        if (tocView != null) return
        val v = layoutInflater.inflate(R.layout.dialog_toc, b.root, false)
        b.root.addView(
            v,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        v.elevation = 16f * resources.displayMetrics.density
        tocView = v
        // 默认先展示书签：大多数时候是想回到自己标记的那几页
        tocMode = MODE_BOOKMARKS
        tocLoading = tocItems.isEmpty()

        val list = v.findViewById<RecyclerView>(R.id.tocList)
        val thumbList = v.findViewById<RecyclerView>(R.id.thumbList)
        val adapter = TocAdapter(
            onPick = { page -> jumpTo(page); dismissTocPanel() },
            onDelete = { page -> confirmRemoveBookmark(page) },
        )
        tocAdapter = adapter
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        // 缩略图列表：书签页签里只画书签那几页，缩略图页签里画整本书
        val grid = GridLayoutManager(this, 1)
        thumbList.layoutManager = grid
        val rows = PageRowAdapter(doc, docId) { page ->
            jumpTo(page)
            dismissTocPanel()
        }
        rows.currentPage = currentPage
        thumbList.adapter = rows
        pageRows = rows
        // 适配器初始就是"整本书"，这里滚一次就是准的（0.8.1 的做法）
        grid.scrollToPositionWithOffset(currentPage, 0)
        // 面板可能已经布局完，listener 不会再触发一次，所以先直接取一次
        if (thumbList.width > 0) rows.rowWidth = thumbList.width
        thumbList.addOnLayoutChangeListener { _, _, _, r, _, _, _, or, _ ->
            val w = r - or
            if (w > 0 && rows.rowWidth != w) {
                rows.rowWidth = w
                rows.notifyDataSetChanged()
            }
        }

        val tabBm = v.findViewById<TextView>(R.id.tabBookmarks)
        val tabToc = v.findViewById<TextView>(R.id.tabToc)
        val tabTh = v.findViewById<TextView>(R.id.tabThumbs)
        val optBar = v.findViewById<View>(R.id.tocOptions)
        val chipPage = v.findViewById<TextView>(R.id.chipPage)
        val chipThumb = v.findViewById<TextView>(R.id.chipThumb)
        val chipAdd = v.findViewById<TextView>(R.id.chipAdd)
        val chipRecent = v.findViewById<TextView>(R.id.chipRecent)
        val fg = UiPrefs.barContent

        fun syncChips() {
            fun tab(tv: TextView, on: Boolean) {
                tv.alpha = if (on) 1f else 0.55f
                tv.setTypeface(
                    android.graphics.Typeface.DEFAULT,
                    if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL,
                )
            }
            tab(tabBm, tocMode == MODE_BOOKMARKS)
            tab(tabToc, tocMode == MODE_TOC)
            tab(tabTh, tocMode == MODE_THUMBS)
            // 选项条只属于书签：目录是文档自己的顺序，缩略图页签本来就是图
            optBar.visibility = if (tocMode == MODE_BOOKMARKS) View.VISIBLE else View.GONE
            styleChip(chipPage, !tocThumb, fg)
            styleChip(chipThumb, tocThumb, fg)
            styleChip(chipAdd, !tocRecentFirst, fg)
            styleChip(chipRecent, tocRecentFirst, fg)
        }

        fun switchTo(mode: Int) {
            if (tocMode == mode) return
            tocMode = mode
            syncChips()
            renderTocRows()
        }
        tabBm.setOnClickListener { switchTo(MODE_BOOKMARKS) }
        tabToc.setOnClickListener { switchTo(MODE_TOC) }
        tabTh.setOnClickListener { switchTo(MODE_THUMBS) }
        chipPage.setOnClickListener { if (tocThumb) { tocThumb = false; syncChips(); renderTocRows() } }
        chipThumb.setOnClickListener { if (!tocThumb) { tocThumb = true; syncChips(); renderTocRows() } }
        chipAdd.setOnClickListener { if (tocRecentFirst) { tocRecentFirst = false; syncChips(); renderTocRows() } }
        chipRecent.setOnClickListener { if (!tocRecentFirst) { tocRecentFirst = true; syncChips(); renderTocRows() } }

        v.findViewById<View>(R.id.btnTocClose).setOnClickListener { dismissTocPanel() }
        v.findViewById<View>(R.id.tocRoot).setOnClickListener { dismissTocPanel() }

        // 面板顶栏 / 选项条跟随配色
        runCatching {
            val inner = (v as ViewGroup).getChildAt(0) as ViewGroup
            val header = inner.getChildAt(0) as ViewGroup
            header.setBackgroundColor(UiPrefs.barColor)
            (inner.getChildAt(1) as ViewGroup).setBackgroundColor(dim(UiPrefs.barColor))
            tabBm.setTextColor(fg)
            tabToc.setTextColor(fg)
            tabTh.setTextColor(fg)
            (header.getChildAt(header.childCount - 1) as? android.widget.ImageButton)
                ?.setColorFilter(fg)
        }

        if (tocItems.isEmpty()) {
            renderTocRows()
            lifecycleScope.launch {
                val items = kotlinx.coroutines.withContext(RenderEngine.dispatcher) {
                    doc.tableOfContents()
                }
                tocItems = items
                tocLoading = false
                if (tocView != null) renderTocRows()
            }
        } else {
            tocLoading = false
            renderTocRows()
        }
        syncChips()
    }

    /** 选项条上的小药丸：选中的填色 + 亮边 + 粗体，未选中只有一圈淡边 */
    private fun styleChip(tv: TextView, on: Boolean, fg: Int) {
        val dp = resources.displayMetrics.density
        tv.setTextColor(if (on) fg else withAlpha(fg, 0x99))
        tv.setTypeface(android.graphics.Typeface.DEFAULT, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        tv.background = GradientDrawable().apply {
            cornerRadius = 13f * dp
            setColor(if (on) withAlpha(fg, 0x3D) else 0x00000000)
            setStroke((1f * dp).toInt().coerceAtLeast(1), withAlpha(fg, if (on) 0xCC else 0x33))
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

    /** 比 barColor 稍暗一档：给面板的选项条用，做出层次又不抢眼 */
    private fun dim(color: Int): Int {
        val f = 0.82f
        return android.graphics.Color.rgb(
            (android.graphics.Color.red(color) * f).toInt(),
            (android.graphics.Color.green(color) * f).toInt(),
            (android.graphics.Color.blue(color) * f).toInt(),
        )
    }

    private fun renderTocRows() {
        val v = tocView ?: return
        val adapter = tocAdapter ?: return
        val thumbAdapter = pageRows ?: return
        val list = v.findViewById<RecyclerView>(R.id.tocList)
        val thumbList = v.findViewById<RecyclerView>(R.id.thumbList)
        val empty = v.findViewById<TextView>(R.id.tvTocEmpty)

        // 缩略图页签：整本书单列往下翻
        if (tocMode == MODE_THUMBS) {
            list.visibility = View.GONE
            thumbList.visibility = View.VISIBLE
            empty.visibility = View.GONE
            // 通常进来时适配器就已经是整本书了，不用换数据、也不动滚动位置；
            // 只有从"书签的缩略图画法"切过来才需要换回来，换了才补一次滚动
            val changed = thumbAdapter.showAll()
            if (changed) {
                thumbList.postDelayed({
                    (thumbList.layoutManager as? GridLayoutManager)
                        ?.scrollToPositionWithOffset(currentPage, 0)
                }, 300L)
            }
            return
        }

        val rows: List<TocAdapter.Row> = if (tocMode == MODE_BOOKMARKS) {
            // 书签按"添加的先后"存，所以按添加直接用，按最近就倒过来
            val pages = if (tocRecentFirst) state.bookmarks.asReversed() else state.bookmarks.toList()
            pages.map { TocAdapter.Row("", it, 0, bookmark = true) }
        } else {
            tocItems.map { TocAdapter.Row(it.title, it.page, it.depth) }
        }
        adapter.submit(rows)

        // 缩略图画法只给书签；目录永远是"标题 + 页码"的文本列表
        val thumbs = tocMode == MODE_BOOKMARKS && tocThumb
        val pages = rows.map { it.page }.distinct()
        list.visibility = if (thumbs) View.GONE else View.VISIBLE
        thumbList.visibility = if (thumbs && pages.isNotEmpty()) View.VISIBLE else View.GONE
        // 只在真的要画缩略图时才换数据，否则缩略图页签的"整本书"状态会被书签覆盖
        if (thumbs) thumbAdapter.showPages(pages)

        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        if (rows.isEmpty()) {
            empty.text = when {
                tocLoading -> getString(R.string.toc_loading)
                tocMode == MODE_BOOKMARKS -> getString(R.string.toc_no_bookmark)
                else -> getString(R.string.toc_empty)
            }
        }
    }

    private fun dismissTocPanel() {
        tocView?.let { b.root.removeView(it) }
        tocView = null
        tocAdapter = null
        pageRows?.release()
        pageRows = null
    }

    // ---- 书签 ----

    private fun hasBookmark(page: Int): Boolean = state.bookmarks.contains(page)

    private fun toggleBookmark() {
        if (hasBookmark(currentPage)) confirmRemoveBookmark(currentPage) else addBookmark(currentPage)
    }

    private fun addBookmark(page: Int) {
        if (page !in state.bookmarks) {
            // 保持"添加的先后"：不再按页码排序，"按最近"才有意义
            state.bookmarks.add(page)
            persistNow()
        }
        playBookmarkFx(added = true)
        if (tocView != null && tocMode == MODE_BOOKMARKS) renderTocRows()
    }

    private fun confirmRemoveBookmark(page: Int) {
        AlertDialog.Builder(this)
            .setTitle("删除书签？")
            .setMessage("第 ${page + 1} 页的书签会被删除。")
            .setPositiveButton("删除") { _, _ ->
                state.bookmarks.remove(page)
                persistNow()
                playBookmarkFx(added = false)
                if (tocView != null) renderTocRows()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 角标动画：加书签是"落下来"的一下，删书签是描边闪一下 */
    private fun playBookmarkFx(added: Boolean) {
        val fx = b.bookmarkFx
        fx.setImageResource(if (added) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border)
        fx.setColorFilter(if (added) 0xFFFFC107.toInt() else 0xFFE0E0E0.toInt())
        fx.visibility = View.VISIBLE
        fx.alpha = 1f
        fx.scaleX = 0.65f
        fx.scaleY = 0.65f
        fx.translationY = -12f * resources.displayMetrics.density
        fx.animate().scaleX(1.15f).scaleY(1.15f).translationY(0f).setDuration(170)
            .withEndAction {
                fx.animate().scaleX(1f).scaleY(1f).alpha(0f).setDuration(300)
                    .withEndAction { fx.visibility = View.GONE }
                    .start()
            }
            .start()
    }

    // ---------------- 导出带批注的 PDF ----------------

    /**
     * 批注都在"图层"里（BookState），源 PDF 一个字节都不改。
     * 导出 = 渲染每一页 → 把高亮/手写/文字批注画在同一张画布上 → 拼成一个新 PDF。
     */
    private fun startExport() {
        val name = state.name.removeSuffix(".pdf") + "-带批注.pdf"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_TITLE, name)
        }
        runCatching { exportLauncher.launch(intent) }
            .onFailure { Toast.makeText(this, "没有可用的文件选择器", Toast.LENGTH_SHORT).show() }
    }

    private val exportLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        runExport(uri)
    }

    private fun runExport(uri: android.net.Uri) {
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = doc.pageCount
        }
        val label = TextView(this).apply {
            setPadding(48, 24, 48, 8)
            setTextColor(0xFFFFFFFF.toInt())
            text = "正在导出 0 / ${doc.pageCount}"
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label)
            addView(bar)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("导出带批注的 PDF")
            .setView(box)
            .setNegativeButton(R.string.cancel, null)
            .setCancelable(false)
            .create()
        dialog.show()

        val job = lifecycleScope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    val out = contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("无法写入")
                    val pdf = android.graphics.pdf.PdfDocument()
                    // 800 是清晰度和体积的折中：再高，318 页扫描件导出会有上百 MB
                    val targetW = 800
                    try {
                        for (i in 0 until doc.pageCount) {
                            val bmp = withContext(RenderEngine.dispatcher) {
                                doc.render(i, targetW)
                            } ?: continue
                            val h = (targetW * bmp.height.toFloat() / bmp.width).toInt()
                            val info = android.graphics.pdf.PdfDocument.PageInfo
                                .Builder(targetW, h, i + 1).create()
                            val page = pdf.startPage(info)
                            page.canvas.drawBitmap(bmp, 0f, 0f, null)
                            drawAnnotationsOn(page.canvas, i, targetW.toFloat(), h.toFloat())
                            pdf.finishPage(page)
                            bmp.recycle()
                            withContext(Dispatchers.Main) {
                                bar.progress = i + 1
                                label.text = "正在导出 ${i + 1} / ${doc.pageCount}"
                            }
                        }
                        pdf.writeTo(out)
                    } finally {
                        runCatching { pdf.close() }
                        runCatching { out.close() }
                    }
                }
            }
            dialog.dismiss()
            if (ok.isSuccess) {
                Toast.makeText(this@ReaderActivity, "导出完成", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(
                    this@ReaderActivity,
                    "导出失败：" + (ok.exceptionOrNull()?.message ?: "未知错误"),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        dialog.setOnDismissListener { if (job.isActive) job.cancel() }
    }

    /** 把这一页的批注按比例画到导出画布上 */
    private fun drawAnnotationsOn(canvas: Canvas, page: Int, w: Float, h: Float) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        for (hl in state.highlights.filter { it.page == page }) {
            val r = RectF(hl.l * w, hl.t * h, hl.r * w, hl.b * h)
            fill.color = (hl.color and 0x00FFFFFF) or (0x5A shl 24)
            canvas.drawRect(r, fill)
            stroke.color = (hl.color and 0x00FFFFFF) or (0xCC shl 24)
            stroke.strokeWidth = 1.6f
            canvas.drawRect(r, stroke)
        }
        for (st in state.strokes.filter { it.page == page }) {
            val pts = st.points
            if (pts.size < 4) continue
            val path = Path()
            path.moveTo(pts[0] * w, pts[1] * h)
            var i = 2
            while (i + 1 < pts.size) {
                path.lineTo(pts[i] * w, pts[i + 1] * h)
                i += 2
            }
            stroke.color = st.color
            stroke.strokeWidth = (st.width * w).coerceAtLeast(1.2f)
            stroke.strokeCap = Paint.Cap.ROUND
            stroke.strokeJoin = Paint.Join.ROUND
            canvas.drawPath(path, stroke)
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG)
        for (n in state.textNotes.filter { it.page == page }) {
            text.textSize = (n.size * w).coerceAtLeast(9f)
            text.color = n.color
            val pad = 4f
            var y = n.y * h
            val maxW = w - n.x * w - pad * 2
            val lines = ArrayList<String>()
            for (para in n.text.split("\n")) {
                var rest = para
                while (rest.isNotEmpty()) {
                    val c = text.breakText(rest, true, maxW, null)
                    if (c <= 0) break
                    lines.add(rest.substring(0, c))
                    rest = rest.substring(c)
                }
                if (lines.isEmpty() || para.isEmpty()) lines.add("")
            }
            val lineH = text.textSize * 1.25f
            var ty = y + pad - text.ascent()
            for (line in lines) {
                canvas.drawText(line, n.x * w + pad, ty, text)
                ty += lineH
            }
        }
    }

    private fun showAbout() {
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "-"
        AlertDialog.Builder(this)
            .setTitle(R.string.about)
            .setMessage("PageHop $version\n本书 ${doc.pageCount} 页\n\n专为扫描版 PDF 调整：自动裁边、颜色模式、手写批注。")
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ---------------- 裁边 ----------------

    private fun showCropMenu() {
        val popup = PopupMenu(this, b.btnCrop)
        popup.menu.add(0, 0, 0, if (state.cropEnabled) "自动裁边：已开启" else "自动裁边：已关闭")
        popup.menu.add(
            0, 1, 1,
            getString(R.string.crop_pad, ((state.cropPad * 1000).toInt() / 10f).toString()),
        )
        popup.menu.add(0, 2, 2, getString(R.string.crop_tune))
        popup.menu.add(0, 3, 3, getString(R.string.crop_reanalyze))
        popup.menu.add(0, 4, 4, "清除手动裁剪")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                0 -> {
                    state.cropEnabled = !state.cropEnabled
                    if (!state.cropEnabled) state.manualCrop = null
                    refreshKeepingPosition()
                    persistNow()
                }

                1 -> showPadDialog()

                2 -> showCropTuneDialog()

                3 -> {
                    state.pageCrops.remove(currentPage)
                    adapter.refreshPage(currentPage)
                    schedulePersist()
                }

                4 -> {
                    state.manualCrop = null
                    refreshKeepingPosition()
                    persistNow()
                }
            }
            true
        }
        popup.show()
    }

    /** 留白比例：裁得紧不紧就靠这一个滑杆，不用再动检测算法 */
    private fun showPadDialog() {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(22), px(16), px(22), px(4))
        }
        val tv = TextView(this).apply {
            text = getString(R.string.crop_pad, ((state.cropPad * 1000).toInt() / 10f).toString())
            setTextColor(Color.WHITE)
            textSize = 15f
        }
        // 0 ~ 10%，步长 0.5%
        val sb = SeekBar(this).apply {
            max = 20
            progress = (state.cropPad * 200).toInt().coerceIn(0, 20)
        }
        var pad = state.cropPad
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                pad = p / 200f
                tv.text = getString(R.string.crop_pad, ((pad * 1000).toInt() / 10f).toString())
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        container.addView(tv)
        container.addView(sb)

        AlertDialog.Builder(this)
            .setTitle(R.string.crop_pad_hint)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                state.cropPad = pad
                state.manualCrop = null
                refreshKeepingPosition()
                persistNow()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showCropTuneDialog() {
        val base = state.manualCrop
            ?: adapter.resolveCrop(currentPage)
            ?: CropRect(0.04f, 0.03f, 0.96f, 0.97f)
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(14), px(20), px(4))
        }
        val values = intArrayOf(
            (base.l * 100).toInt().coerceIn(0, 30),
            (base.t * 100).toInt().coerceIn(0, 30),
            ((1f - base.r) * 100).toInt().coerceIn(0, 30),
            ((1f - base.b) * 100).toInt().coerceIn(0, 30),
        )
        val labelRes = intArrayOf(
            R.string.crop_left, R.string.crop_top, R.string.crop_right, R.string.crop_bottom,
        )
        for (i in 0..3) {
            val tv = TextView(this).apply {
                text = "${getString(labelRes[i])} ${values[i]}%"
                setTextColor(Color.WHITE)
                textSize = 13f
                setPadding(0, px(8), 0, 0)
            }
            val sb = SeekBar(this).apply {
                max = 30
                progress = values[i]
            }
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    values[i] = p
                    tv.text = "${getString(labelRes[i])} $p%"
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            container.addView(tv)
            container.addView(sb)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.crop_tune)
            .setMessage(R.string.crop_tune_hint)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val l = values[0] / 100f
                val t = values[1] / 100f
                val r = (1f - values[2] / 100f).coerceAtLeast(l + 0.25f).coerceAtMost(1f)
                val btm = (1f - values[3] / 100f).coerceAtLeast(t + 0.25f).coerceAtMost(1f)
                state.manualCrop = CropRect(l, t, r, btm)
                refreshKeepingPosition()
                persistNow()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------- 批注 ----------------

    private var penMode = false

    private fun showAnnotationMenu() {
        val popup = PopupMenu(this, b.btnHighlight)
        popup.menu.add(0, 3, 0, getString(R.string.pen_color))
        popup.menu.add(0, 4, 1, getString(R.string.hl_list))
        popup.menu.add(0, 5, 2, "清除本页批注")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                3 -> showColorPicker()
                4 -> showNoteList()
                5 -> clearPageAnnotations()
            }
            true
        }
        popup.show()
    }

    // ---------------- 文字批注 ----------------

    private var textMode = false

    private fun enterTextMode() {
        if (penMode) exitPenMode()
        textMode = true
        adapter.penMode = false
        updatePenButtonStyle()
        adapter.notifyItemRangeChanged(0, doc.pageCount.coerceAtMost(3))
        setBarsVisible(true)
        Toast.makeText(this, "文字批注：点页面任意位置打字", Toast.LENGTH_SHORT).show()
    }

    private fun exitTextMode() {
        textMode = false
        updatePenButtonStyle()
    }

    /** 在页面上点一下：弹输入框，写完存成一条 TextNote */
    private fun showTextNoteDialog(page: Int, x: Float, y: Float, existing: TextNote? = null) {
        val dp = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (14 * dp).toInt(), (20 * dp).toInt(), 0)
        }
        val et = EditText(this).apply {
            hint = "输入批注内容"
            minLines = 2
            maxLines = 5
            if (existing != null) {
                setText(existing.text)
                setSelection(text.length)
            }
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0x88FFFFFF.toInt())
        }
        var color = existing?.color ?: 0xFFFF5252.toInt()
        var size = existing?.size ?: 0.030f

        val colorRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val shades = intArrayOf(
            0xFFFF5252.toInt(), 0xFFFFD54F.toInt(), 0xFF69F0AE.toInt(),
            0xFF64B5F6.toInt(), 0xFFFFFFFF.toInt(),
        )
        val dots = ArrayList<View>()
        fun paintDots() {
            shades.forEachIndexed { i, c ->
                val picked = c == color
                dots[i].background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                    setStroke(
                        (if (picked) 3 else 1) * dp.toInt(),
                        if (picked) 0xFFFFFFFF.toInt() else 0x33FFFFFF,
                    )
                }
            }
        }
        for (c in shades) {
            val v = View(this)
            colorRow.addView(v, LinearLayout.LayoutParams((30 * dp).toInt(), (30 * dp).toInt()).apply { marginEnd = (12 * dp).toInt() })
            dots.add(v)
            v.setOnClickListener { color = c; paintDots() }
        }
        paintDots()

        val sizeSteps = listOf(0.020f, 0.025f, 0.030f, 0.038f, 0.048f)
        val sizeNames = listOf("很小", "小", "中", "大", "很大")
        val startStep = sizeSteps.indexOfFirst { it >= size - 0.001f }.let { if (it < 0) 2 else it }
        val tvSize = TextView(this).apply {
            text = "字号：${sizeNames[startStep]}"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 13f
        }
        val sb = SeekBar(this).apply {
            max = 4
            progress = startStep
        }
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb2: SeekBar?, p: Int, fromUser: Boolean) {
                size = sizeSteps[p.coerceIn(0, 4)]
                tvSize.text = "字号：${sizeNames[p.coerceIn(0, 4)]}"
            }

            override fun onStartTrackingTouch(sb2: SeekBar?) {}
            override fun onStopTrackingTouch(sb2: SeekBar?) {}
        })

        box.addView(et)
        box.addView(TextView(this).apply {
            text = "颜色"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 13f
            setPadding(0, (10 * dp).toInt(), 0, (6 * dp).toInt())
        })
        box.addView(colorRow)
        box.addView(tvSize)
        box.addView(sb)

        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing != null) "编辑文字批注 · 第 ${page + 1} 页" else "第 ${page + 1} 页 · 文字批注")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val txt = et.text.toString().trim()
                if (txt.isEmpty()) return@setPositiveButton
                if (existing != null) {
                    // 就地替换（TextNote 是 data class，换一个实例即可）
                    val i = state.textNotes.indexOfFirst { it.id == existing.id }
                    if (i >= 0) {
                        state.textNotes[i] = existing.copy(
                            text = txt,
                            color = color,
                            size = size,
                        )
                    }
                } else {
                    state.textNotes.add(
                        TextNote(
                            id = java.util.UUID.randomUUID().toString(),
                            page = page,
                            x = x.coerceIn(0.02f, 0.75f),
                            y = y.coerceIn(0.02f, 0.95f),
                            text = txt,
                            color = color,
                            size = size,
                        ),
                    )
                }
                refreshAnnotations(page)
                persistNow()
            }
            .setNegativeButton(R.string.cancel, null)
        if (existing != null) {
            builder.setNeutralButton("删除") { _, _ -> deleteTextNote(existing) }
        }
        val dlg = builder.show()
        // 直接聚焦输入框并弹出输入法：点开就能打字，不用再点一下
        dlg.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        et.requestFocus()
        et.postDelayed({
            val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            imm?.showSoftInput(et, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 120)
    }

    private fun deleteTextNote(note: TextNote) {
        state.textNotes.removeAll { it.id == note.id }
        refreshAnnotations(note.page)
        persistNow()
        Toast.makeText(this, "已删除这条批注", Toast.LENGTH_SHORT).show()
    }

    /**
     * 批注模式：单击「批注」直接开关，长按才是菜单。
     * 进入后收掉上下工具栏、把整屏让给书写；想退出就双击页面把工具栏呼出来。
     */
    private fun enterPenMode() {
        if (textMode) exitTextMode()
        penMode = true
        adapter.penMode = true
        applyPenMode()
        updatePenButtonStyle()
        setBarsVisible(false)
        // 工具栏一收就把手势规则说清楚：批注模式单指是笔，翻页得用双指，
        // 不然"写完想往下翻，页面纹丝不动"会以为卡住了。
        Toast.makeText(
            this,
            "单指写字 · 双指上下滑翻页 · 双击页面调出工具栏",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun exitPenMode() {
        if (!penMode) return
        penMode = false
        adapter.penMode = false
        applyPenMode()
        updatePenButtonStyle()
        setBarsVisible(true)
    }

    /** 批注模式开启时，把顶栏那个「批注」图标点亮，一眼能看出当前在写字 */
    private fun updatePenButtonStyle() {
        b.ivHighlight.setColorFilter(
            if (penMode) 0xFFFF5252.toInt() else UiPrefs.barContent,
        )
        b.btnHighlight.setBackgroundResource(if (penMode) R.drawable.bg_active_item else 0)
        b.btnText.setColorFilter(if (textMode) 0xFF64B5F6.toInt() else UiPrefs.barContent)
        b.btnText.setBackgroundResource(if (textMode) R.drawable.bg_active_item else 0)
    }

    private fun applyPenMode() {
        for (i in 0 until b.pageList.childCount) {
            val child = b.pageList.getChildAt(i)
            if (child is PageView) child.penMode = penMode
        }
    }

    private fun showColorPicker() {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(24), px(22), px(24), px(22))
        }
        var dlg: AlertDialog? = null
        for (c in hlColors) {
            val v = View(this)
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c)
            }
            row.addView(v, LinearLayout.LayoutParams(px(48), px(48)).apply { marginEnd = px(14) })
            v.setOnClickListener {
                adapter.annotationColor = c
                applyPenColor()
                dlg?.dismiss()
            }
        }
        dlg = AlertDialog.Builder(this)
            .setTitle(R.string.pen_color)
            .setView(row)
            .create()
        dlg.show()
    }

    private fun applyPenColor() {
        for (i in 0 until b.pageList.childCount) {
            val child = b.pageList.getChildAt(i)
            if (child is PageView) {
                child.penColor = adapter.annotationColor
                child.invalidate()
            }
        }
    }

    private fun clearPageAnnotations() {
        val page = currentPage
        state.highlights.removeAll { it.page == page }
        state.strokes.removeAll { it.page == page }
        refreshAnnotations(page)
        persistNow()
    }

    private fun showStrokeOptions(s: InkStroke) {
        AlertDialog.Builder(this)
            .setTitle("第 ${s.page + 1} 页的笔迹")
            .setItems(arrayOf("加/改笔记", "换颜色", "删除")) { _, which ->
                when (which) {
                    0 -> editStrokeNote(s)
                    1 -> pickStrokeColor(s)
                    2 -> {
                        state.strokes.removeAll { it.id == s.id }
                        refreshAnnotations(s.page)
                        persistNow()
                    }
                }
            }
            .show()
    }

    private fun editStrokeNote(s: InkStroke) {
        val dp = resources.displayMetrics.density
        val et = EditText(this).apply {
            setText(s.note)
            setHint(R.string.hl_note_hint)
            setPadding((dp * 20).toInt(), (dp * 12).toInt(), (dp * 20).toInt(), (dp * 12).toInt())
        }
        AlertDialog.Builder(this)
            .setTitle("第 ${s.page + 1} 页的笔记")
            .setView(et)
            .setPositiveButton(R.string.save) { _, _ ->
                val idx = state.strokes.indexOfFirst { it.id == s.id }
                if (idx >= 0) {
                    state.strokes[idx] = state.strokes[idx].copy(note = et.text.toString())
                    refreshAnnotations(s.page)
                    persistNow()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickStrokeColor(s: InkStroke) {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(24), px(20), px(24), px(20))
        }
        var dlg: AlertDialog? = null
        for (c in hlColors) {
            val v = View(this)
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c)
            }
            row.addView(v, LinearLayout.LayoutParams(px(46), px(46)).apply { marginEnd = px(14) })
            v.setOnClickListener {
                val idx = state.strokes.indexOfFirst { it.id == s.id }
                if (idx >= 0) {
                    state.strokes[idx] = state.strokes[idx].copy(color = c)
                    refreshAnnotations(s.page)
                    persistNow()
                }
                dlg?.dismiss()
            }
        }
        dlg = AlertDialog.Builder(this).setTitle(R.string.pen_color).setView(row).create()
        dlg?.show()
    }

    private fun showHighlightOptions(h: Highlight) {
        AlertDialog.Builder(this)
            .setTitle("第 ${h.page + 1} 页")
            .setItems(arrayOf("加/改笔记", "换颜色", "删除")) { _, which ->
                when (which) {
                    0 -> editNote(h)
                    1 -> pickColorFor(h)
                    2 -> {
                        state.highlights.removeAll { it.id == h.id }
                        refreshAnnotations(h.page)
                        persistNow()
                    }
                }
            }
            .show()
    }

    private fun editNote(h: Highlight) {
        val dp = resources.displayMetrics.density
        val et = EditText(this).apply {
            setText(h.note)
            setHint(R.string.hl_note_hint)
            setPadding((dp * 20).toInt(), (dp * 12).toInt(), (dp * 20).toInt(), (dp * 12).toInt())
        }
        AlertDialog.Builder(this)
            .setTitle("第 ${h.page + 1} 页的笔记")
            .setView(et)
            .setPositiveButton(R.string.save) { _, _ ->
                val idx = state.highlights.indexOfFirst { it.id == h.id }
                if (idx >= 0) {
                    state.highlights[idx] = state.highlights[idx].copy(note = et.text.toString())
                    refreshAnnotations(h.page)
                    persistNow()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickColorFor(h: Highlight) {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(24), px(20), px(24), px(20))
        }
        var dlg: AlertDialog? = null
        for (c in hlColors) {
            val v = View(this)
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c)
            }
            row.addView(v, LinearLayout.LayoutParams(px(46), px(46)).apply { marginEnd = px(14) })
            v.setOnClickListener {
                val idx = state.highlights.indexOfFirst { it.id == h.id }
                if (idx >= 0) {
                    state.highlights[idx] = state.highlights[idx].copy(color = c)
                    refreshAnnotations(h.page)
                    persistNow()
                }
                dlg?.dismiss()
            }
        }
        dlg = AlertDialog.Builder(this).setTitle(R.string.hl_color).setView(row).create()
        dlg?.show()
    }

    private fun showNoteList() {
        data class Row(val page: Int, val label: String)

        val rows = mutableListOf<Row>()
        state.highlights.forEach { h ->
            rows.add(Row(h.page, "第 ${h.page + 1} 页 · 高亮  ${h.note.ifBlank { "（无笔记）" }}"))
        }
        state.strokes.forEach { s ->
            rows.add(Row(s.page, "第 ${s.page + 1} 页 · 手写  ${s.note.ifBlank { "（无笔记）" }}"))
        }
        if (rows.isEmpty()) {
            Toast.makeText(this, R.string.hl_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val sorted = rows.sortedBy { it.page }
        AlertDialog.Builder(this)
            .setTitle(R.string.hl_list)
            .setItems(sorted.map { it.label }.toTypedArray()) { _, i -> jumpTo(sorted[i].page) }
            .show()
    }

    // ---------------- 跳转 ----------------

    /**
     * 缩略图"地图"：全屏、无间隙、双指改变列数（密度）。
     * 上千页的书翻到这里可以一屏扫完，再点任意一页跳过去。
     *
     * 做成"当前窗口里的覆盖层"而不是 Dialog：Dialog 是独立窗口，
     * 会被系统栏 inset 挤下去（顶栏露在面板上面、底栏被挤出屏幕）。
     * 覆盖层和正文同一个窗口，天然就是全屏。
     */
    private var thumbMap: View? = null
    private var thumbMapAdapter: ThumbAdapter? = null

    private fun dismissThumbMap() {
        thumbMap?.let { b.root.removeView(it) }
        thumbMap = null
        thumbMapRelayout = null
        thumbMapAdapter?.release()
        thumbMapAdapter = null
    }

    private fun showThumbMap() {
        if (thumbMap != null) return
        val v = layoutInflater.inflate(R.layout.dialog_thumbs, b.root, false)
        v.setBackgroundColor(ColorModes.background(state.colorMode))
        v.isClickable = true
        // 顶/底栏有 elevation(6dp)，同层级里会压住后加进来的视图。
        // 覆盖层要高过它们，否则面板自己的标题栏和底部滑条会被阅读器两条栏挡住。
        v.elevation = 16f * resources.displayMetrics.density
        b.root.addView(
            v,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        thumbMap = v

        val grid = v.findViewById<ZoomGrid>(R.id.thumbGrid)
        val tvMapPage = v.findViewById<TextView>(R.id.tvMapPage)
        val tvDensity = v.findViewById<TextView>(R.id.tvDensity)
        val badge = v.findViewById<TextView>(R.id.tvDensityBadge)
        val seek = v.findViewById<SeekBar>(R.id.seekBar)
        val btnClose = v.findViewById<View>(R.id.btnClose)

        // 面板的顶/底栏也跟随"界面配色"（否则换配色后这里还是深青）
        val barFg = UiPrefs.barContent
        val barFaint = (barFg and 0x00FFFFFF) or 0xAA000000.toInt()
        val bottomBar = v.findViewById<View>(R.id.thumbBottom)
        bottomBar.setBackgroundColor(UiPrefs.barColor)
        (btnClose as? android.widget.ImageButton)?.setColorFilter(barFg)
        v.findViewById<TextView>(R.id.tvMapPage).setTextColor(barFg)
        v.findViewById<TextView>(R.id.tvDensity).setTextColor(barFaint)
        val barSolid = android.content.res.ColorStateList.valueOf(barFg)
        val barTrack = android.content.res.ColorStateList.valueOf((barFg and 0x00FFFFFF) or 0x4D000000)
        seek.progressTintList = barSolid
        seek.thumbTintList = barSolid
        seek.progressBackgroundTintList = barTrack

        val n = doc.pageCount
        val metrics = resources.displayMetrics

        val startSpans = UiPrefs.thumbSpans.coerceIn(ThumbZoom.MIN_SPANS, ThumbZoom.MAX_SPANS)
        val glm = GridLayoutManager(this, startSpans)
        grid.layoutManager = glm
        grid.setItemViewCacheSize(24)
        grid.currentSpan = glm.spanCount

        val thumbAdapter = ThumbAdapter(doc, docId, state.colorMode, currentPage) { p ->
            dismissThumbMap()
            jumpTo(p)
        }
        thumbMapAdapter = thumbAdapter
        grid.adapter = thumbAdapter

        fun applyCellSize() {
            // 用网格"当前"的宽高，而不是打开面板时缓存的 metrics：
            // 横竖屏切换后屏幕尺寸变了，缓存值会让格子尺寸对不上（一边大一边小/被裁掉）
            val dm = resources.displayMetrics
            val gridW = if (grid.width > 0) grid.width else dm.widthPixels
            val gridH = if (grid.height > 0) grid.height else dm.heightPixels
            val cellW = (gridW / glm.spanCount).coerceAtLeast(48)
            thumbAdapter.cellWidth = cellW
            // 按页面比例算高度，但不超过可视高度（横屏时否则一行都放不下）
            val byAspect = (cellW * 1.36f).toInt()
            val maxH = (gridH - (8 * resources.displayMetrics.density).toInt()).coerceAtLeast(120)
            thumbAdapter.cellHeight = byAspect.coerceAtMost(maxH)
            grid.currentSpan = glm.spanCount
            tvDensity.text = "${glm.spanCount} 列"
        }
        applyCellSize()

        // 默认的 DefaultItemAnimator 会在 notifyDataSetChanged 时做淡出淡入，
        // 缩放换列时看起来就是"闪一下"，这里直接关掉。
        grid.itemAnimator = null

        // 缩放时在屏幕中央显示“N 列”，让人知道现在到哪一档了
        fun showBadge(span: Int) {
            badge.text = "$span 列"
            badge.animate().cancel()
            badge.alpha = 1f
            badge.visibility = View.VISIBLE
        }

        fun hideBadgeSoon() {
            badge.animate().alpha(0f).setStartDelay(500).setDuration(200)
                .withEndAction { if (badge.alpha < 0.1f) badge.visibility = View.GONE }
                .start()
        }

        /**
         * 换列数，同时保住“当前看的是哪一页”。
         *
         * 关键：这里绝对不能调 notifyDataSetChanged()。实测它会让 RecyclerView 丢掉锚点，
         * 重排后直接回到第 1 页（3 列时看到 100 页，切成 2 列就跳回第 1 页）。
         * 只改可见格子的高度、再把锚点页顶到最上面，位置就保住了。
         */
        fun applySpan(target: Int, anchor: Int) {
            val keep = if (anchor >= 0) anchor else glm.findFirstVisibleItemPosition()
            glm.spanCount = target
            applyCellSize()
            UiPrefs.setThumbSpans(this, target)

            // 现成的格子：宽高都要跟着新列数改。
            // 之前只改了高度，列的宽度由 LayoutManager 负责，但图片是"死尺寸"，
            // 于是从多列变少列时格子变宽、里面的图却还是小的，看起来就是"缩略图没变大"。
            val w = thumbAdapter.cellWidth
            val h = thumbAdapter.cellHeight
            for (i in 0 until grid.childCount) {
                val child = grid.getChildAt(i)
                val lp = child.layoutParams
                if (lp.height != h) {
                    lp.height = h
                    child.layoutParams = lp
                }
                val img = child.findViewById<ImageView>(R.id.imgThumb)
                val ilp = img.layoutParams
                if (ilp.width != w || ilp.height != h) {
                    ilp.width = w
                    ilp.height = h
                    img.layoutParams = ilp
                }
            }
            if (keep >= 0) glm.scrollToPositionWithOffset(keep, 0)
            // 让可见格重新绑定，按新宽度重渲染（否则放大格子后还是那张小图，糊）
            val first = glm.findFirstVisibleItemPosition()
            val last = glm.findLastVisibleItemPosition()
            if (first >= 0 && last >= first) {
                grid.post { thumbAdapter.notifyItemRangeChanged(first, last - first + 1) }
            }
            // 这里**不弹**"N 列"角标：转屏、打开面板时的重排也会走到这里，
            // 如果顺手弹一个就没人负责收——之前"打开缩略图地图，中间一直挂着一个 3 列"就是这个。
            // 需要提示的调用方（双指缩放、点顶栏列数）自己调 showBadge()。
        }

        thumbMapRelayout = { applySpan(glm.spanCount, glm.findFirstVisibleItemPosition()) }
        // 转屏时 onConfigurationChanged 早于重新测量，那时 grid.width 还是旧值。
        // 真正可靠的做法是等网格自己的尺寸变了再重算。
        grid.addOnLayoutChangeListener { _, l, t, r, btm, ol, ot, or, ob ->
            val w = r - l
            val h = btm - t
            if (w <= 0 || h <= 0) return@addOnLayoutChangeListener
            if (w == or - ol && h == ob - ot) return@addOnLayoutChangeListener
            grid.post { applySpan(glm.spanCount, glm.findFirstVisibleItemPosition()) }
        }

        // 手势开始时只记一次锚点：若每个事件都重新取，中途 position 可能已失效（-1）
        var anchorAtScale = -1
        grid.onScaleBeginCallback = {
            anchorAtScale = glm.findFirstVisibleItemPosition()
            showBadge(glm.spanCount)
        }
        grid.onScaleEndCallback = {
            anchorAtScale = -1
            hideBadgeSoon()
        }
        grid.onSpanRequested = { target ->
            applySpan(target, anchorAtScale)
            showBadge(target)
        }

        // 顶栏的“N 列”也能点：点一下换一档，给不方便做双指手势的场合留个入口
        tvDensity.setOnClickListener {
            val next = if (glm.spanCount >= ThumbZoom.MAX_SPANS) ThumbZoom.MIN_SPANS
            else glm.spanCount + 1
            applySpan(next, glm.findFirstVisibleItemPosition())
            showBadge(next)
            hideBadgeSoon()
        }

        var draggingSeek = false
        // 只有当"用户自己拖动网格"时，才让滑条跟着网格走。
        // 点滑条跳页是程序化 scrollToPositionWithOffset：布局还没落定时
        // findFirstVisibleItemPosition() 可能短暂给出 0，一旦回写就成了
        // "滑条从 50%/80% 突然跳回 0%"——之前反复复现不出来的就是这个回写路径。
        var userScrollingGrid = false
        var lastSyncedPage = currentPage

        fun syncFromGrid() {
            val p = (grid.layoutManager as? GridLayoutManager)
                ?.findFirstVisibleItemPosition() ?: return
            if (p < 0) return
            tvMapPage.text = "${p + 1} / $n"
            // 从 3 页往后一步跳到 0 页不可能是真实滚动（真滚会逐页经过），丢掉
            if (p == 0 && lastSyncedPage > 3) return
            if (!draggingSeek) seek.progress = p
            lastSyncedPage = p
        }

        tvMapPage.text = "${currentPage + 1} / $n"
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                when (newState) {
                    RecyclerView.SCROLL_STATE_DRAGGING -> userScrollingGrid = true
                    RecyclerView.SCROLL_STATE_IDLE -> if (userScrollingGrid) {
                        userScrollingGrid = false
                        syncFromGrid()
                    }
                }
            }

            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (userScrollingGrid) syncFromGrid()
            }
        })

        seek.max = (n - 1).coerceAtLeast(0)
        seek.progress = currentPage
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                tvMapPage.text = "${p + 1} / $n"
                lastSyncedPage = p
                glm.scrollToPositionWithOffset(p, 0)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                draggingSeek = true
            }

            // 松手不再跳页、也不关闭面板：这条滑杆只负责"在图上滚到第 N 页"，
            // 真正的跳转由点缩略图完成。之前松手就跳，看起来像"拖动事件穿透到了缩略图"。
            override fun onStopTrackingTouch(sb: SeekBar?) {
                draggingSeek = false
            }
        })

        btnClose.setOnClickListener { dismissThumbMap() }

        // ---------------- 长按预览 ----------------
        val previewLayer = v.findViewById<View>(R.id.previewLayer)
        val previewImage = v.findViewById<ImageView>(R.id.previewImage)
        val previewBottom = v.findViewById<ImageView>(R.id.previewImageBottom)
        val previewDivider = v.findViewById<View>(R.id.previewDivider)
        // 棋盘格线画在网格内部：会跟着网格一起被模糊，半透明大图盖在它上面
        val gridLines = GridLinesDecoration(resources.displayMetrics.density)
        grid.addItemDecoration(gridLines)
        var previewJob: Job? = null
        var hovered = -1
        var longPressed = false
        var downX = 0f
        var downY = 0f
        val slop = ViewConfiguration.get(this).scaledTouchSlop

        fun setVisibleNumbers(visible: Boolean) {
            for (i in 0 until grid.childCount) {
                grid.getChildAt(i)?.findViewById<TextView>(R.id.tvNum)?.visibility =
                    if (visible) View.VISIBLE else View.GONE
            }
        }
        fun pageAt(x: Float, y: Float): Int {
            val child = grid.findChildViewUnder(x, y) ?: return -1
            val pos = grid.getChildAdapterPosition(child)
            return if (pos == RecyclerView.NO_POSITION) -1 else pos
        }

        fun renderPreview(page: Int) {
            hovered = page
            previewLayer.visibility = View.VISIBLE
            // 预览大图要和正文同一个颜色模式（夜间/棕褐…），否则出来一张白纸很跳
            val filter = ColorModes.filter(state.colorMode)
            previewImage.setColorFilter(filter)
            previewBottom.setColorFilter(filter)
            previewImage.alpha = UiPrefs.previewAlpha
            previewBottom.alpha = UiPrefs.previewAlpha
            // 横屏时整页缩在屏幕中间一小条，字根本看不清：竖着切成上下两半铺满左右，
            // 等于把字号放大一倍（和长按箭头那个面板的做法一致）
            val dm = resources.displayMetrics
            val split = dm.widthPixels > dm.heightPixels
            previewBottom.visibility = if (split) View.VISIBLE else View.GONE
            previewDivider.visibility = if (split) View.VISIBLE else View.GONE
            // 底栏画在预览层上面：给它让出位置，别把整页的下沿（横屏时是右半边）压掉
            val barH = bottomBar.height.takeIf { it > 0 }
                ?: (46 * dm.density).toInt()
            previewLayer.setPadding(0, 0, 0, barH)
            previewJob?.cancel()
            // 指到哪页就渲染哪页的大图（按屏宽 90% 渲染，比格子里的缩略图清楚得多）
            previewJob = lifecycleScope.launch {
                // 横屏每一半只占半屏宽，按半屏宽渲染就够 1:1 清晰
                val w = if (split) (dm.widthPixels / 2).coerceAtLeast(320) else dm.widthPixels
                val bmp = RenderEngine.thumb(doc, docId, page, w)
                if (bmp != null && hovered == page) {
                    if (split) {
                        val (top, bottom) = pageHalves(bmp)
                        previewImage.setImageBitmap(top)
                        previewBottom.setImageBitmap(bottom)
                    } else {
                        previewImage.setImageBitmap(bmp)
                    }
                    tvMapPage.text = "${page + 1} / $n"
                }
            }
        }

        fun leavePreview(keepFocus: Boolean) {
            val last = hovered
            previewJob?.cancel()
            previewImage.setImageDrawable(null)
            previewBottom.setImageDrawable(null)
            previewLayer.visibility = View.GONE
            gridLines.active = false
            thumbAdapter.hideNumbers = false
            setVisibleNumbers(true)
            grid.invalidate()
            hovered = -1
            grid.scrollLocked = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                grid.setRenderEffect(null)
            }
            if (keepFocus && last >= 0) {
                // 松手后只把最后一页标红，**不动**地图的位置
                // （之前会把它滚到屏幕中间，看起来像"地图自己动了一下"）
                thumbAdapter.focusPage = last
                tvMapPage.text = "${last + 1} / $n"
                seek.progress = last
                lastSyncedPage = last
            }
        }

        val longPress = Runnable {
            longPressed = true
            // 锁住滚动/双指缩放，并把网格糊掉
            grid.scrollLocked = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                grid.setRenderEffect(
                    RenderEffect.createBlurEffect(22f, 22f, Shader.TileMode.CLAMP),
                )
            }
            // 清掉格子的按下态，免得松手后还留着一块高亮
            for (i in 0 until grid.childCount) grid.getChildAt(i).isPressed = false
            // 棋盘格红线：给手指一个"现在停在哪一格"的参照
            gridLines.color = UiPrefs.gridColor
            gridLines.active = UiPrefs.gridLines
            // 同时把每格右下角的页码角标藏起来，松手再恢复
            thumbAdapter.hideNumbers = true
            setVisibleNumbers(false)
            grid.invalidate()
            vibrateLongPress()
            val p = pageAt(downX, downY)
            if (p >= 0) renderPreview(p) else leavePreview(false)
        }

        grid.touchInterceptor = { e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    longPressed = false
                    downX = e.x
                    downY = e.y
                    grid.postDelayed(longPress, 380L)
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!longPressed) {
                        // 还没长按成立：手指一挪就取消，保证正常滑动/点击不受影响
                        if (abs(e.x - downX) > slop || abs(e.y - downY) > slop) {
                            grid.removeCallbacks(longPress)
                        }
                        false
                    } else {
                        val p = pageAt(e.x, e.y)
                        if (p >= 0 && p != hovered) renderPreview(p)
                        true
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    grid.removeCallbacks(longPress)
                    if (longPressed) {
                        longPressed = false
                        leavePreview(keepFocus = e.actionMasked == MotionEvent.ACTION_UP)
                        true
                    } else {
                        false
                    }
                }

                else -> longPressed
            }
        }

        // 关键：在第一次 layout 之前就把位置定在"当前页"，
        // 否则会先铺出第 1 页附近的缩略图、再滚过去，看起来就是"快速闪一下并扫过全书"。
        glm.scrollToPositionWithOffset(currentPage, 0)

        // 把当前页滚到视口正中。要在 RecyclerView 真正量出高度之后做：
        // 高度还是 0 时设的位置会被丢掉（这次改了底栏高度后就是这么丢的）。
        // 所以做成"量到高度就居中，量不到隔 40ms 再试"，而不是赌一次 post。
        val centerTask = object : Runnable {
            override fun run() {
                val contentH = grid.height - grid.paddingTop - grid.paddingBottom
                if (contentH <= 0) {
                    grid.postDelayed(this, 40)
                    return
                }
                val offset = ((contentH - thumbAdapter.cellHeight) / 2).coerceAtLeast(0)
                glm.scrollToPositionWithOffset(currentPage, offset)
            }
        }
        grid.post(centerTask)
    }

    override fun onBackPressed() {
        // 缩略图面板开着时，返回键先关面板
        if (tocView != null) {
            dismissTocPanel()
            return
        }
        if (thumbMap != null) {
            dismissThumbMap()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    /**
     * 主动定点跳转：输页码、点目录 / 书签、点缩略图、点历史列表、拖底部进度条松手都走这里。
     *
     * 跳过去之后要"停满 1 秒"才记账 —— 不到 1 秒又翻走（或者又跳走），这次跳转
     * 就不算历史。逐页翻页直接走 [goToPage]，永远不会进历史。
     */
    private fun jumpTo(page: Int, banner: Boolean = true) {
        val p = page.coerceIn(0, doc.pageCount - 1)
        if (p == currentPage) return
        val from = currentPage
        asJump { goToPage(p) }
        armJumpCommit(from, p)
        if (banner) showJumpBanner(p, from)
    }

    /** 只换页：滚动 + 刷新指示器，不碰历史（前进 / 后退 / 拖进度条 / 翻页都用它） */
    private fun goToPage(page: Int) {
        val p = page.coerceIn(0, doc.pageCount - 1)
        if (p == currentPage) return
        (b.pageList.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(p, 0)
        setPage(p)
        schedulePersist()
    }

    /**
     * 音量键翻"一屏"，不是翻"一页"。
     *
     * 屏幕上能放几页，一次就滚几页：竖屏一屏 1.4 页就滚 1.4 页，横屏只看得见半页
     * 就滚半页。再留一点重叠（[VOLUME_SCROLL_OVERLAP]），上下屏之间有几行接得上，
     * 不会正好把一句话切在边缘。
     *
     * 放大过之后，能看到的列表高度只有 1/scale，按这个算才不会一次滚过头。
     */
    private fun scrollByViewport(forward: Boolean) {
        if (!::adapter.isInitialized) return
        val view = b.pageList
        val screen = b.zoomFrame.scale.coerceAtLeast(1f)
        // 正文区 = 列表高度减掉上下工具栏占用的 padding（工具栏收起时 padding 是 0）
        val content = (view.height - view.paddingTop - view.paddingBottom)
            .takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val visible = content / screen
        val dy = (visible * (1f - VOLUME_SCROLL_OVERLAP)).toInt().coerceAtLeast(1)
        view.smoothScrollBy(0, if (forward) dy else -dy)
        schedulePersist()
    }

    /**
     * 音量键翻页（设置里默认关闭）。
     * 面板 / 弹窗开着时不接管，免得在目录里翻页把面板后面的正文滚走。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (UiPrefs.volumeKeys && ::doc.isInitialized &&
            tocView == null && thumbMap == null && historyDialog == null
        ) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    scrollByViewport(forward = false)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    scrollByViewport(forward = true)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------------- 前进 / 后退 ----------------

    private val history = PageHistory(HISTORY_MAX)

    /** 跳转后停留多久才算一次有效历史（松手 / 落点之后开始计时） */
    private val jumpDwellMs = 1_000L

    private var pendingJump: Runnable? = null

    /** 正在跳转：这一步换页是"跳"过去的，历史锚点要留在起跳页，别跟着目标页跑 */
    private var jumping = false

    private inline fun <T> asJump(block: () -> T): T {
        val prev = jumping
        jumping = true
        try {
            return block()
        } finally {
            jumping = prev
        }
    }

    /** 所有"换页"都走这里；历史只在 [jumpTo] 停满 1 秒后由 [armJumpCommit] 写入 */
    private fun setPage(newPage: Int) {
        val p = newPage.coerceIn(0, doc.pageCount - 1)
        if (p == currentPage) return
        // 还没停满 1 秒就换页：这次主动跳转作废
        cancelPendingJump()
        currentPage = p
        // 逐页读到这里：记录点跟着走，这样后退回的是上一次跳转的起点；
        // 但最近跳转列表不动 —— 列表只装跳转的两端。跳转目标页、拖进度条途中的
        // 中间页更不能算"读到这里"，锚点要留在起跳页。
        if (!jumping && !draggingSeek) history.moveTo(p)
        updateIndicator()
    }

    /** 主动跳转落点开始计时，1 秒后仍在同一页才记入历史 */
    private fun armJumpCommit(from: Int, page: Int) {
        cancelPendingJump()
        val r = Runnable {
            pendingJump = null
            if (currentPage != page) return@Runnable
            history.commit(from, page)
            updateHistoryButtons()
        }
        pendingJump = r
        main.postDelayed(r, jumpDwellMs)
    }

    private fun cancelPendingJump() {
        pendingJump?.let { main.removeCallbacks(it) }
        pendingJump = null
    }

    private fun updateHistoryButtons() {
        val fg = UiPrefs.barContent
        // 置灰：保留前景色的 RGB、只降透明度。原来写成 or 0x38FFFFFF，
        // 会把 RGB 全刷成白色 —— 浅色界面下就是"白色压在浅底上"，等于看不见
        val dim = (fg and 0x00FFFFFF) or (0x38 shl 24)
        val canBack = history.canGoBack(currentPage)
        val canForward = history.canGoForward()
        b.btnPageBack.alpha = if (canBack) 1f else 0.28f
        b.btnPageBack.setColorFilter(if (canBack) fg else dim)
        b.btnPageForward.alpha = if (canForward) 1f else 0.28f
        b.btnPageForward.setColorFilter(if (canForward) fg else dim)
    }

    private fun goBack() {
        val from = currentPage
        val target = history.back(from) ?: return
        goToPage(target)
        showJumpBanner(target, from)
    }

    private fun goForward() {
        val from = currentPage
        val target = history.forward() ?: return
        goToPage(target)
        showJumpBanner(target, from)
    }

    /**
     * 横屏放大预览：把整页竖着切成上下两半，上半摆左边、下半摆右边。
     * 每一半都能占满半个屏幕宽，等于把字号放大一倍。
     */
    private fun pageHalves(bmp: Bitmap): Pair<Bitmap, Bitmap> {
        if (bmp.height <= 8) return bmp to bmp
        val half = bmp.height / 2
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, half) to
            Bitmap.createBitmap(bmp, 0, half, bmp.width, bmp.height - half)
    }

    /**
     * 长按左 / 右箭头：最近主动跳过的 6 页用缩略图摆 2×3，尽量大，点一张直接跳。
     *
     * 长按其中某一格 = 把那一页放大到整屏（压暗背景、不透明），看得清了松手就恢复。
     */
    private fun showHistoryDialog() {
        val pages = history.recentPages().take(6)
        if (pages.isEmpty()) {
            // 列表只装跳转的两端：打开书、逐页阅读都不算，所以第一次跳转之前是空的
            Toast.makeText(this, "还没有跳转记录", Toast.LENGTH_SHORT).show()
            return
        }
        val dm = resources.displayMetrics
        val density = dm.density
        val landscape = dm.widthPixels > dm.heightPixels
        // 竖屏最多 2 列（3 行），横屏最多 6 列（单行）。
        // 条目少的时候按实际条数铺满，别把右边一大片空着。
        val cols = when {
            landscape -> minOf(6, pages.size)
            pages.size == 1 -> 1
            else -> 2
        }
        val rowsWanted = (pages.size + cols - 1) / cols
        // 面板只在两条工具栏之间那块地方里居中。以前按整屏算尺寸：
        // 横屏时上下的粉色工具栏会被压住，左右也没有对称留白。
        val topInset = if (::b.isInitialized && b.topBar.isShown) b.topBar.height else 0
        val botInset = if (::b.isInitialized && b.bottomBar.isShown) b.bottomBar.height else 0
        val edge = (16 * density).toInt()
        val pad = (6 * density).toInt()
        val availW = (dm.widthPixels - 2 * edge).coerceAtLeast(200)
        val availH = (dm.heightPixels - topInset - botInset - 2 * edge).coerceAtLeast(200)
        val cellW0 = ((availW - 2 * pad) / cols).coerceAtLeast(80)
        val cellH = minOf(
            (cellW0 * 1.36f).toInt(),
            ((availH - 2 * pad) / rowsWanted).coerceAtLeast(80),
        )
        // 高被"可用高度"咬住时把宽度也收回来，保持一页纸的比例：
        // 否则横屏一行只有两格，格子会又矮又胖，页缩在中间一条、两边全空
        val cellW = minOf(cellW0, (cellH / 1.36f).toInt().coerceAtLeast(80))
        val grid = ZoomGrid(this).apply {
            layoutManager = GridLayoutManager(this@ReaderActivity, cols)
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(pad, pad, pad, pad)
            // 面板背景从"对话框窗口"挪到网格自己身上：窗口现在是整屏透明的，
            // 只装一个居中的面板，长按放大时才有一整屏可用。
            background = getDrawable(R.drawable.bg_dialog_panel)
        }
        val adapter = PageRowAdapter(doc, docId) { page ->
            historyDialog?.dismiss()
            jumpTo(page)
        }
        adapter.fixedCellHeight = cellH
        adapter.rowWidth = cellW
        adapter.currentPage = currentPage
        adapter.showOnly(pages)
        grid.adapter = adapter

        // ---------------- 长按放大预览 ----------------
        // 竖屏：一整页铺满屏宽；横屏：同一页竖着切成上下两半，上半张摆屏幕左半边、
        // 下半张摆右半边 —— 每一半都能占满半个屏幕的宽度，等于把字放大了 1.5 倍。
        fun previewImage(): ImageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            // 和正文同一个颜色模式，否则夜里弹出一张白纸
            setColorFilter(ColorModes.filter(state.colorMode))
        }

        val leftImg = previewImage()
        val rightImg = previewImage()
        val previewHolder = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(
                (6 * density).toInt(),
                ((if (landscape) 10 else 44) * density).toInt(),
                (6 * density).toInt(),
                ((if (landscape) 10 else 48) * density).toInt(),
            )
            if (landscape) {
                addView(leftImg, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                // 一条细缝，提示左边是上半页、右边是下半页
                addView(
                    View(this@ReaderActivity).apply { setBackgroundColor(0x33FFFFFF) },
                    LinearLayout.LayoutParams((1 * density).toInt().coerceAtLeast(1), ViewGroup.LayoutParams.MATCH_PARENT),
                )
                addView(rightImg, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            } else {
                addView(
                    leftImg,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        }
        val previewLabel = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding((14 * density).toInt(), (6 * density).toInt(), (14 * density).toInt(), (6 * density).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 16 * density
                setColor(0x99000000.toInt())
            }
        }
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0xE6000000.toInt())
            visibility = View.GONE
            addView(
                previewHolder,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(
                previewLabel,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                ).apply { bottomMargin = (24 * density).toInt() },
            )
            setOnClickListener { /* 预览时吃掉点击，别把面板点没了 */ }
        }

        var hovered = -1
        var longPressed = false
        var downX = 0f
        var downY = 0f
        var previewJob: Job? = null
        val slop = ViewConfiguration.get(this).scaledTouchSlop

        fun pageAt(x: Float, y: Float): Int {
            val child = grid.findChildViewUnder(x, y) ?: return -1
            val pos = grid.getChildAdapterPosition(child)
            return if (pos == RecyclerView.NO_POSITION) -1 else pages.getOrNull(pos) ?: -1
        }

        fun showPreview(page: Int) {
            hovered = page
            overlay.visibility = View.VISIBLE
            previewLabel.text = "第 ${page + 1} 页 / ${doc.pageCount}"
            previewJob?.cancel()
            previewJob = lifecycleScope.launch {
                // 横屏时每一半只占半屏宽，按半屏宽渲染就够 1:1 清晰
                val targetW = if (landscape) (dm.widthPixels / 2).coerceAtLeast(320) else dm.widthPixels
                val bmp = RenderEngine.thumb(doc, docId, page, targetW)
                if (bmp == null || hovered != page) return@launch
                if (landscape) {
                    val (top, bottom) = pageHalves(bmp)
                    leftImg.setImageBitmap(top)
                    rightImg.setImageBitmap(bottom)
                } else {
                    leftImg.setImageBitmap(bmp)
                }
            }
        }

        fun hidePreview() {
            hovered = -1
            previewJob?.cancel()
            leftImg.setImageDrawable(null)
            rightImg.setImageDrawable(null)
            overlay.visibility = View.GONE
            grid.scrollLocked = false
        }

        val longPress = Runnable {
            longPressed = true
            grid.scrollLocked = true
            for (i in 0 until grid.childCount) grid.getChildAt(i).isPressed = false
            vibrateLongPress()
            val p = pageAt(downX, downY)
            if (p >= 0) showPreview(p)
        }

        // RecyclerView.onInterceptTouchEvent 之前就被调用：按下先不抢，长按成立后再接管
        grid.touchInterceptor = { e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    longPressed = false
                    downX = e.x
                    downY = e.y
                    grid.postDelayed(longPress, 380L)
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!longPressed) {
                        if (abs(e.x - downX) > slop || abs(e.y - downY) > slop) {
                            grid.removeCallbacks(longPress)
                        }
                        false
                    } else {
                        val p = pageAt(e.x, e.y)
                        if (p >= 0 && p != hovered) showPreview(p)
                        true
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    grid.removeCallbacks(longPress)
                    if (longPressed) {
                        longPressed = false
                        hidePreview()
                        true // 吃掉松手，别触发跳页
                    } else {
                        false
                    }
                }

                else -> longPressed
            }
        }

        val container = FrameLayout(this)
        // 宽高都写死成"列数 × 格宽 / 行数 × 格高"：交给 wrap_content 时，RecyclerView 在
        // 奇数条（例如 5 条 = 2 列 3 行）会少算一行，第 5 张图就露在面板圆角外面。
        val gridW = cols * cellW + 2 * pad
        val gridH = rowsWanted * cellH + 2 * pad
        val contentH = dm.heightPixels - topInset - botInset
        container.addView(
            grid,
            FrameLayout.LayoutParams(gridW, gridH, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = topInset + ((contentH - gridH) / 2).coerceAtLeast(0)
            },
        )
        container.addView(
            overlay,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        // 纯 Dialog、无标题无按钮：面板贴着缩略图，点面板外面就关
        val dlg = Dialog(this)
        dlg.setContentView(container)
        dlg.window?.let { w ->
            w.setBackgroundDrawableResource(android.R.color.transparent)
            // 背景压暗一点：面板浮在页面上，不压暗就看不出"这是弹出来的东西"
            w.setDimAmount(0.55f)
            // 对话框窗口默认会被状态栏的 inset 挤小：窗口顶边不在屏幕顶边，
            // 于是"在窗口里居中"看起来就是偏下。窗口本身没法越过系统栏，所以
            // 这里先让它铺满可用区域，show() 之后再按真正的位置补偿（见下方 post）。
            w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            // 横屏时挖孔在左边缘，不声明这个模式，整个窗口会被往里挤 100 多 px——
            // 面板就成了"左边一条缝、右边贴边"（用户截图里正是这样）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                w.attributes = w.attributes.apply {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            WindowCompat.setDecorFitsSystemWindows(w, false)
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        container.setOnClickListener { dlg.dismiss() }
        grid.setOnClickListener { /* 网格内部的空白点击别把面板点没了 */ }
        dlg.setOnDismissListener {
            previewJob?.cancel()
            adapter.release()
            historyDialog = null
        }
        historyDialog = dlg
        dlg.show()
        // show() 之后主题可能又把尺寸改回 wrap_content，再钉一次
        dlg.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        // 阅读页是全屏的，这个对话框窗口却会让系统栏重新冒出来（顶上一整条状态栏
        // 压在缩略图上）。把系统栏也一起藏掉，弹层的观感才和阅读页一致。
        dlg.window?.let { w ->
            val controller = WindowCompat.getInsetsController(w, container)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // 窗口被状态栏顶下去了一截（窗口顶边 = 状态栏高度）。把面板上移一半、
        // 给放大预览的底部补上同样一截，两者在"整块屏幕"上才是居中的。
        // 注意要等窗口真正摆好位置再量：刚 show() 时 getLocationOnScreen 还是 0。
        var fixed = false
        val compensate = Runnable {
            if (!fixed) {
                val loc = IntArray(2)
                container.getLocationOnScreen(loc)
                val dy = loc[1]
                if (dy > 0) {
                    fixed = true
                    grid.translationY = -dy / 2f
                    previewHolder.setPadding(
                        previewHolder.paddingLeft,
                        previewHolder.paddingTop,
                        previewHolder.paddingRight,
                        previewHolder.paddingBottom + dy,
                    )
                }
            }
        }
        container.postDelayed(compensate, 80L)
        container.postDelayed(compensate, 350L)
    }

    /**
     * 跳转提示放在顶部（原来在底部的 Snackbar 会挡住正文）。
     * 显示"第 N 页"，并支持点一下回到跳转前的位置；4 秒后自动淡出。
     */
    private fun showJumpBanner(page: Int, from: Int) {
        val banner = b.topBanner
        banner.text = "第 ${page + 1} 页 · 点此回到第 ${from + 1} 页"
        banner.visibility = View.VISIBLE
        banner.alpha = 0f
        banner.animate().alpha(1f).setDuration(150).start()

        bannerHide?.let { banner.removeCallbacks(it) }
        val hide = Runnable {
            banner.animate().alpha(0f).setDuration(200)
                .withEndAction { banner.visibility = View.GONE }
                .start()
        }
        bannerHide = hide
        banner.postDelayed(hide, 4000)

        banner.setOnClickListener {
            banner.removeCallbacks(hide)
            banner.visibility = View.GONE
            if (from in 0 until doc.pageCount) jumpTo(from)
        }
    }

    private var bannerHide: Runnable? = null
}
