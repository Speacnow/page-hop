package com.pageflow.reader.ui

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.pageflow.reader.data.BookState
import com.pageflow.reader.data.CropMath
import com.pageflow.reader.data.CropRect
import com.pageflow.reader.data.Highlight
import com.pageflow.reader.data.InkStroke
import com.pageflow.reader.data.TextNote
import com.pageflow.reader.data.PageMetrics
import com.pageflow.reader.pdf.PdfDoc
import com.pageflow.reader.pdf.RenderEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 连续滚动的一页一页。每页在后台渲染，界面不卡；
 * 首次显示的页会顺带做一次裁边检测并把正文框回传上报。
 */
class PageAdapter(
    private val doc: PdfDoc,
    private val docId: String,
    private val state: BookState,
    private val screenWidth: () -> Int,
    private val aspects: () -> FloatArray,
    private val onTapPage: () -> Unit,
    /** 文字批注模式下的落点回调；为 null 时单击仍是"切换工具栏" */
    private val onTapAt: ((Int, Float, Float) -> Unit)? = null,
    /** 点到已有文字批注（编辑/删除） */
    private val onTextNoteClicked: ((TextNote) -> Unit)? = null,
    private val onDoubleTapPage: () -> Unit,
    private val onHighlightAdded: (Highlight) -> Unit,
    private val onStrokeAdded: (InkStroke) -> Unit,
    private val onHighlightClicked: (Highlight) -> Unit,
    private val onStrokeClicked: (InkStroke) -> Unit,
    private val onCropFound: (Int, CropRect) -> Unit,
) : RecyclerView.Adapter<PageAdapter.VH>() {

    var annotationColor: Int = 0xFF2196F3.toInt()

    /**
     * 渲染倍率：放大时按更高分辨率重画，避免放大后糊成一片。
     * 1.0 = 按屏宽渲染。
     */
    var renderScale: Float = 1f

    /**
     * true = 正在拖进度条快速扫页：先用很低的宽度渲染一张占位图，
     * 让画面立刻有内容（几十毫秒），松手后再按屏宽重画成清晰的。
     */
    var quick: Boolean = false

    /** true = 手写笔迹，false = 框选高亮。默认必须是 false，否则一打开就没法滚动 */
    var penMode: Boolean = false

    /** 整本书的正文框估计：先用它排版，避免每页高度在检测前后跳变 */
    private var bookContent: CropRect? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    class VH(val page: PageView) : RecyclerView.ViewHolder(page) {
        var job: Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val pv = PageView(parent.context)
        // 页面之间不留缝：整本书是连续的一片
        pv.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        return VH(pv)
    }

    override fun getItemCount(): Int = doc.pageCount

    override fun onBindViewHolder(holder: VH, position: Int) {
        val pv = holder.page
        // 同一页重新绑定时保留旧位图：缩放/改裁边后会重绑，若这里清空就会闪一下白
        val samePage = pv.pageIndex == position
        pv.pageIndex = position

        pv.highlights = state.highlights.filter { it.page == position }
        pv.strokes = state.strokes.filter { it.page == position }
        if (!samePage) {
            clearedBinds++
            pv.bitmap = null
            pv.errorText = null
        }
        pv.penMode = penMode
        pv.penColor = annotationColor

        val perPage = state.pageCrops[position]
        val analyze = state.cropEnabled && state.manualCrop == null && perPage == null
        val hint: CropRect? = when {
            !state.cropEnabled -> CropRect.FULL
            state.manualCrop != null -> state.manualCrop
            perPage != null -> CropMath.expand(perPage, state.cropPad)
            bookContent != null -> CropMath.expand(bookContent!!, state.cropPad)
            else -> null
        }
        pv.src = hint ?: CropRect.FULL

        // 页面高度要用"裁边之后"的比例算。
        // 如果拿 PDF 原始页比例（未裁边），每重绑一次页高就变约 10%，
        // 渲染回来又变回去 —— 缩放/换主题时那种"一闪"就是这么来的。
        val raw = aspects().getOrNull(position) ?: 0f
        pv.aspect = PageMetrics.displayAspect(raw, hint ?: CropRect.FULL)

        pv.textNotes = state.textNotes.filter { it.page == position }
        pv.onTapAt = onTapAt
        pv.onTextNoteClicked = onTextNoteClicked
        pv.onTap = { onTapPage() }
        pv.onDoubleTap = { onDoubleTapPage() }
        pv.onHighlightClicked = { onHighlightClicked(it) }
        pv.onStrokeClicked = { onStrokeClicked(it) }
        pv.onHighlightDrawn = { rect ->
            if (pv.pageIndex == position) {
                onHighlightAdded(
                    Highlight(
                        id = UUID.randomUUID().toString(),
                        page = position,
                        l = rect.left, t = rect.top, r = rect.right, b = rect.bottom,
                        color = annotationColor,
                    )
                )
            }
        }
        pv.onStrokeDrawn = { points, penWidth ->
            if (pv.pageIndex == position) {
                onStrokeAdded(
                    InkStroke(
                        id = UUID.randomUUID().toString(),
                        page = position,
                        points = points,
                        color = annotationColor,
                        width = penWidth,
                    )
                )
            }
        }

        holder.job?.cancel()
        holder.job = scope.launch {
            if (quick) {
                // 扫页中：只渲染一张低分辨率的占位图就收工。这里如果继续排全分辨率
                // 渲染，队列会被一帧一换的整页渲染堵死，反而什么都看不到（纯白）。
                val fast = RenderEngine.loadPage(
                    doc = doc,
                    docId = docId,
                    page = position,
                    screenWidth = 360,
                    cropHint = hint,
                    analyze = false,
                    pad = state.cropPad,
                )
                if (fast != null && pv.pageIndex == position) {
                    pv.aspect = fast.aspect
                    pv.src = fast.crop ?: CropRect.FULL
                    pv.bitmap = fast.bitmap
                }
                return@launch
            }
            val rendered = RenderEngine.loadPage(
                doc = doc,
                docId = docId,
                page = position,
                screenWidth = (screenWidth() * renderScale).toInt(),
                cropHint = hint,
                analyze = analyze,
                pad = state.cropPad,
            )
            if (rendered != null && pv.pageIndex == position) {
                if (analyze && rendered.content != null) {
                    if (bookContent == null) bookContent = rendered.content
                    onCropFound(position, rendered.content)
                }
                pv.aspect = rendered.aspect
                pv.src = rendered.crop ?: CropRect.FULL
                pv.bitmap = rendered.bitmap
            } else if (rendered == null && pv.pageIndex == position) {
                pv.errorText = "第 ${position + 1} 页渲染失败"
            }
        }
    }

    override fun onViewRecycled(holder: VH) {
        holder.job?.cancel()
        holder.job = null
        holder.page.bitmap = null
        holder.page.pageIndex = -1
        super.onViewRecycled(holder)
    }

    fun resolveCrop(page: Int): CropRect? {
        if (!state.cropEnabled) return CropRect.FULL
        state.manualCrop?.let { return it }
        val content = state.pageCrops[page] ?: bookContent ?: return null
        return CropMath.expand(content, state.cropPad)
    }

    fun refresh() {
        notifyDataSetChanged()
    }

    fun refreshPage(page: Int) {
        if (page in 0 until itemCount) notifyItemChanged(page)
    }

    fun release() {
        scope.cancel()
    }

    companion object {
        /** 调试计数：因重绑而清空位图的次数 */
        @JvmStatic
        var clearedBinds = 0
    }
}
